package com.cangkir.bitperfectplayer.library

import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.data.CachedTrackSourceKind

/**
 * Lightweight descriptor returned by a fast MediaStore or SAF directory listing pass before
 * expensive per-file metadata/header parsing runs.
 */
data class DiscoveredAudioSignature(
    val uriString: String,
    val fileSizeBytes: Long,
    val dateModifiedSeconds: Long,
    val sourceKind: CachedTrackSourceKind,
)

/**
 * Pure-Kotlin cached record view used by [LibrarySyncDiffer].
 */
data class CachedTrackSignature(
    val track: PlaybackTrack,
    val sourceKind: CachedTrackSourceKind,
)

/**
 * Incremental synchronization plan distinguishing tracks that can be reused directly from Room
 * cache vs. newly added/modified files requiring metadata parsing vs. deleted URIs to prune.
 */
data class IncrementalSyncPlan(
    val unchangedUris: Set<String>,
    val toParseUris: Set<String>,
    val toPruneUris: List<String>,
)

/**
 * Pure-Kotlin incremental library diffing engine (zero `android.*` imports).
 *
 * Compares `uriString` + `fileSizeBytes` + `dateModifiedSeconds` against the persisted Room cache:
 * - Unchanged files skip metadata/header parsing completely (zero redundant disk I/O).
 * - Only newly added or modified files are parsed and upserted.
 * - Deleted files are pruned strictly within the source kinds that were successfully scanned
 *   (so e.g. missing MediaStore permission never wipes cached SAF tracks or vice versa).
 */
object LibrarySyncDiffer {

    fun computePlan(
        cached: List<CachedTrackSignature>,
        discovered: List<DiscoveredAudioSignature>,
        scannedSourceKinds: Set<CachedTrackSourceKind>,
    ): IncrementalSyncPlan {
        val cachedByUri = cached.associateBy { it.track.uriString }
        val discoveredUris = HashSet<String>(discovered.size)

        val unchanged = LinkedHashSet<String>()
        val toParse = LinkedHashSet<String>()

        for (item in discovered) {
            discoveredUris.add(item.uriString)
            val existing = cachedByUri[item.uriString]
            if (existing != null && isUnchanged(existing.track, item)) {
                unchanged.add(item.uriString)
            } else {
                toParse.add(item.uriString)
            }
        }

        val toPrune = cached
            .filter { entry ->
                entry.sourceKind in scannedSourceKinds && entry.track.uriString !in discoveredUris
            }
            .map { it.track.uriString }

        return IncrementalSyncPlan(
            unchangedUris = unchanged,
            toParseUris = toParse,
            toPruneUris = toPrune,
        )
    }

    private fun isUnchanged(cached: PlaybackTrack, discovered: DiscoveredAudioSignature): Boolean {
        if (discovered.fileSizeBytes <= 0L) return false
        if (cached.fileSizeBytes != discovered.fileSizeBytes) return false
        if (cached.dateModifiedSeconds != discovered.dateModifiedSeconds) return false
        // If a lossless track was previously cached before sampleRateHz was parsed, parse it once.
        val isLossless = cached.container.equals("FLAC", ignoreCase = true) ||
            cached.container.equals("WAV", ignoreCase = true)
        if (isLossless && cached.sampleRateHz <= 0) return false
        return true
    }
}
