package com.focusflow.enforcement

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File

/**
 * WindowsStartupManager
 *
 * Adds / removes a HKCU\Software\Microsoft\Windows\CurrentVersion\Run registry
 * entry so FocusFlow JVM launches automatically on Windows login.
 *
 * Uses JNA Advapi32Util for registry access — no admin rights required for HKCU.
 */
object WindowsStartupManager {

    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val APP_NAME = "FocusFlow"

    fun isEnabled(): Boolean {
        if (!isWindows) return false
        return try {
            Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, APP_NAME)
        } catch (_: Exception) {
            false
        }
    }

    fun enable() {
        if (!isWindows) return
        val exePath = resolveExePath()
        try {
            Advapi32Util.registrySetStringValue(
                WinReg.HKEY_CURRENT_USER, RUN_KEY, APP_NAME, "\"$exePath\""
            )
        } catch (_: Exception) {
            // Fallback: reg.exe CLI
            ProcessBuilder(
                "reg", "add", "HKCU\\$RUN_KEY",
                "/v", APP_NAME, "/t", "REG_SZ", "/d", "\"$exePath\"", "/f"
            ).start()
        }
    }

    fun disable() {
        if (!isWindows) return
        try {
            if (isEnabled()) {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, APP_NAME)
            }
        } catch (_: Exception) {
            ProcessBuilder(
                "reg", "delete", "HKCU\\$RUN_KEY", "/v", APP_NAME, "/f"
            ).start()
        }
    }

    /**
     * Exposed so the onboarding relaunch-as-admin button can find the exe path.
     */
    internal fun resolveExePath(): String {
        val processCmd = ProcessHandle.current().info().command().orElse("")

        // Case 1: already running as FocusFlow.exe (rare — JVM usually shows java.exe)
        if (processCmd.endsWith("FocusFlow.exe", ignoreCase = true) && File(processCmd).exists())
            return processCmd

        // Case 2: Compose Desktop distributable layout
        //   <install>/app/runtime/bin/java.exe  →  go up 4 levels → <install>/FocusFlow.exe
        if (processCmd.isNotBlank()) {
            var dir: File? = File(processCmd)
            repeat(4) { dir = dir?.parentFile }
            val candidate = dir?.let { File(it, "FocusFlow.exe") }
            if (candidate?.exists() == true) return candidate.absolutePath
        }

        // Case 3: working directory is the install root
        val fromUserDir = File(System.getProperty("user.dir", ""), "FocusFlow.exe")
        if (fromUserDir.exists()) return fromUserDir.absolutePath

        // Case 4: one level up from working directory
        val fromParent = File(System.getProperty("user.dir", "")).parentFile
            ?.let { File(it, "FocusFlow.exe") }
        if (fromParent?.exists() == true) return fromParent.absolutePath

        // Fallback
        return "FocusFlow.exe"
    }
}
