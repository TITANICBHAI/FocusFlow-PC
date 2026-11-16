package com.focusflow.enforcement

import com.focusflow.services.ProcessSnapshotEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class EscapeTargetSelectorTest {
    private val ownPid = 100L
    private val escapeNames = setOf("powershell.exe", "cmd.exe", "taskmgr.exe", "msiexec.exe")
    private val pathSuffixes = setOf("\\windows\\system32\\taskmgr.exe", "\\windows\\system32\\cmd.exe")
    private val ps = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"

    private fun select(
        snapshot: List<ProcessSnapshotEntry>,
        blockable: (String) -> Boolean = { true },
        trusted: (ProcessSnapshotEntry, String?) -> Boolean = { _, _ -> false }
    ) = EscapeTargetSelector.select(
        snapshot = snapshot,
        ownPid = ownPid,
        escapeNames = escapeNames,
        escapePathSuffixes = pathSuffixes,
        resolvePath = { it.executablePath },
        isNameBlockable = blockable,
        isTrusted = trusted
    )

    private fun entry(pid: Long, name: String, path: String? = null, parent: Long? = 1L) =
        ProcessSnapshotEntry(pid = pid, parentPid = parent, processName = name, executablePath = path)

    @Test
    fun aTrustedHelperIsSparedWhileAUserLaunchedInstanceOfTheSameToolIsStillTargeted() {
        val helper = entry(200L, "powershell.exe", ps, parent = ownPid)
        val userShell = entry(201L, "powershell.exe", ps, parent = 555L)

        val targets = select(listOf(helper, userShell), trusted = { e, _ -> e.pid == 200L })

        assertEquals(mapOf(201L to "powershell.exe"), targets)
    }

    @Test
    fun theTrustDecisionReceivesTheResolvedImagePath() {
        var seenPath: String? = null
        select(listOf(entry(200L, "powershell.exe", ps))) { _, path -> seenPath = path; true }

        assertEquals(ps, seenPath)
    }

    @Test
    fun withoutTrustEveryMatchingProcessIsTargetedByItsOwnPid() {
        val targets = select(listOf(entry(200L, "powershell.exe", ps), entry(201L, "CMD.EXE"), entry(202L, "notepad.exe")))

        assertEquals(mapOf(200L to "powershell.exe", 201L to "cmd.exe"), targets)
    }

    @Test
    fun theEnforcingProcessIsNeverATarget() {
        assertEquals(emptyMap(), select(listOf(entry(ownPid, "powershell.exe", ps))))
    }

    @Test
    fun aRenamedCopyIsCaughtByItsKnownSystemPath() {
        val renamed = entry(300L, "notmalware.exe", "C:\\Windows\\System32\\taskmgr.exe")

        // Reported under the running filename, which is what a kill has to target.
        assertEquals(mapOf(300L to "notmalware.exe"), select(listOf(renamed)))
    }

    @Test
    fun aProcessWithOnlyAPathIsNamedFromThePath() {
        val pathOnly = entry(301L, "", "C:\\Windows\\System32\\cmd.exe")

        assertEquals(mapOf(301L to "cmd.exe"), select(listOf(pathOnly)))
    }

    @Test
    fun anInstallerThatMayNotBeBlockedIsLeftAlone() {
        val installer = entry(400L, "msiexec.exe", "C:\\Windows\\System32\\msiexec.exe")

        assertEquals(emptyMap(), select(listOf(installer), blockable = { it != "msiexec.exe" }))
    }

    @Test
    fun aProcessWithNeitherNameNorPathIsIgnored() {
        assertEquals(emptyMap(), select(listOf(entry(500L, "", null))))
    }
}
