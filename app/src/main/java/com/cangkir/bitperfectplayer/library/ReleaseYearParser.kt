package com.cangkir.bitperfectplayer.library

import com.cangkir.bitperfectplayer.audio.FlacStreamInfoParser
import com.cangkir.bitperfectplayer.lyrics.SeekableByteSource
import java.nio.charset.Charset

/**
 * Pure-Kotlin release-year sanitizer, string date parser, album consensus resolver, and fast
 * header-only FLAC/ID3v2 (`TDRC` / `TYER` / `TDOR`) year extractor (zero `android.*` imports).
 *
 * Valid release years are strictly bounded to [MIN_VALID_YEAR]..[MAX_VALID_YEAR] (`1900..2100`).
 */
object ReleaseYearParser {
    const val MIN_VALID_YEAR: Int = 1900
    const val MAX_VALID_YEAR: Int = 2100

    private const val MAX_ID3_TAG_SCAN_BYTES: Int = 2 * 1024 * 1024
    private const val MAX_TEXT_FRAME_BYTES: Int = 4096
    private const val MAX_ID3_FRAMES_TO_SCAN: Int = 256

    fun sanitizeYear(year: Int): Int = if (year in MIN_VALID_YEAR..MAX_VALID_YEAR) year else 0

    /**
     * Extracts a valid 4-digit release year (`1900..2100`) from plain years (`"2007"`),
     * ISO dates (`"2007-10-30"`), slash/dot dates (`"1967/06/01"`, `"1991.09.24"`),
     * year prefixes (`"1994; 2009"`, `"2003 (Remaster)"`), or compact 8-digit `YYYYMMDD` dates.
     * Returns `0` for invalid, out-of-range, or missing strings.
     */
    fun parse(raw: String?): Int {
        val text = raw?.trim().orEmpty()
        if (text.length < 4) return 0

        var index = 0
        while (index < text.length) {
            if (!text[index].isDigit()) {
                index++
                continue
            }
            val start = index
            while (index < text.length && text[index].isDigit()) {
                index++
            }
            val length = index - start
            val parsed = parseDigitRunAsYear(text, start, length)
            if (parsed > 0) return parsed
        }
        return 0
    }

    private fun parseDigitRunAsYear(text: String, start: Int, length: Int): Int = when (length) {
        4 -> sanitizeYear(text.substring(start, start + 4).toIntOrNull() ?: 0)
        8 -> {
            val year = sanitizeYear(text.substring(start, start + 4).toIntOrNull() ?: 0)
            val month = text.substring(start + 4, start + 6).toIntOrNull() ?: 0
            val day = text.substring(start + 6, start + 8).toIntOrNull() ?: 0
            if (year > 0 && month in 1..12 && day in 1..31) year else 0
        }
        else -> 0
    }

    /**
     * Resolves an album's release year across ALL tracks in the album:
     * - Picks the **most frequent** valid year in `1900..2100`.
     * - Breaks frequency ties by selecting the **earliest (smallest)** valid year.
     * - Returns `0` only if zero tracks have a valid year.
     */
    fun resolveAlbumReleaseYear(years: Iterable<Int>): Int {
        val counts = HashMap<Int, Int>()
        for (rawYear in years) {
            val valid = sanitizeYear(rawYear)
            if (valid > 0) {
                counts[valid] = (counts[valid] ?: 0) + 1
            }
        }
        if (counts.isEmpty()) return 0

        var bestYear = 0
        var bestCount = -1
        for ((year, count) in counts) {
            if (count > bestCount || (count == bestCount && year < bestYear)) {
                bestYear = year
                bestCount = count
            }
        }
        return bestYear
    }

    /**
     * Fast header-only release-year extraction reading strictly container metadata headers (never
     * audio frames): supports FLAC `VORBIS_COMMENT` (`DATE` / `ORIGINALDATE` / `YEAR`) and
     * MP3/ID3v2 (`TDRC` / `TYER` / `TDOR`), including ID3v2-prefixed FLAC files.
     */
    fun extractYearFromHeader(source: SeekableByteSource): Int {
        if (source.size < 10L) return 0
        val magic = ByteArray(4)
        if (readFully(source, 0L, magic, 4) < 4) return 0

        return when {
            isFlacMagic(magic) -> sanitizeYear(FlacStreamInfoParser.parse(source)?.year ?: 0)
            isId3Magic(magic) -> {
                val id3Year = extractId3v2Year(source)
                if (id3Year > 0) {
                    id3Year
                } else {
                    sanitizeYear(FlacStreamInfoParser.parse(source)?.year ?: 0)
                }
            }
            else -> 0
        }
    }

