package com.focusflow.enforcement

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class PsResult(val ok: Boolean, val output: String, val timedOut: Boolean)

/** Runs PowerShell scripts one at a time. A seam so the backend's logic can be tested without PowerShell. */
internal interface PsScriptRunner {
    fun run(script: String, timeoutSec: Long): PsResult

    /** Ends the run in progress, if any. Must not block. */
    fun cancel()
}

/**
 * Runs one PowerShell script at a time with a hard timeout.
 *
 * The program is the absolute Windows PowerShell path (not whatever "powershell" resolves to) and
 * is started through [OwnedProcesses], so enforcement recognises it as FocusFlow's own and never
 * kills it as an escape tool. Reading output, the timeout and ending the process tree are
 * [SystemCommand]'s job (one implementation, not two); this class adds only what is specific to
 * PowerShell: one script at a time, and [cancel] so the app can end a run that is in progress.
 */
internal class PowerShellRunner(
    private val commandFor: (script: String) -> List<String> = { powerShellCommand(it, SystemTools.systemRoot()) },
    private val start: (ProcessBuilder) -> Process = { builder ->
        OwnedProcesses.start(builder, expectedImage = SystemTools.powerShell())
    }
) : PsScriptRunner {
    private val runLock = ReentrantLock()
    @Volatile private var current: Process? = null

    override fun run(script: String, timeoutSec: Long): PsResult = runLock.withLock {
        try {
            val result = SystemCommand.run(commandFor(script), timeoutSec) { builder ->
                start(builder).also { current = it }
            }
            if (result.timedOut) {
                EnforcementLog.warn("NetworkBlocker", "PowerShell firewall call timed out after ${timeoutSec}s and was killed")
            }
            PsResult(result.ok, result.output, result.timedOut)
        } finally {
            current = null
        }
    }

    /** Ends the run in progress, if any. Takes no lock, so it works while [run] is blocked. */
    override fun cancel() {
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
     * Remove any stale rule, create the outbound-deny rule, then read back the rules of THIS batch
     * together with the program each one points at: `FF_OK=<rule>|<program>`.
     * Entries are 'ruleName|exePath' strings (not nested arrays: a one-entry nested array is
     * flattened by PowerShell). The path lookup (`Get-NetFirewallApplicationFilter`) is slow per
     * rule, so it only runs for the rules just applied, never for every FocusFlow rule.
     */
    fun apply(rules: List<FirewallRule>): String {
        val items = rules.joinToString(",\n") { "    '${psq("${ruleName(it.name)}|${it.exePath}")}'" }
        return """
            ${D}ErrorActionPreference = 'Continue'
            ${D}items = @(
            $items
            )
            ${D}batch = @{}
            foreach (${D}i in ${D}items) {
                ${D}name = ${D}i.Substring(0, ${D}i.IndexOf('|'))
                ${D}path = ${D}i.Substring(${D}i.IndexOf('|') + 1)
                ${D}batch[${D}name] = ${D}true
                try {
                    Remove-NetFirewallRule -DisplayName ${D}name -ErrorAction SilentlyContinue
                    New-NetFirewallRule -DisplayName ${D}name -Direction Outbound -Action Block -Program ${D}path -Profile Any -Enabled True -ErrorAction Stop | Out-Null
                } catch { }
            }
            Get-NetFirewallRule -DisplayName '$RULE_PREFIX*' -ErrorAction SilentlyContinue |
                Where-Object { ${D}batch.ContainsKey(${D}_.DisplayName) -and ${D}_.Enabled -eq 'True' -and ${D}_.Direction -eq 'Outbound' -and ${D}_.Action -eq 'Block' } |
                ForEach-Object {
                    ${D}program = ''
                    try { ${D}program = [string](${D}_ | Get-NetFirewallApplicationFilter -ErrorAction Stop).Program } catch { }
                    'FF_OK=' + ${D}_.DisplayName + '|' + ${D}program
                }
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

    /** Every enabled FocusFlow rule with the program it points at: `FF_RULE=<rule>|<program>`. */
    val readEnabled: String = """
        Get-NetFirewallRule -DisplayName '$RULE_PREFIX*' -ErrorAction SilentlyContinue |
        Where-Object { ${D}_.Enabled -eq 'True' } |
        ForEach-Object {
            ${D}program = ''
            try { ${D}program = [string](${D}_ | Get-NetFirewallApplicationFilter -ErrorAction Stop).Program } catch { }
            'FF_RULE=' + ${D}_.DisplayName + '|' + ${D}program
        }
    """.trimIndent()

    /**
     * Rules from output lines `<marker><FocusFlow rule name>|<program>`, keyed by program name
     * (`chrome.exe`). A blank or missing program is null ("could not be read"). Foreign rules are ignored.
     */
    fun parseEntries(output: String, marker: String): Map<String, String?> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith(marker) }
            .map { it.removePrefix(marker) }
            .mapNotNull { raw ->
                val separator = raw.indexOf('|')
                val rule = (if (separator < 0) raw else raw.substring(0, separator)).trim()
                val program = if (separator < 0) null else raw.substring(separator + 1).trim().ifEmpty { null }
                if (!rule.startsWith(RULE_PREFIX)) null
                else (rule.removePrefix(RULE_PREFIX).lowercase() + ".exe") to program
            }
            .toMap()

    /** Program names only, from [parseEntries]. */
    fun parseNames(output: String, marker: String): Set<String> = parseEntries(output, marker).keys

    /** The outcome of checking a batch against what the firewall reports. */
    class Verification(
        /** Rules to treat as active: read back enabled/outbound/Block and pointing at the requested program (or unverifiable). */
        val confirmed: Set<String>,
        /** Rules that exist but point at a different program: name to (requested, actual). Not confirmed. */
        val mismatched: Map<String, Pair<String, String>>,
        /** Rules read back but whose program could not be read. Accepted, because the name matched. */
        val unverifiable: Set<String>
    )

    /**
     * A rule counts only if it points at the program we asked for. A rule that points elsewhere (an
     * old install folder, a different copy) does not block the program that runs, so reporting it as
     * blocked would be a silent failure.
     */
    fun verifyApplied(requested: List<FirewallRule>, readBack: Map<String, String?>): Verification {
        val confirmed = LinkedHashSet<String>()
        val mismatched = LinkedHashMap<String, Pair<String, String>>()
        val unverifiable = LinkedHashSet<String>()
        for (rule in requested) {
            if (!readBack.containsKey(rule.name)) continue
            val actual = readBack[rule.name]
            when {
                actual == null -> { confirmed += rule.name; unverifiable += rule.name }
                sameProgramPath(rule.exePath, actual) -> confirmed += rule.name
                else -> mismatched[rule.name] = rule.exePath to actual
            }
        }
        return Verification(confirmed, mismatched, unverifiable)
    }
}

/** [FirewallBackend] that drives Windows Firewall through PowerShell. */
internal class PowerShellFirewallBackend(
    private val runner: PsScriptRunner = PowerShellRunner(),
    private val applyTimeoutSec: Long = 60L,
    private val queryTimeoutSec: Long = 60L
) : FirewallBackend {

    override fun apply(rules: List<FirewallRule>): Set<String> {
        val startedAt = System.nanoTime()
        val result = runner.run(FirewallScripts.apply(rules), applyTimeoutSec)
        val verdict = FirewallScripts.verifyApplied(rules, FirewallScripts.parseEntries(result.output, "FF_OK="))
        val durationMs = (System.nanoTime() - startedAt) / 1_000_000L

        verdict.mismatched.forEach { (name, paths) ->
            EnforcementLog.warn(
                "NetworkBlocker",
                "firewall rule for $name points at a different program than requested: requested=${paths.first} actual=${paths.second}"
            )
        }
        if (verdict.unverifiable.isNotEmpty()) {
            EnforcementLog.warn(
                "NetworkBlocker",
                "firewall rule program path could not be read back for ${verdict.unverifiable.sorted()}; accepted by name only"
            )
        }
        EnforcementLog.info(
            "NetworkBlocker",
            "firewall apply rules=${rules.size} confirmed=${verdict.confirmed.size} mismatched=${verdict.mismatched.size} " +
                "psOk=${result.ok} timedOut=${result.timedOut} durationMs=$durationMs"
        )
        return verdict.confirmed
    }

    override fun remove(names: Collection<String>): Boolean =
        runner.run(FirewallScripts.remove(names), applyTimeoutSec).ok

    override fun removeAll(): Boolean = runner.run(FirewallScripts.removeAll, applyTimeoutSec).ok

    override fun readActive(): Map<String, String?>? {
        val result = runner.run(FirewallScripts.readEnabled, queryTimeoutSec)
        // A timed-out or failed read must not be mistaken for "no rules".
        return if (result.ok) FirewallScripts.parseEntries(result.output, "FF_RULE=") else null
    }

    override fun close() = runner.cancel()
}
