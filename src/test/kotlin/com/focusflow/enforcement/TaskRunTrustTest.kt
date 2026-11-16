package com.focusflow.enforcement

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskRunTrustTest {
    private val systemRoot = "C:\\Windows"
    private val powerShell = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"
    private val conhost = "C:\\Windows\\System32\\conhost.exe"
    private val svchost = "C:\\Windows\\System32\\svchost.exe"
    private val script = "C:\\Users\\me\\.focusflow-tasks\\watchdog.ps1"
    private val scriptHash = "abc123"
    private val scriptArgs = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"$script\""
    private val legacyArgs = "-NoProfile -NonInteractive -Command \"if (-not (Get-Process -Name 'FocusFlow')) { Start-Process 'C:\\F\\FocusFlow.exe' }\""

    private var fileHash: String? = scriptHash

    private fun trust(vararg expectations: TaskRunExpectation) = TaskRunTrust(
        systemRoot = systemRoot,
        expectations = { expectations.toList() },
        fileSha256 = { path -> if (path.equals(script, ignoreCase = true)) fileHash else null }
    )

    private val scriptRun = TaskRunExpectation(arguments = scriptArgs, scriptPath = script, scriptSha256 = scriptHash)
    private val legacyRun = TaskRunExpectation(arguments = legacyArgs, scriptPath = null, scriptSha256 = null)

    private fun facts(
        image: String? = powerShell,
        commandLine: String? = "\"$powerShell\" $scriptArgs",
        parent: String? = conhost
    ) = TaskRunFacts(imagePath = image, commandLine = commandLine, parentImagePath = parent)

    @Test
    fun aHeadlessWatchdogRunWithTheUnchangedScriptIsTrusted() {
        assertTrue(trust(scriptRun).isTrusted(facts()))
    }

    @Test
    fun anUnquotedProgramNameInTheCommandLineIsAccepted() {
        assertTrue(trust(scriptRun).isTrusted(facts(commandLine = "$powerShell $scriptArgs")))
    }

    @Test
    fun theHiddenWindowVariantStartedByTheTaskSchedulerServiceIsTrusted() {
        val hidden = TaskRunExpectation("-WindowStyle Hidden $scriptArgs", script, scriptHash)
        assertTrue(
            trust(hidden).isTrusted(
                facts(commandLine = "$powerShell -WindowStyle Hidden $scriptArgs", parent = svchost)
            )
        )
    }

    @Test
    fun theLegacyNameOnlyCheckIsTrustedByItsExactCommand() {
        assertTrue(trust(legacyRun).isTrusted(facts(commandLine = "$powerShell $legacyArgs")))
    }

    @Test
    fun caseAndRepeatedSpacesDoNotMatter() {
        val sloppy = "\"${powerShell.uppercase()}\"   -noprofile  -NONINTERACTIVE -executionpolicy bypass -file \"${script.uppercase()}\""
        assertTrue(trust(scriptRun).isTrusted(facts(image = powerShell.uppercase(), commandLine = sloppy)))
    }

    @Test
    fun aCopyOfPowerShellOutsideTheWindowsFolderIsNotTrusted() {
        assertFalse(trust(scriptRun).isTrusted(facts(image = "C:\\Users\\me\\Downloads\\powershell.exe")))
    }

    @Test
    fun anExtraArgumentMakesTheRunUntrusted() {
        assertFalse(trust(scriptRun).isTrusted(facts(commandLine = "$powerShell $scriptArgs; calc")))
        assertFalse(trust(scriptRun).isTrusted(facts(commandLine = "$powerShell $scriptArgs -Command evil")))
    }

    @Test
    fun anotherScriptPathIsNotTrusted() {
        val other = "\"$powerShell\" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"C:\\Users\\me\\escape.ps1\""
        assertFalse(trust(scriptRun).isTrusted(facts(commandLine = other)))
    }

    @Test
    fun aScriptWhoseContentChangedIsNotTrusted() {
        fileHash = "tampered"
        assertFalse(trust(scriptRun).isTrusted(facts()))
    }

    @Test
    fun anUnreadableScriptFailsClosed() {
        fileHash = null
        assertFalse(trust(scriptRun).isTrusted(facts()))
    }

    @Test
    fun aRunStartedByAnythingButTheConsoleHostOrTaskSchedulerIsNotTrusted() {
        assertFalse(trust(scriptRun).isTrusted(facts(parent = "C:\\Windows\\explorer.exe")))
        assertFalse(trust(scriptRun).isTrusted(facts(parent = "C:\\Windows\\System32\\cmd.exe")))
        assertFalse(trust(scriptRun).isTrusted(facts(parent = powerShell)))
        assertFalse(trust(scriptRun).isTrusted(facts(parent = "C:\\Users\\me\\conhost.exe")))
    }

    @Test
    fun missingFactsFailClosed() {
        assertFalse(trust(scriptRun).isTrusted(facts(image = null)))
        assertFalse(trust(scriptRun).isTrusted(facts(commandLine = null)))
        assertFalse(trust(scriptRun).isTrusted(facts(parent = null)))
    }

    @Test
    fun withoutAWindowsFolderNothingIsTrusted() {
        val noRoot = TaskRunTrust(null, { listOf(scriptRun) }, { scriptHash })
        assertFalse(noRoot.isTrusted(facts()))
    }

    @Test
    fun withoutAnyExpectationNothingIsTrusted() {
        assertFalse(trust().isTrusted(facts()))
    }

    @Test
    fun sha256HexMatchesAKnownVector() {
        assertTrue(
            TaskRunTrust.sha256Hex("abc".toByteArray()) ==
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        )
    }
}
