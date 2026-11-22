package com.cangkir.bitperfectplayer.diagnostics

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Chained [Thread.UncaughtExceptionHandler] capturing bounded crash stacktraces ONLY when the
 * user has explicitly opted in to [LocalDiagnosticLogger].
 *
 * Guarantees:
 * - Zero disk I/O when [LocalDiagnosticLogger.isEnabled] is `false`.
 * - Always delegates to the previous default handler so Android crash reporting/termination
 *   never hangs or gets masked.
 */
class DiagnosticCrashHandler internal constructor(
    private val logger: LocalDiagnosticLogger,
    private val previousHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        if (logger.isEnabled) {
            runCatching {
                logger.writeCrashSync(threadName = thread.name, throwable = throwable)
            }
        }
        previousHandler?.uncaughtException(thread, throwable)
    }

    companion object {
        private val installed = AtomicBoolean(false)

        fun install(logger: LocalDiagnosticLogger) {
            if (!installed.compareAndSet(false, true)) return
            val existing = Thread.getDefaultUncaughtExceptionHandler()
            if (existing is DiagnosticCrashHandler) return
            Thread.setDefaultUncaughtExceptionHandler(DiagnosticCrashHandler(logger, existing))
        }
    }
}
