package com.cangkir.bitperfectplayer.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import com.cangkir.bitperfectplayer.diagnostics.DiagnosticLogFormatter
import com.cangkir.bitperfectplayer.diagnostics.LocalDiagnosticLogger
import com.cangkir.bitperfectplayer.lyrics.FileChannelSeekableByteSource
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Native Android [MediaExtractor] + [MediaCodec] PCM decoder for FLAC (16/24-bit), MP3,
 * M4A (AAC), and OGG (Vorbis/Opus).
 *
 * Critical PCM encoding guarantees:
 * - For high-bit-depth (>16-bit, e.g., 24-bit FLAC) sources, requests [AudioFormat.ENCODING_PCM_FLOAT]
 *   (preserves full 24-bit integer mantissa precision losslessly inside float32 and is natively
 *   supported by AOSP `c2.android.flac.decoder` and AudioFlinger).
 * - NEVER assumes `MediaCodec` honored the requested `KEY_PCM_ENCODING`, and NEVER falls back to
 *   the requested encoding when `KEY_PCM_ENCODING` is absent from `codec.outputFormat` (an omitted
 *   `KEY_PCM_ENCODING` in Android `MediaCodec` explicitly means [AudioFormat.ENCODING_PCM_16BIT]).
 * - Pumps the codec until `INFO_OUTPUT_FORMAT_CHANGED` or the first output buffer before returning
 *   from [open], so `AudioTrack` creation always uses the confirmed runtime `codec.outputFormat`.
 * - Every [readPcm] call respects exact `BufferInfo.offset`/`size` and returns whole PCM frames.
 */
