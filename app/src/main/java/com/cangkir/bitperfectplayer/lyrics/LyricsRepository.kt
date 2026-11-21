package com.cangkir.bitperfectplayer.lyrics

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.cangkir.bitperfectplayer.audio.AudioFormatClassifier
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.data.LocalPersistenceRepository
import com.cangkir.bitperfectplayer.data.LyricsRecord
import com.cangkir.bitperfectplayer.data.LyricsSourceType
import com.cangkir.bitperfectplayer.data.PersistenceSanitizer
import com.cangkir.bitperfectplayer.data.UserPreferencesRepository
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loaded lyrics state bound to a track's deterministic SHA-256 content fingerprint.
 */
data class TrackLyricsState(
    val contentFingerprint: String,
    val parsedLyrics: ParsedLyrics,
    val sourceType: LyricsSourceType?,
    val hasPersistedLyrics: Boolean,
    val statusMessage: String? = null,
)

/**
 * Pure-Kotlin helper matching sidecar `.lrc` filenames strictly by identical base filename.
 */
object LrcFileMatcher {
    fun expectedLrcFileName(audioFileName: String): String {
        val base = AudioFormatClassifier.baseNameWithoutExtension(audioFileName)
        return "$base.lrc"
    }

    fun isMatchingSidecarLrc(audioFileName: String, candidateFileName: String?): Boolean {
        if (candidateFileName.isNullOrBlank()) return false
        val trimmedCandidate = candidateFileName.trim()
        if (!trimmedCandidate.endsWith(".lrc", ignoreCase = true)) return false
        val audioBase = AudioFormatClassifier.baseNameWithoutExtension(audioFileName).trim()
        val lrcBase = trimmedCandidate.substring(0, trimmedCandidate.length - 4).trim()
        return audioBase.isNotEmpty() && audioBase.equals(lrcBase, ignoreCase = true)
    }
}

/**
 * Pure-Kotlin lyrics coordination logic (testable on JVM without Android framework).
 */
