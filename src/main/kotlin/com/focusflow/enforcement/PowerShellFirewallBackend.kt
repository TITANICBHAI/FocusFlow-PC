package com.focusflow.enforcement

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

internal class PsResult(val ok: Boolean, val output: String, val timedOut: Boolean)

/**
 * Runs one PowerShell script at a time with a hard timeout.
 *
 * The program is the absolute Windows PowerShell path (not whatever "powershell" resolves to) and
 * is started through [OwnedProcesses], so enforcement recognises it as FocusFlow's own and never
 * kills it as an escape tool. On timeout, or [cancel], the whole process tree is ended, so a stuck
 * firewall call can neither hang its caller nor outlive the app.
 */
internal class PowerShellRunner(
    private val commandFor: (script: String) -> List<String> = { powerShellCommand(it, SystemTools.systemRoot()) },
    private val start: (ProcessBuilder) -> Process = { builder ->
        OwnedProcesses.start(builder, expectedImage = SystemTools.powerShell())
    }
) {
    private val runLock = ReentrantLock()
    @Volatile private var current: Process? = null

    fun run(script: String, timeoutSec: Long): PsResult = runLock.withLock {
        val process = try {
            start(ProcessBuilder(commandFor(script)).redirectErrorStream(true))
        } catch (_: Exception) {
            return@withLock PsResult(ok = false, output = "", timedOut = false)
        }
        current = process
        try {
            try { process.outputStream.close() } catch (_: Exception) {}

            // Drain output on its own thread so a chatty failure cannot fill the pipe.
            val buffer = StringBuilder()
            val reader = thread(name = "FocusFlow-FwPsReader", isDaemon = true) {
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
                killTree(process)
                reader.join(1_000)
                EnforcementLog.warn("NetworkBlocker", "PowerShell firewall call timed out after ${timeoutSec}s and was killed")
                return@withLock PsResult(false, synchronized(buffer) { buffer.toString() }, timedOut = true)
            }
            reader.join(2_000)
            PsResult(process.exitValue() == 0, synchronized(buffer) { buffer.toString() }, timedOut = false)
        } finally {
            current = null
        }
    }

    /** Ends the run in progress, if any. Takes no lock, so it works while [run] is blocked. */
    fun cancel() {
        current?.let(::killTree)
    }

    private fun killTree(process: Process) {
        try { process.toHandle().descendants().forEach { it.destroyForcibly() } } catch (_: Exception) {}
        process.destroyForcibly()
    }

    companion object {
        fun powerShellCommand(script: String, systemRoot: String?): List<String> = listOf(
            SystemTools.powerShell(systemRoot), "-NoLogo", "-NonInteractive", "-NoProfile",
            "-ExecutionPolicy", "Bypass", "-Command", script
        )
    }
}

/** The PowerShell text for each firewall operation, and the parsing of what comes back. */
internal object FirewallScripts {
    const val RULE_PREFIX = "FocusFlow_Block_"
    private const val D = "$"

    /** Escape a value for use inside a single-quoted PowerShell string. */
    fun psq(value: String): String = value.replace("'", "''")

    private fun ruleName(name: String) = RULE_PREFIX + name.removeSuffix(".exe")

    /**
     * Remove any stale rule, create the outbound-deny rule, then read every FocusFlow rule back.
     * Entries are 'ruleName|exePath' strings (not nested arrays: a one-entry nested array is
     * flattened by PowerShell).
     */
    fun apply(rules: List<FirewallRule>): String {
        val items = rules.joinToString(",\n") { "    '${psq("${ruleName(it.name)}|${it.exePath}")}'" }
        return """
            ${D}ErrorActionPreference = 'Continue'
            ${D}items = @(
            $items
            )
            foreach (${D}i in ${D}items) {
                ${D}name = ${D}i.Substring(0, ${D}i.IndexOf('|'))
                ${D}path = ${D}i.Substring(${D}i.IndexOf('|') + 1)
                try {
                    Remove-NetFirewallRule -DisplayName ${D}name -ErrorAction SilentlyContinue
                    New-NetFirewallRule -DisplayName ${D}name -Direction Outbound -Action Block -Program ${D}path -Profile Any -Enabled True -ErrorAction Stop | Out-Null
                } catch { }
            }
            Get-NetFirewallRule -DisplayName '$RULE_PREFIX*' -ErrorAction SilentlyContinue |
                Where-Object { ${D}_.Enabled -eq 'True' -and ${D}_.Direction -eq 'Outbound' -and ${D}_.Action -eq 'Block' } |
                ForEach-Object { 'FF_OK=' + ${D}_.DisplayName }
        """.trimIndent()
    }

    fun remove(names: Collection<String>): String {
        val list = names.joinToString(",") { "'${psq(ruleName(it))}'" }
        return "foreach (${D}n in @($list)) { Remove-NetFirewallRule -DisplayName ${D}n -ErrorAction SilentlyContinue }"
    }

    val removeAll: String = """
        Get-NetFirewallRule -DisplayName '$RULE_PREFIX*' -ErrorAction SilentlyContinue |
        Remove-NetFirewallRule -ErrorAction SilentlyContinue
    """.trimIndent()

    val readEnabled: String = """
        Get-NetFirewallRule -DisplayName '$RULE_PREFIX*' -ErrorAction SilentlyContinue |
        Where-Object { ${D}_.Enabled -eq 'True' } |
        ForEach-Object { 'FF_RULE=' + ${D}_.DisplayName }
    """.trimIndent()

    /** Program names (`chrome.exe`) from output lines `<marker><FocusFlow rule name>`; foreign rules are ignored. */
    fun parseNames(output: String, marker: String): Set<String> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith(marker) }
            .map { it.removePrefix(marker).trim() }
            .filter { it.startsWith(RULE_PREFIX) }
            .map { it.removePrefix(RULE_PREFIX).lowercase() + ".exe" }
            .toSet()
}

/** [FirewallBackend] that drives Windows Firewall through PowerShell. */
internal class PowerShellFirewallBackend(
    private val runner: PowerShellRunner = PowerShellRunner(),
    private val applyTimeoutSec: Long = 60L,
    private val queryTimeoutSec: Long = 60L
) : FirewallBackend {

    override fun apply(rules: List<FirewallRule>): Set<String> {
        val startedAt = System.nanoTime()
        val result = runner.run(FirewallScripts.apply(rules), applyTimeoutSec)
        val confirmed = FirewallScripts.parseNames(result.output, "FF_OK=")
        val durationMs = (System.nanoTime() - startedAt) / 1_000_000L
        EnforcementLog.info(
            "NetworkBlocker",
            "firewall apply rules=${rules.size} confirmed=${rules.count { it.name in confirmed }} " +
                "psOk=${result.ok} timedOut=${result.timedOut} durationMs=$durationMs"
        )
        return confirmed
    }

    override fun remove(names: Collection<String>): Boolean =
        runner.run(FirewallScripts.remove(names), applyTimeoutSec).ok

    override fun removeAll(): Boolean = runner.run(FirewallScripts.removeAll, applyTimeoutSec).ok

    override fun readActive(): Set<String>? {
        val result = runner.run(FirewallScripts.readEnabled, queryTimeoutSec)
        // A timed-out or failed read must not be mistaken for "no rules".
        return if (result.ok) FirewallScripts.parseNames(result.output, "FF_RULE=") else null
    }

    override fun close() = runner.cancel()
}
