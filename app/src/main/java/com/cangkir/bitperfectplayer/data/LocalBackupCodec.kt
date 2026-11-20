package com.cangkir.bitperfectplayer.data

/**
 * Portable, schema-versioned local backup payload covering all user-created and local-only
 * application data: Playlists, Saved Lyrics (keyed by SHA-256), Favorites, and Track Listening Stats.
 */
data class BackupPlaylistEntry(
    val contentFingerprint: String?,
    val trackUri: String,
    val fileName: String,
    val positionIndex: Int,
    val addedAtEpochMs: Long,
)

data class BackupPlaylist(
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val tracks: List<BackupPlaylistEntry>,
)

data class LocalBackupPayload(
    val schemaVersion: Int = LocalBackupCodec.CURRENT_SCHEMA_VERSION,
    val appId: String = LocalBackupCodec.APP_ID,
    val exportedAtEpochMs: Long = 0L,
    val playlists: List<BackupPlaylist> = emptyList(),
    val lyrics: List<LyricsRecord> = emptyList(),
    val favorites: List<FavoriteTrackRecord> = emptyList(),
    val trackStats: List<TrackStatsRecord> = emptyList(),
)

data class LocalBackupRestoreSummary(
    val playlistsMerged: Int,
    val playlistTracksAdded: Int,
    val lyricsUpserted: Int,
    val favoritesMerged: Int,
    val statsMerged: Int,
)

/**
 * Pure-Kotlin, zero-dependency RFC 8259 JSON encoder and strict validating decoder for offline
 * local backups (zero `android.*` or third-party imports).
 */
object LocalBackupCodec {
    const val CURRENT_SCHEMA_VERSION: Int = 1
    const val APP_ID: String = "com.cangkir.bitperfectplayer"

