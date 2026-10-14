package com.focusflow.enforcement

import com.focusflow.services.RelaunchHandoff
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

sealed interface RelaunchTarget {
    data class DirectExe(val path: String, val workingDirectory: String?) : RelaunchTarget
    data class AppsFolder(val aumid: String) : RelaunchTarget
    data class ExecutionAlias(val path: String) : RelaunchTarget
    data object Manual : RelaunchTarget
    data object NotSupported : RelaunchTarget
}

sealed interface RelaunchResult {
    data object HandedOff : RelaunchResult
    data object UserDeclined : RelaunchResult
    data class Failed(val reason: String) : RelaunchResult
    data class NotSupported(val reason: String) : RelaunchResult
}

internal fun chooseRelaunchTarget(
    isWindows: Boolean,
    isMsix: Boolean,
    aumid: String?,
    exePath: String?,
    aliasPath: String?
): RelaunchTarget {
    if (!isWindows) return RelaunchTarget.NotSupported
    if (!isMsix) {
        val executable = exePath?.takeIf(String::isNotBlank) ?: return RelaunchTarget.Manual
        val parent = File(executable).absoluteFile.parentFile?.absolutePath
        return RelaunchTarget.DirectExe(executable, parent)
    }
    aumid?.takeIf(String::isNotBlank)?.let { return RelaunchTarget.AppsFolder(it) }
    aliasPath?.takeIf(String::isNotBlank)?.let { return RelaunchTarget.ExecutionAlias(it) }
    return RelaunchTarget.Manual
}

object ElevationManager {
    private val pendingHandoff = java.util.concurrent.atomic.AtomicReference<RelaunchHandoff.Marker?>(null)

