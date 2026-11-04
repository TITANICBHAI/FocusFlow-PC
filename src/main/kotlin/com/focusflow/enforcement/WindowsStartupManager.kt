package com.focusflow.enforcement

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * WindowsStartupManager
 *
 * Adds / removes a HKCU\Software\Microsoft\Windows\CurrentVersion\Run registry
 * entry so FocusFlow PC launches automatically on Windows login.
 *
 * Uses JNA Advapi32Util for registry access — no admin rights required for HKCU.
 *
 * Administrator rights would not help with a startup entry the user disabled in Task
 * Manager: that switch lives in HKCU (`StartupApproved\Run`), which this process can
 * already write, and an elevated process under a different admin account would write
 * the wrong user's hive. [StartupEntry] therefore clears that flag when, and only when,
 * the user explicitly turns autostart on.
 */
object WindowsStartupManager {

    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val APPROVED_KEY =
        "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run"
    private const val APP_NAME = "FocusFlow"

    private val entry by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { StartupEntry(JnaStartupRegistry) }

    /** What Windows will actually do at login, not merely whether our value exists. */
    fun state(): StartupState {
        if (!isWindows) return StartupState.Off
        return entry.state()
    }

    /** True only when the entry exists AND Windows allows it to run. */
    fun isEnabled(): Boolean = state() == StartupState.On

    /**
     * Turns autostart on at the user's request, including re-enabling an entry they had
     * switched off in Task Manager. Never call this without an explicit user action.
     */
    fun enable(): StartupEnableResult {
        if (!isWindows) return StartupEnableResult.NotSupported
        val before = runCatching { entry.state() }.getOrDefault(StartupState.Off)
        val exePath = resolveExePath()
        val command = "\"$exePath\"".takeIf { File(exePath).isFile }
        val result = entry.enable(command)
        EnforcementLog.info(
            "Startup",
            "autostart enable result=$result before=$before after=${runCatching { entry.state() }.getOrNull()} " +
                "approvalFlag=${runCatching { JnaStartupRegistry.approvalFlag() }.getOrNull()?.firstOrNull()?.toInt()?.and(0xFF) ?: "none"} " +
                "exeFound=${command != null}"
        )
        return result
    }

    /** Returns true when the entry is gone afterwards. */
    fun disable(): Boolean {
        if (!isWindows) return true
        val removed = entry.disable()
        if (!removed) EnforcementLog.warn("Startup", "autostart disable did not remove the Run entry")
        return removed
    }

    private object JnaStartupRegistry : StartupRegistry {
        private val root = WinReg.HKEY_CURRENT_USER

        override fun runValueExists(): Boolean =
            Advapi32Util.registryValueExists(root, RUN_KEY, APP_NAME)

        override fun writeRunValue(command: String) {
            try {
                Advapi32Util.registrySetStringValue(root, RUN_KEY, APP_NAME, command)
            } catch (_: Exception) {
                // Fallback: reg.exe CLI
                runReg("reg", "add", "HKCU\\$RUN_KEY", "/v", APP_NAME, "/t", "REG_SZ", "/d", command, "/f")
            }
        }

        override fun deleteRunValue() {
            if (!runValueExists()) return
            try {
                Advapi32Util.registryDeleteValue(root, RUN_KEY, APP_NAME)
            } catch (_: Exception) {
                runReg("reg", "delete", "HKCU\\$RUN_KEY", "/v", APP_NAME, "/f")
            }
        }

        override fun approvalFlag(): ByteArray? =
            if (Advapi32Util.registryValueExists(root, APPROVED_KEY, APP_NAME)) {
                Advapi32Util.registryGetBinaryValue(root, APPROVED_KEY, APP_NAME)
            } else {
                null
            }

        override fun clearApprovalFlag() {
            if (!Advapi32Util.registryValueExists(root, APPROVED_KEY, APP_NAME)) return
            try {
                Advapi32Util.registryDeleteValue(root, APPROVED_KEY, APP_NAME)
            } catch (_: Exception) {
                runReg("reg", "delete", "HKCU\\$APPROVED_KEY", "/v", APP_NAME, "/f")
            }
        }

        /** Runs reg.exe and waits, so a failure is reported instead of silently lost. */
        private fun runReg(vararg args: String) {
            val process = ProcessBuilder(*args)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IOException("reg.exe timed out")
            }
            if (process.exitValue() != 0) throw IOException("reg.exe exited with ${process.exitValue()}")
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
