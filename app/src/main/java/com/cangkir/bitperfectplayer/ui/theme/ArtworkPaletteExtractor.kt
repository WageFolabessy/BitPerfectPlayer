package com.cangkir.bitperfectplayer.ui.theme

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Perceptual multi-color palette extracted from local artwork in OKLCH space.
 *
 * Carries genuine multi-color roles (`canvasTint`, `primaryAccent`, `secondaryAccent`,
 * `highlightPeak`, atmospheric glow stops, and 3 frequency-mapped spectrum accents) shared across
 * both Dynamic Theme and the offline Spectrum Visualizer (`SpectrumColorStyle.ALBUM_ADAPTIVE`).
 */
data class ArtworkColorPalette(
    val isMonochrome: Boolean,
    val canvasTintArgb: Int,
    val primaryAccentArgb: Int,
    val secondaryAccentArgb: Int,
    val highlightPeakArgb: Int,
    val glowPrimaryArgb: Int,
    val glowSecondaryArgb: Int,
    val spectrumLowArgb: Int,
    val spectrumMidArgb: Int,
    val spectrumHighArgb: Int,
    val primaryOklch: Oklch,
    val secondaryOklch: Oklch,
    val canvasOklch: Oklch,
) {
    fun toSpectrumTriad(): IntArray = intArrayOf(spectrumLowArgb, spectrumMidArgb, spectrumHighArgb)

    /** Returns 4-stop `[spectrumLow, spectrumMid, spectrumHigh, highlightPeak]` for Album-Adaptive spectrum & sparks. */
    fun toSpectrumPalette(): IntArray = intArrayOf(spectrumLowArgb, spectrumMidArgb, spectrumHighArgb, highlightPeakArgb)
}

/**
 * Pure-Kotlin, deterministic, center-weighted OKLab K-Means++ multi-color artwork extractor
 * (`k = 5`, `<= 12` iterations on `<= 48x48` pixels, zero `android.*` / `androidx.*` imports).
 *
 * Balances spatial center weighting, cluster population dominance, and perceptual chroma so
 * dominant cover fields stay faithful, distinct secondary artwork accents are preserved as true
 * multi-color harmonies, tiny neon corner stickers cannot hijack the palette, and monochrome/B&W
 * artwork cleanly signals [ArtworkColorPalette.isMonochrome] = `true`.
 */
object ArtworkPaletteExtractor {
    private const val MAX_GRID_DIM = 48
    private const val CLUSTER_COUNT = 5
    private const val MAX_KMEANS_ITERATIONS = 12
    private const val CONVERGENCE_EPS_SQ = 1e-6f
    private const val MIN_ALPHA = 128
    private const val MIN_RGB_DELTA = 14
    private const val MIN_OKLCH_CHROMA = 0.032f
    private const val MIN_OKLCH_L = 0.04f
    private const val MAX_OKLCH_L = 0.975f
    private const val MIN_CHROMATIC_WEIGHT_RATIO = 0.022f
    private const val MIN_PRIMARY_POPULATION_SHARE = 0.06f
    private const val MIN_SECONDARY_POPULATION_SHARE = 0.04f
    private const val MIN_TERTIARY_POPULATION_SHARE = 0.035f
    private const val MIN_SECONDARY_HUE_SEP_DEG = 28f
    private const val MIN_SPECTRUM_HUE_SEP_DEG = 36f

    /** Fallback Monochrome Studio palette returned for missing, transparent, or B&W artwork. */
    val MONOCHROME_STUDIO_PALETTE: ArtworkColorPalette = ArtworkColorPalette(
        isMonochrome = true,
        canvasTintArgb = 0xFF0E0E10.toInt(),
        primaryAccentArgb = 0xFFF2F2F0.toInt(),
        secondaryAccentArgb = 0xFFB8B8BE.toInt(),
        highlightPeakArgb = 0xFFF2F2F0.toInt(),
        glowPrimaryArgb = 0xFF2A2A30.toInt(),
        glowSecondaryArgb = 0xFF26262C.toInt(),
        spectrumLowArgb = 0xFFB8B8BE.toInt(),
        spectrumMidArgb = 0xFFD8D8DC.toInt(),
        spectrumHighArgb = 0xFFF2F2F0.toInt(),
        primaryOklch = Oklch(l = 0.96f, c = 0f, hDeg = 0f),
        secondaryOklch = Oklch(l = 0.78f, c = 0f, hDeg = 0f),
        canvasOklch = Oklch(l = 0.16f, c = 0f, hDeg = 0f),
    )

