package com.cangkir.bitperfectplayer.library

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.cangkir.bitperfectplayer.audio.AudioFormatClassifier
import com.cangkir.bitperfectplayer.audio.AudioMetadataReader
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.SupportedAudioFormat
import com.cangkir.bitperfectplayer.data.FavoriteTrackRecord
import com.cangkir.bitperfectplayer.data.LocalPersistenceRepository
import com.cangkir.bitperfectplayer.data.UserPreferencesRepository
import com.cangkir.bitperfectplayer.lyrics.AndroidContentFingerprintProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Local-first music library repository scanning the 5 MVP audio formats (FLAC, WAV, MP3, M4A/AAC,
 * OGG/Opus) via `MediaStore.Audio.Media` and Storage Access Framework (SAF) folders/files with
 * defensive persistable URI permission handling.
 */
class LocalLibraryRepository(
    context: Context,
    private val metadataReader: AudioMetadataReader,
    private val preferencesRepository: UserPreferencesRepository,
    private val persistenceRepository: LocalPersistenceRepository,
    private val fingerprintProvider: AndroidContentFingerprintProvider,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val scannedTracks = MutableStateFlow<List<PlaybackTrack>>(emptyList())
    private val isScanning = MutableStateFlow(false)
    private val scanWarning = MutableStateFlow<String?>(null)

    val librarySnapshot: StateFlow<LibrarySnapshot> = combine(
        scannedTracks,
        persistenceRepository.observeRecentlyPlayed(limit = 50),
        persistenceRepository.observeAllFavorites(),
        preferencesRepository.preferencesFlow,
        isScanning,
    ) { tracks, history, favorites, prefs, scanning ->
        LibraryProjector.buildSnapshot(
            allTracks = tracks,
            historyRecords = history,
            favoriteRecords = favorites,
            persistedSafTreeUris = prefs.persistedSafTreeUris,
            isScanning = scanning,
            scanWarning = scanWarning.value,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = LibrarySnapshot(),
    )

    suspend fun refreshLibrary() = withContext(Dispatchers.IO) {
        isScanning.value = true
        scanWarning.value = null
        try {
            val prefs = runCatching { preferencesRepository.getSnapshot() }.getOrNull()
            val mediaStoreList = scanMediaStore()
            val safTreeList = prefs?.persistedSafTreeUris
                ?.flatMap { uriStr -> scanSafTree(uriStr.toUri()) }
                .orEmpty()
            val safFileList = prefs?.persistedSafFileUris
                ?.mapNotNull { uriStr -> scanSingleSafFile(uriStr.toUri()) }
                .orEmpty()

            scannedTracks.value = LibraryProjector.mergeAndDeduplicate(
                mediaStoreTracks = mediaStoreList,
                safTracks = safTreeList + safFileList,
            )
        } catch (e: Exception) {
            scanWarning.value = "Library scan issue: ${e.message}"
        } finally {
            isScanning.value = false
        }
    }

    suspend fun importSafFolder(treeUri: Uri): Int = withContext(Dispatchers.IO) {
        takePersistableReadPermissionDefensively(treeUri)
        preferencesRepository.addPersistedSafTreeUri(treeUri.toString())
        val added = scanSafTree(treeUri)
        refreshLibrary()
        added.size
    }

    suspend fun importSafAudioFile(fileUri: Uri): PlaybackTrack? = withContext(Dispatchers.IO) {
        takePersistableReadPermissionDefensively(fileUri)
        val parsed = scanSingleSafFile(fileUri) ?: return@withContext null
        preferencesRepository.addPersistedSafFileUri(fileUri.toString())
        refreshLibrary()
        parsed
    }

    suspend fun removeSafFolder(treeUriString: String) = withContext(Dispatchers.IO) {
        preferencesRepository.removePersistedSafTreeUri(treeUriString)
        refreshLibrary()
    }

    suspend fun toggleFavorite(track: PlaybackTrack): Boolean = withContext(Dispatchers.IO) {
        val fp = track.contentFingerprint?.takeIf { it.isNotBlank() }
            ?: fingerprintProvider.computeFingerprint(track.uriString.toUri()).getOrNull()
            ?: return@withContext false

        // Cache resolved fingerprint into in-memory library list for instant UI updates.
        if (track.contentFingerprint == null) {
            scannedTracks.value = scannedTracks.value.map { item ->
                if (item.uriString == track.uriString) item.copy(contentFingerprint = fp) else item
            }
        }

        persistenceRepository.toggleFavorite(
            FavoriteTrackRecord(
                contentFingerprint = fp,
                trackUri = track.uriString,
                title = track.title,
                artist = track.artist,
                album = track.album,
                fileName = track.fileName,
                durationMs = track.durationMs,
                addedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun takePersistableReadPermissionDefensively(uri: Uri) {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun scanMediaStore(): List<PlaybackTrack> {
        val results = mutableListOf<PlaybackTrack>()
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.IS_MUSIC)
            add(MediaStore.Audio.Media.IS_RINGTONE)
            add(MediaStore.Audio.Media.IS_ALARM)
            add(MediaStore.Audio.Media.IS_NOTIFICATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(MediaStore.Audio.Media.IS_RECORDING)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
                add(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.Audio.Media.DATA)
            }
        }.toTypedArray()

        val selection = buildString {
            append("${MediaStore.Audio.Media.IS_RINGTONE} = 0 ")
            append("AND ${MediaStore.Audio.Media.IS_ALARM} = 0 ")
            append("AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                append(" AND ${MediaStore.Audio.Media.IS_RECORDING} = 0")
            }
        }
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        try {
            appContext.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val isMusicCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_MUSIC)
                val isRingtoneCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_RINGTONE)
                val isAlarmCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_ALARM)
                val isNotificationCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_NOTIFICATION)
                val isRecordingCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.IS_RECORDING)
                } else {
                    -1
                }

                val relPathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                } else {
                    -1
                }
                val bucketCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
                } else {
                    -1
                }
                @Suppress("DEPRECATION")
                val dataCol = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                } else {
                    -1
                }

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val displayName = cursor.getString(nameCol)?.trim().orEmpty()
                    val mimeType = cursor.getString(mimeCol)?.trim()
                    val format = AudioFormatClassifier.classify(displayName, mimeType) ?: continue

                    val sizeBytes = cursor.getLong(sizeCol).coerceAtLeast(0L)
                    val durationMs = cursor.getLong(durationCol).coerceAtLeast(0L)
                    val relativePath = if (relPathCol >= 0) cursor.getString(relPathCol)?.trim() else null
                    val bucketName = if (bucketCol >= 0) cursor.getString(bucketCol)?.trim() else null
                    val legacyDataPath = if (dataCol >= 0) cursor.getString(dataCol)?.trim() else null

                    val scanCandidate = MediaScanFilter.MediaStoreScanCandidate(
                        displayName = displayName,
                        relativePath = relativePath,
                        legacyDataPath = legacyDataPath,
                        bucketName = bucketName,
                        durationMs = durationMs,
                        sizeBytes = sizeBytes,
                        format = format,
                        isMusic = isMusicCol < 0 || cursor.getInt(isMusicCol) != 0,
                        isRingtone = isRingtoneCol >= 0 && cursor.getInt(isRingtoneCol) != 0,
                        isAlarm = isAlarmCol >= 0 && cursor.getInt(isAlarmCol) != 0,
                        isNotification = isNotificationCol >= 0 && cursor.getInt(isNotificationCol) != 0,
                        isRecording = isRecordingCol >= 0 && cursor.getInt(isRecordingCol) != 0,
                    )
                    if (!MediaScanFilter.shouldIncludeInAutoScan(scanCandidate)) continue

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id,
                    )

                    val rawTitle = cursor.getString(titleCol)?.trim()
                    val rawArtist = cursor.getString(artistCol)?.trim()
                    val rawAlbum = cursor.getString(albumCol)?.trim()

                    val legacyFolder = legacyDataPath
                        ?.substringBeforeLast('/', "")
                        ?.substringAfterLast('/', "")
                    val folderName = bucketName?.takeIf { it.isNotEmpty() }
                        ?: relativePath?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
                        ?: legacyFolder?.takeIf { it.isNotEmpty() }
                        ?: "Internal Storage"

                    val fallbackTitle = AudioFormatClassifier.baseNameWithoutExtension(displayName)
                        .ifBlank { "Unknown Track" }
                    val cleanTitle = rawTitle?.takeIf { it.isNotEmpty() && !it.equals("<unknown>", true) }
                        ?: fallbackTitle
                    val cleanArtist = rawArtist?.takeIf { it.isNotEmpty() && !it.equals("<unknown>", true) }
                        ?: "Unknown Artist"
                    val cleanAlbum = rawAlbum?.takeIf { it.isNotEmpty() && !it.equals("<unknown>", true) }
                        ?: "Unknown Album"

                    // For lossless WAV/FLAC, read exact header bit depth & sample rate.
                    var sampleRate = 0
                    var bitDepth: Int? = null
                    var resolvedDuration = durationMs
                    var codec = format.defaultCodecLabel
                    if (format == SupportedAudioFormat.WAV || format == SupportedAudioFormat.FLAC) {
                        val meta = metadataReader.readMetadata(
                            uri = contentUri,
                            fileName = displayName,
                            mimeType = mimeType,
                            fallbackDurationMs = durationMs,
                        )
                        sampleRate = meta.sampleRateHz
                        bitDepth = meta.bitDepth
                        if (resolvedDuration <= 0L) resolvedDuration = meta.durationMs
                        codec = meta.codec
                    }

                    results.add(
                        PlaybackTrack(
                            uriString = contentUri.toString(),
                            title = cleanTitle,
                            artist = cleanArtist,
                            album = cleanAlbum,
                            folderName = folderName,
                            fileName = displayName.ifBlank { "$cleanTitle.${format.containerLabel.lowercase()}" },
                            mimeType = mimeType,
                            fileSizeBytes = sizeBytes,
                            durationMs = resolvedDuration,
                            sampleRateHz = sampleRate,
                            bitDepth = bitDepth,
                            container = format.containerLabel,
                            codec = codec,
                            relativePath = relativePath,
                        ),
                    )
                }
            }
        } catch (_: SecurityException) {
            // Permission not granted yet; SAF folders/files remain accessible.
        }
        return results
    }

    private fun scanSafTree(treeUri: Uri): List<PlaybackTrack> {
        val rootDoc = runCatching { DocumentFile.fromTreeUri(appContext, treeUri) }.getOrNull()
            ?: return emptyList()
        if (!rootDoc.exists() || !rootDoc.canRead()) return emptyList()

        val found = mutableListOf<PlaybackTrack>()
        val rootFolderName = rootDoc.name?.takeIf { it.isNotBlank() } ?: "SAF Folder"
        traverseDocumentTree(
            dir = rootDoc,
            folderLabel = rootFolderName,
            treeUriString = treeUri.toString(),
            depth = 0,
            out = found,
        )
        return found
    }

    private fun traverseDocumentTree(
        dir: DocumentFile,
        folderLabel: String,
        treeUriString: String,
        depth: Int,
        out: MutableList<PlaybackTrack>,
    ) {
        if (depth > MAX_SAF_TRAVERSAL_DEPTH) return
        val children = runCatching { dir.listFiles() }.getOrDefault(emptyArray())
        for (child in children) {
            if (child.isDirectory) {
                val subName = child.name?.takeIf { it.isNotBlank() } ?: folderLabel
                traverseDocumentTree(child, subName, treeUriString, depth + 1, out)
            } else if (child.isFile && child.canRead()) {
                val fileName = child.name ?: continue
                val mime = child.type
                val format = AudioFormatClassifier.classify(fileName, mime) ?: continue
                val meta = metadataReader.readMetadata(child.uri, fileName, mime)
                out.add(
                    PlaybackTrack(
                        uriString = child.uri.toString(),
                        title = meta.title,
                        artist = meta.artist,
                        album = meta.album,
                        folderName = folderLabel,
                        fileName = fileName,
                        mimeType = mime,
                        fileSizeBytes = child.length().coerceAtLeast(0L),
                        durationMs = meta.durationMs,
                        sampleRateHz = meta.sampleRateHz,
                        bitDepth = meta.bitDepth,
                        container = format.containerLabel,
                        codec = meta.codec,
                        parentTreeUriString = treeUriString,
                    ),
                )
            }
        }
    }

    private fun scanSingleSafFile(fileUri: Uri): PlaybackTrack? {
        val doc = runCatching { DocumentFile.fromSingleUri(appContext, fileUri) }.getOrNull()
            ?: return null
        if (!doc.exists() || !doc.canRead()) return null
        val fileName = doc.name ?: fileUri.lastPathSegment?.substringAfterLast('/') ?: return null
        val mime = doc.type ?: appContext.contentResolver.getType(fileUri)
        val format = AudioFormatClassifier.classify(fileName, mime) ?: return null
        val meta = metadataReader.readMetadata(fileUri, fileName, mime)
        return PlaybackTrack(
            uriString = fileUri.toString(),
            title = meta.title,
            artist = meta.artist,
            album = meta.album,
            folderName = "Imported Files",
            fileName = fileName,
            mimeType = mime,
            fileSizeBytes = doc.length().coerceAtLeast(0L),
            durationMs = meta.durationMs,
            sampleRateHz = meta.sampleRateHz,
            bitDepth = meta.bitDepth,
            container = format.containerLabel,
            codec = meta.codec,
        )
    }

    companion object {
        private const val MAX_SAF_TRAVERSAL_DEPTH = 8
    }
}
