package com.cangkir.bitperfectplayer.library

import com.cangkir.bitperfectplayer.audio.PlaybackTrack

/**
 * Sort direction for library tracks and groups.
 */
enum class SortDirection(val arrow: String, val label: String) {
    ASC("↑", "Ascending"),
    DESC("↓", "Descending"),
    ;

    fun toggled(): SortDirection = if (this == ASC) DESC else ASC

    companion object {
        fun fromStoredName(raw: String?, fallback: SortDirection): SortDirection =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: fallback
    }
}

/**
 * Track sort criteria supporting context-aware ordering across library views and playlist details.
 */
enum class TrackSortOption(val shortLabel: String, val fullLabel: String, val defaultDirection: SortDirection) {
    CUSTOM_ORDER("Custom", "Custom Order", SortDirection.ASC),
    RECENTLY_PLAYED("Recent", "Recently Played", SortDirection.DESC),
    RECENTLY_ADDED("Added", "Recently Added", SortDirection.DESC),
    TRACK_NUMBER("Track #", "Track Number", SortDirection.ASC),
    TITLE("Title", "Title", SortDirection.ASC),
    ARTIST("Artist", "Artist", SortDirection.ASC),
    ALBUM("Album", "Album", SortDirection.ASC),
    DURATION("Duration", "Duration", SortDirection.ASC),
    MOST_PLAYED("Most Played", "Most Played", SortDirection.DESC),
    SAMPLE_RATE("Hi-Res", "Sample Rate (Hi-Res)", SortDirection.DESC),
    DATE_MODIFIED("Date", "Date Added / Modified", SortDirection.DESC),
    FILENAME("Filename", "Filename", SortDirection.ASC),
    ;

    companion object {
        fun fromStoredName(raw: String?, fallback: TrackSortOption): TrackSortOption =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: fallback
    }
}

/**
 * Group list sort criteria for Folders, Artists, and Albums tabs.
 */
enum class GroupSortOption(val shortLabel: String, val fullLabel: String, val defaultDirection: SortDirection) {
    NAME("Name", "Name", SortDirection.ASC),
    TRACK_COUNT("Tracks", "Track Count", SortDirection.DESC),
    ;

    companion object {
        fun fromStoredName(raw: String?, fallback: GroupSortOption): GroupSortOption =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: fallback
    }
}

/**
 * Independent persisted track sort specification.
 */
data class TrackSortSpec(val option: TrackSortOption, val direction: SortDirection = option.defaultDirection) {
    val compactLabel: String
        get() = "${option.shortLabel} ${direction.arrow}"

    fun serialize(): String = "${option.name}:${direction.name}"

    companion object {
        fun deserialize(raw: String?, allowedOptions: List<TrackSortOption>, fallback: TrackSortSpec): TrackSortSpec {
            if (raw.isNullOrBlank()) return fallback
            val parts = raw.trim().split(':')
            val opt = TrackSortOption.fromStoredName(parts.getOrNull(0), fallback.option)
            if (opt !in allowedOptions) return fallback
            val dir = SortDirection.fromStoredName(parts.getOrNull(1), opt.defaultDirection)
            return TrackSortSpec(option = opt, direction = dir)
        }
    }
}

/**
 * Independent persisted group sort specification.
 */
data class GroupSortSpec(val option: GroupSortOption, val direction: SortDirection = option.defaultDirection) {
    val compactLabel: String
        get() = "${option.shortLabel} ${direction.arrow}"

    fun serialize(): String = "${option.name}:${direction.name}"

    companion object {
        fun deserialize(raw: String?, fallback: GroupSortSpec): GroupSortSpec {
            if (raw.isNullOrBlank()) return fallback
            val parts = raw.trim().split(':')
            val opt = GroupSortOption.fromStoredName(parts.getOrNull(0), fallback.option)
            val dir = SortDirection.fromStoredName(parts.getOrNull(1), opt.defaultDirection)
            return GroupSortSpec(option = opt, direction = dir)
        }
    }
}

/**
 * Context scopes whose sort preferences are stored independently so changing All Songs sort
 * never disturbs Album track-number order or Artist album-grouped order.
 */
