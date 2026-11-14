package com.focusflow.services

import com.focusflow.enforcement.EnforcementLog
import com.focusflow.enforcement.GlobalKeyboardHook
import com.focusflow.enforcement.WindowRaiser
import com.focusflow.enforcement.ElevationManager
import com.focusflow.enforcement.LauncherAppIssue
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

    internal fun resolveForSession(app: FocusLauncherApp): FocusLauncherApp? {
        val candidateName = app.exePath?.takeIf(String::isNotBlank) ?: app.processName
        if (isWindowsAppsPath(candidateName) ||
            app.candidateIssue == LauncherAppIssue.StoreApp ||
            app.candidateIssue == LauncherAppIssue.InstallerOrUpdater ||
            isUnsupportedLauncherExecutableName(candidateName) ||
            isUnsupportedLauncherExecutableName(app.processName)
        ) {
            return null
        }

        val executable = resolveExecutable(app) ?: return null
        return app.copy(
            exePath = executable.absolutePath,
            candidateIssue = null
        )
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
            if (spawnMode == LauncherSpawnMode.InteractiveUserToken) {
                return spawnWithUserRights(app, executable, workingDirectory, attemptId)
            }
            val prefix = attemptId?.let { "attemptId=$it " }.orEmpty()
            EnforcementLog.info(
                "Launcher",
                "${prefix}spawn strategy=direct app=${app.processName}"
            )
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
            val pid = process.pid()
            SpawnResult.Started(pid, processCreationTimeMs(pid), method = "direct")
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

    /**
     * Strategy whose launch was last *verified* (a real window of a normal-rights app
     * was raised); tried first next time. Never set merely because a call returned.
     */
    @Volatile
    private var preferredStrategy: LauncherSpawnStrategy? = null

    private val privilegeChecked = java.util.concurrent.atomic.AtomicBoolean(false)
    private val diagnosticsLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * FocusFlow is elevated, so the app must be started with the user's normal
     * rights. Try each supported method in turn, automatically, and report which
     * one was used. Never fall back to starting the app with FocusFlow's own token.
     * Nothing is remembered here: the coordinator calls [launchVerified] once the
     * launch is proven by a raised window.
     */
    private fun spawnWithUserRights(
        app: FocusLauncherApp,
        executable: File,
        workingDirectory: File?,
        attemptId: String?
    ): SpawnResult {
        val prefix = attemptId?.let { "attemptId=$it " }.orEmpty()
        val ownPid = ProcessHandle.current().pid()
        val failures = mutableListOf<String>()

        if (privilegeChecked.compareAndSet(false, true)) {
            val state = runCatching { WindowsUnelevatedLauncher.ensureImpersonatePrivilege() }
                .getOrElse { "unreadable(${it.javaClass.simpleName})" }
            EnforcementLog.info("Launcher", "${prefix}token-launch SeImpersonatePrivilege=$state")
        }

        for (strategy in orderedSpawnStrategies(preferredStrategy)) {
            EnforcementLog.info(
                "Launcher",
                "${prefix}spawn strategy=${strategy.logName} app=${app.processName} " +
                    "focusFlowPid=$ownPid"
            )
            try {
                return when (strategy) {
                    LauncherSpawnStrategy.UserShellToken -> {
                        val pid = WindowsUnelevatedLauncher.start(executable, workingDirectory)
                        SpawnResult.Started(pid, processCreationTimeMs(pid), strategy.logName)
                    }
                    LauncherSpawnStrategy.UserShellTokenFull -> {
                        val pid = WindowsUnelevatedLauncher.start(
                            executable,
                            workingDirectory,
                            WindowsUnelevatedLauncher.TokenRights.Full
                        )
                        SpawnResult.Started(pid, processCreationTimeMs(pid), strategy.logName)
                    }
                    LauncherSpawnStrategy.ExplorerHandoff -> {
                        WindowsExplorerLauncher.start(executable)
                        SpawnResult.Handoff(strategy.logName)
                    }
                }
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError) throw failure
                val detail = failure.message
                    ?.replace(Regex("[\\r\\n\\t]+"), " ")
                    ?.take(160)
                    ?: failure.javaClass.simpleName
                failures += "${strategy.logName}: $detail"
                EnforcementLog.warn(
                    "Launcher",
                    "${prefix}spawn exception strategy=${strategy.logName} " +
                        "app=${app.processName} error=${failure.javaClass.simpleName} " +
                        "detail=$detail"
                )
                if (strategy != LauncherSpawnStrategy.ExplorerHandoff &&
                    diagnosticsLogged.compareAndSet(false, true)
                ) {
                    // Once per run: the facts that decide whether this API may be used
                    // here, so the next log shows the real cause of a repeated failure.
                    val facts = runCatching { WindowsUnelevatedLauncher.diagnostics() }
                        .getOrElse { "unreadable(${it.javaClass.simpleName})" }
                    EnforcementLog.warn("Launcher", "${prefix}token-launch diagnostics $facts")
                }
            }
        }

        return SpawnResult.Failed(
            "Couldn't start this app. Tap to retry. [${failures.joinToString(" | ")}]"
        )
    }

    override fun launchVerified(method: String) {
        val strategy = LauncherSpawnStrategy.entries.firstOrNull { it.logName == method } ?: return
        if (preferredStrategy != strategy) {
            EnforcementLog.info(
                "Launcher",
                "spawn strategy=${strategy.logName} verified; it will be tried first from now on"
            )
            preferredStrategy = strategy
        }
    }

    override fun processElevation(pid: Long, creationTimeMs: Long): ProcessElevation {
        if (!isWindows) return ProcessElevation.Unknown
        // The same process must be there before and after the token is read, so a
        // recycled PID can never be judged (or later closed) as someone else's app.
        if (ProcessSnapshot.isAlive(pid, creationTimeMs) != true) return ProcessElevation.Unknown
        val elevated = WindowsUnelevatedLauncher.isProcessElevated(pid)
            ?: return ProcessElevation.Unknown
        if (ProcessSnapshot.isAlive(pid, creationTimeMs) != true) return ProcessElevation.Unknown
        return if (elevated) ProcessElevation.Elevated else ProcessElevation.NotElevated
    }

    override fun terminateProcess(pid: Long, creationTimeMs: Long): Boolean {
        return try {
            // destroyForcibly() on a handle obtained here re-checks the start time in
            // the JDK, so a PID recycled after this comparison is not terminated.
            val handle = ProcessHandle.of(pid).orElse(null) ?: return false
            val startedAt = handle.info().startInstant().orElse(null)?.toEpochMilli()
                ?: return false
            if (startedAt != creationTimeMs) return false
            handle.destroyForcibly()
        } catch (_: Exception) {
            false
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
        return matchingLauncherExecutablePaths(
            expectedProcessName = processName,
            processes = ProcessSnapshot.snapshotProcesses(),
            resolvePath = ProcessSnapshot::resolveExecutablePath
        ).asSequence()
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

internal data class LauncherAppsResolution(
    val resolved: List<FocusLauncherApp>,
    val unavailable: List<FocusLauncherApp>
)

internal fun resolveLauncherAppsForSession(
    apps: List<FocusLauncherApp>,
    resolve: (FocusLauncherApp) -> FocusLauncherApp?
): LauncherAppsResolution {
    val resolved = ArrayList<FocusLauncherApp>(apps.size)
    val unavailable = ArrayList<FocusLauncherApp>()
    apps.forEach { app ->
        val current = resolve(app)
        if (current == null) unavailable += app else resolved += current
    }
    return LauncherAppsResolution(resolved, unavailable)
}

internal fun matchingLauncherExecutablePaths(
    expectedProcessName: String,
    processes: List<ProcessSnapshotEntry>,
    resolvePath: (ProcessSnapshotEntry) -> String?
): List<String> {
    val expected = expectedProcessName.replace('\\', '/')
        .substringAfterLast('/')
        .lowercase(Locale.ROOT)
    if (expected.isBlank()) return emptyList()

    return processes.asSequence()
        .filter { process ->
            val processName = process.processName.replace('\\', '/')
                .substringAfterLast('/')
                .lowercase(Locale.ROOT)
            processName.isBlank() || processName == expected
        }
        .mapNotNull(resolvePath)
        .filter { path ->
            path.replace('\\', '/').substringAfterLast('/')
                .equals(expected, ignoreCase = true)
        }
        .distinct()
        .toList()
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

/** Ways to start an app with the user's normal rights while FocusFlow is elevated. */
internal enum class LauncherSpawnStrategy(val logName: String) {
    /** Shell token duplicated with query/duplicate/assign-primary plus adjust rights. */
    UserShellToken("user-shell-token"),

    /** Same API with TOKEN_ALL_ACCESS on the duplicate: a different parameter set. */
    UserShellTokenFull("user-shell-token-full"),

    /** Ask the running Explorer to open the app; no PID comes back. */
    ExplorerHandoff("explorer-handoff")
}

/** The last strategy that worked goes first; the others follow in their default order. */
internal fun orderedSpawnStrategies(
    preferred: LauncherSpawnStrategy?
): List<LauncherSpawnStrategy> {
    val all = LauncherSpawnStrategy.entries
    return if (preferred == null) all else listOf(preferred) + all.filter { it != preferred }
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
    /**
     * Which access mask the duplicated shell token gets. The original code used only
     * the three rights Microsoft documents (query, duplicate, assign primary) and
     * every call failed with error 5 in the field, so neither set below repeats
     * that mask. Whether either one works on a given PC is not known without
     * testing on Windows; the log records the outcome and, on failure, the facts
     * gathered by [diagnostics].
     *
     * No explicit desktop is passed: the documentation says a NULL desktop makes the
     * API grant the new process access to the caller's desktop itself, while an
     * explicit desktop makes the caller responsible for those permissions.
     */
    enum class TokenRights {
        /** Documented rights plus TOKEN_ADJUST_DEFAULT and TOKEN_ADJUST_SESSIONID. */
        Extended,

        /** TOKEN_ALL_ACCESS. */
        Full
    }

    fun start(
        executable: File,
        workingDirectory: File?,
        rights: TokenRights = TokenRights.Extended
    ): Long {
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
                    if (rights == TokenRights.Full) TOKEN_ALL_ACCESS else PRIMARY_TOKEN_ACCESS,
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

            val startup = LauncherStartupInfo().apply {
                cb = size()
                // lpDesktop stays null on purpose (see TokenRights).
            }
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
            if (!started) {
                val error = Native.getLastError()
                throw IOException(
                    "CreateProcessWithTokenW failed with Windows error=$error " +
                        "(rights=$rights, shellPid=${shellPid.value})"
                )
            }
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

    /**
     * CreateProcessWithTokenW needs SeImpersonatePrivilege in the caller's token. An
     * elevated administrator normally has it enabled; if it is present but disabled
     * it is switched on here. Returns a short state for the log. Nothing is added to
     * the token: a privilege the token does not hold cannot be granted this way.
     */
    fun ensureImpersonatePrivilege(): String {
        val kernel = Kernel32Api.INSTANCE
        val advapi = Advapi32Api.INSTANCE
        val tokenRef = PointerByReference()
        if (!advapi.OpenProcessToken(
                kernel.GetCurrentProcess(),
                TOKEN_QUERY or TOKEN_ADJUST_PRIVILEGES,
                tokenRef
            )
        ) {
            return "unreadable(OpenProcessToken error=${Native.getLastError()})"
        }
        val token = tokenRef.value
        try {
            val state = impersonatePrivilegeState(advapi, token)
            if (state != "present-disabled") return state
            Memory(16).use { newState ->
                newState.setInt(0, 1)
                newState.setInt(4, SE_IMPERSONATE_LUID_LOW)
                newState.setInt(8, 0)
                newState.setInt(12, SE_PRIVILEGE_ENABLED)
                advapi.AdjustTokenPrivileges(token, false, newState, 0, null, null)
            }
            val after = impersonatePrivilegeState(advapi, token)
            return if (after == "enabled") "enabled-by-focusflow" else "still-$after"
        } finally {
            if (token != null) kernel.CloseHandle(token)
        }
    }

    /**
     * Facts that decide whether this API can work here: sessions of FocusFlow and the
     * shell, token elevation types, SeImpersonatePrivilege, and the Secondary Logon
     * service start type (4 = disabled). Every item is read independently; a failure
     * to read one is reported, never thrown.
     */
    fun diagnostics(): String {
        val kernel = Kernel32Api.INSTANCE
        val advapi = Advapi32Api.INSTANCE
        val parts = mutableListOf<String>()
        fun part(name: String, read: () -> String) {
            parts += "$name=" + runCatching(read)
                .getOrElse { "unreadable(${it.javaClass.simpleName})" }
        }

        val ownPid = ProcessHandle.current().pid().toInt()
        val shellPid = runCatching {
            val window = User32Api.INSTANCE.GetShellWindow()
            val pid = IntByReference()
            if (window != null) User32Api.INSTANCE.GetWindowThreadProcessId(window, pid)
            pid.value
        }.getOrDefault(0)

        fun sessionOf(pid: Int): String {
            val session = IntByReference()
            return if (pid > 0 && kernel.ProcessIdToSessionId(pid, session)) {
                session.value.toString()
            } else {
                "unknown"
            }
        }

        fun elevationTypeOf(process: Pointer?): String {
            val tokenRef = PointerByReference()
            if (process == null || !advapi.OpenProcessToken(process, TOKEN_QUERY, tokenRef)) {
                return "unknown"
            }
            val token = tokenRef.value
            try {
                Memory(Int.SIZE_BYTES.toLong()).use { value ->
                    if (!advapi.GetTokenInformation(
                            token,
                            TOKEN_ELEVATION_TYPE_INFORMATION,
                            value,
                            Int.SIZE_BYTES,
                            IntByReference()
                        )
                    ) {
                        return "unknown"
                    }
                    return when (value.getInt(0)) {
                        1 -> "default"
                        2 -> "full"
                        3 -> "limited"
                        else -> "other"
                    }
                }
            } finally {
                if (token != null) kernel.CloseHandle(token)
            }
        }

        part("focusFlowSession") { sessionOf(ownPid) }
        part("shellPid") { shellPid.toString() }
        part("shellSession") { sessionOf(shellPid) }
        part("focusFlowTokenType") { elevationTypeOf(kernel.GetCurrentProcess()) }
        part("shellTokenType") {
            val process = kernel.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, shellPid)
            try {
                elevationTypeOf(process)
            } finally {
                if (process != null) kernel.CloseHandle(process)
            }
        }
        part("seImpersonate") {
            val tokenRef = PointerByReference()
            if (!advapi.OpenProcessToken(kernel.GetCurrentProcess(), TOKEN_QUERY, tokenRef)) {
                "unknown"
            } else {
                try {
                    impersonatePrivilegeState(advapi, tokenRef.value)
                } finally {
                    kernel.CloseHandle(tokenRef.value)
                }
            }
        }
        part("secondaryLogonStart") {
            Advapi32Util.registryGetIntValue(
                WinReg.HKEY_LOCAL_MACHINE,
                "SYSTEM\\CurrentControlSet\\Services\\seclogon",
                "Start"
            ).toString()
        }
        return parts.joinToString(" ")
    }

    private fun impersonatePrivilegeState(api: Advapi32Api, token: Pointer?): String {
        Memory(2048).use { buffer ->
            if (!api.GetTokenInformation(
                    token,
                    TOKEN_PRIVILEGES_INFORMATION,
                    buffer,
                    2048,
                    IntByReference()
                )
            ) {
                return "unknown"
            }
            val count = buffer.getInt(0)
            for (index in 0 until count.coerceIn(0, 100)) {
                val offset = 4L + index * 12L
                val low = buffer.getInt(offset)
                val high = buffer.getInt(offset + 4)
                val attributes = buffer.getInt(offset + 8)
                if (low == SE_IMPERSONATE_LUID_LOW && high == 0) {
                    return if (attributes and SE_PRIVILEGE_ENABLED != 0) {
                        "enabled"
                    } else {
                        "present-disabled"
                    }
                }
            }
            return "absent"
        }
    }

    /** Elevation of another process, or null when it cannot be read. */
    fun isProcessElevated(pid: Long): Boolean? {
        if (pid <= 0L || pid > Int.MAX_VALUE) return null
        val kernel = Kernel32Api.INSTANCE
        val advapi = Advapi32Api.INSTANCE
        val process = kernel.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid.toInt())
            ?: return null
        var token: Pointer? = null
        return try {
            val tokenRef = PointerByReference()
            if (!advapi.OpenProcessToken(process, TOKEN_QUERY, tokenRef)) {
                null
            } else {
                token = tokenRef.value
                tokenIsElevated(advapi, token)
            }
        } catch (_: Throwable) {
            null
        } finally {
            if (token != null) kernel.CloseHandle(token)
            kernel.CloseHandle(process)
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
        fun GetCurrentProcess(): Pointer?
        fun ProcessIdToSessionId(processId: Int, sessionId: IntByReference): Boolean

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

        fun AdjustTokenPrivileges(
            token: Pointer?,
            disableAllPrivileges: Boolean,
            newState: Pointer?,
            bufferLength: Int,
            previousState: Pointer?,
            returnLength: IntByReference?
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
    private const val TOKEN_ALL_ACCESS = 0xF01FF
    private const val TOKEN_ADJUST_DEFAULT = 0x0080
    private const val TOKEN_ADJUST_SESSIONID = 0x0100
    private const val PRIMARY_TOKEN_ACCESS =
        TOKEN_QUERY or TOKEN_DUPLICATE or TOKEN_ASSIGN_PRIMARY or
            TOKEN_ADJUST_DEFAULT or TOKEN_ADJUST_SESSIONID
    private const val SECURITY_IMPERSONATION = 2
    private const val TOKEN_PRIMARY = 1
    private const val TOKEN_ELEVATION_INFORMATION = 20
    private const val TOKEN_ELEVATION_TYPE_INFORMATION = 18
    private const val TOKEN_PRIVILEGES_INFORMATION = 3
    private const val TOKEN_ADJUST_PRIVILEGES = 0x0020
    private const val SE_IMPERSONATE_LUID_LOW = 29
    private const val SE_PRIVILEGE_ENABLED = 0x2
}

/**
 * Fallback launcher: asks the already-running Windows shell (Explorer) to open the
 * app. Explorer runs with the user's normal rights, so the app does not inherit
 * FocusFlow's administrator token. No app PID is returned; the coordinator finds
 * the new process by name and path.
 */
internal object WindowsExplorerLauncher {
    fun start(executable: File) {
        val windowsDir = System.getenv("SystemRoot")
            ?: System.getenv("windir")
            ?: "C:\\Windows"
        val explorer = File(windowsDir, "explorer.exe")
        if (!explorer.isFile) {
            throw IOException("CreateProcess error=2: explorer.exe was not found.")
        }
        val process = ProcessBuilder(explorer.absolutePath, executable.absolutePath)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        try {
            process.outputStream.close()
        } catch (_: Exception) {
            // The hand-off request is already sent; a closed stdin pipe is not an error.
        }
    }
}
