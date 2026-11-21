package com.cangkir.bitperfectplayer.audio

import com.cangkir.bitperfectplayer.lyrics.SeekableByteSource
import java.nio.charset.Charset
import kotlin.math.log10

/**
 * Pure-Kotlin, OOM-safe header-only ReplayGain metadata parser (zero `android.*` imports).
 *
 * Uses positional reads on [SeekableByteSource] so large lossless audio payloads are never loaded
 * into memory, strictly capping individual metadata block reads. Extracts:
 * - `REPLAYGAIN_TRACK_GAIN`, `REPLAYGAIN_ALBUM_GAIN`, `REPLAYGAIN_TRACK_PEAK`, `REPLAYGAIN_ALBUM_PEAK`
 * - Opus `R128_TRACK_GAIN` and `R128_ALBUM_GAIN` (RFC 7845 Q7.8 dB shifted +5.0 dB from -23 LUFS to -18 LUFS RG reference)
 *
 * Supported containers:
 * 1. FLAC (`VORBIS_COMMENT` metadata block type 4)
 * 2. OGG (Vorbis `\x03vorbis` and Opus `OpusTags` comment headers across multi-page streams)
 * 3. MP3 (ID3v2.3 / ID3v2.4 `TXXX` user-defined text frames)
 * 4. M4A / MP4 (ISO BMFF `moov -> udta -> meta -> ilst -> ----` freeform atoms + optional `iTunNORM` fallback)
 */
object ReplayGainTagParser {
    private const val MAX_METADATA_BLOCK_BYTES = 512 * 1024 // 512 KB hard cap per block/tag
    private const val MAX_HEADER_SCAN_BYTES = 2 * 1024 * 1024L // 2 MB hard cap for scanning
    private const val MAX_OGG_PAGES_TO_SCAN = 12

    private const val R128_TO_REPLAYGAIN_OFFSET_DB = 5.0f

    private class TagAccumulator {
        var trackGainDb: Float? = null
        var albumGainDb: Float? = null
        var trackPeak: Float? = null
        var albumPeak: Float? = null
        var r128TrackGainDb: Float? = null
        var r128AlbumGainDb: Float? = null
        var iTunNormTrackGainDb: Float? = null

        fun acceptKeyValue(rawKey: String, rawValue: String) {
            val key = rawKey.trim().uppercase()
            val value = rawValue.trim()
            if (key.isEmpty() || value.isEmpty()) return

            when (key) {
                "REPLAYGAIN_TRACK_GAIN" -> parseGainDb(value)?.let { trackGainDb = it }
                "REPLAYGAIN_ALBUM_GAIN" -> parseGainDb(value)?.let { albumGainDb = it }
                "REPLAYGAIN_TRACK_PEAK" -> parsePeak(value)?.let { trackPeak = it }
                "REPLAYGAIN_ALBUM_PEAK" -> parsePeak(value)?.let { albumPeak = it }
                "R128_TRACK_GAIN" -> parseR128GainDb(value)?.let { r128TrackGainDb = it }
                "R128_ALBUM_GAIN" -> parseR128GainDb(value)?.let { r128AlbumGainDb = it }
                "ITUNNORM" -> parseITunNormGainDb(value)?.let { iTunNormTrackGainDb = it }
            }
        }

        fun buildOrNull(): ReplayGainMetadata? {
            val resolvedTrackGain = trackGainDb ?: r128TrackGainDb ?: iTunNormTrackGainDb
            val resolvedAlbumGain = albumGainDb ?: r128AlbumGainDb
            val hasAnyGain = resolvedTrackGain != null || resolvedAlbumGain != null
            val hasAnyPeak = trackPeak != null || albumPeak != null
            if (!hasAnyGain && !hasAnyPeak) {
                return null
            }
            return ReplayGainMetadata(
                trackGainDb = resolvedTrackGain,
                trackPeak = trackPeak,
                albumGainDb = resolvedAlbumGain,
                albumPeak = albumPeak,
            )
        }
    }

