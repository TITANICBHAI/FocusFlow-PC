package com.focusflow.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsSettingsLauncherTest {

    @Test
    fun theThreeSettingsPagesAreAccepted() {
        assertTrue(WindowsSettingsLauncher.isSettingsLink(WindowsSettingsLauncher.SECURITY))
        assertTrue(WindowsSettingsLauncher.isSettingsLink(WindowsSettingsLauncher.NOTIFICATIONS))
        assertTrue(WindowsSettingsLauncher.isSettingsLink(WindowsSettingsLauncher.FOCUS_ASSIST))
    }

    @Test
    fun onlyPlainSettingsLinksAreAccepted() {
        listOf(
            "https://example.com",
            "ms-settings:",
            "ms-windows-store://review/?ProductId=1",
            "C:\\Windows\\System32\\calc.exe",
            "ms-settings:notifications calc.exe",
            "ms-settings:a\"b",
            "ms-settings:a&b|c",
            "ms-settings:a\nb",
            "ms-settings:" + "x".repeat(300),
        ).forEach { assertFalse(WindowsSettingsLauncher.isSettingsLink(it), it) }
    }

    @Test
    fun theCommandUsesTheRealWindowsUrlHandler() {
        assertEquals(
            listOf("C:\\Windows\\System32\\rundll32.exe", "url.dll,FileProtocolHandler", "ms-settings:quiethours"),
            WindowsSettingsLauncher.command("ms-settings:quiethours", "C:\\Windows")
        )
    }

    @Test
    fun noCommandIsBuiltForAnythingElse() {
        assertNull(WindowsSettingsLauncher.command("https://example.com", "C:\\Windows"))
    }

    @Test
    fun onWindowsTheHandlerIsStartedAndTheBrowserRouteIsNotUsed() {
        var started: List<String>? = null
        var fellBack = false
        val ok = WindowsSettingsLauncher.open(
            WindowsSettingsLauncher.NOTIFICATIONS,
            windows = true,
            systemRoot = "C:\\Windows",
            start = { started = it },
            fallback = { fellBack = true },
        )
        assertTrue(ok)
        assertFalse(fellBack)
        assertEquals("ms-settings:notifications", started?.last())
    }

    @Test
    fun ifTheHandlerCannotStartTheOldRouteIsTried() {
        var fellBackWith: String? = null
        val ok = WindowsSettingsLauncher.open(
            WindowsSettingsLauncher.SECURITY,
            windows = true,
            systemRoot = "C:\\Windows",
            start = { throw java.io.IOException("no such file") },
            fallback = { fellBackWith = it },
        )
        assertTrue(ok)
        assertEquals("ms-settings:windowsdefender", fellBackWith)
    }

    @Test
    fun offWindowsTheHandlerIsNeverStarted() {
        var started = false
        WindowsSettingsLauncher.open(
            WindowsSettingsLauncher.SECURITY,
            windows = false,
            start = { started = true },
            fallback = {},
        )
        assertFalse(started)
    }

    @Test
    fun reportsFailureWhenBothRoutesFail() {
        val ok = WindowsSettingsLauncher.open(
            WindowsSettingsLauncher.SECURITY,
            windows = true,
            systemRoot = "C:\\Windows",
            start = { throw java.io.IOException("x") },
            fallback = { throw java.io.IOException("y") },
        )
        assertFalse(ok)
    }
}
