package com.cangkir.bitperfectplayer.lyrics

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * Pure-Kotlin random-access byte source abstraction for OOM-safe content fingerprinting.
 * Keeps core hashing logic completely free of `android.*` dependencies.
 */
interface SeekableByteSource : Closeable {
    val size: Long

    /**
     * Reads up to [length] bytes starting at [offset] into [buffer] at [destOffset].
     * Returns the number of bytes read, or -1 if end-of-source has been reached.
     */
    @Throws(IOException::class)
    fun readAt(offset: Long, buffer: ByteArray, destOffset: Int, length: Int): Int
}

private fun validateReadAtRange(offset: Long, bufferSize: Int, destOffset: Int, length: Int) {
    val negativeArg = offset < 0L || destOffset < 0 || length < 0
    if (negativeArg || destOffset + length > bufferSize) {
        throw IllegalArgumentException(
            "Invalid readAt parameters: offset=$offset, destOffset=$destOffset, length=$length",
        )
    }
}

/**
 * [SeekableByteSource] backed by a [FileChannel] using positional reads so large audio files
 * never need to be loaded into memory.
 */
class FileChannelSeekableByteSource(private val channel: FileChannel, override val size: Long = channel.size()) : SeekableByteSource {
    override fun readAt(offset: Long, buffer: ByteArray, destOffset: Int, length: Int): Int {
        validateReadAtRange(offset, buffer.size, destOffset, length)
        if (length == 0) return 0
        val byteBuffer = ByteBuffer.wrap(buffer, destOffset, length)
        return channel.read(byteBuffer, offset)
    }

    override fun close() {
        channel.close()
    }
}

/**
 * In-memory [SeekableByteSource] useful for tests and small buffers.
 */
class ByteArraySeekableByteSource(private val data: ByteArray) : SeekableByteSource {
    override val size: Long = data.size.toLong()

    override fun readAt(offset: Long, buffer: ByteArray, destOffset: Int, length: Int): Int {
        validateReadAtRange(offset, buffer.size, destOffset, length)
        if (length == 0) return 0
        if (offset >= data.size) return -1
        val toCopy = minOf(length.toLong(), data.size.toLong() - offset).toInt()
        System.arraycopy(data, offset.toInt(), buffer, destOffset, toCopy)
        return toCopy
    }

    override fun close() = Unit
}

/**
 * Deterministic SHA-256 audio content fingerprint generator (pure Kotlin, zero `android.*` imports).
 *
 * Specification:
 * - If `size <= 131072` bytes (128 KB): lowercase 64-char hex `SHA-256(entire file bytes)`.
 * - If `size > 131072` bytes: lowercase 64-char hex `SHA-256(first 65536 bytes + last 65536 bytes + total size as 8-byte big-endian unsigned integer)`.
 */
object ContentFingerprintGenerator {

    const val FULL_HASH_THRESHOLD_BYTES: Long = 131_072L // 128 KB
    const val EDGE_CHUNK_BYTES: Int = 65_536 // 64 KB
    private const val STREAM_READ_BUFFER_BYTES: Int = 8_192