    private class SampleSet(
        val l: FloatArray,
        val a: FloatArray,
        val b: FloatArray,
        val chroma: FloatArray,
        val weight: FloatArray,
        val count: Int,
        val totalChromaticWeight: Float,
        val totalOpaqueWeight: Float,
    )

    private data class ScoredCluster(val oklch: Oklch, val lab: OkLab, val populationShare: Float, val primaryScore: Float)

    /**
     * Extracts a rich multi-color [ArtworkColorPalette] from ARGB [pixels] (`width * height`).
     * Returns [MONOCHROME_STUDIO_PALETTE] (`isMonochrome = true`) on empty, transparent, or B&W art.
     */
    fun extractPalette(pixels: IntArray, width: Int, height: Int): ArtworkColorPalette {
        if (width <= 0 || height <= 0 || pixels.isEmpty()) return MONOCHROME_STUDIO_PALETTE
        val totalPixels = minOf(pixels.size, width * height)
        if (totalPixels <= 0) return MONOCHROME_STUDIO_PALETTE

        val samples = sampleChromaticPixels(pixels, width, height, totalPixels)
        if (samples.count == 0 || samples.totalOpaqueWeight <= 1e-4f) return MONOCHROME_STUDIO_PALETTE
        if (samples.totalChromaticWeight / samples.totalOpaqueWeight < MIN_CHROMATIC_WEIGHT_RATIO) {
            return MONOCHROME_STUDIO_PALETTE
        }

        val clusters = runDeterministicKMeans(samples)
        if (clusters.isEmpty()) return MONOCHROME_STUDIO_PALETTE

        val primary = pickPrimaryCluster(clusters)
        val secondary = pickSecondaryCluster(clusters, primary)
        val tertiary = pickTertiaryCluster(clusters, primary, secondary)
        return buildPaletteFromClusters(primary, secondary, tertiary)
    }

    private fun sampleChromaticPixels(pixels: IntArray, width: Int, height: Int, totalPixels: Int): SampleSet {
        val sampleCols = min(width, MAX_GRID_DIM)
        val sampleRows = min(height, MAX_GRID_DIM)
        val maxPoints = sampleCols * sampleRows

        val lArr = FloatArray(maxPoints)
        val aArr = FloatArray(maxPoints)
        val bArr = FloatArray(maxPoints)
        val cArr = FloatArray(maxPoints)
        val wArr = FloatArray(maxPoints)

        var count = 0
        var totalChromaticWeight = 0f
        var totalOpaqueWeight = 0f

        for (sy in 0 until sampleRows) {
            val y = ((sy + 0.5f) * height / sampleRows).toInt().coerceIn(0, height - 1)
            val ny = ((y + 0.5f) / height.toFloat()) * 2f - 1f
            val rowOffset = y * width
            for (sx in 0 until sampleCols) {
                val x = ((sx + 0.5f) * width / sampleCols).toInt().coerceIn(0, width - 1)
                val idx = rowOffset + x
                val argb = if (idx < totalPixels) pixels[idx] else 0
                if (((argb ushr 24) and 0xFF) < MIN_ALPHA) continue

                val nx = ((x + 0.5f) / width.toFloat()) * 2f - 1f
                // Gentle spatial center weighting: de-emphasize outer frame edges & corner stickers.
                val spatialWeight = (1.0f - 0.30f * (nx * nx + ny * ny)).coerceIn(0.40f, 1.0f)
                totalOpaqueWeight += spatialWeight

                val lab = extractEligibleChromaticLab(argb)
                if (lab != null) {
                    lArr[count] = lab.l
                    aArr[count] = lab.a
                    bArr[count] = lab.b
                    cArr[count] = OklchColorMath.okLabToOklch(lab).c
                    wArr[count] = spatialWeight
                    totalChromaticWeight += spatialWeight
                    count++
                }
            }
        }

        return SampleSet(
            l = lArr,
            a = aArr,
            b = bArr,
            chroma = cArr,
            weight = wArr,
            count = count,
            totalChromaticWeight = totalChromaticWeight,
            totalOpaqueWeight = totalOpaqueWeight,
        )
    }

