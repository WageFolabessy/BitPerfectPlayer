package com.cangkir.bitperfectplayer.lyrics

import android.net.Uri
import com.cangkir.bitperfectplayer.audio.PlaybackTrack

interface LyricsStore {
    suspend fun loadLyricsForTrack(track: PlaybackTrack): TrackLyricsState?

    suspend fun importLrcFromSaf(track: PlaybackTrack, lrcUri: Uri): Result<TrackLyricsState>

    suspend fun saveManualLyrics(track: PlaybackTrack, rawText: String): Result<TrackLyricsState>

    suspend fun deleteLyrics(track: PlaybackTrack): Result<TrackLyricsState>
}
