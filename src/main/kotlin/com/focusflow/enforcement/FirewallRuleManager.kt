package com.focusflow.enforcement

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Who needs a firewall rule to exist. A rule is removed only when its last owner lets go. */
enum class FirewallOwner {
    /** Nuclear Mode's blocks on escape tools. */
    NUCLEAR,

    /** A rule the person asked for (App Blocker, VPN / network screens). */
    USER,

    /** Network cutoffs raised by enforcement during a focus session; they end with the session. */
    SESSION,

    /** A rule found in the firewall at startup that nobody in this run has claimed. */
    RECOVERED
}

/** One outbound-deny rule: [name] is the normalized program name (`chrome.exe`), [exePath] its full path. */
internal data class FirewallRule(val name: String, val exePath: String)

/**
 * The part that actually talks to Windows Firewall. Everything here is blocking and may take
 * seconds; [FirewallRuleManager] serializes calls and keeps them off the enforcement path.
 * A fake implementation makes the rule logic testable without Windows.
 */
internal interface FirewallBackend {
    /** Creates the rules and returns the names that were read back as enabled, outbound and Block. */
    fun apply(rules: List<FirewallRule>): Set<String>

    /** Removes the rules for [names]. Missing rules are not an error. */
    fun remove(names: Collection<String>): Boolean

    /** Removes every rule FocusFlow created. */
    fun removeAll(): Boolean

    /** The programs with an active FocusFlow rule, or null when the firewall could not be read. */
    fun readActive(): Set<String>?

    /** Stops whatever is running right now (a stuck PowerShell). Must not block or take locks. */
    fun close()
}

/**
 * Keeps track of which programs are blocked and why, and drives a [FirewallBackend].
 *
 * What it guarantees, and why it exists:
 *  - **Owners.** Nuclear Mode, the person's own rules and session cutoffs can need the same
 *    program. Ending a session used to remove every rule, including Nuclear Mode's; now only the
 *    session's own claims are released, and a rule disappears when its last owner releases it.
 *  - **No stale "active".** The state is updated only after the firewall confirms a rule, and only
 *    if someone still wants it; a rule released while its apply was running is removed again
 *    instead of being recorded as active.
 *  - **Serialized firewall access, no lock held across it.** One backend call at a time; state
 *    locks are never held while the (slow) backend runs.
 *  - **Coalescing.** Background requests collect into a queue and are applied in batches.
 */
