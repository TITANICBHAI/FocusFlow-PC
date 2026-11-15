package com.focusflow.services

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Structural guard (reads source; does not run the UI or Windows).
 *
 * Policy: the Global PIN protects only actions that WEAKEN protection (turn off, remove, pause).
 * Actions that add or strengthen protection (add a keyword, turn ON, add a VPN process / network
 * rule) are pinless. Also guards that the standalone timer is driven by a live clock.
 */
class PinOnlyForWeakeningSourceTest {
    private fun source(relative: String): String =
        Files.readString(Path.of("src/main/kotlin/com/focusflow").resolve(relative))

    private fun countOf(text: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(text).count()

    // ── Keyword blocker ──────────────────────────────────────────────────────────

    @Test
    fun keywordBlockerAddsAndTurnOnArePinless() {
        val text = source("ui/screens/KeywordBlockerScreen.kt")
        assertTrue(text.contains("save(keywords + kw)"), "adding a keyword must call save directly")
        assertTrue(text.contains("save((keywords + preset.keywords).distinct())"), "adding a preset must be pinless")
        assertTrue(
            text.contains("if (v) applyEnabled(true) else withPin { applyEnabled(false) }"),
            "turning ON is pinless, turning OFF needs the PIN"
        )
        assertFalse(Regex("withPin\\s*\\{\\s*save\\(updated\\)").containsMatchIn(text))
        assertFalse(Regex("withPin\\s*\\{\\s*save\\(merged\\)").containsMatchIn(text))
    }

    @Test
    fun keywordBlockerRemovalsStillNeedThePin() {
        val text = source("ui/screens/KeywordBlockerScreen.kt")
        assertTrue(text.contains("withPin { save(keywords - kw) }"), "removing one keyword")
        assertTrue(text.contains("withPin { save(keywords - preset.keywords.toSet()) }"), "removing a preset")
        assertTrue(Regex("withPin\\s*\\{\\s*save\\(emptyList\\(\\)\\)").containsMatchIn(text), "clear all")
    }

    @Test
    fun keywordBlockerPinCallSitesAreExactlyTheWeakeningOnes() {
        // fun definition + turn OFF + remove keyword + remove preset + clear all
        assertEquals(5, countOf(source("ui/screens/KeywordBlockerScreen.kt"), "withPin"))
    }

    // ── VPN / network ────────────────────────────────────────────────────────────

    @Test
    fun vpnTurnOnIsPinlessAndTurnOffNeedsThePin() {
        val text = source("ui/screens/VpnNetworkScreen.kt")
        assertTrue(text.contains("if (v) applyVpnState() else withPin(applyVpnState)"))
    }

    @Test
    fun vpnBuiltInListCanBeExpandedWithoutAPin() {
        val text = source("ui/screens/VpnNetworkScreen.kt")
        assertTrue(text.contains("clickable { expandKnownVpn = !expandKnownVpn }"))
        assertFalse(text.contains("withPin { expandKnownVpn"))
    }

    @Test
    fun vpnPinCallSitesAreExactlyTheWeakeningOnes() {
        // fun definition + VPN off + remove custom process + pause rule + remove rule
        assertEquals(5, countOf(source("ui/screens/VpnNetworkScreen.kt"), "withPin"))
    }

    @Test
    fun vpnAddingProcessesAndRulesAndReEnablingRulesIsPinless() {
        val text = source("ui/screens/VpnNetworkScreen.kt")
        // The add / re-enable calls must not sit inside a withPin block.
        assertFalse(Regex("withPin\\s*\\{\\s*scope\\.launch\\s*\\{\\s*try\\s*\\{\\s*withContext\\(Dispatchers\\.IO\\)\\s*\\{\\s*VpnBlocker\\.addCustomProcess").containsMatchIn(text))
        assertFalse(Regex("withPin\\s*\\{\\s*scope\\.launch\\s*\\{\\s*var hostsOk").containsMatchIn(text))
        assertFalse(Regex("withPin\\s*\\{\\s*scope\\.launch\\s*\\{\\s*withContext\\(Dispatchers\\.IO\\)\\s*\\{\\s*Database\\.setNetworkCutoffRuleEnabled\\(rule\\.id, true\\)").containsMatchIn(text))
    }

    @Test
    fun vpnRemovalsAndPausesStillNeedThePin() {
        val text = source("ui/screens/VpnNetworkScreen.kt")
        assertTrue(Regex("withPin\\s*\\{\\s*scope\\.launch\\s*\\{\\s*try\\s*\\{\\s*withContext\\(Dispatchers\\.IO\\)\\s*\\{\\s*VpnBlocker\\.removeCustomProcess").containsMatchIn(text))
        assertTrue(Regex("withPin\\s*\\{\\s*scope\\.launch\\s*\\{\\s*withContext\\(Dispatchers\\.IO\\)\\s*\\{\\s*Database\\.setNetworkCutoffRuleEnabled\\(rule\\.id, false\\)").containsMatchIn(text))
        assertTrue(Regex("withPin\\s*\\{\\s*scope\\.launch\\s*\\{\\s*withContext\\(Dispatchers\\.IO\\)\\s*\\{\\s*Database\\.deleteNetworkCutoffRule").containsMatchIn(text))
    }

    // ── Standalone timer is live ─────────────────────────────────────────────────

    @Test
    fun standaloneTimerScreensAreDrivenByTheLiveClock() {
        for (file in listOf("ui/screens/AppBlockerScreen.kt", "ui/screens/FocusScreen.kt", "ui/screens/ActiveScreen.kt")) {
            val text = source(file)
            assertTrue(text.contains("rememberLiveNowMs("), "$file must use the live clock")
        }
    }

    @Test
    fun theDeadTickCounterThatNeverRecomposedIsGone() {
        val text = source("ui/screens/AppBlockerScreen.kt")
        // This counter was incremented but never read in composition, so nothing recomposed.
        assertFalse(text.contains("while (true) { delay(1000); tick++ }"))
    }

    @Test
    fun theCountdownIsDerivedFromTheTickingNowNotARawServiceCall() {
        assertFalse(source("ui/screens/FocusScreen.kt").contains("StandaloneBlockService.remainingMs()"))
        val blocker = source("ui/screens/AppBlockerScreen.kt")
        assertFalse(blocker.contains("StandaloneBlockService.remainingMs()"))
        assertFalse(blocker.contains("StandaloneBlockService.startsInMs()"))
        assertTrue(blocker.contains("remainingMsAt(nowMs)"))
    }
}
