package com.cangkir.bitperfectplayer.library

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedArtworkParserTest {
    private val fakeJpegFront = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x03, 0xFF.toByte(), 0xD9.toByte())
    private val fakeJpegBack = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x02, 0x04, 0xFF.toByte(), 0xD9.toByte())
    private val fakePngDisc = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x06, 0x06)
    private val fakeJpegArtist8 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x08, 0x08, 0xFF.toByte(), 0xD9.toByte())
    private val fakeJpegArtist19 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x13, 0x19, 0xFF.toByte(), 0xD9.toByte())
    private val fakeJpegArtist7 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x07, 0x07, 0xFF.toByte(), 0xD9.toByte())

    @Test
    fun flacExtractsFrontBackDiscAndPrioritizesArtistType8Over19And7() {
        val flacBytes = buildFlacWithPictures(
            listOf(
                3 to fakeJpegFront,
                19 to fakeJpegArtist19,
                4 to fakeJpegBack,
                7 to fakeJpegArtist7,
                6 to fakePngDisc,
                8 to fakeJpegArtist8,
            ),
        )

        val multi = EmbeddedArtworkParser.extractMultiple(
            flacBytes,
            setOf(
                EmbeddedArtworkSlot.FRONT_COVER,
                EmbeddedArtworkSlot.BACK_COVER,
                EmbeddedArtworkSlot.DISC,
                EmbeddedArtworkSlot.ARTIST,
            ),
        )

        assertArrayEquals(fakeJpegFront, multi[EmbeddedArtworkSlot.FRONT_COVER])
        assertArrayEquals(fakeJpegBack, multi[EmbeddedArtworkSlot.BACK_COVER])
        assertArrayEquals(fakePngDisc, multi[EmbeddedArtworkSlot.DISC])
        assertArrayEquals(fakeJpegArtist8, multi[EmbeddedArtworkSlot.ARTIST])
    }

    @Test
    fun flacFallsBackToLowerPriorityArtistTypesWhenType8Absent() {
        val flacBytes = buildFlacWithPictures(
            listOf(
                3 to fakeJpegFront,
                19 to fakeJpegArtist19,
                7 to fakeJpegArtist7,
            ),
        )

        assertArrayEquals(
            fakeJpegArtist7,
            EmbeddedArtworkParser.extract(flacBytes, EmbeddedArtworkSlot.ARTIST),
        )
        assertNull(EmbeddedArtworkParser.extract(flacBytes, EmbeddedArtworkSlot.DISC))
        assertNull(EmbeddedArtworkParser.extract(flacBytes, EmbeddedArtworkSlot.BACK_COVER))
    }

    @Test
    fun flacWithLeadingId3TagParsesBothAndUpgradesArtistPriority() {
        val id3Prefix = buildId3Tag(
            majorVersion = 3,
            frames = listOf(buildApicFrame(majorVersion = 3, pictureType = 19, description = "Logo", imageBytes = fakeJpegArtist19)),
        )
        val flacPart = buildFlacWithPictures(
            listOf(
                8 to fakeJpegArtist8,
                6 to fakePngDisc,
            ),
        )
        val combined = id3Prefix + flacPart

        assertArrayEquals(fakeJpegArtist8, EmbeddedArtworkParser.extract(combined, EmbeddedArtworkSlot.ARTIST))
        assertArrayEquals(fakePngDisc, EmbeddedArtworkParser.extract(combined, EmbeddedArtworkSlot.DISC))
    }

    @Test
    fun id3v23ExtractsBackDiscAndArtistWithUtf16Description() {
        val id3 = buildId3Tag(
            majorVersion = 3,
            frames = listOf(
                buildTextFrame(majorVersion = 3, id = "TIT2", text = "Track Title"),
                buildApicFrame(majorVersion = 3, pictureType = 3, description = "Front", imageBytes = fakeJpegFront),
                buildApicFrame(
                    majorVersion = 3,
                    pictureType = 4,
                    description = "Back Cover",
                    imageBytes = fakeJpegBack,
                    utf16Description = true,
                ),
                buildApicFrame(majorVersion = 3, pictureType = 6, description = "CD", imageBytes = fakePngDisc),
                buildApicFrame(majorVersion = 3, pictureType = 10, description = "Band", imageBytes = fakeJpegArtist19),
            ),
        )

        assertArrayEquals(fakeJpegBack, EmbeddedArtworkParser.extract(id3, EmbeddedArtworkSlot.BACK_COVER))
        assertArrayEquals(fakePngDisc, EmbeddedArtworkParser.extract(id3, EmbeddedArtworkSlot.DISC))
        assertArrayEquals(fakeJpegArtist19, EmbeddedArtworkParser.extract(id3, EmbeddedArtworkSlot.ARTIST))
    }

    @Test
    fun id3v24WithExtendedHeaderDataLengthIndicatorAndUnsyncExtractsCleanly() {
        val rawImageWithSyncPattern = byteArrayOf(
            0xFF.toByte(),
            0xD8.toByte(),
            0xFF.toByte(),
            0xE0.toByte(),
            0x12,
            0x34,
            0xFF.toByte(),
            0xD9.toByte(),
        )
        val apicFrame = buildApicFrame(
            majorVersion = 4,
            pictureType = 6,
            description = "Disc",
            imageBytes = rawImageWithSyncPattern,
            includeDataLengthIndicator = true,
            applyUnsync = true,
        )
        val id3 = buildId3Tag(
            majorVersion = 4,
            frames = listOf(apicFrame),
            includeExtendedHeader = true,
        )

        assertArrayEquals(rawImageWithSyncPattern, EmbeddedArtworkParser.extract(id3, EmbeddedArtworkSlot.DISC))
    }

    @Test
    fun rejectsOversizedPictureBlockAbove8MbCapWithoutCrash() {
        val oversizedDeclaredLength = EmbeddedArtworkParser.MAX_PICTURE_BYTES + 1024
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(StandardCharsets.US_ASCII))
        // Last block, type 6 (PICTURE), claimed block length 40 bytes but internal dataLen > 8MB
        out.write(byteArrayOf((0x80 or 6).toByte(), 0x00, 0x00, 40))
        writeInt32Be(out, 6) // DISC
        writeInt32Be(out, 0) // mime len
        writeInt32Be(out, 0) // desc len
        writeInt32Be(out, 0) // width
        writeInt32Be(out, 0) // height
        writeInt32Be(out, 0) // depth
        writeInt32Be(out, 0) // colors
        writeInt32Be(out, oversizedDeclaredLength)
        out.write(ByteArray(8))

        assertNull(EmbeddedArtworkParser.extract(out.toByteArray(), EmbeddedArtworkSlot.DISC))
    }

    @Test
    fun unsupportedContainersAndCorruptedOrTruncatedStreamsReturnNullSafely() {
        val wavHeader = "RIFF\u0024\u0000\u0000\u0000WAVEfmt ".toByteArray(StandardCharsets.US_ASCII)
        val oggHeader = "OggS\u0000\u0002\u0000\u0000\u0000\u0000".toByteArray(StandardCharsets.US_ASCII)
        val m4aHeader = byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
        val truncatedFlac = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte(), 0x86.toByte(), 0, 0)
        val truncatedId3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 1, 0)

        assertNull(EmbeddedArtworkParser.extract(wavHeader, EmbeddedArtworkSlot.ARTIST))
        assertNull(EmbeddedArtworkParser.extract(oggHeader, EmbeddedArtworkSlot.DISC))
        assertNull(EmbeddedArtworkParser.extract(m4aHeader, EmbeddedArtworkSlot.BACK_COVER))
        assertNull(EmbeddedArtworkParser.extract(truncatedFlac, EmbeddedArtworkSlot.DISC))
        assertNull(EmbeddedArtworkParser.extract(truncatedId3, EmbeddedArtworkSlot.ARTIST))
        assertTrue(EmbeddedArtworkParser.extractMultiple(ByteArray(0), setOf(EmbeddedArtworkSlot.DISC)).isEmpty())
    }

    private fun buildFlacWithPictures(pictures: List<Pair<Int, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(StandardCharsets.US_ASCII))
        // Leading STREAMINFO block (type 0, 34 bytes, non-last)
        out.write(byteArrayOf(0x00, 0x00, 0x00, 34))
        out.write(ByteArray(34))

        pictures.forEachIndexed { index, (pictureType, imageBytes) ->
            val blockPayload = buildFlacPicturePayload(pictureType, imageBytes)
            val isLast = index == pictures.lastIndex
            val headerByte = (if (isLast) 0x80 else 0x00) or 6
            out.write(headerByte)
            out.write((blockPayload.size ushr 16) and 0xFF)
            out.write((blockPayload.size ushr 8) and 0xFF)
            out.write(blockPayload.size and 0xFF)
            out.write(blockPayload)
        }
        // Trailing dummy audio frames (parser must stop before reading these)
        out.write(ByteArray(128) { 0x55 })
        return out.toByteArray()
    }

    private fun buildFlacPicturePayload(pictureType: Int, imageBytes: ByteArray): ByteArray {
        val mime = "image/jpeg".toByteArray(StandardCharsets.US_ASCII)
        val desc = "desc".toByteArray(StandardCharsets.UTF_8)
        val out = ByteArrayOutputStream()
        writeInt32Be(out, pictureType)
        writeInt32Be(out, mime.size)
        out.write(mime)
        writeInt32Be(out, desc.size)
        out.write(desc)
        writeInt32Be(out, 500)
        writeInt32Be(out, 500)
        writeInt32Be(out, 24)
        writeInt32Be(out, 0)
        writeInt32Be(out, imageBytes.size)
        out.write(imageBytes)
        return out.toByteArray()
    }

    private fun buildId3Tag(majorVersion: Int, frames: List<ByteArray>, includeExtendedHeader: Boolean = false): ByteArray {
        val body = ByteArrayOutputStream()
        if (includeExtendedHeader) {
            if (majorVersion == 4) {
                // v2.4 synchsafe ext header size = 6 (includes the 4 size bytes)
                writeSynchsafeInt32(body, 6)
                body.write(byteArrayOf(0x01, 0x00))
            } else {
                writeInt32Be(body, 6)
                body.write(ByteArray(6))
            }
        }
        frames.forEach { body.write(it) }
        // Trailing zero padding inside tag
        body.write(ByteArray(16))

        val bodyBytes = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(StandardCharsets.US_ASCII))
        out.write(majorVersion)
        out.write(0)
        out.write(if (includeExtendedHeader) 0x40 else 0x00)
        writeSynchsafeInt32(out, bodyBytes.size)
        out.write(bodyBytes)
        return out.toByteArray()
    }

    private fun buildTextFrame(majorVersion: Int, id: String, text: String): ByteArray {
        val payload = ByteArrayOutputStream()
        payload.write(3) // UTF-8
        payload.write(text.toByteArray(StandardCharsets.UTF_8))
        return wrapId3Frame(majorVersion, id, payload.toByteArray(), flagsLo = 0)
    }

    private fun buildApicFrame(
        majorVersion: Int,
        pictureType: Int,
        description: String,
        imageBytes: ByteArray,
        utf16Description: Boolean = false,
        includeDataLengthIndicator: Boolean = false,
        applyUnsync: Boolean = false,
    ): ByteArray {
        val payload = ByteArrayOutputStream()
        if (utf16Description) {
            payload.write(1) // UTF-16 with BOM
        } else {
            payload.write(3) // UTF-8
        }
        payload.write("image/jpeg".toByteArray(StandardCharsets.US_ASCII))
        payload.write(0)
        payload.write(pictureType)
        if (utf16Description) {
            // UTF-16LE BOM + chars + 0x00 0x00 terminator
            payload.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
            payload.write(description.toByteArray(StandardCharsets.UTF_16LE))
            payload.write(byteArrayOf(0x00, 0x00))
        } else {
            payload.write(description.toByteArray(StandardCharsets.UTF_8))
            payload.write(0)
        }
        payload.write(imageBytes)
        val rawBody = payload.toByteArray()

        val finalBody = ByteArrayOutputStream()
        if (includeDataLengthIndicator && majorVersion == 4) {
            writeSynchsafeInt32(finalBody, rawBody.size)
        }
        if (applyUnsync) {
            finalBody.write(applyUnsynchronization(rawBody))
        } else {
            finalBody.write(rawBody)
        }
        var flagsLo = 0
        if (includeDataLengthIndicator && majorVersion == 4) flagsLo = flagsLo or 0x01
        if (applyUnsync && majorVersion == 4) flagsLo = flagsLo or 0x02

        return wrapId3Frame(majorVersion, "APIC", finalBody.toByteArray(), flagsLo = flagsLo)
    }

    private fun wrapId3Frame(majorVersion: Int, id: String, payload: ByteArray, flagsLo: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(StandardCharsets.US_ASCII))
        if (majorVersion == 4) {
            writeSynchsafeInt32(out, payload.size)
        } else {
            writeInt32Be(out, payload.size)
        }
        out.write(0)
        out.write(flagsLo)
        out.write(payload)
        return out.toByteArray()
    }

    private fun applyUnsynchronization(src: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (i in src.indices) {
            val b = src[i].toInt() and 0xFF
            out.write(b)
            if (b == 0xFF) {
                val next = if (i + 1 < src.size) src[i + 1].toInt() and 0xFF else 0
                if (next == 0x00 || (next and 0xE0) == 0xE0) {
                    out.write(0x00)
                }
            }
        }
        return out.toByteArray()
    }

    private fun writeInt32Be(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeSynchsafeInt32(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 21) and 0x7F)
        out.write((value ushr 14) and 0x7F)
        out.write((value ushr 7) and 0x7F)
        out.write(value and 0x7F)
    }
}