    private fun extractEligibleChromaticLab(argb: Int): OkLab? {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val rgbDelta = max(r, max(g, b)) - min(r, min(g, b))
        if (rgbDelta < MIN_RGB_DELTA) return null

        val lab = OklchColorMath.argbToOkLab(argb)
        val chroma = OklchColorMath.okLabToOklch(lab).c
        if (chroma < MIN_OKLCH_CHROMA || lab.l < MIN_OKLCH_L || lab.l > MAX_OKLCH_L) return null
        return lab
    }

    private fun runDeterministicKMeans(samples: SampleSet): List<ScoredCluster> {
        val k = min(CLUSTER_COUNT, samples.count)
        val cL = FloatArray(k)
        val cA = FloatArray(k)
        val cB = FloatArray(k)

        initializeCentroidsKMeansPlusPlus(samples, k, cL, cA, cB)

        val sumL = FloatArray(k)
        val sumA = FloatArray(k)
        val sumB = FloatArray(k)
        val sumW = FloatArray(k)

        for (iteration in 0 until MAX_KMEANS_ITERATIONS) {
            sumL.fill(0f)
            sumA.fill(0f)
            sumB.fill(0f)
            sumW.fill(0f)

            for (i in 0 until samples.count) {
                val best = findNearestCentroid(samples.l[i], samples.a[i], samples.b[i], k, cL, cA, cB)
                val w = samples.weight[i]
                sumL[best] += samples.l[i] * w
                sumA[best] += samples.a[i] * w
                sumB[best] += samples.b[i] * w
                sumW[best] += w
            }

            var maxShiftSq = 0f
            for (c in 0 until k) {
                val w = sumW[c]
                if (w > 1e-6f) {
                    val newL = sumL[c] / w
                    val newA = sumA[c] / w
                    val newB = sumB[c] / w
                    val dl = newL - cL[c]
                    val da = newA - cA[c]
                    val db = newB - cB[c]
                    maxShiftSq = max(maxShiftSq, dl * dl + da * da + db * db)
                    cL[c] = newL
                    cA[c] = newA
                    cB[c] = newB
                }
            }
            if (maxShiftSq < CONVERGENCE_EPS_SQ) break
        }

        val totalW = samples.totalChromaticWeight.coerceAtLeast(1e-6f)
        val results = ArrayList<ScoredCluster>(k)
        for (c in 0 until k) {
            val w = sumW[c]
            if (w <= 1e-6f) continue
            val lab = OkLab(cL[c], cA[c], cB[c])
            val oklch = OklchColorMath.okLabToOklch(lab)
            if (oklch.c < MIN_OKLCH_CHROMA * 0.65f) continue
            val popShare = (w / totalW).coerceIn(0f, 1f)
            val score = computePrimaryClusterScore(popShare, oklch)
            results.add(ScoredCluster(oklch = oklch, lab = lab, populationShare = popShare, primaryScore = score))
        }
        return results
    }

    private fun initializeCentroidsKMeansPlusPlus(samples: SampleSet, k: Int, cL: FloatArray, cA: FloatArray, cB: FloatArray) {
        // Seed 0: deterministic sample maximizing spatial weight * (0.35 + chroma).
        var firstIdx = 0
        var firstScore = -1f
        for (i in 0 until samples.count) {
            val s = samples.weight[i] * (0.35f + samples.chroma[i])
            if (s > firstScore) {
                firstScore = s
                firstIdx = i
            }
        }
        cL[0] = samples.l[firstIdx]
        cA[0] = samples.a[firstIdx]
        cB[0] = samples.b[firstIdx]

        if (k <= 1) return
        val minDistSq = FloatArray(samples.count) { Float.MAX_VALUE }

        for (c in 1 until k) {
            val prevL = cL[c - 1]
            val prevA = cA[c - 1]
            val prevB = cB[c - 1]
            var nextIdx = 0
            var bestWeightedDist = -1f

            for (i in 0 until samples.count) {
                val dl = samples.l[i] - prevL
                val da = samples.a[i] - prevA
                val db = samples.b[i] - prevB
                val dSq = dl * dl + da * da + db * db
                if (dSq < minDistSq[i]) minDistSq[i] = dSq

                val candidateScore = minDistSq[i] * samples.weight[i] * (0.25f + samples.chroma[i])
                if (candidateScore > bestWeightedDist) {
                    bestWeightedDist = candidateScore
                    nextIdx = i
                }
            }
            cL[c] = samples.l[nextIdx]
            cA[c] = samples.a[nextIdx]
            cB[c] = samples.b[nextIdx]
        }
    }