    fun encode(payload: LocalBackupPayload): String {
        val sb = StringBuilder(4096)
        sb.append("{\n")
        sb.append("  \"schemaVersion\": ").append(payload.schemaVersion).append(",\n")
        sb.append("  \"appId\": "); appendJsonString(sb, payload.appId); sb.append(",\n")
        sb.append("  \"exportedAtEpochMs\": ").append(payload.exportedAtEpochMs.coerceAtLeast(0L)).append(",\n")

        // Playlists
        sb.append("  \"playlists\": [")
        payload.playlists.forEachIndexed { idx, pl ->
            if (idx > 0) sb.append(',')
            sb.append("\n    {\n")
            sb.append("      \"name\": "); appendJsonString(sb, pl.name); sb.append(",\n")
            sb.append("      \"createdAtEpochMs\": ").append(pl.createdAtEpochMs.coerceAtLeast(0L)).append(",\n")
            sb.append("      \"updatedAtEpochMs\": ").append(pl.updatedAtEpochMs.coerceAtLeast(0L)).append(",\n")
            sb.append("      \"tracks\": [")
            pl.tracks.forEachIndexed { tIdx, tr ->
                if (tIdx > 0) sb.append(',')
                sb.append("\n        {")
                sb.append("\"contentFingerprint\": "); appendNullableJsonString(sb, tr.contentFingerprint); sb.append(", ")
                sb.append("\"trackUri\": "); appendJsonString(sb, tr.trackUri); sb.append(", ")
                sb.append("\"fileName\": "); appendJsonString(sb, tr.fileName); sb.append(", ")
                sb.append("\"positionIndex\": ").append(tr.positionIndex.coerceAtLeast(0)).append(", ")
                sb.append("\"addedAtEpochMs\": ").append(tr.addedAtEpochMs.coerceAtLeast(0L))
                sb.append('}')
            }
            if (pl.tracks.isNotEmpty()) sb.append("\n      ")
            sb.append("]\n    }")
        }
        if (payload.playlists.isNotEmpty()) sb.append("\n  ")
        sb.append("],\n")

        // Lyrics
        sb.append("  \"lyrics\": [")
        payload.lyrics.forEachIndexed { idx, ly ->
            if (idx > 0) sb.append(',')
            sb.append("\n    {")
            sb.append("\"contentFingerprint\": "); appendJsonString(sb, ly.contentFingerprint); sb.append(", ")
            sb.append("\"rawLyricsText\": "); appendJsonString(sb, ly.rawLyricsText); sb.append(", ")
            sb.append("\"isSyncedLrc\": ").append(ly.isSyncedLrc).append(", ")
            sb.append("\"sourceType\": "); appendJsonString(sb, ly.sourceType.name); sb.append(", ")
            sb.append("\"fileName\": "); appendJsonString(sb, ly.fileName); sb.append(", ")
            sb.append("\"fileSizeBytes\": ").append(ly.fileSizeBytes.coerceAtLeast(0L)).append(", ")
            sb.append("\"durationMs\": ").append(ly.durationMs.coerceAtLeast(0L)).append(", ")
            sb.append("\"updatedAtEpochMs\": ").append(ly.updatedAtEpochMs.coerceAtLeast(0L))
            sb.append('}')
        }
        if (payload.lyrics.isNotEmpty()) sb.append("\n  ")
        sb.append("],\n")

        // Favorites
        sb.append("  \"favorites\": [")
        payload.favorites.forEachIndexed { idx, fav ->
            if (idx > 0) sb.append(',')
            sb.append("\n    {")
            sb.append("\"contentFingerprint\": "); appendJsonString(sb, fav.contentFingerprint); sb.append(", ")
            sb.append("\"trackUri\": "); appendJsonString(sb, fav.trackUri); sb.append(", ")
            sb.append("\"title\": "); appendJsonString(sb, fav.title); sb.append(", ")
            sb.append("\"artist\": "); appendJsonString(sb, fav.artist); sb.append(", ")
            sb.append("\"album\": "); appendJsonString(sb, fav.album); sb.append(", ")
            sb.append("\"fileName\": "); appendJsonString(sb, fav.fileName); sb.append(", ")
            sb.append("\"durationMs\": ").append(fav.durationMs.coerceAtLeast(0L)).append(", ")
            sb.append("\"addedAtEpochMs\": ").append(fav.addedAtEpochMs.coerceAtLeast(0L))
            sb.append('}')
        }
        if (payload.favorites.isNotEmpty()) sb.append("\n  ")
        sb.append("],\n")

        // Track Stats
        sb.append("  \"trackStats\": [")
        payload.trackStats.forEachIndexed { idx, st ->
            if (idx > 0) sb.append(',')
            sb.append("\n    {")
            sb.append("\"contentFingerprint\": "); appendJsonString(sb, st.contentFingerprint); sb.append(", ")
            sb.append("\"trackUri\": "); appendJsonString(sb, st.trackUri); sb.append(", ")
            sb.append("\"title\": "); appendJsonString(sb, st.title); sb.append(", ")
            sb.append("\"artist\": "); appendJsonString(sb, st.artist); sb.append(", ")
            sb.append("\"album\": "); appendJsonString(sb, st.album); sb.append(", ")
            sb.append("\"fileName\": "); appendJsonString(sb, st.fileName); sb.append(", ")
            sb.append("\"playCount\": ").append(st.playCount.coerceAtLeast(0)).append(", ")
            sb.append("\"totalPlayTimeMs\": ").append(st.totalPlayTimeMs.coerceAtLeast(0L)).append(", ")
            sb.append("\"lastPlayedAtEpochMs\": ").append(st.lastPlayedAtEpochMs.coerceAtLeast(0L))
            sb.append('}')
        }
        if (payload.trackStats.isNotEmpty()) sb.append("\n  ")
        sb.append("]\n}\n")

        return sb.toString()
    }

