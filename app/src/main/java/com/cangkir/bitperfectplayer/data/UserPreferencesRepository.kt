package com.cangkir.bitperfectplayer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cangkir.bitperfectplayer.audio.QueueSourceType
import com.cangkir.bitperfectplayer.library.GroupSortScope
import com.cangkir.bitperfectplayer.library.GroupSortSpec
import com.cangkir.bitperfectplayer.library.LibrarySortPreferences
import com.cangkir.bitperfectplayer.library.TrackSortScope
import com.cangkir.bitperfectplayer.library.TrackSortSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.bitPerfectUserPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "bitperfect_user_preferences",
)

/**
 * Local-first Jetpack DataStore repository storing hearing safety mode (default Safe Mode),
 * playback controls (shuffle/repeat/gapless), Safe-Mode-only ReplayGain settings, persisted SAF
 * URIs, context-aware last playback restoration state, and independent per-scope sort preferences.
 */
class UserPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val AUDIO_SAFETY_MODE = stringPreferencesKey("audio_safety_mode")
        val SHUFFLE_ENABLED = booleanPreferencesKey("shuffle_enabled")
        val REPEAT_MODE = stringPreferencesKey("repeat_mode")
        val GAPLESS_ENABLED = booleanPreferencesKey("gapless_enabled")
        val REPLAYGAIN_MODE = stringPreferencesKey("replaygain_mode")
        val REPLAYGAIN_PREAMP_DB = floatPreferencesKey("replaygain_preamp_db")
        val REPLAYGAIN_PREVENT_CLIPPING = booleanPreferencesKey("replaygain_prevent_clipping")
        val PERSISTED_SAF_TREE_URIS = stringSetPreferencesKey("persisted_saf_tree_uris")
        val PERSISTED_SAF_FILE_URIS = stringSetPreferencesKey("persisted_saf_file_uris")
        val LAST_TRACK_URI = stringPreferencesKey("last_track_uri")
        val LAST_TRACK_FINGERPRINT = stringPreferencesKey("last_track_fingerprint")
        val LAST_POSITION_MS = longPreferencesKey("last_position_ms")
        val LAST_QUEUE_SOURCE_TYPE = stringPreferencesKey("last_queue_source_type")
        val LAST_QUEUE_SOURCE_KEY = stringPreferencesKey("last_queue_source_key")
        val LAST_QUEUE_SOURCE_LABEL = stringPreferencesKey("last_queue_source_label")

        fun trackSortKey(scope: TrackSortScope) = stringPreferencesKey(scope.preferenceKey)
        fun groupSortKey(scope: GroupSortScope) = stringPreferencesKey(scope.preferenceKey)
    }

    val preferencesFlow: Flow<UserPreferences> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs -> mapPreferences(prefs) }

    suspend fun getSnapshot(): UserPreferences = preferencesFlow.first()

    suspend fun setAudioSafetyMode(mode: AudioSafetyModePreference) {
        dataStore.edit { prefs ->
            prefs[Keys.AUDIO_SAFETY_MODE] = mode.name
        }
    }

    suspend fun setShuffleEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.SHUFFLE_ENABLED] = enabled
        }
    }

    suspend fun setRepeatMode(mode: RepeatModePreference) {
        dataStore.edit { prefs ->
            prefs[Keys.REPEAT_MODE] = mode.name
        }
    }

    suspend fun setGaplessEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.GAPLESS_ENABLED] = enabled
        }
    }

    suspend fun setReplayGainMode(mode: ReplayGainModePreference) {
        dataStore.edit { prefs ->
            prefs[Keys.REPLAYGAIN_MODE] = mode.name
        }
    }

    suspend fun setReplayGainPreampDb(preampDb: Float) {
        dataStore.edit { prefs ->
            prefs[Keys.REPLAYGAIN_PREAMP_DB] = PersistenceSanitizer.sanitizeReplayGainPreampDb(preampDb)
        }
    }

    suspend fun setReplayGainPreventClipping(preventClipping: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.REPLAYGAIN_PREVENT_CLIPPING] = preventClipping
        }
    }

    suspend fun setTrackSort(scope: TrackSortScope, spec: TrackSortSpec) {
        val validSpec = if (spec.option in scope.availableOptions) spec else scope.defaultSpec
        dataStore.edit { prefs ->
            prefs[Keys.trackSortKey(scope)] = validSpec.serialize()
        }
    }

    suspend fun setGroupSort(scope: GroupSortScope, spec: GroupSortSpec) {
        dataStore.edit { prefs ->
            prefs[Keys.groupSortKey(scope)] = spec.serialize()
        }
    }

    suspend fun addPersistedSafTreeUri(uriString: String) {
        val cleaned = uriString.trim()
        if (cleaned.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[Keys.PERSISTED_SAF_TREE_URIS].orEmpty()
            prefs[Keys.PERSISTED_SAF_TREE_URIS] = PersistenceSanitizer.sanitizeUriSet(current + cleaned)
        }
    }

    suspend fun removePersistedSafTreeUri(uriString: String) {
        val cleaned = uriString.trim()
        dataStore.edit { prefs ->
            val current = prefs[Keys.PERSISTED_SAF_TREE_URIS].orEmpty()
            prefs[Keys.PERSISTED_SAF_TREE_URIS] = current - cleaned
        }
    }

    suspend fun addPersistedSafFileUri(uriString: String) {
        val cleaned = uriString.trim()
        if (cleaned.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[Keys.PERSISTED_SAF_FILE_URIS].orEmpty()
            prefs[Keys.PERSISTED_SAF_FILE_URIS] = PersistenceSanitizer.sanitizeUriSet(current + cleaned)
        }
    }

    suspend fun removePersistedSafFileUri(uriString: String) {
        val cleaned = uriString.trim()
        dataStore.edit { prefs ->
            val current = prefs[Keys.PERSISTED_SAF_FILE_URIS].orEmpty()
            prefs[Keys.PERSISTED_SAF_FILE_URIS] = current - cleaned
        }
    }

    suspend fun saveLastPlaybackState(
        trackUri: String?,
        contentFingerprint: String?,
        positionMs: Long,
        queueSourceType: QueueSourceType? = null,
        queueSourceKey: String? = null,
        queueSourceLabel: String? = null,
    ) {
        dataStore.edit { prefs ->
            val cleanUri = trackUri?.trim()?.takeIf { it.isNotEmpty() }
            val cleanFp = contentFingerprint
                ?.let { PersistenceSanitizer.normalizeFingerprint(it) }
                ?.takeIf { it.isNotEmpty() }

            if (cleanUri != null) {
                prefs[Keys.LAST_TRACK_URI] = cleanUri
            } else {
                prefs.remove(Keys.LAST_TRACK_URI)
            }
            if (cleanFp != null) {
                prefs[Keys.LAST_TRACK_FINGERPRINT] = cleanFp
            } else {
                prefs.remove(Keys.LAST_TRACK_FINGERPRINT)
            }
            prefs[Keys.LAST_POSITION_MS] = PersistenceSanitizer.sanitizeNonNegative(positionMs)

            if (queueSourceType != null) {
                prefs[Keys.LAST_QUEUE_SOURCE_TYPE] = queueSourceType.name
                val cleanKey = queueSourceKey?.trim()?.takeIf { it.isNotEmpty() }
                val cleanLabel = queueSourceLabel?.trim()?.takeIf { it.isNotEmpty() }
                if (cleanKey != null) {
                    prefs[Keys.LAST_QUEUE_SOURCE_KEY] = cleanKey
                } else {
                    prefs.remove(Keys.LAST_QUEUE_SOURCE_KEY)
                }
                if (cleanLabel != null) {
                    prefs[Keys.LAST_QUEUE_SOURCE_LABEL] = cleanLabel
                } else {
                    prefs.remove(Keys.LAST_QUEUE_SOURCE_LABEL)
                }
            }
        }
    }

    suspend fun clearLastPlaybackState() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.LAST_TRACK_URI)
            prefs.remove(Keys.LAST_TRACK_FINGERPRINT)
            prefs[Keys.LAST_POSITION_MS] = 0L
            prefs.remove(Keys.LAST_QUEUE_SOURCE_TYPE)
            prefs.remove(Keys.LAST_QUEUE_SOURCE_KEY)
            prefs.remove(Keys.LAST_QUEUE_SOURCE_LABEL)
        }
    }

    private fun mapPreferences(prefs: Preferences): UserPreferences {
        fun readTrackSort(scope: TrackSortScope): TrackSortSpec = TrackSortSpec.deserialize(
            raw = prefs[Keys.trackSortKey(scope)],
            allowedOptions = scope.availableOptions,
            fallback = scope.defaultSpec,
        )

        fun readGroupSort(scope: GroupSortScope): GroupSortSpec = GroupSortSpec.deserialize(
            raw = prefs[Keys.groupSortKey(scope)],
            fallback = scope.defaultSpec,
        )

        val storedSourceType = QueueSourceType.fromStoredName(prefs[Keys.LAST_QUEUE_SOURCE_TYPE])
        val storedSourceKey = prefs[Keys.LAST_QUEUE_SOURCE_KEY]?.trim()?.takeIf { it.isNotEmpty() }
        val storedSourceLabel = prefs[Keys.LAST_QUEUE_SOURCE_LABEL]?.trim()?.takeIf { it.isNotEmpty() }

        return UserPreferences(
            audioSafetyMode = AudioSafetyModePreference.fromStoredName(prefs[Keys.AUDIO_SAFETY_MODE]),
            shuffleEnabled = prefs[Keys.SHUFFLE_ENABLED] ?: false,
            repeatMode = RepeatModePreference.fromStoredName(prefs[Keys.REPEAT_MODE]),
            gaplessEnabled = prefs[Keys.GAPLESS_ENABLED] ?: true,
            replayGainMode = ReplayGainModePreference.fromStoredName(prefs[Keys.REPLAYGAIN_MODE]),
            replayGainPreampDb = PersistenceSanitizer.sanitizeReplayGainPreampDb(
                prefs[Keys.REPLAYGAIN_PREAMP_DB] ?: 0.0f,
            ),
            replayGainPreventClipping = prefs[Keys.REPLAYGAIN_PREVENT_CLIPPING] ?: true,
            persistedSafTreeUris = PersistenceSanitizer.sanitizeUriSet(
                prefs[Keys.PERSISTED_SAF_TREE_URIS].orEmpty(),
            ),
            persistedSafFileUris = PersistenceSanitizer.sanitizeUriSet(
                prefs[Keys.PERSISTED_SAF_FILE_URIS].orEmpty(),
            ),
            lastPlaybackState = LastPlaybackState(
                trackUri = prefs[Keys.LAST_TRACK_URI]?.trim()?.takeIf { it.isNotEmpty() },
                contentFingerprint = prefs[Keys.LAST_TRACK_FINGERPRINT]?.trim()?.takeIf { it.isNotEmpty() },
                positionMs = PersistenceSanitizer.sanitizeNonNegative(prefs[Keys.LAST_POSITION_MS] ?: 0L),
                queueSourceType = storedSourceType,
                queueSourceKey = storedSourceKey,
                queueSourceLabel = storedSourceLabel,
            ),
            sortPreferences = LibrarySortPreferences(
                allSongs = readTrackSort(TrackSortScope.ALL_SONGS),
                albumDetail = readTrackSort(TrackSortScope.ALBUM_DETAIL),
                artistDetail = readTrackSort(TrackSortScope.ARTIST_DETAIL),
                folderDetail = readTrackSort(TrackSortScope.FOLDER_DETAIL),
                recent = readTrackSort(TrackSortScope.RECENT),
                favorites = readTrackSort(TrackSortScope.FAVORITES),
                playlistDetail = readTrackSort(TrackSortScope.PLAYLIST_DETAIL),
                foldersList = readGroupSort(GroupSortScope.FOLDERS_LIST),
                artistsList = readGroupSort(GroupSortScope.ARTISTS_LIST),
                albumsList = readGroupSort(GroupSortScope.ALBUMS_LIST),
            ),
        )
    }

    companion object {
        fun fromContext(context: Context): UserPreferencesRepository =
            UserPreferencesRepository(context.applicationContext.bitPerfectUserPreferencesDataStore)
    }
}
