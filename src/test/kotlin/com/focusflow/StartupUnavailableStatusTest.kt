package com.focusflow

import com.focusflow.services.SystemTrayManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StartupUnavailableStatusTest {
    @Test
    fun gateStatusAppearsOnlyWhenTheDatabaseIsUnavailable() {
        assertEquals(
            DATABASE_UNAVAILABLE_BLOCKING_STATUS,
            databaseUnavailableBlockingStatus(isDatabaseUnavailable = true)
        )
        assertNull(databaseUnavailableBlockingStatus(isDatabaseUnavailable = false))
    }

    @Test
    fun unavailableTrayShowsDisabledStatusWithoutBlockingControls() {
        val entries = SystemTrayManager.databaseUnavailableTrayEntries(
            DATABASE_UNAVAILABLE_BLOCKING_STATUS
        )

        assertEquals(3, entries.size)
        assertEquals(DATABASE_UNAVAILABLE_BLOCKING_STATUS, entries[0].label)
        assertFalse(entries[0].enabled)
        assertEquals(
            SystemTrayManager.UnavailableTrayAction.STATUS,
            entries[0].action
        )
        assertEquals("Open FocusFlow", entries[1].label)
        assertEquals(SystemTrayManager.UnavailableTrayAction.OPEN, entries[1].action)
        assertEquals("Quit", entries[2].label)
        assertEquals(SystemTrayManager.UnavailableTrayAction.QUIT, entries[2].action)
        assertTrue(entries.none { it.label == "Toggle Blocking" })
    }
}