    fun parse(source: SeekableByteSource, formatHint: SupportedAudioFormat? = null): ReplayGainMetadata? {
        if (source.size < 12L) return null
        val probe = ByteArray(16)
        val probeLen = readFully(source, 0L, probe, minOf(16, source.size.toInt()))
        if (probeLen < 4) return null

        val isFlac = matchesAscii(probe, 0, "fLaC")
        val isOgg = matchesAscii(probe, 0, "OggS")
        val isId3 = matchesAscii(probe, 0, "ID3")
        val isMp4 = probeLen >= 8 && matchesAscii(probe, 4, "ftyp")

        return when {
            // FLAC files can occasionally carry a leading ID3v2 prefix before "fLaC".
            isFlac || formatHint == SupportedAudioFormat.FLAC ->
                parseFlac(source) ?: if (isId3) parseId3v2(source) else null
            isOgg || formatHint == SupportedAudioFormat.OGG -> parseOgg(source)
            isMp4 || formatHint == SupportedAudioFormat.M4A -> parseMp4(source)
            isId3 || formatHint == SupportedAudioFormat.MP3 -> parseId3v2(source)
            else -> null
        }
    }

    // -------------------------------------------------------------------------
    // 1. FLAC VORBIS_COMMENT (Metadata Block Type 4)
    // -------------------------------------------------------------------------

    private fun parseFlac(source: SeekableByteSource): ReplayGainMetadata? {
        var offset = 0L
        val probe = ByteArray(10)
        if (readFully(source, 0L, probe, 10) < 10) return null

        // Skip optional leading ID3v2 header
        if (matchesAscii(probe, 0, "ID3")) {
            val tagSize = readSynchsafeInt(probe, 6)
            offset = 10L + tagSize.toLong()
        }

        val magic = ByteArray(4)
        if (offset + 4L > source.size || readFully(source, offset, magic, 4) < 4) return null
        if (!matchesAscii(magic, 0, "fLaC")) return null
        offset += 4L

        val blockHeader = ByteArray(4)
        var blocksVisited = 0
        while (offset + 4L <= source.size && blocksVisited < 64) {
            blocksVisited++
            if (readFully(source, offset, blockHeader, 4) < 4) break
            val isLast = (blockHeader[0].toInt() and 0x80) != 0
            val blockType = blockHeader[0].toInt() and 0x7F
            val blockLength = ((blockHeader[1].toInt() and 0xFF) shl 16) or
                ((blockHeader[2].toInt() and 0xFF) shl 8) or
                (blockHeader[3].toInt() and 0xFF)
            offset += 4L

            if (blockLength < 0 || offset + blockLength.toLong() > source.size) break

            if (blockType == 4 && blockLength in 8..MAX_METADATA_BLOCK_BYTES) {
                val payload = ByteArray(blockLength)
                if (readFully(source, offset, payload, blockLength) == blockLength) {
                    val acc = TagAccumulator()
                    parseVorbisCommentVector(payload, 0, payload.size, acc)
                    return acc.buildOrNull()
                }
                return null
            }

            offset += blockLength.toLong()
            if (isLast) break
        }
        return null
    }

    // -------------------------------------------------------------------------
    // 2. OGG Vorbis (\x03vorbis) & Opus (OpusTags) Comment Headers
    // -------------------------------------------------------------------------

    private data class OggPageHeader(val isContinued: Boolean, val lacing: ByteArray, val bodyOffset: Long)

    private fun parseOgg(source: SeekableByteSource): ReplayGainMetadata? {
        var offset = 0L
        var pagesScanned = 0
        val pageHeader = ByteArray(27)
        val packetBuilder = ByteArrayAccumulator(MAX_METADATA_BLOCK_BYTES)

        while (offset + 27L <= source.size &&
            pagesScanned < MAX_OGG_PAGES_TO_SCAN &&
            offset < MAX_HEADER_SCAN_BYTES
        ) {
            pagesScanned++
            val page = readOggPageHeader(source, offset, pageHeader) ?: break
            offset = page.bodyOffset

            if (!page.isContinued) {
                packetBuilder.reset()
            }

            for (segIdx in page.lacing.indices) {
                val segLen = page.lacing[segIdx].toInt() and 0xFF
                if (offset + segLen.toLong() > source.size) return null

                if (segLen > 0) {
                    val toRead = minOf(segLen, packetBuilder.remainingCapacity())
                    if (toRead > 0) {
                        val tmp = ByteArray(toRead)
                        if (readFully(source, offset, tmp, toRead) < toRead) return null
                        packetBuilder.append(tmp, 0, toRead)
                    }
                    offset += segLen.toLong()
                }

                // Packet terminates whenever a segment length < 255
                if (segLen < 255) {
                    val packet = packetBuilder.toByteArray()
                    packetBuilder.reset()
                    parseOggCommentPacket(packet)?.let { return it }
                }
            }
        }
        return null
    }