    /** In-process token query; non-Windows environments do not need elevation. */
    val isElevated: Boolean by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (!isWindows) {
            true
        } else {
            runCatching(::queryTokenElevation).getOrElse { failure ->
                EnforcementLog.warn("Elevation", "token elevation query failed", failure)
                false
            }
        }
    }

    fun logStartupDiagnostics() {
        runCatching {
            val identity = CurrentPackageIdentity.value
            val exePath = runCatching {
                WindowsStartupManager.resolveExePath()
            }.getOrNull()
            EnforcementLog.info(
                "Elevation",
                "startup variant=${if (InstallVariant.isMsix) "msix" else "exe"} " +
                    "pfn=${identity?.packageFamilyName.orEmpty()} " +
                    "aumid=${identity?.aumid.orEmpty()} tokenElevated=$isElevated " +
                    "exe=${exePath.orEmpty()} pid=${ProcessHandle.current().pid()} " +
                    "user=${System.getProperty("user.name").orEmpty()}"
            )
        }.onFailure { EnforcementLog.warn("Elevation", "startup diagnostics failed", it) }
    }

    fun relaunchElevated(): RelaunchResult {
        val requestStartedAt = System.nanoTime()
        if (!isWindows) {
            EnforcementLog.info(
                "Elevation",
                "handoff outcome=not-supported strategy=none totalElapsedMs=${elapsedMsSince(requestStartedAt)}"
            )
            return RelaunchResult.NotSupported("Administrator relaunch is available only on Windows.")
        }
        if (isElevated) {
            EnforcementLog.warn(
                "Elevation",
                "handoff outcome=failed reason=already-elevated totalElapsedMs=${elapsedMsSince(requestStartedAt)}"
            )
            return RelaunchResult.Failed("FocusFlow is already running as Administrator.")
        }

        val identity = CurrentPackageIdentity.value
        val target = chooseRelaunchTarget(
            isWindows = true,
            isMsix = InstallVariant.isMsix,
            aumid = identity?.aumid,
            exePath = if (InstallVariant.isMsix) null else WindowsStartupManager.resolveExePath(),
            aliasPath = executionAliasPath()?.takeIf(File::isFile)?.absolutePath
        )
        val strategy = target.strategyName()
        if (target == RelaunchTarget.Manual) {
            EnforcementLog.warn(
                "Elevation",
                "handoff outcome=not-supported strategy=$strategy reason=target-unavailable " +
                    "totalElapsedMs=${elapsedMsSince(requestStartedAt)}"
            )
            return RelaunchResult.NotSupported(MANUAL_ELEVATION_INSTRUCTIONS)
        }
        if (target == RelaunchTarget.NotSupported) {
            EnforcementLog.info(
                "Elevation",
                "handoff outcome=not-supported strategy=$strategy totalElapsedMs=${elapsedMsSince(requestStartedAt)}"
            )
            return RelaunchResult.NotSupported("Administrator relaunch is available only on Windows.")
        }

        val marker = RelaunchHandoff.begin()
            ?: run {
                EnforcementLog.warn(
                    "Elevation",
                    "handoff outcome=failed strategy=$strategy reason=restart-already-in-progress " +
                        "totalElapsedMs=${elapsedMsSince(requestStartedAt)}"
                )
                return RelaunchResult.Failed("Another administrator restart is already in progress.")
            }
        pendingHandoff.set(marker)
        EnforcementLog.info(
            "Elevation",
            "relaunch begin strategy=$strategy pid=${ProcessHandle.current().pid()} " +
                "user=${System.getProperty("user.name").orEmpty()}"
        )

        val shellStartedAt = System.nanoTime()
        val shellResult = try {
            executeRunAs(target, "--focusflow-elevation-handoff=${marker.nonce}")
        } catch (failure: Exception) {
            RelaunchHandoff.clear(marker)
            pendingHandoff.compareAndSet(marker, null)
            EnforcementLog.warn(
                "Elevation",
                "ShellExecuteEx invocation failed strategy=$strategy elapsedMs=${elapsedMsSince(shellStartedAt)}",
                failure
            )
            EnforcementLog.info(
                "Elevation",
                "handoff outcome=shell-error strategy=$strategy totalElapsedMs=${elapsedMsSince(requestStartedAt)}"
            )
            return RelaunchResult.Failed(
                failure.message?.takeIf(String::isNotBlank) ?: "Windows could not start the elevated instance."
            )
        }

        EnforcementLog.info(
            "Elevation",
            "ShellExecuteEx strategy=$strategy called=${shellResult.shellExecuteCalled} " +
                "started=${shellResult.started} errorCode=${shellResult.errorCode} " +
                "elapsedMs=${elapsedMsSince(shellStartedAt)}"
        )
        if (!shellResult.started) {
            RelaunchHandoff.clear(marker)
            pendingHandoff.compareAndSet(marker, null)
            val declined = shellResult.errorCode == ERROR_CANCELLED
            EnforcementLog.info(
                "Elevation",
                "handoff outcome=${if (declined) "user-declined" else "shell-error"} strategy=$strategy " +
                    "totalElapsedMs=${elapsedMsSince(requestStartedAt)}"
            )
            return if (declined) {
                RelaunchResult.UserDeclined
            } else {
                RelaunchResult.Failed(
                    "Windows could not start the elevated instance (error ${shellResult.errorCode})."
                )
            }
        }

        val helloWaitStartedAt = System.nanoTime()
        val confirmed = runCatching { RelaunchHandoff.awaitHello(marker, HANDOFF_TIMEOUT_MS) }
            .getOrElse { failure ->
                EnforcementLog.warn("Elevation", "handoff wait failed", failure)
                false
            }
        val helloWaitMs = elapsedMsSince(helloWaitStartedAt)
        if (!confirmed) {
            RelaunchHandoff.clear(marker)
            pendingHandoff.compareAndSet(marker, null)
            EnforcementLog.warn(
                "Elevation",
                "handoff outcome=timed-out strategy=$strategy helloWaitMs=$helloWaitMs " +
                    "totalElapsedMs=${elapsedMsSince(requestStartedAt)}; old instance remains active"
            )
            return RelaunchResult.Failed(
                "The elevated instance did not confirm startup. FocusFlow is still running; try the Start menu option."
            )
        }

        EnforcementLog.info(
            "Elevation",
            "handoff outcome=confirmed strategy=$strategy helloWaitMs=$helloWaitMs " +
                "totalElapsedMs=${elapsedMsSince(requestStartedAt)}"
        )
        return RelaunchResult.HandedOff
    }

    fun cancelPendingHandoff() {
        pendingHandoff.getAndSet(null)?.let(RelaunchHandoff::clear)
    }

    private fun executionAliasPath(): File? {
        val localAppData = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank) ?: return null
        return File(localAppData, "Microsoft/WindowsApps/focusflow.exe")
    }

    private fun RelaunchTarget.strategyName(): String = when (this) {
        is RelaunchTarget.DirectExe -> "direct-exe"
        is RelaunchTarget.AppsFolder -> "apps-folder"
        is RelaunchTarget.ExecutionAlias -> "execution-alias"
        RelaunchTarget.Manual -> "manual"
        RelaunchTarget.NotSupported -> "not-supported"
    }

    private fun elapsedMsSince(startedAtNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)

    private fun queryTokenElevation(): Boolean {
        val kernel = KernelElevationApi.INSTANCE
        val advapi = AdvapiTokenApi.INSTANCE
        val token = PointerByReference()
        if (!advapi.OpenProcessToken(kernel.GetCurrentProcess(), TOKEN_QUERY, token)) {
            throw IllegalStateException("OpenProcessToken failed (error ${Native.getLastError()})")
        }

        try {
            Memory(Int.SIZE_BYTES.toLong()).use { elevation ->
                val returned = IntByReference()
                if (!advapi.GetTokenInformation(
                        token.value,
                        TOKEN_ELEVATION_CLASS,
                        elevation,
                        Int.SIZE_BYTES,
                        returned
                    )
                ) {
                    throw IllegalStateException("GetTokenInformation failed (error ${Native.getLastError()})")
                }
                return elevation.getInt(0) != 0
            }
        } finally {
            kernel.CloseHandle(token.value)
        }
    }

    private fun executeRunAs(target: RelaunchTarget, arguments: String): ShellLaunchResult {
        val task = FutureTask {
            val ole = Ole32Api.INSTANCE
            val initResult = ole.CoInitializeEx(null, COINIT_APARTMENTTHREADED or COINIT_DISABLE_OLE1DDE)
            if (initResult < 0) {
                return@FutureTask ShellLaunchResult(false, false, initResult)
            }
            try {
                val info = ShellExecuteInfo().apply {
                    cbSize = size()
                    lpVerb = WString("runas")
                    when (target) {
                        is RelaunchTarget.DirectExe -> {
                            lpFile = WString(target.path)
                            lpDirectory = target.workingDirectory?.let(::WString)
                        }
                        is RelaunchTarget.AppsFolder -> lpFile = WString("shell:AppsFolder\\${target.aumid}")
                        is RelaunchTarget.ExecutionAlias -> lpFile = WString(target.path)
                        RelaunchTarget.Manual, RelaunchTarget.NotSupported ->
                            return@FutureTask ShellLaunchResult(false, false, ERROR_INVALID_PARAMETER)
                    }
                    lpParameters = WString(arguments)
                    nShow = SW_SHOWNORMAL
                }
                val started = ShellExecuteApi.INSTANCE.ShellExecuteExW(info)
                ShellLaunchResult(true, started, if (started) 0 else Native.getLastError())
            } finally {
                ole.CoUninitialize()
            }
        }
        Thread(task, "FocusFlow-Elevation-ShellExecute").apply {
            isDaemon = true
            start()
        }
        return task.get()
    }

    private data class ShellLaunchResult(
        val shellExecuteCalled: Boolean,
        val started: Boolean,
        val errorCode: Int
    )

    @Structure.FieldOrder(
        "cbSize", "fMask", "hwnd", "lpVerb", "lpFile", "lpParameters", "lpDirectory",
        "nShow", "hInstApp", "lpIDList", "lpClass", "hkeyClass", "dwHotKey", "hIcon", "hProcess"
    )
    private class ShellExecuteInfo : Structure() {
        @JvmField var cbSize: Int = 0
        @JvmField var fMask: Int = 0
        @JvmField var hwnd: Pointer? = null
        @JvmField var lpVerb: WString? = null
        @JvmField var lpFile: WString? = null
        @JvmField var lpParameters: WString? = null
        @JvmField var lpDirectory: WString? = null
        @JvmField var nShow: Int = 0
        @JvmField var hInstApp: Pointer? = null
        @JvmField var lpIDList: Pointer? = null
        @JvmField var lpClass: WString? = null
        @JvmField var hkeyClass: Pointer? = null
        @JvmField var dwHotKey: Int = 0
        @JvmField var hIcon: Pointer? = null
        @JvmField var hProcess: Pointer? = null
    }

    private interface KernelElevationApi : StdCallLibrary {
        fun GetCurrentProcess(): Pointer
        fun CloseHandle(handle: Pointer?): Boolean

        companion object {
            val INSTANCE: KernelElevationApi =
                Native.load("kernel32", KernelElevationApi::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }

    private interface AdvapiTokenApi : StdCallLibrary {
        fun OpenProcessToken(process: Pointer, access: Int, token: PointerByReference): Boolean
        fun GetTokenInformation(
            token: Pointer?,
            informationClass: Int,
            information: Pointer,
            informationLength: Int,
            returnedLength: IntByReference
        ): Boolean

        companion object {
            val INSTANCE: AdvapiTokenApi =
                Native.load("advapi32", AdvapiTokenApi::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }

    private interface Ole32Api : StdCallLibrary {
        fun CoInitializeEx(reserved: Pointer?, concurrencyModel: Int): Int
        fun CoUninitialize()

        companion object {
            val INSTANCE: Ole32Api =
                Native.load("ole32", Ole32Api::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }

    private interface ShellExecuteApi : StdCallLibrary {
        fun ShellExecuteExW(info: ShellExecuteInfo): Boolean

        companion object {
            val INSTANCE: ShellExecuteApi =
                Native.load("shell32", ShellExecuteApi::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }

    private const val TOKEN_QUERY = 0x0008
    private const val TOKEN_ELEVATION_CLASS = 20
    private const val COINIT_APARTMENTTHREADED = 0x2
    private const val COINIT_DISABLE_OLE1DDE = 0x4
    private const val SW_SHOWNORMAL = 1
    private const val ERROR_CANCELLED = 1223
    private const val ERROR_INVALID_PARAMETER = 87
    private const val HANDOFF_TIMEOUT_MS = 10_000L
    private const val MANUAL_ELEVATION_INSTRUCTIONS =
        "Start FocusFlow from the Start menu, right-click it, then choose More → Run as administrator."
}
