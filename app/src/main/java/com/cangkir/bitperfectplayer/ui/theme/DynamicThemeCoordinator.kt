package com.cangkir.bitperfectplayer.ui.theme

import com.cangkir.bitperfectplayer.audio.AlbumPaletteExtractor
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.data.NowPlayingArtworkStyle
import com.cangkir.bitperfectplayer.library.EmbeddedArtworkSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Lazy slot-resolution plan pairing the actively displayed artwork slot with an optional
 * monochrome-borrow fallback slot (never eagerly loading unused slots).
 */
data class ArtworkSlotResolutionPlan(val primarySlot: EmbeddedArtworkSlot, val fallbackSlot: EmbeddedArtworkSlot? = null)

/**
 * Pure-Kotlin, stateless slot selection and monochrome-borrow rules for context-aware 4-slot
 * artwork theming (`FRONT_COVER`, `BACK_COVER`, `DISC`, `ARTIST`).
 *
 * Guarantees:
 * - Zero `android.*` / `androidx.*` imports for deterministic JUnit 4 JVM testing.
 * - Lazy single-slot evaluation: `fallbackSlot` is invoked ONLY when `primarySlot` is absent
 *   (`null`) or purely monochrome ([ArtworkColorPalette.isMonochrome] == `true`).
 */
object DynamicThemeSlotPolicy {

    /**
     * Resolves which embedded artwork slot governs Now Playing theming & Album-Adaptive spectrum
     * accents based on the actively visible presentation mode:
     * - [NowPlayingArtworkStyle.VINYL_DISC]: `DISC` slot, lazily borrowing `FRONT_COVER` if absent or monochrome.
     * - [NowPlayingArtworkStyle.SQUARE_COVER] with back cover flipped visible: `BACK_COVER` slot,
     *   lazily borrowing `FRONT_COVER` if absent or monochrome.
     * - [NowPlayingArtworkStyle.SQUARE_COVER] default front face: `FRONT_COVER` only.
     */
    fun resolveNowPlayingSlotPlan(artworkStyle: NowPlayingArtworkStyle, isBackCoverVisible: Boolean): ArtworkSlotResolutionPlan =
        when (artworkStyle) {
            NowPlayingArtworkStyle.VINYL_DISC -> ArtworkSlotResolutionPlan(
                primarySlot = EmbeddedArtworkSlot.DISC,
                fallbackSlot = EmbeddedArtworkSlot.FRONT_COVER,
            )
            NowPlayingArtworkStyle.SQUARE_COVER -> if (isBackCoverVisible) {
                ArtworkSlotResolutionPlan(
                    primarySlot = EmbeddedArtworkSlot.BACK_COVER,
                    fallbackSlot = EmbeddedArtworkSlot.FRONT_COVER,
                )
            } else {
                ArtworkSlotResolutionPlan(
                    primarySlot = EmbeddedArtworkSlot.FRONT_COVER,
                    fallbackSlot = null,
                )
            }
        }

    /**
     * Resolves the active track's perceptual [ArtworkColorPalette] lazily according to
     * [resolveNowPlayingSlotPlan]. When the primary slot returns a chromatic palette, [loadSlotPalette]
     * is NEVER called for the fallback slot.
     */
    suspend fun resolveTrackPaletteWithBorrow(
        track: PlaybackTrack?,
        artworkStyle: NowPlayingArtworkStyle,
        isBackCoverVisible: Boolean,
        loadSlotPalette: suspend (PlaybackTrack, EmbeddedArtworkSlot) -> ArtworkColorPalette?,
    ): ArtworkColorPalette? {
        if (track == null) return null
        val plan = resolveNowPlayingSlotPlan(artworkStyle, isBackCoverVisible)
        val primary = loadSlotPalette(track, plan.primarySlot)
        if (primary != null && !primary.isMonochrome) return primary

        val fallbackSlot = plan.fallbackSlot ?: return primary
        val fallback = loadSlotPalette(track, fallbackSlot)
        return if (fallback != null && !fallback.isMonochrome) fallback else primary ?: fallback
    }

