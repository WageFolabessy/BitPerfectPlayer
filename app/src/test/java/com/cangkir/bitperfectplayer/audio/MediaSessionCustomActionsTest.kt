package com.cangkir.bitperfectplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSessionCustomActionsTest {

    @Test
    fun buildCustomActionSpecs_withActiveTrack_reflectsFavoriteAndShuffleStates() {
        val offOff = MediaSessionCustomActions.buildCustomActionSpecs(
            isFavorite = false,
            shuffleEnabled = false,
            hasActiveTrack = true,
        )
        assertEquals(2, offOff.size)
        assertEquals(MediaSessionCustomActions.ACTION_TOGGLE_FAVORITE, offOff[0].action)
        assertEquals("Add to Favorites", offOff[0].label)
        assertEquals(MediaCustomActionIcon.FAVORITE_OUTLINE, offOff[0].icon)
        assertEquals(MediaSessionCustomActions.ACTION_TOGGLE_SHUFFLE, offOff[1].action)
        assertEquals("Shuffle Off", offOff[1].label)
        assertEquals(MediaCustomActionIcon.SHUFFLE_OFF, offOff[1].icon)

        val onOn = MediaSessionCustomActions.buildCustomActionSpecs(
            isFavorite = true,
            shuffleEnabled = true,
            hasActiveTrack = true,
        )
        assertEquals(2, onOn.size)
        assertEquals("Remove from Favorites", onOn[0].label)
        assertEquals(MediaCustomActionIcon.FAVORITE_FILLED, onOn[0].icon)
        assertEquals("Shuffle On", onOn[1].label)
        assertEquals(MediaCustomActionIcon.SHUFFLE_ON, onOn[1].icon)
    }

    @Test
    fun buildCustomActionSpecs_withoutActiveTrack_omitsFavoriteAndKeepsShuffle() {
        val specs = MediaSessionCustomActions.buildCustomActionSpecs(
            isFavorite = true,
            shuffleEnabled = true,
            hasActiveTrack = false,
        )
        assertEquals(1, specs.size)
        assertEquals(MediaSessionCustomActions.ACTION_TOGGLE_SHUFFLE, specs[0].action)
        assertEquals(MediaCustomActionIcon.SHUFFLE_ON, specs[0].icon)
    }

    @Test
    fun resolveCommand_mapsKnownAndUnknownActionsDeterministically() {
        assertEquals(
            MediaCustomActionCommand.TOGGLE_FAVORITE,
            MediaSessionCustomActions.resolveCommand(MediaSessionCustomActions.ACTION_TOGGLE_FAVORITE),
        )
        assertEquals(
            MediaCustomActionCommand.TOGGLE_SHUFFLE,
            MediaSessionCustomActions.resolveCommand(MediaSessionCustomActions.ACTION_TOGGLE_SHUFFLE),
        )
        assertEquals(
            MediaCustomActionCommand.UNKNOWN,
            MediaSessionCustomActions.resolveCommand(null),
        )
        assertEquals(
            MediaCustomActionCommand.UNKNOWN,
            MediaSessionCustomActions.resolveCommand("com.example.UNKNOWN"),
        )
    }
}
