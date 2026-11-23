package com.cangkir.bitperfectplayer.library

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Standard embedded artwork slots mapped to FLAC `METADATA_BLOCK_PICTURE` and ID3v2 `APIC`
 * picture type codes.
 */
enum class EmbeddedArtworkSlot(val pictureTypePriority: IntArray) {
    FRONT_COVER(intArrayOf(3)),
    BACK_COVER(intArrayOf(4)),
    DISC(intArrayOf(6)),

    /** Priority order: Artist/Performer (8), Lead Artist (7), Band/Orchestra (10), Band Logotype (19). */
    ARTIST(intArrayOf(8, 7, 10, 19)),
    ;

    fun priorityRankOf(pictureType: Int): Int = pictureTypePriority.indexOf(pictureType)
}

/**
 * Pure-Kotlin, zero-dependency, header-only binary parser extracting embedded multi-artwork slots
 * (`FRONT_COVER`, `BACK_COVER`, `DISC`, `ARTIST`) from FLAC (`METADATA_BLOCK_PICTURE`) and
 * MP3 (`ID3` v2.3 & v2.4 `APIC`) audio headers on demand (zero `android.*` / `androidx.*` imports).
 *
 * Safety & Performance Guarantees:
 * - Stops reading immediately after audio metadata headers (never reads audio frame data).
 * - Enforces a strict [MAX_PICTURE_BYTES] (`8 MB`) cap per picture block and skips non-matching
 *   blocks without allocating image buffers.
 * - Gracefully returns `null` / empty map on unsupported containers (WAV/M4A/OGG), corrupted
 *   headers, truncated streams, or oversized blocks without throwing uncaught exceptions.
 */
object EmbeddedArtworkParser {
    const val MAX_PICTURE_BYTES: Int = 8 * 1024 * 1024

    private const val MAX_ID3_TAG_BYTES: Int = 32 * 1024 * 1024
    private const val MAX_FLAC_METADATA_BLOCKS: Int = 64
    private const val MAX_ID3_FRAMES: Int = 512
    private const val MAX_HEADER_STRING_BYTES: Int = 64 * 1024
    private const val FLAC_PICTURE_BLOCK_TYPE: Int = 6
    private const val SKIP_SCRATCH_SIZE: Int = 4096

    private class CandidateMatch(val priorityRank: Int, val bytes: ByteArray)

    fun extract(bytes: ByteArray, slot: EmbeddedArtworkSlot): ByteArray? = extract(ByteArrayInputStream(bytes), slot)

    fun extract(input: InputStream, slot: EmbeddedArtworkSlot): ByteArray? = extractMultiple(input, setOf(slot))[slot]

    fun extractMultiple(bytes: ByteArray, slots: Set<EmbeddedArtworkSlot>): Map<EmbeddedArtworkSlot, ByteArray> =
        extractMultiple(ByteArrayInputStream(bytes), slots)

