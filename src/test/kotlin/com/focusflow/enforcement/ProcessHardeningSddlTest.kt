package com.focusflow.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcessHardeningSddlTest {
    @Test
    fun putsTheDenyEntryFirstAndKeepsFlagsAndEveryOriginalEntry() {
        val original = "D:AI(A;;0x1fffff;;;SY)(A;;0x1fffff;;;BA)(A;;0x1fffff;;;S-1-5-21-1-2-3-1001)"
        assertEquals(
            "D:AI(D;;0x0001;;;IU)(A;;0x1fffff;;;SY)(A;;0x1fffff;;;BA)(A;;0x1fffff;;;S-1-5-21-1-2-3-1001)",
            ProcessHardeningSddl.withDenyTerminate(original)
        )
    }

    @Test
    fun isIdempotent() {
        val once = ProcessHardeningSddl.withDenyTerminate("D:(A;;GA;;;SY)")!!
        assertEquals(once, ProcessHardeningSddl.withDenyTerminate(once))
        assertEquals(1, Regex(Regex.escape(ProcessHardeningSddl.DENY_TERMINATE_ACE)).findAll(once).count())
    }

    @Test
    fun leavesUnusualPermissionsAlone() {
        assertNull(ProcessHardeningSddl.withDenyTerminate("D:NO_ACCESS_CONTROL"))
        assertNull(ProcessHardeningSddl.withDenyTerminate("D:"))
        assertNull(ProcessHardeningSddl.withDenyTerminate("O:BAG:BA"))
        assertNull(ProcessHardeningSddl.withDenyTerminate(""))
    }

    @Test
    fun deniesOnlyTheTerminateRight() {
        // 0x0001 is PROCESS_TERMINATE. Query/read rights are untouched, so Task Manager keeps working.
        assertTrue(ProcessHardeningSddl.DENY_TERMINATE_ACE.contains(";0x0001;"))
        assertTrue(ProcessHardeningSddl.DENY_TERMINATE_ACE.startsWith("(D;"))
    }
}
