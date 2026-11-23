package com.cangkir.bitperfectplayer.library

import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure-Kotlin width/height pair for aspect-ratio-preserving artwork scaling (zero `android.*` imports).
 */
data class ArtworkDimensions(val width: Int, val height: Int)

/**
 * Pure-Kotlin artwork helper utilities (zero `android.*` imports) for:
 * - Zero-crypto plain-string memory cache keys with album-level deduplication (safe on Main Thread)
 * - IO-only SHA-256 disk filename hashing
 * - Fast thumbnail vs hero extraction strategy selection
 * - Power-of-2 bitmap downsampling and aspect-ratio-preserving proportional target scaling
 * - Disk-prune throttling and SAF sibling cover matching.
 */
object AlbumArtworkHelper {
    const val THUMBNAIL_BUCKET_PX = 160
    const val HERO_BUCKET_PX = 600
    const val DISC_BUCKET_PX = 512
    const val SLOT_LARGE_BUCKET_PX = 960
    const val DISK_PRUNE_WRITE_INTERVAL = 50

    /**
     * Ordered priority list of local sibling cover filenames matched case-insensitively inside
     * SAF album folders when embedded audio tag artwork is unavailable.
     */
    val SUPPORTED_COVER_FILENAMES: List<String> = listOf(
        "cover.jpg",
        "cover.png",
        "folder.jpg",
        "front.jpg",
        "album.jpg",
        "cover.jpeg",
        "folder.png",
        "front.png",
        "album.png",
    )

    private val priorityByLowerName: Map<String, Int> =
        SUPPORTED_COVER_FILENAMES.withIndex().associate { (index, name) -> name to index }

