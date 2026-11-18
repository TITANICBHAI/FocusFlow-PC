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
        var readResult: Map<String, String?>? = emptyMap()

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
        override fun readActive(): Map<String, String?>? = readResult
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
        recheckIntervalMs = 600_000L,
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

    // ── the rule must keep pointing at the program that is really there ─────

    private val recheckDue = 600_001L

    private fun appliedPaths() = backend.applyCalls.flatten().map { it.exePath }

    @Test
    fun aRuleIsAppliedAgainWhenTheProgramMovedAndTheRecheckIsDue() {
        paths["app.exe"] = "C:\\old\\app.exe"
        manager.acquire(user, listOf("app.exe"))
        paths["app.exe"] = "C:\\new\\app.exe"
        clock += recheckDue

        manager.acquire(user, listOf("app.exe"))

        assertEquals(listOf("C:\\old\\app.exe", "C:\\new\\app.exe"), appliedPaths())
        assertTrue(manager.isBlocked("app.exe"))
    }

    @Test
    fun aRecheckThatFindsTheSamePathAppliesNothingAndPathsCompareWithoutCaseOrSlashes() {
        paths["app.exe"] = "C:\\Old\\App.exe"
        manager.acquire(user, listOf("app.exe"))
        paths["app.exe"] = "c:/old/app.exe"
        clock += recheckDue

        manager.acquire(user, listOf("app.exe"))

        assertEquals(1, backend.applyCalls.size)
    }

    @Test
    fun beforeTheRecheckIsDueThePathIsNotEvenLookedUp() {
        var lookups = 0
        val counting = FirewallRuleManager(
            backend, resolvePath = { lookups++; "C:\\x\\a.exe" }, nowMs = { clock }, recheckIntervalMs = 600_000L
        )
        counting.acquire(user, listOf("a.exe"))

        counting.acquire(nuclear, listOf("a.exe"))
        assertFalse(counting.request(session, "a.exe"), "the busy path must stay cheap")

        assertEquals(1, lookups)
    }

    @Test
    fun aRequestForABlockedProgramWhoseRecheckIsDueIsQueuedAndTheWorkerVerifiesIt() {
        paths["app.exe"] = "C:\\old\\app.exe"
        manager.acquire(user, listOf("app.exe"))
        paths["app.exe"] = "C:\\new\\app.exe"
        clock += recheckDue

        assertTrue(manager.request(session, "app.exe"))
        manager.processQueued()

        assertEquals(listOf("C:\\old\\app.exe", "C:\\new\\app.exe"), appliedPaths())
    }

    @Test
    fun ifTheReapplyIsNotConfirmedTheProgramIsNotClaimedAsBlockedBecauseTheOldRuleWasReplaced() {
        paths["app.exe"] = "C:\\old\\app.exe"
        manager.acquire(user, listOf("app.exe"))
        paths["app.exe"] = "C:\\new\\app.exe"
        backend.confirm = { false }
        clock += recheckDue

        manager.acquire(user, listOf("app.exe"))

        assertFalse(manager.isBlocked("app.exe"))
    }

    @Test
    fun aRecoveredRuleWithAStalePathIsAppliedAgainOnFirstUse() {
        backend.readResult = mapOf("app.exe" to "C:\\old\\app.exe")
        manager.syncFromBackend()
        paths["app.exe"] = "C:\\new\\app.exe"

        manager.acquire(nuclear, listOf("app.exe"))

        assertEquals(listOf("C:\\new\\app.exe"), appliedPaths())
    }

    @Test
    fun aRecoveredRuleWhosePathMatchesIsLeftAlone() {
        backend.readResult = mapOf("app.exe" to "C:\\Windows\\System32\\app.exe")
        manager.syncFromBackend()

        manager.acquire(nuclear, listOf("app.exe"))

        assertTrue(backend.applyCalls.isEmpty())
        assertTrue(manager.isBlocked("app.exe"))
    }

    @Test
    fun aRecoveredRuleWhosePathCouldNotBeReadIsAppliedAgainSoItCanBeTrusted() {
        backend.readResult = mapOf("app.exe" to null)
        manager.syncFromBackend()

        manager.acquire(nuclear, listOf("app.exe"))

        assertEquals(1, backend.applyCalls.size)
    }

    @Test
    fun aProgramThatCannotBeFoundDuringARecheckKeepsItsRule() {
        var found = true
        val m = FirewallRuleManager(
            backend, resolvePath = { if (found) "C:\\old\\app.exe" else null },
            nowMs = { clock }, recheckIntervalMs = 600_000L
        )
        m.acquire(user, listOf("app.exe"))
        found = false
        clock += recheckDue

        m.acquire(user, listOf("app.exe"))

        assertTrue(m.isBlocked("app.exe"), "an unfindable program must not drop a working rule")
        assertEquals(1, backend.applyCalls.size)
    }

    // ── sync, removeAll, shutdown ────────────────────────────────────────────

    @Test
    fun rulesFoundInTheFirewallAtStartupAreAdoptedAndAnyonesReleaseRemovesThem() {
        backend.rules += "old.exe"
        backend.readResult = mapOf("old.exe" to "C:\\Windows\\System32\\old.exe")

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
        backend.readResult = emptyMap()

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
