package com.cangkir.bitperfectplayer.library

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.cangkir.bitperfectplayer.audio.AudioFormatClassifier
import com.cangkir.bitperfectplayer.audio.AudioMetadataReader
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.SupportedAudioFormat
import com.cangkir.bitperfectplayer.audio.TrackNumberParser
import com.cangkir.bitperfectplayer.data.CachedTrackEntity
import com.cangkir.bitperfectplayer.data.CachedTrackSourceKind
import com.cangkir.bitperfectplayer.data.FavoriteTrackRecord
import com.cangkir.bitperfectplayer.data.LocalPersistenceRepository
import com.cangkir.bitperfectplayer.data.TrackPositionBackfill
import com.cangkir.bitperfectplayer.data.TrackYearBackfill
import com.cangkir.bitperfectplayer.data.UserPreferences
import com.cangkir.bitperfectplayer.data.UserPreferencesRepository
import com.cangkir.bitperfectplayer.data.toCachedEntity
import com.cangkir.bitperfectplayer.data.toPlaybackTrack
import com.cangkir.bitperfectplayer.lyrics.AndroidContentFingerprintProvider
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Local-first music library repository backed by Room (`cached_tracks`) so cached songs render
 * immediately on app launch with zero empty-screen flash, followed by incremental background diffing
 * (`uri` + `size` + `dateModified`) across `MediaStore.Audio.Media` and persisted SAF folders/files.
 *
 * Also coordinates instant optimistic heart-icon favorite toggling backed by Room and executes all
 * context-aware sorting strictly on `Dispatchers.Default` for jank-free 120 Hz scrolling.
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
    private val syncMutex = Mutex()

    private val isScanning = MutableStateFlow(false)
    private val scanWarning = MutableStateFlow<String?>(null)
    private val optimisticFavorites = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    private val combinedTracksFlow: Flow<List<PlaybackTrack>> =
        persistenceRepository.observeCachedTracks().map { entities ->
            val mediaTracks = mutableListOf<PlaybackTrack>()
            val safTracks = mutableListOf<PlaybackTrack>()
            for (entity in entities) {
                val track = entity.toPlaybackTrack()
                if (CachedTrackSourceKind.fromStoredName(entity.sourceKind) == CachedTrackSourceKind.MEDIA_STORE) {
                    mediaTracks.add(track)
                } else {
                    safTracks.add(track)
                }
            }
            LibraryProjector.mergeAndDeduplicate(
                mediaStoreTracks = mediaTracks,
                safTracks = safTracks,
            )
        }

    private val playCountsFlow: Flow<Map<String, Int>> =
        persistenceRepository.observeAllTrackStats().map { stats ->
            if (stats.isEmpty()) {
                emptyMap()
            } else {
                HashMap<String, Int>(stats.size * 2).apply {
                    for (rec in stats) {
                        if (rec.playCount > 0) {
                            put(rec.contentFingerprint.lowercase(), rec.playCount)
                            if (rec.trackUri.isNotBlank()) {
                                putIfAbsent(rec.trackUri, rec.playCount)
                            }
                        }
                    }
                }
            }
        }

    val librarySnapshot: StateFlow<LibrarySnapshot> = combine(
        combinedTracksFlow,
        persistenceRepository.observeRecentlyPlayed(limit = 50),
        persistenceRepository.observeAllFavorites(),
        preferencesRepository.preferencesFlow,
        combine(isScanning, optimisticFavorites, playCountsFlow) { scanning, optimistic, playCounts ->
            Triple(scanning, optimistic, playCounts)
        },
    ) { tracks, history, favorites, prefs, (scanning, optimistic, playCounts) ->
        LibraryProjector.buildSnapshot(
            allTracks = tracks,
            historyRecords = history,
            favoriteRecords = favorites,
            optimisticFavorites = optimistic,
            persistedSafTreeUris = prefs.persistedSafTreeUris,
            sortPreferences = prefs.sortPreferences,
            playCountsByKey = playCounts,
            isScanning = scanning,
            scanWarning = scanWarning.value,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = LibrarySnapshot(),
        )

    private data class DiscoveredLibrarySnapshot(
        val mediaStorePass: MediaStoreDiscoveryResult,
        val safTreeEntries: List<DiscoveredSafEntry>,
        val safFileEntries: List<DiscoveredSafEntry>,
        val signatures: List<DiscoveredAudioSignature>,
        val scannedSources: Set<CachedTrackSourceKind>,
    )

    private data class SyncMutations(
        val entitiesToUpsert: List<CachedTrackEntity>,
        val positionBackfills: List<TrackPositionBackfill>,
        val yearBackfills: List<TrackYearBackfill>,
    )

    /**
     * Runs an incremental background library synchronization without ever clearing the displayed
     * track list. Only newly added or modified (`size` / `dateModified`) files incur metadata parsing.
     */
    suspend fun refreshLibrary() = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            isScanning.value = true
            scanWarning.value = null
            try {
                syncLibraryOnceLocked()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: IOException) {
                scanWarning.value = "Library sync issue: ${e.message}"
            } catch (e: SecurityException) {
                scanWarning.value = "Library sync issue: ${e.message}"
            } catch (e: SQLiteException) {
                scanWarning.value = "Library sync issue: ${e.message}"
            } catch (e: IllegalStateException) {
                scanWarning.value = "Library sync issue: ${e.message}"
            } catch (e: IllegalArgumentException) {
                scanWarning.value = "Library sync issue: ${e.message}"
            } finally {
                isScanning.value = false
            }
        }
    }

    private suspend fun syncLibraryOnceLocked() {
        val cachedEntities = persistenceRepository.getAllCachedTracks()
        val cachedByUri = cachedEntities.associateBy { it.uriString }
        val cachedSignatures = cachedEntities.map { entity ->
            CachedTrackSignature(
                track = entity.toPlaybackTrack(),
                sourceKind = CachedTrackSourceKind.fromStoredName(entity.sourceKind),
            )
        }

        val prefs = runCatching { preferencesRepository.getSnapshot() }.getOrNull()
        val discovered = discoverLibrarySnapshot(prefs)
        val plan = LibrarySyncDiffer.computePlan(
            cached = cachedSignatures,
            discovered = discovered.signatures,
            scannedSourceKinds = discovered.scannedSources,
        )
        val mutations = collectSyncMutations(
            discovered = discovered,
            cachedByUri = cachedByUri,
            toParseUris = plan.toParseUris,
            nowEpoch = System.currentTimeMillis(),
        )

        if (mutations.entitiesToUpsert.isNotEmpty()) {
            persistenceRepository.upsertCachedTracks(mutations.entitiesToUpsert)
        }
        if (mutations.positionBackfills.isNotEmpty()) {
            persistenceRepository.backfillCachedTrackPositions(mutations.positionBackfills)
        }
        if (mutations.yearBackfills.isNotEmpty()) {
            persistenceRepository.backfillCachedTrackYears(mutations.yearBackfills)
        }
        if (plan.toPruneUris.isNotEmpty()) {
            persistenceRepository.deleteCachedTracksByUris(plan.toPruneUris)
        }
    }

    private fun discoverLibrarySnapshot(prefs: UserPreferences?): DiscoveredLibrarySnapshot {
        val signatures = mutableListOf<DiscoveredAudioSignature>()
        val scannedSources = linkedSetOf<CachedTrackSourceKind>()

        val mediaStorePass = discoverMediaStoreCandidates()
        if (mediaStorePass.permissionGranted) {
            scannedSources.add(CachedTrackSourceKind.MEDIA_STORE)
            mediaStorePass.candidates.forEach { item ->
                signatures.add(
                    DiscoveredAudioSignature(
                        uriString = item.uriString,
                        fileSizeBytes = item.sizeBytes,
                        dateModifiedSeconds = item.dateModifiedSeconds,
                        sourceKind = CachedTrackSourceKind.MEDIA_STORE,
                    ),
                )
            }
        }

        val safTreeEntries = buildList {
            prefs?.persistedSafTreeUris?.forEach { uriStr ->
                addAll(discoverSafTreeCandidates(uriStr.toUri()))
            }
        }
        scannedSources.add(CachedTrackSourceKind.SAF_TREE)
        safTreeEntries.forEach { entry ->
            signatures.add(entry.toDiscoveredSignature(CachedTrackSourceKind.SAF_TREE))
        }

        val safFileEntries = buildList {
            prefs?.persistedSafFileUris?.forEach { uriStr ->
                discoverSingleSafFileCandidate(uriStr.toUri())?.let { add(it) }
            }
        }
        scannedSources.add(CachedTrackSourceKind.SAF_FILE)
        safFileEntries.forEach { entry ->
            signatures.add(entry.toDiscoveredSignature(CachedTrackSourceKind.SAF_FILE))
        }

        return DiscoveredLibrarySnapshot(
            mediaStorePass = mediaStorePass,
            safTreeEntries = safTreeEntries,
            safFileEntries = safFileEntries,
            signatures = signatures,
            scannedSources = scannedSources,
        )
    }

    private fun DiscoveredSafEntry.toDiscoveredSignature(sourceKind: CachedTrackSourceKind): DiscoveredAudioSignature =
        DiscoveredAudioSignature(
            uriString = uriString,
            fileSizeBytes = sizeBytes,
            dateModifiedSeconds = dateModifiedSeconds,
            sourceKind = sourceKind,
        )

    private fun collectSyncMutations(
        discovered: DiscoveredLibrarySnapshot,
        cachedByUri: Map<String, CachedTrackEntity>,
        toParseUris: Set<String>,
        nowEpoch: Long,
    ): SyncMutations {
        val entitiesToUpsert = mutableListOf<CachedTrackEntity>()
        val positionBackfills = mutableListOf<TrackPositionBackfill>()
        val yearBackfills = mutableListOf<TrackYearBackfill>()

        for (candidate in discovered.mediaStorePass.candidates) {
            val existing = cachedByUri[candidate.uriString]
            if (candidate.uriString in toParseUris) {
                entitiesToUpsert.add(
                    parseMediaStoreCandidate(candidate, existingFingerprint = existing?.contentFingerprint)
                        .toCachedEntity(CachedTrackSourceKind.MEDIA_STORE, nowEpoch),
                )
            } else if (existing != null) {
                if (existing.discNumber == 0 && existing.trackNumber == 0) {
                    val resolved = TrackNumberParser.resolve(
                        mediaStorePackedTrack = candidate.packedTrack,
                        trackNumberTag = candidate.cdTrackNumber,
                        discNumberTag = candidate.discNumber,
                        fileName = candidate.displayName,
                    )
                    if (resolved.hasPosition) {
                        positionBackfills.add(
                            TrackPositionBackfill(
                                uriString = candidate.uriString,
                                discNumber = resolved.discNumber,
                                trackNumber = resolved.trackNumber,
                            ),
                        )
                    }
                }
                if (existing.year == 0 && candidate.year > 0) {
                    yearBackfills.add(TrackYearBackfill(uriString = candidate.uriString, year = candidate.year))
                }
            }
        }

        collectSafSyncMutations(
            entries = discovered.safTreeEntries,
            sourceKind = CachedTrackSourceKind.SAF_TREE,
            cachedByUri = cachedByUri,
            toParseUris = toParseUris,
            nowEpoch = nowEpoch,
            entitiesToUpsert = entitiesToUpsert,
            positionBackfills = positionBackfills,
        )
        collectSafSyncMutations(
            entries = discovered.safFileEntries,
            sourceKind = CachedTrackSourceKind.SAF_FILE,
            cachedByUri = cachedByUri,
            toParseUris = toParseUris,
            nowEpoch = nowEpoch,
            entitiesToUpsert = entitiesToUpsert,
            positionBackfills = positionBackfills,
        )

        return SyncMutations(
            entitiesToUpsert = entitiesToUpsert,
            positionBackfills = positionBackfills,
            yearBackfills = yearBackfills,
        )
    }

    private fun collectSafSyncMutations(
        entries: List<DiscoveredSafEntry>,
        sourceKind: CachedTrackSourceKind,
        cachedByUri: Map<String, CachedTrackEntity>,
        toParseUris: Set<String>,
        nowEpoch: Long,
        entitiesToUpsert: MutableList<CachedTrackEntity>,
        positionBackfills: MutableList<TrackPositionBackfill>,
    ) {
        for (entry in entries) {
            val existing = cachedByUri[entry.uriString]
            if (entry.uriString in toParseUris) {
                entitiesToUpsert.add(
                    parseSafEntry(entry, existingFingerprint = existing?.contentFingerprint)
                        .toCachedEntity(sourceKind, nowEpoch),
                )
            } else if (existing != null && existing.discNumber == 0 && existing.trackNumber == 0) {
                val resolved = TrackNumberParser.parseFilenamePosition(entry.fileName)
                if (resolved.hasPosition) {
                    positionBackfills.add(
                        TrackPositionBackfill(
                            uriString = entry.uriString,
                            discNumber = resolved.discNumber,
                            trackNumber = resolved.trackNumber,
                        ),
                    )
                }
            }
        }
    }

    suspend fun importSafFolder(treeUri: Uri): Int = withContext(Dispatchers.IO) {
        takePersistableReadPermissionDefensively(treeUri)
        preferencesRepository.addPersistedSafTreeUri(treeUri.toString())
        val discovered = discoverSafTreeCandidates(treeUri)
        refreshLibrary()
        discovered.size
    }

    suspend fun importSafAudioFile(fileUri: Uri): PlaybackTrack? = withContext(Dispatchers.IO) {
        takePersistableReadPermissionDefensively(fileUri)
        val candidate = discoverSingleSafFileCandidate(fileUri) ?: return@withContext null
        val parsed = parseSafEntry(candidate, existingFingerprint = null)
        preferencesRepository.addPersistedSafFileUri(fileUri.toString())
        persistenceRepository.upsertCachedTracks(
            listOf(parsed.toCachedEntity(CachedTrackSourceKind.SAF_FILE, System.currentTimeMillis())),
        )
        parsed
    }

    suspend fun removeSafFolder(treeUriString: String) = withContext(Dispatchers.IO) {
        preferencesRepository.removePersistedSafTreeUri(treeUriString)
        refreshLibrary()
    }

    /**
     * Immediately toggles the heart icon via optimistic state (next-frame UI update across Library,
     * Now Playing, and Favorites tab) and persists to Room asynchronously on `Dispatchers.IO`,
     * rolling back only if fingerprint/DB persistence fails.
     */
    suspend fun toggleFavorite(track: PlaybackTrack): Result<Boolean> {
        val currentSnapshot = librarySnapshot.value
        val currentlyFavorite = FavoriteStateReducer.isFavorited(
            track = track,
            favoriteUris = currentSnapshot.favoriteUris,
            favoriteFingerprints = currentSnapshot.favoriteFingerprints,
        )
        val targetFavorite = !currentlyFavorite

        // 1. Immediate optimistic UI update (zero wait for SHA-256 or disk I/O).
        optimisticFavorites.update { it + (track.uriString to targetFavorite) }

        return withContext(Dispatchers.IO) {
            try {
                val fp = track.contentFingerprint?.takeIf { it.isNotBlank() }
                    ?: fingerprintProvider.computeFingerprint(track.uriString.toUri()).getOrNull()
                    ?: throw IllegalStateException("Unable to read audio file for fingerprint")

                // Persist resolved fingerprint into cached_tracks so future operations are instant.
                if (track.contentFingerprint == null) {
                    persistenceRepository.updateCachedTrackFingerprint(track.uriString, fp)
                }

                persistenceRepository.setFavorite(
                    record = FavoriteTrackRecord(
                        contentFingerprint = fp,
                        trackUri = track.uriString,
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        fileName = track.fileName,
                        durationMs = track.durationMs,
                        addedAtEpochMs = System.currentTimeMillis(),
                    ),
                    targetFavorite = targetFavorite,
                )

                clearOptimisticFavorite(track.uriString, targetFavorite)
                Result.success(targetFavorite)
            } catch (ce: CancellationException) {
                clearOptimisticFavorite(track.uriString, targetFavorite)
                throw ce
            } catch (e: IOException) {
                clearOptimisticFavorite(track.uriString, targetFavorite)
                Result.failure(e)
            } catch (e: SecurityException) {
                clearOptimisticFavorite(track.uriString, targetFavorite)
                Result.failure(e)
            } catch (e: SQLiteException) {
                clearOptimisticFavorite(track.uriString, targetFavorite)
                Result.failure(e)
            } catch (e: IllegalStateException) {
                clearOptimisticFavorite(track.uriString, targetFavorite)
                Result.failure(e)
            } catch (e: IllegalArgumentException) {
                clearOptimisticFavorite(track.uriString, targetFavorite)
                Result.failure(e)
            }
        }
    }

    private fun clearOptimisticFavorite(uriString: String, expectedState: Boolean) {
        // Clear override only if it still reflects our target state (respect newer taps).
        optimisticFavorites.update { current ->
            if (current[uriString] == expectedState) current - uriString else current
        }
    }

    private fun takePersistableReadPermissionDefensively(uri: Uri) {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private data class MediaStoreDiscoveryResult(val permissionGranted: Boolean, val candidates: List<RawMediaStoreCandidate>)

    private data class RawMediaStoreCandidate(
        val uri: Uri,
        val uriString: String,
        val displayName: String,
        val mimeType: String?,
        val format: SupportedAudioFormat,
        val sizeBytes: Long,
        val dateModifiedSeconds: Long,
        val durationMs: Long,
        val rawTitle: String?,
        val rawArtist: String?,
        val rawAlbum: String?,
        val packedTrack: Int,
        val cdTrackNumber: String?,
        val discNumber: String?,
        val year: Int,
        val folderName: String,
        val relativePath: String?,
    )

    private data class DiscoveredSafEntry(
        val uri: Uri,
        val uriString: String,
        val fileName: String,
        val mimeType: String?,
        val format: SupportedAudioFormat,
        val sizeBytes: Long,
        val dateModifiedSeconds: Long,
        val folderLabel: String,
        val parentTreeUriString: String?,
    )

    private fun hasMediaStoreAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    private class MediaStoreColumnIndices(cursor: Cursor) {
        val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val name = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val track = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
        val year = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR)
        val cdTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            cursor.getColumnIndex(MediaStore.Audio.Media.CD_TRACK_NUMBER)
        } else {
            -1
        }
        val disc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            cursor.getColumnIndex(MediaStore.Audio.Media.DISC_NUMBER)
        } else {
            -1
        }
        val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val size = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
        val dateModified = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
        val mime = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
        val isMusic = cursor.getColumnIndex(MediaStore.Audio.Media.IS_MUSIC)
        val isRingtone = cursor.getColumnIndex(MediaStore.Audio.Media.IS_RINGTONE)
        val isAlarm = cursor.getColumnIndex(MediaStore.Audio.Media.IS_ALARM)
        val isNotification = cursor.getColumnIndex(MediaStore.Audio.Media.IS_NOTIFICATION)
        val isRecording = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            cursor.getColumnIndex(MediaStore.Audio.Media.IS_RECORDING)
        } else {
            -1
        }
        val relPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
        } else {
            -1
        }
        val bucket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cursor.getColumnIndex(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
        } else {
            -1
        }

        @Suppress("DEPRECATION")
        val legacyData = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
        } else {
            -1
        }
    }

    private fun discoverMediaStoreCandidates(): MediaStoreDiscoveryResult {
        if (!hasMediaStoreAudioPermission()) {
            return MediaStoreDiscoveryResult(permissionGranted = false, candidates = emptyList())
        }
        val results = mutableListOf<RawMediaStoreCandidate>()
        val projection = buildMediaStoreProjection()
        val selection = buildMediaStoreSelection()
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        return try {
            appContext.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder,
            )?.use { cursor ->
                val cols = MediaStoreColumnIndices(cursor)
                while (cursor.moveToNext()) {
                    readMediaStoreCandidateRow(cursor, cols)?.let { results.add(it) }
                }
            }
            MediaStoreDiscoveryResult(permissionGranted = true, candidates = results)
        } catch (_: SecurityException) {
            MediaStoreDiscoveryResult(permissionGranted = false, candidates = emptyList())
        }
    }

    private fun buildMediaStoreProjection(): Array<String> = buildList {
        add(MediaStore.Audio.Media._ID)
        add(MediaStore.Audio.Media.DISPLAY_NAME)
        add(MediaStore.Audio.Media.TITLE)
        add(MediaStore.Audio.Media.ARTIST)
        add(MediaStore.Audio.Media.ALBUM)
        add(MediaStore.Audio.Media.TRACK)
        add(MediaStore.Audio.Media.YEAR)
        add(MediaStore.Audio.Media.DURATION)
        add(MediaStore.Audio.Media.SIZE)
        add(MediaStore.Audio.Media.DATE_MODIFIED)
        add(MediaStore.Audio.Media.MIME_TYPE)
        add(MediaStore.Audio.Media.IS_MUSIC)
        add(MediaStore.Audio.Media.IS_RINGTONE)
        add(MediaStore.Audio.Media.IS_ALARM)
        add(MediaStore.Audio.Media.IS_NOTIFICATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(MediaStore.Audio.Media.CD_TRACK_NUMBER)
            add(MediaStore.Audio.Media.DISC_NUMBER)
        }
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

    private fun buildMediaStoreSelection(): String = buildString {
        append("${MediaStore.Audio.Media.IS_RINGTONE} = 0 ")
        append("AND ${MediaStore.Audio.Media.IS_ALARM} = 0 ")
        append("AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            append(" AND ${MediaStore.Audio.Media.IS_RECORDING} = 0")
        }
    }

    private fun readMediaStoreCandidateRow(cursor: Cursor, cols: MediaStoreColumnIndices): RawMediaStoreCandidate? {
        val displayName = cursor.getString(cols.name)?.trim().orEmpty()
        val mimeType = cursor.getString(cols.mime)?.trim()
        val format = AudioFormatClassifier.classify(displayName, mimeType) ?: return null

        val sizeBytes = cursor.getLong(cols.size).coerceAtLeast(0L)
        val dateModifiedSeconds = if (cols.dateModified >= 0) cursor.getLong(cols.dateModified).coerceAtLeast(0L) else 0L
        val durationMs = cursor.getLong(cols.duration).coerceAtLeast(0L)
        val relativePath = cursor.getTrimmedStringOrNull(cols.relPath)
        val bucketName = cursor.getTrimmedStringOrNull(cols.bucket)
        val legacyDataPath = cursor.getTrimmedStringOrNull(cols.legacyData)

        val scanCandidate = MediaScanFilter.MediaStoreScanCandidate(
            displayName = displayName,
            relativePath = relativePath,
            legacyDataPath = legacyDataPath,
            bucketName = bucketName,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            format = format,
            isMusic = cols.isMusic < 0 || cursor.getInt(cols.isMusic) != 0,
            isRingtone = cols.isRingtone >= 0 && cursor.getInt(cols.isRingtone) != 0,
            isAlarm = cols.isAlarm >= 0 && cursor.getInt(cols.isAlarm) != 0,
            isNotification = cols.isNotification >= 0 && cursor.getInt(cols.isNotification) != 0,
            isRecording = cols.isRecording >= 0 && cursor.getInt(cols.isRecording) != 0,
        )
        if (!MediaScanFilter.shouldIncludeInAutoScan(scanCandidate)) return null

        val contentUri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            cursor.getLong(cols.id),
        )
        val folderName = resolveMediaStoreFolderName(bucketName, relativePath, legacyDataPath)
        val packedTrack = if (cols.track >= 0 && !cursor.isNull(cols.track)) cursor.getInt(cols.track) else 0
        val rawYear = if (cols.year >= 0 && !cursor.isNull(cols.year)) cursor.getInt(cols.year) else 0

        return RawMediaStoreCandidate(
            uri = contentUri,
            uriString = contentUri.toString(),
            displayName = displayName,
            mimeType = mimeType,
            format = format,
            sizeBytes = sizeBytes,
            dateModifiedSeconds = dateModifiedSeconds,
            durationMs = durationMs,
            rawTitle = cursor.getString(cols.title)?.trim(),
            rawArtist = cursor.getString(cols.artist)?.trim(),
            rawAlbum = cursor.getString(cols.album)?.trim(),
            packedTrack = packedTrack,
            cdTrackNumber = cursor.getNonNullTrimmedStringOrNull(cols.cdTrack),
            discNumber = cursor.getNonNullTrimmedStringOrNull(cols.disc),
            year = ArtistDetailHelper.sanitizeYear(rawYear),
            folderName = folderName,
            relativePath = relativePath,
        )
    }

    private fun resolveMediaStoreFolderName(bucketName: String?, relativePath: String?, legacyDataPath: String?): String {
        val legacyFolder = legacyDataPath
            ?.substringBeforeLast('/', "")
            ?.substringAfterLast('/', "")
        return bucketName?.takeIf { it.isNotEmpty() }
            ?: relativePath?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
            ?: legacyFolder?.takeIf { it.isNotEmpty() }
            ?: "Internal Storage"
    }

    private fun Cursor.getTrimmedStringOrNull(columnIndex: Int): String? = if (columnIndex >= 0) getString(columnIndex)?.trim() else null

    private fun Cursor.getNonNullTrimmedStringOrNull(columnIndex: Int): String? =
        if (columnIndex >= 0 && !isNull(columnIndex)) getString(columnIndex)?.trim() else null

    private fun parseMediaStoreCandidate(candidate: RawMediaStoreCandidate, existingFingerprint: String?): PlaybackTrack {
        // Fast path: reads direct FileChannel header for FLAC/WAV and skips MediaMetadataRetriever
        // completely when cursor tags + direct header already supply sampleRateHz & durationMs.
        val meta = metadataReader.readMetadataForMediaStore(
            uri = candidate.uri,
            fileName = candidate.displayName,
            mimeType = candidate.mimeType,
            cursorDurationMs = candidate.durationMs,
            cursorTitle = candidate.rawTitle,
            cursorArtist = candidate.rawArtist,
            cursorAlbum = candidate.rawAlbum,
            cursorPackedTrack = candidate.packedTrack,
            cursorCdTrackNumber = candidate.cdTrackNumber,
            cursorDiscNumber = candidate.discNumber,
            cursorYear = candidate.year,
        )

        return PlaybackTrack(
            uriString = candidate.uriString,
            title = meta.title,
            artist = meta.artist,
            album = meta.album,
            folderName = candidate.folderName,
            fileName = candidate.displayName.ifBlank { "${meta.title}.${candidate.format.containerLabel.lowercase()}" },
            mimeType = candidate.mimeType,
            fileSizeBytes = candidate.sizeBytes,
            dateModifiedSeconds = candidate.dateModifiedSeconds,
            durationMs = meta.durationMs,
            sampleRateHz = meta.sampleRateHz,
            bitDepth = meta.bitDepth,
            container = candidate.format.containerLabel,
            codec = meta.codec,
            contentFingerprint = existingFingerprint,
            relativePath = candidate.relativePath,
            discNumber = meta.discNumber,
            trackNumber = meta.trackNumber,
            year = meta.year,
        )
    }

    private fun discoverSafTreeCandidates(treeUri: Uri): List<DiscoveredSafEntry> {
        val rootDoc = runCatching { DocumentFile.fromTreeUri(appContext, treeUri) }.getOrNull()
            ?: return emptyList()
        if (!rootDoc.exists() || !rootDoc.canRead()) return emptyList()

        val found = mutableListOf<DiscoveredSafEntry>()
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
        out: MutableList<DiscoveredSafEntry>,
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
                out.add(
                    DiscoveredSafEntry(
                        uri = child.uri,
                        uriString = child.uri.toString(),
                        fileName = fileName,
                        mimeType = mime,
                        format = format,
                        sizeBytes = child.length().coerceAtLeast(0L),
                        dateModifiedSeconds = (child.lastModified() / 1000L).coerceAtLeast(0L),
                        folderLabel = folderLabel,
                        parentTreeUriString = treeUriString,
                    ),
                )
            }
        }
    }

    private fun discoverSingleSafFileCandidate(fileUri: Uri): DiscoveredSafEntry? {
        val doc = runCatching { DocumentFile.fromSingleUri(appContext, fileUri) }.getOrNull()
            ?: return null
        if (!doc.exists() || !doc.canRead()) return null
        val fileName = doc.name ?: fileUri.lastPathSegment?.substringAfterLast('/') ?: return null
        val mime = doc.type ?: appContext.contentResolver.getType(fileUri)
        val format = AudioFormatClassifier.classify(fileName, mime) ?: return null
        return DiscoveredSafEntry(
            uri = fileUri,
            uriString = fileUri.toString(),
            fileName = fileName,
            mimeType = mime,
            format = format,
            sizeBytes = doc.length().coerceAtLeast(0L),
            dateModifiedSeconds = (doc.lastModified() / 1000L).coerceAtLeast(0L),
            folderLabel = "Imported Files",
            parentTreeUriString = null,
        )
    }

    private fun parseSafEntry(entry: DiscoveredSafEntry, existingFingerprint: String?): PlaybackTrack {
        val meta = metadataReader.readMetadata(entry.uri, entry.fileName, entry.mimeType)
        return PlaybackTrack(
            uriString = entry.uriString,
            title = meta.title,
            artist = meta.artist,
            album = meta.album,
            folderName = entry.folderLabel,
            fileName = entry.fileName,
            mimeType = entry.mimeType,
            fileSizeBytes = entry.sizeBytes,
            dateModifiedSeconds = entry.dateModifiedSeconds,
            durationMs = meta.durationMs,
            sampleRateHz = meta.sampleRateHz,
            bitDepth = meta.bitDepth,
            container = entry.format.containerLabel,
            codec = meta.codec,
            contentFingerprint = existingFingerprint,
            parentTreeUriString = entry.parentTreeUriString,
            discNumber = meta.discNumber,
            trackNumber = meta.trackNumber,
            year = meta.year,
        )
    }

    companion object {
        private const val MAX_SAF_TRAVERSAL_DEPTH = 8
    }
}
