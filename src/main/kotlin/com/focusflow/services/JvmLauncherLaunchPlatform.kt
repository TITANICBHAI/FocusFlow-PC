package com.focusflow.services

import com.focusflow.enforcement.EnforcementLog
import com.focusflow.enforcement.GlobalKeyboardHook
import com.focusflow.enforcement.WindowRaiser
import com.focusflow.enforcement.ElevationManager
import com.focusflow.enforcement.isWindows
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.io.File
import java.io.IOException
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

    override fun validateExecutable(app: FocusLauncherApp): ExecutablePreflightResult {
        val candidateName = app.exePath ?: app.processName
        if (isWindowsAppsPath(candidateName)) {
            return ExecutablePreflightResult.UnsupportedCandidate
        }
        if (isUnsupportedLauncherExecutableName(candidateName)) {
            return ExecutablePreflightResult.UnsupportedCandidate
        }

        return if (resolveExecutable(app) != null) {
            ExecutablePreflightResult.Supported
        } else {
            ExecutablePreflightResult.NotFound
        }
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
        val pathHint = app.exePath?.takeIf(String::isNotBlank)
        if ((pathHint ?: app.processName).let(::isWindowsAppsPath)) {
            return SpawnResult.Failed(
                "This is a Microsoft Store app and can't be started from here yet",
                canRetry = false
            )
        }
        if (isUnsupportedLauncherExecutableName(pathHint ?: app.processName)) {
            return SpawnResult.Failed(
                "This installer, updater, or system utility is not supported by Focus Launcher.",
                canRetry = false
            )
        }

        return try {
            val executable = resolveExecutable(app)
                ?: return SpawnResult.Failed(
                    "CreateProcess error=2: the app executable could not be found.",
                    canRetry = false
                )
            val workingDirectory = launcherWorkingDirectory(executable)
            val spawnMode = launcherSpawnMode(
                isWindowsHost = isWindows,
                focusFlowElevated = if (isWindows) ElevationManager.isElevated else false
            )
            val pid = if (spawnMode == LauncherSpawnMode.InteractiveUserToken) {
                val prefix = attemptId?.let { "attemptId=$it " }.orEmpty()
                EnforcementLog.info(
                    "Launcher",
                    "${prefix}spawn strategy=user-shell-token app=${app.processName}"
                )
                WindowsUnelevatedLauncher.start(executable, workingDirectory)
            } else {
                val process = ProcessBuilder(executable.absolutePath)
                    .apply { workingDirectory?.let(::directory) }
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                try {
                    process.outputStream.close()
                } catch (closeFailure: Exception) {
                    EnforcementLog.warn(
                        "Launcher",
                        "spawned app but could not close child stdin app=${app.processName}",
                        closeFailure
                    )
                }
                process.pid()
            }
            SpawnResult.Started(pid, processCreationTimeMs(pid))
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
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
            SpawnResult.Failed("Couldn't start this app. Tap to retry.")
        }
    }

    private fun resolveExecutable(app: FocusLauncherApp): File? =
        resolveLauncherExecutableFile(
            tilePath = app.exePath,
            processName = app.processName,
            isWindowsHost = isWindows,
            appPathsLookup = ::lookupAppPaths,
            pathValue = System.getenv("PATH"),
            runningInstancePaths = { runningInstancePaths(app.processName) }
        )

    private fun lookupAppPaths(executableName: String): String? {
        if (!isWindows) return null

        val appName = executableName.replace('\\', '/').substringAfterLast('/')
        val keys = listOf(
            WinReg.HKEY_CURRENT_USER to "Software\\Microsoft\\Windows\\CurrentVersion\\App Paths\\$appName",
            WinReg.HKEY_LOCAL_MACHINE to "Software\\Microsoft\\Windows\\CurrentVersion\\App Paths\\$appName",
            WinReg.HKEY_LOCAL_MACHINE to
                "Software\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\App Paths\\$appName"
        )
        for ((hive, key) in keys) {
            try {
                if (Advapi32Util.registryValueExists(hive, key, "")) {
                    return Advapi32Util.registryGetStringValue(hive, key, "")
                }
            } catch (failure: Exception) {
                EnforcementLog.warn(
                    "Launcher",
                    "App Paths lookup failed exe=$appName",
                    failure
                )
            }
        }
        return null
    }

    private fun runningInstancePaths(processName: String): Sequence<String> {
        val expectedName = processName.replace('\\', '/').substringAfterLast('/').lowercase(Locale.ROOT)
        return ProcessSnapshot.snapshotProcesses().asSequence()
            .filter { process ->
                process.processName.isBlank() ||
                    process.processName.replace('\\', '/').substringAfterLast('/')
                        .equals(expectedName, ignoreCase = true)
            }
            .mapNotNull(ProcessSnapshot::resolveExecutablePath)
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

    private fun processCreationTimeMs(pid: Long): Long? =
        runCatching {
            ProcessHandle.of(pid).orElse(null)
                ?.info()
                ?.startInstant()
                ?.orElse(null)
                ?.toEpochMilli()
        }.getOrNull()
}

