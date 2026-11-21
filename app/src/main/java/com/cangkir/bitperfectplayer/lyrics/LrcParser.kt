package com.cangkir.bitperfectplayer.lyrics

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Metadata extracted from standard `.lrc` header tags (`[ti:]`, `[ar:]`, `[al:]`, `[offset:]`).
 */
data class LrcMetadata(val title: String? = null, val artist: String? = null, val album: String? = null, val offsetMs: Long = 0L)

/**
 * A single synchronized lyric line with its effective playback timestamp in milliseconds.
 */
data class LrcLine(val timestampMs: Long, val text: String)

/**
 * Result of parsing `.lrc` or plain-text lyrics.
 *
 * When [isSynced] is `true`, [lines] contains chronologically sorted timestamped lines.
 * When [isSynced] is `false`, [lines] is empty and [plainLines] contains clean static text lines.
 */
data class ParsedLyrics(
    val lines: List<LrcLine>,
    val plainLines: List<String>,
    val isSynced: Boolean,
    val metadata: LrcMetadata,
    val rawText: String,
)

/**
 * Fault-tolerant, pure-Kotlin `.lrc` parser and active-line resolver (zero `android.*` imports).
 *
 * Offset convention follows the LRC specification: positive `[offset:+N]` shifts lyrics earlier
 * (`effectiveTimestampMs = (rawTimestampMs - offsetMs).coerceAtLeast(0L)`), while negative
 * `[offset:-N]` delays lyrics.
 */
object LrcParser {

    // Matches leading or inline bracket tokens like [01:23.45], [ti:Song], [offset:-250], or malformed tags.
    private val BRACKET_TAG_REGEX = Regex("""\[([^\[\]\r\n]+)]""")

