package com.focusflow.services

import com.focusflow.enforcement.EnforcementLog
import com.focusflow.enforcement.WindowRaiser
import com.focusflow.enforcement.isRunningAsAdmin
import com.focusflow.enforcement.isWindows
import java.io.File
import java.util.Locale

internal object JvmLauncherLaunchPlatform : LaunchPlatform {
    override fun nowMs(): Long = System.currentTimeMillis()

    override fun findInstances(app: FocusLauncherApp): List<RunningInstance> =
        ProcessSnapshot.findInstances(app, nowMs())

    override fun findWindows(pids: Set<Long>): List<WindowRef> =
        WindowRaiser.findWindows(pids)

    override fun spawn(app: FocusLauncherApp): SpawnResult {
        val executablePath = app.exePath?.takeIf(String::isNotBlank)
        if (executablePath != null && isWindowsAppsPath(executablePath)) {
            return SpawnResult.Failed(
                "This is a Microsoft Store app and can't be started from here yet",
                canRetry = false
            )
        }

        return try {
            val processBuilder = when {
                executablePath != null -> {
                    val executable = File(executablePath)
                    ProcessBuilder(executablePath).apply {
                        executable.parentFile?.takeIf(File::isDirectory)?.let(::directory)
                    }
                }
                isWindows -> ProcessBuilder("cmd", "/c", "start", "", app.processName)
                else -> ProcessBuilder(app.processName)
            }.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)

            val process = processBuilder.start()
            SpawnResult.Started(process.pid())
        } catch (failure: Exception) {
            EnforcementLog.warn(
                "Launcher",
                "spawn exception app=${app.processName}",
                failure
            )
            val detail = failure.message?.takeIf(String::isNotBlank)
                ?: failure.javaClass.simpleName
            SpawnResult.Failed("Couldn't open ${app.displayName}: $detail")
        }
    }

    override suspend fun raise(window: WindowRef): RaiseResult =
        WindowRaiser.raise(window)

    override fun focusFlowElevated(): Boolean? =
        if (isWindows) isRunningAsAdmin() else null

    private fun isWindowsAppsPath(path: String): Boolean {
        val normalized = path.replace('/', '\\').lowercase(Locale.ROOT)
        return normalized.contains("\\windowsapps\\")
    }
}
