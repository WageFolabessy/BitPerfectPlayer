package com.cangkir.bitperfectplayer.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import com.cangkir.bitperfectplayer.lyrics.FileChannelSeekableByteSource
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native Android [MediaExtractor] + [MediaCodec] PCM decoder for FLAC (16/24-bit), MP3,
 * M4A (AAC), and OGG (Vorbis/Opus).
 *
 * Inspects the runtime decoded PCM encoding directly from [MediaCodec.getOutputFormat] so
 * diagnostics honestly reflect whether the OS decoder preserved 24-bit output or down-converted
 * to 16-bit PCM.
 */
class MediaCodecAudioDecoder private constructor(
    private val extractor: MediaExtractor,
    private val codec: MediaCodec,
    initialFormat: DecodedPcmFormat,
    initialPendingPcm: ByteArray?,
) : PcmAudioDecoder {

    private var _format: DecodedPcmFormat = initialFormat
    override val format: DecodedPcmFormat
        get() = _format

    private var _currentPositionMs: Long = 0L
    override val currentPositionMs: Long
        get() = _currentPositionMs

    private val closed = AtomicBoolean(false)
    private var inputEos = false
    private var outputEos = false

    private var pendingBytes: ByteArray? = initialPendingPcm
    private var pendingOffset: Int = 0
    private val bufferInfo = MediaCodec.BufferInfo()

    override fun readPcm(destination: ByteArray): Int {
        if (closed.get()) return -1

        // 1. Drain leftover buffered PCM bytes from a previous decoded MediaCodec frame.
        val pending = pendingBytes
        if (pending != null && pendingOffset < pending.size) {
            val toCopy = minOf(destination.size, pending.size - pendingOffset)
            System.arraycopy(pending, pendingOffset, destination, 0, toCopy)
            pendingOffset += toCopy
            if (pendingOffset >= pending.size) {
                pendingBytes = null
                pendingOffset = 0
            }
            return toCopy
        }

        if (outputEos) return -1

        try {
            var attempts = 0
            while (attempts < 64 && !closed.get()) {
                attempts++
                feedInputBufferIfAvailable()

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                when {
                    outIndex >= 0 -> {
                        val isEnd = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val size = bufferInfo.size
                        var copied = 0

                        if (size > 0) {
                            val outBuf = codec.getOutputBuffer(outIndex)
                            if (outBuf != null) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + size)
                                copied = minOf(destination.size, size)
                                outBuf.get(destination, 0, copied)
                                if (copied < size) {
                                    val leftover = ByteArray(size - copied)
                                    outBuf.get(leftover)
                                    pendingBytes = leftover
                                    pendingOffset = 0
                                }
                            }
                            if (bufferInfo.presentationTimeUs >= 0L) {
                                _currentPositionMs = bufferInfo.presentationTimeUs / 1000L
                            }
                        }

                        codec.releaseOutputBuffer(outIndex, false)

                        if (isEnd) {
                            outputEos = true
                            return if (copied > 0) copied else -1
                        }
                        if (copied > 0) return copied
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        updateFormatFromMediaFormat(codec.outputFormat)
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputEos && attempts > 16) {
                            outputEos = true
                            return -1
                        }
                    }
                }
            }
            return 0
        } catch (e: IllegalStateException) {
            throw AudioDecodeException.CorruptedFile("MediaCodec decode error: ${e.message}", e)
        } catch (e: MediaCodec.CodecException) {
            throw AudioDecodeException.CorruptedFile("Hardware/OS codec error: ${e.diagnosticInfo}", e)
        }
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
        } catch (e: Exception) {
            throw AudioDecodeException.IoError("Failed seeking audio stream: ${e.message}", e)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
        pendingBytes = null
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

    private fun updateFormatFromMediaFormat(outFormat: MediaFormat) {
        val sampleRate = outFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, _format.sampleRateHz)
        val channels = outFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, _format.channelCount)
        val androidPcmEnc = outFormat.getIntegerOrDefault(
            MediaFormat.KEY_PCM_ENCODING,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val encodingType = mapAndroidPcmEncoding(androidPcmEnc)
        _format = _format.copy(
            sampleRateHz = sampleRate,
            channelCount = channels,
            encoding = encodingType,
            effectiveBitDepth = encodingType.effectiveBitDepth,
        )
    }

    companion object {
        private const val DEQUEUE_TIMEOUT_US = 10_000L

        @Throws(AudioDecodeException::class)
        fun open(
            context: Context,
            uri: Uri,
            fileName: String?,
            mimeHint: String? = null,
        ): PcmAudioDecoder {
            val classified = AudioFormatClassifier.classify(fileName, mimeHint)
            if (classified == SupportedAudioFormat.WAV) {
                openDirectWavDecoder(context, uri)?.let { return it }
            }
            return openMediaCodecDecoder(context, uri, fileName, classified)
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
        ): MediaCodecAudioDecoder {
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

                val sourceSampleRate = flacInfo?.sampleRateHz
                    ?: trackFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44_100)
                val sourceChannels = flacInfo?.channelCount
                    ?: trackFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
                val durationMs = when {
                    trackFormat.containsKey(MediaFormat.KEY_DURATION) ->
                        (trackFormat.getLong(MediaFormat.KEY_DURATION) / 1000L).coerceAtLeast(0L)
                    flacInfo != null && flacInfo.durationMs > 0L -> flacInfo.durationMs
                    else -> 0L
                }
                val sourceBitDepth = flacInfo?.bitsPerSample ?: detectSourceBitDepthFromFormat(trackFormat)

                // Request higher precision PCM from MediaCodec for >16-bit lossless tracks where supported.
                if (sourceBitDepth != null && sourceBitDepth > 16) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        trackFormat.setInteger(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_24BIT_PACKED,
                        )
                    } else {
                        trackFormat.setInteger(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_FLOAT,
                        )
                    }
                }

                codec = createAndConfigureCodec(mime, trackFormat, sourceBitDepth)
                codec.start()

                val codecLabel = runCatching { codec.name }.getOrDefault("Android MediaCodec")
                val containerLabel = classifiedFormat?.containerLabel ?: containerLabelFromMime(mime)
                val sourceCodecLabel = codecLabelFromMime(mime)

                // Negotiate initial output format by pumping briefly so reported PCM bit depth is verified.
                val initialOutFormat = runCatching { codec.outputFormat }.getOrNull()
                val initialEnc = mapAndroidPcmEncoding(
                    initialOutFormat?.getIntegerOrDefault(
                        MediaFormat.KEY_PCM_ENCODING,
                        AudioFormat.ENCODING_PCM_16BIT,
                    ) ?: AudioFormat.ENCODING_PCM_16BIT,
                )

                val initialDecoded = DecodedPcmFormat(
                    sampleRateHz = initialOutFormat?.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sourceSampleRate)
                        ?: sourceSampleRate,
                    channelCount = initialOutFormat?.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, sourceChannels)
                        ?: sourceChannels,
                    encoding = initialEnc,
                    effectiveBitDepth = initialEnc.effectiveBitDepth,
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
                    initialFormat = initialDecoded,
                    initialPendingPcm = null,
                )
                // Prime first output format/frame so diagnostics immediately reflect actual MediaCodec PCM output.
                decoder.primeOutputFormat()
                return decoder
            } catch (e: AudioDecodeException) {
                runCatching { codec?.release() }
                runCatching { extractor.release() }
                throw e
            } catch (e: IOException) {
                runCatching { codec?.release() }
                runCatching { extractor.release() }
                throw AudioDecodeException.IoError("I/O error opening media source: ${e.message}", e)
            } catch (e: Exception) {
                runCatching { codec?.release() }
                runCatching { extractor.release() }
                throw AudioDecodeException.DecoderInitFailed("Failed to initialize decoder: ${e.message}", e)
            }
        }

        private fun createAndConfigureCodec(
            mime: String,
            trackFormat: MediaFormat,
            sourceBitDepth: Int?,
        ): MediaCodec {
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(trackFormat, null, null, 0)
                return codec
            } catch (firstFailure: Exception) {
                // If requesting high-precision PCM was rejected by a vendor codec, retry with default format
                // and let runtime outputFormat inspection honestly report actual output precision.
                if (sourceBitDepth != null && sourceBitDepth > 16) {
                    runCatching { codec.release() }
                    val fallbackCodec = MediaCodec.createDecoderByType(mime)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        trackFormat.removeKey(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        trackFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    }
                    fallbackCodec.configure(trackFormat, null, null, 0)
                    return fallbackCodec
                }
                runCatching { codec.release() }
                throw firstFailure
            }
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
                if (enc != null) return mapAndroidPcmEncoding(enc).effectiveBitDepth
            }
            return null
        }

        internal fun mapAndroidPcmEncoding(androidEncoding: Int): PcmEncodingType {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                when (androidEncoding) {
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> return PcmEncodingType.PCM_24BIT_PACKED
                    AudioFormat.ENCODING_PCM_32BIT -> return PcmEncodingType.PCM_32BIT
                }
            }
            return when (androidEncoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> PcmEncodingType.PCM_FLOAT
                else -> PcmEncodingType.PCM_16BIT
            }
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

    private fun primeOutputFormat() {
        val probeBuffer = ByteArray(16_384)
        var tries = 0
        while (tries < 12 && !outputEos) {
            tries++
            feedInputBufferIfAvailable()
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    updateFormatFromMediaFormat(codec.outputFormat)
                }
                outIndex >= 0 -> {
                    val size = bufferInfo.size
                    if (size > 0) {
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + size)
                            val saved = ByteArray(size)
                            outBuf.get(saved)
                            pendingBytes = saved
                            pendingOffset = 0
                        }
                    }
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputEos = true
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    break
                }
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (probeBuffer.isEmpty()) break
                }
            }
        }
    }
}