    /**
     * Selects the highest-priority sibling cover filename from [fileNames], matching
     * case-insensitively. Returns the original filename preserving provider casing, or `null`.
     */
    fun selectBestSiblingCoverName(fileNames: Iterable<String>): String? {
        var bestName: String? = null
        var bestPriority = Int.MAX_VALUE
        for (raw in fileNames) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            val priority = priorityByLowerName[trimmed.lowercase(Locale.ROOT)] ?: continue
            if (priority < bestPriority) {
                bestPriority = priority
                bestName = trimmed
                if (bestPriority == 0) break
            }
        }
        return bestName
    }

    /**
     * Normalizes requested pixel dimensions into two reusable cache buckets (`160px` for list
     * thumbnails & mini-player, `600px` for Now Playing & MediaSession) to prevent duplicate
     * decoding and keep disk/memory usage minimal.
     */
    fun normalizeSizeBucket(targetSizePx: Int): Int = if (targetSizePx <= 240) THUMBNAIL_BUCKET_PX else HERO_BUCKET_PX

    /**
     * Bounds decoded dimensions per embedded artwork slot so circular Vinyl [EmbeddedArtworkSlot.DISC]
     * center labels never exceed `512 px` (`~1 MB` ARGB_8888) and large Back/Artist slots stay `<= 960 px`.
     */
    fun resolveSlotTargetSizePx(slot: EmbeddedArtworkSlot, requestedSizePx: Int): Int {
        val normalized = if (requestedSizePx <= 240) THUMBNAIL_BUCKET_PX else requestedSizePx.coerceAtLeast(1)
        return when (slot) {
            EmbeddedArtworkSlot.DISC -> minOf(normalized, DISC_BUCKET_PX)
            else -> minOf(normalized, SLOT_LARGE_BUCKET_PX)
        }
    }

    /**
     * Returns `true` when [folderName] is specific enough to scope fallback `DISC` artwork caches
     * when a track has no distinct `album` tag.
     */
    fun isDistinctFolderFallback(folderName: String?): Boolean {
        val trimmed = folderName?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase(Locale.ROOT)
        return lower !in GENERIC_FOLDER_NAMES
    }

    /**
     * Builds a lightweight slot memory cache key. For [EmbeddedArtworkSlot.DISC], keys by normalized
     * `artist + album + discNumber` (or distinct SAF folder fallback) so intra-album skips hit in `0 ms`.
     */
    fun buildSlotCacheKey(
        slot: EmbeddedArtworkSlot,
        trackUri: String,
        artist: String?,
        album: String?,
        discNumber: Int?,
        parentTreeUriString: String?,
        folderName: String?,
        dateModifiedSeconds: Long,
        fileSizeBytes: Long,
        targetSizePx: Int,
    ): String {
        val bucket = resolveSlotTargetSizePx(slot, targetSizePx)
        if (slot == EmbeddedArtworkSlot.DISC) {
            val discTag = discNumber?.takeIf { it > 0 }?.toString() ?: "0"
            val scope = if (!parentTreeUriString.isNullOrBlank()) {
                val normFolder = folderName?.trim()?.lowercase(Locale.ROOT).orEmpty()
                "saf:${parentTreeUriString.trim()}|$normFolder"
            } else {
                "ms"
            }
            if (isDistinctAlbumName(album)) {
                val normAlbum = album!!.trim().lowercase(Locale.ROOT)
                val normArtist = artist?.trim()?.lowercase(Locale.ROOT)
                    ?.takeIf { it.isNotEmpty() && it != "<unknown>" }
                    ?: "unknown"
                return "slot:${slot.name}|alb|$scope|$normArtist|$normAlbum|d$discTag|$bucket"
            }
            if (!parentTreeUriString.isNullOrBlank() && isDistinctFolderFallback(folderName)) {
                return "slot:${slot.name}|fld|$scope|d$discTag|$bucket"
            }
        }
        return "slot:${slot.name}|${trackUri.trim()}|${dateModifiedSeconds.coerceAtLeast(0L)}|" +
            "${fileSizeBytes.coerceAtLeast(0L)}|$bucket"
    }

    /**
     * Returns `true` when [album] identifies a real album name eligible for album-level artwork
     * deduplication across tracks of the same album.
     */
    fun isDistinctAlbumName(album: String?): Boolean {
        val trimmed = album?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        return !trimmed.equals("Unknown Album", ignoreCase = true) &&
            !trimmed.equals("<unknown>", ignoreCase = true) &&
            !trimmed.equals("Unknown", ignoreCase = true)
    }

    /**
     * Builds a lightweight plain-string memory cache key (ZERO SHA-256 hashing so it is completely
     * safe to invoke synchronously on the Main Thread during Compose composition).
     *
     * When [album] is a valid distinct album name, deduplicates artwork across tracks sharing the
     * same normalized `artist + album` (scoped by SAF folder when applicable) so an entire 15-track
     * FLAC album decodes and caches only ONE bitmap/JPEG. Falls back to track identity
     * (`trackUri + dateModified + fileSize`) when album is blank or `"Unknown Album"`.
     */
    fun buildMemoryCacheKey(
        trackUri: String,
        artist: String?,
        album: String?,
        parentTreeUriString: String?,
        folderName: String?,
        dateModifiedSeconds: Long,
        fileSizeBytes: Long,
        targetSizePx: Int,
    ): String {
        val bucket = normalizeSizeBucket(targetSizePx)
        if (isDistinctAlbumName(album)) {
            val normAlbum = album!!.trim().lowercase(Locale.ROOT)
            val normArtist = artist?.trim()?.lowercase(Locale.ROOT)
                ?.takeIf { it.isNotEmpty() && it != "<unknown>" }
                ?: "unknown"
            val scope = if (!parentTreeUriString.isNullOrBlank()) {
                val normFolder = folderName?.trim()?.lowercase(Locale.ROOT).orEmpty()
                "saf:${parentTreeUriString.trim()}|$normFolder"
            } else {
                "ms"
            }
            return "alb|$bucket|$scope|$normArtist|$normAlbum"
        }
        return "trk|$bucket|${trackUri.trim()}|${dateModifiedSeconds.coerceAtLeast(0L)}|${fileSizeBytes.coerceAtLeast(0L)}"
    }

    /**
     * Computes a deterministic lowercase 64-character SHA-256 hex filename hash for [memoryKey].
     * Invoke ONLY on `Dispatchers.IO` for disk cache reads/writes (never on the Main Thread).
     */
    fun hashKeyForDiskFilename(memoryKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(memoryKey.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (byte in digest) {
                val unsigned = byte.toInt() and 0xFF
                append(HEX_DIGITS[unsigned ushr 4])
                append(HEX_DIGITS[unsigned and 0x0F])
            }
        }
    }

    /**
     * Legacy track-level disk hash helper retained for deterministic compatibility.
     */
    fun buildCacheKey(trackUri: String, dateModifiedSeconds: Long, fileSizeBytes: Long, targetSizePx: Int): String {
        val memKey = buildMemoryCacheKey(
            trackUri = trackUri,
            artist = null,
            album = null,
            parentTreeUriString = null,
            folderName = null,
            dateModifiedSeconds = dateModifiedSeconds,
            fileSizeBytes = fileSizeBytes,
            targetSizePx = targetSizePx,
        )
        return hashKeyForDiskFilename(memKey)
    }

    /**
     * For list/mini-player thumbnail requests (`160px`) on MediaStore URIs (API 29+), prefer fast
     * OS-indexed `ContentResolver.loadThumbnail(...)` BEFORE opening large FLAC files with
     * `MediaMetadataRetriever`. Hero artwork (`600px`) continues to prefer embedded full-res tags first.
     */
    fun shouldPreferMediaStoreThumbnailFirst(targetSizePx: Int, isMediaStoreUri: Boolean, supportsApi29LoadThumbnail: Boolean): Boolean =
        normalizeSizeBucket(targetSizePx) == THUMBNAIL_BUCKET_PX &&
            isMediaStoreUri &&
            supportsApi29LoadThumbnail

    /**
     * Throttles filesystem directory listing (`diskCacheDir.listFiles()`) to run at most once
     * every [interval] disk writes.
     */
    fun shouldPruneDiskCache(completedWriteCount: Int, interval: Int = DISK_PRUNE_WRITE_INTERVAL): Boolean =
        completedWriteCount > 0 && interval > 0 && (completedWriteCount % interval == 0)

    /**
     * Computes a power-of-2 `BitmapFactory.Options.inSampleSize` so decoded bitmap dimensions stay
     * at or above `[reqWidth] x [reqHeight]` while avoiding excessive memory allocation.
     */
    fun computeInSampleSize(srcWidth: Int, srcHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        val invalidSourceSize = srcWidth <= 0 || srcHeight <= 0
        val invalidRequestedSize = reqWidth <= 0 || reqHeight <= 0
        if (invalidSourceSize || invalidRequestedSize) {
            return 1
        }
        var inSampleSize = 1
        if (srcHeight > reqHeight || srcWidth > reqWidth) {
            val halfHeight = srcHeight / 2
            val halfWidth = srcWidth / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    /**
     * Computes aspect-ratio-preserving target dimensions bounded by [maxDimensionPx] along the
     * longest edge. Never center-crops or stretches non-square artwork (`16:9`, `4:3`, portrait).
     *
     * When the source longest edge is already within `maxDimensionPx * toleranceScale`, returns
     * the original dimensions unchanged to avoid unnecessary bitmap rescaling.
     */
    fun computeProportionalTargetDimensions(
        srcWidth: Int,
        srcHeight: Int,
        maxDimensionPx: Int,
        toleranceScale: Float = 1.0f,
    ): ArtworkDimensions {
        if (srcWidth <= 0 || srcHeight <= 0 || maxDimensionPx <= 0) {
            return ArtworkDimensions(
                width = srcWidth.coerceAtLeast(0),
                height = srcHeight.coerceAtLeast(0),
            )
        }
        val longestSide = maxOf(srcWidth, srcHeight)
        val effectiveTolerance = if (toleranceScale.isFinite() && toleranceScale >= 1.0f) toleranceScale else 1.0f
        val maxAllowed = (maxDimensionPx.toDouble() * effectiveTolerance.toDouble()).roundToInt()
        if (longestSide <= maxAllowed) {
            return ArtworkDimensions(width = srcWidth, height = srcHeight)
        }
        val scale = maxDimensionPx.toDouble() / longestSide.toDouble()
        val targetWidth = (srcWidth.toDouble() * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (srcHeight.toDouble() * scale).roundToInt().coerceAtLeast(1)
        return ArtworkDimensions(width = targetWidth, height = targetHeight)
    }

    /**
     * Derives the parent directory document ID from a slash-delimited SAF document ID
     * (e.g. `primary:Music/Album/01.flac` -> `primary:Music/Album`, or `primary:01.flac` -> `primary:`).
     * Returns `null` if [documentId] does not expose a slash or colon hierarchy.
     */
    fun deriveParentDocumentId(documentId: String): String? {
        val trimmed = documentId.trim()
        if (trimmed.isEmpty()) return null
        val slashIndex = trimmed.lastIndexOf('/')
        if (slashIndex > 0) {
            return trimmed.substring(0, slashIndex)
        }
        val colonIndex = trimmed.indexOf(':')
        if (colonIndex > 0 && colonIndex < trimmed.lastIndex) {
            return trimmed.substring(0, colonIndex + 1)
        }
        return null
    }

    private const val HEX_DIGITS = "0123456789abcdef"
    private val GENERIC_FOLDER_NAMES = setOf(
        "unknown",
        "<unknown>",
        "music",
        "audio",
        "download",
        "downloads",
        "root",
        "internal storage",
        "sd card",
        "songs",
        "tracks",
    )
}
