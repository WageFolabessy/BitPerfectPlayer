package com.cangkir.bitperfectplayer.data

/**
 * Origin of a locally persisted lyrics entry. Strictly offline (manual editor or local `.lrc` file).
 */
enum class LyricsSourceType {
    MANUAL,
    LRC_FILE,
    ;

    companion object {
        fun fromStoredName(raw: String?): LyricsSourceType =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: MANUAL
    }
}

/**
 * Pure-Kotlin domain record representing lyrics persistently bound to an audio track via its
 * deterministic SHA-256 content fingerprint.
 */
data class LyricsRecord(
    val contentFingerprint: String,
    val rawLyricsText: String,
    val isSyncedLrc: Boolean,
    val sourceType: LyricsSourceType,
    val fileName: String,
    val fileSizeBytes: Long,
    val durationMs: Long,
    val updatedAtEpochMs: Long,
)

/**
 * Pure-Kotlin domain record representing a recently played track entry.
 */
data class TrackHistoryRecord(
    val contentFingerprint: String,
    val trackUri: String,
    val title: String,
    val artist: String,
    val album: String,
    val fileName: String,
    val durationMs: Long,
    val playedAtEpochMs: Long,
)

/**
 * Pure-Kotlin domain record representing a user-favorited track keyed by content fingerprint.
 */
data class FavoriteTrackRecord(
    val contentFingerprint: String,
    val trackUri: String,
    val title: String,
    val artist: String,
    val album: String,
    val fileName: String,
    val durationMs: Long,
    val addedAtEpochMs: Long,
)

/**
 * Pure-Kotlin domain record representing a local user-created playlist.
 */
data class PlaylistRecord(
    val id: Long = 0L,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

/**
 * Pure-Kotlin domain record representing an ordered track entry inside a user playlist.
 */
data class PlaylistTrackRecord(
    val id: Long = 0L,
    val playlistId: Long,
    val contentFingerprint: String?,
    val trackUri: String,
    val fileName: String,
    val positionIndex: Int,
    val addedAtEpochMs: Long,
)

/**
 * Pure-Kotlin domain record representing persistent anti-skip listening statistics bound to a
 * track's deterministic SHA-256 content fingerprint.
 */
data class TrackStatsRecord(
    val contentFingerprint: String,
    val trackUri: String,
    val title: String,
    val artist: String,
    val album: String,
    val fileName: String,
    val playCount: Int,
    val totalPlayTimeMs: Long,
    val lastPlayedAtEpochMs: Long,
)

/**
 * Persisted audio safety mode preference.
 * Hearing safety invariant: defaults to [SAFE_VOLUME_MODE] on fresh install.
 */
enum class AudioSafetyModePreference {
    SAFE_VOLUME_MODE,
    BIT_PERFECT_OPT_IN,
    ;

    companion object {
        fun fromStoredName(raw: String?): AudioSafetyModePreference =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
                ?: SAFE_VOLUME_MODE
    }
}

/**
 * Persisted repeat mode preference aligned with playback queue repeat states.
 */
enum class RepeatModePreference {
    OFF,
    ALL,
    ONE,
    ;

    companion object {
        fun fromStoredName(raw: String?): RepeatModePreference =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: OFF
    }
}

/**
 * Persisted ReplayGain mode preference (defaults to [OFF]; strictly bypassed in Bit-Perfect Mode).
 */
enum class ReplayGainModePreference {
    OFF,
    TRACK,
    ALBUM,
    ;

    companion object {
        fun fromStoredName(raw: String?): ReplayGainModePreference =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: OFF
    }
}

/**
 * Snapshot of the last active track and playback position for restoration on app relaunch.
 */
data class LastPlaybackState(
    val trackUri: String? = null,
    val contentFingerprint: String? = null,
    val positionMs: Long = 0L,
)

/**
 * Complete local user preferences snapshot.
 */
data class UserPreferences(
    val audioSafetyMode: AudioSafetyModePreference = AudioSafetyModePreference.SAFE_VOLUME_MODE,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatModePreference = RepeatModePreference.OFF,
    val gaplessEnabled: Boolean = true,
    val replayGainMode: ReplayGainModePreference = ReplayGainModePreference.OFF,
    val replayGainPreampDb: Float = 0.0f,
    val replayGainPreventClipping: Boolean = true,
    val persistedSafTreeUris: Set<String> = emptySet(),
    val persistedSafFileUris: Set<String> = emptySet(),
    val lastPlaybackState: LastPlaybackState = LastPlaybackState(),
)

/**
 * Pure-Kotlin validation and sanitization helpers for persistence inputs.
 */
object PersistenceSanitizer {
    private val HEX_64_REGEX = Regex("^[0-9a-f]{64}$")

    fun normalizeFingerprint(raw: String): String = raw.trim().lowercase()

    fun isValidSha256Fingerprint(raw: String): Boolean =
        HEX_64_REGEX.matches(normalizeFingerprint(raw))

    fun sanitizeNonNegative(value: Long): Long = value.coerceAtLeast(0L)

    fun sanitizeReplayGainPreampDb(value: Float): Float =
        if (value.isFinite()) value.coerceIn(-6.0f, 6.0f) else 0.0f

    fun sanitizeDisplayText(raw: String?, fallback: String): String =
        raw?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

    fun sanitizeUriSet(uris: Set<String>): Set<String> =
        uris.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun sanitizeLyricsText(raw: String): String =
        raw.replace("\r\n", "\n").replace('\r', '\n')
}
