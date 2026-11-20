package com.cangkir.bitperfectplayer.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class ContentFingerprintGeneratorTest {

    private fun sha256Hex(vararg chunks: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        chunks.forEach { digest.update(it) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun deterministicBytes(length: Int, seed: Int = 31): ByteArray =
        ByteArray(length) { idx -> ((idx * seed + (idx ushr 3) + 17) and 0xFF).toByte() }

    @Test
    fun emptyFile_producesStandardEmptySha256_onBothPaths() {
        val expected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val seekableHash = ContentFingerprintGenerator.computeFromBytes(ByteArray(0))
        val streamHash = ContentFingerprintGenerator.computeFromStream(ByteArrayInputStream(ByteArray(0)))

        assertEquals(expected, seekableHash)
        assertEquals(expected, streamHash)
        assertEquals(64, seekableHash.length)
        assertEquals(seekableHash.lowercase(), seekableHash)
    }

    @Test
    fun fileSmallerThan64KB_hashesEntireContent() {
        val bytes = deterministicBytes(12_345)
        val expected = sha256Hex(bytes)

        val seekableHash = ContentFingerprintGenerator.computeFromBytes(bytes)
        val streamHash = ContentFingerprintGenerator.computeFromStream(ByteArrayInputStream(bytes))

        assertEquals(expected, seekableHash)
        assertEquals(expected, streamHash)
    }

    @Test
    fun fileBetween64KBAnd128KB_hashesEntireContent() {
        val bytes = deterministicBytes(90_000)
        val expected = sha256Hex(bytes)

        val seekableHash = ContentFingerprintGenerator.computeFromBytes(bytes)
        val streamHash = ContentFingerprintGenerator.computeFromStream(ByteArrayInputStream(bytes))

        assertEquals(expected, seekableHash)
        assertEquals(expected, streamHash)
    }

    @Test
    fun fileExact128KB_hashesEntireContentWithoutSizeSuffix() {
        val bytes = deterministicBytes(131_072)
        val expected = sha256Hex(bytes)

        val seekableHash = ContentFingerprintGenerator.computeFromBytes(bytes)
        val streamHash = ContentFingerprintGenerator.computeFromStream(ByteArrayInputStream(bytes))

        assertEquals(expected, seekableHash)
        assertEquals(expected, streamHash)
    }

    @Test
    fun fileLargerThan128KB_hashesHeadTailAndBigEndianUint64Size_identicalAcrossSeekableAndStream() {
        val sizes = listOf(131_073, 196_608, 250_000, 300_001)
        for (size in sizes) {
            val bytes = deterministicBytes(size)
            val head = bytes.copyOfRange(0, 65_536)
            val tail = bytes.copyOfRange(size - 65_536, size)
            val sizeBe = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(size.toLong()).array()
            val expected = sha256Hex(head, tail, sizeBe)

            val seekableHash = ContentFingerprintGenerator.computeFromBytes(bytes)
            val streamHash = ContentFingerprintGenerator.computeFromStream(ByteArrayInputStream(bytes))

            assertEquals("Seekable mismatch for size=$size", expected, seekableHash)
            assertEquals("Stream mismatch for size=$size", expected, streamHash)
        }
    }

    @Test
    fun fileChannelSeekableSource_matchesStreamHash_andIgnoresFilenameDifferences() {
        val bytes = deterministicBytes(200_000)
        val fileA = File.createTempFile("track_alpha_", ".flac")
        val fileB = File.createTempFile("renamed_beta_", ".wav")
        try {
            fileA.writeBytes(bytes)
            fileB.writeBytes(bytes)

            val hashA = RandomAccessFile(fileA, "r").use { raf ->
                ContentFingerprintGenerator.computeFromSeekable(FileChannelSeekableByteSource(raf.channel))
            }
            val hashB = RandomAccessFile(fileB, "r").use { raf ->
                ContentFingerprintGenerator.computeFromSeekable(FileChannelSeekableByteSource(raf.channel))
            }
            val streamHash = fileA.inputStream().use { ContentFingerprintGenerator.computeFromStream(it) }

            assertEquals(hashA, hashB)
            assertEquals(hashA, streamHash)
        } finally {
            fileA.delete()
            fileB.delete()
        }
    }

    @Test
    fun oneByteMutationAtStartEndOrSize_producesDistinctHashes() {
        val base = deterministicBytes(180_000)
        val baseHash = ContentFingerprintGenerator.computeFromBytes(base)

        val mutatedStart = base.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        val mutatedEnd = base.copyOf().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0x01).toByte() }
        val mutatedSize = ByteArray(base.size + 1).also {
            // Identical first 64 KB and identical last 64 KB, only total size differs by +1 byte in middle.
            System.arraycopy(base, 0, it, 0, 65_536)
            System.arraycopy(base, base.size - 65_536, it, it.size - 65_536, 65_536)
        }

        assertNotEquals(baseHash, ContentFingerprintGenerator.computeFromBytes(mutatedStart))
        assertNotEquals(baseHash, ContentFingerprintGenerator.computeFromBytes(mutatedEnd))
        assertNotEquals(baseHash, ContentFingerprintGenerator.computeFromBytes(mutatedSize))
    }

    @Test
    fun ioFailure_returnsFailureResultWithoutCrashing() {
        val failingSource = object : SeekableByteSource {
            override val size: Long = 1024L
            override fun readAt(offset: Long, buffer: ByteArray, destOffset: Int, length: Int): Int {
                throw IOException("Simulated disk read error")
            }

            override fun close() = Unit
        }
        val seekableRes = ContentFingerprintGenerator.computeSafely { failingSource }
        assertTrue(seekableRes.isFailure)
        assertTrue(seekableRes.exceptionOrNull() is IOException)

        val failingStream = object : InputStream() {
            override fun read(): Int = throw IOException("Simulated stream error")
        }
        val streamRes = ContentFingerprintGenerator.computeStreamSafely { failingStream }
        assertTrue(streamRes.isFailure)
        assertTrue(streamRes.exceptionOrNull() is IOException)
    }

    @Test
    fun streamChunkedIrregularReads_matchesSeekableHash() {
        val bytes = deterministicBytes(175_321)
        val expected = ContentFingerprintGenerator.computeFromBytes(bytes)
        val quirkyStream = object : InputStream() {
            private var pos = 0
            override fun read(): Int = if (pos < bytes.size) bytes[pos++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= bytes.size) return -1
                val step = minOf(len, 137, bytes.size - pos)
                System.arraycopy(bytes, pos, b, off, step)
                pos += step
                return step
            }
        }
        assertEquals(expected, ContentFingerprintGenerator.computeFromStream(quirkyStream))
    }
}
