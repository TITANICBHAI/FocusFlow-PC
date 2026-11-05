package com.focusflow.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UninstallCommandMatcherTest {
    private val code = "{B4C3F3A2-8E41-4D9A-B7C6-D1E0F2A34B56}"
    private val codes = setOf(code)

    @Test
    fun settingsAndControlPanelRemovalIsRecognised() {
        assertTrue(UninstallCommandMatcher.isFocusFlowRemoval("MsiExec.exe /X$code", codes))
        assertTrue(UninstallCommandMatcher.isFocusFlowRemoval("\"C:\\Windows\\System32\\msiexec.exe\" /x $code", codes))
        assertTrue(UninstallCommandMatcher.isFocusFlowRemoval("msiexec /x\"$code\" /qb", codes))
    }

    @Test
    fun quietAndPackageManagerRemovalIsRecognised() {
        assertTrue(UninstallCommandMatcher.isFocusFlowRemoval("msiexec.exe /x $code /qn /norestart", codes))
        assertTrue(UninstallCommandMatcher.isFocusFlowRemoval("msiexec -x $code", codes))
        assertTrue(UninstallCommandMatcher.isFocusFlowRemoval("msiexec /uninstall $code", codes))
        assertTrue(UninstallCommandMatcher.isFocusFlowRemoval("msiexec /i $code REMOVE=ALL", codes))
    }

    @Test
    fun productCodeIsMatchedCaseInsensitively() {
        assertTrue(UninstallCommandMatcher.isFocusFlowRemoval("msiexec /x ${code.lowercase()}", codes))
        assertTrue(UninstallCommandMatcher.isFocusFlowRemoval("msiexec /x $code", setOf(code.lowercase())))
    }

    @Test
    fun maintenanceModeOnTheInstalledProductCountsAsRemoval() {
        assertTrue(UninstallCommandMatcher.isFocusFlowRemoval("MsiExec.exe /I$code", codes))
        assertTrue(UninstallCommandMatcher.isFocusFlowRemoval("msiexec /i \"$code\"", codes))
    }

    @Test
    fun maintenanceModeOnAnotherProductIsNotRemoval() {
        assertFalse(
            UninstallCommandMatcher.isFocusFlowRemoval(
                "MsiExec.exe /I{11111111-2222-3333-4444-555555555555}", codes
            )
        )
    }

    @Test
    fun removalByPackagePathNamingFocusFlowIsRecognisedEvenWithoutProductCodes() {
        assertTrue(
            UninstallCommandMatcher.isFocusFlowRemoval(
                "msiexec /x C:\\Users\\me\\Downloads\\FocusFlow-1.2.0.msi /qb", emptySet()
            )
        )
    }

    @Test
    fun installingOrUpgradingFromAPackageIsNotRemoval() {
        assertFalse(
            UninstallCommandMatcher.isFocusFlowRemoval(
                "msiexec /i \"C:\\Users\\me\\AppData\\Local\\Temp\\FocusFlow-1.3.0.msi\"", codes
            )
        )
        assertFalse(UninstallCommandMatcher.isFocusFlowRemoval("msiexec /i C:\\setup\\FocusFlow.msi /qn", codes))
    }

    @Test
    fun removingAnotherProgramIsNotTouched() {
        assertFalse(
            UninstallCommandMatcher.isFocusFlowRemoval(
                "MsiExec.exe /X{11111111-2222-3333-4444-555555555555}", codes
            )
        )
        assertFalse(UninstallCommandMatcher.isFocusFlowRemoval("msiexec /x C:\\apps\\Other.msi", codes))
    }

    @Test
    fun theWindowsInstallerServiceProcessAndEmptyInputAreNotRemovals() {
        assertFalse(UninstallCommandMatcher.isFocusFlowRemoval("C:\\Windows\\system32\\msiexec.exe /V", codes))
        assertFalse(UninstallCommandMatcher.isFocusFlowRemoval("", codes))
        assertFalse(UninstallCommandMatcher.isFocusFlowRemoval(null, codes))
    }

    @Test
    fun onlyMsiexecCommandLinesAreConsidered() {
        assertFalse(UninstallCommandMatcher.isFocusFlowRemoval("C:\\Program Files\\FocusFlow\\FocusFlow.exe --uninstall", codes))
        assertFalse(UninstallCommandMatcher.isFocusFlowRemoval("cmd.exe /c echo /x $code", codes))
    }

    @Test
    fun similarLookingSwitchesAreNotRemoval() {
        assertFalse(UninstallCommandMatcher.isFocusFlowRemoval("msiexec /xyz FocusFlow", codes))
        assertFalse(UninstallCommandMatcher.isFocusFlowRemoval("msiexec /quiet FocusFlow.msi", codes))
    }

    @Test
    fun productCodesAreExtractedFromRegistryStrings() {
        val found = UninstallCommandMatcher.productCodesIn(
            code.lowercase(),
            "MsiExec.exe /X$code",
            null,
            "\"C:\\Apps\\FocusFlow\\FocusFlow.exe\" --uninstall"
        )
        assertEquals(setOf(code), found)
    }
}
