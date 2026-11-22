package com.cangkir.bitperfectplayer.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppVersionSnapshot(val versionName: String, val versionCode: Long, val isDebugBuild: Boolean)

/**
 * Opt-in, 100% offline, non-blocking local diagnostic logger storing structured `.txt` logs across
 * four bounded/rotated files inside app-private `filesDir/diagnostic_logs/`.
 *
 * Guarantees:
 * - Default **OFF** (`isEnabled = false`). When disabled, inline callers return immediately on a
 *   single `@Volatile` read with zero lambda/map/string allocations and zero disk I/O.
 * - Producers enqueue into a bounded drop-oldest [DiagnosticLogBuffer] and never block UI or
 *   `BitPerfectAudioThread` on disk I/O.
 * - A single low-priority background thread (`BitPerfectDiagWriter`) is lazily spawned only once
 *   logging is enabled, flushing every ~3 seconds or on explicit flush/pause/export.
 */
class LocalDiagnosticLogger(context: Context) {
    private val appContext = context.applicationContext

    val logDir: File = File(appContext.filesDir, LOG_DIR_NAME)
    val exportCacheDir: File = File(appContext.cacheDir, EXPORT_CACHE_DIR_NAME)

    val appVersion: AppVersionSnapshot = resolveAppVersionSnapshot(appContext)

    @Volatile
    var isEnabled: Boolean = false
        private set

    private val buffer = DiagnosticLogBuffer(DiagnosticLogBuffer.DEFAULT_CAPACITY)
    private val fileWriteLock = ReentrantLock()
    private val signalLock = ReentrantLock()
    private val signalCondition = signalLock.newCondition()
    private val writerStarted = AtomicBoolean(false)
    private val shutdownRequested = AtomicBoolean(false)

    @Volatile
    private var flushRequested: Boolean = false