    private fun readOggPageHeader(source: SeekableByteSource, offset: Long, pageHeader: ByteArray): OggPageHeader? {
        if (readFully(source, offset, pageHeader, 27) < 27 || !matchesAscii(pageHeader, 0, "OggS")) {
            return null
        }
        val headerType = pageHeader[5].toInt() and 0xFF
        val segmentCount = pageHeader[26].toInt() and 0xFF
        val lacingOffset = offset + 27L
        if (lacingOffset + segmentCount.toLong() > source.size) return null
        val lacing = ByteArray(segmentCount)
        if (readFully(source, lacingOffset, lacing, segmentCount) < segmentCount) return null
        return OggPageHeader(
            isContinued = (headerType and 0x01) != 0,
            lacing = lacing,
            bodyOffset = lacingOffset + segmentCount.toLong(),
        )
    }

    private fun parseOggCommentPacket(packet: ByteArray): ReplayGainMetadata? {
        if (packet.size < 16) return null
        val acc = TagAccumulator()
        // Vorbis comment header packet starts with 0x03 + "vorbis"
        if (packet[0] == 0x03.toByte() && matchesAscii(packet, 1, "vorbis")) {
            parseVorbisCommentVector(packet, 7, packet.size, acc)
            return acc.buildOrNull()
        }
        // Opus comment header packet starts with "OpusTags"
        if (matchesAscii(packet, 0, "OpusTags")) {
            parseVorbisCommentVector(packet, 8, packet.size, acc)
            return acc.buildOrNull()
        }
        return null
    }

    private fun parseVorbisCommentVector(data: ByteArray, startOffset: Int, endOffset: Int, acc: TagAccumulator) {
        var pos = startOffset
        if (pos + 4 > endOffset) return
        val vendorLen = readInt32LE(data, pos)
        pos += 4
        if (vendorLen < 0 || pos + vendorLen > endOffset) return
        pos += vendorLen

        if (pos + 4 > endOffset) return
        val commentCount = readInt32LE(data, pos)
        pos += 4
        if (commentCount <= 0 || commentCount > 4096) return

        for (i in 0 until commentCount) {
            if (pos + 4 > endOffset) break
            val entryLen = readInt32LE(data, pos)
            pos += 4
            if (entryLen < 0 || pos + entryLen > endOffset) break
            if (entryLen > 0) {
                val entry = String(data, pos, entryLen, Charsets.UTF_8)
                val eq = entry.indexOf('=')
                if (eq > 0 && eq < entry.length - 1) {
                    acc.acceptKeyValue(entry.substring(0, eq), entry.substring(eq + 1))
                }
            }
            pos += entryLen
        }
    }

    // -------------------------------------------------------------------------
    // 3. MP3 ID3v2.3 / ID3v2.4 TXXX Frames
    // -------------------------------------------------------------------------

