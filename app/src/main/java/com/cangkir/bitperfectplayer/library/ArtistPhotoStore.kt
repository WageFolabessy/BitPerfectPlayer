package com.cangkir.bitperfectplayer.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.LruCache
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * 100% offline local custom artist photo storage backed by app-private `filesDir/artist_photos/`
 * (zero runtime storage/media permissions required; integrates directly with Android Photo Picker).
 *
 * Automatically downscales imported photos to `<= 1024 px` on the longest edge, applies EXIF
 * orientation transforms so portrait photos never appear rotated, and publishes reactive
 * [photoRevision] ticks so `ArtistDetailView` and its Contextual Artist Dynamic Theme refresh
 * immediately upon set or reset.
 */
class ArtistPhotoStore(context: Context) {
    private val appContext = context.applicationContext
    private val photosDir = File(appContext.filesDir, ARTIST_PHOTOS_DIR)

    private val memoryCache = object : LruCache<String, Bitmap>(MAX_MEMORY_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    private val _photoRevision = MutableStateFlow(0)
    val photoRevision: StateFlow<Int> = _photoRevision.asStateFlow()

    fun normalizedArtistHash(artistName: String): String {
        val normalized = artistName.trim().lowercase(Locale.ROOT)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    fun themeCacheKeyFor(artistName: String, revision: Int = _photoRevision.value): String =
        "artist_custom_photo:${normalizedArtistHash(artistName)}:rev$revision"

    fun getArtistPhotoFile(artistName: String): File = File(photosDir, "${normalizedArtistHash(artistName)}.jpg")

    fun getCachedBitmap(artistName: String): Bitmap? = memoryCache.get(normalizedArtistHash(artistName))

    suspend fun hasCustomArtistPhoto(artistName: String): Boolean = withContext(Dispatchers.IO) {
        val file = getArtistPhotoFile(artistName)
        file.isFile && file.length() > 0L
    }

    suspend fun loadArtistPhotoBitmap(artistName: String): Bitmap? {
        val hash = normalizedArtistHash(artistName)
        memoryCache.get(hash)?.takeIf { !it.isRecycled }?.let { return it }

        return withContext(Dispatchers.IO) {
            memoryCache.get(hash)?.takeIf { !it.isRecycled }?.let { return@withContext it }
            val file = File(photosDir, "$hash.jpg")
            if (!file.isFile || file.length() <= 0L) return@withContext null
            val decoded = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = AlbumArtworkHelper.computeInSampleSize(
                        srcWidth = bounds.outWidth,
                        srcHeight = bounds.outHeight,
                        reqWidth = MAX_PHOTO_DIMENSION_PX,
                        reqHeight = MAX_PHOTO_DIMENSION_PX,
                    )
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeFile(file.absolutePath, opts)
            }.getOrNull()
            if (decoded != null) {
                memoryCache.put(hash, decoded)
            }
            decoded
        }
    }

    /**
     * Imports a user-selected image [sourceUri] from Android Photo Picker or `GetContent`,
     * applies EXIF rotation, downscales to `<= 1024 px`, and persists atomically as JPEG 88%.
     */
    suspend fun saveArtistPhoto(artistName: String, sourceUri: Uri): Result<Bitmap> = withContext(Dispatchers.IO) {
        runCatching {
            val rawBytes = readUriBytesBounded(sourceUri, MAX_IMPORT_IMAGE_BYTES)
                ?: error("Selected photo could not be read or exceeds 16 MB")
            val exifOrientation = readJpegExifOrientation(rawBytes)
            val decoded = decodeSampledBitmap(rawBytes, MAX_PHOTO_DIMENSION_PX)
                ?: error("Selected file is not a supported image")
            val oriented = applyExifOrientation(decoded, exifOrientation)
            val finalBitmap = constrainMaxDimension(oriented, MAX_PHOTO_DIMENSION_PX)

            if (!photosDir.exists()) {
                photosDir.mkdirs()
            }
            val hash = normalizedArtistHash(artistName)
            val finalFile = File(photosDir, "$hash.jpg")
            val tempFile = File(photosDir, "$hash.tmp")
            FileOutputStream(tempFile).use { stream ->
                check(finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
                    "Failed to save artist photo"
                }
            }
            if (!tempFile.renameTo(finalFile)) {
                tempFile.delete()
                error("Failed to finalize saved artist photo")
            }

            memoryCache.put(hash, finalBitmap)
            _photoRevision.update { it + 1 }
            finalBitmap
        }
    }

    suspend fun clearArtistPhoto(artistName: String): Boolean = withContext(Dispatchers.IO) {
        val hash = normalizedArtistHash(artistName)
        memoryCache.remove(hash)
        val file = File(photosDir, "$hash.jpg")
        val deleted = !file.exists() || file.delete()
        _photoRevision.update { it + 1 }
        deleted
    }

    private fun readUriBytesBounded(uri: Uri, maxBytes: Int): ByteArray? = runCatching {
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

    private fun decodeSampledBitmap(bytes: ByteArray, maxDimPx: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = AlbumArtworkHelper.computeInSampleSize(
                srcWidth = bounds.outWidth,
                srcHeight = bounds.outHeight,
                reqWidth = maxDimPx,
                reqHeight = maxDimPx,
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            EXIF_ROTATE_90 -> matrix.postRotate(90f)
            EXIF_ROTATE_180 -> matrix.postRotate(180f)
            EXIF_ROTATE_270 -> matrix.postRotate(270f)
            EXIF_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            EXIF_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            EXIF_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            EXIF_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return runCatching {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) {
                bitmap.recycle()
            }
            rotated
        }.getOrDefault(bitmap)
    }

    private fun constrainMaxDimension(bitmap: Bitmap, maxDimPx: Int): Bitmap {
        val target = AlbumArtworkHelper.computeProportionalTargetDimensions(
            srcWidth = bitmap.width,
            srcHeight = bitmap.height,
            maxDimensionPx = maxDimPx,
            toleranceScale = 1.05f,
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

    companion object {
        private const val ARTIST_PHOTOS_DIR = "artist_photos"
        private const val MAX_PHOTO_DIMENSION_PX = 1024
        private const val MAX_IMPORT_IMAGE_BYTES = 16 * 1024 * 1024
        private const val MAX_MEMORY_CACHE_KB = 8_192
        private const val JPEG_QUALITY = 88

        private const val EXIF_NORMAL = 1
        private const val EXIF_FLIP_HORIZONTAL = 2
        private const val EXIF_ROTATE_180 = 3
        private const val EXIF_FLIP_VERTICAL = 4
        private const val EXIF_TRANSPOSE = 5
        private const val EXIF_ROTATE_90 = 6
        private const val EXIF_TRANSVERSE = 7
        private const val EXIF_ROTATE_270 = 8
        private const val EXIF_TAG_ORIENTATION = 0x0112

        /**
         * Pure-Kotlin JPEG APP1 EXIF Orientation tag (`0x0112`) reader avoiding external
         * dependencies and legacy `android.media.ExifInterface` Lint warnings.
         */
        internal fun readJpegExifOrientation(bytes: ByteArray): Int {
            if (bytes.size < 12 || (bytes[0].toInt() and 0xFF) != 0xFF || (bytes[1].toInt() and 0xFF) != 0xD8) {
                return EXIF_NORMAL
            }
            var pos = 2
            var resolved = EXIF_NORMAL
            while (pos + 4 <= bytes.size && resolved == EXIF_NORMAL) {
                val markerPos = advanceToJpegMarker(bytes, pos)
                if (markerPos < 0 || markerPos + 2 > bytes.size) break
                val marker = bytes[markerPos - 1].toInt() and 0xFF
                val segLen = ((bytes[markerPos].toInt() and 0xFF) shl 8) or (bytes[markerPos + 1].toInt() and 0xFF)
                val segEnd = markerPos + segLen
                if (segLen < 2 || segEnd > bytes.size) break
                if (marker == 0xE1 && segLen >= 16) {
                    parseTiffOrientation(bytes, markerPos + 2, segEnd)?.let { resolved = it }
                }
                pos = segEnd
            }
            return resolved
        }

        private fun advanceToJpegMarker(bytes: ByteArray, startPos: Int): Int {
            var p = startPos
            if ((bytes[p].toInt() and 0xFF) != 0xFF) return -1
            while (p < bytes.size && (bytes[p].toInt() and 0xFF) == 0xFF) p++
            if (p >= bytes.size) return -1
            val marker = bytes[p].toInt() and 0xFF
            return if (marker == 0xDA || marker == 0xD9) -1 else p + 1
        }

        private fun parseTiffOrientation(bytes: ByteArray, app1Start: Int, segEnd: Int): Int? {
            val hasExifHeader = app1Start + 14 <= segEnd &&
                bytes[app1Start] == 'E'.code.toByte() &&
                bytes[app1Start + 1] == 'x'.code.toByte() &&
                bytes[app1Start + 2] == 'i'.code.toByte() &&
                bytes[app1Start + 3] == 'f'.code.toByte() &&
                bytes[app1Start + 4].toInt() == 0 &&
                bytes[app1Start + 5].toInt() == 0
            if (!hasExifHeader) return null

            val tiff = app1Start + 6
            val littleEndian = when {
                bytes[tiff] == 'I'.code.toByte() && bytes[tiff + 1] == 'I'.code.toByte() -> true
                bytes[tiff] == 'M'.code.toByte() && bytes[tiff + 1] == 'M'.code.toByte() -> false
                else -> return null
            }
            if (readUint16(bytes, tiff + 2, littleEndian) != 42) return null
            val ifdOffset = readInt32(bytes, tiff + 4, littleEndian)
            val ifdStart = tiff + ifdOffset
            if (ifdOffset < 8 || ifdStart + 2 > segEnd) return null

            val entries = readUint16(bytes, ifdStart, littleEndian).coerceAtMost(64)
            var entryPos = ifdStart + 2
            for (i in 0 until entries) {
                if (entryPos + 12 > segEnd) break
                if (readUint16(bytes, entryPos, littleEndian) == EXIF_TAG_ORIENTATION) {
                    val orientation = readUint16(bytes, entryPos + 8, littleEndian)
                    return orientation.takeIf { it in 1..8 }
                }
                entryPos += 12
            }
            return null
        }

        private fun readUint16(bytes: ByteArray, offset: Int, littleEndian: Boolean): Int = if (littleEndian) {
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        } else {
            ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
        }

        private fun readInt32(bytes: ByteArray, offset: Int, littleEndian: Boolean): Int = if (littleEndian) {
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
        } else {
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
        }
    }
}
