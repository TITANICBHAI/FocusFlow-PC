package com.focusflow.enforcement

import com.focusflow.AppPaths
import java.io.File
import java.time.LocalDateTime

/**
 * EnforcementLog
 *
 * Lightweight append-only log for enforcement-layer warnings and soft failures.
 * Writes to the app data directory — separate from crash.log so the two
 * are easy to distinguish (crash.log = fatal / DB, enforcement.log = Win32 soft fails).
 *
 * Rules:
 *  - Never throws. Every write is wrapped in runCatching.
 *  - Never blocks the caller for more than a filesystem append.
 *  - Only called inside existing catch blocks — control flow is never changed.
 */
internal object EnforcementLog {

    private val logFile: File by lazy {
        AppPaths.logDir.also { it.mkdirs() }
            .let { File(it, "enforcement.log") }
    }

    // Maximum file size before rotation. On rotation the oldest half is discarded
    // so recent enforcement history is preserved while preventing unbounded growth.
    // A flapping enforcement condition (blocked app constantly switching foreground)
    // can write hundreds of lines per minute — without a cap this fills the disk.
    private const val MAX_LOG_BYTES = 512_000L   // 512 KB ceiling
    private const val TRIM_TO_BYTES = 256_000    // keep newest ~256 KB after rotation

    private fun appendLine(line: String) {
        runCatching {
            // Rotate if needed before appending so we never exceed the ceiling.
            if (logFile.length() > MAX_LOG_BYTES) {
                val content = logFile.readText()
                // Find the first newline past the midpoint so we keep whole lines only.
                val cutAt = content.indexOf('\n', (content.length - TRIM_TO_BYTES).coerceAtLeast(0))
                logFile.writeText(if (cutAt > 0) content.substring(cutAt + 1) else "")
            }
            logFile.appendText(line)
        }
    }

    fun warn(tag: String, message: String, cause: Throwable? = null) {
        val ts = LocalDateTime.now()
        val causeStr = cause?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        appendLine("[$ts] WARN  [$tag] $message$causeStr\n")
    }

    fun info(tag: String, message: String) {
        val ts = LocalDateTime.now()
        appendLine("[$ts] INFO  [$tag] $message\n")
    }
}