    private fun parseId3v2(source: SeekableByteSource): ReplayGainMetadata? {
        val header = ByteArray(10)
        if (readFully(source, 0L, header, 10) < 10 || !matchesAscii(header, 0, "ID3")) return null

        val majorVersion = header[3].toInt() and 0xFF
        val tagSize = readSynchsafeInt(header, 6)
        if ((majorVersion != 3 && majorVersion != 4) || tagSize <= 0) return null

        val flags = header[5].toInt() and 0xFF
        val hasExtendedHeader = (flags and 0x40) != 0
        val tagEnd = minOf(10L + tagSize.toLong(), source.size, MAX_HEADER_SCAN_BYTES)
        var offset = if (hasExtendedHeader) {
            skipId3ExtendedHeader(source, 10L, tagEnd, majorVersion)
        } else {
            10L
        }

        val acc = TagAccumulator()
        val frameHeader = ByteArray(10)
        var framesVisited = 0

        while (offset + 10L <= tagEnd && framesVisited < 512) {
            framesVisited++
            if (readFully(source, offset, frameHeader, 10) < 10) break
            // Padding reached (0x00 bytes)
            if (frameHeader[0] == 0.toByte() && frameHeader[1] == 0.toByte()) break

            val frameSize = readId3FrameSize(frameHeader, majorVersion)
            offset += 10L
            if (frameSize <= 0 || offset + frameSize.toLong() > tagEnd) break

            parseId3FrameIfTxxx(source, offset, frameHeader, frameSize, majorVersion, acc)
            offset += frameSize.toLong()
        }

        return acc.buildOrNull()
    }

    private fun skipId3ExtendedHeader(source: SeekableByteSource, offset: Long, tagEnd: Long, majorVersion: Int): Long {
        if (offset + 4L > tagEnd) return offset
        val extBuf = ByteArray(4)
        if (readFully(source, offset, extBuf, 4) < 4) return offset
        val extSize = if (majorVersion == 4) readSynchsafeInt(extBuf, 0) else readInt32BE(extBuf, 0)
        if (extSize < 4) return offset
        return offset + if (majorVersion == 4) extSize.toLong() else (4L + extSize.toLong())
    }

    private fun readId3FrameSize(frameHeader: ByteArray, majorVersion: Int): Int =
        if (majorVersion == 4) readSynchsafeInt(frameHeader, 4) else readInt32BE(frameHeader, 4)

    private fun parseId3FrameIfTxxx(
        source: SeekableByteSource,
        payloadOffset: Long,
        frameHeader: ByteArray,
        frameSize: Int,
        majorVersion: Int,
        acc: TagAccumulator,
    ) {
        val frameId = String(frameHeader, 0, 4, Charsets.ISO_8859_1)
        if (frameId != "TXXX" || frameSize > MAX_METADATA_BLOCK_BYTES) return

        val frameFlags = ((frameHeader[8].toInt() and 0xFF) shl 8) or (frameHeader[9].toInt() and 0xFF)
        val isCompressedOrEncrypted = if (majorVersion == 4) {
            (frameFlags and 0x000C) != 0
        } else {
            (frameFlags and 0x00C0) != 0
        }
        if (isCompressedOrEncrypted) return

        val payload = ByteArray(frameSize)
        if (readFully(source, payloadOffset, payload, frameSize) == frameSize) {
            parseId3TxxxPayload(payload, acc)
        }
    }

    private fun parseId3TxxxPayload(payload: ByteArray, acc: TagAccumulator) {
        if (payload.size < 3) return
        val encodingByte = payload[0].toInt() and 0xFF
        val contentStart = 1
        val contentLen = payload.size - contentStart

        when (encodingByte) {
            0, 3 -> { // ISO-8859-1 or UTF-8 (single-byte NUL separator)
                val charset = if (encodingByte == 3) Charsets.UTF_8 else Charsets.ISO_8859_1
                val nulIdx = findSingleByteNul(payload, contentStart, payload.size)
                if (nulIdx < 0 || nulIdx + 1 >= payload.size) return
                val desc = String(payload, contentStart, nulIdx - contentStart, charset)
                val valEnd = trimTrailingNuls(payload, nulIdx + 1, payload.size)
                if (valEnd <= nulIdx + 1) return
                val value = String(payload, nulIdx + 1, valEnd - (nulIdx + 1), charset)
                acc.acceptKeyValue(desc, value)
            }

            1, 2 -> { // UTF-16 with BOM or UTF-16BE (two-byte NUL separator)
                if (contentLen < 4) return
                val nulIdx = findDoubleByteNul(payload, contentStart, payload.size)
                if (nulIdx < 0 || nulIdx + 2 >= payload.size) return
                val desc = decodeId3Utf16(payload, contentStart, nulIdx - contentStart, encodingByte == 2)
                val value = decodeId3Utf16(payload, nulIdx + 2, payload.size - (nulIdx + 2), encodingByte == 2)
                    .trimEnd('\u0000')
                acc.acceptKeyValue(desc, value)
            }
        }
    }

