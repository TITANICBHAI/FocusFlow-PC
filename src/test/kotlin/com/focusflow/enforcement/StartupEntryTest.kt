package com.focusflow.enforcement

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StartupEntryTest {

    private class FakeRegistry : StartupRegistry {
        var runValue: String? = null
        var flag: ByteArray? = null
        var failWrite = false
        var failClearFlag = false
        var failDelete = false
        var failRead = false
        var writes = 0

        override fun runValueExists(): Boolean {
            if (failRead) throw IllegalStateException("registry unavailable")
            return runValue != null
        }

        override fun writeRunValue(command: String) {
            if (failWrite) throw IllegalStateException("access denied")
            writes++
            runValue = command
        }

        override fun deleteRunValue() {
            if (failDelete) throw IllegalStateException("access denied")
            runValue = null
        }

        override fun approvalFlag(): ByteArray? {
            if (failRead) throw IllegalStateException("registry unavailable")
            return flag
        }

        override fun clearApprovalFlag() {
            if (failClearFlag) throw IllegalStateException("access denied")
            flag = null
        }
    }

    /** What Task Manager writes when the user clicks Disable: 03 00 00 00 + FILETIME. */
    private fun disabledByTaskManager() =
        byteArrayOf(3, 0, 0, 0, 0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x01)

    private fun enabledFlag() = byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    @Test
    fun theApprovalFlagIsReadByItsFirstByte() {
        assertTrue(isApprovalFlagEnabled(null))
        assertTrue(isApprovalFlagEnabled(ByteArray(0)))
        assertTrue(isApprovalFlagEnabled(enabledFlag()))
        assertTrue(isApprovalFlagEnabled(byteArrayOf(6, 0, 0, 0)))
        assertFalse(isApprovalFlagEnabled(disabledByTaskManager()))
        assertFalse(isApprovalFlagEnabled(byteArrayOf(3)))
    }

    @Test
    fun withoutARunValueTheStateIsOffEvenIfAFlagExists() {
        val registry = FakeRegistry().apply { flag = disabledByTaskManager() }
        assertEquals(StartupState.Off, StartupEntry(registry).state())
    }

    @Test
    fun aRunValueWithNoFlagIsOn() {
        val registry = FakeRegistry().apply { runValue = "\"C:\\FocusFlow.exe\"" }
        assertEquals(StartupState.On, StartupEntry(registry).state())
    }

    @Test
    fun anEntryDisabledInTaskManagerIsReportedAsDisabledInWindowsNotOn() {
        val registry = FakeRegistry().apply {
            runValue = "\"C:\\FocusFlow.exe\""
            flag = disabledByTaskManager()
        }
        assertEquals(StartupState.DisabledInWindows, StartupEntry(registry).state())
    }

    @Test
    fun turningAutostartOnReEnablesAnEntryTheUserDisabledInTaskManager() {
        val registry = FakeRegistry().apply {
            runValue = "\"C:\\Old\\FocusFlow.exe\""
            flag = disabledByTaskManager()
        }
        val result = StartupEntry(registry).enable("\"C:\\New\\FocusFlow.exe\"")

        assertEquals(StartupEnableResult.Enabled, result)
        assertEquals("\"C:\\New\\FocusFlow.exe\"", registry.runValue)
        assertNull(registry.flag, "The disable flag must be cleared")
        assertEquals(StartupState.On, StartupEntry(registry).state())
    }

    @Test
    fun aFreshEnableWritesTheValueAndReportsEnabled() {
        val registry = FakeRegistry()
        assertEquals(StartupEnableResult.Enabled, StartupEntry(registry).enable("\"C:\\FocusFlow.exe\""))
        assertEquals("\"C:\\FocusFlow.exe\"", registry.runValue)
    }

    @Test
    fun anEnabledFlagSurvivesEnableAsEnabled() {
        val registry = FakeRegistry().apply { flag = enabledFlag() }
        assertEquals(StartupEnableResult.Enabled, StartupEntry(registry).enable("\"C:\\FocusFlow.exe\""))
    }

    @Test
    fun withoutALaunchCommandNothingIsWritten() {
        val registry = FakeRegistry()
        val entry = StartupEntry(registry)
        assertEquals(StartupEnableResult.NoLaunchCommand, entry.enable(null))
        assertEquals(StartupEnableResult.NoLaunchCommand, entry.enable("   "))
        assertEquals(0, registry.writes)
        assertNull(registry.runValue)
    }

    @Test
    fun aFailedWriteIsReportedInsteadOfPretendingToSucceed() {
        val registry = FakeRegistry().apply { failWrite = true }
        assertEquals(StartupEnableResult.WriteFailed, StartupEntry(registry).enable("\"C:\\FocusFlow.exe\""))
    }

    @Test
    fun ifTheDisableFlagCannotBeClearedTheUserIsToldWindowsStillBlocksIt() {
        val registry = FakeRegistry().apply {
            flag = disabledByTaskManager()
            failClearFlag = true
        }
        val result = StartupEntry(registry).enable("\"C:\\FocusFlow.exe\"")

        assertEquals(StartupEnableResult.DisabledByWindows, result)
        assertContentEquals(disabledByTaskManager(), registry.flag)
    }

    @Test
    fun disableRemovesTheEntryAndReportsIt() {
        val registry = FakeRegistry().apply { runValue = "\"C:\\FocusFlow.exe\"" }
        assertTrue(StartupEntry(registry).disable())
        assertNull(registry.runValue)
    }

    @Test
    fun disableAlsoWorksOnAnEntryWindowsHadSwitchedOff() {
        val registry = FakeRegistry().apply {
            runValue = "\"C:\\FocusFlow.exe\""
            flag = disabledByTaskManager()
        }
        assertTrue(StartupEntry(registry).disable())
    }

    @Test
    fun aFailedDisableIsReportedAsNotRemoved() {
        val registry = FakeRegistry().apply {
            runValue = "\"C:\\FocusFlow.exe\""
            failDelete = true
        }
        assertFalse(StartupEntry(registry).disable())
    }

    @Test
    fun unreadableRegistryNeverThrowsFromState() {
        val registry = FakeRegistry().apply {
            runValue = "\"C:\\FocusFlow.exe\""
            failRead = true
        }
        assertEquals(StartupState.Off, StartupEntry(registry).state())
    }
}
