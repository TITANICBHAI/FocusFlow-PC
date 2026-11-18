package com.focusflow.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirewallPathVerificationTest {

    // ── script text ──────────────────────────────────────────────────────────

    @Test
    fun theApplyScriptReadsBackTheProgramPathOfEachRuleItJustCreated() {
        val script = FirewallScripts.apply(listOf(FirewallRule("a.exe", "C:\\x\\a.exe")))

        assertTrue("Get-NetFirewallApplicationFilter" in script)
        assertTrue(".Program" in script)
        assertTrue("'FF_OK=' + " in script && "'|'" in script, "output must be FF_OK=<rule>|<program>")
    }

    @Test
    fun theApplyScriptOnlyLooksUpPathsForTheBatchItApplied() {
        val script = FirewallScripts.apply(listOf(FirewallRule("a.exe", "C:\\x\\a.exe")))

        assertTrue("ContainsKey" in script, "the per-rule path lookup is slow, so it must be limited to this batch")
    }

    @Test
    fun theStartupReadAlsoReturnsThePathOfEachRule() {
        assertTrue("Get-NetFirewallApplicationFilter" in FirewallScripts.readEnabled)
        assertTrue("FF_RULE=" in FirewallScripts.readEnabled)
    }

    // ── parsing ──────────────────────────────────────────────────────────────

    @Test
    fun entriesCarryTheirProgramPathAndABlankPathMeansUnknown() {
        val output = """
            noise
            FF_OK=FocusFlow_Block_Chrome|C:\Program Files\Chrome\chrome.exe
            FF_OK=FocusFlow_Block_cmd|
            FF_OK=FocusFlow_Block_tool
            FF_OK=SomeoneElsesRule|C:\x.exe
        """.trimIndent()

        val entries = FirewallScripts.parseEntries(output, "FF_OK=")

        assertEquals(
            mapOf<String, String?>("chrome.exe" to "C:\\Program Files\\Chrome\\chrome.exe", "cmd.exe" to null, "tool.exe" to null),
            entries
        )
    }

    // ── verification ─────────────────────────────────────────────────────────

    private val rules = listOf(
        FirewallRule("a.exe", "C:\\Apps\\A\\a.exe"),
        FirewallRule("b.exe", "C:\\Apps\\B\\b.exe"),
        FirewallRule("c.exe", "C:\\Apps\\C\\c.exe"),
        FirewallRule("d.exe", "C:\\Apps\\D\\d.exe")
    )

    @Test
    fun aRuleIsConfirmedOnlyWhenItsProgramPathIsTheOneRequested() {
        val verdict = FirewallScripts.verifyApplied(
            rules,
            mapOf<String, String?>(
                "a.exe" to "c:/apps/a/A.EXE",            // same file, different case and slashes
                "b.exe" to "C:\\Apps\\OLD-B\\b.exe"      // rule points somewhere else
            )
        )

        assertEquals(setOf("a.exe"), verdict.confirmed - verdict.unverifiable)
        assertEquals(mapOf("b.exe" to ("C:\\Apps\\B\\b.exe" to "C:\\Apps\\OLD-B\\b.exe")), verdict.mismatched)
        assertTrue("b.exe" !in verdict.confirmed)
    }

    @Test
    fun aRuleWhosePathCannotBeReadIsAcceptedButReportedAsUnverifiable() {
        val verdict = FirewallScripts.verifyApplied(rules, mapOf<String, String?>("c.exe" to null))

        assertEquals(setOf("c.exe"), verdict.confirmed)
        assertEquals(setOf("c.exe"), verdict.unverifiable)
    }

    @Test
    fun aRuleThatIsNotInTheReadBackIsNotConfirmed() {
        val verdict = FirewallScripts.verifyApplied(rules, emptyMap())

        assertTrue(verdict.confirmed.isEmpty() && verdict.mismatched.isEmpty() && verdict.unverifiable.isEmpty())
    }

    // ── the backend, driven by a fake PowerShell ─────────────────────────────

    private class FakeRunner(private val reply: (String) -> PsResult) : PsScriptRunner {
        val scripts = mutableListOf<String>()
        var cancelled = 0
        override fun run(script: String, timeoutSec: Long): PsResult { scripts += script; return reply(script) }
        override fun cancel() { cancelled++ }
    }

    @Test
    fun applyConfirmsOnlyRulesWhosePathMatchedAndSendsTheRequestedRulesToPowerShell() {
        val runner = FakeRunner {
            PsResult(true, "FF_OK=FocusFlow_Block_a|C:\\Apps\\A\\a.exe\nFF_OK=FocusFlow_Block_b|C:\\elsewhere\\b.exe\n", false)
        }
        val backend = PowerShellFirewallBackend(runner)

        val confirmed = backend.apply(rules.take(2))

        assertEquals(setOf("a.exe"), confirmed)
        assertTrue("FocusFlow_Block_a|C:\\Apps\\A\\a.exe" in runner.scripts.single())
    }

    @Test
    fun readActiveReturnsRulesWithTheirPathsAndNullWhenThePowerShellFailed() {
        val ok = PowerShellFirewallBackend(FakeRunner { PsResult(true, "FF_RULE=FocusFlow_Block_a|C:\\Apps\\A\\a.exe\nFF_RULE=FocusFlow_Block_b|\n", false) })
        assertEquals(mapOf<String, String?>("a.exe" to "C:\\Apps\\A\\a.exe", "b.exe" to null), ok.readActive())

        val timedOut = PowerShellFirewallBackend(FakeRunner { PsResult(false, "", true) })
        assertNull(timedOut.readActive(), "a failed read must not look like 'no rules'")
    }

    @Test
    fun closeCancelsTheRunnerInProgress() {
        val runner = FakeRunner { PsResult(true, "", false) }

        PowerShellFirewallBackend(runner).close()

        assertEquals(1, runner.cancelled)
    }

    // ── the path cache ───────────────────────────────────────────────────────

    private var clock = 5_000L
    private val onDisk = mutableSetOf<String>()
    private val lookups = mutableListOf<String>()
    private var found: String? = "C:\\v1\\app.exe"

    private val cache = ResolvedPathCache(
        resolve = { lookups += it; found },
        exists = { it in onDisk },
        nowMs = { clock },
        ttlMs = 600_000L
    )

    @Test
    fun aResolvedPathIsReusedWithinItsLifetime() {
        onDisk += "C:\\v1\\app.exe"

        assertEquals("C:\\v1\\app.exe", cache.get("app.exe"))
        assertEquals("C:\\v1\\app.exe", cache.get("app.exe"))

        assertEquals(1, lookups.size)
    }

    @Test
    fun afterItsLifetimeTheProgramIsLookedUpAgainEvenIfTheOldFileStillExists() {
        onDisk += listOf("C:\\v1\\app.exe", "C:\\v2\\app.exe")
        cache.get("app.exe")
        found = "C:\\v2\\app.exe"
        clock += 600_001L

        assertEquals("C:\\v2\\app.exe", cache.get("app.exe"))
    }

    @Test
    fun aCachedPathWhoseFileIsGoneIsLookedUpAgainImmediately() {
        onDisk += "C:\\v1\\app.exe"
        cache.get("app.exe")
        onDisk.clear()
        found = "C:\\v2\\app.exe"

        assertEquals("C:\\v2\\app.exe", cache.get("app.exe"))
    }

    @Test
    fun aProgramThatWasNotFoundIsNotRemembered() {
        found = null
        assertNull(cache.get("app.exe"))
        found = "C:\\v1\\app.exe"

        assertEquals("C:\\v1\\app.exe", cache.get("app.exe"))
        assertEquals(2, lookups.size)
    }
}
