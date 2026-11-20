package com.cangkir.bitperfectplayer.library

import java.security.MessageDigest
import java.util.Locale

/**
 * Pure-Kotlin artwork helper utilities (zero `android.*` imports) for deterministic local cache
 * keying, power-of-2 bitmap downsampling calculations, and SAF sibling cover-image matching.
 */
object AlbumArtworkHelper {
    const val THUMBNAIL_BUCKET_PX = 160
    const val HERO_BUCKET_PX = 600

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
    fun normalizeSizeBucket(targetSizePx: Int): Int {
        return if (targetSizePx <= 240) THUMBNAIL_BUCKET_PX else HERO_BUCKET_PX
    }

    /**
     * Builds a deterministic lowercase 64-character SHA-256 cache key incorporating track URI,
     * modification timestamp, file size, and normalized size bucket. Safe for both in-memory
     * `LruCache` keys and disk cache filenames.
     */
    fun buildCacheKey(
        trackUri: String,
        dateModifiedSeconds: Long,
        fileSizeBytes: Long,
        targetSizePx: Int,
    ): String {
        val bucket = normalizeSizeBucket(targetSizePx)
        val payload = "${trackUri.trim()}|${dateModifiedSeconds.coerceAtLeast(0L)}|${fileSizeBytes.coerceAtLeast(0L)}|$bucket"
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (byte in digest) {
                val unsigned = byte.toInt() and 0xFF
                append(HEX_DIGITS[unsigned ushr 4])
                append(HEX_DIGITS[unsigned and 0x0F])
            }
        }
    }

    /**
     * Computes a power-of-2 `BitmapFactory.Options.inSampleSize` so decoded bitmap dimensions stay
     * at or above `[reqWidth] x [reqHeight]` while avoiding excessive memory allocation.
     */
    fun computeInSampleSize(
        srcWidth: Int,
        srcHeight: Int,
        reqWidth: Int,
        reqHeight: Int,
    ): Int {
        if (srcWidth <= 0 || srcHeight <= 0 || reqWidth <= 0 || reqHeight <= 0) {
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
}
