package com.cangkir.bitperfectplayer.audio

/**
 * Pure-Kotlin parsed disc & track position (0 means absent/unspecified).
 */
data class ParsedTrackPosition(
    val discNumber: Int = 0,
    val trackNumber: Int = 0,
) {
    val hasPosition: Boolean
        get() = discNumber > 0 || trackNumber > 0
}

/**
 * Pure-Kotlin parser for audio track & disc numbers across MediaStore packed integers,
 * text metadata tags (`"3/12"`, `"03"`, `"1/2"`), and leading filename prefixes (zero `android.*` imports).
 */
object TrackNumberParser {
    private const val MAX_REASONABLE_TRACK_OR_DISC = 999

    // Matches explicit compact disc-track prefix like "1-04 - Title.flac" or "02-05 Title.flac"
    private val FILENAME_DISC_TRACK_REGEX = Regex("""^(\d{1,2})-(\d{2,3})(?:[\s._)\]-]+|$)""")

    // Matches leading "04 - Acid Rain.flac", "01. Intro.wav", "03_Song.mp3", "12 Song.m4a"
    // Strictly requires 1..3 digits followed by a separator (excludes 4-digit years like "2024 - Live"
    // and ordinal words like "21st Century Breakdown").
    private val FILENAME_TRACK_REGEX = Regex("""^\s*(\d{1,3})(?:[\s._)\]-]+|$)""")

    /**
     * Unpacks Android's `MediaStore.Audio.Media.TRACK` integer encoding where
     * `disc = raw / 1000` and `track = raw % 1000` (e.g., `1003` -> disc 1, track 3;
     * `2004` -> disc 2, track 4; `7` -> disc 0, track 7).
     */
    fun parseMediaStorePackedTrack(rawTrack: Int): ParsedTrackPosition {
        if (rawTrack <= 0) return ParsedTrackPosition()
        val disc = (rawTrack / 1000).coerceAtLeast(0)
        val track = (rawTrack % 1000).coerceAtLeast(0)
        return ParsedTrackPosition(
            discNumber = if (disc <= MAX_REASONABLE_TRACK_OR_DISC) disc else 0,
            trackNumber = if (track <= MAX_REASONABLE_TRACK_OR_DISC) track else 0,
        )
    }

    /**
     * Parses a metadata string tag such as `"3/12"`, `"03"`, `" 1 / 2 "`, `"2 of 3"`, or `"7"`.
     * Never throws on malformed, negative, or overflowing text.
     */
    fun parseTagNumber(raw: String?): Int {
        if (raw.isNullOrBlank()) return 0
        val trimmed = raw.trim()
        // Take the leading segment before '/', 'of', or whitespace
        val slashSplit = trimmed.substringBefore('/').trim()
        if (slashSplit.isEmpty()) return 0
        // Extract contiguous leading digits only if the token begins with digits (reject "-3" or "abc")
        var end = 0
        while (end < slashSplit.length && slashSplit[end].isDigit()) {
            end++
        }
        if (end == 0) return 0
        // Reject tokens where digits are immediately glued to letters (e.g., "21st")
        if (end < slashSplit.length && slashSplit[end].isLetter()) return 0

        val parsed = slashSplit.substring(0, end).toIntOrNull() ?: return 0
        return if (parsed in 1..MAX_REASONABLE_TRACK_OR_DISC) parsed else 0
    }

    /**
     * Parses an optional leading track (or disc-track) number from an audio filename
     * (e.g., `"04 - Acid Rain.flac"` -> track `4`; `"1-03 Song.flac"` -> disc `1`, track `3`).
     * Rejects 4-digit years (`"2024 - Live.flac"`) and ordinals (`"21st Century Breakdown.flac"`).
     */
    fun parseFilenamePosition(fileName: String?): ParsedTrackPosition {
        if (fileName.isNullOrBlank()) return ParsedTrackPosition()
        val base = AudioFormatClassifier.baseNameWithoutExtension(fileName).trim()
        if (base.isEmpty()) return ParsedTrackPosition()

        val compactDiscTrack = FILENAME_DISC_TRACK_REGEX.find(base)
        if (compactDiscTrack != null) {
            val d = compactDiscTrack.groupValues[1].toIntOrNull() ?: 0
            val t = compactDiscTrack.groupValues[2].toIntOrNull() ?: 0
            if (d in 1..99 && t in 1..MAX_REASONABLE_TRACK_OR_DISC) {
                return ParsedTrackPosition(discNumber = d, trackNumber = t)
            }
        }

        val match = FILENAME_TRACK_REGEX.find(base) ?: return ParsedTrackPosition()
        val digits = match.groupValues[1]
        // Ensure we didn't split a 4+ digit prefix (already prevented by separator requirement,
        // double-checked here for safety).
        val track = digits.toIntOrNull() ?: return ParsedTrackPosition()
        return if (track in 1..MAX_REASONABLE_TRACK_OR_DISC) {
            ParsedTrackPosition(discNumber = 0, trackNumber = track)
        } else {
            ParsedTrackPosition()
        }
    }

    fun parseFilenameTrackNumber(fileName: String?): Int = parseFilenamePosition(fileName).trackNumber

    /**
     * Resolves canonical `(discNumber, trackNumber)` with strict precedence:
     * 1. Explicit text tags (`trackNumberTag`, `discNumberTag`, e.g., `CD_TRACK_NUMBER`/`DISC_NUMBER` or Vorbis Comments)
     * 2. Android MediaStore packed integer (`TRACK`: `disc * 1000 + track`)
     * 3. Optional pre-parsed header tags (`headerDiscNumber`, `headerTrackNumber`, e.g., FLAC Vorbis Comments)
     * 4. Leading filename number fallback ONLY when both MediaStore and tag track numbers are missing (`0`).
     */
    fun resolve(
        mediaStorePackedTrack: Int = 0,
        trackNumberTag: String? = null,
        discNumberTag: String? = null,
        headerDiscNumber: Int = 0,
        headerTrackNumber: Int = 0,
        fileName: String? = null,
    ): ParsedTrackPosition {
        val packed = parseMediaStorePackedTrack(mediaStorePackedTrack)
        val parsedTagTrack = parseTagNumber(trackNumberTag)
        val parsedTagDisc = parseTagNumber(discNumberTag)
        val cleanHeaderDisc = if (headerDiscNumber in 1..MAX_REASONABLE_TRACK_OR_DISC) headerDiscNumber else 0
        val cleanHeaderTrack = if (headerTrackNumber in 1..MAX_REASONABLE_TRACK_OR_DISC) headerTrackNumber else 0

        var disc = when {
            parsedTagDisc > 0 -> parsedTagDisc
            packed.discNumber > 0 -> packed.discNumber
            cleanHeaderDisc > 0 -> cleanHeaderDisc
            else -> 0
        }
        var track = when {
            parsedTagTrack > 0 -> parsedTagTrack
            packed.trackNumber > 0 -> packed.trackNumber
            cleanHeaderTrack > 0 -> cleanHeaderTrack
            else -> 0
        }

        // Only fall back to filename when BOTH MediaStore and metadata tags have no track number.
        if (track == 0 && !fileName.isNullOrBlank()) {
            val fromFile = parseFilenamePosition(fileName)
            if (fromFile.trackNumber > 0) {
                track = fromFile.trackNumber
                if (disc == 0 && fromFile.discNumber > 0) {
                    disc = fromFile.discNumber
                }
            }
        }

        return ParsedTrackPosition(discNumber = disc, trackNumber = track)
    }
}