    private fun findNearestCentroid(l: Float, a: Float, b: Float, k: Int, cL: FloatArray, cA: FloatArray, cB: FloatArray): Int {
        var bestIdx = 0
        var bestDist = Float.MAX_VALUE
        for (c in 0 until k) {
            val dl = l - cL[c]
            val da = a - cA[c]
            val db = b - cB[c]
            val d = dl * dl + da * da + db * db
            if (d < bestDist) {
                bestDist = d
                bestIdx = c
            }
        }
        return bestIdx
    }

    private fun computePrimaryClusterScore(populationShare: Float, oklch: Oklch): Float {
        // Population exponent 0.75 guarantees tiny corner neon badges cannot beat a dominant color field.
        val popFactor = populationShare.pow(0.75f)
        val chromaFactor = 0.35f + (oklch.c / 0.22f).coerceIn(0f, 1.15f)
        val lightnessFactor = (1.0f - abs(oklch.l - 0.56f) * 0.70f).coerceIn(0.45f, 1.0f)
        return popFactor * chromaFactor * lightnessFactor
    }

    private fun pickPrimaryCluster(clusters: List<ScoredCluster>): ScoredCluster {
        val significant = clusters.filter { it.populationShare >= MIN_PRIMARY_POPULATION_SHARE }
        val pool = if (significant.isNotEmpty()) significant else clusters
        return pool.maxBy { it.primaryScore }
    }

    private fun pickSecondaryCluster(clusters: List<ScoredCluster>, primary: ScoredCluster): ScoredCluster? {
        var best: ScoredCluster? = null
        var bestScore = -1f
        for (cluster in clusters) {
            if (cluster === primary || cluster.populationShare < MIN_SECONDARY_POPULATION_SHARE) continue
            val hueDist = OklchColorMath.circularHueDistanceDeg(cluster.oklch.hDeg, primary.oklch.hDeg)
            val labDist = okLabDistance(cluster.lab, primary.lab)
            val isDistinct = hueDist >= MIN_SECONDARY_HUE_SEP_DEG || (hueDist >= 22f && labDist >= 0.14f)
            if (!isDistinct) continue

            val popFactor = cluster.populationShare.pow(0.55f)
            val chromaFactor = 0.40f + (cluster.oklch.c / 0.20f).coerceIn(0f, 1.20f)
            val sepBonus = 0.75f + (hueDist / 180f) * 0.50f
            val score = popFactor * chromaFactor * sepBonus
            if (score > bestScore) {
                bestScore = score
                best = cluster
            }
        }
        return best
    }

    private fun pickTertiaryCluster(clusters: List<ScoredCluster>, primary: ScoredCluster, secondary: ScoredCluster?): ScoredCluster? {
        if (secondary == null) return null
        var best: ScoredCluster? = null
        var bestScore = -1f
        for (cluster in clusters) {
            if (cluster === primary || cluster === secondary) continue
            if (cluster.populationShare < MIN_TERTIARY_POPULATION_SHARE) continue
            val distPrimary = OklchColorMath.circularHueDistanceDeg(cluster.oklch.hDeg, primary.oklch.hDeg)
            val distSecondary = OklchColorMath.circularHueDistanceDeg(cluster.oklch.hDeg, secondary.oklch.hDeg)
            if (distPrimary < MIN_SPECTRUM_HUE_SEP_DEG || distSecondary < MIN_SPECTRUM_HUE_SEP_DEG) continue

            val score = cluster.populationShare.pow(0.50f) * (0.45f + cluster.oklch.c * 4.0f)
            if (score > bestScore) {
                bestScore = score
                best = cluster
            }
        }
        return best
    }

