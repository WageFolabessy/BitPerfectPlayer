package com.cangkir.bitperfectplayer.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.cangkir.bitperfectplayer.library.ArtistDetailHelper
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
    val discNumber: Int = 0,
    val trackNumber: Int = 0,
    val year: Int = 0,
)

/**
 * Local-only metadata & stream header reader (zero network access).
 *
 * Provides a fast header-only path for MediaStore tracks (`readMetadataForMediaStore`) that reads
 * direct `FileChannel` headers (`FlacStreamInfoParser` / `WavHeaderParser`) and skips heavyweight
 * `MediaMetadataRetriever` Binder IPC whenever cursor tags + direct header already supply
 * `sampleRateHz > 0` and `durationMs > 0`. Full `readMetadata` remains available for SAF files.
 */
class AudioMetadataReader(private val context: Context) {
    /**
     * Fast-path metadata reader for MediaStore tracks where `TITLE`, `ARTIST`, `ALBUM`, `TRACK`,
     * `YEAR`, and `DURATION` were already indexed by Android's MediaStore cursor. Skips
     * `MediaMetadataRetriever` completely for FLAC/WAV tracks once direct header parsing resolves
     * `sampleRateHz` and `durationMs`.
     */
    fun readMetadataForMediaStore(
        uri: Uri,
        fileName: String,
        mimeType: String? = null,
        cursorDurationMs: Long = 0L,
        cursorTitle: String? = null,
        cursorArtist: String? = null,
        cursorAlbum: String? = null,
        cursorPackedTrack: Int = 0,
        cursorCdTrackNumber: String? = null,
        cursorDiscNumber: String? = null,
        cursorYear: Int = 0,
    ): AudioTrackMetadata = readMetadataInternal(
        uri = uri,
        fileName = fileName,
        mimeType = mimeType,
        fallbackDurationMs = cursorDurationMs,
        cursor = CursorMetaInput(
            title = cursorTitle,
            artist = cursorArtist,
            album = cursorAlbum,
            packedTrack = cursorPackedTrack,
            cdTrackNumber = cursorCdTrackNumber,
            discNumber = cursorDiscNumber,
            year = cursorYear,
        ),
        allowSkipRetriever = true,
    )

    /**
     * Full metadata reader for SAF files where MediaStore columns are unavailable.
     */
    fun readMetadata(uri: Uri, fileName: String, mimeType: String? = null, fallbackDurationMs: Long = 0L): AudioTrackMetadata =
        readMetadataInternal(
            uri = uri,
            fileName = fileName,
            mimeType = mimeType,
            fallbackDurationMs = fallbackDurationMs,
            cursor = CursorMetaInput(),
            allowSkipRetriever = false,
        )

    private data class CursorMetaInput(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val packedTrack: Int = 0,
        val cdTrackNumber: String? = null,
        val discNumber: String? = null,
        val year: Int = 0,
    )

    private class MutableMetadataState(
        var title: String?,
        var artist: String?,
        var album: String?,
        var durationMs: Long,
        var sampleRateHz: Int = 0,
        var bitDepth: Int? = null,
        var channelCount: Int = 2,
        var codec: String,
        var headerVerified: Boolean = false,
        var headerDiscNumber: Int = 0,
        var headerTrackNumber: Int = 0,
        var trackNumberTag: String?,
        var discNumberTag: String?,
        var year: Int = 0,
    )

