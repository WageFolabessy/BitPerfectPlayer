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
 *
 * Provides a fast header-only path for MediaStore tracks (`readMetadataForMediaStore`) that reads
 * direct `FileChannel` headers (`FlacStreamInfoParser` / `WavHeaderParser`) and skips heavyweight
 * `MediaMetadataRetriever` Binder IPC whenever cursor tags + direct header already supply
 * `sampleRateHz > 0` and `durationMs > 0`. Full `readMetadata` remains available for SAF files.
 */
class AudioMetadataReader(
    private val context: Context,
) {
    /**
     * Fast-path metadata reader for MediaStore tracks where `TITLE`, `ARTIST`, `ALBUM`, and
     * `DURATION` were already indexed by Android's MediaStore cursor. Skips `MediaMetadataRetriever`
     * completely for FLAC/WAV tracks once direct header parsing resolves `sampleRateHz` and
     * `durationMs`.
     */
    fun readMetadataForMediaStore(
        uri: Uri,
        fileName: String,
        mimeType: String? = null,
        cursorDurationMs: Long = 0L,
        cursorTitle: String? = null,
        cursorArtist: String? = null,
        cursorAlbum: String? = null,
    ): AudioTrackMetadata = readMetadataInternal(
        uri = uri,
        fileName = fileName,
        mimeType = mimeType,
        fallbackDurationMs = cursorDurationMs,
        cursorTitle = cursorTitle,
        cursorArtist = cursorArtist,
        cursorAlbum = cursorAlbum,
        allowSkipRetriever = true,
    )

    /**
     * Full metadata reader for SAF files where MediaStore columns are unavailable.
     */
    fun readMetadata(
        uri: Uri,
        fileName: String,
        mimeType: String? = null,
        fallbackDurationMs: Long = 0L,
    ): AudioTrackMetadata = readMetadataInternal(
        uri = uri,
        fileName = fileName,
        mimeType = mimeType,
        fallbackDurationMs = fallbackDurationMs,
        cursorTitle = null,
        cursorArtist = null,
        cursorAlbum = null,
        allowSkipRetriever = false,
    )

    private fun readMetadataInternal(
        uri: Uri,
        fileName: String,
        mimeType: String?,
        fallbackDurationMs: Long,
        cursorTitle: String?,
        cursorArtist: String?,
        cursorAlbum: String?,
        allowSkipRetriever: Boolean,
    ): AudioTrackMetadata {
        val fallbackTitle = AudioFormatClassifier.baseNameWithoutExtension(fileName)
            .ifBlank { "Unknown Track" }
        val classified = AudioFormatClassifier.classify(fileName, mimeType)

        var title = MetadataScanPolicy.sanitizeCursorTag(cursorTitle)
        var artist = MetadataScanPolicy.sanitizeCursorTag(cursorArtist)
        var album = MetadataScanPolicy.sanitizeCursorTag(cursorAlbum)
        var durationMs = fallbackDurationMs.coerceAtLeast(0L)
        var sampleRateHz = 0
        var bitDepth: Int? = null
        var channelCount = 2
        var codec = classified?.defaultCodecLabel ?: "Unknown"
        val container = classified?.containerLabel ?: "AUDIO"
        var headerVerified = false

        // 1. Fast direct lossless FileChannel header verification for WAV and FLAC (< 1 ms, zero Binder IPC)
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

        // 2. Skip MediaMetadataRetriever completely on MediaStore fast path when essential fields are present
        val hasCursorMetadata = allowSkipRetriever && (title != null || artist != null || album != null || fallbackDurationMs > 0L)
        val shouldSkipRetriever = allowSkipRetriever && MetadataScanPolicy.canSkipMediaMetadataRetriever(
            hasCursorMetadata = hasCursorMetadata,
            sampleRateHz = sampleRateHz,
            durationMs = durationMs,
        )

        if (!shouldSkipRetriever) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                if (title == null) {
                    title = MetadataScanPolicy.sanitizeCursorTag(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    )
                }
                if (artist == null) {
                    artist = MetadataScanPolicy.sanitizeCursorTag(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    )
                }
                if (album == null) {
                    album = MetadataScanPolicy.sanitizeCursorTag(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    )
                }

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
                // Gracefully fall back to cursor/filename & verified header info without crashing.
            } finally {
                runCatching { retriever.release() }
            }
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