    private val HEX_CHARS = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
    )

    @Throws(IOException::class)
    fun computeFromBytes(bytes: ByteArray): String = ByteArraySeekableByteSource(bytes).use { computeFromSeekable(it) }

    /**
     * Computes the deterministic fingerprint using OOM-safe positional reads.
     * Reads at most 128 KB from the underlying source regardless of file size.
     */
    @Throws(IOException::class)
    fun computeFromSeekable(source: SeekableByteSource): String {
        val totalSize = source.size
        if (totalSize < 0L) {
            throw IOException("Negative source size: $totalSize")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        if (totalSize <= FULL_HASH_THRESHOLD_BYTES) {
            val buffer = ByteArray(STREAM_READ_BUFFER_BYTES)
            var offset = 0L
            while (offset < totalSize) {
                val toRead = minOf(buffer.size.toLong(), totalSize - offset).toInt()
                val read = source.readAt(offset, buffer, 0, toRead)
                if (read <= 0) {
                    throw EOFException("Unexpected EOF at offset $offset of $totalSize")
                }
                digest.update(buffer, 0, read)
                offset += read
            }
        } else {
            val chunk = ByteArray(EDGE_CHUNK_BYTES)
            readFullyAt(source, 0L, chunk, EDGE_CHUNK_BYTES)
            digest.update(chunk, 0, EDGE_CHUNK_BYTES)

            val tailOffset = totalSize - EDGE_CHUNK_BYTES
            readFullyAt(source, tailOffset, chunk, EDGE_CHUNK_BYTES)
            digest.update(chunk, 0, EDGE_CHUNK_BYTES)

            digest.update(encodeBigEndianUint64(totalSize))
        }
        return toLowerHex(digest.digest())
    }

    /**
     * Computes the exact same deterministic fingerprint from a non-seekable [InputStream]
     * using fixed-size 64 KB head + 64 KB ring buffers (OOM-safe for arbitrarily large files).
     */
    @Throws(IOException::class)
    fun computeFromStream(inputStream: InputStream): String {
        val headBuffer = ByteArray(EDGE_CHUNK_BYTES)
        val tailRing = ByteArray(EDGE_CHUNK_BYTES)
        val readBuffer = ByteArray(STREAM_READ_BUFFER_BYTES)

        var totalBytes = 0L
        var ringPos = 0

        while (true) {
            val count = inputStream.read(readBuffer)
            if (count == -1) break
            if (count == 0) continue

            if (totalBytes < EDGE_CHUNK_BYTES) {
                val headOffset = totalBytes.toInt()
                val headCopy = minOf(count, EDGE_CHUNK_BYTES - headOffset)
                System.arraycopy(readBuffer, 0, headBuffer, headOffset, headCopy)
            }

            // Copy read chunk into 64 KB circular tail ring buffer (count <= 8192 < 65536).
            val firstPart = minOf(count, EDGE_CHUNK_BYTES - ringPos)
            System.arraycopy(readBuffer, 0, tailRing, ringPos, firstPart)
            val secondPart = count - firstPart
            if (secondPart > 0) {
                System.arraycopy(readBuffer, firstPart, tailRing, 0, secondPart)
            }
            ringPos = (ringPos + count) % EDGE_CHUNK_BYTES
            totalBytes += count
        }

        val digest = MessageDigest.getInstance("SHA-256")
        when {
            totalBytes <= EDGE_CHUNK_BYTES -> {
                digest.update(headBuffer, 0, totalBytes.toInt())
            }
            totalBytes <= FULL_HASH_THRESHOLD_BYTES -> {
                digest.update(headBuffer, 0, EDGE_CHUNK_BYTES)
                val remainder = (totalBytes - EDGE_CHUNK_BYTES).toInt()
                digest.update(tailRing, 0, remainder)
            }
            else -> {
                digest.update(headBuffer, 0, EDGE_CHUNK_BYTES)
                val firstTailLen = EDGE_CHUNK_BYTES - ringPos
                if (firstTailLen > 0) {
                    digest.update(tailRing, ringPos, firstTailLen)
                }
                if (ringPos > 0) {
                    digest.update(tailRing, 0, ringPos)
                }
                digest.update(encodeBigEndianUint64(totalBytes))
            }
        }
        return toLowerHex(digest.digest())
    }

    fun computeSafely(sourceFactory: () -> SeekableByteSource): Result<String> = runCatching {
        sourceFactory().use { computeFromSeekable(it) }
    }

    fun computeStreamSafely(streamFactory: () -> InputStream): Result<String> = runCatching {
        streamFactory().use { computeFromStream(it) }
    }

    @Throws(IOException::class)
    private fun readFullyAt(source: SeekableByteSource, startOffset: Long, target: ByteArray, expectedBytes: Int) {
        var filled = 0
        while (filled < expectedBytes) {
            val read = source.readAt(startOffset + filled, target, filled, expectedBytes - filled)
            if (read <= 0) {
                throw EOFException(
                    "Unexpected EOF reading $expectedBytes bytes at offset $startOffset (read $filled)",
                )
            }
            filled += read
        }
    }

    internal fun encodeBigEndianUint64(value: Long): ByteArray = ByteBuffer.allocate(8)
        .order(ByteOrder.BIG_ENDIAN)
        .putLong(value)
        .array()

    private fun toLowerHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val unsigned = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX_CHARS[unsigned ushr 4]
            out[i * 2 + 1] = HEX_CHARS[unsigned and 0x0F]
        }
        return String(out)
    }
}