internal fun isUnsupportedLauncherExecutableName(pathOrName: String): Boolean {
    val basename = pathOrName.replace('\\', '/').substringAfterLast('/')
    return Regex(
        "(?i)(^|[._ -])(uninstall(?:er)?|unins\\w*|updater?|installer|setup)([._ -]|$)"
    ).containsMatchIn(basename.substringBeforeLast('.', basename))
}

internal fun launcherWorkingDirectory(executable: File): File? =
    executable.absoluteFile.parentFile?.takeIf(File::isDirectory)

internal enum class LauncherSpawnMode {
    Direct,
    InteractiveUserToken
}

internal fun launcherSpawnMode(
    isWindowsHost: Boolean,
    focusFlowElevated: Boolean
): LauncherSpawnMode =
    if (isWindowsHost && focusFlowElevated) {
        LauncherSpawnMode.InteractiveUserToken
    } else {
        LauncherSpawnMode.Direct
    }

internal fun resolveLauncherExecutableFile(
    tilePath: String?,
    processName: String,
    isWindowsHost: Boolean,
    appPathsLookup: (String) -> String?,
    pathValue: String?,
    runningInstancePaths: () -> Sequence<String>
): File? {
    val executableName = processName.replace('\\', '/').substringAfterLast('/')
    val candidatePaths = sequence {
        yield(tilePath)
        if (isWindowsHost) {
            yield(appPathsLookup(executableName)?.let(::executablePathFromAppPathsValue))
        }
        yieldAll(findExecutableOnPath(executableName, pathValue, isWindowsHost))
        yieldAll(runningInstancePaths())
    }

    return candidatePaths
        .mapNotNull { path -> usableExecutable(path, isWindowsHost) }
        .firstOrNull()
}

internal fun executablePathFromAppPathsValue(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null

    if (trimmed.startsWith('"')) {
        val closingQuote = trimmed.indexOf('"', startIndex = 1)
        if (closingQuote > 1) return trimmed.substring(1, closingQuote)
    }

    val executableSuffix = Regex("(?i)\\.(exe|com)(?=\\s|,|$)").find(trimmed)
        ?: return trimmed.removeSurrounding("\"").takeIf(String::isNotBlank)
    return trimmed.substring(0, executableSuffix.range.last + 1)
        .trim()
        .removeSurrounding("\"")
}

private fun findExecutableOnPath(
    executableName: String,
    pathValue: String?,
    isWindowsHost: Boolean
): Sequence<String> = sequence {
    if (pathValue.isNullOrBlank()) return@sequence
    val names = when {
        !isWindowsHost -> listOf(executableName)
        executableName.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("exe", "com") ->
            listOf(executableName)
        else -> listOf("$executableName.exe", "$executableName.com")
    }
    val separator = if (isWindowsHost) ';' else File.pathSeparatorChar
    for (directory in pathValue.split(separator)) {
        if (directory.isBlank()) continue
        for (name in names) yield(File(directory.trim().trim('"'), name).path)
    }
}

