package com.cangkir.bitperfectplayer.lyrics

import android.content.ContentResolver
import android.net.Uri
import java.io.FileInputStream
import java.io.IOException

/**
 * Android [ContentResolver] adapter bridging `content://` and `file://` URIs to
 * [ContentFingerprintGenerator].
 *
 * Prefers random-access head/tail reading via [android.os.ParcelFileDescriptor] + [java.nio.channels.FileChannel]
 * to avoid scanning multi-hundred-megabyte lossless files, and falls back transparently to the
 * fixed-size 64 KB ring-buffer streaming path for non-seekable providers.
 */
class AndroidContentFingerprintProvider(private val contentResolver: ContentResolver) {
    fun computeFingerprint(uri: Uri): Result<String> {
        val seekableResult = tryComputeViaParcelFileDescriptor(uri)
        if (seekableResult != null) {
            return seekableResult
        }
        return computeViaInputStream(uri)
    }

    private fun tryComputeViaParcelFileDescriptor(uri: Uri): Result<String>? {
        val pfd = try {
            contentResolver.openFileDescriptor(uri, "r")
        } catch (_: SecurityException) {
            return Result.failure(SecurityException("Permission denied opening $uri"))
        } catch (_: Exception) {
            return null
        } ?: return null

        return pfd.use { descriptor ->
            try {
                FileInputStream(descriptor.fileDescriptor).use { fis ->
                    val channel = fis.channel
                    val size = channel.size()
                    if (size < 0L) {
                        return null
                    }
                    val source = FileChannelSeekableByteSource(channel, size)
                    Result.success(ContentFingerprintGenerator.computeFromSeekable(source))
                }
            } catch (_: IOException) {
                // Non-seekable pipe/socket/virtual provider: let caller fall back to InputStream.
                null
            }
        }
    }

    private fun computeViaInputStream(uri: Uri): Result<String> = runCatching {
        val stream = contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to open input stream for URI: $uri")
        stream.use { ContentFingerprintGenerator.computeFromStream(it) }
    }
}