    fun extractMultiple(input: InputStream, slots: Set<EmbeddedArtworkSlot>): Map<EmbeddedArtworkSlot, ByteArray> {
        if (slots.isEmpty()) return emptyMap()
        val matches = LinkedHashMap<EmbeddedArtworkSlot, CandidateMatch>(slots.size)
        try {
            val magic = ByteArray(4)
            if (readExact(input, magic, 4) < 4) return emptyMap()

            when {
                isFlacMagic(magic) -> parseFlacMetadataBlocks(input, slots, matches)
                isId3Magic(magic) -> {
                    parseId3v2Tag(input, firstFourBytes = magic, slots = slots, matches = matches)
                    // Some FLAC files carry a leading ID3v2 wrapper before the `fLaC` marker.
                    if (!allSlotsHaveTopPriority(slots, matches)) {
                        val afterId3 = ByteArray(4)
                        if (readExact(input, afterId3, 4) == 4 && isFlacMagic(afterId3)) {
                            parseFlacMetadataBlocks(input, slots, matches)
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // Return any already-validated matches safely; never crash on truncated/unreadable streams.
        } catch (_: IllegalArgumentException) {
            // Defensively ignore malformed header offsets/lengths.
        }
        if (matches.isEmpty()) return emptyMap()
        val result = LinkedHashMap<EmbeddedArtworkSlot, ByteArray>(matches.size)
        for ((slot, match) in matches) {
            result[slot] = match.bytes
        }
        return result
    }

    private fun parseFlacMetadataBlocks(
        input: InputStream,
        slots: Set<EmbeddedArtworkSlot>,
        matches: MutableMap<EmbeddedArtworkSlot, CandidateMatch>,
    ) {
        val blockHeader = ByteArray(4)
        var blocksVisited = 0
        var isLast = false
        while (!isLast && blocksVisited < MAX_FLAC_METADATA_BLOCKS && !allSlotsHaveTopPriority(slots, matches)) {
            if (readExact(input, blockHeader, 4) < 4) return
            blocksVisited++
            isLast = (blockHeader[0].toInt() and 0x80) != 0
            val blockType = blockHeader[0].toInt() and 0x7F
            val blockLength = readUint24Be(blockHeader, 0)
            if (blockLength < 0) return

            if (blockType == FLAC_PICTURE_BLOCK_TYPE) {
                if (!parseFlacPictureBlock(input, blockLength, slots, matches)) return
            } else {
                if (!skipExact(input, blockLength.toLong())) return
            }
        }
    }

    private data class FlacPictureDescriptor(val pictureType: Int, val dataLen: Int, val remainingBytes: Int, val streamOk: Boolean = true)

    private fun parseFlacPictureBlock(
        input: InputStream,
        blockLength: Int,
        slots: Set<EmbeddedArtworkSlot>,
        matches: MutableMap<EmbeddedArtworkSlot, CandidateMatch>,
    ): Boolean {
        if (blockLength < 32) return skipExact(input, blockLength.toLong())
        val desc = readFlacPictureDescriptor(input, blockLength)
        if (!desc.streamOk) return false

        val targetSlot = if (desc.dataLen in 1..MAX_PICTURE_BYTES && desc.dataLen <= desc.remainingBytes) {
            findBetterMatchingSlot(desc.pictureType, slots, matches)
        } else {
            null
        }
        if (targetSlot == null) {
            return skipExact(input, desc.remainingBytes.coerceAtLeast(0).toLong())
        }

        val imageBytes = ByteArray(desc.dataLen)
        if (readExact(input, imageBytes, desc.dataLen) < desc.dataLen) return false
        recordMatch(targetSlot, desc.pictureType, imageBytes, matches)
        val trailing = desc.remainingBytes - desc.dataLen
        return trailing <= 0 || skipExact(input, trailing.toLong())
    }

    private fun readFlacPictureDescriptor(input: InputStream, blockLength: Int): FlacPictureDescriptor {
        val pictureType = readInt32Be(input) ?: return FlacPictureDescriptor(0, 0, 0, streamOk = false)
        val mimeLen = readInt32Be(input) ?: return FlacPictureDescriptor(0, 0, 0, streamOk = false)
        var remaining = blockLength - 8
        val mimeValid = mimeLen in 0..MAX_HEADER_STRING_BYTES && mimeLen <= remaining
        if (!mimeValid) return FlacPictureDescriptor(pictureType, 0, remaining)
        if (!skipExact(input, mimeLen.toLong())) return FlacPictureDescriptor(0, 0, 0, streamOk = false)
        remaining -= mimeLen

        val descLen = if (remaining >= 24) readInt32Be(input) else null
        return when {
            remaining < 24 -> FlacPictureDescriptor(pictureType, 0, remaining)
            descLen == null -> FlacPictureDescriptor(0, 0, 0, streamOk = false)
            descLen !in 0..MAX_HEADER_STRING_BYTES || descLen + 20 > remaining - 4 ->
                FlacPictureDescriptor(pictureType, 0, remaining - 4)
            !skipExact(input, descLen.toLong() + 16L) -> FlacPictureDescriptor(0, 0, 0, streamOk = false)
            else -> {
                val dataLen = readInt32Be(input)
                if (dataLen == null) {
                    FlacPictureDescriptor(0, 0, 0, streamOk = false)
                } else {
                    FlacPictureDescriptor(pictureType, dataLen, remaining - 4 - descLen - 16 - 4)
                }
            }
        }
    }

    private fun parseId3v2Tag(
        input: InputStream,
        firstFourBytes: ByteArray,
        slots: Set<EmbeddedArtworkSlot>,
        matches: MutableMap<EmbeddedArtworkSlot, CandidateMatch>,
    ) {
        val restHeader = ByteArray(6)
        if (readExact(input, restHeader, 6) < 6) return

        val majorVersion = firstFourBytes[3].toInt() and 0xFF
        val flags = restHeader[1].toInt() and 0xFF
        val tagSize = readSynchsafeInt32(restHeader, 2)
        if (tagSize <= 0 || tagSize > MAX_ID3_TAG_BYTES) return

        var remaining = tagSize
        val supportedVersion = majorVersion == 3 || majorVersion == 4
        if (!supportedVersion) {
            skipExact(input, remaining.toLong())
            return
        }

        val hasExtendedHeader = (flags and 0x40) != 0
        if (hasExtendedHeader) {
            val extConsumed = skipId3ExtendedHeader(input, majorVersion, remaining) ?: return
            remaining -= extConsumed
        }

        val tagUnsync = (flags and 0x80) != 0
        val frameHeader = ByteArray(10)
        var framesVisited = 0

        while (remaining >= 10 && framesVisited < MAX_ID3_FRAMES) {
            if (readExact(input, frameHeader, 10) < 10) return
            remaining -= 10
            framesVisited++

            // Padding starts with 0x00 bytes — no more frames exist in this ID3 tag.
            if (frameHeader[0].toInt() == 0) {
                skipExact(input, remaining.toLong())
                return
            }

            val frameSize = decodeId3FrameSize(frameHeader, majorVersion)
            if (frameSize <= 0 || frameSize > remaining) {
                skipExact(input, remaining.coerceAtLeast(0).toLong())
                return
            }

            val isApic = frameHeader[0] == 'A'.code.toByte() &&
                frameHeader[1] == 'P'.code.toByte() &&
                frameHeader[2] == 'I'.code.toByte() &&
                frameHeader[3] == 'C'.code.toByte()

            if (isApic && frameSize <= MAX_PICTURE_BYTES + MAX_HEADER_STRING_BYTES && !allSlotsHaveTopPriority(slots, matches)) {
                val frameBytes = ByteArray(frameSize)
                if (readExact(input, frameBytes, frameSize) < frameSize) return
                remaining -= frameSize
                parseId3ApicFramePayload(
                    rawPayload = frameBytes,
                    majorVersion = majorVersion,
                    frameFlagsLo = frameHeader[9].toInt() and 0xFF,
                    tagUnsync = tagUnsync,
                    slots = slots,
                    matches = matches,
                )
            } else {
                if (!skipExact(input, frameSize.toLong())) return
                remaining -= frameSize
            }
        }

        if (remaining > 0) {
            skipExact(input, remaining.toLong())
        }
    }

    private fun skipId3ExtendedHeader(input: InputStream, majorVersion: Int, remainingTagBytes: Int): Int? {
        if (remainingTagBytes < 4) return null
        val sizeBytes = ByteArray(4)
        if (readExact(input, sizeBytes, 4) < 4) return null
        val rawSize = if (majorVersion == 4) {
            readSynchsafeInt32(sizeBytes, 0)
        } else {
            readRegularInt32Be(sizeBytes, 0)
        }
        // ID3v2.4 extended header size includes the 4 size bytes; v2.3 excludes them.
        val bodyToSkip = if (majorVersion == 4) rawSize - 4 else rawSize
        if (bodyToSkip < 0 || bodyToSkip + 4 > remainingTagBytes) return null
        if (!skipExact(input, bodyToSkip.toLong())) return null
        return 4 + bodyToSkip
    }

    private fun parseId3ApicFramePayload(
        rawPayload: ByteArray,
        majorVersion: Int,
        frameFlagsLo: Int,
        tagUnsync: Boolean,
        slots: Set<EmbeddedArtworkSlot>,
        matches: MutableMap<EmbeddedArtworkSlot, CandidateMatch>,
    ) {
        val isCompressedOrEncrypted = if (majorVersion == 4) {
            (frameFlagsLo and 0x0C) != 0
        } else {
            (frameFlagsLo and 0xC0) != 0
        }
        if (isCompressedOrEncrypted) return

        val hasDataLengthIndicator = majorVersion == 4 && (frameFlagsLo and 0x01) != 0
        val frameUnsync = majorVersion == 4 && (frameFlagsLo and 0x02) != 0
        val payload = if (tagUnsync || frameUnsync) undoUnsynchronization(rawPayload) else rawPayload

        var offset = if (hasDataLengthIndicator) 4 else 0
        if (offset + 4 > payload.size) return

        val textEncoding = payload[offset].toInt() and 0xFF
        offset++

        // Null-terminated ASCII MIME type
        val mimeEnd = findSingleZero(payload, offset)
        if (mimeEnd < 0 || mimeEnd + 2 >= payload.size) return
        offset = mimeEnd + 1

        val pictureType = payload[offset].toInt() and 0xFF
        offset++

        val targetSlot = findBetterMatchingSlot(pictureType, slots, matches) ?: return

        val descEnd = findDescriptionTerminatorEnd(payload, offset, textEncoding)
        if (descEnd < 0 || descEnd >= payload.size) return

        val imageLength = payload.size - descEnd
        if (imageLength !in 1..MAX_PICTURE_BYTES) return

        val imageBytes = payload.copyOfRange(descEnd, payload.size)
        recordMatch(targetSlot, pictureType, imageBytes, matches)
    }

    private fun findDescriptionTerminatorEnd(payload: ByteArray, start: Int, textEncoding: Int): Int {
        // Encodings 1 (UTF-16 with BOM) and 2 (UTF-16BE) terminate with 2-byte 0x00 0x00.
        val isUtf16 = textEncoding == 1 || textEncoding == 2
        if (!isUtf16) {
            val zeroIdx = findSingleZero(payload, start)
            return if (zeroIdx >= 0) zeroIdx + 1 else -1
        }
        var idx = start
        while (idx + 1 < payload.size) {
            if (payload[idx].toInt() == 0 && payload[idx + 1].toInt() == 0) {
                return idx + 2
            }
            idx += 2
        }
        return -1
    }

    private fun findSingleZero(data: ByteArray, start: Int): Int {
        for (i in start until data.size) {
            if (data[i].toInt() == 0) return i
        }
        return -1
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

    private fun findBetterMatchingSlot(
        pictureType: Int,
        slots: Set<EmbeddedArtworkSlot>,
        matches: Map<EmbeddedArtworkSlot, CandidateMatch>,
    ): EmbeddedArtworkSlot? {
        for (slot in slots) {
            val rank = slot.priorityRankOf(pictureType)
            if (rank >= 0) {
                val existing = matches[slot]
                if (existing == null || rank < existing.priorityRank) {
                    return slot
                }
            }
        }
        return null
    }

    private fun recordMatch(
        slot: EmbeddedArtworkSlot,
        pictureType: Int,
        bytes: ByteArray,
        matches: MutableMap<EmbeddedArtworkSlot, CandidateMatch>,
    ) {
        val rank = slot.priorityRankOf(pictureType)
        if (rank < 0) return
        val existing = matches[slot]
        if (existing == null || rank < existing.priorityRank) {
            matches[slot] = CandidateMatch(priorityRank = rank, bytes = bytes)
        }
    }

    private fun allSlotsHaveTopPriority(slots: Set<EmbeddedArtworkSlot>, matches: Map<EmbeddedArtworkSlot, CandidateMatch>): Boolean =
        slots.all { slot -> matches[slot]?.priorityRank == 0 }

    private fun decodeId3FrameSize(header: ByteArray, majorVersion: Int): Int {
        if (majorVersion != 4) return readRegularInt32Be(header, 4)
        // Standard ID3v2.4 uses synchsafe integers; if any MSB is set, fall back to regular 32-bit BE.
        val hasNonSynchsafeBit = ((header[4].toInt() or header[5].toInt() or header[6].toInt() or header[7].toInt()) and 0x80) != 0
        return if (hasNonSynchsafeBit) {
            readRegularInt32Be(header, 4)
        } else {
            readSynchsafeInt32(header, 4)
        }
    }

    private fun isFlacMagic(b: ByteArray): Boolean =
        b[0] == 'f'.code.toByte() && b[1] == 'L'.code.toByte() && b[2] == 'a'.code.toByte() && b[3] == 'C'.code.toByte()

    private fun isId3Magic(b: ByteArray): Boolean = b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()

    private fun readUint24Be(blockHeader: ByteArray, startOffset: Int): Int = ((blockHeader[startOffset + 1].toInt() and 0xFF) shl 16) or
        ((blockHeader[startOffset + 2].toInt() and 0xFF) shl 8) or
        (blockHeader[startOffset + 3].toInt() and 0xFF)

    private fun readRegularInt32Be(bytes: ByteArray, offset: Int): Int = ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)

    private fun readSynchsafeInt32(bytes: ByteArray, offset: Int): Int = ((bytes[offset].toInt() and 0x7F) shl 21) or
        ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
        ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
        (bytes[offset + 3].toInt() and 0x7F)

    private fun readInt32Be(input: InputStream): Int? {
        val buf = ByteArray(4)
        if (readExact(input, buf, 4) < 4) return null
        return readRegularInt32Be(buf, 0)
    }

    private fun readExact(input: InputStream, target: ByteArray, length: Int): Int {
        var total = 0
        while (total < length) {
            val read = input.read(target, total, length - total)
            if (read <= 0) break
            total += read
        }
        return total
    }

    private fun skipExact(input: InputStream, bytesToSkip: Long): Boolean {
        var remaining = bytesToSkip
        val scratch = ByteArray(SKIP_SCRATCH_SIZE)
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val toRead = minOf(remaining, scratch.size.toLong()).toInt()
                val read = input.read(scratch, 0, toRead)
                if (read <= 0) return false
                remaining -= read.toLong()
            }
        }
        return true
    }
}
