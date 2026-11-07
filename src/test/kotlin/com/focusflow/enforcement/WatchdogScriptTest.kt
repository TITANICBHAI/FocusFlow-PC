package com.focusflow.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchdogScriptTest {
    private val exe = "C:\\Program Files\\FocusFlow\\FocusFlow.exe"
    private val scriptPath = "C:\\Users\\me\\.focusflow\\watchdog.ps1"
    private val systemRoot = "C:\\Windows"
    private val conhost = "C:\\Windows\\System32\\conhost.exe"
    private val powerShell = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"

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
        val command = WatchdogScript.taskCommandForScript("C:\\Users\\me\\.focusflow\\watchdog.ps1")
        assertTrue(WatchdogScript.shouldUsePathCheck(exe, true, true, command))
        assertFalse(WatchdogScript.shouldUsePathCheck(exe, true, false, command), "Store/MSIX keeps the old check")
        assertFalse(WatchdogScript.shouldUsePathCheck(exe, false, true, command), "missing exe")
        assertFalse(WatchdogScript.shouldUsePathCheck("C:\\jdk\\bin\\java.exe", true, true, command), "dev run")
        assertFalse(WatchdogScript.shouldUsePathCheck(exe, true, true, "x".repeat(WatchdogScript.MAX_TASK_COMMAND_LENGTH + 1)))
        assertEquals(260, WatchdogScript.MAX_TASK_COMMAND_LENGTH)
    }

    // --- no console flash --------------------------------------------------------------------

    @Test
    fun headlessHostNeedsWindows10Build1809OrNewer() {
        assertEquals(conhost, WatchdogScript.headlessHost(systemRoot, 22631) { true })
        assertEquals(conhost, WatchdogScript.headlessHost(systemRoot, WatchdogScript.MIN_HEADLESS_CONHOST_BUILD) { true })
        assertNull(WatchdogScript.headlessHost(systemRoot, WatchdogScript.MIN_HEADLESS_CONHOST_BUILD - 1) { true }, "Windows 10 1803")
        assertNull(WatchdogScript.headlessHost(systemRoot, null) { true }, "unknown build")
    }

    @Test
    fun headlessHostIsNullWhenConhostOrSystemRootIsMissing() {
        assertNull(WatchdogScript.headlessHost(systemRoot, 22631) { false }, "conhost.exe not on disk")
        assertNull(WatchdogScript.headlessHost(null, 22631) { true })
        assertNull(WatchdogScript.headlessHost("  ", 22631) { true })
    }

    @Test
    fun headlessHostToleratesATrailingSlashOnTheSystemRoot() {
        assertEquals(conhost, WatchdogScript.headlessHost("C:\\Windows\\", 22631) { true })
    }

    @Test
    fun headlessActionRunsPowerShellInsideConhostWithoutAWindow() {
        val action = WatchdogScript.scriptAction(scriptPath, systemRoot, conhost)
        assertEquals(conhost, action.command)
        assertTrue(action.arguments.startsWith("--headless $powerShell "), action.arguments)
        assertTrue(action.arguments.contains("-File \"$scriptPath\""))
        assertFalse(action.arguments.contains("-WindowStyle"), "nothing to hide: there is no window")
    }

    @Test
    fun withoutAHeadlessHostItFallsBackToHiddenPowerShell() {
        val action = WatchdogScript.scriptAction(scriptPath, systemRoot, null)
        assertEquals(powerShell, action.command)
        assertTrue(action.arguments.startsWith("-WindowStyle Hidden "), action.arguments)
        assertTrue(action.arguments.contains("-File \"$scriptPath\""))
    }

    @Test
    fun everyActionSkipsTheProfileAndNeverPrompts() {
        listOf(conhost, null).forEach { host ->
            listOf(
                WatchdogScript.scriptAction(scriptPath, systemRoot, host),
                WatchdogScript.legacyAction(exe, systemRoot, host)
            ).forEach { action ->
                assertTrue(action.arguments.contains("-NoProfile"), action.arguments)
                assertTrue(action.arguments.contains("-NonInteractive"), action.arguments)
            }
        }
    }

    @Test
    fun legacyActionKeepsTheNameOnlyCheckAndStartsTheRealExe() {
        val action = WatchdogScript.legacyAction(exe, systemRoot, conhost)
        assertTrue(action.arguments.contains("Get-Process -Name 'FocusFlow'"))
        assertTrue(action.arguments.contains("Start-Process 'C:\\Program Files\\FocusFlow\\FocusFlow.exe'"))
        assertFalse(action.arguments.contains("-ieq"))
    }

    @Test
    fun missingSystemRootFallsBackToBarePowerShell() {
        assertEquals("powershell.exe", WatchdogScript.powerShellExe(null))
        assertEquals("powershell.exe", WatchdogScript.powerShellExe(""))
        assertEquals(powerShell, WatchdogScript.powerShellExe("C:\\Windows\\"))
    }

    @Test
    fun commandLineQuotesAProgramPathOnlyWhenItHasASpace() {
        assertEquals("a.exe -x", WatchdogScript.TaskAction("a.exe", "-x").commandLine)
        assertEquals("\"C:\\Program Files\\a.exe\" -x", WatchdogScript.TaskAction("C:\\Program Files\\a.exe", "-x").commandLine)
    }

    @Test
    fun scriptFilesStartWithAByteOrderMarkSoAccentedProfileNamesSurvive() {
        val text = WatchdogScript.script("C:\\Users\\Jos\u00e9\\AppData\\Local\\FocusFlow\\FocusFlow.exe")
        val bytes = WatchdogScript.scriptBytes(text)

        assertEquals(listOf(0xEF, 0xBB, 0xBF), bytes.take(3).map { it.toInt() and 0xFF })
        assertEquals(text, String(bytes, 3, bytes.size - 3, Charsets.UTF_8))
        // Without the mark, Windows PowerShell 5.1 reads these bytes with the ANSI code page and
        // sees a different name. That is the bug the mark prevents.
        assertTrue(String(text.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1) != text)
    }

    @Test
    fun theFallbackCommandLineIsShortAndStillHeadlessWhenSupported() {
        val plain = WatchdogScript.taskCommandForScript(scriptPath)
        assertTrue(plain.startsWith("powershell.exe -WindowStyle Hidden "), plain)

        val headless = WatchdogScript.taskCommandForScript(scriptPath, headless = true)
        assertTrue(headless.startsWith("conhost.exe --headless powershell.exe "), headless)
        assertTrue(headless.length <= WatchdogScript.MAX_TASK_COMMAND_LENGTH)
    }
}