    /**
     * Resolves Artist Detail palette lazily: tries `ARTIST` slot (custom photo or embedded `ARTIST`
     * art) first, and ONLY when absent or monochrome falls back to the artist's oldest-first album
     * `FRONT_COVER` before defaulting to Monochrome Studio (`null` / monochrome).
     */
    suspend fun resolveArtistPaletteWithFallback(
        loadArtistPalette: suspend () -> ArtworkColorPalette?,
        loadOldestAlbumFrontPalette: suspend () -> ArtworkColorPalette?,
    ): ArtworkColorPalette? {
        val artistPalette = loadArtistPalette()
        if (artistPalette != null && !artistPalette.isMonochrome) return artistPalette

        val albumFallback = loadOldestAlbumFrontPalette()
        return if (albumFallback != null && !albumFallback.isMonochrome) albumFallback else artistPalette ?: albumFallback
    }

    /** Maps [palette] into contrast-verified [BitPerfectDynamicTokens] for [intensity], or `null` if monochrome/absent. */
    fun toDynamicTokens(
        palette: ArtworkColorPalette?,
        intensity: DynamicThemeIntensity = DynamicThemeIntensity.SUBTLE,
    ): BitPerfectDynamicTokens? = palette?.let { CoverThemePaletteGenerator.generateTokensFromPalette(it, intensity) }

    /** Maps [palette] into a 3-stop OLED spectrum triad, or Monochrome Studio silver-ivory if monochrome/absent. */
    fun toSpectrumTriad(palette: ArtworkColorPalette?): IntArray = if (palette != null && !palette.isMonochrome) {
        palette.toSpectrumTriad()
    } else {
        AlbumPaletteExtractor.FALLBACK_STUDIO_PALETTE.copyOf()
    }

    /** Maps [palette] into a 4-stop OLED spectrum palette `[low, mid, high, highlightPeak]`. */
    fun toSpectrumPalette(palette: ArtworkColorPalette?): IntArray = if (palette != null && !palette.isMonochrome) {
        palette.toSpectrumPalette()
    } else {
        AlbumPaletteExtractor.FALLBACK_STUDIO_PALETTE_4.copyOf()
    }
}

/**
 * Pure-Kotlin reactive coordinator orchestrating context-aware multi-slot Dynamic Theme tokens,
 * [DynamicThemeIntensity] (`SUBTLE` vs `VIBRANT`), back-cover flip state, and contextual detail
 * screen overrides (`Library`, `Album Detail`, `Artist Detail`, `Playlist Detail`) off the main
 * thread without bloating `MainViewModel`.
 */
