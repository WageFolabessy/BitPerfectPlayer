package com.cangkir.bitperfectplayer.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LocalBackupExportSummary(
    val playlistsCount: Int,
    val lyricsCount: Int,
    val favoritesCount: Int,
    val statsCount: Int,
)

/**
 * 100% offline SAF `.json` local backup exporter and non-destructive importer backed by
 * [LocalPersistenceRepository] and pure-Kotlin [LocalBackupCodec].
 */
class LocalBackupRepository(
    context: Context,
    private val persistenceRepository: LocalPersistenceRepository,
) {
    private val appContext = context.applicationContext

    suspend fun exportBackupToUri(destinationUri: Uri): Result<LocalBackupExportSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = persistenceRepository.exportLocalBackupPayload()
                val json = LocalBackupCodec.encode(payload)
                val out = appContext.contentResolver.openOutputStream(destinationUri, "wt")
                    ?: throw IllegalStateException("Unable to open destination file for backup")
                out.use { stream ->
                    OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                        writer.write(json)
                        writer.flush()
                    }
                }
                LocalBackupExportSummary(
                    playlistsCount = payload.playlists.size,
                    lyricsCount = payload.lyrics.size,
                    favoritesCount = payload.favorites.size,
                    statsCount = payload.trackStats.size,
                )
            }
        }

    suspend fun importBackupFromUri(sourceUri: Uri): Result<LocalBackupRestoreSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                val rawJson = appContext.contentResolver.openInputStream(sourceUri)?.use { input ->
                    val buffer = ByteArray(8192)
                    val out = ByteArrayOutputStream()
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BACKUP_FILE_BYTES) {
                            throw IllegalArgumentException("Backup file exceeds 16 MB safety limit")
                        }
                        out.write(buffer, 0, read)
                    }
                    out.toString(Charsets.UTF_8.name())
                } ?: throw IllegalStateException("Unable to read selected backup file")

                val payload = LocalBackupCodec.decode(rawJson).getOrThrow()
                persistenceRepository.importLocalBackupPayload(payload)
            }
        }

    companion object {
        private const val MAX_BACKUP_FILE_BYTES = 16 * 1024 * 1024 // 16 MB OOM safety cap

        fun suggestBackupFileName(nowEpochMs: Long = System.currentTimeMillis()): String {
            val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(nowEpochMs))
            return "bitperfect_backup_$stamp.json"
        }
    }
}
