package com.focusflow.data

import com.focusflow.enforcement.EnforcementLog
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opt-in, ten-minute JFR capture for contended entries on Database's intrinsic monitor.
 *
 * Disabled unless -Dfocusflow.db.contention.enabled=true is explicitly set. JFR is
 * accessed reflectively so packaged runtimes that omit jdk.jfr do not gain a new
 * runtime-module requirement; an explicitly requested but unavailable capture is logged.
 */
internal object DatabaseMonitorDiagnostics {
    private const val ENABLE_PROPERTY = "focusflow.db.contention.enabled"
    private const val MONITOR_CLASS = "com.focusflow.data.Database"
    private const val EVENT_NAME = "jdk.JavaMonitorEnter"
    private val captureDuration = Duration.ofMinutes(10)
    private val started = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)

    @Volatile
    private var recording: Any? = null

    @Volatile
    private var recordingDirectory: Path? = null

    @Volatile
    private var captureStartedAt: Instant? = null

    @Volatile
    private var captureStartedAtNanos: Long = 0L

    fun startIfEnabled() {
        if (System.getProperty(ENABLE_PROPERTY) != "true") return
        if (!started.compareAndSet(false, true)) return

        try {
            val directory = Files.createTempDirectory("focusflow-f16-contention-")
            recordingDirectory = directory
            val activeRecording = newRecording()
            recording = activeRecording
            captureStartedAt = Instant.now()
            captureStartedAtNanos = System.nanoTime()
            invoke(activeRecording, "start")

            Runtime.getRuntime().addShutdownHook(
                Thread(
                    { finish("application shutdown") },
                    "FocusFlow-F16-contention-shutdown"
                ).apply { isDaemon = true }
            )

            Thread(
                {
                    try {
                        Thread.sleep(captureDuration.toMillis())
                        finish("ten-minute window completed")
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        finish("measurement timer interrupted")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (fatal: VirtualMachineError) {
                        throw fatal
                    } catch (failure: Throwable) {
                        logFailure("measurement timer failed", failure)
                    }
                },
                "FocusFlow-F16-contention-timer"
            ).apply { isDaemon = true }.start()

            EnforcementLog.info(
                "F16",
                "Debug database-monitor measurement started; it will stop after ten minutes."
            )
        } catch (cancelled: CancellationException) {
            cleanupRecordingFiles()
            throw cancelled
        } catch (fatal: VirtualMachineError) {
            cleanupRecordingFiles()
            throw fatal
        } catch (failure: Throwable) {
            cleanupRecordingFiles()
            logFailure("could not start debug database-monitor measurement", failure)
        }
    }

    internal fun summarizeRecording(path: Path): String {
        val totals = linkedMapOf<String, WaitAggregate>()
        var databaseWaitCount = 0
        var databaseWaitNanos = 0L
        var awtWaitCount = 0
        var awtWaitNanos = 0L
        val readerType = Class.forName("jdk.jfr.consumer.RecordingFile")
        val reader = readerType.getConstructor(Path::class.java).newInstance(path)

        try {
            while (invoke(reader, "hasMoreEvents") as Boolean) {
                val event = invoke(reader, "readEvent") ?: continue
                if (eventTypeName(event) != EVENT_NAME) continue

                val monitorClass = eventValue(event, "monitorClass")
                    ?.let { invoke(it, "getName") as? String }
                if (monitorClass != MONITOR_CLASS) continue

                val duration = invoke(event, "getDuration") as? Duration ?: continue
                val waitNanos = duration.toNanos().coerceAtLeast(0L)
                val thread = eventValue(event, "eventThread")
                val threadName = thread?.let { invoke(it, "getJavaName") as? String }.orEmpty()
                val isAwtEventThread = threadName.startsWith("AWT-EventQueue-")
                val frames = eventStackFrames(event)
                val databaseFrameIndex = frames.indexOfFirst { it.className == MONITOR_CLASS }
                val databaseMethod = frames.getOrNull(databaseFrameIndex)?.methodName
                    ?: "<monitor-enter>"
                val callerFrame = if (databaseFrameIndex >= 0) {
                    frames.drop(databaseFrameIndex + 1)
                        .firstOrNull { it.className != MONITOR_CLASS }
                } else {
                    frames.firstOrNull()
                }
                val callsite = callerFrame?.format()
                    ?: frames.firstOrNull()?.format()
                    ?: "<stack trace unavailable>"
                val key = "$callsite -> Database.$databaseMethod"

                databaseWaitCount++
                databaseWaitNanos += waitNanos
                if (isAwtEventThread) {
                    awtWaitCount++
                    awtWaitNanos += waitNanos
                }

                val aggregate = totals.getOrPut(key) { WaitAggregate() }
                aggregate.count++
                aggregate.totalNanos += waitNanos
                aggregate.maxNanos = maxOf(aggregate.maxNanos, waitNanos)
                if (isAwtEventThread) {
                    aggregate.awtCount++
                    aggregate.awtNanos += waitNanos
                }
            }
        } finally {
            invoke(reader, "close")
        }

        return buildString {
            appendLine("F16 database monitor contention")
            appendLine("Monitor: $MONITOR_CLASS")
            appendLine("Captured contended enters: $databaseWaitCount")
            appendLine("Total wait: ${formatMillis(databaseWaitNanos)} ms")
            appendLine("AWT event-thread wait: $awtWaitCount enters, ${formatMillis(awtWaitNanos)} ms")
            appendLine("Top 10 callsites by accumulated monitor wait:")
            if (totals.isEmpty()) {
                appendLine("  No contended Database monitor enters were recorded.")
            } else {
                totals.entries
                    .sortedWith(
                        compareByDescending<Map.Entry<String, WaitAggregate>> { it.value.totalNanos }
                            .thenByDescending { it.value.count }
                            .thenBy { it.key }
                    )
                    .take(10)
                    .forEachIndexed { index, (callsite, aggregate) ->
                        appendLine(
                            "  ${index + 1}. ${aggregate.count} waits, " +
                                "${formatMillis(aggregate.totalNanos)} ms total, " +
                                "${formatMillis(aggregate.maxNanos)} ms max, " +
                                "AWT ${aggregate.awtCount} waits / " +
                                "${formatMillis(aggregate.awtNanos)} ms — $callsite"
                        )
                    }
            }
        }
    }

    private fun newRecording(): Any {
        val recordingType = Class.forName("jdk.jfr.Recording")
        val settingsType = Class.forName("jdk.jfr.EventSettings")
        val activeRecording = recordingType.getConstructor().newInstance()
        invoke(activeRecording, "setName", arrayOf(String::class.java), "FocusFlow F16 database monitor")
        invoke(
            activeRecording,
            "setToDisk",
            arrayOf(Boolean::class.javaPrimitiveType!!),
            true
        )
        val eventSettings = invoke(
            activeRecording,
            "enable",
            arrayOf(String::class.java),
            EVENT_NAME
        ) ?: error("JFR did not return settings for $EVENT_NAME")
        invoke(
            eventSettings,
            "withThreshold",
            arrayOf(Duration::class.java),
            Duration.ZERO
        )
        settingsType.getMethod("withStackTrace").invoke(eventSettings)
        return activeRecording
    }

    private fun finish(reason: String) {
        if (!finished.compareAndSet(false, true)) return
        val activeRecording = recording ?: return
        val directory = recordingDirectory ?: return
        val recordingPath = directory.resolve("database-monitor.jfr")

        try {
            invoke(activeRecording, "stop")
            invoke(activeRecording, "dump", arrayOf(Path::class.java), recordingPath)
            val elapsedMillis = ((System.nanoTime() - captureStartedAtNanos).coerceAtLeast(0L)) / 1_000_000L
            val startedAt = captureStartedAt ?: Instant.now()
            val report = buildString {
                appendLine("F16 debug database-monitor capture")
                appendLine("Started: $startedAt")
                appendLine("Ended: ${Instant.now()}")
                appendLine("Requested duration: ${captureDuration.toMinutes()} minutes")
                appendLine("Actual duration: $elapsedMillis ms")
                appendLine("Stop reason: $reason")
                appendLine("Runtime: ${System.getProperty("java.version").orEmpty()}")
                appendLine("OS: ${System.getProperty("os.name").orEmpty()} ${System.getProperty("os.arch").orEmpty()}")
                appendLine()
                append(summarizeRecording(recordingPath))
            }
            val reportFile = writeReport(report)
            report.lineSequence()
                .filter(String::isNotBlank)
                .forEach { EnforcementLog.info("F16", it) }
            EnforcementLog.info(
                "F16",
                "Debug database-monitor measurement ended after $elapsedMillis ms; report saved under .focusflow."
            )
            println("F16 database-monitor report: ${reportFile.name}")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: VirtualMachineError) {
            throw fatal
        } catch (failure: Throwable) {
            logFailure("could not finish or summarize debug database-monitor measurement", failure)
        } finally {
            closeRecording(activeRecording)
            cleanupRecordingFiles()
        }
    }

    private fun writeReport(contents: String): File {
        val outputDirectory = File(System.getProperty("user.home"), ".focusflow").toPath()
        Files.createDirectories(outputDirectory)
        val reportPath = Files.createTempFile(outputDirectory, "F16-db-contention-", ".txt")
        Files.writeString(
            reportPath,
            contents,
            StandardCharsets.UTF_8,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING
        )
        return reportPath.toFile()
    }

    private fun eventValue(event: Any, fieldName: String): Any? =
        invoke(event, "getValue", arrayOf(String::class.java), fieldName)

    private fun eventTypeName(event: Any): String? =
        invoke(event, "getEventType")?.let { invoke(it, "getName") as? String }

    private fun eventStackFrames(event: Any): List<MonitorStackFrame> {
        val stack = eventValue(event, "stackTrace") ?: return emptyList()
        val frames = invoke(stack, "getFrames") as? List<*> ?: return emptyList()
        return frames.mapNotNull { frame ->
            frame ?: return@mapNotNull null
            val method = invoke(frame, "getMethod") ?: return@mapNotNull null
            val type = invoke(method, "getType") ?: return@mapNotNull null
            MonitorStackFrame(
                className = invoke(type, "getName") as? String ?: return@mapNotNull null,
                methodName = invoke(method, "getName") as? String ?: return@mapNotNull null,
                lineNumber = invoke(frame, "getLineNumber") as? Int ?: -1
            )
        }
    }

    private fun invoke(
        target: Any,
        methodName: String,
        parameterTypes: Array<Class<*>> = emptyArray(),
        vararg arguments: Any
    ): Any? = try {
        target.javaClass.getMethod(methodName, *parameterTypes).invoke(target, *arguments)
    } catch (failure: InvocationTargetException) {
        throw failure.targetException ?: failure
    }

    private fun closeRecording(activeRecording: Any) {
        try {
            invoke(activeRecording, "close")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: VirtualMachineError) {
            throw fatal
        } catch (failure: Throwable) {
            logFailure("could not close debug database-monitor recording", failure)
        }
    }

    private fun cleanupRecordingFiles() {
        val directory = recordingDirectory ?: return
        try {
            Files.deleteIfExists(directory.resolve("database-monitor.jfr"))
            Files.deleteIfExists(directory)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: VirtualMachineError) {
            throw fatal
        } catch (failure: Throwable) {
            logFailure("could not remove the temporary F16 recording", failure)
        }
    }

    private fun logFailure(message: String, failure: Throwable) {
        val cause = (failure as? InvocationTargetException)?.targetException ?: failure
        EnforcementLog.warn(
            "F16",
            "$message (${cause.javaClass.simpleName}: ${cause.message.orEmpty()})"
        )
    }

    private fun formatMillis(nanos: Long): String =
        String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0)

    private data class MonitorStackFrame(
        val className: String,
        val methodName: String,
        val lineNumber: Int
    ) {
        fun format(): String = "$className.$methodName:${lineNumber.takeIf { it >= 0 } ?: "?"}"
    }

    private data class WaitAggregate(
        var count: Int = 0,
        var totalNanos: Long = 0L,
        var maxNanos: Long = 0L,
        var awtCount: Int = 0,
        var awtNanos: Long = 0L
    )
}
