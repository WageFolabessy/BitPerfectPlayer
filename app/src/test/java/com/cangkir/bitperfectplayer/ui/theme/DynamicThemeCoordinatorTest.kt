package com.cangkir.bitperfectplayer.ui.theme

import com.cangkir.bitperfectplayer.audio.AlbumPaletteExtractor
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.data.NowPlayingArtworkStyle
import com.cangkir.bitperfectplayer.library.EmbeddedArtworkSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DynamicThemeCoordinatorTest {

    private val trackA = PlaybackTrack(
        uriString = "content://test/audio/track_a.flac",
        title = "Track A",
        artist = "Studio Artist",
        album = "Debut LP",
        fileName = "track_a.flac",
        durationMs = 240_000L,
        container = "FLAC",
        codec = "FLAC",
    )

    private val trackB = PlaybackTrack(
        uriString = "content://test/audio/track_b.flac",
        title = "Track B",
        artist = "Studio Artist",
        album = "Second LP",
        fileName = "track_b.flac",
        durationMs = 210_000L,
        container = "FLAC",
        codec = "FLAC",
    )

    private val bluePalette: ArtworkColorPalette =
        ArtworkPaletteExtractor.extractPalette(IntArray(24 * 24) { 0xFF1D4ED8.toInt() }, 24, 24)

    private val goldPalette: ArtworkColorPalette =
        ArtworkPaletteExtractor.extractPalette(IntArray(24 * 24) { 0xFFE5A93B.toInt() }, 24, 24)

    private val crimsonPalette: ArtworkColorPalette =
        ArtworkPaletteExtractor.extractPalette(IntArray(24 * 24) { 0xFFD9363E.toInt() }, 24, 24)

    private val emeraldPalette: ArtworkColorPalette =
        ArtworkPaletteExtractor.extractPalette(IntArray(24 * 24) { 0xFF10B981.toInt() }, 24, 24)

    private val monoPalette: ArtworkColorPalette = ArtworkPaletteExtractor.MONOCHROME_STUDIO_PALETTE

    private inline fun TestScope.withCoordinatorScope(block: (CoroutineScope) -> Unit) {
        val coordinatorScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        try {
            block(coordinatorScope)
        } finally {
            coordinatorScope.cancel()
            advanceUntilIdle()
        }
    }

    private class RecordingMultiSlotResolver(
        private val palettesByTrackSlot: Map<Pair<String, EmbeddedArtworkSlot>, ArtworkColorPalette?>,
    ) : CoverThemeResolver {
        val requestedCalls = mutableListOf<Pair<String, EmbeddedArtworkSlot>>()

        override fun supportsMultiSlotPalettes(): Boolean = true

        override fun cacheKeyFor(track: PlaybackTrack): String = track.uriString

        override fun cacheKeyFor(track: PlaybackTrack, slot: EmbeddedArtworkSlot): String = "${track.uriString}|${slot.name}"

        override suspend fun resolveTokens(track: PlaybackTrack?): DynamicThemeTokens? =
            resolvePalette(track, EmbeddedArtworkSlot.FRONT_COVER)?.let {
                CoverThemePaletteGenerator.generateTokensFromPalette(it)
            }

        override suspend fun resolvePalette(track: PlaybackTrack?, slot: EmbeddedArtworkSlot): ArtworkColorPalette? {
            if (track == null) return null
            requestedCalls.add(track.uriString to slot)
            return palettesByTrackSlot[track.uriString to slot]
        }

        override fun clear() {
            requestedCalls.clear()
        }
    }

    @Test
    fun vinylDiscMode_usesChromaticDiscSlotLazilyWithoutDecodingFrontOrBackCover() = runTest {
        withCoordinatorScope { scope ->
            val resolver = RecordingMultiSlotResolver(
                mapOf(
                    (trackA.uriString to EmbeddedArtworkSlot.DISC) to goldPalette,
                    (trackA.uriString to EmbeddedArtworkSlot.FRONT_COVER) to bluePalette,
                    (trackA.uriString to EmbeddedArtworkSlot.BACK_COVER) to crimsonPalette,
                ),
            )
            val coordinator = DynamicThemeCoordinator(
                scope = scope,
                coverThemeResolver = resolver,
                dynamicThemeEnabled = MutableStateFlow(true),
                activeTrackFlow = MutableStateFlow<PlaybackTrack?>(trackA),
                artworkStyleFlow = MutableStateFlow(NowPlayingArtworkStyle.VINYL_DISC),
            )

            advanceUntilIdle()

            assertEquals(goldPalette, coordinator.activeTrackPalette.value)
            assertNotNull(coordinator.globalDynamicThemeTokens.value)
            // Strict lazy single-slot loading: chromatic DISC must NEVER decode FRONT_COVER, BACK_COVER, or ARTIST.
            assertEquals(listOf(trackA.uriString to EmbeddedArtworkSlot.DISC), resolver.requestedCalls)
        }
    }

    @Test
    fun vinylDiscMode_monochromeOrMissingDiscBorrowsChromaticFrontCoverElseMonochromeStudio() = runTest {
        withCoordinatorScope { scope ->
            // Case 1: Monochrome DISC + Chromatic FRONT_COVER -> lazily borrows FRONT_COVER.
            val monoDiscResolver = RecordingMultiSlotResolver(
                mapOf(
                    (trackA.uriString to EmbeddedArtworkSlot.DISC) to monoPalette,
                    (trackA.uriString to EmbeddedArtworkSlot.FRONT_COVER) to bluePalette,
                ),
            )
            val coordinator = DynamicThemeCoordinator(
                scope = scope,
                coverThemeResolver = monoDiscResolver,
                dynamicThemeEnabled = MutableStateFlow(true),
                activeTrackFlow = MutableStateFlow<PlaybackTrack?>(trackA),
                artworkStyleFlow = MutableStateFlow(NowPlayingArtworkStyle.VINYL_DISC),
            )
            advanceUntilIdle()

            assertEquals(bluePalette, coordinator.activeTrackPalette.value)
            assertEquals(
                CoverThemePaletteGenerator.generateTokensFromPalette(bluePalette, DynamicThemeIntensity.SUBTLE),
                coordinator.globalDynamicThemeTokens.value,
            )
            assertEquals(
                listOf(
                    trackA.uriString to EmbeddedArtworkSlot.DISC,
                    trackA.uriString to EmbeddedArtworkSlot.FRONT_COVER,
                ),
                monoDiscResolver.requestedCalls,
            )

            // Case 2: Both DISC (missing) and FRONT_COVER (monochrome) -> Monochrome Studio (null tokens).
            val bothMonoResolver = RecordingMultiSlotResolver(
                mapOf(
                    (trackA.uriString to EmbeddedArtworkSlot.DISC) to null,
                    (trackA.uriString to EmbeddedArtworkSlot.FRONT_COVER) to monoPalette,
                ),
            )
            val monoCoordinator = DynamicThemeCoordinator(
                scope = scope,
                coverThemeResolver = bothMonoResolver,
                dynamicThemeEnabled = MutableStateFlow(true),
                activeTrackFlow = MutableStateFlow<PlaybackTrack?>(trackA),
                artworkStyleFlow = MutableStateFlow(NowPlayingArtworkStyle.VINYL_DISC),
            )
            advanceUntilIdle()

            assertNull(monoCoordinator.globalDynamicThemeTokens.value)
            assertTrue(monoCoordinator.activeSpectrumTriad.value.contentEquals(AlbumPaletteExtractor.FALLBACK_STUDIO_PALETTE))
        }
    }

    @Test
    fun squareCoverMode_loadsFrontOnlyThenBackOnFlipAndBorrowsFrontIfBackIsMonochrome() = runTest {
        withCoordinatorScope { scope ->
            val resolver = RecordingMultiSlotResolver(
                mapOf(
                    (trackA.uriString to EmbeddedArtworkSlot.FRONT_COVER) to bluePalette,
                    (trackA.uriString to EmbeddedArtworkSlot.BACK_COVER) to crimsonPalette,
                    (trackB.uriString to EmbeddedArtworkSlot.FRONT_COVER) to emeraldPalette,
                    (trackB.uriString to EmbeddedArtworkSlot.BACK_COVER) to monoPalette,
                ),
            )
            val activeTrack = MutableStateFlow<PlaybackTrack?>(trackA)
            val style = MutableStateFlow(NowPlayingArtworkStyle.SQUARE_COVER)
            val coordinator = DynamicThemeCoordinator(
                scope = scope,
                coverThemeResolver = resolver,
                dynamicThemeEnabled = MutableStateFlow(true),
                activeTrackFlow = activeTrack,
                artworkStyleFlow = style,
            )

            advanceUntilIdle()
            assertEquals(bluePalette, coordinator.activeTrackPalette.value)
            assertFalse(coordinator.isNowPlayingBackCoverVisible.value)
            assertEquals(listOf(trackA.uriString to EmbeddedArtworkSlot.FRONT_COVER), resolver.requestedCalls)

            // Flip to Back Cover on Track A -> loads BACK_COVER on demand and applies crimson palette.
            coordinator.setNowPlayingBackCoverVisible(true)
            advanceUntilIdle()
            assertTrue(coordinator.isNowPlayingBackCoverVisible.value)
            assertEquals(crimsonPalette, coordinator.activeTrackPalette.value)
            assertEquals(
                listOf(
                    trackA.uriString to EmbeddedArtworkSlot.FRONT_COVER,
                    trackA.uriString to EmbeddedArtworkSlot.BACK_COVER,
                ),
                resolver.requestedCalls,
            )

            // Skip to Track B -> back-cover flip resets automatically; ONLY Track B FRONT_COVER is loaded!
            resolver.requestedCalls.clear()
            activeTrack.value = trackB
            advanceUntilIdle()
            assertFalse(coordinator.isNowPlayingBackCoverVisible.value)
            assertEquals(emeraldPalette, coordinator.activeTrackPalette.value)
            assertEquals(listOf(trackB.uriString to EmbeddedArtworkSlot.FRONT_COVER), resolver.requestedCalls)

            // Flip Track B to monochrome BACK_COVER -> borrows chromatic Track B FRONT_COVER!
            resolver.requestedCalls.clear()
            coordinator.setNowPlayingBackCoverVisible(true)
            advanceUntilIdle()
            assertTrue(coordinator.isNowPlayingBackCoverVisible.value)
            assertEquals(emeraldPalette, coordinator.activeTrackPalette.value)
            assertEquals(
                listOf(
                    trackB.uriString to EmbeddedArtworkSlot.BACK_COVER,
                    trackB.uriString to EmbeddedArtworkSlot.FRONT_COVER,
                ),
                resolver.requestedCalls,
            )
        }
    }

    @Test
    fun artistSlotPolicy_usesChromaticArtistFirstAndLazilyBorrowsOldestAlbumFrontOnMonochromeOrMissing() = runTest {
        var albumLoaderCalls = 0
        val chromaticArtist = DynamicThemeSlotPolicy.resolveArtistPaletteWithFallback(
            loadArtistPalette = { crimsonPalette },
            loadOldestAlbumFrontPalette = {
                albumLoaderCalls++
                bluePalette
            },
        )
        assertEquals(crimsonPalette, chromaticArtist)
        assertEquals("Chromatic ARTIST slot must never trigger album fallback decode", 0, albumLoaderCalls)

        val monoArtistBorrow = DynamicThemeSlotPolicy.resolveArtistPaletteWithFallback(
            loadArtistPalette = { monoPalette },
            loadOldestAlbumFrontPalette = {
                albumLoaderCalls++
                bluePalette
            },
        )
        assertEquals(bluePalette, monoArtistBorrow)
        assertEquals(1, albumLoaderCalls)

        val missingArtistBorrow = DynamicThemeSlotPolicy.resolveArtistPaletteWithFallback(
            loadArtistPalette = { null },
            loadOldestAlbumFrontPalette = {
                albumLoaderCalls++
                goldPalette
            },
        )
        assertEquals(goldPalette, missingArtistBorrow)
        assertEquals(2, albumLoaderCalls)

        val bothMonochrome = DynamicThemeSlotPolicy.resolveArtistPaletteWithFallback(
            loadArtistPalette = { monoPalette },
            loadOldestAlbumFrontPalette = { monoPalette },
        )
        assertNull(DynamicThemeSlotPolicy.toDynamicTokens(bothMonochrome))
    }

    @Test
    fun dynamicThemeCoordinator_intensityToggleAndContextualOverridesRequireZeroRedecodes() = runTest {
        withCoordinatorScope { scope ->
            val resolver = RecordingMultiSlotResolver(
                mapOf((trackA.uriString to EmbeddedArtworkSlot.FRONT_COVER) to bluePalette),
            )
            val enabled = MutableStateFlow(true)
            val coordinator = DynamicThemeCoordinator(
                scope = scope,
                coverThemeResolver = resolver,
                dynamicThemeEnabled = enabled,
                activeTrackFlow = MutableStateFlow<PlaybackTrack?>(trackA),
                artworkStyleFlow = MutableStateFlow(NowPlayingArtworkStyle.SQUARE_COVER),
            )
            advanceUntilIdle()

            val subtleTokens = coordinator.globalDynamicThemeTokens.value
            assertNotNull(subtleTokens)
            assertEquals(DynamicThemeIntensity.SUBTLE, subtleTokens!!.intensity)
            assertEquals(1, resolver.requestedCalls.size)

            // Switching SUBTLE -> VIBRANT re-maps cached ArtworkColorPalette instantaneously with zero resolver calls.
            coordinator.setIntensity(DynamicThemeIntensity.VIBRANT)
            advanceUntilIdle()
            val vibrantTokens = coordinator.globalDynamicThemeTokens.value
            assertNotNull(vibrantTokens)
            assertEquals(DynamicThemeIntensity.VIBRANT, vibrantTokens!!.intensity)
            assertEquals(1, resolver.requestedCalls.size)

            // Contextual override (e.g. Album Detail) supersedes playback theme and restores cleanly on clear.
            val contextualTokens = CoverThemePaletteGenerator.generateTokensFromPalette(goldPalette, DynamicThemeIntensity.VIBRANT)!!
            coordinator.setContextualDynamicTheme("library", contextualTokens)
            advanceUntilIdle()
            assertEquals(contextualTokens, coordinator.globalDynamicThemeTokens.value)

            coordinator.setContextualDynamicTheme("library", null)
            advanceUntilIdle()
            assertEquals(vibrantTokens, coordinator.globalDynamicThemeTokens.value)

            // Disabling Dynamic Theme clears active tokens immediately back to Monochrome Studio (null).
            enabled.value = false
            advanceUntilIdle()
            assertNull(coordinator.globalDynamicThemeTokens.value)
            assertEquals(1, resolver.requestedCalls.size)
        }
    }
}