class MediaCodecAudioDecoder private constructor(
    private val extractor: MediaExtractor,
    private val codec: MediaCodec,
    private val codecName: String,
    private val requestedEncoding: PcmEncodingType,
    initialFormat: DecodedPcmFormat,
) : PcmAudioDecoder {

    @Volatile
    private var _format: DecodedPcmFormat = initialFormat
    override val format: DecodedPcmFormat
        get() = _format

    private val _formatVersion = AtomicInteger(0)
    override val formatVersion: Int
        get() = _formatVersion.get()

    @Volatile
    private var _currentPositionMs: Long = 0L
    override val currentPositionMs: Long
        get() = _currentPositionMs

    private val closed = AtomicBoolean(false)
    private var inputEos = false
    private var outputEos = false
    private var outputFormatConfirmed = false

    private var pendingBytes: ByteArray? = null
    private var pendingOffset: Int = 0
    private val bufferInfo = MediaCodec.BufferInfo()

    override fun readPcm(destination: ByteArray): Int {
        if (closed.get()) return -1

        val bytesPerFrame = _format.bytesPerFrame.coerceAtLeast(1)
        val maxAlignedCopy = PcmStreamRules.alignByteCountToFrame(destination.size, bytesPerFrame)
        if (maxAlignedCopy <= 0) return 0

        val drained = drainPendingPcm(destination, bytesPerFrame, maxAlignedCopy)
        if (drained > 0) return drained
        if (outputEos) return -1

        return try {
            pumpDecoderUntilOutput(destination)
        } catch (e: MediaCodec.CodecException) {
            throw AudioDecodeException.CorruptedFile("Hardware/OS codec error: ${e.diagnosticInfo}", e)
        } catch (e: IllegalStateException) {
            throw AudioDecodeException.CorruptedFile("MediaCodec decode error: ${e.message}", e)
        }
    }

    private fun drainPendingPcm(destination: ByteArray, bytesPerFrame: Int, maxAlignedCopy: Int): Int {
        val pending = pendingBytes ?: return 0
        if (pendingOffset >= pending.size) return 0
        val available = pending.size - pendingOffset
        val toCopy = PcmStreamRules.alignByteCountToFrame(minOf(maxAlignedCopy, available), bytesPerFrame)
        if (toCopy <= 0) return 0
        System.arraycopy(pending, pendingOffset, destination, 0, toCopy)
        pendingOffset += toCopy
        if (pendingOffset >= pending.size) {
            pendingBytes = null
            pendingOffset = 0
        }
        return toCopy
    }

    private fun pumpDecoderUntilOutput(destination: ByteArray): Int {
        var attempts = 0
        while (attempts < 64 && !closed.get()) {
            attempts++
            feedInputBufferIfAvailable()

            val outIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            when {
                outIndex >= 0 -> processDequeuedOutputBuffer(outIndex, destination)?.let { return it }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    applyActualOutputFormat(codec.outputFormat, sourceTag = "INFO_OUTPUT_FORMAT_CHANGED")
                    // Return 0 so AudioOutputEngine can notice formatVersion change before next write if needed.
                    if (pendingBytes == null) return 0
                }
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (inputEos && attempts > 20) {
                        outputEos = true
                        return -1
                    }
                }
            }
        }
        return 0
    }

    private fun processDequeuedOutputBuffer(outIndex: Int, destination: ByteArray): Int? {
        val isEnd = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
        val extracted = extractAndReleaseOutputBuffer(outIndex)

        val combined = combineWithPending(extracted)
        val frameBytes = _format.bytesPerFrame.coerceAtLeast(1)
        val alignedDestMax = PcmStreamRules.alignByteCountToFrame(destination.size, frameBytes)
        val toCopy = PcmStreamRules.alignByteCountToFrame(
            minOf(alignedDestMax, combined.size),
            frameBytes,
        )

        if (toCopy > 0) {
            System.arraycopy(combined, 0, destination, 0, toCopy)
            if (toCopy < combined.size) {
                pendingBytes = combined.copyOfRange(toCopy, combined.size)
                pendingOffset = 0
            } else {
                pendingBytes = null
                pendingOffset = 0
            }
        } else if (combined.isNotEmpty()) {
            pendingBytes = combined
            pendingOffset = 0
        }

        return when {
            isEnd -> {
                outputEos = true
                if (toCopy > 0) toCopy else -1
            }
            toCopy > 0 -> toCopy
            else -> null
        }
    }

    private fun extractAndReleaseOutputBuffer(outIndex: Int): ByteArray {
        val size = bufferInfo.size
        var extracted = ByteArray(0)
        if (size > 0) {
            if (!outputFormatConfirmed) {
                // Ensure format reflects actual outputFormat even if OEM codec didn't emit INFO_OUTPUT_FORMAT_CHANGED first.
                applyActualOutputFormat(codec.outputFormat, sourceTag = "firstBuffer")
            }
            val outBuf = codec.getOutputBuffer(outIndex)
            if (outBuf != null) {
                extracted = PcmStreamRules.extractBufferSlice(
                    source = outBuf,
                    offset = bufferInfo.offset,
                    size = size,
                )
            }
            if (bufferInfo.presentationTimeUs >= 0L) {
                _currentPositionMs = bufferInfo.presentationTimeUs / 1000L
            }
        }
        codec.releaseOutputBuffer(outIndex, false)
        return extracted
    }

    override fun seekToMs(positionMs: Long): Long {
        if (closed.get()) return _currentPositionMs
        val clampedMs = positionMs.coerceIn(0L, format.durationMs.coerceAtLeast(0L))
        try {
            extractor.seekTo(clampedMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            codec.flush()
            pendingBytes = null
            pendingOffset = 0
            inputEos = false
            outputEos = false
            val sampleTimeUs = extractor.sampleTime
            _currentPositionMs = if (sampleTimeUs >= 0L) sampleTimeUs / 1000L else clampedMs
            return _currentPositionMs
        } catch (e: IllegalStateException) {
            throw AudioDecodeException.IoError("Failed seeking audio stream: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw AudioDecodeException.IoError("Failed seeking audio stream: ${e.message}", e)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
        pendingBytes = null
        pendingOffset = 0
    }

    private fun combineWithPending(incoming: ByteArray): ByteArray {
        val existing = pendingBytes
        val existingRem = if (existing != null && pendingOffset < existing.size) {
            existing.size - pendingOffset
        } else {
            0
        }
        if (existingRem == 0) return incoming
        if (incoming.isEmpty()) {
            return existing!!.copyOfRange(pendingOffset, existing.size)
        }
        val merged = ByteArray(existingRem + incoming.size)
        System.arraycopy(existing!!, pendingOffset, merged, 0, existingRem)
        System.arraycopy(incoming, 0, merged, existingRem, incoming.size)
        return merged
    }

    private fun feedInputBufferIfAvailable() {
        if (inputEos) return
        val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (inIndex < 0) return
        val inBuffer = codec.getInputBuffer(inIndex) ?: return
        val sampleSize = extractor.readSampleData(inBuffer, 0)
        if (sampleSize < 0) {
            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputEos = true
        } else {
            val presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
            codec.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0)
            extractor.advance()
        }
    }

    private fun applyActualOutputFormat(outFormat: MediaFormat, sourceTag: String) {
        val sampleRate = outFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, _format.sampleRateHz)
        val channels = outFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, _format.channelCount)
        val hasPcmKey = outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
        val reportedEncoding = if (hasPcmKey) {
            runCatching { outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) }.getOrNull()
        } else {
            null
        }
        // CRITICAL: Missing KEY_PCM_ENCODING strictly resolves to PCM_16BIT — NEVER requestedEncoding.
        val resolvedEncoding = PcmStreamRules.resolveMediaCodecOutputEncoding(
            hasPcmEncodingKey = hasPcmKey,
            reportedEncoding = reportedEncoding,
            supportsApi31Encodings = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        )

        val previous = _format
        val updated = previous.copy(
            sampleRateHz = sampleRate,
            channelCount = channels,
            encoding = resolvedEncoding,
            effectiveBitDepth = resolvedEncoding.effectiveBitDepth,
        )
        _format = updated
        outputFormatConfirmed = true
        if (previous.encoding != updated.encoding ||
            previous.sampleRateHz != updated.sampleRateHz ||
            previous.channelCount != updated.channelCount
        ) {
            _formatVersion.incrementAndGet()
        }

        Log.d(
            TAG,
            "MediaCodec outputFormat ($sourceTag): codec=$codecName, requested=$requestedEncoding, " +
                "hasPcmKey=$hasPcmKey, rawPcmKey=$reportedEncoding, resolved=${updated.encoding}, " +
                "sampleRate=${updated.sampleRateHz}Hz, channels=${updated.channelCount}",
        )
    }

    /**
     * Pumps initial input frames until `INFO_OUTPUT_FORMAT_CHANGED` and/or the first decoded PCM
     * buffer arrives so `AudioTrack` creation uses the confirmed runtime `codec.outputFormat`.
     */
    private fun primeOutputFormat() {
        var tries = 0
        while (tries < PRIME_MAX_ATTEMPTS && !outputEos) {
            tries++
            feedInputBufferIfAvailable()
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    applyActualOutputFormat(codec.outputFormat, sourceTag = "prime:INFO_OUTPUT_FORMAT_CHANGED")
                    // Stop priming as soon as format is confirmed AND we either got data or next step is audio loop.
                    // Continue briefly to also grab first buffer or exit cleanly if already confirmed.
                }
                outIndex >= 0 -> {
                    if (!outputFormatConfirmed) {
                        applyActualOutputFormat(codec.outputFormat, sourceTag = "prime:firstBuffer")
                    }
                    val size = bufferInfo.size
                    if (size > 0) {
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null) {
                            pendingBytes = PcmStreamRules.extractBufferSlice(
                                source = outBuf,
                                offset = bufferInfo.offset,
                                size = size,
                            )
                            pendingOffset = 0
                        }
                        if (bufferInfo.presentationTimeUs >= 0L) {
                            _currentPositionMs = bufferInfo.presentationTimeUs / 1000L
                        }
                    }
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputEos = true
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    break
                }
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (outputFormatConfirmed && tries >= 8) break
                }
            }
        }
        if (!outputFormatConfirmed) {
            // Even if codec produced no output during prime, inspect outputFormat strictly (defaulting absent key to PCM_16BIT).
            runCatching { codec.outputFormat }.getOrNull()?.let { fmt ->
                applyActualOutputFormat(fmt, sourceTag = "prime:fallback")
            }
        }
    }

    companion object {
        private const val TAG = "BitPerfectAudio"
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val PRIME_MAX_ATTEMPTS = 64

        @Throws(AudioDecodeException::class)
        fun open(
            context: Context,
            uri: Uri,
            fileName: String?,
            mimeHint: String? = null,
            diagnosticLogger: LocalDiagnosticLogger? = null,
        ): PcmAudioDecoder {
            val classified = AudioFormatClassifier.classify(fileName, mimeHint)
            return try {
                val decoder = if (classified == SupportedAudioFormat.WAV) {
                    openDirectWavDecoder(context, uri) ?: openMediaCodecDecoder(context, uri, fileName, classified)
                } else {
                    openMediaCodecDecoder(context, uri, fileName, classified)
                }
                diagnosticLogger?.logAudio("DECODER_CONFIG") {
                    val fmt = decoder.format
                    linkedMapOf(
                        "ext" to DiagnosticLogFormatter.extractSafeFileExtension(fileName, mimeHint),
                        "container" to fmt.sourceContainer,
                        "codec" to fmt.sourceCodec,
                        "decoder" to fmt.decoderName,
                        "srcSr" to fmt.sourceSampleRateHz,
                        "srcBit" to (fmt.sourceBitDepth ?: 0),
                        "outSr" to fmt.sampleRateHz,
                        "outCh" to fmt.channelCount,
                        "outEnc" to fmt.encoding.name,
                        "outBit" to fmt.effectiveBitDepth,
                        "verifiedHdr" to fmt.sourceHeaderVerified,
                    )
                }
                decoder
            } catch (e: AudioDecodeException) {
                diagnosticLogger?.logError("DECODER_OPEN_ERROR") {
                    linkedMapOf(
                        "ext" to DiagnosticLogFormatter.extractSafeFileExtension(fileName, mimeHint),
                        "errType" to e.javaClass.simpleName,
                        "msg" to e.message,
                    )
                }
                throw e
            }
        }

        private fun openDirectWavDecoder(context: Context, uri: Uri): WavPcmDecoder? {
            val pfd = try {
                context.contentResolver.openFileDescriptor(uri, "r")
            } catch (_: Exception) {
                return null
            } ?: return null

            var closePfdOnError = true
            try {
                val fis = FileInputStream(pfd.fileDescriptor)
                val channel = fis.channel
                val size = channel.size()
                if (size < 44L) {
                    fis.close()
                    return null
                }
                val source = object : com.cangkir.bitperfectplayer.lyrics.SeekableByteSource {
                    private val delegate = FileChannelSeekableByteSource(channel, size)
                    override val size: Long = size
                    override fun readAt(offset: Long, buffer: ByteArray, destOffset: Int, length: Int): Int =
                        delegate.readAt(offset, buffer, destOffset, length)

                    override fun close() {
                        runCatching { fis.close() }
                        runCatching { pfd.close() }
                    }
                }
                val supportsNative24And32 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                val decoder = WavPcmDecoder(source, supportsNative24And32BitPcm = supportsNative24And32)
                Log.d(
                    TAG,
                    "WavPcmDecoder opened: sourceBits=${decoder.header.bitsPerSample}, " +
                        "outputEncoding=${decoder.format.encoding}, sampleRate=${decoder.format.sampleRateHz}Hz, " +
                        "channels=${decoder.format.channelCount}",
                )
                closePfdOnError = false
                return decoder
            } catch (e: AudioDecodeException) {
                throw e
            } catch (e: IOException) {
                throw AudioDecodeException.IoError("Unable to open WAV file: ${e.message}", e)
            } finally {
                if (closePfdOnError) {
                    runCatching { pfd.close() }
                }
            }
        }

        private fun openMediaCodecDecoder(
            context: Context,
            uri: Uri,
            fileName: String?,
            classifiedFormat: SupportedAudioFormat?,
        ): PcmAudioDecoder {
            // Inspect FLAC header directly if applicable so source bit depth (16 vs 24) is verified.
            val flacInfo = if (classifiedFormat == SupportedAudioFormat.FLAC) {
                readFlacHeaderSafely(context, uri)
            } else {
                null
            }

            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(context, uri, null)
                val (trackFormat, mime) = selectFirstAudioTrack(extractor, fileName, uri)

                val sourceSampleRate = flacInfo?.sampleRateHz
                    ?: trackFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44_100)
                val sourceChannels = flacInfo?.channelCount
                    ?: trackFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
                val durationMs = resolveTrackDurationMs(trackFormat, flacInfo)

                // When MediaExtractor exposes audio/raw directly (e.g., Android 16 / Custom ROM
                // FlacExtractor decoding FLAC internally), NEVER force KEY_PCM_ENCODING=PCM_FLOAT
                // or route through c2.android.raw.decoder (which emits integer bytes tagged as
                // float32 -> white noise). Read raw PCM directly with verified source encoding.
                if (mime.equals(MediaFormat.MIMETYPE_AUDIO_RAW, ignoreCase = true)) {
                    return RawExtractorPcmDecoder.open(
                        extractor = extractor,
                        trackFormat = trackFormat,
                        sourceSampleRate = sourceSampleRate,
                        sourceChannels = sourceChannels,
                        durationMs = durationMs,
                        classifiedFormat = classifiedFormat,
                        flacInfo = flacInfo,
                    )
                }

                val sourceBitDepth = flacInfo?.bitsPerSample ?: detectSourceBitDepthFromFormat(trackFormat)

                // Request ENCODING_PCM_FLOAT for >16-bit compressed sources (preserves full 24-bit mantissa precision
                // losslessly and is natively supported by c2.android.flac.decoder and AudioFlinger).
                // For 16-bit sources (MP3, AAC, OGG, 16-bit FLAC), omit KEY_PCM_ENCODING so Android codecs
                // use their native 16-bit PCM default without vendor configure() rejections.
                val requestedPcm = PcmStreamRules.selectMediaCodecRequestEncoding(sourceBitDepth)
                var pcmEncodingKeySetByUs = false
                if (PcmStreamRules.shouldSetMediaCodecPcmEncodingKey(requestedPcm)) {
                    trackFormat.setInteger(
                        MediaFormat.KEY_PCM_ENCODING,
                        AudioFormat.ENCODING_PCM_FLOAT,
                    )
                    pcmEncodingKeySetByUs = true
                }

                codec = createAndConfigureCodec(
                    mime = mime,
                    trackFormat = trackFormat,
                    pcmEncodingKeySet = pcmEncodingKeySetByUs || trackFormat.containsKey(MediaFormat.KEY_PCM_ENCODING),
                )
                codec.start()

                val codecLabel = runCatching { codec.name }.getOrDefault("Android MediaCodec")
                val containerLabel = classifiedFormat?.containerLabel ?: containerLabelFromMime(mime)
                val sourceCodecLabel = codecLabelFromMime(mime)

                // Initialize conservatively to PCM_16BIT; primeOutputFormat() resolves the true runtime
                // encoding strictly from INFO_OUTPUT_FORMAT_CHANGED / first output buffer.
                val initialDecoded = DecodedPcmFormat(
                    sampleRateHz = sourceSampleRate,
                    channelCount = sourceChannels,
                    encoding = PcmEncodingType.PCM_16BIT,
                    effectiveBitDepth = PcmEncodingType.PCM_16BIT.effectiveBitDepth,
                    decoderName = "MediaCodec ($codecLabel)",
                    durationMs = durationMs,
                    sourceContainer = containerLabel,
                    sourceCodec = sourceCodecLabel,
                    sourceSampleRateHz = sourceSampleRate,
                    sourceBitDepth = sourceBitDepth,
                    sourceHeaderVerified = flacInfo != null,
                )

                val decoder = MediaCodecAudioDecoder(
                    extractor = extractor,
                    codec = codec,
                    codecName = codecLabel,
                    requestedEncoding = requestedPcm,
                    initialFormat = initialDecoded,
                )
                decoder.primeOutputFormat()
                return decoder
            } catch (e: AudioDecodeException) {
                releaseInitResources(codec, extractor)
                throw e
            } catch (e: IOException) {
                releaseInitResources(codec, extractor)
                throw AudioDecodeException.IoError("I/O error opening media source: ${e.message}", e)
            } catch (e: IllegalStateException) {
                failDecoderInit(codec, extractor, e)
            } catch (e: IllegalArgumentException) {
                failDecoderInit(codec, extractor, e)
            } catch (e: MediaCodec.CryptoException) {
                failDecoderInit(codec, extractor, e)
            } catch (e: SecurityException) {
                failDecoderInit(codec, extractor, e)
            }
        }

        private fun selectFirstAudioTrack(extractor: MediaExtractor, fileName: String?, uri: Uri): Pair<MediaFormat, String> {
            var audioTrackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    trackFormat = fmt
                    break
                }
            }
            if (audioTrackIndex < 0 || trackFormat == null) {
                throw AudioDecodeException.UnsupportedFormat(
                    "No supported audio track found in ${fileName ?: uri}",
                )
            }
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                ?: throw AudioDecodeException.UnsupportedFormat("Audio track has missing MIME type")
            validateAllowedMime(mime, fileName)
            extractor.selectTrack(audioTrackIndex)
            return trackFormat to mime
        }

        private fun resolveTrackDurationMs(trackFormat: MediaFormat, flacInfo: FlacStreamInfo?): Long = when {
            trackFormat.containsKey(MediaFormat.KEY_DURATION) ->
                (trackFormat.getLong(MediaFormat.KEY_DURATION) / 1000L).coerceAtLeast(0L)
            flacInfo != null && flacInfo.durationMs > 0L -> flacInfo.durationMs
            else -> 0L
        }

        private fun failDecoderInit(codec: MediaCodec?, extractor: MediaExtractor, cause: Throwable): Nothing {
            releaseInitResources(codec, extractor)
            throw AudioDecodeException.DecoderInitFailed("Failed to initialize decoder: ${cause.message}", cause)
        }

        private fun releaseInitResources(codec: MediaCodec?, extractor: MediaExtractor) {
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }

        private fun createAndConfigureCodec(mime: String, trackFormat: MediaFormat, pcmEncodingKeySet: Boolean): MediaCodec {
            val codec = MediaCodec.createDecoderByType(mime)
            val firstFailure = tryConfigureCodec(codec, trackFormat) ?: return codec
            runCatching { codec.release() }

            val hasPcmKey = pcmEncodingKeySet || trackFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
            if (!hasPcmKey) throw firstFailure

            val fallbackCodec = MediaCodec.createDecoderByType(mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                trackFormat.removeKey(MediaFormat.KEY_PCM_ENCODING)
            } else {
                trackFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            }
            val retryFailure = tryConfigureCodec(fallbackCodec, trackFormat) ?: return fallbackCodec
            runCatching { fallbackCodec.release() }
            throw retryFailure
        }

        private fun tryConfigureCodec(codec: MediaCodec, trackFormat: MediaFormat): Throwable? = try {
            codec.configure(trackFormat, null, null, 0)
            null
        } catch (e: IllegalArgumentException) {
            e
        } catch (e: IllegalStateException) {
            e
        } catch (e: MediaCodec.CryptoException) {
            e
        }

        private fun validateAllowedMime(mime: String, fileName: String?) {
            val lower = mime.lowercase()
            val allowed = lower == MediaFormat.MIMETYPE_AUDIO_FLAC ||
                lower == MediaFormat.MIMETYPE_AUDIO_MPEG ||
                lower == MediaFormat.MIMETYPE_AUDIO_AAC ||
                lower == MediaFormat.MIMETYPE_AUDIO_VORBIS ||
                lower == MediaFormat.MIMETYPE_AUDIO_OPUS ||
                lower == MediaFormat.MIMETYPE_AUDIO_RAW
            if (!allowed) {
                throw AudioDecodeException.UnsupportedFormat(
                    "Format '$mime' (${fileName ?: "unknown"}) is outside the 5 MVP supported formats (FLAC, WAV, MP3, M4A/AAC, OGG/Opus)",
                )
            }
        }

        private fun detectSourceBitDepthFromFormat(format: MediaFormat): Int? {
            if (format.containsKey("bits-per-sample")) {
                val bps = runCatching { format.getInteger("bits-per-sample") }.getOrNull()
                if (bps != null && bps > 0) return bps
            }
            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                val enc = runCatching { format.getInteger(MediaFormat.KEY_PCM_ENCODING) }.getOrNull()
                if (enc != null) {
                    return PcmStreamRules.mapAndroidPcmEncoding(
                        androidEncoding = enc,
                        supportsApi31Encodings = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                    ).effectiveBitDepth
                }
            }
            return null
        }

        private fun readFlacHeaderSafely(context: Context, uri: Uri): FlacStreamInfo? {
            val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return null
            return pfd.use { descriptor ->
                runCatching {
                    FileInputStream(descriptor.fileDescriptor).use { fis ->
                        FlacStreamInfoParser.parse(FileChannelSeekableByteSource(fis.channel))
                    }
                }.getOrNull()
            }
        }

        private fun containerLabelFromMime(mime: String): String = when (mime.lowercase()) {
            MediaFormat.MIMETYPE_AUDIO_FLAC -> "FLAC"
            MediaFormat.MIMETYPE_AUDIO_MPEG -> "MP3"
            MediaFormat.MIMETYPE_AUDIO_AAC -> "M4A"
            MediaFormat.MIMETYPE_AUDIO_VORBIS, MediaFormat.MIMETYPE_AUDIO_OPUS -> "OGG"
            MediaFormat.MIMETYPE_AUDIO_RAW -> "WAV"
            else -> "AUDIO"
        }

        private fun codecLabelFromMime(mime: String): String = when (mime.lowercase()) {
            MediaFormat.MIMETYPE_AUDIO_FLAC -> "FLAC"
            MediaFormat.MIMETYPE_AUDIO_MPEG -> "MP3"
            MediaFormat.MIMETYPE_AUDIO_AAC -> "AAC"
            MediaFormat.MIMETYPE_AUDIO_VORBIS -> "Vorbis"
            MediaFormat.MIMETYPE_AUDIO_OPUS -> "Opus"
            MediaFormat.MIMETYPE_AUDIO_RAW -> "PCM"
            else -> mime
        }

        private fun MediaFormat.getIntegerOrDefault(key: String, defaultValue: Int): Int =
            if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(defaultValue) else defaultValue
    }
}