    private val _storageStats = MutableStateFlow(DiagnosticLogStats())
    val storageStats: StateFlow<DiagnosticLogStats> = _storageStats.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        val previous = isEnabled
        isEnabled = enabled
        if (enabled) {
            ensureWriterThreadStarted()
            if (!previous) {
                enqueueEntry(
                    level = DiagnosticLogLevel.INFO,
                    channel = DiagnosticLogChannel.AUDIO_PIPELINE,
                    event = "DIAGNOSTIC_LOGGING_ENABLED",
                    fields = linkedMapOf(
                        "appVersion" to appVersion.versionName,
                        "versionCode" to appVersion.versionCode,
                        "api" to Build.VERSION.SDK_INT,
                    ),
                )
            }
            requestFlush()
        } else {
            buffer.clear()
        }
    }

    inline fun log(
        level: DiagnosticLogLevel,
        channel: DiagnosticLogChannel,
        event: String,
        buildFields: () -> Map<String, Any?> = { emptyMap() },
    ) {
        if (!isEnabled) return
        enqueueEntry(level, channel, event, buildFields())
    }

    inline fun logAudio(
        event: String,
        level: DiagnosticLogLevel = DiagnosticLogLevel.INFO,
        buildFields: () -> Map<String, Any?> = { emptyMap() },
    ) {
        if (!isEnabled) return
        enqueueEntry(level, DiagnosticLogChannel.AUDIO_PIPELINE, event, buildFields())
    }

    inline fun logPerf(
        event: String,
        level: DiagnosticLogLevel = DiagnosticLogLevel.INFO,
        buildFields: () -> Map<String, Any?> = { emptyMap() },
    ) {
        if (!isEnabled) return
        enqueueEntry(level, DiagnosticLogChannel.PERFORMANCE, event, buildFields())
    }

    inline fun logError(
        event: String,
        level: DiagnosticLogLevel = DiagnosticLogLevel.ERROR,
        buildFields: () -> Map<String, Any?> = { emptyMap() },
    ) {
        if (!isEnabled) return
        enqueueEntry(level, DiagnosticLogChannel.ERRORS_AND_CRASHES, event, buildFields())
    }

    @PublishedApi
    internal fun enqueueEntry(level: DiagnosticLogLevel, channel: DiagnosticLogChannel, event: String, fields: Map<String, Any?>) {
        if (!isEnabled) return
        val formatted = DiagnosticLogFormatter.formatEntry(
            epochMs = System.currentTimeMillis(),
            level = level,
            channel = channel,
            event = event,
            fields = fields,
        )
        buffer.offer(QueuedLogRecord(channel = channel, payload = formatted))
        if (level == DiagnosticLogLevel.ERROR || level == DiagnosticLogLevel.CRASH) {
            requestFlush()
        }
    }

    fun writeDeviceSummary(fields: Map<String, Any?>) {
        if (!isEnabled) return
        val payload = DiagnosticLogFormatter.formatDeviceSummary(
            epochMs = System.currentTimeMillis(),
            fields = buildBaseDeviceFields() + fields,
        )
        buffer.offer(QueuedLogRecord(channel = DiagnosticLogChannel.DEVICE_SUMMARY, payload = payload))
        requestFlush()
    }

    fun writeDeviceSummarySync(fields: Map<String, Any?>) {
        if (!isEnabled) return
        val payload = DiagnosticLogFormatter.formatDeviceSummary(
            epochMs = System.currentTimeMillis(),
            fields = buildBaseDeviceFields() + fields,
        )
        fileWriteLock.withLock {
            runCatching {
                DiagnosticLogRotationPolicy.writeChannelPayload(
                    logDir = logDir,
                    channel = DiagnosticLogChannel.DEVICE_SUMMARY,
                    payload = payload,
                )
            }
            _storageStats.value = DiagnosticLogRotationPolicy.computeStorageStats(logDir)
        }
    }

    fun requestFlush() {
        if (!writerStarted.get()) return
        signalLock.withLock {
            flushRequested = true
            signalCondition.signalAll()
        }
    }

    /**
     * Synchronously drains all buffered records to disk. Safe to call off the main thread before
     * exporting/sharing logs or during crash capture.
     */
    fun flushSync() {
        val batch = ArrayList<QueuedLogRecord>(64)
        val dropped = buffer.drainInto(batch)
        if (batch.isEmpty() && dropped <= 0L) {
            refreshStorageStatsSync()
            return
        }
        writeBatchLocked(batch, dropped)
    }

    /**
     * Synchronously records an uncaught exception stacktrace ONLY when [isEnabled] is `true`,
     * guarded by a bounded [CRASH_LOCK_TIMEOUT_MS] lock wait so the platform crash handler is
     * guaranteed never to deadlock or hang even if another thread stalled during disk I/O.
     */
    fun writeCrashSync(threadName: String, throwable: Throwable) {
        if (!isEnabled) return
        runCatching {
            val traceWriter = StringWriter(4096)
            PrintWriter(traceWriter).use { pw ->
                throwable.printStackTrace(pw)
            }
            val crashBlock = DiagnosticLogFormatter.formatCrashBlock(
                epochMs = System.currentTimeMillis(),
                appVersion = appVersion.versionName,
                versionCode = appVersion.versionCode,
                apiLevel = Build.VERSION.SDK_INT,
                threadName = threadName,
                stackTrace = traceWriter.toString(),
            )
            val pending = ArrayList<QueuedLogRecord>(64)
            val dropped = buffer.drainInto(pending)
            pending.add(QueuedLogRecord(DiagnosticLogChannel.ERRORS_AND_CRASHES, crashBlock))
            val acquired = runCatching {
                fileWriteLock.tryLock(CRASH_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
            if (!acquired) return
            try {
                writeBatchHoldingLock(pending, dropped)
            } finally {
                fileWriteLock.unlock()
            }
        }
    }

    fun refreshStorageStatsSync(): DiagnosticLogStats = fileWriteLock.withLock {
        val stats = DiagnosticLogRotationPolicy.computeStorageStats(logDir)
        _storageStats.value = stats
        stats
    }

    fun getStorageStats(): DiagnosticLogStats = refreshStorageStatsSync()

    fun clearAllLogs(): DiagnosticLogStats {
        buffer.clear()
        return fileWriteLock.withLock {
            runCatching {
                DiagnosticLogRotationPolicy.clearAll(logDir = logDir, exportCacheDir = exportCacheDir)
            }
            val empty = DiagnosticLogStats()
            _storageStats.value = empty
            empty
        }
    }

    fun buildBaseDeviceFields(): Map<String, Any?> = linkedMapOf(
        "appVersion" to appVersion.versionName,
        "versionCode" to appVersion.versionCode,
        "buildType" to if (appVersion.isDebugBuild) "debug" else "release",
        "manufacturer" to Build.MANUFACTURER,
        "model" to Build.MODEL,
        "androidRelease" to Build.VERSION.RELEASE,
        "apiLevel" to Build.VERSION.SDK_INT,
    )

    private fun ensureWriterThreadStarted() {
        if (!writerStarted.compareAndSet(false, true)) return
        val worker = Thread(
            {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                runWriterLoop()
            },
            WRITER_THREAD_NAME,
        ).apply {
            isDaemon = true
        }
        worker.start()
    }

    private fun runWriterLoop() {
        val batch = ArrayList<QueuedLogRecord>(64)
        while (!shutdownRequested.get()) {
            signalLock.withLock {
                if (!flushRequested && buffer.size() == 0) {
                    if (isEnabled) {
                        runCatching { signalCondition.await(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS) }
                    } else {
                        // Zero-CPU park while disabled until explicitly re-enabled or flushed.
                        runCatching { signalCondition.await() }
                    }
                }
                flushRequested = false
            }
            batch.clear()
            val dropped = buffer.drainInto(batch)
            if (batch.isNotEmpty() || dropped > 0L) {
                writeBatchLocked(batch, dropped)
            }
        }
    }

    private fun writeBatchLocked(batch: List<QueuedLogRecord>, droppedCount: Long) {
        fileWriteLock.withLock {
            writeBatchHoldingLock(batch, droppedCount)
        }
    }

    private fun writeBatchHoldingLock(batch: List<QueuedLogRecord>, droppedCount: Long) {
        for (record in batch) {
            runCatching {
                DiagnosticLogRotationPolicy.writeChannelPayload(
                    logDir = logDir,
                    channel = record.channel,
                    payload = record.payload,
                )
            }
        }
        if (droppedCount > 0L) {
            val droppedLine = DiagnosticLogFormatter.formatEntry(
                epochMs = System.currentTimeMillis(),
                level = DiagnosticLogLevel.WARN,
                channel = DiagnosticLogChannel.PERFORMANCE,
                event = "LOG_BUFFER_DROPPED",
                fields = mapOf("droppedOldest" to droppedCount),
            )
            runCatching {
                DiagnosticLogRotationPolicy.writeChannelPayload(
                    logDir = logDir,
                    channel = DiagnosticLogChannel.PERFORMANCE,
                    payload = droppedLine,
                )
            }
        }
        _storageStats.value = DiagnosticLogRotationPolicy.computeStorageStats(logDir)
    }

    companion object {
        const val LOG_DIR_NAME = "diagnostic_logs"
        const val EXPORT_CACHE_DIR_NAME = "diagnostic_exports"
        private const val WRITER_THREAD_NAME = "BitPerfectDiagWriter"
        private const val FLUSH_INTERVAL_MS = 3_000L
        private const val CRASH_LOCK_TIMEOUT_MS = 500L

        private fun resolveAppVersionSnapshot(context: Context): AppVersionSnapshot {
            val isDebug = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            return runCatching {
                val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.PackageInfoFlags.of(0L),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pkgInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pkgInfo.versionCode.toLong()
                }
                AppVersionSnapshot(
                    versionName = pkgInfo.versionName ?: "1.2",
                    versionCode = code,
                    isDebugBuild = isDebug,
                )
            }.getOrDefault(AppVersionSnapshot(versionName = "1.2", versionCode = 3L, isDebugBuild = isDebug))
        }
    }
}
