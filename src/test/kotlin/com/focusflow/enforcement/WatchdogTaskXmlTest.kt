package com.focusflow.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchdogTaskXmlTest {
    private val action = WatchdogScript.TaskAction(
        command = "C:\\Windows\\System32\\conhost.exe",
        arguments = "--headless powershell.exe -NoProfile -File \"C:\\Users\\me\\.focusflow\\watchdog.ps1\""
    )
    private val boundary = "2026-11-06T14:00:00"

    private fun watchdog(userId: String? = "PC\\me", action: WatchdogScript.TaskAction = this.action) =
        WatchdogTaskXml.build("desc", WatchdogTaskXml.Trigger.EVERY_TWO_MINUTES, action, userId, boundary)

    private fun guard(userId: String? = "PC\\me") =
        WatchdogTaskXml.build("desc", WatchdogTaskXml.Trigger.AT_LOGON, action, userId, boundary)

    @Test
    fun watchdogRepeatsEveryTwoMinutesForever() {
        val xml = watchdog()
        assertTrue(xml.contains("<TimeTrigger>"))
        assertTrue(xml.contains("<Interval>PT2M</Interval>"))
        assertTrue(xml.contains("<StopAtDurationEnd>false</StopAtDurationEnd>"))
        assertFalse(xml.contains("<LogonTrigger>"))
        assertTrue(xml.contains("<StartBoundary>$boundary</StartBoundary>"))
    }

    @Test
    fun taskbarGuardRunsOnceAtLogon() {
        val xml = guard()
        assertTrue(xml.contains("<LogonTrigger>"))
        assertFalse(xml.contains("<TimeTrigger>"))
        assertFalse(xml.contains("<Repetition>"))
    }

    @Test
    fun bothTasksRunOnBatteryToo() {
        listOf(watchdog(), guard()).forEach { xml ->
            assertTrue(xml.contains("<DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>"))
            assertTrue(xml.contains("<StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>"))
        }
    }

    @Test
    fun aHungRunCannotBlockTheWatchdogForDays() {
        val xml = watchdog()
        assertTrue(xml.contains("<MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>"))
        assertTrue(xml.contains("<ExecutionTimeLimit>PT5M</ExecutionTimeLimit>"))
        assertTrue(guard().contains("<ExecutionTimeLimit>PT10M</ExecutionTimeLimit>"))
    }

    @Test
    fun tasksRunInTheInteractiveSessionWithoutElevation() {
        listOf(watchdog(), guard()).forEach { xml ->
            assertTrue(xml.contains("<LogonType>InteractiveToken</LogonType>"))
            assertTrue(xml.contains("<RunLevel>LeastPrivilege</RunLevel>"))
        }
    }

    @Test
    fun theUserIdIsWrittenWhenKnownAndOmittedWhenNot() {
        assertTrue(watchdog(userId = "PC\\me").contains("<UserId>PC\\me</UserId>"))
        assertFalse(watchdog(userId = null).contains("<UserId>"))
        assertFalse(watchdog(userId = "  ").contains("<UserId>"))
    }

    @Test
    fun specialCharactersInNamesAndCommandsAreEscaped() {
        val xml = watchdog(
            userId = "PC\\A&B",
            action = WatchdogScript.TaskAction("C:\\R&D\\a.exe", "-x \"<y>\"")
        )
        assertTrue(xml.contains("<UserId>PC\\A&amp;B</UserId>"))
        assertTrue(xml.contains("<Command>C:\\R&amp;D\\a.exe</Command>"))
        assertTrue(xml.contains("<Arguments>-x \"&lt;y&gt;\"</Arguments>"))
    }

    @Test
    fun theCommandAndArgumentsAreInTheExecElement() {
        val xml = watchdog()
        assertTrue(xml.contains("<Command>C:\\Windows\\System32\\conhost.exe</Command>"))
        assertTrue(xml.contains("-File \"C:\\Users\\me\\.focusflow\\watchdog.ps1\"</Arguments>"))
    }

    @Test
    fun theDocumentDeclaresUtf16SoTheEncodedFileMatches() {
        val xml = watchdog()
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-16\"?>"))
        val bytes = WatchdogTaskXml.encode(xml)
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xFE.toByte(), bytes[1])
        assertEquals(xml, String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE))
    }

    @Test
    fun userIdJoinsDomainAndUser() {
        assertEquals("PC\\me", WatchdogTaskXml.userId("PC", "me"))
        assertEquals("me", WatchdogTaskXml.userId(null, "me"))
        assertEquals("me", WatchdogTaskXml.userId("", "me"))
        assertNull(WatchdogTaskXml.userId("PC", null))
        assertNull(WatchdogTaskXml.userId("PC", ""))
    }
}