internal class FirewallRuleManager(
    private val backend: FirewallBackend,
    private val resolvePath: (name: String) -> String?,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val batchSize: Int = 20,
    private val failureBackoffMs: Long = 60_000L,
    private val unresolvedBackoffMs: Long = 10_000L,
    private val wake: () -> Unit = {}
) {
    private val stateLock = Any()
    private val backendLock = ReentrantLock()

    private val owners = HashMap<String, MutableSet<FirewallOwner>>()
    private val active = HashSet<String>()
    private val pending = HashSet<String>()
    private val queued = LinkedHashMap<String, MutableSet<FirewallOwner>>()
    private val retryAfter = HashMap<String, Long>()
    @Volatile private var closed = false

    // ── Blocking operations ──────────────────────────────────────────────────

    /** Claims [names] for [owner] and applies whatever is not blocked yet. Returns those confirmed blocked. */
    fun acquire(owner: FirewallOwner, names: Collection<String>): Set<String> {
        if (closed) return emptySet()
        val wanted = names.distinct()
        synchronized(stateLock) { wanted.forEach { owners.getOrPut(it) { mutableSetOf() }.add(owner) } }
        return applyMissing(wanted)
    }

    /** Lets go of [names]; each rule is removed once nobody owns it any more. */
    fun release(owner: FirewallOwner, names: Collection<String>) {
        val toRemove = ArrayList<String>()
        synchronized(stateLock) {
            for (name in names.distinct()) {
                queued[name]?.let { claimants ->
                    claimants.remove(owner)
                    if (claimants.isEmpty()) queued.remove(name)
                }
                val claimants = owners[name]
                claimants?.remove(owner)
                claimants?.remove(FirewallOwner.RECOVERED)
                val stillWanted = !claimants.isNullOrEmpty() || queued.containsKey(name)
                if (stillWanted) continue
                owners.remove(name)
                active.remove(name)
                pending.remove(name)
                retryAfter.remove(name)
                toRemove += name
            }
        }
        if (toRemove.isNotEmpty()) backendLock.withLock { backend.remove(toRemove) }
    }

    /** Lets go of everything [owner] holds (for example when a session ends). */
    fun releaseOwner(owner: FirewallOwner) {
        val held = synchronized(stateLock) {
            owners.filterValues { owner in it }.keys + queued.filterValues { owner in it }.keys
        }
        if (held.isNotEmpty()) release(owner, held)
    }

    /** Removes every FocusFlow rule and forgets all state. Still works after [shutdown], for exit cleanup. */
    fun removeAll() {
        synchronized(stateLock) {
            owners.clear(); active.clear(); pending.clear(); queued.clear(); retryAfter.clear()
        }
        backendLock.withLock { backend.removeAll() }
    }

    /** Adopts the rules that already exist in the firewall. A failed read changes nothing. */
    fun syncFromBackend() {
        val found = backend.readActive() ?: return
        synchronized(stateLock) {
            val vanished = active.filter { it !in found }
            active.removeAll(vanished.toSet())
            // A rule someone still wants but that is gone from the firewall is applied again later.
            vanished.filter { owners.containsKey(it) }.forEach { pending += it; retryAfter[it] = nowMs() }
            for (name in found) {
                active += name
                pending -= name
                val claimants = owners.getOrPut(name) { mutableSetOf() }
                if (claimants.isEmpty()) claimants += FirewallOwner.RECOVERED
            }
        }
    }

    // ── Background requests ──────────────────────────────────────────────────

    /**
     * Non-blocking: asks for [name] to be blocked on behalf of [owner]. Returns true when new work
     * was queued; false when it is already blocked, already queued, backing off, or shut down.
     */
    fun request(owner: FirewallOwner, name: String): Boolean {
        if (closed) return false
        synchronized(stateLock) {
            if (name in active) {
                owners.getOrPut(name) { mutableSetOf() }.add(owner)
                return false
            }
            queued[name]?.let { it.add(owner); return false }
            val notBefore = retryAfter[name]
            if (notBefore != null && nowMs() < notBefore) {
                owners.getOrPut(name) { mutableSetOf() }.add(owner)
                return false
            }
            queued[name] = mutableSetOf(owner)
        }
        wake()
        return true
    }

    /** Applies everything queued so far, in batches. Called by the background worker. */
    fun processQueued(): Int {
        val names = synchronized(stateLock) {
            if (closed || queued.isEmpty()) return 0
            val taken = LinkedHashMap(queued)
            queued.clear()
            taken.forEach { (name, claimants) -> owners.getOrPut(name) { mutableSetOf() }.addAll(claimants) }
            taken.keys.toList()
        }
        applyMissing(names)
        return names.size
    }

    /** Queues programs whose path could not be found earlier (once their back-off has passed). */
    fun retryPending() {
        var queuedAny = false
        synchronized(stateLock) {
            if (closed) return
            val now = nowMs()
            for (name in pending.toList()) {
                val notBefore = retryAfter[name]
                if (notBefore != null && now < notBefore) continue
                queued.getOrPut(name) { mutableSetOf() }.addAll(owners[name].orEmpty())
                queuedAny = true
            }
        }
        if (queuedAny) wake()
    }

    /** Stops accepting work, drops what is queued and stops the backend's current call. */
    fun shutdown() {
        closed = true
        synchronized(stateLock) { queued.clear() }
        backend.close()
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    fun isBlocked(name: String): Boolean = synchronized(stateLock) { name in active }
    fun activeCount(): Int = synchronized(stateLock) { active.size }
    fun pendingCount(): Int = synchronized(stateLock) { pending.size }
    fun queuedCount(): Int = synchronized(stateLock) { queued.size }

    // ── Applying ─────────────────────────────────────────────────────────────

    private fun applyMissing(names: Collection<String>): Set<String> {
        val candidates = synchronized(stateLock) { names.filter { it !in active && owners.containsKey(it) } }

        val rules = ArrayList<FirewallRule>()
        for (name in candidates) {
            val path = resolvePath(name)
            if (path == null) {
                synchronized(stateLock) { pending += name; retryAfter[name] = nowMs() + unresolvedBackoffMs }
            } else {
                rules += FirewallRule(name, path)
            }
        }

        for (chunk in rules.chunked(batchSize.coerceAtLeast(1))) {
            backendLock.withLock {
                // Someone may have released, or another thread applied it, while we waited.
                val wanted = synchronized(stateLock) {
                    if (closed) emptyList() else chunk.filter { owners.containsKey(it.name) && it.name !in active }
                }
                if (wanted.isEmpty()) return@withLock

                val confirmed = try {
                    backend.apply(wanted)
                } catch (_: Throwable) {
                    emptySet()
                }

                val stale = ArrayList<String>()
                synchronized(stateLock) {
                    for (rule in wanted) {
                        when {
                            rule.name !in confirmed -> retryAfter[rule.name] = nowMs() + failureBackoffMs
                            owners.containsKey(rule.name) -> {
                                active += rule.name
                                pending -= rule.name
                                retryAfter.remove(rule.name)
                            }
                            // Released while the firewall was working: undo it rather than record it.
                            else -> stale += rule.name
                        }
                    }
                }
                if (stale.isNotEmpty()) backend.remove(stale)
            }
        }
        return synchronized(stateLock) { names.filter { it in active }.toSet() }
    }
}
