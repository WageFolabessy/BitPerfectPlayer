package com.cangkir.bitperfectplayer.audio

import com.cangkir.bitperfectplayer.lyrics.ByteArraySeekableByteSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ReplayGainTagParserTest {

    @Test
    fun parseFlacVorbisComment_extractsTrackAndAlbumGainAndPeakSkippingPictureBlock() {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.ISO_8859_1))

        // Block 0: STREAMINFO (34 zero bytes, not last)
        out.write(flacBlockHeader(isLast = false, blockType = 0, length = 34))
        out.write(ByteArray(34))

        // Block 6: PICTURE (128 bytes skipped without reading)
        out.write(flacBlockHeader(isLast = false, blockType = 6, length = 128))
        out.write(ByteArray(128))

        // Block 4: VORBIS_COMMENT (last)
        val vorbisPayload = buildVorbisCommentVector(
            vendor = "BitPerfectTest",
            comments = listOf(
                "TITLE=So What",
                "REPLAYGAIN_TRACK_GAIN=-7.45 dB",
                "REPLAYGAIN_TRACK_PEAK=0.987654",
                "REPLAYGAIN_ALBUM_GAIN=-8.10 dB",
                "REPLAYGAIN_ALBUM_PEAK=1.000000",
            ),
        )
        out.write(flacBlockHeader(isLast = true, blockType = 4, length = vorbisPayload.size))
        out.write(vorbisPayload)

        val meta = ReplayGainTagParser.parse(ByteArraySeekableByteSource(out.toByteArray()), SupportedAudioFormat.FLAC)
        assertNotNull(meta)
        assertEquals(-7.45f, meta!!.trackGainDb!!, 1e-4f)
        assertEquals(0.987654f, meta.trackPeak!!, 1e-5f)
        assertEquals(-8.10f, meta.albumGainDb!!, 1e-4f)
        assertEquals(1.0f, meta.albumPeak!!, 1e-5f)
    }

    @Test
    fun parseMp3Id3v23AndId3v24Txxx_handlesIsoUtf8AndUtf16BomFrames() {
        // ID3v2.3 with UTF-16 BOM track gain and ISO-8859-1 album gain + peak
        val id3v23 = buildId3v2Tag(
            majorVersion = 3,
            frames = listOf(
                "TXXX" to buildTxxxUtf16LeBom("replaygain_track_gain", "-5.25 dB"),
                "TXXX" to buildTxxxIso("REPLAYGAIN_TRACK_PEAK", "0.9123"),
                "TXXX" to buildTxxxIso("REPLAYGAIN_ALBUM_GAIN", "-6.40 dB"),
            ),
        )
        val meta23 = ReplayGainTagParser.parse(ByteArraySeekableByteSource(id3v23), SupportedAudioFormat.MP3)
        assertNotNull(meta23)
        assertEquals(-5.25f, meta23!!.trackGainDb!!, 1e-4f)
        assertEquals(0.9123f, meta23.trackPeak!!, 1e-4f)
        assertEquals(-6.40f, meta23.albumGainDb!!, 1e-4f)

        // ID3v2.4 with UTF-8 TXXX frames and synchsafe frame sizes
        val id3v24 = buildId3v2Tag(
            majorVersion = 4,
            frames = listOf(
                "TXXX" to buildTxxxUtf8("REPLAYGAIN_TRACK_GAIN", "+1.75 dB"),
                "TXXX" to buildTxxxUtf8("REPLAYGAIN_ALBUM_PEAK", "0.75"),
            ),
        )
        val meta24 = ReplayGainTagParser.parse(ByteArraySeekableByteSource(id3v24), SupportedAudioFormat.MP3)
        assertNotNull(meta24)
        assertEquals(1.75f, meta24!!.trackGainDb!!, 1e-4f)
        assertEquals(0.75f, meta24.albumPeak!!, 1e-4f)
    }

    @Test
    fun parseOggVorbisAndOpusR128Tags_extractsCommentsCleanly() {
        // OGG Vorbis comment packet
        val vorbisPacket = ByteArrayOutputStream().apply {
            write(0x03)
            write("vorbis".toByteArray(Charsets.ISO_8859_1))
            write(
                buildVorbisCommentVector(
                    vendor = "libvorbis",
                    comments = listOf(
                        "REPLAYGAIN_TRACK_GAIN=-3.60 dB",
                        "REPLAYGAIN_TRACK_PEAK=0.88",
                    ),
                ),
            )
        }.toByteArray()
        val oggVorbis = buildOggStream(listOf(ByteArray(30), vorbisPacket))
        val vorbisMeta = ReplayGainTagParser.parse(ByteArraySeekableByteSource(oggVorbis), SupportedAudioFormat.OGG)
        assertNotNull(vorbisMeta)
        assertEquals(-3.60f, vorbisMeta!!.trackGainDb!!, 1e-4f)
        assertEquals(0.88f, vorbisMeta.trackPeak!!, 1e-4f)

        // OGG Opus comment packet with RFC 7845 R128 Q7.8 gains (-2560 = -10.0 dB rel -23 LUFS -> -5.0 dB rel -18 LUFS)
        val opusPacket = ByteArrayOutputStream().apply {
            write("OpusTags".toByteArray(Charsets.ISO_8859_1))
            write(
                buildVorbisCommentVector(
                    vendor = "libopus",
                    comments = listOf(
                        "R128_TRACK_GAIN=-2560",
                        "R128_ALBUM_GAIN=-2048",
                    ),
                ),
            )
        }.toByteArray()
        val oggOpus = buildOggStream(listOf("OpusHead".toByteArray(Charsets.ISO_8859_1) + ByteArray(11), opusPacket))
        val opusMeta = ReplayGainTagParser.parse(ByteArraySeekableByteSource(oggOpus), SupportedAudioFormat.OGG)
        assertNotNull(opusMeta)
        assertEquals(-5.0f, opusMeta!!.trackGainDb!!, 1e-4f)
        assertEquals(-3.0f, opusMeta.albumGainDb!!, 1e-4f)
    }

    @Test
    fun parseM4aFreeformAtomsAndITunNormFallback_handlesMp4Hierarchy() {
        val ftyp = mp4Box("ftyp", "M4A ".toByteArray(Charsets.ISO_8859_1) + ByteArray(8))
        val mdat = mp4Box("mdat", ByteArray(64)) // skipped via positional seek
        val ilst = mp4Box(
            "ilst",
            mp4FreeformAtom("replaygain_track_gain", "-4.80 dB") +
                mp4FreeformAtom("replaygain_track_peak", "0.94"),
        )
        val meta = mp4Box("meta", ByteArray(4) + ilst) // 4-byte FullBox header
        val udta = mp4Box("udta", meta)
        val moov = mp4Box("moov", udta)
        val m4aBytes = ftyp + mdat + moov

        val parsed = ReplayGainTagParser.parse(ByteArraySeekableByteSource(m4aBytes), SupportedAudioFormat.M4A)
        assertNotNull(parsed)
        assertEquals(-4.80f, parsed!!.trackGainDb!!, 1e-4f)
        assertEquals(0.94f, parsed.trackPeak!!, 1e-4f)

        // Optional iTunNORM fallback when explicit replaygain_* atoms are absent
        // 00000FA0 hex = 4000 -> -10 * log10(4000/1000) = -6.0206 dB
        val ilstNorm = mp4Box(
            "ilst",
            mp4FreeformAtom("iTunNORM", " 00000FA0 00000FA0 00001000 00001000"),
        )
        val m4aNorm = ftyp + mp4Box("moov", mp4Box("udta", mp4Box("meta", ByteArray(4) + ilstNorm)))
        val normMeta = ReplayGainTagParser.parse(ByteArraySeekableByteSource(m4aNorm), SupportedAudioFormat.M4A)
        assertNotNull(normMeta)
        assertEquals(-6.0206f, normMeta!!.trackGainDb!!, 1e-3f)
    }

    @Test
    fun malformedCorruptedOrEmptyInputs_neverThrowAndReturnNullSafely() {
        assertNull(ReplayGainTagParser.parse(ByteArraySeekableByteSource(ByteArray(0))))
        assertNull(ReplayGainTagParser.parse(ByteArraySeekableByteSource("not-audio".toByteArray())))

        // FLAC with malformed numeric strings
        val badVorbis = buildVorbisCommentVector(
            vendor = "bad",
            comments = listOf(
                "REPLAYGAIN_TRACK_GAIN=not_a_number",
                "REPLAYGAIN_TRACK_PEAK=-2.5",
                "REPLAYGAIN_ALBUM_GAIN=999.0 dB",
                "REPLAYGAIN_ALBUM_PEAK=NaN",
            ),
        )
        val badFlac = ByteArrayOutputStream().apply {
            write("fLaC".toByteArray(Charsets.ISO_8859_1))
            write(flacBlockHeader(isLast = true, blockType = 4, length = badVorbis.size))
            write(badVorbis)
        }.toByteArray()
        assertNull(ReplayGainTagParser.parse(ByteArraySeekableByteSource(badFlac), SupportedAudioFormat.FLAC))

        // Truncated ID3 header
        val truncatedId3 = "ID3\u0003\u0000\u0000\u0000\u0000\u0005\u0000TXXX".toByteArray(Charsets.ISO_8859_1)
        assertNull(ReplayGainTagParser.parse(ByteArraySeekableByteSource(truncatedId3), SupportedAudioFormat.MP3))
    }

    // -------------------------------------------------------------------------
    // Synthetic Binary Test Builders
    // -------------------------------------------------------------------------

    private fun flacBlockHeader(isLast: Boolean, blockType: Int, length: Int): ByteArray = byteArrayOf(
        ((if (isLast) 0x80 else 0) or (blockType and 0x7F)).toByte(),
        ((length shr 16) and 0xFF).toByte(),
        ((length shr 8) and 0xFF).toByte(),
        (length and 0xFF).toByte(),
    )

    private fun buildVorbisCommentVector(vendor: String, comments: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
        out.write(int32LE(vendorBytes.size))
        out.write(vendorBytes)
        out.write(int32LE(comments.size))
        for (comment in comments) {
            val cb = comment.toByteArray(Charsets.UTF_8)
            out.write(int32LE(cb.size))
            out.write(cb)
        }
        return out.toByteArray()
    }

    private fun buildId3v2Tag(majorVersion: Int, frames: List<Pair<String, ByteArray>>): ByteArray {
        val body = ByteArrayOutputStream()
        for ((id, payload) in frames) {
            body.write(id.toByteArray(Charsets.ISO_8859_1))
            body.write(if (majorVersion == 4) synchsafeInt(payload.size) else int32BE(payload.size))
            body.write(byteArrayOf(0, 0)) // flags
            body.write(payload)
        }
        val bodyBytes = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(majorVersion)
        out.write(0) // minor
        out.write(0) // flags
        out.write(synchsafeInt(bodyBytes.size))
        out.write(bodyBytes)
        out.write(ByteArray(32)) // dummy audio tail
        return out.toByteArray()
    }

    private fun buildTxxxIso(desc: String, value: String): ByteArray =
        byteArrayOf(0) + desc.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + value.toByteArray(Charsets.ISO_8859_1)

    private fun buildTxxxUtf8(desc: String, value: String): ByteArray =
        byteArrayOf(3) + desc.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + value.toByteArray(Charsets.UTF_8)

    private fun buildTxxxUtf16LeBom(desc: String, value: String): ByteArray {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        return byteArrayOf(1) +
            bom + desc.toByteArray(Charsets.UTF_16LE) +
            byteArrayOf(0, 0) +
            bom + value.toByteArray(Charsets.UTF_16LE)
    }

    private fun buildOggStream(packets: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        packets.forEachIndexed { idx, packet ->
            val segments = mutableListOf<Int>()
            var remaining = packet.size
            while (remaining >= 255) {
                segments.add(255)
                remaining -= 255
            }
            segments.add(remaining)

            out.write("OggS".toByteArray(Charsets.ISO_8859_1))
            out.write(0) // version
            out.write(if (idx == 0) 0x02 else 0x00) // BOS on first page
            out.write(ByteArray(8)) // granule pos
            out.write(int32LE(1)) // serial
            out.write(int32LE(idx)) // page seq
            out.write(ByteArray(4)) // checksum
            out.write(segments.size)
            for (seg in segments) out.write(seg)
            out.write(packet)
        }
        return out.toByteArray()
    }

    private fun mp4Box(type: String, payload: ByteArray): ByteArray =
        int32BE(8 + payload.size) + type.toByteArray(Charsets.ISO_8859_1) + payload

    private fun mp4FreeformAtom(name: String, value: String): ByteArray {
        val meanBox = mp4Box("mean", ByteArray(4) + "com.apple.iTunes".toByteArray(Charsets.UTF_8))
        val nameBox = mp4Box("name", ByteArray(4) + name.toByteArray(Charsets.UTF_8))
        val dataBox = mp4Box("data", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0) + value.toByteArray(Charsets.UTF_8))
        return mp4Box("----", meanBox + nameBox + dataBox)
    }

    private fun int32LE(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun int32BE(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array()

    private fun synchsafeInt(v: Int): ByteArray = byteArrayOf(
        ((v shr 21) and 0x7F).toByte(),
        ((v shr 14) and 0x7F).toByte(),
        ((v shr 7) and 0x7F).toByte(),
        (v and 0x7F).toByte(),
    )
}
