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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 100% offline local album cover art extractor & two-tier cache (in-memory [LruCache] + app disk
 * cache under `cacheDir/artwork/`).
 *
 * Extraction order (strictly local, zero network):
 * 1. Embedded artwork tags via [MediaMetadataRetriever.getEmbeddedPicture]
 * 2. MediaStore OS thumbnail fallback via `ContentResolver.loadThumbnail` (API 29+ for `content://media/`)
 * 3. SAF sibling cover image fallback (`cover.jpg`, `cover.png`, `folder.jpg`, `front.jpg`, `album.jpg`,
 *    case-insensitive) inside the track's SAF album folder.
 */
class AlbumArtworkLoader(context: Context) {
    private val appContext = context.applicationContext
    private val diskCacheDir = File(appContext.cacheDir, ARTWORK_CACHE_DIR)
    private val decodeSemaphore = Semaphore(permits = MAX_CONCURRENT_DECODES)

    private val memoryCache: LruCache<String, Bitmap> = createMemoryCache()
    private val missingKeys = ConcurrentHashMap.newKeySet<String>()
    private val folderCoverUriCache = ConcurrentHashMap<String, String>()

    /**
     * Fast synchronous memory lookup for zero-flicker initial Compose rendering.
     */
    fun getCachedFromMemory(track: PlaybackTrack, targetSizePx: Int = THUMBNAIL_SIZE_PX): Bitmap? {
        val key = cacheKeyFor(track, targetSizePx)
        return memoryCache.get(key)
    }

    /**
     * Clears negative-hit caches (called on manual library refresh so newly added folder art or
     * re-tagged tracks are re-evaluated).
     */
    fun clearNegativeCache() {
        missingKeys.clear()
        folderCoverUriCache.clear()
    }

    /**
     * Loads and downsamples local artwork for [track] off the main thread, returning `null` if no
     * local artwork exists. Never performs any network I/O.
     */
    suspend fun loadArtwork(track: PlaybackTrack, targetSizePx: Int = THUMBNAIL_SIZE_PX): Bitmap? {
        val bucket = AlbumArtworkHelper.normalizeSizeBucket(targetSizePx)
        val key = cacheKeyFor(track, bucket)

        memoryCache.get(key)?.let { return it }
        if (missingKeys.contains(key)) return null

        return withContext(Dispatchers.IO) {
            memoryCache.get(key)?.let { return@withContext it }
            if (missingKeys.contains(key)) return@withContext null

            decodeSemaphore.withPermit {
                memoryCache.get(key)?.let { return@withPermit it }
                if (missingKeys.contains(key)) return@withPermit null

                // 1. Check local disk cache first
                readFromDiskCache(key, bucket)?.let { cached ->
                    memoryCache.put(key, cached)
                    return@withPermit cached
                }

                // 2. Extract from local file tags / MediaStore / SAF sibling image
                val decoded = extractLocalArtwork(track, bucket)
                if (decoded != null) {
                    val normalized = constrainMaxDimension(decoded, bucket)
                    memoryCache.put(key, normalized)
                    writeToDiskCache(key, normalized)
                    normalized
                } else {
                    missingKeys.add(key)
                    null
                }
            }
        }
    }

    private fun extractLocalArtwork(track: PlaybackTrack, bucketPx: Int): Bitmap? {
        val uri = runCatching { track.uriString.toUri() }.getOrNull() ?: return null

        // Primary: embedded picture from FLAC/MP3/M4A/OGG/WAV audio tags
        extractEmbeddedPicture(uri, bucketPx)?.let { return it }

        // Secondary: MediaStore OS thumbnail loader on API 29+ for content://media/ tracks
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isMediaStoreUri(uri)) {
            extractMediaStoreThumbnail(uri, bucketPx)?.let { return it }
        }

        // Tertiary: SAF sibling cover image in the same folder
        if (!track.parentTreeUriString.isNullOrBlank()) {
            extractSafSiblingCover(uri, track.parentTreeUriString, bucketPx)?.let { return it }
        }

        return null
    }

    private fun extractEmbeddedPicture(uri: Uri, bucketPx: Int): Bitmap? {
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

    private fun extractSafSiblingCover(
        trackUri: Uri,
        treeUriString: String,
        bucketPx: Int,
    ): Bitmap? {
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
        // Fast O(1) directory child query using DocumentsContract when parent document ID is known.
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

        // Fallback: inspect immediate children of the persisted SAF tree root.
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

    private fun constrainMaxDimension(bitmap: Bitmap, targetSizePx: Int): Bitmap {
        val longestSide = max(bitmap.width, bitmap.height)
        val maxAllowed = (targetSizePx * 1.25f).roundToInt()
        if (longestSide <= maxAllowed || longestSide <= 0) return bitmap
        val ratio = targetSizePx.toFloat() / longestSide.toFloat()
        val scaledWidth = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
        val scaledHeight = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
        return runCatching {
            val scaled = bitmap.scale(scaledWidth, scaledHeight, filter = true)
            if (scaled !== bitmap) {
                bitmap.recycle()
            }
            scaled
        }.getOrDefault(bitmap)
    }

    private fun readFromDiskCache(key: String, targetSizePx: Int): Bitmap? {
        val file = File(diskCacheDir, "$key.jpg")
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

    private fun writeToDiskCache(key: String, bitmap: Bitmap) {
        runCatching {
            if (!diskCacheDir.exists()) {
                diskCacheDir.mkdirs()
            }
            val finalFile = File(diskCacheDir, "$key.jpg")
            val tempFile = File(diskCacheDir, "$key.tmp")
            FileOutputStream(tempFile).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            }
            if (!tempFile.renameTo(finalFile)) {
                tempFile.delete()
            }
            pruneDiskCacheIfOversized()
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

    private fun cacheKeyFor(track: PlaybackTrack, targetSizePx: Int): String =
        AlbumArtworkHelper.buildCacheKey(
            trackUri = track.uriString,
            dateModifiedSeconds = track.dateModifiedSeconds,
            fileSizeBytes = track.fileSizeBytes,
            targetSizePx = targetSizePx,
        )

    private fun isMediaStoreUri(uri: Uri): Boolean =
        uri.scheme == "content" && uri.authority == "media"

    private fun createMemoryCache(): LruCache<String, Bitmap> {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024L / 8L)
            .toInt()
            .coerceIn(MIN_MEM_CACHE_KB, MAX_MEM_CACHE_KB)
        return object : LruCache<String, Bitmap>(maxKb) {
            override fun sizeOf(key: String, value: Bitmap): Int =
                (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    companion object {
        const val THUMBNAIL_SIZE_PX = AlbumArtworkHelper.THUMBNAIL_BUCKET_PX
        const val HERO_SIZE_PX = AlbumArtworkHelper.HERO_BUCKET_PX

        private const val ARTWORK_CACHE_DIR = "artwork"
        private const val MAX_CONCURRENT_DECODES = 4
        private const val MAX_COVER_IMAGE_BYTES = 12 * 1024 * 1024
        private const val JPEG_QUALITY = 88
        private const val MIN_MEM_CACHE_KB = 4_096
        private const val MAX_MEM_CACHE_KB = 24_576
        private const val MAX_DISK_CACHE_FILES = 600
        private const val TARGET_DISK_CACHE_FILES = 450
    }
}
