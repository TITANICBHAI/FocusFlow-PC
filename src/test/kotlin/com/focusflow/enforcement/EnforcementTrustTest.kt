package com.focusflow.enforcement

import com.focusflow.services.ProcessSnapshotEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnforcementTrustTest {
    private val ownPid = 100L
    private val systemRoot = "C:\\Windows"
    private val ps = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"
    private val conhost = "C:\\Windows\\System32\\conhost.exe"
    private val script = "C:\\Users\\me\\.focusflow-tasks\\watchdog.ps1"
    private val args = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"$script\""

    private val registry = OwnedProcessRegistry(ownPid)
    private val taskRuns = TaskRunTrust(
        systemRoot = systemRoot,
        expectations = { listOf(TaskRunExpectation(args, script, "hash")) },
        fileSha256 = { "hash" }
    )

    private fun entry(pid: Long, name: String, path: String, parent: Long?) =
        ProcessSnapshotEntry(pid = pid, parentPid = parent, creationTimeMs = 1_000L + pid, processName = name, executablePath = path)

    private val conhostEntry = entry(10L, "conhost.exe", conhost, parent = 4L)
    private val taskPowerShell = entry(11L, "powershell.exe", ps, parent = 10L)

    private fun check(
        snapshot: List<ProcessSnapshotEntry>,
        commandLineOf: (Long) -> String? = { "\"$ps\" $args" }
    ) = EnforcementTrust.helperCheck(
        snapshot = snapshot,
        registry = registry,
        taskRunTrust = taskRuns,
        commandLineOf = commandLineOf,
        imageOf = { it.executablePath }
    )

    @Test
    fun aHelperFocusFlowStartedIsTrustedWithoutReadingAnyCommandLine() {
        val helper = entry(20L, "powershell.exe", ps, parent = ownPid)
        registry.completeSpawn(registry.beginSpawn(ps), helper.pid, helper.creationTimeMs)

        val trusted = check(listOf(helper)) { error("the command line must not be read for an owned helper") }

        assertTrue(trusted(helper, ps))
    }

    @Test
    fun theCommandLineOfOneProcessIsReadOnceNoMatterHowOftenItIsAskedAbout() {
        var reads = 0
        val trusted = check(listOf(conhostEntry, taskPowerShell)) { reads++; "\"$ps\" $args" }

        assertTrue(trusted(taskPowerShell, ps))
        assertTrue(trusted(taskPowerShell, ps))
        assertTrue(trusted(taskPowerShell, ps))

        assertEquals(1, reads)
    }

    @Test
    fun aScheduledTaskRunThatMatchesEveryCheckIsTrusted() {
        assertTrue(check(listOf(conhostEntry, taskPowerShell))(taskPowerShell, ps))
    }

    @Test
    fun aPersonsPowerShellIsNotTrustedEvenWhenAHelperExists() {
        val helper = entry(20L, "powershell.exe", ps, parent = ownPid)
        registry.completeSpawn(registry.beginSpawn(ps), helper.pid, helper.creationTimeMs)
        val persons = entry(30L, "powershell.exe", ps, parent = 555L)
        val explorer = entry(555L, "explorer.exe", "C:\\Windows\\explorer.exe", parent = 4L)

        val trusted = check(listOf(helper, persons, explorer)) { "\"$ps\"" }

        assertFalse(trusted(persons, ps))
    }

    @Test
    fun aPowerShellWithTheTaskArgumentsButStartedFromAShellIsNotTrusted() {
        val shell = entry(40L, "cmd.exe", "C:\\Windows\\System32\\cmd.exe", parent = 4L)
        val fromShell = entry(41L, "powershell.exe", ps, parent = shell.pid)

        assertFalse(check(listOf(shell, fromShell))(fromShell, ps))
    }

    @Test
    fun aRunWhoseCommandLineCannotBeReadIsNotTrusted() {
        assertFalse(check(listOf(conhostEntry, taskPowerShell), commandLineOf = { null })(taskPowerShell, ps))
    }

    @Test
    fun aRunWhoseParentIsGoneIsNotTrusted() {
        assertFalse(check(listOf(taskPowerShell))(taskPowerShell, ps))
    }

    @Test
    fun otherProgramsNeverCostACommandLineLookup() {
        val cmd = entry(50L, "cmd.exe", "C:\\Windows\\System32\\cmd.exe", parent = 10L)
        val trusted = check(listOf(conhostEntry, cmd)) { error("only PowerShell can be a task run") }

        assertFalse(trusted(cmd, cmd.executablePath))
    }

    @Test
    fun aProcessWithAnUnknownImageIsNotTrusted() {
        assertEquals(false, check(listOf(conhostEntry, taskPowerShell))(taskPowerShell, null))
    }
}
