package com.cangkir.bitperfectplayer.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.LruCache
import android.util.Size
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 100% offline local album cover art extractor & two-tier cache (in-memory [LruCache] + async
 * disk cache under `cacheDir/artwork/`).
 *
 * Scroll-performance guarantees:
 * - Zero Main-Thread SHA-256 hashing: memory cache & missing-key checks use lightweight album-level
 *   plain-string keys (`AlbumArtworkHelper.buildMemoryCacheKey`).
 * - Album-level deduplication: tracks sharing a valid album name decode and cache only ONE bitmap.
 * - Fast extraction order: `160px` MediaStore thumbnails try OS-indexed `ContentResolver.loadThumbnail`
 *   first (avoiding 200-400 ms `MediaMetadataRetriever` Binder calls on large FLACs), while `600px`
 *   hero artwork prefers full-resolution `MediaMetadataRetriever.embeddedPicture` first.
 * - Fire-and-forget async disk writes on `Dispatchers.IO` with throttled cache pruning (every 50 writes).
 */
class AlbumArtworkLoader(context: Context) {
    private val appContext = context.applicationContext
    private val diskCacheDir = File(appContext.cacheDir, ARTWORK_CACHE_DIR)
    private val decodeSemaphore = Semaphore(permits = MAX_CONCURRENT_DECODES)
    private val diskWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))
    private val diskWriteCounter = AtomicInteger(0)

    private val memoryCache: LruCache<String, Bitmap> = createMemoryCache()
    private val slotMemoryCache: LruCache<String, Bitmap> = createSlotMemoryCache()
    private val missingKeys = ConcurrentHashMap.newKeySet<String>()
    private val slotMissingKeys = ConcurrentHashMap.newKeySet<String>()
    private val folderCoverUriCache = ConcurrentHashMap<String, String>()
    private val folderResolvedTrackKeyCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var playbackOpeningUriFlow: StateFlow<String?>? = null

    fun bindPlaybackOpeningUriFlow(openingTrackUriFlow: StateFlow<String?>) {
        playbackOpeningUriFlow = openingTrackUriFlow
    }

    private suspend fun yieldIfTrackCurrentlyOpening(trackUri: String) {
        val flow = playbackOpeningUriFlow ?: return
        if (flow.value == trackUri) {
            withTimeoutOrNull(OPENING_URI_YIELD_TIMEOUT_MS) {
                flow.first { it != trackUri }
            }
        }
    }

    /**
     * Lightweight plain-string cache identity key safe to call synchronously on the Main Thread.
     */
    fun memoryKeyFor(track: PlaybackTrack, targetSizePx: Int = THUMBNAIL_SIZE_PX): String = AlbumArtworkHelper.buildMemoryCacheKey(
        trackUri = track.uriString,
        artist = track.artist,
        album = track.album,
        parentTreeUriString = track.parentTreeUriString,
        folderName = track.folderName,
        dateModifiedSeconds = track.dateModifiedSeconds,
        fileSizeBytes = track.fileSizeBytes,
        targetSizePx = targetSizePx,
    )

    /**
     * Fast synchronous memory lookup for zero-flicker initial Compose rendering (zero crypto/IO).
     */
    fun getCachedFromMemory(track: PlaybackTrack, targetSizePx: Int = THUMBNAIL_SIZE_PX): Bitmap? {
        val key = memoryKeyFor(track, targetSizePx)
        return memoryCache.get(key)?.takeIf { !it.isRecycled }
    }

    /**
     * Fast synchronous lookup returning the first candidate in [tracks] already cached in memory.
     */
    fun getCachedFirstAvailableArtwork(tracks: List<PlaybackTrack>, targetSizePx: Int = THUMBNAIL_SIZE_PX): Pair<PlaybackTrack, Bitmap>? {
        val bucket = AlbumArtworkHelper.normalizeSizeBucket(targetSizePx)
        for (track in tracks) {
            val cached = getCachedFromMemory(track, bucket)
            if (cached != null) return track to cached
        }
        return null
    }

    /**
     * Lazily resolves the first candidate in [tracks] that has local Front Cover artwork,
     * preserving exact priority order (e.g., oldest-first artist album cover fallback) while
     * reusing the shared thumbnail/hero memory and disk caches.
     */
    suspend fun loadFirstAvailableArtwork(
        tracks: List<PlaybackTrack>,
        targetSizePx: Int = THUMBNAIL_SIZE_PX,
    ): Pair<PlaybackTrack, Bitmap>? {
        val bucket = AlbumArtworkHelper.normalizeSizeBucket(targetSizePx)
        for (track in tracks) {
            val bitmap = loadArtwork(track, bucket)
            if (bitmap != null) return track to bitmap
        }
        return null
    }

    /**
     * Fast synchronous lookup for a folder's previously resolved Front Cover thumbnail.
     */
    fun getCachedFolderArtwork(folderName: String, candidates: List<PlaybackTrack>, targetSizePx: Int = THUMBNAIL_SIZE_PX): Bitmap? {
        if (candidates.isEmpty()) return null
        val bucket = AlbumArtworkHelper.normalizeSizeBucket(targetSizePx)
        val folderKey = buildFolderArtworkKey(folderName, candidates, bucket)
        val cachedTrackKey = folderResolvedTrackKeyCache[folderKey]
        if (cachedTrackKey != null) {
            if (cachedTrackKey.isEmpty()) return null
            memoryCache.get(cachedTrackKey)?.takeIf { !it.isRecycled }?.let { return it }
        }
        return getCachedFirstAvailableArtwork(candidates, bucket)?.second
    }

    /**
     * Deterministically resolves and caches a Front Cover thumbnail for a folder by probing
     * [candidates] (from [FolderArtworkSelector.orderArtworkCandidates]) in order via [loadArtwork],
     * reusing existing thumbnail caches with zero redundant file parsing.
     */
    suspend fun loadFolderArtwork(folderName: String, candidates: List<PlaybackTrack>, targetSizePx: Int = THUMBNAIL_SIZE_PX): Bitmap? {
        if (candidates.isEmpty()) return null
        val bucket = AlbumArtworkHelper.normalizeSizeBucket(targetSizePx)
        val folderKey = buildFolderArtworkKey(folderName, candidates, bucket)
        val cachedTrackKey = folderResolvedTrackKeyCache[folderKey]
        if (cachedTrackKey != null) {
            if (cachedTrackKey.isEmpty()) return null
            memoryCache.get(cachedTrackKey)?.takeIf { !it.isRecycled }?.let { return it }
        }

        val resolved = loadFirstAvailableArtwork(candidates, bucket)
        return if (resolved != null) {
            val winningKey = memoryKeyFor(resolved.first, bucket)
            folderResolvedTrackKeyCache[folderKey] = winningKey
            resolved.second
        } else {
            folderResolvedTrackKeyCache[folderKey] = ""
            null
        }
    }

    private fun buildFolderArtworkKey(folderName: String, candidates: List<PlaybackTrack>, bucket: Int): String =
        "folder_art|${folderName.trim().lowercase()}|${candidates.first().uriString}|${candidates.size}|$bucket"

    /**
     * Clears negative-hit caches (called on manual library refresh so newly added folder art or
     * re-tagged tracks are re-evaluated).
     */
    fun clearNegativeCache() {
        missingKeys.clear()
        slotMissingKeys.clear()
        folderCoverUriCache.clear()
        folderResolvedTrackKeyCache.clear()
    }

    internal fun seedArtworkForTesting(track: PlaybackTrack, bitmap: Bitmap, targetSizePx: Int = THUMBNAIL_SIZE_PX) {
        val bucket = AlbumArtworkHelper.normalizeSizeBucket(targetSizePx)
        val key = memoryKeyFor(track, bucket)
        missingKeys.remove(key)
        folderResolvedTrackKeyCache.clear()
        memoryCache.put(key, bitmap)
    }

    /**
     * Loads and downsamples local artwork for [track] off the main thread, returning `null` if no
     * local artwork exists. Never performs any network I/O.
     */
    suspend fun loadArtwork(track: PlaybackTrack, targetSizePx: Int = THUMBNAIL_SIZE_PX): Bitmap? {
        val bucket = AlbumArtworkHelper.normalizeSizeBucket(targetSizePx)
        val memKey = memoryKeyFor(track, bucket)

        memoryCache.get(memKey)?.let { return it }
        if (missingKeys.contains(memKey)) return null

        return withContext(Dispatchers.IO) {
            memoryCache.get(memKey)?.let { return@withContext it }
            if (missingKeys.contains(memKey)) return@withContext null
            yieldIfTrackCurrentlyOpening(track.uriString)

            decodeSemaphore.withPermit {
                memoryCache.get(memKey)?.let { return@withPermit it }
                if (missingKeys.contains(memKey)) return@withPermit null

                // Compute SHA-256 disk filename hash strictly on Dispatchers.IO.
                val diskHash = AlbumArtworkHelper.hashKeyForDiskFilename(memKey)

                // 1. Check local disk cache first
                readFromDiskCache(diskHash, bucket)?.let { cached ->
                    memoryCache.put(memKey, cached)
                    return@withPermit cached
                }

                // 2. Extract from local MediaStore thumbnail / embedded tags / SAF sibling image
                val decoded = extractLocalArtwork(track, bucket)
                if (decoded != null) {
                    val normalized = constrainMaxDimension(decoded, bucket)
                    memoryCache.put(memKey, normalized)
                    // Persist to disk asynchronously so UI receives the decoded bitmap immediately.
                    diskWriteScope.launch {
                        writeToDiskCache(diskHash, normalized)
                    }
                    normalized
                } else {
                    missingKeys.add(memKey)
                    null
                }
            }
        }
    }

    /**
     * Builds a lightweight cache key for an embedded multi-artwork [slot] (`DISC`, `BACK_COVER`,
     * `ARTIST`) on [track]. `DISC` keys deduplicate at album/artist/disc granularity (with distinct
     * SAF folder fallback) so intra-album skips resolve synchronously in `0 ms`.
     */
    fun slotCacheKeyFor(track: PlaybackTrack, slot: EmbeddedArtworkSlot, targetSizePx: Int = HERO_SIZE_PX): String =
        AlbumArtworkHelper.buildSlotCacheKey(
            slot = slot,
            trackUri = track.uriString,
            artist = track.artist,
            album = track.album,
            discNumber = track.discNumber,
            parentTreeUriString = track.parentTreeUriString,
            folderName = track.folderName,
            dateModifiedSeconds = track.dateModifiedSeconds,
            fileSizeBytes = track.fileSizeBytes,
            targetSizePx = targetSizePx,
        )

    fun getCachedTrackSlotBitmap(track: PlaybackTrack, slot: EmbeddedArtworkSlot, targetSizePx: Int = HERO_SIZE_PX): Bitmap? =
        slotMemoryCache.get(slotCacheKeyFor(track, slot, targetSizePx))?.takeIf { !it.isRecycled }

    fun isTrackSlotKnownMissing(track: PlaybackTrack, slot: EmbeddedArtworkSlot, targetSizePx: Int = DISC_SIZE_PX): Boolean =
        slotMissingKeys.contains(slotCacheKeyFor(track, slot, targetSizePx))

    suspend fun preloadDiscArtwork(track: PlaybackTrack) {
        if (!ArtistDetailHelper.supportsEmbeddedMultiArtwork(track)) return
        if (getCachedTrackSlotBitmap(track, EmbeddedArtworkSlot.DISC, DISC_SIZE_PX) != null) return
        if (isTrackSlotKnownMissing(track, EmbeddedArtworkSlot.DISC, DISC_SIZE_PX)) return
        loadTrackSlotBitmap(track, EmbeddedArtworkSlot.DISC, DISC_SIZE_PX)
    }

    internal fun seedSlotBitmapForTesting(
        track: PlaybackTrack,
        slot: EmbeddedArtworkSlot,
        bitmap: Bitmap,
        targetSizePx: Int = HERO_SIZE_PX,
    ) {
        val key = slotCacheKeyFor(track, slot, targetSizePx)
        slotMissingKeys.remove(key)
        slotMemoryCache.put(key, bitmap)
    }

    internal fun seedArtistEmbeddedBitmapForTesting(artistName: String, bitmap: Bitmap, targetSizePx: Int = THUMBNAIL_SIZE_PX) {
        val bucket = AlbumArtworkHelper.normalizeSizeBucket(targetSizePx)
        val normalized = artistName.trim().lowercase()
        val key = "artist_slot:ARTIST|$normalized|$bucket"
        val missingKey = "artist_slot_missing:ARTIST|$normalized"
        slotMissingKeys.remove(missingKey)
        slotMemoryCache.put(key, bitmap)
    }

    /**
     * Lazily reads an embedded multi-artwork [slot] directly from FLAC/MP3 audio headers on
     * `Dispatchers.IO`. Returns `null` immediately for non-FLAC/MP3 tracks or when absent.
     */
    suspend fun loadTrackSlotBitmap(track: PlaybackTrack, slot: EmbeddedArtworkSlot, targetSizePx: Int = HERO_SIZE_PX): Bitmap? {
        if (!ArtistDetailHelper.supportsEmbeddedMultiArtwork(track)) return null
        val bucket = AlbumArtworkHelper.resolveSlotTargetSizePx(slot, targetSizePx)
        val key = slotCacheKeyFor(track, slot, bucket)
        slotMemoryCache.get(key)?.takeIf { !it.isRecycled }?.let { return it }
        if (slotMissingKeys.contains(key)) return null

        return withContext(Dispatchers.IO) {
            slotMemoryCache.get(key)?.takeIf { !it.isRecycled }?.let { return@withContext it }
            if (slotMissingKeys.contains(key)) return@withContext null
            yieldIfTrackCurrentlyOpening(track.uriString)

            decodeSemaphore.withPermit {
                slotMemoryCache.get(key)?.takeIf { !it.isRecycled }?.let { return@withPermit it }
                if (slotMissingKeys.contains(key)) return@withPermit null

                val rawBytes = extractEmbeddedSlotBytes(track, slot)
                val decoded = rawBytes?.let { decodeSampledBitmapFromBytes(it, bucket) }
                if (decoded != null) {
                    val normalized = constrainMaxDimension(decoded, bucket, toleranceScale = 1.0f)
                    slotMemoryCache.put(key, normalized)
                    normalized
                } else {
                    slotMissingKeys.add(key)
                    null
                }
            }
        }
    }

    /**
     * Lazily checks up to 3 FLAC/MP3 tracks in [tracks] for an embedded `BACK_COVER` picture block
     * on `Dispatchers.IO`, cached at album granularity.
     */
    suspend fun loadAlbumBackCoverBitmap(albumName: String, tracks: List<PlaybackTrack>, targetSizePx: Int = HERO_SIZE_PX): Bitmap? {
        val candidates = tracks.filter { ArtistDetailHelper.supportsEmbeddedMultiArtwork(it) }.take(MAX_ALBUM_BACK_PROBES)
        if (candidates.isEmpty()) return null
        val bucket = AlbumArtworkHelper.resolveSlotTargetSizePx(EmbeddedArtworkSlot.BACK_COVER, targetSizePx)
        val key = "album_slot:BACK_COVER|${albumName.trim().lowercase()}|${candidates.first().uriString}|$bucket"
        slotMemoryCache.get(key)?.takeIf { !it.isRecycled }?.let { return it }
        if (slotMissingKeys.contains(key)) return null

        return withContext(Dispatchers.IO) {
            slotMemoryCache.get(key)?.takeIf { !it.isRecycled }?.let { return@withContext it }
            if (slotMissingKeys.contains(key)) return@withContext null

            decodeSemaphore.withPermit {
                slotMemoryCache.get(key)?.takeIf { !it.isRecycled }?.let { return@withPermit it }
                if (slotMissingKeys.contains(key)) return@withPermit null

                var found: Bitmap? = null
                for (track in candidates) {
                    val bytes = extractEmbeddedSlotBytes(track, EmbeddedArtworkSlot.BACK_COVER) ?: continue
                    val decoded = decodeSampledBitmapFromBytes(bytes, bucket)
                    if (decoded != null) {
                        found = constrainMaxDimension(decoded, bucket, toleranceScale = 1.0f)
                        break
                    }
                }
                if (found != null) {
                    slotMemoryCache.put(key, found)
                } else {
                    slotMissingKeys.add(key)
                }
                found
            }
        }
    }

    fun getCachedArtistEmbeddedBitmap(artistName: String, targetSizePx: Int = HERO_SIZE_PX): Bitmap? {
        val bucket = AlbumArtworkHelper.resolveSlotTargetSizePx(EmbeddedArtworkSlot.ARTIST, targetSizePx)
        val key = "artist_slot:ARTIST|${artistName.trim().lowercase()}|$bucket"
        return slotMemoryCache.get(key)?.takeIf { !it.isRecycled }
    }

    /**
     * Synchronous check indicating whether embedded `ARTIST` artwork has already been probed and
     * confirmed absent for [artistName] (zero I/O).
     */
    fun isArtistEmbeddedMissing(artistName: String): Boolean {
        val missingKey = "artist_slot_missing:ARTIST|${artistName.trim().lowercase()}"
        return slotMissingKeys.contains(missingKey)
    }

    /**
     * Lazily probes a bounded list of FLAC/MP3 [candidateTracks] for an embedded `ARTIST` picture
     * block (priority `8 -> 7 -> 10 -> 19`) on `Dispatchers.IO`, stopping at the first match.
     * Negative misses are cached size-independently across `Artists` tab and `ArtistDetailView`.
     */
    suspend fun loadArtistEmbeddedBitmap(
        artistName: String,
        candidateTracks: List<PlaybackTrack>,
        targetSizePx: Int = HERO_SIZE_PX,
    ): Bitmap? {
        if (candidateTracks.isEmpty()) return null
        val bucket = AlbumArtworkHelper.resolveSlotTargetSizePx(EmbeddedArtworkSlot.ARTIST, targetSizePx)
        val normalized = artistName.trim().lowercase()
        val key = "artist_slot:ARTIST|$normalized|$bucket"
        val missingKey = "artist_slot_missing:ARTIST|$normalized"
        slotMemoryCache.get(key)?.takeIf { !it.isRecycled }?.let { return it }
        if (slotMissingKeys.contains(missingKey)) return null

        return withContext(Dispatchers.IO) {
            slotMemoryCache.get(key)?.takeIf { !it.isRecycled }?.let { return@withContext it }
            if (slotMissingKeys.contains(missingKey)) return@withContext null

            decodeSemaphore.withPermit {
                slotMemoryCache.get(key)?.takeIf { !it.isRecycled }?.let { return@withPermit it }
                if (slotMissingKeys.contains(missingKey)) return@withPermit null

                var found: Bitmap? = null
                for (track in candidateTracks) {
                    val bytes = extractEmbeddedSlotBytes(track, EmbeddedArtworkSlot.ARTIST) ?: continue
                    val decoded = decodeSampledBitmapFromBytes(bytes, bucket)
                    if (decoded != null) {
                        found = constrainMaxDimension(decoded, bucket, toleranceScale = 1.0f)
                        break
                    }
                }
                if (found != null) {
                    slotMemoryCache.put(key, found)
                } else {
                    slotMissingKeys.add(missingKey)
                }
                found
            }
        }
    }

    private fun extractEmbeddedSlotBytes(track: PlaybackTrack, slot: EmbeddedArtworkSlot): ByteArray? {
        val uri = runCatching { track.uriString.toUri() }.getOrNull() ?: return null
        return runCatching {
            appContext.contentResolver.openInputStream(uri)?.buffered(HEADER_BUFFER_BYTES)?.use { input ->
                EmbeddedArtworkParser.extract(input, slot)
            }
        }.getOrNull()
    }

    private fun extractLocalArtwork(track: PlaybackTrack, bucketPx: Int): Bitmap? {
        val uri = runCatching { track.uriString.toUri() }.getOrNull() ?: return null
        val isMediaStore = isMediaStoreUri(uri)
        val preferThumbnailFirst = AlbumArtworkHelper.shouldPreferMediaStoreThumbnailFirst(
            targetSizePx = bucketPx,
            isMediaStoreUri = isMediaStore,
            supportsApi29LoadThumbnail = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
        )

        val primaryArtwork = if (preferThumbnailFirst) {
            // Fast path for list/mini-player 160px thumbnails on MediaStore: try OS-indexed thumbnail first.
            extractMediaStoreThumbnail(uri, bucketPx) ?: extractEmbeddedPicture(track, uri, bucketPx)
        } else {
            // Hero (600px) & SAF/legacy path: prefer lightweight header-only FLAC/MP3 FRONT_COVER first.
            val embedded = extractEmbeddedPicture(track, uri, bucketPx)
            if (embedded == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isMediaStore) {
                extractMediaStoreThumbnail(uri, bucketPx)
            } else {
                embedded
            }
        }

        // Tertiary: SAF sibling cover image in the same folder
        return primaryArtwork ?: track.parentTreeUriString
            ?.takeIf { it.isNotBlank() }
            ?.let { extractSafSiblingCover(uri, it, bucketPx) }
    }

    private fun extractEmbeddedPicture(track: PlaybackTrack, uri: Uri, bucketPx: Int): Bitmap? {
        if (ArtistDetailHelper.supportsEmbeddedMultiArtwork(track)) {
            extractEmbeddedSlotBytes(track, EmbeddedArtworkSlot.FRONT_COVER)
                ?.let { bytes -> decodeSampledBitmapFromBytes(bytes, bucketPx) }
                ?.let { return it }
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            val embeddedBytes = retriever.embeddedPicture ?: return null
            decodeSampledBitmapFromBytes(embeddedBytes, bucketPx)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun extractMediaStoreThumbnail(uri: Uri, bucketPx: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            appContext.contentResolver.loadThumbnail(uri, Size(bucketPx, bucketPx), null)
        }.getOrNull()
    }

    private fun extractSafSiblingCover(trackUri: Uri, treeUriString: String, bucketPx: Int): Bitmap? {
        val treeUri = runCatching { treeUriString.toUri() }.getOrNull() ?: return null
        val docId = runCatching {
            if (DocumentsContract.isDocumentUri(appContext, trackUri)) {
                DocumentsContract.getDocumentId(trackUri)
            } else {
                null
            }
        }.getOrNull()

        val parentDocId = docId?.let { AlbumArtworkHelper.deriveParentDocumentId(it) }
        val folderKey = "$treeUriString|${parentDocId ?: "ROOT"}"

        val coverUriString = folderCoverUriCache[folderKey] ?: run {
            val resolved = findSiblingCoverUri(treeUri, parentDocId)?.toString().orEmpty()
            folderCoverUriCache[folderKey] = resolved
            resolved
        }
        if (coverUriString.isEmpty()) return null

        val coverUri = runCatching { coverUriString.toUri() }.getOrNull() ?: return null
        val bytes = readBytesBounded(coverUri, MAX_COVER_IMAGE_BYTES) ?: return null
        return decodeSampledBitmapFromBytes(bytes, bucketPx)
    }

    private fun findSiblingCoverUri(treeUri: Uri, parentDocId: String?): Uri? {
        if (!parentDocId.isNullOrBlank()) {
            val childrenUri = runCatching {
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            }.getOrNull()
            if (childrenUri != null) {
                val candidates = LinkedHashMap<String, String>()
                runCatching {
                    appContext.contentResolver.query(
                        childrenUri,
                        arrayOf(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        ),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        if (idIdx >= 0 && nameIdx >= 0) {
                            while (cursor.moveToNext()) {
                                val childId = cursor.getString(idIdx) ?: continue
                                val childName = cursor.getString(nameIdx) ?: continue
                                candidates[childName] = childId
                            }
                        }
                    }
                }
                val bestName = AlbumArtworkHelper.selectBestSiblingCoverName(candidates.keys)
                if (bestName != null) {
                    val bestDocId = candidates[bestName]
                    if (bestDocId != null) {
                        return runCatching {
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, bestDocId)
                        }.getOrNull()
                    }
                }
            }
        }

        val rootDoc = runCatching { DocumentFile.fromTreeUri(appContext, treeUri) }.getOrNull()
            ?: return null
        val children = runCatching { rootDoc.listFiles() }.getOrDefault(emptyArray())
        val fileByName = LinkedHashMap<String, DocumentFile>()
        for (child in children) {
            if (child.isFile && child.canRead()) {
                val name = child.name ?: continue
                fileByName[name] = child
            }
        }
        val bestRootName = AlbumArtworkHelper.selectBestSiblingCoverName(fileByName.keys)
        return bestRootName?.let { fileByName[it]?.uri }
    }

    private fun readBytesBounded(uri: Uri, maxBytes: Int): ByteArray? {
        return runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16_384)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > maxBytes) return null
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        }.getOrNull()
    }

    private fun decodeSampledBitmapFromBytes(bytes: ByteArray, targetSizePx: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = AlbumArtworkHelper.computeInSampleSize(
                srcWidth = bounds.outWidth,
                srcHeight = bounds.outHeight,
                reqWidth = targetSizePx,
                reqHeight = targetSizePx,
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        }.getOrNull()
    }

    private fun constrainMaxDimension(bitmap: Bitmap, targetSizePx: Int, toleranceScale: Float = 1.25f): Bitmap {
        val target = AlbumArtworkHelper.computeProportionalTargetDimensions(
            srcWidth = bitmap.width,
            srcHeight = bitmap.height,
            maxDimensionPx = targetSizePx,
            toleranceScale = toleranceScale,
        )
        if (target.width == bitmap.width && target.height == bitmap.height) return bitmap
        if (target.width <= 0 || target.height <= 0) return bitmap
        return runCatching {
            val scaled = bitmap.scale(target.width, target.height, filter = true)
            if (scaled !== bitmap) {
                bitmap.recycle()
            }
            scaled
        }.getOrDefault(bitmap)
    }

    private fun readFromDiskCache(diskHash: String, targetSizePx: Int): Bitmap? {
        val file = File(diskCacheDir, "$diskHash.jpg")
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = AlbumArtworkHelper.computeInSampleSize(
                    srcWidth = bounds.outWidth,
                    srcHeight = bounds.outHeight,
                    reqWidth = targetSizePx,
                    reqHeight = targetSizePx,
                )
            }
            BitmapFactory.decodeFile(file.absolutePath, options)?.also {
                file.setLastModified(System.currentTimeMillis())
            }
        }.getOrNull()
    }

    private fun writeToDiskCache(diskHash: String, bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        runCatching {
            if (!diskCacheDir.exists()) {
                diskCacheDir.mkdirs()
            }
            val finalFile = File(diskCacheDir, "$diskHash.jpg")
            val tempFile = File(diskCacheDir, "$diskHash.tmp")
            FileOutputStream(tempFile).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            }
            if (!tempFile.renameTo(finalFile)) {
                tempFile.delete()
            }
            val writeCount = diskWriteCounter.incrementAndGet()
            if (AlbumArtworkHelper.shouldPruneDiskCache(writeCount)) {
                pruneDiskCacheIfOversized()
            }
        }
    }

    private fun pruneDiskCacheIfOversized() {
        val files = diskCacheDir.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") } ?: return
        if (files.size <= MAX_DISK_CACHE_FILES) return
        val sortedOldestFirst = files.sortedBy { it.lastModified() }
        val toDelete = sortedOldestFirst.size - TARGET_DISK_CACHE_FILES
        for (i in 0 until toDelete) {
            sortedOldestFirst[i].delete()
        }
    }

    private fun isMediaStoreUri(uri: Uri): Boolean = uri.scheme == "content" && uri.authority == "media"

    private fun createMemoryCache(): LruCache<String, Bitmap> {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024L / 8L)
            .toInt()
            .coerceIn(MIN_MEM_CACHE_KB, MAX_MEM_CACHE_KB)
        return object : LruCache<String, Bitmap>(maxKb) {
            override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    private fun createSlotMemoryCache(): LruCache<String, Bitmap> {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024L / 10L)
            .toInt()
            .coerceIn(MIN_SLOT_MEM_CACHE_KB, MAX_SLOT_MEM_CACHE_KB)
        return object : LruCache<String, Bitmap>(maxKb) {
            override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    companion object {
        const val THUMBNAIL_SIZE_PX = AlbumArtworkHelper.THUMBNAIL_BUCKET_PX
        const val HERO_SIZE_PX = AlbumArtworkHelper.HERO_BUCKET_PX
        const val DISC_SIZE_PX = AlbumArtworkHelper.DISC_BUCKET_PX
        const val SLOT_HERO_SIZE_PX = AlbumArtworkHelper.SLOT_LARGE_BUCKET_PX

        private const val ARTWORK_CACHE_DIR = "artwork"
        private const val MAX_CONCURRENT_DECODES = 4
        private const val MAX_COVER_IMAGE_BYTES = 12 * 1024 * 1024
        private const val HEADER_BUFFER_BYTES = 16 * 1024
        private const val MAX_ALBUM_BACK_PROBES = 3
        private const val JPEG_QUALITY = 88
        private const val MIN_MEM_CACHE_KB = 4_096
        private const val MAX_MEM_CACHE_KB = 24_576
        private const val MIN_SLOT_MEM_CACHE_KB = 8_192
        private const val MAX_SLOT_MEM_CACHE_KB = 24_576
        private const val MAX_DISK_CACHE_FILES = 600
        private const val TARGET_DISK_CACHE_FILES = 450
        private const val OPENING_URI_YIELD_TIMEOUT_MS = 450L
    }
}
