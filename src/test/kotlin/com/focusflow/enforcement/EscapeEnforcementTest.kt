package com.focusflow.enforcement

import com.focusflow.services.ProcessSnapshotEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EscapeEnforcementTest {
    private val ownPid = 100L
    private val ps = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"
    private val escapeNames = setOf("powershell.exe", "cmd.exe")
    private val suffixes = setOf("\\windows\\system32\\cmd.exe")

    private fun entry(pid: Long, name: String, path: String? = null, created: Long = 1_000L + pid) =
        ProcessSnapshotEntry(pid = pid, parentPid = 4L, creationTimeMs = created, processName = name, executablePath = path)

    private fun plan(
        snapshot: List<ProcessSnapshotEntry>,
        trusted: Set<Long> = emptySet(),
        launcherBlocks: ((ProcessSnapshotEntry) -> Boolean)? = null
    ) = EscapeEnforcement.plan(
        snapshot = snapshot,
        ownPid = ownPid,
        escapeNames = escapeNames,
        escapePathSuffixes = suffixes,
        resolvePath = { it.executablePath },
        isNameBlockable = { true },
        isTrusted = { process, _ -> process.pid in trusted },
        launcherBlocks = launcherBlocks
    )

    // ── planning ─────────────────────────────────────────────────────────────

    @Test
    fun withoutALauncherEveryUntrustedEscapeProcessIsATarget() {
        val persons = entry(30L, "powershell.exe", ps)
        val own = entry(31L, "powershell.exe", ps)

        assertEquals(mapOf(30L to "powershell.exe"), plan(listOf(persons, own), trusted = setOf(31L)))
    }

    @Test
    fun theLauncherPolicyCanSpareAnEscapeProcessThatIsPartOfAnAllowedChain() {
        val chained = entry(30L, "cmd.exe", "C:\\Windows\\System32\\cmd.exe")
        val loose = entry(31L, "cmd.exe", "C:\\Windows\\System32\\cmd.exe")

        val targets = plan(listOf(chained, loose), launcherBlocks = { it.pid != 30L })

        assertEquals(mapOf(31L to "cmd.exe"), targets)
    }

    @Test
    fun theLauncherPolicyIsNeverAskedAboutAProcessThatIsNotAnEscapeTool() {
        val asked = mutableListOf<Long>()
        plan(listOf(entry(30L, "notepad.exe", "C:\\Windows\\notepad.exe"), entry(31L, "cmd.exe")),
            launcherBlocks = { asked += it.pid; true })

        assertEquals(listOf(31L), asked)
    }

    // ── killing ──────────────────────────────────────────────────────────────

    private class Recorder(private val alive: (Long) -> Boolean? = { true }, private val batchResult: Boolean = true) {
        val batches = mutableListOf<List<Long>>()
        val single = mutableListOf<Long>()
        fun run(targets: Map<Long, String>, snapshot: List<ProcessSnapshotEntry>) = EscapeEnforcement.kill(
            targets = targets,
            snapshot = snapshot,
            isAlive = { pid, _ -> alive(pid) },
            killBatch = { batches += it.toList(); batchResult },
            killOne = { single += it }
        )
    }

    private val a = entry(30L, "powershell.exe", ps)
    private val b = entry(31L, "cmd.exe", "C:\\Windows\\System32\\cmd.exe")

    @Test
    fun allTargetsAreEndedWithOneBatchedCall() {
        val recorder = Recorder()

        val ended = recorder.run(mapOf(30L to "powershell.exe", 31L to "cmd.exe"), listOf(a, b))

        assertEquals(listOf(listOf(30L, 31L)), recorder.batches)
        assertTrue(recorder.single.isEmpty())
        assertEquals(mapOf(30L to "powershell.exe", 31L to "cmd.exe"), ended)
    }

    @Test
    fun aProcessThatIsAlreadyGoneOrWhoseIdWasReusedIsNotKilled() {
        val recorder = Recorder(alive = { pid -> pid != 30L })

        val ended = recorder.run(mapOf(30L to "powershell.exe", 31L to "cmd.exe"), listOf(a, b))

        assertEquals(listOf(listOf(31L)), recorder.batches)
        assertEquals(mapOf(31L to "cmd.exe"), ended)
    }

    @Test
    fun aProcessWhoseLivenessCannotBeReadIsStillKilled() {
        val recorder = Recorder(alive = { null })

        recorder.run(mapOf(30L to "powershell.exe"), listOf(a))

        assertEquals(listOf(listOf(30L)), recorder.batches)
    }

    @Test
    fun nothingAliveMeansNoCommandIsRun() {
        val recorder = Recorder(alive = { false })

        val ended = recorder.run(mapOf(30L to "powershell.exe"), listOf(a))

        assertTrue(recorder.batches.isEmpty())
        assertTrue(ended.isEmpty())
    }

    @Test
    fun whenTheBatchFailsEachStillRunningProcessIsEndedDirectly() {
        var calls = 0
        // alive for the first check of each process, but 30 has gone by the time of the fallback
        val recorder = Recorder(alive = { pid -> !(pid == 30L && calls++ >= 1) }, batchResult = false)

        recorder.run(mapOf(30L to "powershell.exe", 31L to "cmd.exe"), listOf(a, b))

        assertEquals(listOf(31L), recorder.single)
    }

    @Test
    fun aDirectKillThatThrowsDoesNotStopTheRest() {
        val singles = mutableListOf<Long>()

        EscapeEnforcement.kill(
            targets = mapOf(30L to "powershell.exe", 31L to "cmd.exe"),
            snapshot = listOf(a, b),
            isAlive = { _, _ -> true },
            killBatch = { false },
            killOne = { pid -> singles += pid; if (pid == 30L) error("access denied") }
        )

        assertEquals(listOf(30L, 31L), singles)
    }
}
