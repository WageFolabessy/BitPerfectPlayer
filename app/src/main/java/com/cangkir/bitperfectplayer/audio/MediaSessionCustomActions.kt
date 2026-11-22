package com.cangkir.bitperfectplayer.audio

/**
 * Logical icon identifiers for `MediaSession` / notification custom actions mapped to monochrome
 * Android vector drawables inside [PlaybackForegroundService] (zero `android.*` imports).
 */
enum class MediaCustomActionIcon {
    FAVORITE_FILLED,
    FAVORITE_OUTLINE,
    SHUFFLE_ON,
    SHUFFLE_OFF,
}

/**
 * Pure-Kotlin descriptor for a `MediaSession` `PlaybackState.CustomAction` and matching
 * `Notification.Action`.
 */
data class MediaCustomActionSpec(val action: String, val label: String, val icon: MediaCustomActionIcon)

/**
 * Dispatched custom action command resolved from `MediaSession.Callback.onCustomAction` or
 * `PlaybackForegroundService.onStartCommand`.
 */
enum class MediaCustomActionCommand {
    TOGGLE_FAVORITE,
    TOGGLE_SHUFFLE,
    UNKNOWN,
}

/**
 * Pure-Kotlin contract and state mapper for `Favorite` and `Shuffle` `MediaSession` custom actions
 * (zero `android.*` / `androidx.*` imports).
 */
object MediaSessionCustomActions {
    const val ACTION_TOGGLE_FAVORITE = "com.cangkir.bitperfectplayer.action.TOGGLE_FAVORITE"
    const val ACTION_TOGGLE_SHUFFLE = "com.cangkir.bitperfectplayer.action.TOGGLE_SHUFFLE"

    fun buildCustomActionSpecs(isFavorite: Boolean, shuffleEnabled: Boolean, hasActiveTrack: Boolean): List<MediaCustomActionSpec> {
        val specs = ArrayList<MediaCustomActionSpec>(2)
        if (hasActiveTrack) {
            specs.add(
                MediaCustomActionSpec(
                    action = ACTION_TOGGLE_FAVORITE,
                    label = if (isFavorite) "Remove from Favorites" else "Add to Favorites",
                    icon = if (isFavorite) MediaCustomActionIcon.FAVORITE_FILLED else MediaCustomActionIcon.FAVORITE_OUTLINE,
                ),
            )
        }
        specs.add(
            MediaCustomActionSpec(
                action = ACTION_TOGGLE_SHUFFLE,
                label = if (shuffleEnabled) "Shuffle On" else "Shuffle Off",
                icon = if (shuffleEnabled) MediaCustomActionIcon.SHUFFLE_ON else MediaCustomActionIcon.SHUFFLE_OFF,
            ),
        )
        return specs
    }

    fun resolveCommand(action: String?): MediaCustomActionCommand = when (action) {
        ACTION_TOGGLE_FAVORITE -> MediaCustomActionCommand.TOGGLE_FAVORITE
        ACTION_TOGGLE_SHUFFLE -> MediaCustomActionCommand.TOGGLE_SHUFFLE
        else -> MediaCustomActionCommand.UNKNOWN
    }
}
