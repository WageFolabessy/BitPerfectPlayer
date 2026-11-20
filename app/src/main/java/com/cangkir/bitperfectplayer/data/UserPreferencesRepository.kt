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
 * URIs, and last playback restoration state.
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
        }
    }

    suspend fun clearLastPlaybackState() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.LAST_TRACK_URI)
            prefs.remove(Keys.LAST_TRACK_FINGERPRINT)
            prefs[Keys.LAST_POSITION_MS] = 0L
        }
    }

    private fun mapPreferences(prefs: Preferences): UserPreferences {
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
            ),
        )
    }

    companion object {
        fun fromContext(context: Context): UserPreferencesRepository =
            UserPreferencesRepository(context.applicationContext.bitPerfectUserPreferencesDataStore)
    }
}
