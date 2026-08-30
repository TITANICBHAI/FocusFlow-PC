package com.focusflow.enforcement

/**
 * NetworkBlocker — three-layer Windows Firewall enforcement
 *
 * Layer 1 — Resolve exe path:
 *   First tries `Get-Process` (process must be running). If the process is not
 *   currently running, falls back to a directory search across the five most
 *   common Windows install locations (Program Files, ProgramFiles(x86), AppData,
 *   LocalAppData, System32). Resolved paths are cached so subsequent calls skip
 *   the search entirely.
 *
 * Layer 2 — Apply + verify:
 *   Creates the outbound-deny firewall rule via `New-NetFirewallRule`, then
 *   immediately calls `Get-NetFirewallRule` to confirm the rule exists and is
 *   enabled. Only marks the rule as active if verification passes.
 *
 * Layer 3 — Sync from firewall state on startup:
 *   [syncFromFirewall] reads all existing FocusFlow rules from the Windows
 *   Firewall and populates [activeRules]. This survives app restarts — rules
 *   created in a previous session are recognised and not double-applied.
 */
object NetworkBlocker {

    private const val RULE_PREFIX = "FocusFlow_Block_"

    /** Tracks which process names have an active firewall rule. */
    private val activeRules: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf())

    /**
     * Cache: baseName (no .exe, lowercase) → resolved absolute exe path.
     * Avoids repeated directory searches for the same process.
     */
    private val resolvedPaths = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Processes for which we have a pending rule (path not yet resolved).
     * ProcessMonitor retries these on the next block cycle.
     */
    private val pendingRules: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf())

    // ── Layer 1: Path resolution ─────────────────────────────────────────────

    /**
     * Resolves the absolute exe path for [baseName] (process name without ".exe").
     *
     * Resolution order:
     *   1. In-memory cache (instant)
     *   2. Running process via Get-Process (most accurate)
     *   3. Common install directories (handles pre-emptive blocks before process runs)
     */
    private fun resolveExePath(baseName: String): String? {
        // Fast path — already resolved; ConcurrentHashMap read is lock-free.
        resolvedPaths[baseName]?.let { return it }

        // Serialise resolution per map instance.
        // Without this guard two concurrent addRule("chrome.exe") calls both see a
        // cache miss and both spawn an expensive PowerShell process for the same name.
        // The synchronized block is cheap: resolution is at most once-per-process-name,
        // so contention on this lock is negligible in practice.
        return synchronized(resolvedPaths) {
            // Re-read inside the lock — another thread may have resolved while we waited.
            resolvedPaths[baseName] ?: run {
                val resolved = resolveExePathUncached(baseName)
                // Only cache on success; null means the process isn't running yet.
                // Leaving it out of the cache lets retryPendingRules() try again later.
                if (resolved != null) resolvedPaths[baseName] = resolved
                resolved
            }
        }
    }

    private fun resolveExePathUncached(baseName: String): String? {
        // 2. Running process
        val fromProcess = runPowerShellAndRead(
            "(Get-Process -Name '$baseName' -ErrorAction SilentlyContinue " +
            "| Select-Object -First 1 -ExpandProperty Path)"
        )?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        if (fromProcess != null) return fromProcess

        // 3. Directory search — try bare exe name, then one level deep
        val searchRoots = listOfNotNull(
            System.getenv("ProgramFiles"),
            System.getenv("ProgramFiles(x86)"),
            System.getenv("LOCALAPPDATA"),
            System.getenv("APPDATA"),
            "C:\\Windows\\System32",
            "C:\\Windows\\SysWOW64"
        )
        return searchRoots.firstNotNullOfOrNull { root ->
            val direct = java.io.File(root, "$baseName.exe")
            if (direct.exists()) return@firstNotNullOfOrNull direct.absolutePath
            java.io.File(root).listFiles()?.firstNotNullOfOrNull { sub ->
                val nested = java.io.File(sub, "$baseName.exe")
                if (nested.exists()) nested.absolutePath else null
            }
        }
    }

    // ── Layer 2: Apply + verify ──────────────────────────────────────────────

    /**
     * Block all outbound traffic for [processName] (e.g. "chrome.exe").
     *
     * Returns true  — rule created and verified.
     * Returns false — no admin, not Windows, or path could not be resolved
     *                 (rule is queued in [pendingRules] for retry).
     */
    fun addRule(processName: String): Boolean {
        if (!isWindows || !isRunningAsAdmin()) return false

        val lower    = normalizeProcessName(processName)
        val baseName = lower.removeSuffix(".exe")
        val ruleName = RULE_PREFIX + baseName

        if (activeRules.contains(lower)) return true   // Already applied

        val exePath = resolveExePath(baseName)
        if (exePath == null) {
            pendingRules.add(lower)
            return false
        }

        // Remove any stale rule with the same name before creating fresh
        runPowerShell(
            "Remove-NetFirewallRule -DisplayName '$ruleName' -ErrorAction SilentlyContinue"
        )

        val created = runPowerShell("""
            New-NetFirewallRule `
                -DisplayName '$ruleName' `
                -Direction Outbound `
                -Action Block `
                -Program '$exePath' `
                -Profile Any `
                -Enabled True `
                -ErrorAction Stop | Out-Null
        """.trimIndent())

        // Verify the rule actually exists and is an enabled outbound block.
        // Previously the create command's exit code was discarded and a stale
        // enabled rule with the same display name could be treated as success.
        val verified = created && verifyRuleExists(ruleName)
        if (verified) {
            activeRules.add(lower)
            pendingRules.remove(lower)
        } else {
            EnforcementLog.warn(
                "NetworkBlocker",
                "Firewall rule was not confirmed for $processName (path=$exePath)"
            )
        }
        return verified
    }

    private fun verifyRuleExists(ruleName: String): Boolean {
        val count = runPowerShellAndRead(
            "(Get-NetFirewallRule -DisplayName '$ruleName' -ErrorAction SilentlyContinue " +
            "| Where-Object { \$_.Enabled -eq 'True' -and \$_.Direction -eq 'Outbound' " +
            "-and \$_.Action -eq 'Block' } | Measure-Object).Count"
        )?.trim()?.toIntOrNull() ?: return false
        return count > 0
    }

    /**
     * Remove the outbound-block rule for [processName].
     */
    fun removeRule(processName: String) {
        if (!isWindows) return
        val baseName = normalizeProcessName(processName).removeSuffix(".exe")
        val ruleName = RULE_PREFIX + baseName
        runPowerShell(
            "Remove-NetFirewallRule -DisplayName '$ruleName' -ErrorAction SilentlyContinue"
        )
        activeRules.remove(normalizeProcessName(processName))
        pendingRules.remove(normalizeProcessName(processName))
    }

    /**
     * Remove every FocusFlow-created firewall rule. Call on app exit / session end.
     */
    fun removeAllRules() {
        if (!isWindows) return
        runPowerShell("""
            Get-NetFirewallRule |
            Where-Object { ${'$'}_.DisplayName -like '$RULE_PREFIX*' } |
            Remove-NetFirewallRule -ErrorAction SilentlyContinue
        """.trimIndent())
        activeRules.clear()
        pendingRules.clear()
    }

    // ── Layer 3: Sync from actual firewall state ──────────────────────────────

    /**
     * Reads all existing FocusFlow firewall rules from Windows and populates
     * [activeRules] accordingly. Call once at app startup so rules created in a
     * previous session are recognised without being re-applied.
     */
    fun syncFromFirewall() {
        if (!isWindows || !isRunningAsAdmin()) return
        val output = runPowerShellAndRead("""
            Get-NetFirewallRule |
            Where-Object { ${'$'}_.DisplayName -like '$RULE_PREFIX*' -and ${'$'}_.Enabled -eq 'True' } |
            Select-Object -ExpandProperty DisplayName
        """.trimIndent()) ?: return

        activeRules.clear()
        output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { ruleName ->
                val baseName = ruleName.removePrefix(RULE_PREFIX).lowercase()
                activeRules.add("$baseName.exe")
            }
    }

    /**
     * Retry any rules that failed path resolution (process was not running at
     * block time). Call periodically from ProcessMonitor's enforcement loop.
     */
    fun retryPendingRules() {
        val pending = synchronized(pendingRules) { pendingRules.toSet() }
        pending.forEach { addRule(it) }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    fun isBlocked(processName: String): Boolean =
        activeRules.contains(normalizeProcessName(processName))

    fun activeRuleCount(): Int = activeRules.size

    fun pendingRuleCount(): Int = pendingRules.size

    // ── PowerShell helpers ────────────────────────────────────────────────────

    private fun runPowerShell(script: String): Boolean {
        try {
            val process = ProcessBuilder(
                "powershell", "-NonInteractive", "-NoProfile",
                "-ExecutionPolicy", "Bypass", "-Command", script
            ).redirectErrorStream(true).start()
            // Drain the merged output before waiting.  A failed firewall command
            // can write enough diagnostic text to fill the pipe; closing it
            // immediately would leave waitFor() blocked and make enforcement
            // appear to hang.
            process.inputStream.bufferedReader().readText()
            return process.waitFor() == 0
        } catch (_: Exception) {
            return false
        }
    }

    private fun runPowerShellAndRead(script: String): String? {
        return try {
            val proc = ProcessBuilder(
                "powershell", "-NonInteractive", "-NoProfile",
                "-ExecutionPolicy", "Bypass", "-Command", script
            ).redirectErrorStream(true).start()
            val text = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            text.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    private fun normalizeProcessName(processName: String): String =
        processName.trim()
            .substringAfterLast('\\')
            .substringAfterLast('/')
            .lowercase()
            .let { if (it.endsWith(".exe")) it else "$it.exe" }
}