    // Strict timestamp syntax: mm:ss, mm:ss.x, mm:ss.xx, mm:ss.xxx, or mm:ss:xx (colon fraction variant).
    private val TIMESTAMP_REGEX = Regex("""^(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?$""")
    private val METADATA_TAG_REGEX = Regex("""^(ti|ar|al|offset|by|length|re|ve)\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)

    /**
     * Decodes raw `.lrc` bytes handling UTF-8 BOM, UTF-16BE BOM, UTF-16LE BOM,
     * BOM-less UTF-16 heuristic, strict UTF-8, and ISO-8859-1 fallback. Never throws.
     */
    fun decodeBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        // 1. UTF-8 BOM (EF BB BF)
        if (hasUtf8Bom(bytes)) {
            return decodeUtf8OrLatin1(bytes, offset = 3)
        }

        // 2. UTF-16BE BOM (FE FF)
        if (bytes.size >= 2 &&
            (bytes[0].toInt() and 0xFF) == 0xFE &&
            (bytes[1].toInt() and 0xFF) == 0xFF
        ) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }

        // 3. UTF-16LE BOM (FF FE)
        if (bytes.size >= 2 &&
            (bytes[0].toInt() and 0xFF) == 0xFF &&
            (bytes[1].toInt() and 0xFF) == 0xFE
        ) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }

        // 4. Heuristic for BOM-less UTF-16 (even byte length with strong NUL parity pattern)
        detectBomlessUtf16(bytes)?.let { charset ->
            return String(bytes, charset)
        }

        // 5. Strict UTF-8, falling back cleanly to ISO-8859-1
        return decodeUtf8OrLatin1(bytes, offset = 0)
    }

    private fun hasUtf8Bom(bytes: ByteArray): Boolean {
        if (bytes.size < 3) return false
        return (bytes[0].toInt() and 0xFF) == 0xEF &&
            (bytes[1].toInt() and 0xFF) == 0xBB &&
            (bytes[2].toInt() and 0xFF) == 0xBF
    }

    /**
     * Parses raw `.lrc` bytes directly without crashing on any encoding or malformed content.
     */
    fun parseBytes(bytes: ByteArray): ParsedLyrics = parse(decodeBytes(bytes))

    /**
     * Parses `.lrc` or plain-text lyrics string into a [ParsedLyrics] representation.
     * Never throws on empty, corrupted, or untimestamped input.
     */
    fun parse(rawInput: String): ParsedLyrics {
        val normalized = rawInput
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        if (normalized.isBlank()) {
            return ParsedLyrics(
                lines = emptyList(),
                plainLines = emptyList(),
                isSynced = false,
                metadata = LrcMetadata(),
                rawText = normalized,
            )
        }

        val metadataBuilder = MutableLrcMetadata()
        val rawTimedEntries = mutableListOf<RawTimedEntry>()
        val staticCandidates = mutableListOf<String>()
        var sequenceCounter = 0

        for (rawLine in normalized.split('\n')) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) {
                staticCandidates.add("")
                continue
            }

            val parsedLine = parseLeadingBracketTags(trimmed, metadataBuilder)
            if (parsedLine.timestamps.isNotEmpty()) {
                val lyricText = trimmed.substring(parsedLine.textStartIndex).trim()
                for (ts in parsedLine.timestamps) {
                    rawTimedEntries.add(RawTimedEntry(ts, sequenceCounter++, lyricText))
                }
            } else if (!parsedLine.matchedMetadataOnly) {
                staticCandidates.add(trimmed)
            }
        }

        val metadata = metadataBuilder.toImmutable()
        if (rawTimedEntries.isNotEmpty()) {
            val syncedLines = rawTimedEntries
                .map { entry ->
                    val adjusted = (entry.rawTimestampMs - metadata.offsetMs).coerceAtLeast(0L)
                    IndexedLrcLine(adjusted, entry.order, entry.text)
                }
                .sortedWith(compareBy<IndexedLrcLine> { it.timestampMs }.thenBy { it.order })
                .map { LrcLine(timestampMs = it.timestampMs, text = it.text) }

            return ParsedLyrics(
                lines = syncedLines,
                plainLines = syncedLines.map { it.text },
                isSynced = true,
                metadata = metadata,
                rawText = normalized,
            )
        }

        // Untimestamped plain text fallback: trim leading/trailing blank lines while preserving stanza breaks.
        val cleanedPlain = trimBlankEdges(staticCandidates)
        return ParsedLyrics(
            lines = emptyList(),
            plainLines = cleanedPlain,
            isSynced = false,
            metadata = metadata,
            rawText = normalized,
        )
    }

    private fun parseLeadingBracketTags(trimmed: String, metadata: MutableLrcMetadata): ParsedLineTags {
        val timestampsOnLine = mutableListOf<Long>()
        var matchedMetadataOnly = false
        var idx = 0
        var scanning = true

        // Consume leading consecutive bracket tokens (multi-timestamp or metadata headers).
        while (scanning && idx < trimmed.length && trimmed[idx] == '[') {
            val match = BRACKET_TAG_REGEX.matchAt(trimmed, idx)
            if (match == null) {
                scanning = false
            } else {
                val token = match.groupValues[1].trim()
                val parsedTs = parseTimestampToken(token)
                val metaMatch = if (parsedTs == null && timestampsOnLine.isEmpty()) {
                    METADATA_TAG_REGEX.matchEntire(token)
                } else {
                    null
                }
                when {
                    parsedTs != null -> {
                        timestampsOnLine.add(parsedTs)
                        idx = skipSpaces(trimmed, match.range.last + 1)
                    }
                    metaMatch != null -> {
                        metadata.applyTag(
                            key = metaMatch.groupValues[1].lowercase(),
                            value = metaMatch.groupValues[2].trim(),
                        )
                        idx = skipSpaces(trimmed, match.range.last + 1)
                        if (idx >= trimmed.length) {
                            matchedMetadataOnly = true
                            scanning = false
                        }
                    }
                    else -> {
                        // Malformed/unknown bracket token: stop consuming tags and preserve remaining text safely.
                        scanning = false
                    }
                }
            }
        }
        return ParsedLineTags(timestampsOnLine, idx, matchedMetadataOnly)
    }

    private fun skipSpaces(text: String, startIndex: Int): Int {
        var idx = startIndex
        while (idx < text.length && text[idx] == ' ') idx++
        return idx
    }

    private class MutableLrcMetadata {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var offsetMs: Long = 0L

        fun applyTag(key: String, value: String) {
            when (key) {
                "ti" -> if (value.isNotEmpty()) title = value
                "ar" -> if (value.isNotEmpty()) artist = value
                "al" -> if (value.isNotEmpty()) album = value
                "offset" -> value.toLongOrNull()?.let { offsetMs = it }
            }
        }

        fun toImmutable(): LrcMetadata = LrcMetadata(title = title, artist = artist, album = album, offsetMs = offsetMs)
    }

    private data class ParsedLineTags(val timestamps: List<Long>, val textStartIndex: Int, val matchedMetadataOnly: Boolean)

    /**
     * Deterministically resolves the active lyric line index for [positionMs] using binary search.
     *
     * Returns `-1` if [lines] is empty or [positionMs] precedes the first line timestamp.
     * Supports arbitrary forward and backward seeks in O(log n) time.
     */
    fun findActiveLineIndex(lines: List<LrcLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        if (positionMs < lines.first().timestampMs) return -1

        var low = 0
        var high = lines.lastIndex
        var result = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timestampMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    internal fun parseTimestampToken(token: String): Long? {
        val match = TIMESTAMP_REGEX.matchEntire(token.trim()) ?: return null
        val minutes = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = match.groupValues[2].toLongOrNull() ?: return null
        if (seconds !in 0L..59L) return null

        val fracRaw = match.groupValues[3]
        val millis = when (fracRaw.length) {
            0 -> 0L
            1 -> (fracRaw.toLongOrNull() ?: return null) * 100L
            2 -> (fracRaw.toLongOrNull() ?: return null) * 10L
            3 -> fracRaw.toLongOrNull() ?: return null
            else -> return null
        }
        return (minutes * 60_000L) + (seconds * 1_000L) + millis
    }

    private fun decodeUtf8OrLatin1(bytes: ByteArray, offset: Int): String {
        val len = bytes.size - offset
        if (len <= 0) return ""
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, offset, len)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, offset, len, Charsets.ISO_8859_1)
        }
    }

    private fun detectBomlessUtf16(bytes: ByteArray): java.nio.charset.Charset? {
        if (bytes.size < 4 || bytes.size % 2 != 0) return null
        val samplePairs = minOf(bytes.size / 2, 128)
        var evenNulls = 0
        var oddNulls = 0
        for (i in 0 until samplePairs) {
            if (bytes[i * 2] == 0.toByte()) evenNulls++
            if (bytes[i * 2 + 1] == 0.toByte()) oddNulls++
        }
        val threshold = (samplePairs * 3) / 4
        return when {
            evenNulls >= threshold && oddNulls == 0 -> Charsets.UTF_16BE
            oddNulls >= threshold && evenNulls == 0 -> Charsets.UTF_16LE
            else -> null
        }
    }

    private fun trimBlankEdges(lines: List<String>): List<String> {
        val firstNonBlank = lines.indexOfFirst { it.isNotBlank() }
        if (firstNonBlank == -1) return emptyList()
        val lastNonBlank = lines.indexOfLast { it.isNotBlank() }
        return lines.subList(firstNonBlank, lastNonBlank + 1)
    }

    private data class RawTimedEntry(val rawTimestampMs: Long, val order: Int, val text: String)

    private data class IndexedLrcLine(val timestampMs: Long, val order: Int, val text: String)
}