    /**
     * Fast header-only ID3v2.3 / ID3v2.4 release-year extractor reading `TDRC`, `TYER`, and `TDOR`
     * text frames without touching audio frames. Priority: `TDRC` -> `TYER` -> `TDOR`.
     */
    fun extractId3v2Year(source: SeekableByteSource): Int {
        if (source.size < 10L) return 0
        val header = ByteArray(10)
        if (readFully(source, 0L, header, 10) < 10 || !isId3Magic(header)) return 0

        val majorVersion = header[3].toInt() and 0xFF
        if (majorVersion != 3 && majorVersion != 4) return 0

        val flags = header[5].toInt() and 0xFF
        val tagUnsync = (flags and 0x80) != 0
        val hasExtendedHeader = (flags and 0x40) != 0
        val tagSize = readSynchsafeInt32(header, 6)
        if (tagSize <= 0) return 0

        val tagEnd = minOf(source.size, 10L + minOf(tagSize, MAX_ID3_TAG_SCAN_BYTES).toLong())
        var cursor = if (hasExtendedHeader) {
            skipId3ExtendedHeader(source, 10L, tagEnd, majorVersion)
        } else {
            10L
        }

        val state = Id3YearAccumulator()
        val frameHeader = ByteArray(10)
        var framesVisited = 0

        while (cursor + 10L <= tagEnd && framesVisited < MAX_ID3_FRAMES_TO_SCAN && state.tdrcYear == 0) {
            framesVisited++
            if (readFully(source, cursor, frameHeader, 10) < 10) break
            if (frameHeader[0].toInt() == 0) break

            val frameSize = decodeId3FrameSize(frameHeader, majorVersion)
            val payloadOffset = cursor + 10L
            if (frameSize <= 0 || payloadOffset + frameSize.toLong() > tagEnd) break

            val frameId = String(frameHeader, 0, 4, Charsets.ISO_8859_1)
            if (frameId == "TDRC" || frameId == "TYER" || frameId == "TDOR") {
                readYearTextFrame(
                    source = source,
                    frameId = frameId,
                    payloadOffset = payloadOffset,
                    frameSize = frameSize,
                    majorVersion = majorVersion,
                    frameFlagsLo = frameHeader[9].toInt() and 0xFF,
                    tagUnsync = tagUnsync,
                    state = state,
                )
            }
            cursor = payloadOffset + frameSize.toLong()
        }

        return state.resolveBestYear()
    }

    private class Id3YearAccumulator(var tdrcYear: Int = 0, var tyerYear: Int = 0, var tdorYear: Int = 0) {
        fun resolveBestYear(): Int = tdrcYear.takeIf { it > 0 }
            ?: tyerYear.takeIf { it > 0 }
            ?: tdorYear
    }

    private fun readYearTextFrame(
        source: SeekableByteSource,
        frameId: String,
        payloadOffset: Long,
        frameSize: Int,
        majorVersion: Int,
        frameFlagsLo: Int,
        tagUnsync: Boolean,
        state: Id3YearAccumulator,
    ) {
        if (frameSize > MAX_TEXT_FRAME_BYTES) return
        val compressedOrEncrypted = if (majorVersion == 4) {
            (frameFlagsLo and 0x0C) != 0
        } else {
            (frameFlagsLo and 0xC0) != 0
        }
        if (compressedOrEncrypted) return

        val rawPayload = ByteArray(frameSize)
        if (readFully(source, payloadOffset, rawPayload, frameSize) < frameSize) return

        val hasDataLengthIndicator = majorVersion == 4 && (frameFlagsLo and 0x01) != 0
        val frameUnsync = majorVersion == 4 && (frameFlagsLo and 0x02) != 0
        val payload = if (tagUnsync || frameUnsync) undoUnsynchronization(rawPayload) else rawPayload
        val textStart = if (hasDataLengthIndicator) 4 else 0
        val decoded = decodeId3TextPayload(payload, textStart)
        val parsed = parse(decoded)
        if (parsed <= 0) return

        when (frameId) {
            "TDRC" -> if (state.tdrcYear == 0) state.tdrcYear = parsed
            "TYER" -> if (state.tyerYear == 0) state.tyerYear = parsed
            "TDOR" -> if (state.tdorYear == 0) state.tdorYear = parsed
        }
    }

