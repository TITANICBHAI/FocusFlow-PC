package com.focusflow.enforcement

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NetworkBlocker — Windows Firewall enforcement (outbound-deny rules per program).
 *
 * This object is the public face; the logic lives in smaller, separately tested pieces:
 *  - [FirewallRuleManager] owns the state: who needs each rule ([FirewallOwner]), what is
 *    confirmed active, the queue of background requests and the retry back-off.
 *  - [PowerShellFirewallBackend] talks to Windows Firewall: one PowerShell run at a time, absolute
 *    PowerShell path, started as a FocusFlow-owned process (so Nuclear Mode never kills it), hard
 *    timeouts, the whole process tree killed on timeout or [shutdown].
 *  - [ExePathResolver] finds the program a rule points at, trusting the real Windows copy over a
 *    same-named process running from elsewhere.
 *
 * A rule only counts as active after the firewall read-back shows it enabled, outbound and Block.
 * Enforcement code never waits for the firewall: [requestRule] queues the work for a background
 * worker, which applies queued programs together in batches.
 *
 * Owners: Nuclear Mode, the person's own rules and session cutoffs can need the same program.
 * A rule is removed only when its last owner releases it, so ending a focus session
 * ([removeSessionRules]) no longer removes Nuclear Mode's rules.
 */
object NetworkBlocker {

    private val resolver by lazy { ExePathResolver(SystemTools.systemRoot()) }

    /** baseName (no .exe, lowercase) → resolved absolute exe path. */
    private val resolvedPaths = ConcurrentHashMap<String, String>()

    private val worker by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "FocusFlow-Firewall").apply { isDaemon = true } }
    }
    private val workerScheduled = AtomicBoolean(false)

    private val managerDelegate = lazy {
        FirewallRuleManager(
            backend = PowerShellFirewallBackend(),
            resolvePath = ::resolveExePath,
            wake = ::wakeWorker
        )
    }
    private val manager: FirewallRuleManager get() = managerDelegate.value

    private fun resolveExePath(name: String): String? {
        val baseName = name.removeSuffix(".exe")
        resolvedPaths[baseName]?.let { return it }
        val resolved = resolver.resolve(name) ?: return null
        resolvedPaths[baseName] = resolved
        return resolved
    }

    /** Runs the queued requests on the worker; requests that arrive meanwhile schedule one more run. */
    private fun wakeWorker() {
        if (!workerScheduled.compareAndSet(false, true)) return
        try {
            worker.execute {
                workerScheduled.set(false)
                try {
                    manager.processQueued()
                } catch (t: Throwable) {
                    EnforcementLog.warn("NetworkBlocker", "Background firewall work failed", t)
                }
            }
        } catch (_: RejectedExecutionException) {
            workerScheduled.set(false)
        }
    }

    private val enabled: Boolean get() = isWindows && ElevationManager.isElevated

    // ── Applying ──────────────────────────────────────────────────────────────

    /**
     * Non-blocking. Asks the background worker to block [processName]. Safe to call from the
     * enforcement loop / hook callbacks: it never waits for PowerShell. Repeated calls collapse into
     * one queued job, and a failed attempt is not repeated for a minute.
     */
    fun requestRule(processName: String, owner: FirewallOwner = FirewallOwner.SESSION) {
        if (!enabled) return
        manager.request(owner, normalizeProcessName(processName))
    }

    /**
     * Blocking. Block all outbound traffic for [processName] (e.g. "chrome.exe"). For UI actions
     * that need the result. Returns true only when the firewall confirmed the rule.
     */
    fun addRule(processName: String, owner: FirewallOwner = FirewallOwner.USER): Boolean {
        if (!enabled) return false
        val name = normalizeProcessName(processName)
        return manager.acquire(owner, listOf(name)).contains(name)
    }

    /**
     * Blocking. Applies outbound-deny rules for many programs in as few PowerShell runs as
     * possible. Returns the programs whose rule was confirmed active, including ones that already were.
     */
    fun addRules(processNames: Collection<String>, owner: FirewallOwner = FirewallOwner.USER): Set<String> {
        if (!enabled) return emptySet()
        return manager.acquire(owner, processNames.map(::normalizeProcessName))
    }

    // ── Removing ──────────────────────────────────────────────────────────────

    /** Blocking. Release [owner]'s claim on [processName]; the rule goes when nobody else needs it. */
    fun removeRule(processName: String, owner: FirewallOwner = FirewallOwner.USER) {
        removeRules(listOf(processName), owner)
    }

    /** Blocking. Release [owner]'s claim on several programs. */
    fun removeRules(processNames: Collection<String>, owner: FirewallOwner = FirewallOwner.USER) {
        if (!isWindows) return
        val names = processNames.map(::normalizeProcessName).distinct()
        if (names.isNotEmpty()) manager.release(owner, names)
    }

    /** Blocking. Release what focus-session cutoffs claimed; rules Nuclear Mode or the person need stay. */
    fun removeSessionRules() {
        if (!isWindows) return
        manager.releaseOwner(FirewallOwner.SESSION)
    }

    /** Blocking. Remove every FocusFlow-created firewall rule. For app exit and uninstall, not for a session end. */
    fun removeAllRules() {
        if (!isWindows) return
        manager.removeAll()
    }

    /**
     * Stops accepting work, drops what is queued and ends a PowerShell run in progress. Call on app
     * exit before [removeAllRules] (which still works afterwards).
     */
    fun shutdown() {
        if (managerDelegate.isInitialized()) manager.shutdown()
    }

    // ── Startup sync / retry ──────────────────────────────────────────────────

    /** Reads the existing FocusFlow rules so rules from a previous run are recognised. Call once at startup. */
    fun syncFromFirewall() {
        if (!enabled) return
        manager.syncFromBackend()
    }

    /** Re-queues rules whose program path was not found earlier. Non-blocking. */
    fun retryPendingRules() {
        if (managerDelegate.isInitialized()) manager.retryPending()
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    fun isBlocked(processName: String): Boolean =
        managerDelegate.isInitialized() && manager.isBlocked(normalizeProcessName(processName))

    fun activeRuleCount(): Int = if (managerDelegate.isInitialized()) manager.activeCount() else 0

    fun pendingRuleCount(): Int = if (managerDelegate.isInitialized()) manager.pendingCount() else 0

    private fun normalizeProcessName(processName: String): String =
        processName.trim()
            .substringAfterLast('\\')
            .substringAfterLast('/')
            .lowercase()
            .let { if (it.endsWith(".exe")) it else "$it.exe" }
}
