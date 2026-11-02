package com.focusflow.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchdogScriptTest {
    private val exe = "C:\\Program Files\\FocusFlow\\FocusFlow.exe"

    @Test
    fun scriptComparesTheProcessPathWithTheRealExecutable() {
        val script = WatchdogScript.script(exe)
        assertTrue(script.contains("\$exe = 'C:\\Program Files\\FocusFlow\\FocusFlow.exe'"))
        assertTrue(script.contains("\$path -ieq \$exe"))
        assertTrue(script.contains("Start-Process -FilePath \$exe"))
    }

    @Test
    fun anUnreadablePathCountsAsRunningSoNoSecondCopyIsStarted() {
        assertTrue(WatchdogScript.script(exe).contains("[string]::IsNullOrEmpty(\$path)"))
    }

    @Test
    fun singleQuotesInThePathAreEscaped() {
        val script = WatchdogScript.script("C:\\Users\\O'Neil\\FocusFlow\\FocusFlow.exe")
        assertTrue(script.contains("'C:\\Users\\O''Neil\\FocusFlow\\FocusFlow.exe'"))
    }

    @Test
    fun legacyCommandKeepsTheOldNameOnlyCheck() {
        val command = WatchdogScript.legacyTaskCommand(exe)
        assertTrue(command.contains("Get-Process -Name 'FocusFlow'"))
        assertFalse(command.contains("-ieq"))
    }

    @Test
    fun pathCheckIsUsedOnlyWhenEverythingLooksRight() {
        val command = WatchdogScript.taskCommandForScript("C:\\Users\\me\\AppData\\Roaming\\FocusFlow\\watchdog.ps1")
        assertTrue(WatchdogScript.shouldUsePathCheck(exe, true, true, command))
        assertFalse(WatchdogScript.shouldUsePathCheck(exe, true, false, command), "Store/MSIX keeps the old check")
        assertFalse(WatchdogScript.shouldUsePathCheck(exe, false, true, command), "missing exe")
        assertFalse(WatchdogScript.shouldUsePathCheck("C:\\jdk\\bin\\java.exe", true, true, command), "dev run")
        assertFalse(WatchdogScript.shouldUsePathCheck(exe, true, true, "x".repeat(WatchdogScript.MAX_TASK_COMMAND_LENGTH + 1)))
        assertEquals(260, WatchdogScript.MAX_TASK_COMMAND_LENGTH)
    }
}