    private fun readMetadataInternal(
        uri: Uri,
        fileName: String,
        mimeType: String?,
        fallbackDurationMs: Long,
        cursor: CursorMetaInput,
        allowSkipRetriever: Boolean,
    ): AudioTrackMetadata {
        val fallbackTitle = AudioFormatClassifier.baseNameWithoutExtension(fileName)
            .ifBlank { "Unknown Track" }
        val classified = AudioFormatClassifier.classify(fileName, mimeType)
        val container = classified?.containerLabel ?: "AUDIO"

        val state = MutableMetadataState(
            title = MetadataScanPolicy.sanitizeCursorTag(cursor.title),
            artist = MetadataScanPolicy.sanitizeCursorTag(cursor.artist),
            album = MetadataScanPolicy.sanitizeCursorTag(cursor.album),
            durationMs = fallbackDurationMs.coerceAtLeast(0L),
            codec = classified?.defaultCodecLabel ?: "Unknown",
            trackNumberTag = cursor.cdTrackNumber,
            discNumberTag = cursor.discNumber,
            year = ArtistDetailHelper.sanitizeYear(cursor.year),
        )

        // 1. Fast direct lossless FileChannel header verification for WAV and FLAC (< 1 ms, zero Binder IPC)
        applyLosslessHeaderMetadata(uri, classified, state)

        // 2. Skip MediaMetadataRetriever completely on MediaStore fast path when essential fields are present
        val hasCursorTags = state.title != null || state.artist != null || state.album != null
        val hasCursorMetadata = allowSkipRetriever && (hasCursorTags || fallbackDurationMs > 0L)
        val shouldSkipRetriever = allowSkipRetriever &&
            MetadataScanPolicy.canSkipMediaMetadataRetriever(
                hasCursorMetadata = hasCursorMetadata,
                sampleRateHz = state.sampleRateHz,
                durationMs = state.durationMs,
            )

        if (!shouldSkipRetriever) {
            applyRetrieverMetadata(uri, state)
        }

        val resolvedPosition = TrackNumberParser.resolve(
            mediaStorePackedTrack = cursor.packedTrack,
            trackNumberTag = state.trackNumberTag,
            discNumberTag = state.discNumberTag,
            headerDiscNumber = state.headerDiscNumber,
            headerTrackNumber = state.headerTrackNumber,
            fileName = fileName,
        )

        return AudioTrackMetadata(
            title = state.title ?: fallbackTitle,
            artist = state.artist ?: "Unknown Artist",
            album = state.album ?: "Unknown Album",
            durationMs = state.durationMs,
            container = container,
            codec = state.codec,
            sampleRateHz = state.sampleRateHz,
            bitDepth = state.bitDepth,
            channelCount = state.channelCount,
            sourceHeaderVerified = state.headerVerified,
            discNumber = resolvedPosition.discNumber,
            trackNumber = resolvedPosition.trackNumber,
            year = state.year,
        )
    }

    private fun applyLosslessHeaderMetadata(uri: Uri, classified: SupportedAudioFormat?, state: MutableMetadataState) {
        when (classified) {
            SupportedAudioFormat.WAV -> {
                readWavHeaderSafely(uri)?.let { wav ->
                    state.sampleRateHz = wav.sampleRateHz
                    state.bitDepth = wav.bitsPerSample
                    state.channelCount = wav.channelCount
                    if (wav.durationMs > 0L) state.durationMs = wav.durationMs
                    state.codec = if (wav.isFloatPcm) "IEEE Float PCM" else "PCM"
                    state.headerVerified = true
                }
            }
            SupportedAudioFormat.FLAC -> {
                readFlacHeaderSafely(uri)?.let { flac ->
                    state.sampleRateHz = flac.sampleRateHz
                    state.bitDepth = flac.bitsPerSample
                    state.channelCount = flac.channelCount
                    if (flac.durationMs > 0L) state.durationMs = flac.durationMs
                    state.codec = "FLAC"
                    state.headerVerified = true
                    state.headerDiscNumber = flac.discNumber
                    state.headerTrackNumber = flac.trackNumber
                    if (state.year == 0 && flac.year > 0) {
                        state.year = flac.year
                    }
                }
            }
            else -> Unit
        }
    }

    private fun applyRetrieverMetadata(uri: Uri, state: MutableMetadataState) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            if (state.title == null) {
                state.title = MetadataScanPolicy.sanitizeCursorTag(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                )
            }
            if (state.artist == null) {
                state.artist = MetadataScanPolicy.sanitizeCursorTag(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                )
            }
            if (state.album == null) {
                state.album = MetadataScanPolicy.sanitizeCursorTag(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                )
            }
            if (state.trackNumberTag.isNullOrBlank()) {
                state.trackNumberTag = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            }
            if (state.discNumberTag.isNullOrBlank()) {
                state.discNumberTag = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
            }
            if (state.year == 0) {
                val rawYear = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                state.year = ArtistDetailHelper.parseReleaseYear(rawYear)
            }
            if (state.durationMs <= 0L) {
                state.durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (state.sampleRateHz <= 0) {
                    state.sampleRateHz = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                        ?.toIntOrNull() ?: 0
                }
                if (state.bitDepth == null) {
                    state.bitDepth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                        ?.toIntOrNull()?.takeIf { it > 0 }
                }
            }
        } catch (_: Exception) {
            // Gracefully fall back to cursor/filename & verified header info without crashing.
        } finally {
            runCatching { retriever.release() }
        }
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
