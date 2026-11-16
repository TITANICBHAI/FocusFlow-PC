package com.focusflow.services

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FocusFlow's own helper processes (a firewall PowerShell run, taskkill, a scheduled-task run)
 * must survive the kiosk sweep, while the same programs started by a person must not.
 */
class LauncherKioskTrustedHelperTest {
    private val ownPid = 1L
    private val systemRoot = "C:\\Windows"
    private val ps = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"

    private fun powerShell(pid: Long, parent: Long) = ProcessSnapshotEntry(
        pid = pid,
        parentPid = parent,
        creationTimeMs = 100L + pid,
        processName = "powershell.exe",
        executablePath = ps
    )

    private fun decide(
        process: ProcessSnapshotEntry,
        trusted: ((ProcessSnapshotEntry, String?) -> Boolean)? = null,
        chainAllowance: Boolean = false
    ): LauncherKioskPolicy.Decision {
        val snapshot = listOf(process)
        return if (trusted == null) {
            LauncherKioskPolicy.decide(
                process = process,
                snapshot = snapshot,
                allowedProcessNames = setOf("steam.exe"),
                allowedExecutablePaths = emptyMap(),
                safeProcessNames = setOf("explorer.exe"),
                ownPid = ownPid,
                systemRoot = systemRoot,
                chainAllowanceEnabled = chainAllowance
            )
        } else {
            LauncherKioskPolicy.decide(
                process = process,
                snapshot = snapshot,
                allowedProcessNames = setOf("steam.exe"),
                allowedExecutablePaths = emptyMap(),
                safeProcessNames = setOf("explorer.exe"),
                ownPid = ownPid,
                systemRoot = systemRoot,
                chainAllowanceEnabled = chainAllowance,
                isTrustedHelper = trusted
            )
        }
    }

    @Test
    fun aPowerShellThatNobodyVouchesForIsBlocked() {
        assertEquals(LauncherKioskPolicy.Decision.BLOCK, decide(powerShell(50L, parent = ownPid)))
    }

    @Test
    fun aTrustedHelperIsAllowedEvenWhenItIsAnEscapeToolAndChainAllowanceIsOff() {
        val helper = powerShell(50L, parent = ownPid)

        assertEquals(
            LauncherKioskPolicy.Decision.ALLOW,
            decide(helper, trusted = { process, _ -> process.pid == 50L })
        )
    }

    @Test
    fun aPersonsPowerShellIsStillBlockedWhileAHelperIsTrusted() {
        val persons = powerShell(51L, parent = 777L)

        assertEquals(
            LauncherKioskPolicy.Decision.BLOCK,
            decide(persons, trusted = { process, _ -> process.pid == 50L })
        )
    }

    @Test
    fun theTrustCheckSeesTheResolvedImagePath() {
        var seen: String? = null
        decide(powerShell(50L, parent = ownPid), trusted = { _, path -> seen = path; true })

        assertEquals(ps, seen)
    }
}