class LyricsCoordinator(
    private val loadStoredLyrics: suspend (fingerprint: String) -> LyricsRecord?,
    private val upsertStoredLyrics: suspend (record: LyricsRecord) -> Unit,
    private val deleteStoredLyrics: suspend (fingerprint: String) -> Boolean,
    private val resolveFingerprint: suspend (track: PlaybackTrack) -> String?,
    private val findAutoDetectLrcBytes: suspend (track: PlaybackTrack) -> ByteArray?,
    private val clockEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun loadLyricsForTrack(track: PlaybackTrack): TrackLyricsState? {
        val fp = resolveTrackFingerprint(track) ?: return null

        // 1. Check persisted Room database by deterministic content fingerprint first.
        val existing = loadStoredLyrics(fp)
        if (existing != null) {
            return TrackLyricsState(
                contentFingerprint = fp,
                parsedLyrics = LrcParser.parse(existing.rawLyricsText),
                sourceType = existing.sourceType,
                hasPersistedLyrics = true,
            )
        }

        // 2. Attempt offline auto-detection of sibling `<baseName>.lrc` inside accessible folders.
        val autoBytes = runCatching { findAutoDetectLrcBytes(track) }.getOrNull()
        if (autoBytes != null && autoBytes.isNotEmpty()) {
            val parsed = LrcParser.parseBytes(autoBytes)
            val record = LyricsRecord(
                contentFingerprint = fp,
                rawLyricsText = parsed.rawText,
                isSyncedLrc = parsed.isSynced,
                sourceType = LyricsSourceType.LRC_FILE,
                fileName = track.fileName,
                fileSizeBytes = track.fileSizeBytes,
                durationMs = track.durationMs,
                updatedAtEpochMs = clockEpochMs(),
            )
            upsertStoredLyrics(record)
            return TrackLyricsState(
                contentFingerprint = fp,
                parsedLyrics = parsed,
                sourceType = LyricsSourceType.LRC_FILE,
                hasPersistedLyrics = true,
                statusMessage = "Auto-detected matching .lrc file",
            )
        }

        return TrackLyricsState(
            contentFingerprint = fp,
            parsedLyrics = ParsedLyrics(
                lines = emptyList(),
                plainLines = emptyList(),
                isSynced = false,
                metadata = LrcMetadata(),
                rawText = "",
            ),
            sourceType = null,
            hasPersistedLyrics = false,
        )
    }

    suspend fun saveLrcBytes(track: PlaybackTrack, lrcBytes: ByteArray): Result<TrackLyricsState> = runCatching {
        val fp = resolveTrackFingerprint(track)
            ?: throw IOException("Unable to compute content fingerprint for ${track.fileName}")
        val parsed = LrcParser.parseBytes(lrcBytes)
        if (parsed.rawText.isBlank()) {
            throw IOException("Selected .lrc file is empty")
        }
        val record = LyricsRecord(
            contentFingerprint = fp,
            rawLyricsText = parsed.rawText,
            isSyncedLrc = parsed.isSynced,
            sourceType = LyricsSourceType.LRC_FILE,
            fileName = track.fileName,
            fileSizeBytes = track.fileSizeBytes,
            durationMs = track.durationMs,
            updatedAtEpochMs = clockEpochMs(),
        )
        upsertStoredLyrics(record)
        TrackLyricsState(
            contentFingerprint = fp,
            parsedLyrics = parsed,
            sourceType = LyricsSourceType.LRC_FILE,
            hasPersistedLyrics = true,
            statusMessage = if (parsed.isSynced) "Imported synced .lrc lyrics" else "Imported plain-text lyrics",
        )
    }

    suspend fun saveManualLyrics(track: PlaybackTrack, rawText: String): Result<TrackLyricsState> = runCatching {
        val fp = resolveTrackFingerprint(track)
            ?: throw IOException("Unable to compute content fingerprint for ${track.fileName}")
        val sanitized = PersistenceSanitizer.sanitizeLyricsText(rawText)
        if (sanitized.isBlank()) {
            deleteStoredLyrics(fp)
            return@runCatching TrackLyricsState(
                contentFingerprint = fp,
                parsedLyrics = LrcParser.parse(""),
                sourceType = null,
                hasPersistedLyrics = false,
                statusMessage = "Lyrics cleared",
            )
        }
        val parsed = LrcParser.parse(sanitized)
        val record = LyricsRecord(
            contentFingerprint = fp,
            rawLyricsText = parsed.rawText,
            isSyncedLrc = parsed.isSynced,
            sourceType = LyricsSourceType.MANUAL,
            fileName = track.fileName,
            fileSizeBytes = track.fileSizeBytes,
            durationMs = track.durationMs,
            updatedAtEpochMs = clockEpochMs(),
        )
        upsertStoredLyrics(record)
        TrackLyricsState(
            contentFingerprint = fp,
            parsedLyrics = parsed,
            sourceType = LyricsSourceType.MANUAL,
            hasPersistedLyrics = true,
            statusMessage = "Saved lyrics locally",
        )
    }

    suspend fun deleteLyrics(track: PlaybackTrack): Result<TrackLyricsState> = runCatching {
        val fp = resolveTrackFingerprint(track)
            ?: throw IOException("Unable to compute content fingerprint for ${track.fileName}")
        deleteStoredLyrics(fp)
        TrackLyricsState(
            contentFingerprint = fp,
            parsedLyrics = LrcParser.parse(""),
            sourceType = null,
            hasPersistedLyrics = false,
            statusMessage = "Lyrics deleted",
        )
    }

    private suspend fun resolveTrackFingerprint(track: PlaybackTrack): String? {
        val existing = track.contentFingerprint?.takeIf { PersistenceSanitizer.isValidSha256Fingerprint(it) }
        if (existing != null) return PersistenceSanitizer.normalizeFingerprint(existing)
        return resolveFingerprint(track)?.let { PersistenceSanitizer.normalizeFingerprint(it) }
    }
}

/**
 * 100% offline lyrics repository combining deterministic SHA-256 track fingerprinting,
 * Room local persistence, SAF `.lrc` import, sibling `.lrc` auto-detection, and manual editing.
 */
