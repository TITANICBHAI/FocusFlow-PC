package com.focusflow.enforcement

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

object PrintHello {
    @JvmStatic fun main(args: Array<String>) { println("hello from child") }
}

object SleepForAMinute {
    @JvmStatic fun main(args: Array<String>) { Thread.sleep(60_000) }
}

class PowerShellFirewallBackendTest {

    private fun javaCommand(main: Class<*>) = listOf(
        File(System.getProperty("java.home"), "bin/java").path,
        "-cp", System.getProperty("java.class.path"), main.name
    )

    private val started = mutableListOf<Process>()

    private fun runner(main: Class<*>) = PowerShellRunner(
        commandFor = { javaCommand(main) },
        start = { builder -> builder.start().also { started += it } }
    )

    @Test
    fun aFinishedRunReportsSuccessAndItsOutput() {
        val result = runner(PrintHello::class.java).run("ignored", timeoutSec = 30)

        assertTrue(result.ok)
        assertFalse(result.timedOut)
        assertTrue("hello from child" in result.output)
    }

    @Test
    fun aRunThatOutlastsItsTimeoutIsKilled() {
        val result = runner(SleepForAMinute::class.java).run("ignored", timeoutSec = 1)

        assertFalse(result.ok)
        assertTrue(result.timedOut)
        assertTrue(started.single().waitFor(5, TimeUnit.SECONDS), "the timed-out process must be dead")
    }

    @Test
    fun cancelEndsARunThatIsInProgress() {
        val r = runner(SleepForAMinute::class.java)
        var result: PsResult? = null
        val worker = thread { result = r.run("ignored", timeoutSec = 60) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (started.isEmpty() && System.nanoTime() < deadline) Thread.sleep(20)

        r.cancel()

        worker.join(10_000)
        assertFalse(worker.isAlive, "the run must return after cancel")
        assertFalse(result!!.ok)
        assertFalse(result!!.timedOut, "a cancel is not a timeout")
        assertTrue(started.single().waitFor(5, TimeUnit.SECONDS))
    }

    @Test
    fun aProgramThatCannotBeStartedIsAFailureNotACrash() {
        val result = PowerShellRunner(commandFor = { listOf("x") }, start = { error("no such file") })
            .run("ignored", timeoutSec = 5)

        assertFalse(result.ok)
        assertFalse(result.timedOut)
    }

    @Test
    fun theDefaultCommandUsesTheAbsolutePowerShellPath() {
        val command = PowerShellRunner.powerShellCommand("Get-Date", "C:\\Windows")

        assertEquals("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", command.first())
        assertEquals("Get-Date", command.last())
        assertTrue("-NonInteractive" in command && "-NoProfile" in command)
    }

    // ── scripts ──────────────────────────────────────────────────────────────

    @Test
    fun theApplyScriptQuotesEveryRuleAndEscapesApostrophes() {
        val script = FirewallScripts.apply(
            listOf(FirewallRule("chrome.exe", "C:\\Users\\O'Brien\\chrome.exe"), FirewallRule("cmd.exe", "C:\\Windows\\System32\\cmd.exe"))
        )

        assertTrue("'FocusFlow_Block_chrome|C:\\Users\\O''Brien\\chrome.exe'" in script)
        assertTrue("'FocusFlow_Block_cmd|C:\\Windows\\System32\\cmd.exe'" in script)
        assertTrue("New-NetFirewallRule" in script && "Get-NetFirewallRule -DisplayName 'FocusFlow_Block_*'" in script)
    }

    @Test
    fun confirmedRulesAreReadBackFromTheOutputOfOurOwnRulesOnly() {
        val output = """
            noise
            FF_OK=FocusFlow_Block_Chrome
            FF_OK= FocusFlow_Block_cmd
            FF_OK=SomeoneElsesRule
        """.trimIndent()

        assertEquals(setOf("chrome.exe", "cmd.exe"), FirewallScripts.parseNames(output, "FF_OK="))
    }

    @Test
    fun theRemoveScriptNamesEveryRuleAndEscapes() {
        val script = FirewallScripts.remove(listOf("chrome.exe", "o'neil.exe"))

        assertTrue("'FocusFlow_Block_chrome'" in script && "'FocusFlow_Block_o''neil'" in script)
    }
}
