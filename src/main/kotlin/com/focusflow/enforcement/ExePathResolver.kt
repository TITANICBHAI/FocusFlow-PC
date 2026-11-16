package com.focusflow.enforcement

import java.io.File

/**
 * Finds the full path of a program so a firewall rule can name it.
 *
 * The order matters. The real Windows copy is looked up first: a person who copies `cmd.exe` to a
 * temp folder and runs it must not make the firewall rule point at their copy (the rule would then
 * not cover the real tool). Only after the system folders is a running process of that name
 * trusted, then the usual install roots.
 */
internal class ExePathResolver(
    systemRoot: String?,
    private val env: (String) -> String? = System::getenv,
    private val exists: (String) -> Boolean = { File(it).exists() },
    private val runningImage: (exeName: String) -> String? = ::findRunningImage,
    private val subdirectories: (String) -> List<String> = ::listSubdirectories
) {
    private val root = (systemRoot?.takeIf { it.isNotBlank() } ?: "C:\\Windows").trimEnd('\\', '/')

    private val systemDirectories = listOf(
        "$root\\System32",
        "$root\\System32\\WindowsPowerShell\\v1.0",
        "$root\\System32\\wbem",
        "$root\\SysWOW64",
        "$root\\SysWOW64\\WindowsPowerShell\\v1.0",
        root
    )

    fun resolve(exeName: String): String? {
        systemDirectories.map { "$it\\$exeName" }.firstOrNull(exists)?.let { return it }
        runningImage(exeName)?.let { return it }

        val installRoots = listOfNotNull(
            env("ProgramFiles"), env("ProgramFiles(x86)"), env("LOCALAPPDATA"), env("APPDATA")
        )
        for (installRoot in installRoots) {
            "$installRoot\\$exeName".takeIf(exists)?.let { return it }
            subdirectories(installRoot).map { "$it\\$exeName" }.firstOrNull(exists)?.let { return it }
        }
        return null
    }

    private companion object {
        fun findRunningImage(exeName: String): String? = try {
            ProcessHandle.allProcesses()
                .map { it.info().command().orElse(null) }
                .filter { cmd ->
                    cmd != null && cmd.substringAfterLast('\\').substringAfterLast('/').equals(exeName, ignoreCase = true)
                }
                .findFirst()
                .orElse(null)
        } catch (_: Exception) {
            null
        }

        fun listSubdirectories(dir: String): List<String> =
            File(dir).listFiles()?.filter { it.isDirectory }?.map { it.path }.orEmpty()
    }
}