    private fun decodeId3TextPayload(payload: ByteArray, startOffset: Int): String {
        if (startOffset + 2 > payload.size) return ""
        val encoding = payload[startOffset].toInt() and 0xFF
        val contentStart = startOffset + 1
        val contentLen = payload.size - contentStart
        if (contentLen <= 0) return ""

        return when (encoding) {
            0 -> String(payload, contentStart, contentLen, Charsets.ISO_8859_1)
            3 -> String(payload, contentStart, contentLen, Charsets.UTF_8)
            1 -> decodeUtf16WithOptionalBom(payload, contentStart, contentLen, forceBigEndian = false)
            2 -> decodeUtf16WithOptionalBom(payload, contentStart, contentLen, forceBigEndian = true)
            else -> ""
        }.replace("\u0000", " ")
    }

    private fun decodeUtf16WithOptionalBom(data: ByteArray, offset: Int, length: Int, forceBigEndian: Boolean): String {
        var start = offset
        var len = length
        val charset: Charset = if (!forceBigEndian && len >= 2) {
            val b0 = data[start].toInt() and 0xFF
            val b1 = data[start + 1].toInt() and 0xFF
            when {
                b0 == 0xFF && b1 == 0xFE -> {
                    start += 2
                    len -= 2
                    Charsets.UTF_16LE
                }
                b0 == 0xFE && b1 == 0xFF -> {
                    start += 2
                    len -= 2
                    Charsets.UTF_16BE
                }
                else -> Charsets.UTF_16BE
            }
        } else {
            Charsets.UTF_16BE
        }
        val evenLen = len and 1.inv()
        return if (evenLen > 0) String(data, start, evenLen, charset) else ""
    }

    private fun skipId3ExtendedHeader(source: SeekableByteSource, offset: Long, tagEnd: Long, majorVersion: Int): Long {
        if (offset + 4L > tagEnd) return offset
        val extBuf = ByteArray(4)
        if (readFully(source, offset, extBuf, 4) < 4) return offset
        val extSize = if (majorVersion == 4) readSynchsafeInt32(extBuf, 0) else readRegularInt32Be(extBuf, 0)
        if (extSize < 4) return offset
        val next = offset + if (majorVersion == 4) extSize.toLong() else (4L + extSize.toLong())
        return next.coerceAtMost(tagEnd)
    }

    private fun decodeId3FrameSize(header: ByteArray, majorVersion: Int): Int {
        if (majorVersion != 4) return readRegularInt32Be(header, 4)
        val hasNonSynchsafeBit = ((header[4].toInt() or header[5].toInt() or header[6].toInt() or header[7].toInt()) and 0x80) != 0
        return if (hasNonSynchsafeBit) readRegularInt32Be(header, 4) else readSynchsafeInt32(header, 4)
    }

    private fun undoUnsynchronization(src: ByteArray): ByteArray {
        val out = ByteArray(src.size)
        var outLen = 0
        var i = 0
        while (i < src.size) {
            val b = src[i]
            out[outLen++] = b
            if ((b.toInt() and 0xFF) == 0xFF && i + 1 < src.size && src[i + 1].toInt() == 0x00) {
                i += 2
            } else {
                i++
            }
        }
        return if (outLen == src.size) out else out.copyOf(outLen)
    }

    private fun isFlacMagic(b: ByteArray): Boolean =
        b[0] == 'f'.code.toByte() && b[1] == 'L'.code.toByte() && b[2] == 'a'.code.toByte() && b[3] == 'C'.code.toByte()

    private fun isId3Magic(b: ByteArray): Boolean = b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()

    private fun readRegularInt32Be(bytes: ByteArray, offset: Int): Int = ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)

    private fun readSynchsafeInt32(bytes: ByteArray, offset: Int): Int = ((bytes[offset].toInt() and 0x7F) shl 21) or
        ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
        ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
        (bytes[offset + 3].toInt() and 0x7F)

    private fun readFully(source: SeekableByteSource, startOffset: Long, buf: ByteArray, len: Int): Int {
        var total = 0
        while (total < len) {
            val read = source.readAt(startOffset + total, buf, total, len - total)
            if (read <= 0) break
            total += read
        }
        return total
    }
}
