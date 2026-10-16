package com.focusflow.services

import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach

class LauncherKioskPolicyTest {
    private val allowedNames = setOf("steam.exe")
    private val allowedPaths = mapOf("steam.exe" to "C:\\Games\\Steam\\steam.exe")
    private val safeNames = setOf("explorer.exe")
    private val ownPid = 1L
    private val systemRoot = "C:\\Windows"

    @BeforeEach
    fun resetPolicyState() {
        LauncherKioskPolicy.resetForTest()
    }

    @Test
    fun allowsLiveLauncherDescendantsAndBlocksUnrelatedProcesses() {
        val root = steam()
        val game = ProcessSnapshotEntry(
            pid = 11L,
            parentPid = root.pid,
            creationTimeMs = 200L,
            processName = "game.exe",
            executablePath = "C:\\Games\\Example\\game.exe"
        )
        val unrelatedParent = ProcessSnapshotEntry(
            pid = 90L,
            parentPid = 0L,
            creationTimeMs = 300L,
            processName = "other-parent.exe",
            executablePath = "C:\\Other\\parent.exe"
        )
        val unrelated = ProcessSnapshotEntry(
            pid = 91L,
            parentPid = unrelatedParent.pid,
            creationTimeMs = 400L,
            processName = "other.exe",
            executablePath = "C:\\Other\\other.exe"
        )
        val snapshot = listOf(root, game, unrelatedParent, unrelated)

        assertEquals(LauncherKioskPolicy.Decision.ALLOW, decide(game, snapshot, enabled = true))
        assertEquals(LauncherKioskPolicy.Decision.BLOCK, decide(unrelated, snapshot, enabled = true))
        assertEquals(LauncherKioskPolicy.Decision.BLOCK, decide(game, snapshot, enabled = false))
    }

    @Test
    fun reusedParentPidCannotMakeAnOlderProcessPartOfTheAllowedChain() {
        val root = steam().copy(creationTimeMs = 500L)
        val olderUnrelatedProcess = ProcessSnapshotEntry(
            pid = 11L,
            parentPid = root.pid,
            creationTimeMs = 400L,
            processName = "other.exe",
            executablePath = "C:\\Other\\other.exe"
        )

        assertEquals(
            LauncherKioskPolicy.Decision.BLOCK,
            decide(olderUnrelatedProcess, listOf(root, olderUnrelatedProcess), enabled = true)
        )
    }

    @Test
    fun escapeToolsRemainBlockedEvenWhenTheyAreInAnAllowedLaunchChain() {
        val root = steam()
        val taskManager = ProcessSnapshotEntry(
            pid = 12L,
            parentPid = root.pid,
            creationTimeMs = 210L,
            processName = "taskmgr.exe",
            executablePath = "C:\\Windows\\System32\\taskmgr.exe"
        )
        val renamedEscapeTool = taskManager.copy(
            pid = 13L,
            processName = "renamed.exe",
            executablePath = "C:\\Windows\\System32\\taskmgr.exe"
        )
        val snapshot = listOf(root, taskManager, renamedEscapeTool)

        assertEquals(LauncherKioskPolicy.Decision.BLOCK, decide(taskManager, snapshot, enabled = true))
        assertEquals(LauncherKioskPolicy.Decision.BLOCK, decide(renamedEscapeTool, snapshot, enabled = true))
    }

    @Test
    fun onlyFocusFlowDirectChildSystemUtilitiesReceiveTheNuclearExemption() {
        val root = steam()
        val ownHelper = ProcessSnapshotEntry(
            pid = 20L,
            parentPid = ownPid,
            creationTimeMs = 220L,
            processName = "cmd.exe",
            executablePath = "C:\\Windows\\System32\\cmd.exe"
        )
        val otherHelper = ownHelper.copy(pid = 21L, parentPid = 99L)
        val otherParent = ProcessSnapshotEntry(
            pid = 99L,
            parentPid = 0L,
            creationTimeMs = 221L,
            processName = "other.exe",
            executablePath = "C:\\Other\\other.exe"
        )
        val snapshot = listOf(root, ownHelper, otherParent, otherHelper)

        assertEquals(LauncherKioskPolicy.Decision.ALLOW, decide(ownHelper, snapshot, enabled = true))
        assertEquals(LauncherKioskPolicy.Decision.BLOCK, decide(otherHelper, snapshot, enabled = true))
        assertEquals(LauncherKioskPolicy.Decision.BLOCK, decide(ownHelper, snapshot, enabled = false))
    }

    @Test
    fun squirrelUpdaterInLiveAllowedInstallRootIsIncluded() {
        val discord = ProcessSnapshotEntry(
            pid = 30L,
            parentPid = 0L,
            creationTimeMs = 100L,
            processName = "discord.exe",
            executablePath = "C:\\Users\\User\\Discord\\app-1.2.3\\Discord.exe"
        )
        val updater = ProcessSnapshotEntry(
            pid = 31L,
            parentPid = 0L,
            creationTimeMs = 101L,
            processName = "Update.exe",
            executablePath = "C:\\Users\\User\\Discord\\Update.exe"
        )

        assertEquals(
            LauncherKioskPolicy.Decision.ALLOW,
            LauncherKioskPolicy.decide(
                process = updater,
                snapshot = listOf(discord, updater),
                allowedProcessNames = setOf("discord.exe"),
                allowedExecutablePaths = mapOf("discord.exe" to discord.executablePath!!),
                safeProcessNames = safeNames,
                ownPid = ownPid,
                systemRoot = systemRoot,
                chainAllowanceEnabled = true
            )
        )
    }

    @Test
    fun unresolvedLineageWaitsForANewSnapshotThenBlocks() {
        val root = steam()
        val orphan = ProcessSnapshotEntry(
            pid = 41L,
            parentPid = 777L,
            creationTimeMs = 410L,
            processName = "orphan.exe",
            executablePath = "C:\\Other\\orphan.exe"
        )
        val snapshot = listOf(root, orphan)
        LauncherKioskPolicy.onNewSnapshot(snapshot)

        assertEquals(
            LauncherKioskPolicy.Decision.DEFER_ONE_SNAPSHOT,
            decide(orphan, snapshot, enabled = true, snapshotVersion = 1L)
        )
        assertEquals(
            LauncherKioskPolicy.Decision.DEFER_ONE_SNAPSHOT,
            decide(orphan, snapshot, enabled = true, snapshotVersion = 1L)
        )

        LauncherKioskPolicy.onNewSnapshot(snapshot)
        assertEquals(
            LauncherKioskPolicy.Decision.BLOCK,
            decide(orphan, snapshot, enabled = true, snapshotVersion = 2L)
        )
    }

    private fun steam() = ProcessSnapshotEntry(
        pid = 10L,
        parentPid = 2L,
        creationTimeMs = 100L,
        processName = "steam.exe",
        executablePath = allowedPaths.getValue("steam.exe")
    )

    private fun decide(
        process: ProcessSnapshotEntry,
        snapshot: List<ProcessSnapshotEntry>,
        enabled: Boolean,
        snapshotVersion: Long = 1L
    ) = LauncherKioskPolicy.decide(
        process = process,
        snapshot = snapshot,
        allowedProcessNames = allowedNames,
        allowedExecutablePaths = allowedPaths,
        safeProcessNames = safeNames,
        ownPid = ownPid,
        systemRoot = systemRoot,
        chainAllowanceEnabled = enabled,
        snapshotVersion = snapshotVersion
    )
}