    private fun decodeId3Utf16(data: ByteArray, offset: Int, length: Int, forceBigEndian: Boolean): String {
        if (length <= 0) return ""
        val charset: Charset
        var start = offset
        var len = length
        if (!forceBigEndian && len >= 2) {
            val b0 = data[start].toInt() and 0xFF
            val b1 = data[start + 1].toInt() and 0xFF
            when {
                b0 == 0xFF && b1 == 0xFE -> {
                    charset = Charsets.UTF_16LE
                    start += 2
                    len -= 2
                }
                b0 == 0xFE && b1 == 0xFF -> {
                    charset = Charsets.UTF_16BE
                    start += 2
                    len -= 2
                }
                else -> charset = Charsets.UTF_16BE
            }
        } else {
            charset = Charsets.UTF_16BE
        }
        val evenLen = len and 1.inv()
        return if (evenLen > 0) String(data, start, evenLen, charset) else ""
    }

    // -------------------------------------------------------------------------
    // 4. M4A / MP4 ISO BMFF (moov -> udta -> meta -> ilst -> ----)
    // -------------------------------------------------------------------------

    private fun parseMp4(source: SeekableByteSource): ReplayGainMetadata? {
        val moov = findMp4Box(source, 0L, source.size, "moov") ?: return null
        val udta = findMp4Box(source, moov.payloadStart, moov.boxEnd, "udta") ?: return null
        val meta = findMp4Box(source, udta.payloadStart, udta.boxEnd, "meta") ?: return null
        // ISO BMFF `meta` is a FullBox: skip 4-byte version + flags before child boxes (defensively
        // also check without 4-byte skip for non-compliant encoders).
        val ilst = findMp4Box(source, meta.payloadStart + 4L, meta.boxEnd, "ilst")
            ?: findMp4Box(source, meta.payloadStart, meta.boxEnd, "ilst")
            ?: return null

        val acc = TagAccumulator()
        var cursor = ilst.payloadStart
        var itemsVisited = 0
        while (cursor + 8L <= ilst.boxEnd && itemsVisited < 256) {
            itemsVisited++
            val box = readMp4BoxHeader(source, cursor, ilst.boxEnd) ?: break
            if (box.type == "----" && (box.boxEnd - box.payloadStart) in 8L..MAX_METADATA_BLOCK_BYTES.toLong()) {
                parseMp4FreeformAtom(source, box.payloadStart, box.boxEnd, acc)
            }
            if (box.boxEnd <= cursor) break
            cursor = box.boxEnd
        }
        return acc.buildOrNull()
    }

    private fun parseMp4FreeformAtom(source: SeekableByteSource, start: Long, end: Long, acc: TagAccumulator) {
        var nameValue: String? = null
        var dataValue: String? = null
        var cursor = start
        var subVisited = 0
        while (cursor + 8L <= end && subVisited < 16) {
            subVisited++
            val child = readMp4BoxHeader(source, cursor, end) ?: break
            val payloadLen = (child.boxEnd - child.payloadStart).toInt()
            if (payloadLen in 1..MAX_METADATA_BLOCK_BYTES) {
                when (child.type) {
                    "name" -> {
                        // FullBox: 4-byte version/flags followed by UTF-8 name
                        if (payloadLen > 4) {
                            val buf = ByteArray(payloadLen - 4)
                            if (readFully(source, child.payloadStart + 4L, buf, buf.size) == buf.size) {
                                nameValue = String(buf, Charsets.UTF_8)
                            }
                        }
                    }
                    "data" -> {
                        // Apple `data` atom: 4-byte type indicator + 4-byte locale + payload
                        if (payloadLen > 8) {
                            val buf = ByteArray(payloadLen - 8)
                            if (readFully(source, child.payloadStart + 8L, buf, buf.size) == buf.size) {
                                dataValue = String(buf, Charsets.UTF_8)
                            }
                        }
                    }
                }
            }
            if (child.boxEnd <= cursor) break
            cursor = child.boxEnd
        }
        if (nameValue != null && dataValue != null) {
            acc.acceptKeyValue(nameValue, dataValue)
        }
    }