enum class TrackSortScope(
    val preferenceKey: String,
    val sheetTitle: String,
    val defaultSpec: TrackSortSpec,
    val availableOptions: List<TrackSortOption>,
) {
    ALL_SONGS(
        preferenceKey = "sort_track_all_songs",
        sheetTitle = "Sort All Songs",
        defaultSpec = TrackSortSpec(TrackSortOption.TITLE, SortDirection.ASC),
        availableOptions = listOf(
            TrackSortOption.TITLE,
            TrackSortOption.ARTIST,
            TrackSortOption.ALBUM,
            TrackSortOption.TRACK_NUMBER,
            TrackSortOption.DURATION,
            TrackSortOption.MOST_PLAYED,
            TrackSortOption.SAMPLE_RATE,
            TrackSortOption.DATE_MODIFIED,
        ),
    ),
    ALBUM_DETAIL(
        preferenceKey = "sort_track_album_detail",
        sheetTitle = "Sort Album Tracks",
        defaultSpec = TrackSortSpec(TrackSortOption.TRACK_NUMBER, SortDirection.ASC),
        availableOptions = listOf(
            TrackSortOption.TRACK_NUMBER,
            TrackSortOption.TITLE,
            TrackSortOption.DURATION,
            TrackSortOption.MOST_PLAYED,
            TrackSortOption.SAMPLE_RATE,
            TrackSortOption.FILENAME,
        ),
    ),
    ARTIST_DETAIL(
        preferenceKey = "sort_track_artist_detail",
        sheetTitle = "Sort Artist Tracks",
        defaultSpec = TrackSortSpec(TrackSortOption.ALBUM, SortDirection.ASC),
        availableOptions = listOf(
            TrackSortOption.ALBUM,
            TrackSortOption.TRACK_NUMBER,
            TrackSortOption.TITLE,
            TrackSortOption.DURATION,
            TrackSortOption.MOST_PLAYED,
            TrackSortOption.SAMPLE_RATE,
        ),
    ),
    FOLDER_DETAIL(
        preferenceKey = "sort_track_folder_detail",
        sheetTitle = "Sort Folder Tracks",
        defaultSpec = TrackSortSpec(TrackSortOption.TRACK_NUMBER, SortDirection.ASC),
        availableOptions = listOf(
            TrackSortOption.TRACK_NUMBER,
            TrackSortOption.FILENAME,
            TrackSortOption.TITLE,
            TrackSortOption.ARTIST,
            TrackSortOption.DURATION,
            TrackSortOption.SAMPLE_RATE,
            TrackSortOption.DATE_MODIFIED,
        ),
    ),
    RECENT(
        preferenceKey = "sort_track_recent",
        sheetTitle = "Sort Recently Played",
        defaultSpec = TrackSortSpec(TrackSortOption.RECENTLY_PLAYED, SortDirection.DESC),
        availableOptions = listOf(
            TrackSortOption.RECENTLY_PLAYED,
            TrackSortOption.TITLE,
            TrackSortOption.ARTIST,
            TrackSortOption.ALBUM,
            TrackSortOption.MOST_PLAYED,
            TrackSortOption.SAMPLE_RATE,
        ),
    ),
    FAVORITES(
        preferenceKey = "sort_track_favorites",
        sheetTitle = "Sort Favorites",
        defaultSpec = TrackSortSpec(TrackSortOption.RECENTLY_ADDED, SortDirection.DESC),
        availableOptions = listOf(
            TrackSortOption.RECENTLY_ADDED,
            TrackSortOption.TITLE,
            TrackSortOption.ARTIST,
            TrackSortOption.ALBUM,
            TrackSortOption.TRACK_NUMBER,
            TrackSortOption.MOST_PLAYED,
            TrackSortOption.SAMPLE_RATE,
        ),
    ),
    PLAYLIST_DETAIL(
        preferenceKey = "sort_track_playlist_detail",
        sheetTitle = "Sort Playlist Tracks",
        defaultSpec = TrackSortSpec(TrackSortOption.CUSTOM_ORDER, SortDirection.ASC),
        availableOptions = listOf(
            TrackSortOption.CUSTOM_ORDER,
            TrackSortOption.TITLE,
            TrackSortOption.ARTIST,
            TrackSortOption.ALBUM,
            TrackSortOption.DURATION,
            TrackSortOption.MOST_PLAYED,
            TrackSortOption.SAMPLE_RATE,
        ),
    ),
}