    fun decode(rawJson: String): Result<LocalBackupPayload> = runCatching {
        val cleaned = rawJson.removePrefix("\uFEFF").trim()
        if (cleaned.isEmpty()) {
            throw IllegalArgumentException("Backup file is empty")
        }
        val root = JsonParser(cleaned).parseRootObject()

        val schemaVersion = root.requireInt("schemaVersion")
        if (schemaVersion < 1 || schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw IllegalArgumentException("Unsupported backup schemaVersion: $schemaVersion")
        }

        val appId = root.optionalString("appId") ?: APP_ID
        val exportedAt = root.optionalLong("exportedAtEpochMs")?.coerceAtLeast(0L) ?: 0L

        val playlists = root.requireArray("playlists").mapNotNull { elem ->
            val obj = elem as? Map<*, *> ?: throw IllegalArgumentException("Invalid playlist entry")
            val name = obj.optionalString("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val createdAt = obj.optionalLong("createdAtEpochMs")?.coerceAtLeast(0L) ?: 0L
            val updatedAt = obj.optionalLong("updatedAtEpochMs")?.coerceAtLeast(0L) ?: createdAt
            val tracks = obj.optionalArray("tracks").orEmpty().mapIndexedNotNull { idx, tElem ->
                val tObj = tElem as? Map<*, *> ?: throw IllegalArgumentException("Invalid playlist track entry")
                val uri = tObj.optionalString("trackUri")?.trim().orEmpty()
                val fileName = PersistenceSanitizer.sanitizeDisplayText(tObj.optionalString("fileName"), "Unknown")
                val fp = tObj.optionalString("contentFingerprint")
                    ?.let { PersistenceSanitizer.normalizeFingerprint(it) }
                    ?.takeIf { PersistenceSanitizer.isValidSha256Fingerprint(it) }
                if (uri.isEmpty() && fp == null) return@mapIndexedNotNull null
                BackupPlaylistEntry(
                    contentFingerprint = fp,
                    trackUri = uri,
                    fileName = fileName,
                    positionIndex = tObj.optionalInt("positionIndex")?.coerceAtLeast(0) ?: idx,
                    addedAtEpochMs = tObj.optionalLong("addedAtEpochMs")?.coerceAtLeast(0L) ?: updatedAt,
                )
            }.sortedBy { it.positionIndex }
            BackupPlaylist(
                name = name,
                createdAtEpochMs = createdAt,
                updatedAtEpochMs = updatedAt,
                tracks = tracks,
            )
        }

        val lyrics = root.requireArray("lyrics").mapNotNull { elem ->
            val obj = elem as? Map<*, *> ?: throw IllegalArgumentException("Invalid lyrics entry")
            val fp = obj.optionalString("contentFingerprint")
                ?.let { PersistenceSanitizer.normalizeFingerprint(it) }
                ?.takeIf { PersistenceSanitizer.isValidSha256Fingerprint(it) }
                ?: return@mapNotNull null
            val rawText = obj.optionalString("rawLyricsText") ?: return@mapNotNull null
            LyricsRecord(
                contentFingerprint = fp,
                rawLyricsText = PersistenceSanitizer.sanitizeLyricsText(rawText),
                isSyncedLrc = obj.optionalBoolean("isSyncedLrc") ?: false,
                sourceType = LyricsSourceType.fromStoredName(obj.optionalString("sourceType")),
                fileName = PersistenceSanitizer.sanitizeDisplayText(obj.optionalString("fileName"), "Unknown"),
                fileSizeBytes = PersistenceSanitizer.sanitizeNonNegative(obj.optionalLong("fileSizeBytes") ?: 0L),
                durationMs = PersistenceSanitizer.sanitizeNonNegative(obj.optionalLong("durationMs") ?: 0L),
                updatedAtEpochMs = PersistenceSanitizer.sanitizeNonNegative(obj.optionalLong("updatedAtEpochMs") ?: 0L),
            )
        }

        val favorites = root.requireArray("favorites").mapNotNull { elem ->
            val obj = elem as? Map<*, *> ?: throw IllegalArgumentException("Invalid favorite entry")
            val fp = obj.optionalString("contentFingerprint")
                ?.let { PersistenceSanitizer.normalizeFingerprint(it) }
                ?.takeIf { PersistenceSanitizer.isValidSha256Fingerprint(it) }
                ?: return@mapNotNull null
            val fileName = PersistenceSanitizer.sanitizeDisplayText(obj.optionalString("fileName"), "Unknown")
            FavoriteTrackRecord(
                contentFingerprint = fp,
                trackUri = obj.optionalString("trackUri")?.trim().orEmpty(),
                title = PersistenceSanitizer.sanitizeDisplayText(obj.optionalString("title"), fileName),
                artist = PersistenceSanitizer.sanitizeDisplayText(obj.optionalString("artist"), "Unknown Artist"),
                album = PersistenceSanitizer.sanitizeDisplayText(obj.optionalString("album"), "Unknown Album"),
                fileName = fileName,
                durationMs = PersistenceSanitizer.sanitizeNonNegative(obj.optionalLong("durationMs") ?: 0L),
                addedAtEpochMs = PersistenceSanitizer.sanitizeNonNegative(obj.optionalLong("addedAtEpochMs") ?: 0L),
            )
        }

        val trackStats = root.requireArray("trackStats").mapNotNull { elem ->
            val obj = elem as? Map<*, *> ?: throw IllegalArgumentException("Invalid trackStats entry")
            val fp = obj.optionalString("contentFingerprint")
                ?.let { PersistenceSanitizer.normalizeFingerprint(it) }
                ?.takeIf { PersistenceSanitizer.isValidSha256Fingerprint(it) }
                ?: return@mapNotNull null
            val fileName = PersistenceSanitizer.sanitizeDisplayText(obj.optionalString("fileName"), "Unknown")
            TrackStatsRecord(
                contentFingerprint = fp,
                trackUri = obj.optionalString("trackUri")?.trim().orEmpty(),
                title = PersistenceSanitizer.sanitizeDisplayText(obj.optionalString("title"), fileName),
                artist = PersistenceSanitizer.sanitizeDisplayText(obj.optionalString("artist"), "Unknown Artist"),
                album = PersistenceSanitizer.sanitizeDisplayText(obj.optionalString("album"), "Unknown Album"),
                fileName = fileName,
                playCount = (obj.optionalInt("playCount") ?: 0).coerceAtLeast(0),
                totalPlayTimeMs = PersistenceSanitizer.sanitizeNonNegative(obj.optionalLong("totalPlayTimeMs") ?: 0L),
                lastPlayedAtEpochMs = PersistenceSanitizer.sanitizeNonNegative(obj.optionalLong("lastPlayedAtEpochMs") ?: 0L),
            )
        }

        LocalBackupPayload(
            schemaVersion = schemaVersion,
            appId = appId,
            exportedAtEpochMs = exportedAt,
            playlists = playlists,
            lyrics = lyrics,
            favorites = favorites,
            trackStats = trackStats,
        )
    }

    // -------------------------------------------------------------------------
    // Minimal Pure-Kotlin JSON Writer & Strict Parser (RFC 8259)
    // -------------------------------------------------------------------------

    private fun appendNullableJsonString(sb: StringBuilder, value: String?) {
        if (value == null) sb.append("null") else appendJsonString(sb, value)
    }

    private fun appendJsonString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u")
                        val hex = ch.code.toString(16).padStart(4, '0')
                        sb.append(hex)
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
    }