    private data class Mp4Box(val type: String, val payloadStart: Long, val boxEnd: Long)

    private fun findMp4Box(source: SeekableByteSource, rangeStart: Long, rangeEnd: Long, targetType: String): Mp4Box? {
        var cursor = rangeStart
        var boxesVisited = 0
        while (cursor + 8L <= rangeEnd && boxesVisited < 256) {
            boxesVisited++
            val box = readMp4BoxHeader(source, cursor, rangeEnd) ?: return null
            if (box.type == targetType) return box
            if (box.boxEnd <= cursor) return null
            cursor = box.boxEnd
        }
        return null
    }

    private fun readMp4BoxHeader(source: SeekableByteSource, offset: Long, rangeEnd: Long): Mp4Box? {
        if (offset + 8L > rangeEnd) return null
        val hdr = ByteArray(8)
        if (readFully(source, offset, hdr, 8) < 8) return null
        val rawSize = readUInt32BE(hdr, 0)
        val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
        val bounds = resolveMp4BoxBounds(source, offset, rawSize, rangeEnd) ?: return null
        return Mp4Box(type = type, payloadStart = bounds.first, boxEnd = bounds.second)
    }

    private fun resolveMp4BoxBounds(source: SeekableByteSource, offset: Long, rawSize: Long, rangeEnd: Long): Pair<Long, Long>? {
        val payloadStart: Long
        val boxEnd: Long
        when (rawSize) {
            0L -> {
                payloadStart = offset + 8L
                boxEnd = rangeEnd
            }
            1L -> {
                if (offset + 16L > rangeEnd) return null
                val ext = ByteArray(8)
                if (readFully(source, offset + 8L, ext, 8) < 8) return null
                val largeSize = readInt64BE(ext, 0)
                if (largeSize < 16L) return null
                payloadStart = offset + 16L
                boxEnd = minOf(offset + largeSize, rangeEnd)
            }
            else -> {
                if (rawSize < 8L) return null
                payloadStart = offset + 8L
                boxEnd = minOf(offset + rawSize, rangeEnd)
            }
        }
        return if (payloadStart <= boxEnd) payloadStart to boxEnd else null
    }

    // -------------------------------------------------------------------------
    // Fault-tolerant numeric tag value parsers
    // -------------------------------------------------------------------------

    internal fun parseGainDb(raw: String): Float? {
        val cleaned = raw.trim()
            .replace("dB", "", ignoreCase = true)
            .trim()
            .replace(',', '.')
        val value = cleaned.toFloatOrNull() ?: return null
        if (!value.isFinite() || value < -60.0f || value > 60.0f) return null
        return value
    }

    internal fun parsePeak(raw: String): Float? {
        val cleaned = raw.trim().replace(',', '.')
        val value = cleaned.toFloatOrNull() ?: return null
        if (!value.isFinite() || value <= 0.0f || value > 10.0f) return null
        return value
    }

    /**
     * Parses RFC 7845 Opus `R128_TRACK_GAIN` / `R128_ALBUM_GAIN` Q7.8 signed integer strings
     * (in 1/256 dB units relative to -23 LUFS) and converts to ReplayGain (-18 LUFS) reference.
     */
    internal fun parseR128GainDb(raw: String): Float? {
        val q78 = raw.trim().toIntOrNull() ?: return null
        if (q78 < -32768 || q78 > 32767) return null
        val db = (q78 / 256.0f) + R128_TO_REPLAYGAIN_OFFSET_DB
        if (!db.isFinite() || db < -60.0f || db > 60.0f) return null
        return db
    }

