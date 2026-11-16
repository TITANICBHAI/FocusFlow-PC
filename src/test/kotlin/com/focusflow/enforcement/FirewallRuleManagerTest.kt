package com.focusflow.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirewallRuleManagerTest {

    /** A firewall that lives in memory and records every operation. */
    private class FakeBackend : FirewallBackend {
        val rules = linkedSetOf<String>()
        val applyCalls = mutableListOf<List<FirewallRule>>()
        val removeCalls = mutableListOf<List<String>>()
        var removeAllCalls = 0
        var closeCalls = 0
        var confirm: (FirewallRule) -> Boolean = { true }
        var onApply: () -> Unit = {}
        var readResult: Set<String>? = emptySet()

        override fun apply(rules: List<FirewallRule>): Set<String> {
            applyCalls += rules
            onApply()
            val ok = rules.filter(confirm).map { it.name }
            this.rules += ok
            return ok.toSet()
        }

        override fun remove(names: Collection<String>): Boolean {
            removeCalls += names.toList()
            rules -= names.toSet()
            return true
        }

        override fun removeAll(): Boolean { removeAllCalls++; rules.clear(); return true }
        override fun readActive(): Set<String>? = readResult
        override fun close() { closeCalls++ }
    }

    private var clock = 1_000_000L
    private val backend = FakeBackend()
    private val paths = mutableMapOf<String, String>()
    private var wakes = 0

    private val manager = FirewallRuleManager(
        backend = backend,
        resolvePath = { name -> paths[name] ?: "C:\\Windows\\System32\\$name" },
        nowMs = { clock },
        batchSize = 2,
        failureBackoffMs = 60_000L,
        unresolvedBackoffMs = 10_000L,
        wake = { wakes++ }
    )

    private val nuclear = FirewallOwner.NUCLEAR
    private val user = FirewallOwner.USER
    private val session = FirewallOwner.SESSION

    // ── applying ─────────────────────────────────────────────────────────────

    @Test
    fun manyProgramsAreAppliedInBatchesAndOnlyConfirmedOnesCountAsBlocked() {
        backend.confirm = { it.name != "b.exe" }

        val confirmed = manager.acquire(user, listOf("a.exe", "b.exe", "c.exe"))

        assertEquals(setOf("a.exe", "c.exe"), confirmed)
        assertEquals(listOf(2, 1), backend.applyCalls.map { it.size })
        assertTrue(manager.isBlocked("a.exe"))
        assertFalse(manager.isBlocked("b.exe"))
    }

    @Test
    fun aProgramAlreadyBlockedIsNotAppliedAgain() {
        manager.acquire(user, listOf("a.exe"))
        manager.acquire(nuclear, listOf("a.exe"))

        assertEquals(1, backend.applyCalls.size)
    }

    @Test
    fun aProgramWhosePathCannotBeFoundIsPendingAndNotSentToTheFirewall() {
        val unresolved = FirewallRuleManager(backend, resolvePath = { null }, nowMs = { clock })

        assertTrue(unresolved.acquire(user, listOf("ghost.exe")).isEmpty())

        assertTrue(backend.applyCalls.isEmpty())
        assertEquals(1, unresolved.pendingCount())
    }

    @Test
    fun theRulePathIsTheResolvedPathOfTheProgram() {
        paths["tool.exe"] = "D:\\apps\\tool.exe"

        manager.acquire(user, listOf("tool.exe"))

        assertEquals(listOf(FirewallRule("tool.exe", "D:\\apps\\tool.exe")), backend.applyCalls.single())
    }

    // ── ownership ────────────────────────────────────────────────────────────

    @Test
    fun aRuleTwoFeaturesNeedStaysUntilTheLastOneLetsGo() {
        manager.acquire(nuclear, listOf("cmd.exe"))
        manager.acquire(session, listOf("cmd.exe"))

        manager.release(session, listOf("cmd.exe"))
        assertTrue(manager.isBlocked("cmd.exe"))
        assertTrue(backend.removeCalls.isEmpty())

        manager.release(nuclear, listOf("cmd.exe"))
        assertFalse(manager.isBlocked("cmd.exe"))
        assertEquals(listOf(listOf("cmd.exe")), backend.removeCalls)
        assertFalse("cmd.exe" in backend.rules)
    }

    @Test
    fun endingASessionRemovesOnlyWhatTheSessionOwns() {
        manager.acquire(nuclear, listOf("cmd.exe", "taskmgr.exe"))
        manager.acquire(session, listOf("game.exe", "cmd.exe"))
        manager.acquire(user, listOf("chat.exe"))

        manager.releaseOwner(session)

        assertTrue(manager.isBlocked("cmd.exe"), "Nuclear Mode still needs it")
        assertTrue(manager.isBlocked("taskmgr.exe"))
        assertTrue(manager.isBlocked("chat.exe"))
        assertFalse(manager.isBlocked("game.exe"))
        assertEquals(setOf("cmd.exe", "taskmgr.exe", "chat.exe"), backend.rules)
    }

    @Test
    fun releasingARuleNobodyRecordedStillRemovesItFromTheFirewall() {
        manager.release(user, listOf("stray.exe"))

        assertEquals(listOf(listOf("stray.exe")), backend.removeCalls)
    }

    // ── races ────────────────────────────────────────────────────────────────

    @Test
    fun aRuleReleasedWhileItsApplyIsRunningIsNotLeftBlockedOrRecordedActive() {
        backend.onApply = { manager.release(user, listOf("a.exe")) }

        manager.acquire(user, listOf("a.exe"))

        assertFalse(manager.isBlocked("a.exe"), "state must not say blocked")
        assertFalse("a.exe" in backend.rules, "the firewall must not keep a rule nobody owns")
        assertEquals(0, manager.activeCount())
    }

    @Test
    fun aRuleStillWantedByAnotherOwnerSurvivesTheOtherOwnersReleaseDuringApply() {
        manager.request(session, "a.exe")
        manager.request(nuclear, "a.exe")   // same program, second owner, same queued job
        backend.onApply = { manager.release(session, listOf("a.exe")) }

        manager.processQueued()

        assertTrue(manager.isBlocked("a.exe"))
        assertTrue("a.exe" in backend.rules)
        assertEquals(1, backend.applyCalls.size)
    }

    // ── background requests ──────────────────────────────────────────────────

    @Test
    fun queuedRequestsAreCoalescedIntoBatchedApplies() {
        assertTrue(manager.request(session, "a.exe"))
        assertTrue(manager.request(session, "b.exe"))
        assertTrue(manager.request(session, "c.exe"))
        assertFalse(manager.request(session, "a.exe"), "already queued")

        assertEquals(3, wakes, "the worker is woken once per newly queued program")
        assertEquals(3, manager.queuedCount())
        manager.processQueued()

        assertEquals(listOf(2, 1), backend.applyCalls.map { it.size })
        assertEquals(0, manager.queuedCount())
    }

    @Test
    fun aFailedApplyIsNotRetriedUntilTheBackoffHasPassed() {
        backend.confirm = { false }
        manager.request(session, "a.exe"); manager.processQueued()

        assertFalse(manager.request(session, "a.exe"))
        clock += 59_000L
        assertFalse(manager.request(session, "a.exe"))
        clock += 2_000L
        assertTrue(manager.request(session, "a.exe"))
    }

    @Test
    fun aRequestForAnAlreadyBlockedProgramRecordsTheOwnerButAppliesNothing() {
        manager.acquire(nuclear, listOf("cmd.exe"))

        assertFalse(manager.request(session, "cmd.exe"))
        manager.release(nuclear, listOf("cmd.exe"))

        assertTrue(manager.isBlocked("cmd.exe"), "the session still owns it")
    }

    @Test
    fun releasingAProgramDropsItsQueuedRequest() {
        manager.request(session, "a.exe")

        manager.release(session, listOf("a.exe"))
        manager.processQueued()

        assertTrue(backend.applyCalls.isEmpty())
    }

    @Test
    fun retryPendingRequeuesProgramsWhosePathNowResolves() {
        var found = false
        val m = FirewallRuleManager(backend, resolvePath = { if (found) "C:\\x\\late.exe" else null }, nowMs = { clock })
        m.acquire(session, listOf("late.exe"))
        assertEquals(1, m.pendingCount())

        found = true
        clock += 11_000L
        m.retryPending()
        m.processQueued()

        assertTrue(m.isBlocked("late.exe"))
        assertEquals(0, m.pendingCount())
    }

    // ── sync, removeAll, shutdown ────────────────────────────────────────────

    @Test
    fun rulesFoundInTheFirewallAtStartupAreAdoptedAndAnyonesReleaseRemovesThem() {
        backend.rules += "old.exe"
        backend.readResult = setOf("old.exe")

        manager.syncFromBackend()
        assertTrue(manager.isBlocked("old.exe"))

        manager.releaseOwner(session)
        assertTrue(manager.isBlocked("old.exe"), "a session end must not delete a recovered rule")

        manager.release(user, listOf("old.exe"))
        assertFalse(manager.isBlocked("old.exe"))
    }

    @Test
    fun aFailedFirewallReadDoesNotWipeWhatIsKnown() {
        manager.acquire(user, listOf("a.exe"))
        backend.readResult = null

        manager.syncFromBackend()

        assertTrue(manager.isBlocked("a.exe"))
    }

    @Test
    fun aSuccessfulReadDropsRulesThatNoLongerExist() {
        manager.acquire(user, listOf("a.exe"))
        backend.readResult = emptySet()

        manager.syncFromBackend()

        assertFalse(manager.isBlocked("a.exe"))
    }

    @Test
    fun removeAllClearsEveryRuleAndEveryPieceOfState() {
        manager.acquire(user, listOf("a.exe"))
        manager.request(session, "b.exe")

        manager.removeAll()

        assertEquals(1, backend.removeAllCalls)
        assertEquals(0, manager.activeCount())
        assertEquals(0, manager.queuedCount())
        assertEquals(0, manager.pendingCount())
    }

    @Test
    fun afterShutdownNothingIsQueuedOrAppliedAndTheBackendIsClosed() {
        manager.request(session, "a.exe")

        manager.shutdown()

        assertEquals(1, backend.closeCalls)
        assertEquals(0, manager.queuedCount())
        assertFalse(manager.request(session, "b.exe"))
        assertTrue(manager.acquire(user, listOf("c.exe")).isEmpty())
        assertTrue(backend.applyCalls.isEmpty())
    }

    @Test
    fun aBackendThatThrowsIsTreatedAsNothingConfirmed() {
        backend.onApply = { error("powershell blew up") }

        val confirmed = manager.acquire(user, listOf("a.exe"))

        assertTrue(confirmed.isEmpty())
        assertFalse(manager.isBlocked("a.exe"))
    }
}