class DynamicThemeCoordinator(
    private val scope: CoroutineScope,
    private val coverThemeResolver: CoverThemeResolver,
    private val dynamicThemeEnabled: StateFlow<Boolean>,
    private val activeTrackFlow: StateFlow<PlaybackTrack?>,
    private val artworkStyleFlow: StateFlow<NowPlayingArtworkStyle>,
    initialIntensity: DynamicThemeIntensity = DynamicThemeIntensity.SUBTLE,
    private val intensityFlow: StateFlow<DynamicThemeIntensity>? = null,
) {
    private data class PlaybackThemeRequest(
        val enabled: Boolean,
        val track: PlaybackTrack?,
        val artworkStyle: NowPlayingArtworkStyle,
        val isBackCoverVisible: Boolean,
        val requestIdentityKey: String,
    )

    private val intensityState = MutableStateFlow(intensityFlow?.value ?: initialIntensity)
    val intensity: StateFlow<DynamicThemeIntensity> = intensityState.asStateFlow()

    // Keyed by track URI so track skips immediately reset back-cover visibility without a race window.
    private val backCoverVisibleTrackUriState = MutableStateFlow<String?>(null)

    val isNowPlayingBackCoverVisible: StateFlow<Boolean> = combine(
        activeTrackFlow,
        artworkStyleFlow,
        backCoverVisibleTrackUriState,
    ) { track, style, visibleTrackUri ->
        style == NowPlayingArtworkStyle.SQUARE_COVER && track != null && visibleTrackUri == track.uriString
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = false,
    )

    private val activeTrackPaletteState = MutableStateFlow<ArtworkColorPalette?>(null)
    val activeTrackPalette: StateFlow<ArtworkColorPalette?> = activeTrackPaletteState.asStateFlow()

    // Fallback token slot used strictly when a legacy CoverThemeResolver does not expose multi-slot palettes.
    private val legacyPlaybackTokensState = MutableStateFlow<DynamicThemeTokens?>(null)
    private val contextualDynamicThemeBySourceState = MutableStateFlow<Map<String, DynamicThemeTokens>>(emptyMap())

    val activeSpectrumTriad: StateFlow<IntArray> = activeTrackPaletteState
        .map { palette -> DynamicThemeSlotPolicy.toSpectrumTriad(palette) }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = AlbumPaletteExtractor.FALLBACK_STUDIO_PALETTE.copyOf(),
        )

    val globalDynamicThemeTokens: StateFlow<BitPerfectDynamicTokens?> = combine(
        dynamicThemeEnabled,
        intensityState,
        activeTrackPaletteState,
        legacyPlaybackTokensState,
        contextualDynamicThemeBySourceState,
    ) { enabled, currentIntensity, palette, legacyTokens, contextualMap ->
        if (!enabled) {
            null
        } else {
            contextualMap.values.lastOrNull()
                ?: DynamicThemeSlotPolicy.toDynamicTokens(palette, currentIntensity)
                ?: legacyTokens
        }
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = null,
    )

    init {
        intensityFlow?.let { externalFlow ->
            scope.launch {
                externalFlow.collect { persisted ->
                    intensityState.value = persisted
                }
            }
        }
        observePlaybackTheme()
    }

    /** Updates Dynamic Theme chromatic intensity (`SUBTLE` vs `VIBRANT`) instantly from cached OKLCH palettes. */
    fun setIntensity(newIntensity: DynamicThemeIntensity) {
        intensityState.value = newIntensity
    }

    /**
     * Records whether the Square Cover back face is currently flipped visible on Now Playing.
     * Automatically clears when the active track changes or artwork style switches to Vinyl Disc.
     */
    fun setNowPlayingBackCoverVisible(visible: Boolean, trackUri: String? = activeTrackFlow.value?.uriString) {
        backCoverVisibleTrackUriState.value = if (visible) trackUri else null
    }

    /** Applies or clears a contextual detail screen dynamic theme override (e.g. Album/Artist/Playlist Detail). */
    fun setContextualDynamicTheme(sourceKey: String, tokens: DynamicThemeTokens?) {
        contextualDynamicThemeBySourceState.update { current ->
            if (tokens == null) current - sourceKey else current + (sourceKey to tokens)
        }
    }

    private fun observePlaybackTheme() {
        scope.launch {
            combine(
                dynamicThemeEnabled,
                activeTrackFlow,
                artworkStyleFlow,
                backCoverVisibleTrackUriState,
            ) { enabled, track, style, visibleBackUri ->
                val isBackVisible = style == NowPlayingArtworkStyle.SQUARE_COVER &&
                    track != null &&
                    visibleBackUri == track.uriString
                val identityKey = buildRequestIdentityKey(track, style, isBackVisible)
                PlaybackThemeRequest(
                    enabled = enabled,
                    track = track,
                    artworkStyle = style,
                    isBackCoverVisible = isBackVisible,
                    requestIdentityKey = identityKey,
                )
            }
                .distinctUntilChangedBy { req ->
                    Triple(req.enabled, req.artworkStyle, req.isBackCoverVisible) to req.requestIdentityKey
                }
                .collectLatest { req ->
                    resolvePlaybackRequest(req)
                }
        }
    }

    private suspend fun resolvePlaybackRequest(req: PlaybackThemeRequest) {
        val track = req.track
        if (!req.enabled || track == null) {
            activeTrackPaletteState.value = null
            legacyPlaybackTokensState.value = null
            return
        }
        if (coverThemeResolver.supportsMultiSlotPalettes()) {
            legacyPlaybackTokensState.value = null
            activeTrackPaletteState.value = DynamicThemeSlotPolicy.resolveTrackPaletteWithBorrow(
                track = track,
                artworkStyle = req.artworkStyle,
                isBackCoverVisible = req.isBackCoverVisible,
                loadSlotPalette = { t, slot -> coverThemeResolver.resolvePalette(t, slot) },
            )
        } else {
            activeTrackPaletteState.value = null
            legacyPlaybackTokensState.value = coverThemeResolver.resolveTokens(track)
        }
    }

    private fun buildRequestIdentityKey(track: PlaybackTrack?, style: NowPlayingArtworkStyle, isBackCoverVisible: Boolean): String {
        if (track == null) return ""
        val plan = DynamicThemeSlotPolicy.resolveNowPlayingSlotPlan(style, isBackCoverVisible)
        val primaryKey = coverThemeResolver.cacheKeyFor(track, plan.primarySlot)
        val fallbackKey = plan.fallbackSlot?.let { coverThemeResolver.cacheKeyFor(track, it) }.orEmpty()
        return "$primaryKey|$fallbackKey"
    }
}