enum class GroupSortScope(val preferenceKey: String, val sheetTitle: String, val defaultSpec: GroupSortSpec) {
    FOLDERS_LIST(
        preferenceKey = "sort_group_folders",
        sheetTitle = "Sort Folders",
        defaultSpec = GroupSortSpec(GroupSortOption.NAME, SortDirection.ASC),
    ),
    ARTISTS_LIST(
        preferenceKey = "sort_group_artists",
        sheetTitle = "Sort Artists",
        defaultSpec = GroupSortSpec(GroupSortOption.NAME, SortDirection.ASC),
    ),
    ALBUMS_LIST(
        preferenceKey = "sort_group_albums",
        sheetTitle = "Sort Albums",
        defaultSpec = GroupSortSpec(GroupSortOption.NAME, SortDirection.ASC),
    ),
}

/**
 * Snapshot of all independent per-context sort preferences.
 */
data class LibrarySortPreferences(
    val allSongs: TrackSortSpec = TrackSortScope.ALL_SONGS.defaultSpec,
    val albumDetail: TrackSortSpec = TrackSortScope.ALBUM_DETAIL.defaultSpec,
    val artistDetail: TrackSortSpec = TrackSortScope.ARTIST_DETAIL.defaultSpec,
    val folderDetail: TrackSortSpec = TrackSortScope.FOLDER_DETAIL.defaultSpec,
    val recent: TrackSortSpec = TrackSortScope.RECENT.defaultSpec,
    val favorites: TrackSortSpec = TrackSortScope.FAVORITES.defaultSpec,
    val playlistDetail: TrackSortSpec = TrackSortScope.PLAYLIST_DETAIL.defaultSpec,
    val foldersList: GroupSortSpec = GroupSortScope.FOLDERS_LIST.defaultSpec,
    val artistsList: GroupSortSpec = GroupSortScope.ARTISTS_LIST.defaultSpec,
    val albumsList: GroupSortSpec = GroupSortScope.ALBUMS_LIST.defaultSpec,
) {
    fun specFor(scope: TrackSortScope): TrackSortSpec = when (scope) {
        TrackSortScope.ALL_SONGS -> allSongs
        TrackSortScope.ALBUM_DETAIL -> albumDetail
        TrackSortScope.ARTIST_DETAIL -> artistDetail
        TrackSortScope.FOLDER_DETAIL -> folderDetail
        TrackSortScope.RECENT -> recent
        TrackSortScope.FAVORITES -> favorites
        TrackSortScope.PLAYLIST_DETAIL -> playlistDetail
    }

    fun specFor(scope: GroupSortScope): GroupSortSpec = when (scope) {
        GroupSortScope.FOLDERS_LIST -> foldersList
        GroupSortScope.ARTISTS_LIST -> artistsList
        GroupSortScope.ALBUMS_LIST -> albumsList
    }
}

/**
 * Pure-Kotlin deterministic sorting engine for tracks and library groups (zero `android.*` imports).
 *
 * Designed to execute strictly on background dispatchers (`Dispatchers.Default`) inside repositories
 * and ViewModels so Compose `LazyColumn` rendering performs zero sorting work.
 */
object LibrarySortPolicy {

    /**
     * Sorts [tracks] according to [spec] using optional [playCountsByKey] (keyed by lowercase
     * content fingerprint and/or URI string) for [TrackSortOption.MOST_PLAYED].
     */
    fun sortTracks(tracks: List<PlaybackTrack>, spec: TrackSortSpec, playCountsByKey: Map<String, Int> = emptyMap()): List<PlaybackTrack> {
        if (tracks.size <= 1) return tracks

        return when (spec.option) {
            TrackSortOption.CUSTOM_ORDER -> {
                if (spec.direction == SortDirection.ASC) tracks else tracks.asReversed()
            }
            TrackSortOption.RECENTLY_PLAYED,
            TrackSortOption.RECENTLY_ADDED,
            -> {
                // Incoming lists are already ordered newest-first (DESC).
                if (spec.direction == SortDirection.DESC) tracks else tracks.asReversed()
            }
            else -> {
                val baseComparator = trackComparator(spec.option, playCountsByKey)
                val effectiveComparator = if (spec.direction == SortDirection.ASC) {
                    baseComparator
                } else {
                    baseComparator.reversed()
                }
                tracks.sortedWith(effectiveComparator)
            }
        }
    }

