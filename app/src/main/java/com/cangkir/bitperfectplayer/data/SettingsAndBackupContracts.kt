package com.cangkir.bitperfectplayer.data

import android.net.Uri
import com.cangkir.bitperfectplayer.library.GroupSortScope
import com.cangkir.bitperfectplayer.library.GroupSortSpec
import com.cangkir.bitperfectplayer.library.TrackSortScope
import com.cangkir.bitperfectplayer.library.TrackSortSpec
import kotlinx.coroutines.flow.Flow

interface UserPreferencesStore {
    val preferencesFlow: Flow<UserPreferences>

    suspend fun getSnapshot(): UserPreferences

    suspend fun setTrackSort(scope: TrackSortScope, spec: TrackSortSpec)

    suspend fun setGroupSort(scope: GroupSortScope, spec: GroupSortSpec)

    suspend fun setNowPlayingArtworkStyle(style: NowPlayingArtworkStyle)

    suspend fun setSpectrumVisualizerEnabled(enabled: Boolean)

    suspend fun setSpectrumColorStyle(style: SpectrumColorStyle)

    suspend fun setSpectrumSparksEnabled(enabled: Boolean)

    suspend fun setDefaultHomepage(homepage: DefaultHomepage)

    suspend fun setDynamicCoverThemeEnabled(enabled: Boolean)

    suspend fun setDiagnosticLoggingEnabled(enabled: Boolean)
}

interface LocalBackupStore {
    suspend fun exportBackupToUri(destinationUri: Uri): Result<LocalBackupExportSummary>

    suspend fun importBackupFromUri(sourceUri: Uri): Result<LocalBackupRestoreSummary>
}
