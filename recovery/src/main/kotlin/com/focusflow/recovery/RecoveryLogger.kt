package com.focusflow.recovery

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Writes a plain-text recovery log to the user's Desktop (fallback: TEMP).
 *
 * Filename: FocusFlow-Recovery-<yyyyMMdd-HHmmss>.log
 *
 * Usage:
 *   RecoveryLogger.start(isAdmin, focusFlowWasRunning)
 *   RecoveryLogger.logScan(enforcementState)           // optional pre-run snapshot
 *   RecoveryLogger.logStep(number, step, result)        // called per step
 *   val path = RecoveryLogger.finish(anyFailed)         // returns file path, "" on error
 */
object RecoveryLogger {

    // Timestamp is created when start() is called, not at class-load time, so
    // the log filename always matches the actual moment recovery began.
    private var sessionStamp: String = ""

    private val logFile: File get() {
        val desktop = File(System.getProperty("user.home"), "Desktop")
        val dir     = if (desktop.exists() && desktop.canWrite()) desktop
                      else File(System.getProperty("java.io.tmpdir"))
        return File(dir, "FocusFlow-Recovery-$sessionStamp.log")
    }

    private val entries = mutableListOf<String>()

    private fun sep(char: Char = '=') = char.toString().repeat(64)
    private fun now() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))

    fun start(isAdmin: Boolean, focusFlowWasRunning: Boolean) {
        // Stamp the exact moment recovery begins — not class-load time.
        sessionStamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))

        entries.clear()
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        entries += sep()
        val recoveryVersion = System.getProperty("focusflow.recovery.version") ?: "unknown"
        entries += "  FocusFlow Emergency Recovery Tool  v$recoveryVersion"
        entries += "  Run started : $ts"
        entries += sep('-')
        entries += "  OS          : ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})"
        entries += "  Java        : ${System.getProperty("java.version")}"
        entries += "  Admin       : $isAdmin"
        entries += "  FF running  : $focusFlowWasRunning"
        entries += sep()
        entries += ""
    }

    /**
     * Log a snapshot of the pre-run enforcement state so the log captures
     * exactly what was found before any steps were executed.
     */
    fun logScan(state: EnforcementState?) {
        entries += "[${now()}]  PRE-SCAN"
        if (state == null) {
            entries += "           Enforcement state: scan not available"
            entries += ""
            return
        }
        entries += "           DB found          : ${state.dbFound}"
        entries += "           Crash guard       : ${state.crashGuard}"
        entries += "           Hard locked       : ${state.hardLocked}"
        entries += "           Nuclear mode      : ${state.nuclearMode}"
        entries += "           Kill switch used  : ${state.killSwitchCapped}"
        entries += "           Registry policies : ${when (state.registryPolicies) {
            -1   -> "unknown (admin required)"
            0    -> "none"
            else -> "${state.registryPolicies} value(s) active"
        }}"
        entries += "           Firewall rules    : ${when (state.firewallRuleCount) {
            -1   -> "unknown (admin required)"
            0    -> "none"
            else -> "${state.firewallRuleCount} rule(s)"
        }}"
        entries += "           Hosts entries     : ${when (state.hostsEntryCount) {
            -1   -> "unknown (admin required)"
            0    -> "none"
            else -> "${state.hostsEntryCount} entry/entries"
        }}"
        entries += ""
    }

    fun logStep(number: Int, step: RecoveryStep, result: StepResult) {
        val status = result.status.name.padEnd(8)
        val detail = if (result.detail.isNotBlank()) "  →  ${result.detail}" else ""
        entries += "[${now()}]  STEP $number  [$status]  ${step.name}$detail"
    }

    /**
     * Writes all collected entries to disk.
     * @return Absolute path of the log file, or empty string if writing failed.
     */
    fun finish(anyFailed: Boolean): String {
        entries += ""
        entries += sep('-')
        entries += if (anyFailed)
            "  RESULT  : PARTIAL — one or more steps failed (check FAILED lines above)"
        else
            "  RESULT  : SUCCESS — all steps completed"
        entries += sep()

        return try {
            logFile.parentFile?.mkdirs()
            logFile.writeText(entries.joinToString(System.lineSeparator()))
            logFile.absolutePath
        } catch (_: Exception) {
            ""
        }
    }
}