class LyricsRepository(
    context: Context,
    private val persistenceRepository: LocalPersistenceRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val fingerprintProvider: AndroidContentFingerprintProvider,
) {
    private val appContext = context.applicationContext

    private val coordinator = LyricsCoordinator(
        loadStoredLyrics = { fp -> persistenceRepository.getLyricsByFingerprint(fp) },
        upsertStoredLyrics = { rec -> persistenceRepository.upsertLyrics(rec) },
        deleteStoredLyrics = { fp -> persistenceRepository.deleteLyricsByFingerprint(fp) },
        resolveFingerprint = { track ->
            val computed = fingerprintProvider.computeFingerprint(track.uriString.toUri()).getOrNull()
            if (computed != null && track.contentFingerprint != computed) {
                runCatching {
                    persistenceRepository.updateCachedTrackFingerprint(
                        uriString = track.uriString,
                        fingerprint = computed,
                    )
                }
            }
            computed
        },
        findAutoDetectLrcBytes = { track -> findMatchingSidecarLrcBytes(track) },
    )

    suspend fun loadLyricsForTrack(track: PlaybackTrack): TrackLyricsState? = withContext(Dispatchers.IO) {
        coordinator.loadLyricsForTrack(track)
    }

    suspend fun importLrcFromSaf(track: PlaybackTrack, lrcUri: Uri): Result<TrackLyricsState> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = readUriBytesCapped(lrcUri)
                ?: throw IOException("Unable to read selected .lrc file")
            coordinator.saveLrcBytes(track, bytes).getOrThrow()
        }
    }

    suspend fun saveManualLyrics(track: PlaybackTrack, rawText: String): Result<TrackLyricsState> = withContext(Dispatchers.IO) {
        coordinator.saveManualLyrics(track, rawText)
    }

    suspend fun deleteLyrics(track: PlaybackTrack): Result<TrackLyricsState> = withContext(Dispatchers.IO) {
        coordinator.deleteLyrics(track)
    }

    private suspend fun findMatchingSidecarLrcBytes(track: PlaybackTrack): ByteArray? {
        val targetFileName = LrcFileMatcher.expectedLrcFileName(track.fileName)

        // 1. Search the track's own SAF tree first, followed by all other persisted SAF trees.
        val candidateTrees = linkedSetOf<String>()
        track.parentTreeUriString?.takeIf { it.isNotBlank() }?.let { candidateTrees.add(it) }
        runCatching { preferencesRepository.getSnapshot().persistedSafTreeUris }
            .getOrNull()
            ?.forEach { candidateTrees.add(it) }

        for (treeUriStr in candidateTrees) {
            val matchUri = findLrcInSafTree(treeUriStr.toUri(), track.fileName, depth = 0)
            if (matchUri != null) {
                readUriBytesCapped(matchUri)?.let { return it }
            }
        }

        // 2. Attempt MediaStore.Files lookup for `<baseName>.lrc` in the same relative folder if accessible.
        val mediaStoreUri = findLrcInMediaStore(targetFileName, track.relativePath)
        if (mediaStoreUri != null) {
            readUriBytesCapped(mediaStoreUri)?.let { return it }
        }

        return null
    }

    private fun findLrcInSafTree(treeUri: Uri, audioFileName: String, depth: Int): Uri? {
        val root = runCatching { DocumentFile.fromTreeUri(appContext, treeUri) }.getOrNull()
            ?: return null
        if (!root.exists() || !root.canRead()) return null
        return searchDocDirForLrc(root, audioFileName, depth)
    }

    private fun searchDocDirForLrc(dir: DocumentFile, audioFileName: String, depth: Int): Uri? {
        if (depth > MAX_LRC_SEARCH_DEPTH) return null
        val children = runCatching { dir.listFiles() }.getOrDefault(emptyArray())
        for (child in children) {
            if (child.isFile && child.canRead() && LrcFileMatcher.isMatchingSidecarLrc(audioFileName, child.name)) {
                return child.uri
            }
        }
        for (child in children) {
            if (child.isDirectory && child.canRead()) {
                searchDocDirForLrc(child, audioFileName, depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun findLrcInMediaStore(expectedLrcFileName: String, relativePath: String?): Uri? = try {
        val filesUri = MediaStore.Files.getContentUri("external")
        val projection = buildList {
            add(MediaStore.Files.FileColumns._ID)
            add(MediaStore.Files.FileColumns.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Files.FileColumns.RELATIVE_PATH)
            }
        }.toTypedArray()

        val selection: String
        val selectionArgs: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !relativePath.isNullOrBlank()) {
            selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ? AND ${MediaStore.Files.FileColumns.RELATIVE_PATH} = ?"
            selectionArgs = arrayOf(expectedLrcFileName, relativePath)
        } else {
            selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?"
            selectionArgs = arrayOf(expectedLrcFileName)
        }

        appContext.contentResolver.query(
            filesUri,
            projection,
            selection,
            selectionArgs,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                ContentUris.withAppendedId(filesUri, id)
            } else {
                null
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun readUriBytesCapped(uri: Uri): ByteArray? = runCatching {
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8_192)
            var total = 0
            while (total < MAX_LRC_FILE_BYTES) {
                val toRead = minOf(buf.size, MAX_LRC_FILE_BYTES - total)
                val read = input.read(buf, 0, toRead)
                if (read <= 0) break
                out.write(buf, 0, read)
                total += read
            }
            out.toByteArray()
        }
    }.getOrNull()

    companion object {
        private const val MAX_LRC_SEARCH_DEPTH = 6
        private const val MAX_LRC_FILE_BYTES = 2 * 1024 * 1024 // 2 MB cap prevents OOM on non-text files
    }
}