    private fun buildPaletteFromClusters(
        primary: ScoredCluster,
        secondary: ScoredCluster?,
        tertiary: ScoredCluster?,
    ): ArtworkColorPalette {
        val primaryOklch = primary.oklch
        val secondaryOklch = secondary?.oklch ?: deriveFallbackSecondaryOklch(primaryOklch)

        val canvasChroma = (primaryOklch.c * 0.14f).coerceIn(0.008f, 0.022f)
        val canvasOklch = Oklch(l = 0.155f, c = canvasChroma, hDeg = primaryOklch.hDeg)
        val canvasTintArgb = OklchColorMath.oklchToSrgbArgb(canvasOklch)
        val darkInk = OklchColorMath.oklchToSrgbArgb(l = 0.145f, c = canvasChroma * 0.75f, hDeg = primaryOklch.hDeg)
        val darkRefSurface = OklchColorMath.oklchToSrgbArgb(
            l = 0.220f,
            c = (canvasChroma * 1.15f).coerceAtMost(0.028f),
            hDeg = primaryOklch.hDeg,
        )

        val primaryAccentArgb = OklchColorMath.solveAccessibleDarkThemeAccent(
            hueDeg = primaryOklch.hDeg,
            desiredChroma = (primaryOklch.c * 0.85f).coerceIn(0.09f, 0.16f),
            initialLightness = 0.75f,
            onAccentArgb = darkInk,
            darkReferenceArgb = darkRefSurface,
            minContrastRatio = 4.5,
            maxRelativeLuminance = 0.58,
        )
        val secondaryAccentArgb = OklchColorMath.solveAccessibleDarkThemeAccent(
            hueDeg = secondaryOklch.hDeg,
            desiredChroma = (secondaryOklch.c * 0.85f).coerceIn(0.08f, 0.15f),
            initialLightness = 0.75f,
            onAccentArgb = darkInk,
            darkReferenceArgb = darkRefSurface,
            minContrastRatio = 4.5,
            maxRelativeLuminance = 0.58,
        )

        val highlightHue = if (secondary != null && secondary.oklch.c >= primaryOklch.c * 0.85f) {
            secondaryOklch.hDeg
        } else {
            OklchColorMath.normalizeHueDeg(primaryOklch.hDeg + 18f)
        }
        val highlightPeakArgb = OklchColorMath.oklchToSrgbArgb(
            l = 0.85f,
            c = min(0.14f, OklchColorMath.maxInGamutChroma(0.85f, highlightHue) * 0.90f),
            hDeg = highlightHue,
        )

        val glowPrimaryChroma = min(
            (primaryOklch.c * 0.95f).coerceIn(0.08f, 0.16f),
            OklchColorMath.maxInGamutChroma(0.48f, primaryOklch.hDeg) * 0.92f,
        )
        val glowPrimaryArgb = OklchColorMath.oklchToSrgbArgb(l = 0.48f, c = glowPrimaryChroma, hDeg = primaryOklch.hDeg)
        val glowSecondaryChroma = min(
            (secondaryOklch.c * 0.90f).coerceIn(0.07f, 0.15f),
            OklchColorMath.maxInGamutChroma(0.44f, secondaryOklch.hDeg) * 0.92f,
        )
        val glowSecondaryArgb = OklchColorMath.oklchToSrgbArgb(l = 0.44f, c = glowSecondaryChroma, hDeg = secondaryOklch.hDeg)

        val (specLow, specMid, specHigh) = buildSpectrumTriad(primary, secondary, tertiary)

        return ArtworkColorPalette(
            isMonochrome = false,
            canvasTintArgb = canvasTintArgb,
            primaryAccentArgb = primaryAccentArgb,
            secondaryAccentArgb = secondaryAccentArgb,
            highlightPeakArgb = highlightPeakArgb,
            glowPrimaryArgb = glowPrimaryArgb,
            glowSecondaryArgb = glowSecondaryArgb,
            spectrumLowArgb = specLow,
            spectrumMidArgb = specMid,
            spectrumHighArgb = specHigh,
            primaryOklch = primaryOklch,
            secondaryOklch = secondaryOklch,
            canvasOklch = canvasOklch,
        )
    }

