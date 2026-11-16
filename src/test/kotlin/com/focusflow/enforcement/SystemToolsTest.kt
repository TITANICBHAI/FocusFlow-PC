package com.focusflow.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals

class SystemToolsTest {
    @Test
    fun toolsResolveToTheWindowsSystemFolder() {
        assertEquals("C:\\Windows\\System32\\taskkill.exe", SystemTools.path("taskkill.exe", "C:\\Windows"))
    }

    @Test
    fun aTrailingSeparatorOnTheWindowsFolderIsIgnored() {
        assertEquals("D:\\Win\\System32\\schtasks.exe", SystemTools.path("schtasks.exe", "D:\\Win\\"))
        assertEquals("D:\\Win\\System32\\schtasks.exe", SystemTools.path("schtasks.exe", "D:/Win/"))
    }

    @Test
    fun withoutAWindowsFolderTheBareNameIsUsed() {
        assertEquals("taskkill.exe", SystemTools.path("taskkill.exe", null))
        assertEquals("taskkill.exe", SystemTools.path("taskkill.exe", "  "))
    }

    @Test
    fun powerShellResolvesToTheRealWindowsPowerShell() {
        assertEquals(
            "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
            SystemTools.powerShell("C:\\Windows")
        )
    }
}