private fun usableExecutable(path: String?, isWindowsHost: Boolean): File? {
    val candidatePath = path?.trim()?.removeSurrounding("\"")
        ?.takeIf(String::isNotBlank) ?: return null
    val file = File(candidatePath)
    if (!file.isFile || !file.canRead()) return null
    if (isWindowsHost) {
        val normalized = file.path.replace('/', '\\').lowercase(Locale.ROOT)
        if (normalized.contains("\\windowsapps\\")) return null
        if (file.extension.lowercase(Locale.ROOT) !in setOf("exe", "com")) return null
    }
    if (isUnsupportedLauncherExecutableName(file.name)) return null
    return file.absoluteFile
}

/**
 * Starts an application with the interactive shell's non-elevated token. If that
 * token cannot be obtained or is itself elevated, fail closed rather than
 * launching the app with FocusFlow's elevated token.
 */
internal object WindowsUnelevatedLauncher {
    fun start(executable: File, workingDirectory: File?): Long {
        val shellWindow = User32Api.INSTANCE.GetShellWindow()
            ?: throw IOException("The interactive Windows shell is unavailable.")
        val shellPid = IntByReference()
        if (User32Api.INSTANCE.GetWindowThreadProcessId(shellWindow, shellPid) == 0 ||
            shellPid.value <= 0
        ) {
            throw IOException("Could not identify the interactive Windows shell process.")
        }

        val kernel = Kernel32Api.INSTANCE
        val advapi = Advapi32Api.INSTANCE
        val shellProcess = kernel.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, shellPid.value)
            ?: throw win32Failure("OpenProcess")
        var shellToken: Pointer? = null
        var primaryToken: Pointer? = null
        var processInfo: LauncherProcessInformation? = null
        try {
            val shellTokenRef = PointerByReference()
            if (!advapi.OpenProcessToken(
                    shellProcess,
                    TOKEN_QUERY or TOKEN_DUPLICATE,
                    shellTokenRef
                )
            ) {
                throw win32Failure("OpenProcessToken")
            }
            shellToken = shellTokenRef.value

            val primaryTokenRef = PointerByReference()
            if (!advapi.DuplicateTokenEx(
                    shellToken,
                    TOKEN_QUERY or TOKEN_DUPLICATE or TOKEN_ASSIGN_PRIMARY,
                    null,
                    SECURITY_IMPERSONATION,
                    TOKEN_PRIMARY,
                    primaryTokenRef
                )
            ) {
                throw win32Failure("DuplicateTokenEx")
            }
            primaryToken = primaryTokenRef.value

            if (tokenIsElevated(advapi, primaryToken)) {
                throw IOException("CreateProcess error=5: the interactive shell token is elevated.")
            }

            val startup = LauncherStartupInfo().apply { cb = size() }
            val created = LauncherProcessInformation()
            processInfo = created
            val started = advapi.CreateProcessWithTokenW(
                primaryToken,
                0,
                WString(executable.absolutePath),
                null,
                0,
                null,
                workingDirectory?.let { WString(it.absolutePath) },
                startup,
                created
            )
            if (!started) throw win32Failure("CreateProcessWithTokenW")
            return created.dwProcessId.toLong()
        } finally {
            processInfo?.let { info ->
                if (info.hThread != null) kernel.CloseHandle(info.hThread)
                if (info.hProcess != null) kernel.CloseHandle(info.hProcess)
            }
            if (primaryToken != null) kernel.CloseHandle(primaryToken)
            if (shellToken != null) kernel.CloseHandle(shellToken)
            kernel.CloseHandle(shellProcess)
        }
    }

    private fun tokenIsElevated(api: Advapi32Api, token: Pointer?): Boolean {
        Memory(Int.SIZE_BYTES.toLong()).use { value ->
            val returnedLength = IntByReference()
            if (!api.GetTokenInformation(
                    token,
                    TOKEN_ELEVATION_INFORMATION,
                    value,
                    Int.SIZE_BYTES,
                    returnedLength
                )
            ) {
                throw win32Failure("GetTokenInformation")
            }
            return value.getInt(0) != 0
        }
    }

    private fun win32Failure(operation: String): IOException =
        IOException("$operation failed with Windows error=${Native.getLastError()}")

    @Structure.FieldOrder(
        "cb", "lpReserved", "lpDesktop", "lpTitle", "dwX", "dwY", "dwXSize", "dwYSize",
        "dwXCountChars", "dwYCountChars", "dwFillAttribute", "dwFlags", "wShowWindow",
        "cbReserved2", "lpReserved2", "hStdInput", "hStdOutput", "hStdError"
    )
    class LauncherStartupInfo : Structure() {
        @JvmField var cb: Int = 0
        @JvmField var lpReserved: WString? = null
        @JvmField var lpDesktop: WString? = null
        @JvmField var lpTitle: WString? = null
        @JvmField var dwX: Int = 0
        @JvmField var dwY: Int = 0
        @JvmField var dwXSize: Int = 0
        @JvmField var dwYSize: Int = 0
        @JvmField var dwXCountChars: Int = 0
        @JvmField var dwYCountChars: Int = 0
        @JvmField var dwFillAttribute: Int = 0
        @JvmField var dwFlags: Int = 0
        @JvmField var wShowWindow: Short = 0
        @JvmField var cbReserved2: Short = 0
        @JvmField var lpReserved2: Pointer? = null
        @JvmField var hStdInput: Pointer? = null
        @JvmField var hStdOutput: Pointer? = null
        @JvmField var hStdError: Pointer? = null
    }

    @Structure.FieldOrder("hProcess", "hThread", "dwProcessId", "dwThreadId")
    class LauncherProcessInformation : Structure() {
        @JvmField var hProcess: Pointer? = null
        @JvmField var hThread: Pointer? = null
        @JvmField var dwProcessId: Int = 0
        @JvmField var dwThreadId: Int = 0
    }

    private interface User32Api : StdCallLibrary {
        fun GetShellWindow(): Pointer?
        fun GetWindowThreadProcessId(window: Pointer?, processId: IntByReference): Int

        companion object {
            val INSTANCE: User32Api =
                Native.load("user32", User32Api::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }

    private interface Kernel32Api : StdCallLibrary {
        fun OpenProcess(access: Int, inheritHandle: Boolean, processId: Int): Pointer?
        fun CloseHandle(handle: Pointer?): Boolean

        companion object {
            val INSTANCE: Kernel32Api =
                Native.load("kernel32", Kernel32Api::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }

    private interface Advapi32Api : StdCallLibrary {
        fun OpenProcessToken(
            processHandle: Pointer?,
            desiredAccess: Int,
            tokenHandle: PointerByReference
        ): Boolean

        fun DuplicateTokenEx(
            existingToken: Pointer?,
            desiredAccess: Int,
            tokenAttributes: Pointer?,
            impersonationLevel: Int,
            tokenType: Int,
            newToken: PointerByReference
        ): Boolean

        fun GetTokenInformation(
            token: Pointer?,
            informationClass: Int,
            tokenInformation: Pointer,
            tokenInformationLength: Int,
            returnLength: IntByReference
        ): Boolean

        fun CreateProcessWithTokenW(
            token: Pointer?,
            logonFlags: Int,
            applicationName: WString?,
            commandLine: Pointer?,
            creationFlags: Int,
            environment: Pointer?,
            currentDirectory: WString?,
            startupInfo: LauncherStartupInfo,
            processInformation: LauncherProcessInformation
        ): Boolean

        companion object {
            val INSTANCE: Advapi32Api =
                Native.load("advapi32", Advapi32Api::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }

    private const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
    private const val TOKEN_ASSIGN_PRIMARY = 0x0001
    private const val TOKEN_DUPLICATE = 0x0002
    private const val TOKEN_QUERY = 0x0008
    private const val SECURITY_IMPERSONATION = 2
    private const val TOKEN_PRIMARY = 1
    private const val TOKEN_ELEVATION_INFORMATION = 20
}
