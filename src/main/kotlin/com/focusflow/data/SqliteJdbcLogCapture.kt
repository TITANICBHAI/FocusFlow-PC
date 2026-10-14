package com.focusflow.data

import java.io.File
import java.util.concurrent.CancellationException
import java.util.logging.FileHandler
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter

/**
 * Captures sqlite-jdbc's JUL diagnostics locally before the native loader runs.
 * The memory copy is bounded and is attached only to local startup diagnostics.
 */
internal object SqliteJdbcLogCapture {
    private const val LOGGER_NAME = "org.sqlite"
    private const val LOG_FILE_PATTERN = "sqlite-jdbc-%g.log"
    private const val MAX_LOG_FILE_BYTES = 256 * 1024
    private const val LOG_FILE_COUNT = 2
    private const val MAX_MEMORY_CHARS = 64 * 1024

    private val logger = Logger.getLogger(LOGGER_NAME)
    @Volatile
    private var memoryHandler: BoundedMemoryHandler? = null

    @Volatile
    private var fileHandler: FileHandler? = null

    @Volatile
    private var installedLogFilePattern: String? = null

    @Synchronized
    fun install() {
        if (memoryHandler == null) {
            val handler = BoundedMemoryHandler()
            try {
                logger.addHandler(handler)
                memoryHandler = handler
            } catch (failure: Throwable) {
                closeQuietly(handler, failure)
                rethrowIfFatalOrCancelled(failure)
            }
        }

        val home = System.getProperty("user.home")?.takeIf(String::isNotBlank) ?: return
        val directory = File(home, ".focusflow")
        val pattern = File(directory, LOG_FILE_PATTERN).absolutePath
        if (installedLogFilePattern == pattern && fileHandler != null) return

        fileHandler?.let { oldHandler ->
            try {
                logger.removeHandler(oldHandler)
                oldHandler.close()
            } catch (failure: Throwable) {
                rethrowIfFatalOrCancelled(failure)
            } finally {
                fileHandler = null
                installedLogFilePattern = null
            }
        }

        var candidate: FileHandler? = null
        try {
            directory.mkdirs()
            candidate = FileHandler(pattern, MAX_LOG_FILE_BYTES, LOG_FILE_COUNT, true).apply {
                level = Level.ALL
                formatter = SimpleFormatter()
            }
            logger.addHandler(candidate)
            fileHandler = candidate
            installedLogFilePattern = pattern
        } catch (failure: Throwable) {
            candidate?.let { closeQuietly(it, failure) }
            rethrowIfFatalOrCancelled(failure)
            // Logging setup is best effort; native loading must still proceed.
        }
    }

    @Synchronized
    fun filePattern(): String? = installedLogFilePattern

    fun snapshot(): String {
        synchronized(this) {
            try {
                fileHandler?.flush()
            } catch (failure: Throwable) {
                rethrowIfFatalOrCancelled(failure)
            }
            return memoryHandler?.snapshot().orEmpty()
        }
    }

    fun clearCapturedOutput() {
        memoryHandler?.clear()
    }

    private class BoundedMemoryHandler : Handler() {
        private val buffer = StringBuilder()

        init {
            level = Level.ALL
        }

        override fun publish(record: LogRecord?) {
            if (record == null || !isLoggable(record)) return
            val rendered = try {
                SimpleFormatter().format(record)
            } catch (failure: Throwable) {
                rethrowIfFatalOrCancelled(failure)
                "${record.level}: ${record.message}\n"
            }
            synchronized(buffer) {
                buffer.append(rendered)
                if (buffer.length > MAX_MEMORY_CHARS) {
                    buffer.delete(0, buffer.length - MAX_MEMORY_CHARS)
                }
            }
        }

        override fun flush() = Unit

        override fun close() = Unit

        fun snapshot(): String = synchronized(buffer) { buffer.toString() }

        fun clear() {
            synchronized(buffer) { buffer.setLength(0) }
        }
    }

    private fun closeQuietly(handler: Handler, originalFailure: Throwable) {
        try {
            handler.close()
        } catch (closeFailure: Throwable) {
            originalFailure.addSuppressed(closeFailure)
            rethrowIfFatalOrCancelled(closeFailure)
        }
    }

    private fun rethrowIfFatalOrCancelled(failure: Throwable) {
        when (failure) {
            is CancellationException -> throw failure
            is VirtualMachineError -> throw failure
        }
    }
}
