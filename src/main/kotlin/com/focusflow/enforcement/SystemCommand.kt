package com.focusflow.enforcement

import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs a Windows tool (schtasks, reg, ...) to completion with a hard timeout.
 *
 * Output is read on its own thread, so a process that never exits cannot block the caller on a
 * read: the timeout is honoured and the process is killed. The tool is started through
 * [OwnedProcesses], so enforcement recognises it as FocusFlow's own helper.
 */
internal object SystemCommand {

    class Result(val exitCode: Int?, val output: String, val timedOut: Boolean) {
        val ok: Boolean get() = !timedOut && exitCode == 0
    }

    /** @param command the program first; for a system tool use [SystemTools.path] so it is an absolute path. */
    fun run(
        command: List<String>,
        timeoutSec: Long,
        start: (ProcessBuilder) -> Process = { OwnedProcesses.start(it, expectedImage = command.first()) }
    ): Result {
        val process = try {
            start(ProcessBuilder(command).redirectErrorStream(true))
        } catch (_: Exception) {
            return Result(exitCode = null, output = "", timedOut = false)
        }
        try { process.outputStream.close() } catch (_: Exception) {}

        val buffer = StringBuilder()
        val reader = thread(name = "FocusFlow-CmdReader", isDaemon = true) {
            try {
                process.inputStream.bufferedReader().use { r ->
                    val chunk = CharArray(4096)
                    while (true) {
                        val n = r.read(chunk)
                        if (n < 0) break
                        synchronized(buffer) { buffer.append(chunk, 0, n) }
                    }
                }
            } catch (_: Exception) {}
        }

        val finished = try {
            process.waitFor(timeoutSec, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            try { process.toHandle().descendants().forEach { it.destroyForcibly() } } catch (_: Exception) {}
            process.destroyForcibly()
            reader.join(1_000)
            return Result(exitCode = null, output = synchronized(buffer) { buffer.toString() }, timedOut = true)
        }
        reader.join(2_000)
        return Result(process.exitValue(), synchronized(buffer) { buffer.toString() }, timedOut = false)
    }
}
