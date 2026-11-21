package com.cangkir.bitperfectplayer.library

import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import java.net.URLDecoder
import java.util.Locale

data class M3u8ParsedEntry(
    val location: String,
    val displayTitle: String? = null,
    val durationSeconds: Int? = null,
    val hintUri: String? = null,
    val hintFingerprint: String? = null,
)

data class M3u8ParsedPlaylist(val titleHint: String?, val entries: List<M3u8ParsedEntry>)

data class M3u8ImportMatchResult(val matchedTracks: List<PlaybackTrack>, val unmatchedLocations: List<String>)

/**
 * Pure-Kotlin `.m3u8` playlist exporter, fault-tolerant parser, local library matcher, and
 * deterministic playlist track reorder helper (zero `android.*` imports).
 */
object M3u8PlaylistCodec {

    private const val HEADER_EXTM3U = "#EXTM3U"
    private const val PREFIX_PLAYLIST = "#PLAYLIST:"
    private const val PREFIX_EXTINF = "#EXTINF:"
    private const val PREFIX_HINT_URI = "#EXT-X-BITPERFECT-URI:"
    private const val PREFIX_HINT_SHA256 = "#EXT-X-BITPERFECT-SHA256:"

    /**
     * Exports [tracks] to UTF-8 `.m3u8` format with standard `#EXTM3U`, `#PLAYLIST:`, and
     * `#EXTINF:` directives plus portable relative-path/filename locations and optional
     * BitPerfectPlayer URI/SHA-256 comment hints for lossless re-import.
     */
    fun exportToM3u8(playlistName: String, tracks: List<PlaybackTrack>): String = buildString {
        appendLine(HEADER_EXTM3U)
        val cleanName = playlistName.replace("\r", " ").replace("\n", " ").trim()
        if (cleanName.isNotEmpty()) {
            append(PREFIX_PLAYLIST).appendLine(cleanName)
        }
        for (track in tracks) {
            val durSec = if (track.durationMs > 0L) (track.durationMs / 1000L).toInt() else -1
            val cleanArtist = track.artist.replace("\r", " ").replace("\n", " ").trim()
            val cleanTitle = track.title.replace("\r", " ").replace("\n", " ").trim()
            val display = if (cleanArtist.isNotEmpty() && !cleanArtist.equals("Unknown Artist", ignoreCase = true)) {
                "$cleanArtist - $cleanTitle"
            } else {
                cleanTitle
            }
            append(PREFIX_EXTINF).append(durSec).append(',').appendLine(display)
            append(PREFIX_HINT_URI).appendLine(track.uriString.trim())
            track.contentFingerprint?.trim()?.takeIf { it.isNotEmpty() }?.let { fp ->
                append(PREFIX_HINT_SHA256).appendLine(fp.lowercase(Locale.ROOT))
            }
            appendLine(formatExportLocation(track))
        }
    }

    /**
     * Fault-tolerant `.m3u8` / `.m3u` parser supporting UTF-8 BOM, CRLF/LF/CR line endings,
     * `#PLAYLIST:`, `#EXTINF:`, optional BitPerfectPlayer hint tags, and plain line-delimited paths.
     */
    fun parseM3u8(rawText: String): M3u8ParsedPlaylist {
        val cleaned = rawText.removePrefix("\uFEFF")
        val lines = cleaned.replace("\r\n", "\n").replace('\r', '\n').split('\n')

        var titleHint: String? = null
        var pendingTitle: String? = null
        var pendingDurationSec: Int? = null
        var pendingHintUri: String? = null
        var pendingHintFp: String? = null
        val entries = mutableListOf<M3u8ParsedEntry>()

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                when {
                    line.startsWith(PREFIX_PLAYLIST, ignoreCase = true) -> {
                        val candidate = line.substring(PREFIX_PLAYLIST.length).trim()
                        if (candidate.isNotEmpty() && titleHint == null) {
                            titleHint = candidate
                        }
                    }
                    line.startsWith(PREFIX_EXTINF, ignoreCase = true) -> {
                        val body = line.substring(PREFIX_EXTINF.length)
                        val commaIdx = body.indexOf(',')
                        if (commaIdx >= 0) {
                            pendingDurationSec = body.substring(0, commaIdx).trim().toIntOrNull()
                            pendingTitle = body.substring(commaIdx + 1).trim().takeIf { it.isNotEmpty() }
                        } else {
                            pendingDurationSec = body.trim().toIntOrNull()
                        }
                    }
                    line.startsWith(PREFIX_HINT_URI, ignoreCase = true) -> {
                        pendingHintUri = line.substring(PREFIX_HINT_URI.length).trim().takeIf { it.isNotEmpty() }
                    }
                    line.startsWith(PREFIX_HINT_SHA256, ignoreCase = true) -> {
                        pendingHintFp = line.substring(PREFIX_HINT_SHA256.length).trim().lowercase(Locale.ROOT)
                            .takeIf { it.isNotEmpty() }
                    }
                }
                continue
            }