    private fun deriveFallbackSecondaryOklch(primary: Oklch): Oklch {
        // True single-color cover fallback: graceful analogous lift cleanly separated across OKLCH & HSL.
        val shiftedHue = OklchColorMath.normalizeHueDeg(primary.hDeg + 48f)
        return Oklch(
            l = (primary.l + 0.05f).coerceIn(0.50f, 0.80f),
            c = (primary.c * 0.85f).coerceIn(0.06f, 0.15f),
            hDeg = shiftedHue,
        )
    }

    private fun buildSpectrumTriad(primary: ScoredCluster, secondary: ScoredCluster?, tertiary: ScoredCluster?): Triple<Int, Int, Int> {
        val midHue = primary.oklch.hDeg
        val lowHue: Float
        val highHue: Float
        when {
            secondary != null && tertiary != null -> {
                lowHue = tertiary.oklch.hDeg
                highHue = secondary.oklch.hDeg
            }
            secondary != null -> {
                highHue = ensureHueSeparation(secondary.oklch.hDeg, midHue, 48f)
                var derivedLow = OklchColorMath.normalizeHueDeg(midHue + 180f)
                if (OklchColorMath.circularHueDistanceDeg(derivedLow, highHue) < 48f) {
                    derivedLow = OklchColorMath.normalizeHueDeg(highHue + 68f)
                }
                lowHue = derivedLow
            }
            else -> {
                // Single-color cover: mid stays true to primaryAccent, high lifts +58 deg, low anchors +168 deg.
                lowHue = OklchColorMath.normalizeHueDeg(midHue + 168f)
                highHue = OklchColorMath.normalizeHueDeg(midHue + 58f)
            }
        }

        val low = toOledSpectrumColor(lowHue, tertiary?.oklch?.c ?: primary.oklch.c, targetLightness = 0.69f)
        val mid = toOledSpectrumColor(midHue, primary.oklch.c, targetLightness = 0.72f)
        val high = toOledSpectrumColor(highHue, secondary?.oklch?.c ?: primary.oklch.c, targetLightness = 0.75f)
        return Triple(low, mid, high)
    }

    private fun ensureHueSeparation(candidateHue: Float, anchorHue: Float, minSepDeg: Float): Float {
        if (OklchColorMath.circularHueDistanceDeg(candidateHue, anchorHue) >= minSepDeg) return candidateHue
        return OklchColorMath.normalizeHueDeg(anchorHue + minSepDeg + 10f)
    }

    private fun toOledSpectrumColor(hueDeg: Float, sourceChroma: Float, targetLightness: Float): Int {
        val hue = OklchColorMath.normalizeHueDeg(hueDeg)
        val effectiveL = when {
            hue in 68f..118f -> targetLightness.coerceIn(0.72f, 0.78f)
            hue in 125f..195f -> max(targetLightness, 0.74f).coerceAtMost(0.78f)
            hue in 240f..310f -> targetLightness.coerceIn(0.67f, 0.73f)
            else -> targetLightness.coerceIn(0.68f, 0.75f)
        }
        val maxC = OklchColorMath.maxInGamutChroma(effectiveL, hue)
        val desiredC = max(sourceChroma, 0.14f).coerceAtMost(0.22f)
        // Stay in vibrant, crisp OLED chroma band (68%..86% of sRGB gamut at target L).
        val effectiveC = desiredC.coerceIn(maxC * 0.68f, maxC * 0.86f)
        return OklchColorMath.oklchToSrgbArgb(effectiveL, effectiveC, hue)
    }

    private fun okLabDistance(a: OkLab, b: OkLab): Float {
        val dl = a.l - b.l
        val da = a.a - b.a
        val db = a.b - b.b
        return kotlin.math.sqrt(dl * dl + da * da + db * db)
    }
}
