package com.focusflow.enforcement

/**
 * Pure string logic for [ProcessHardening] (unit-tested without Windows).
 *
 * Takes the process's current DACL in SDDL form and puts one "deny PROCESS_TERMINATE" entry for
 * interactive users (IU) in front of it. Every existing allow entry stays, so Task Manager can
 * still list the process and Windows can still query it; only "end task" / taskkill / Stop-Process
 * fail with "access denied". SYSTEM services are not interactive users, so Windows itself can
 * still shut the process down at sign-out or shutdown.
 */
internal object ProcessHardeningSddl {
    const val DENY_TERMINATE_ACE = "(D;;0x0001;;;IU)"

    /** Returns the hardened DACL, or null when the input is not a normal DACL (then nothing is changed). */
    fun withDenyTerminate(originalDacl: String): String? {
        val dacl = originalDacl.trim()
        if (!dacl.startsWith("D:")) return null
        val firstEntry = dacl.indexOf('(')
        if (firstEntry < 0) return null // NULL / empty DACL: leave it alone
        if (dacl.contains(DENY_TERMINATE_ACE)) return dacl // already hardened
        return dacl.substring(0, firstEntry) + DENY_TERMINATE_ACE + dacl.substring(firstEntry)
    }
}
