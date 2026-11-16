package com.focusflow.enforcement

/**
 * Full paths of the Windows tools FocusFlow runs.
 *
 * A bare name like `powershell` is resolved by Windows searching, among other places, the folder
 * the running program was loaded from, so it is not guaranteed to be the real tool. An absolute
 * path in the Windows folder is, and it is also the one thing [OwnedProcessRegistry] can compare a
 * running helper's image against.
 */
internal object SystemTools {

    fun systemRoot(): String? =
        System.getenv("SystemRoot")?.takeIf { it.isNotBlank() }
            ?: System.getenv("windir")?.takeIf { it.isNotBlank() }

    /** `<Windows>\System32\<exe>`, or just [exe] when the Windows folder is unknown (non-Windows hosts). */
    fun path(exe: String, systemRoot: String? = systemRoot()): String {
        val root = systemRoot?.takeIf { it.isNotBlank() }?.replace('/', '\\')?.trimEnd('\\') ?: return exe
        return "$root\\System32\\$exe"
    }

    fun powerShell(systemRoot: String? = systemRoot()): String = WatchdogScript.powerShellExe(systemRoot)
}