    /**
     * Sorts [groups] according to [spec] (`NAME` or `TRACK_COUNT`, `ASC` or `DESC`).
     */
    fun sortGroups(groups: List<LibraryGroup>, spec: GroupSortSpec): List<LibraryGroup> {
        if (groups.size <= 1) return groups
        val baseComparator: Comparator<LibraryGroup> = when (spec.option) {
            GroupSortOption.NAME -> Comparator { a, b ->
                compareNaturalIgnoreCase(a.name, b.name)
            }
            GroupSortOption.TRACK_COUNT -> Comparator { a, b ->
                val byCount = a.tracks.size.compareTo(b.tracks.size)
                if (byCount != 0) byCount else compareNaturalIgnoreCase(a.name, b.name)
            }
        }
        val effective = if (spec.direction == SortDirection.ASC) {
            baseComparator
        } else {
            when (spec.option) {
                GroupSortOption.NAME -> baseComparator.reversed()
                // For descending track count, break ties alphabetically A->Z for clean reading.
                GroupSortOption.TRACK_COUNT -> Comparator { a, b ->
                    val byCountDesc = b.tracks.size.compareTo(a.tracks.size)
                    if (byCountDesc != 0) byCountDesc else compareNaturalIgnoreCase(a.name, b.name)
                }
            }
        }
        return groups.sortedWith(effective)
    }

    private fun trackComparator(option: TrackSortOption, playCountsByKey: Map<String, Int>): Comparator<PlaybackTrack> = when (option) {
        TrackSortOption.TRACK_NUMBER -> TRACK_NUMBER_COMPARATOR
        TrackSortOption.TITLE -> Comparator { a, b ->
            val cmp = compareNaturalIgnoreCase(a.title, b.title)
            if (cmp != 0) cmp else compareNaturalIgnoreCase(a.fileName, b.fileName)
        }
        TrackSortOption.ARTIST -> Comparator { a, b ->
            val byArtist = compareNaturalIgnoreCase(a.artist, b.artist)
            if (byArtist != 0) return@Comparator byArtist
            val byAlbum = compareNaturalIgnoreCase(a.album, b.album)
            if (byAlbum != 0) return@Comparator byAlbum
            TRACK_NUMBER_COMPARATOR.compare(a, b)
        }
        TrackSortOption.ALBUM -> Comparator { a, b ->
            val byAlbum = compareNaturalIgnoreCase(a.album, b.album)
            if (byAlbum != 0) return@Comparator byAlbum
            TRACK_NUMBER_COMPARATOR.compare(a, b)
        }
        TrackSortOption.DURATION -> Comparator { a, b ->
            val byDur = a.durationMs.compareTo(b.durationMs)
            if (byDur != 0) byDur else compareNaturalIgnoreCase(a.title, b.title)
        }
        TrackSortOption.MOST_PLAYED -> Comparator { a, b ->
            val countA = lookupPlayCount(a, playCountsByKey)
            val countB = lookupPlayCount(b, playCountsByKey)
            val byCount = countA.compareTo(countB)
            if (byCount != 0) byCount else compareNaturalIgnoreCase(b.title, a.title)
        }
        TrackSortOption.SAMPLE_RATE -> Comparator { a, b ->
            val byRate = a.sampleRateHz.compareTo(b.sampleRateHz)
            if (byRate != 0) return@Comparator byRate
            val byDepth = (a.bitDepth ?: 0).compareTo(b.bitDepth ?: 0)
            if (byDepth != 0) return@Comparator byDepth
            compareNaturalIgnoreCase(b.title, a.title)
        }
        TrackSortOption.DATE_MODIFIED -> Comparator { a, b ->
            val byDate = a.dateModifiedSeconds.compareTo(b.dateModifiedSeconds)
            if (byDate != 0) byDate else compareNaturalIgnoreCase(b.title, a.title)
        }
        TrackSortOption.FILENAME -> Comparator { a, b ->
            val byFile = compareNaturalIgnoreCase(a.fileName, b.fileName)
            if (byFile != 0) byFile else compareNaturalIgnoreCase(a.title, b.title)
        }
        TrackSortOption.CUSTOM_ORDER,
        TrackSortOption.RECENTLY_PLAYED,
        TrackSortOption.RECENTLY_ADDED,
        -> Comparator { _, _ -> 0 }
    }

