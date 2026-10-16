package com.focusflow.services

import com.focusflow.enforcement.EnforcementLog
import com.focusflow.enforcement.GlobalKeyboardHook
import com.focusflow.enforcement.WindowRaiser
import com.focusflow.enforcement.ElevationManager
import com.focusflow.enforcement.isWindows
import java.io.File
import java.util.Locale

internal object JvmLauncherLaunchPlatform : LaunchPlatform {
    override fun nowMs(): Long = System.currentTimeMillis()

    override fun findInstances(app: FocusLauncherApp): List<RunningInstance> =
        ProcessSnapshot.findInstances(app, nowMs())

    override fun processSnapshot(app: FocusLauncherApp): List<ProcessSnapshotEntry> =
        ProcessSnapshot.snapshotProcesses()

    override fun unsupportedReason(app: FocusLauncherApp): String? =
        (app.exePath ?: app.processName).takeIf(::isWindowsAppsPath)?.let {
            "Microsoft Store apps cannot be launched from Focus Launcher yet."
        }

    override fun findWindows(pids: Set<Long>): List<WindowRef> =
        WindowRaiser.findWindows(pids)

    override fun findWindows(pids: Set<Long>, attemptId: String): List<WindowRef> =
        WindowRaiser.findWindows(pids, attemptId)

    override fun spawn(app: FocusLauncherApp): SpawnResult =
        spawnInternal(app, attemptId = null)

    override fun spawn(app: FocusLauncherApp, attemptId: String): SpawnResult =
        spawnInternal(app, attemptId)

    private fun spawnInternal(app: FocusLauncherApp, attemptId: String?): SpawnResult {
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
            val prefix = attemptId?.let { "attemptId=$it " }.orEmpty()
            val detail = failure.message
                ?.replace(Regex("[\\r\\n\\t]+"), " ")
                ?.take(160)
                ?: failure.javaClass.simpleName
            EnforcementLog.warn(
                "Launcher",
                "${prefix}spawn exception app=${app.processName} " +
                    "error=${failure.javaClass.simpleName} detail=$detail"
            )
            val userDetail = failure.message?.takeIf(String::isNotBlank)
                ?: failure.javaClass.simpleName
            SpawnResult.Failed("Couldn't open ${app.displayName}: $userDetail")
        }
    }

    override suspend fun raise(window: WindowRef): RaiseResult =
        WindowRaiser.raise(window)

    override suspend fun raise(window: WindowRef, attemptId: String): RaiseResult =
        WindowRaiser.raise(window, attemptId)

    override fun focusFlowElevated(): Boolean? =
        if (isWindows) ElevationManager.isElevated else null

    override fun lastForegroundLockCall(): String? =
        GlobalKeyboardHook.lastForegroundLockCall

    override fun processIsAlive(pid: Long): Boolean? =
        runCatching { ProcessHandle.of(pid).map { it.isAlive }.orElse(false) }.getOrNull()

    override fun processIsAlive(pid: Long, creationTimeMs: Long?): Boolean? =
        ProcessSnapshot.isAlive(pid, creationTimeMs)

    override fun finishAttempt(attemptId: String) {
        WindowRaiser.finishAttempt(attemptId)
    }

    private fun isWindowsAppsPath(path: String): Boolean {
        val normalized = path.replace('/', '\\').lowercase(Locale.ROOT)
        return normalized.contains("\\windowsapps\\")
    }
}