    /**
     * Optional Apple Sound Check (`iTunNORM`) fallback parser converting the first two hex loudness
     * fields into an approximate ReplayGain dB adjustment.
     */
    internal fun parseITunNormGainDb(raw: String): Float? {
        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size < 2) return null
        val v0 = tokens[0].toLongOrNull(16) ?: return null
        val v1 = tokens[1].toLongOrNull(16) ?: return null
        val maxLoudness = maxOf(v0, v1)
        if (maxLoudness <= 0L) return null
        val db = (-10.0 * log10(maxLoudness.toDouble() / 1000.0)).toFloat()
        if (!db.isFinite() || db < -60.0f || db > 60.0f) return null
        return db
    }

    // -------------------------------------------------------------------------
    // Binary Helpers
    // -------------------------------------------------------------------------

    private fun readFully(source: SeekableByteSource, startOffset: Long, buf: ByteArray, len: Int): Int {
        var total = 0
        while (total < len) {
            val read = source.readAt(startOffset + total, buf, total, len - total)
            if (read <= 0) break
            total += read
        }
        return total
    }

    private fun matchesAscii(buf: ByteArray, offset: Int, ascii: String): Boolean {
        if (offset < 0 || offset + ascii.length > buf.size) return false
        for (i in ascii.indices) {
            if ((buf[offset + i].toInt() and 0xFF) != ascii[i].code) return false
        }
        return true
    }

    private fun readSynchsafeInt(buf: ByteArray, offset: Int): Int = ((buf[offset].toInt() and 0x7F) shl 21) or
        ((buf[offset + 1].toInt() and 0x7F) shl 14) or
        ((buf[offset + 2].toInt() and 0x7F) shl 7) or
        (buf[offset + 3].toInt() and 0x7F)

    private fun readInt32BE(buf: ByteArray, offset: Int): Int = ((buf[offset].toInt() and 0xFF) shl 24) or
        ((buf[offset + 1].toInt() and 0xFF) shl 16) or
        ((buf[offset + 2].toInt() and 0xFF) shl 8) or
        (buf[offset + 3].toInt() and 0xFF)

    private fun readUInt32BE(buf: ByteArray, offset: Int): Long = readInt32BE(buf, offset).toLong() and 0xFFFFFFFFL

    private fun readInt64BE(buf: ByteArray, offset: Int): Long = ((buf[offset].toLong() and 0xFF) shl 56) or
        ((buf[offset + 1].toLong() and 0xFF) shl 48) or
        ((buf[offset + 2].toLong() and 0xFF) shl 40) or
        ((buf[offset + 3].toLong() and 0xFF) shl 32) or
        ((buf[offset + 4].toLong() and 0xFF) shl 24) or
        ((buf[offset + 5].toLong() and 0xFF) shl 16) or
        ((buf[offset + 6].toLong() and 0xFF) shl 8) or
        (buf[offset + 7].toLong() and 0xFF)

    private fun readInt32LE(buf: ByteArray, offset: Int): Int = (buf[offset].toInt() and 0xFF) or
        ((buf[offset + 1].toInt() and 0xFF) shl 8) or
        ((buf[offset + 2].toInt() and 0xFF) shl 16) or
        ((buf[offset + 3].toInt() and 0xFF) shl 24)

    private fun findSingleByteNul(data: ByteArray, start: Int, end: Int): Int {
        for (i in start until end) {
            if (data[i] == 0.toByte()) return i
        }
        return -1
    }

    private fun findDoubleByteNul(data: ByteArray, start: Int, end: Int): Int {
        var i = start
        while (i + 1 < end) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) return i
            i += 2
        }
        return -1
    }

    private fun trimTrailingNuls(data: ByteArray, start: Int, end: Int): Int {
        var idx = end
        while (idx > start && data[idx - 1] == 0.toByte()) {
            idx--
        }
        return idx
    }

    private class ByteArrayAccumulator(private val maxCapacity: Int) {
        private var buffer = ByteArray(1024)
        private var size = 0

        fun remainingCapacity(): Int = (maxCapacity - size).coerceAtLeast(0)

        fun append(src: ByteArray, offset: Int, length: Int) {
            val toCopy = minOf(length, remainingCapacity())
            if (toCopy <= 0) return
            if (size + toCopy > buffer.size) {
                var newCap = buffer.size
                while (newCap < size + toCopy) {
                    newCap = minOf(newCap * 2, maxCapacity)
                }
                buffer = buffer.copyOf(newCap)
            }
            System.arraycopy(src, offset, buffer, size, toCopy)
            size += toCopy
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)

        fun reset() {
            size = 0
        }
    }
}