            entries.add(
                M3u8ParsedEntry(
                    location = line,
                    displayTitle = pendingTitle,
                    durationSeconds = pendingDurationSec,
                    hintUri = pendingHintUri,
                    hintFingerprint = pendingHintFp,
                ),
            )
            pendingTitle = null
            pendingDurationSec = null
            pendingHintUri = null
            pendingHintFp = null
        }

        return M3u8ParsedPlaylist(titleHint = titleHint, entries = entries)
    }

    /**
     * Resolves parsed `.m3u8` [entries] against local [libraryTracks] using deterministic priority:
     * 1. Exact SHA-256 content fingerprint hint (survives file rename/move)
     * 2. Exact track URI (`location` or `hintUri`)
     * 3. Normalized relative path match (handles `/` & `\`, `%20` decoding, case-insensitive)
     * 4. Case-insensitive exact filename match (`fileName`)
     */
    fun matchEntriesToLibrary(entries: List<M3u8ParsedEntry>, libraryTracks: List<PlaybackTrack>): M3u8ImportMatchResult {
        if (entries.isEmpty()) return M3u8ImportMatchResult(emptyList(), emptyList())

        val byFingerprint = HashMap<String, PlaybackTrack>(libraryTracks.size)
        val byUri = HashMap<String, PlaybackTrack>(libraryTracks.size)
        val byRelativePath = HashMap<String, PlaybackTrack>(libraryTracks.size)
        val byFileName = HashMap<String, PlaybackTrack>(libraryTracks.size)

        for (track in libraryTracks) {
            track.contentFingerprint?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }?.let { fp ->
                byFingerprint.putIfAbsent(fp, track)
            }
            byUri.putIfAbsent(track.uriString.trim(), track)
            val normRel = normalizePath(formatExportLocation(track))
            if (normRel.isNotEmpty()) {
                byRelativePath.putIfAbsent(normRel, track)
            }
            val normFile = normalizeFileName(track.fileName)
            if (normFile.isNotEmpty()) {
                byFileName.putIfAbsent(normFile, track)
            }
        }

        val matched = mutableListOf<PlaybackTrack>()
        val unmatched = mutableListOf<String>()

        for (entry in entries) {
            val resolved = resolveSingleEntry(
                entry = entry,
                byFingerprint = byFingerprint,
                byUri = byUri,
                byRelativePath = byRelativePath,
                byFileName = byFileName,
                libraryTracks = libraryTracks,
            )
            if (resolved != null) {
                matched.add(resolved)
            } else {
                unmatched.add(entry.location)
            }
        }

        return M3u8ImportMatchResult(
            matchedTracks = matched,
            unmatchedLocations = unmatched,
        )
    }

    /**
     * Deterministically moves an entry ID from [fromIndex] to [toIndex] inside an ordered playlist.
     */
    fun <T> reorderList(items: List<T>, fromIndex: Int, toIndex: Int): List<T> {
        if (items.size <= 1) return items
        if (fromIndex !in items.indices || toIndex !in items.indices || fromIndex == toIndex) {
            return items
        }
        val mutable = items.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)
        return mutable
    }

    /**
     * Builds a safe default `.m3u8` export filename for a playlist name.
     */
    fun suggestExportFileName(playlistName: String): String {
        val safe = playlistName
            .trim()
            .replace(Regex("[^A-Za-z0-9._\\- ]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { "playlist" }
        return if (safe.endsWith(".m3u8", ignoreCase = true)) safe else "$safe.m3u8"
    }

    private fun resolveSingleEntry(
        entry: M3u8ParsedEntry,
        byFingerprint: Map<String, PlaybackTrack>,
        byUri: Map<String, PlaybackTrack>,
        byRelativePath: Map<String, PlaybackTrack>,
        byFileName: Map<String, PlaybackTrack>,
        libraryTracks: List<PlaybackTrack>,
    ): PlaybackTrack? {
        val directMatch = entry.hintFingerprint?.let { byFingerprint[it.lowercase(Locale.ROOT)] }
            ?: byUri[entry.location.trim()]
            ?: entry.hintUri?.let { byUri[it.trim()] }
        if (directMatch != null) return directMatch

        val normalizedLoc = normalizePath(entry.location)
        val pathMatch = if (normalizedLoc.isNotEmpty()) {
            byRelativePath[normalizedLoc] ?: findByRelativePathSuffix(normalizedLoc, libraryTracks)
        } else {
            null
        }
        if (pathMatch != null) return pathMatch

        val extractedName = normalizeFileName(normalizedLoc.substringAfterLast('/'))
        return extractedName.takeIf { it.isNotEmpty() }?.let { byFileName[it] }
    }

    private fun findByRelativePathSuffix(normalizedLoc: String, libraryTracks: List<PlaybackTrack>): PlaybackTrack? =
        // Suffix path match (e.g., `/storage/emulated/0/Music/Jazz/01.flac` matching `music/jazz/01.flac`)
        libraryTracks.firstOrNull { track ->
            val trackRel = normalizePath(formatExportLocation(track))
            trackRel.contains('/') &&
                (normalizedLoc.endsWith("/$trackRel") || trackRel.endsWith("/$normalizedLoc"))
        }

    private fun formatExportLocation(track: PlaybackTrack): String {
        val rel = track.relativePath?.trim()?.replace('\\', '/')?.trim('/')
        return if (!rel.isNullOrEmpty()) {
            "$rel/${track.fileName.trim()}"
        } else {
            track.fileName.trim().ifEmpty { track.uriString.trim() }
        }
    }

    private fun normalizePath(raw: String): String {
        val stripped = raw.trim()
            .removePrefix("file://")
            .replace('\\', '/')
        val decoded = runCatching { URLDecoder.decode(stripped, "UTF-8") }.getOrDefault(stripped)
        return decoded.trim('/').lowercase(Locale.ROOT)
    }

    private fun normalizeFileName(raw: String): String = raw.trim().substringAfterLast('/').substringAfterLast('\\').lowercase(Locale.ROOT)
}
