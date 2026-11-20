package com.cangkir.bitperfectplayer.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.cangkir.bitperfectplayer.lyrics.FileChannelSeekableByteSource
import java.io.FileInputStream

/**
 * Extracted local audio file metadata with graceful fallback to filename when tags are missing
 * or corrupted.
 */
data class AudioTrackMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val container: String,
    val codec: String,
    val sampleRateHz: Int,
    val bitDepth: Int?,
    val channelCount: Int,
    val sourceHeaderVerified: Boolean,
)

/**
 * Local-only metadata & stream header reader (zero network access).
 */
class AudioMetadataReader(
    private val context: Context,
) {
    fun readMetadata(
        uri: Uri,
        fileName: String,
        mimeType: String? = null,
        fallbackDurationMs: Long = 0L,
    ): AudioTrackMetadata {
        val fallbackTitle = AudioFormatClassifier.baseNameWithoutExtension(fileName)
            .ifBlank { "Unknown Track" }
        val classified = AudioFormatClassifier.classify(fileName, mimeType)

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var durationMs = fallbackDurationMs.coerceAtLeast(0L)
        var sampleRateHz = 0
        var bitDepth: Int? = null
        var channelCount = 2
        var codec = classified?.defaultCodecLabel ?: "Unknown"
        var container = classified?.containerLabel ?: "AUDIO"
        var headerVerified = false

        // 1. Direct lossless header verification for WAV and FLAC
        if (classified == SupportedAudioFormat.WAV) {
            readWavHeaderSafely(uri)?.let { wav ->
                sampleRateHz = wav.sampleRateHz
                bitDepth = wav.bitsPerSample
                channelCount = wav.channelCount
                if (wav.durationMs > 0L) durationMs = wav.durationMs
                codec = if (wav.isFloatPcm) "IEEE Float PCM" else "PCM"
                headerVerified = true
            }
        } else if (classified == SupportedAudioFormat.FLAC) {
            readFlacHeaderSafely(uri)?.let { flac ->
                sampleRateHz = flac.sampleRateHz
                bitDepth = flac.bitsPerSample
                channelCount = flac.channelCount
                if (flac.durationMs > 0L) durationMs = flac.durationMs
                codec = "FLAC"
                headerVerified = true
            }
        }

        // 2. Read ID3/Vorbis/MP4 tags via MediaMetadataRetriever safely
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.trim()?.takeIf { it.isNotEmpty() }
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.trim()?.takeIf { it.isNotEmpty() }
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.trim()?.takeIf { it.isNotEmpty() }

            if (durationMs <= 0L) {
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            }

            if (sampleRateHz <= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                sampleRateHz = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                    ?.toIntOrNull() ?: 0
            }
            if (bitDepth == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                bitDepth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                    ?.toIntOrNull()?.takeIf { it > 0 }
            }
        } catch (_: Exception) {
            // Gracefully fall back to filename & verified header info without crashing.
        } finally {
            runCatching { retriever.release() }
        }

        return AudioTrackMetadata(
            title = title ?: fallbackTitle,
            artist = artist ?: "Unknown Artist",
            album = album ?: "Unknown Album",
            durationMs = durationMs,
            container = container,
            codec = codec,
            sampleRateHz = sampleRateHz,
            bitDepth = bitDepth,
            channelCount = channelCount,
            sourceHeaderVerified = headerVerified,
        )
    }

    private fun readWavHeaderSafely(uri: Uri): WavHeaderInfo? {
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return null
        return pfd.use { descriptor ->
            runCatching {
                FileInputStream(descriptor.fileDescriptor).use { fis ->
                    WavHeaderParser.parse(FileChannelSeekableByteSource(fis.channel))
                }
            }.getOrNull()
        }
    }

    private fun readFlacHeaderSafely(uri: Uri): FlacStreamInfo? {
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return null
        return pfd.use { descriptor ->
            runCatching {
                FileInputStream(descriptor.fileDescriptor).use { fis ->
                    FlacStreamInfoParser.parse(FileChannelSeekableByteSource(fis.channel))
                }
            }.getOrNull()
        }
    }
}
