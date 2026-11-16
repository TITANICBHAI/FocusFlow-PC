package com.focusflow.enforcement

import java.security.MessageDigest

/** What can be observed about a running PowerShell process. Any unknown value is null. */
internal data class TaskRunFacts(
    val imagePath: String?,
    val commandLine: String?,
    val parentImagePath: String?
)

/**
 * One scheduled-task run FocusFlow generates: the exact arguments PowerShell receives and, for a
 * script run, the script file with the SHA-256 of the content FocusFlow wrote to it.
 */
internal class TaskRunExpectation(
    val arguments: String,
    val scriptPath: String?,
    val scriptSha256: String?
)

/**
 * Decides whether a PowerShell process is one of FocusFlow's own scheduled-task runs (the
 * watchdog and the taskbar guard).
 *
 * These are not children of FocusFlow: Task Scheduler starts them, usually through
 * `conhost.exe --headless`. FocusFlow cannot register them when they start, so they are
 * recognised by what they are. A run is trusted only when EVERY one of these holds:
 *   1. the program is the real Windows PowerShell in the Windows folder (not a copy elsewhere);
 *   2. its arguments are exactly what FocusFlow's task definition passes, nothing added or removed;
 *   3. it was started by the console host or the Task Scheduler service host from the Windows
 *      folder, not by a shell, a script or any other program;
 *   4. for a script run, the script file on disk still has exactly the content FocusFlow wrote.
 *
 * A person who copies the command can therefore only run FocusFlow's own unchanged script. Any
 * fact that cannot be read counts against trust, so this never spares more than before.
 */
internal class TaskRunTrust(
    systemRoot: String?,
    private val expectations: () -> List<TaskRunExpectation>,
    private val fileSha256: (String) -> String?
) {
    private val root = systemRoot?.takeIf(String::isNotBlank)?.let { normalize(it).trimEnd('\\') }
    private val powerShellImage = root?.let { "$it\\system32\\windowspowershell\\v1.0\\powershell.exe" }
    private val allowedParents = root?.let { setOf("$it\\system32\\conhost.exe", "$it\\system32\\svchost.exe") }
        ?: emptySet()

    fun isTrusted(facts: TaskRunFacts): Boolean {
        val image = facts.imagePath?.let(::normalize) ?: return false
        val parent = facts.parentImagePath?.let(::normalize) ?: return false
        val commandLine = facts.commandLine ?: return false
        if (powerShellImage == null || image != powerShellImage) return false
        if (parent !in allowedParents) return false

        val actualArguments = argumentsAfterProgram(commandLine) ?: return false
        return expectations().any { expected ->
            if (normalizeArguments(expected.arguments) != actualArguments) return@any false
            val scriptPath = expected.scriptPath ?: return@any true
            val expectedHash = expected.scriptSha256 ?: return@any false
            fileSha256(scriptPath)?.equals(expectedHash, ignoreCase = true) == true
        }
    }

    /** Everything after the program name, with the program name's quoting removed. */
    private fun argumentsAfterProgram(commandLine: String): String? {
        val text = commandLine.trim()
        val rest = if (text.startsWith("\"")) {
            val end = text.indexOf('"', 1)
            if (end < 0) return null
            text.substring(end + 1)
        } else {
            val space = text.indexOf(' ')
            if (space < 0) "" else text.substring(space)
        }
        return normalizeArguments(rest)
    }

    private fun normalizeArguments(arguments: String): String =
        arguments.trim().replace(Regex("\\s+"), " ").lowercase()

    private fun normalize(path: String): String = OwnedProcessRegistry.normalize(path)

    companion object {
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
