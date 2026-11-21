package com.cangkir.bitperfectplayer.library

import android.content.Context
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.cangkir.bitperfectplayer.audio.AudioFormatClassifier
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.ReplayGainMetadata
import com.cangkir.bitperfectplayer.audio.ReplayGainTagParser
import com.cangkir.bitperfectplayer.data.LocalPersistenceRepository
import com.cangkir.bitperfectplayer.lyrics.AndroidContentFingerprintProvider
import com.cangkir.bitperfectplayer.lyrics.FileChannelSeekableByteSource
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Complete on-demand technical inspection snapshot for a single local track, loaded lazily on
 * `Dispatchers.IO` ONLY when the Track Info bottom sheet opens (zero disk I/O during scrolling).
 */
data class TrackTechnicalInfo(
    val track: PlaybackTrack,
    val isLoadingDetails: Boolean = true,
    val fileSizeBytes: Long = track.fileSizeBytes,
    val replayGainMetadata: ReplayGainMetadata? = null,
    val contentFingerprint: String? = track.contentFingerprint,
    val playCount: Int = 0,
    val totalPlayTimeMs: Long = 0L,
    val lastPlayedAtEpochMs: Long? = null,
)

class TrackInfoLoader(
    context: Context,
    private val persistenceRepository: LocalPersistenceRepository,
    private val fingerprintProvider: AndroidContentFingerprintProvider,
) {
    private val appContext = context.applicationContext

    suspend fun loadFullDetails(track: PlaybackTrack): TrackTechnicalInfo = withContext(Dispatchers.IO) {
        val uri = track.uriString.toUri()

        val resolvedSize = if (track.fileSizeBytes > 0L) {
            track.fileSizeBytes
        } else {
            queryFileSizeBytes(uri)
        }

        val replayGain = readReplayGainHeaderOnly(uri, track.fileName, track.mimeType)

        val resolvedFingerprint = track.contentFingerprint?.takeIf { it.isNotBlank() }
            ?: fingerprintProvider.computeFingerprint(uri).getOrNull()

        if (resolvedFingerprint != null && track.contentFingerprint != resolvedFingerprint) {
            runCatching {
                persistenceRepository.updateCachedTrackFingerprint(
                    uriString = track.uriString,
                    fingerprint = resolvedFingerprint,
                )
            }
        }

        val stats = resolvedFingerprint?.let { fp ->
            runCatching { persistenceRepository.getTrackStatsByFingerprint(fp) }.getOrNull()
        }

        TrackTechnicalInfo(
            track = track,
            isLoadingDetails = false,
            fileSizeBytes = resolvedSize,
            replayGainMetadata = replayGain,
            contentFingerprint = resolvedFingerprint,
            playCount = stats?.playCount ?: 0,
            totalPlayTimeMs = stats?.totalPlayTimeMs ?: 0L,
            lastPlayedAtEpochMs = stats?.lastPlayedAtEpochMs?.takeIf { it > 0L },
        )
    }

    private fun queryFileSizeBytes(uri: android.net.Uri): Long = runCatching {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else 0L
            } else {
                0L
            }
        } ?: 0L
    }.getOrDefault(0L)

    private fun readReplayGainHeaderOnly(uri: android.net.Uri, fileName: String, mimeType: String?): ReplayGainMetadata? = runCatching {
        val formatHint = AudioFormatClassifier.classify(fileName, mimeType)
        appContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { fis ->
                val channel = fis.channel
                val size = runCatching { channel.size() }.getOrDefault(-1L)
                if (size > 0L) {
                    ReplayGainTagParser.parse(
                        source = FileChannelSeekableByteSource(channel, size),
                        formatHint = formatHint,
                    )
                } else {
                    null
                }
            }
        }
    }.getOrNull()
}