    private fun Map<*, *>.requireInt(key: String): Int {
        val raw = this[key] ?: throw IllegalArgumentException("Missing required integer field '$key'")
        return (raw as? Number)?.toInt()
            ?: throw IllegalArgumentException("Expected integer for '$key'")
    }

    private fun Map<*, *>.optionalInt(key: String): Int? = (this[key] as? Number)?.toInt()

    private fun Map<*, *>.optionalLong(key: String): Long? = (this[key] as? Number)?.toLong()

    private fun Map<*, *>.optionalBoolean(key: String): Boolean? = this[key] as? Boolean

    private fun Map<*, *>.optionalString(key: String): String? = this[key] as? String

    private fun Map<*, *>.requireArray(key: String): List<*> {
        val raw = this[key] ?: throw IllegalArgumentException("Missing required array field '$key'")
        return raw as? List<*> ?: throw IllegalArgumentException("Expected array for '$key'")
    }

    private fun Map<*, *>.optionalArray(key: String): List<*>? = this[key] as? List<*>

    private class JsonParser(private val src: String) {
        private var pos = 0

        fun parseRootObject(): Map<String, Any?> {
            skipWhitespace()
            val value = parseObject()
            skipWhitespace()
            if (pos != src.length) {
                throw IllegalArgumentException("Unexpected trailing characters at offset $pos")
            }
            return value
        }