    /**
     * Canonical Album / Folder track-number comparator:
     * 1. Tracks with an extracted disc or track number come before untagged (`0`) tracks so
     *    untagged bonus files never jump ahead of Track 1.
     * 2. Disc number ascending (`0`, `1`, `2`, ...)
     * 3. Track number ascending (`1`, `2`, ..., `18`)
     * 4. Natural filename ascending (`"2 - Song.flac"` < `"10 - Song.flac"`)
     * 5. Title ascending as final deterministic tie-breaker.
     */
    val TRACK_NUMBER_COMPARATOR: Comparator<PlaybackTrack> = Comparator { a, b ->
        val aHasNumber = a.trackNumber > 0 || a.discNumber > 0
        val bHasNumber = b.trackNumber > 0 || b.discNumber > 0
        if (aHasNumber != bHasNumber) {
            return@Comparator if (aHasNumber) -1 else 1
        }
        if (aHasNumber) {
            val byDisc = a.discNumber.compareTo(b.discNumber)
            if (byDisc != 0) return@Comparator byDisc
            val aTrackSort = if (a.trackNumber > 0) a.trackNumber else Int.MAX_VALUE
            val bTrackSort = if (b.trackNumber > 0) b.trackNumber else Int.MAX_VALUE
            val byTrack = aTrackSort.compareTo(bTrackSort)
            if (byTrack != 0) return@Comparator byTrack
        }
        val byFile = compareNaturalIgnoreCase(a.fileName, b.fileName)
        if (byFile != 0) return@Comparator byFile
        compareNaturalIgnoreCase(a.title, b.title)
    }

    private fun lookupPlayCount(track: PlaybackTrack, playCountsByKey: Map<String, Int>): Int {
        if (playCountsByKey.isEmpty()) return 0
        track.contentFingerprint?.lowercase()?.let { fp ->
            playCountsByKey[fp]?.let { return it }
        }
        return playCountsByKey[track.uriString] ?: 0
    }

    /**
     * Allocation-light natural case-insensitive string comparator (`"Track 2"` < `"Track 10"`).
     */
    fun compareNaturalIgnoreCase(a: String, b: String): Int {
        var i = 0
        var j = 0
        val lenA = a.length
        val lenB = b.length

        while (i < lenA && j < lenB) {
            val ca = a[i]
            val cb = b[j]

            if (ca.isDigit() && cb.isDigit()) {
                val startA = i
                while (i < lenA && a[i].isDigit()) i++
                val startB = j
                while (j < lenB && b[j].isDigit()) j++

                // Skip leading zeros to compare numeric magnitude without BigInteger allocations
                var nzA = startA
                while (nzA < i && a[nzA] == '0') nzA++
                var nzB = startB
                while (nzB < j && b[nzB] == '0') nzB++

                val sigLenA = i - nzA
                val sigLenB = j - nzB
                if (sigLenA != sigLenB) return sigLenA.compareTo(sigLenB)

                for (k in 0 until sigLenA) {
                    val diff = a[nzA + k].compareTo(b[nzB + k])
                    if (diff != 0) return diff
                }
                // If numerically equal (e.g., "01" vs "1"), prefer shorter or lexicographic tie-break later
            } else {
                val la = ca.lowercaseChar()
                val lb = cb.lowercaseChar()
                if (la != lb) return la.compareTo(lb)
                i++
                j++
            }
        }
        return (lenA - i).compareTo(lenB - j)
    }
}