        private fun parseValue(depth: Int): Any? {
            if (depth > 32) throw IllegalArgumentException("JSON nesting too deep")
            skipWhitespace()
            if (pos >= src.length) throw IllegalArgumentException("Unexpected end of JSON")
            return when (src[pos]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("Unexpected character '${src[pos]}' at offset $pos")
            }
        }

        private fun parseObject(depth: Int = 0): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            val map = LinkedHashMap<String, Any?>()
            if (peek() == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                val value = parseValue(depth)
                map[key] = value
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at offset $pos")
                }
            }
        }

        private fun parseArray(depth: Int): List<Any?> {
            expect('[')
            skipWhitespace()
            val list = ArrayList<Any?>()
            if (peek() == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue(depth))
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at offset $pos")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < src.length) {
                val ch = src[pos++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) throw IllegalArgumentException("Unterminated string escape")
                        when (val esc = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) throw IllegalArgumentException("Invalid \\u escape")
                                val hex = src.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw IllegalArgumentException("Invalid \\u escape: $hex")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("Invalid escape character '\\$esc'")
                        }
                    }
                    else -> {
                        if (ch.code < 0x20) {
                            throw IllegalArgumentException("Unescaped control character in JSON string")
                        }
                        sb.append(ch)
                    }
                }
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }

        private fun parseNumber(): Number {
            val start = pos
            if (peek() == '-') pos++
            var hasDigits = false
            while (pos < src.length && src[pos] in '0'..'9') {
                hasDigits = true
                pos++
            }
            if (!hasDigits) throw IllegalArgumentException("Invalid number at offset $start")
            var isFloat = false
            if (peek() == '.') {
                isFloat = true
                pos++
                var fracDigits = false
                while (pos < src.length && src[pos] in '0'..'9') {
                    fracDigits = true
                    pos++
                }
                if (!fracDigits) throw IllegalArgumentException("Invalid fractional number at offset $start")
            }
            if (peek() == 'e' || peek() == 'E') {
                isFloat = true
                pos++
                if (peek() == '+' || peek() == '-') pos++
                var expDigits = false
                while (pos < src.length && src[pos] in '0'..'9') {
                    expDigits = true
                    pos++
                }
                if (!expDigits) throw IllegalArgumentException("Invalid exponent at offset $start")
            }
            val token = src.substring(start, pos)
            return if (isFloat) {
                token.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid floating number: $token")
            } else {
                token.toLongOrNull() ?: token.toDoubleOrNull()
                    ?: throw IllegalArgumentException("Invalid integer number: $token")
            }
        }

        private fun <T> parseLiteral(expected: String, result: T): T {
            if (!src.startsWith(expected, pos)) {
                throw IllegalArgumentException("Expected '$expected' at offset $pos")
            }
            pos += expected.length
            return result
        }

        private fun expect(ch: Char) {
            if (pos >= src.length || src[pos] != ch) {
                throw IllegalArgumentException("Expected '$ch' at offset $pos")
            }
            pos++
        }

        private fun peek(): Char? = if (pos < src.length) src[pos] else null

        private fun skipWhitespace() {
            while (pos < src.length && (src[pos] == ' ' || src[pos] == '\n' || src[pos] == '\r' || src[pos] == '\t')) {
                pos++
            }
        }
    }
}
