package com.focusflow.enforcement

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * Resolves foreground process names without changing the legacy process-name
 * helpers used by enforcement. The API is injectable so resolution behavior is
 * testable without Windows or JNA calls.
 */
class ProcessNameResolver(
    private val api: ProcessImageApi,
    private val warn: (String) -> Unit = {}
) {
    fun resolve(pid: Long, foregroundWindow: Pointer? = null): String? {
        if (pid <= 0L) return null
        val path = api.processHandleCommand(pid) ?: api.queryFullImagePath(pid)
        val image = path?.let(::imageName)
        if (image == null) {
            warn("Could not resolve executable name for foreground PID $pid")
            return null
        }
        if (!image.equals("applicationframehost.exe", ignoreCase = true)) {
            return image.lowercase()
        }

        val hostedPid = api.storeAppChildProcessId(pid, foregroundWindow)
        val hostedPath = hostedPid?.let { api.processHandleCommand(it) ?: api.queryFullImagePath(it) }
        val hostedImage = hostedPath?.let(::imageName)?.lowercase()
        if (hostedImage == null || hostedImage == "applicationframehost.exe") {
            warn("Could not resolve the Store app hosted by ApplicationFrameHost PID $pid")
            return null
        }
        return hostedImage
    }

    private fun imageName(path: String): String? =
        path.substringAfterLast('\\').substringAfterLast('/').takeIf { it.isNotBlank() }
}

interface ProcessImageApi {
    fun processHandleCommand(pid: Long): String?
    fun queryFullImagePath(pid: Long): String?
    fun storeAppChildProcessId(hostPid: Long, foregroundWindow: Pointer?): Long?
}

private interface Kernel32ProcessImage : StdCallLibrary {
    fun OpenProcess(desiredAccess: Int, inheritHandle: Boolean, processId: Int): HANDLE?
    fun QueryFullProcessImageNameW(
        process: HANDLE,
        flags: Int,
        imagePath: CharArray,
        size: IntByReference
    ): Boolean
    fun CloseHandle(handle: HANDLE): Boolean

    companion object {
        val INSTANCE: Kernel32ProcessImage =
            Native.load("kernel32", Kernel32ProcessImage::class.java, W32APIOptions.DEFAULT_OPTIONS)
        const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
    }
}

object WindowsProcessImageApi : ProcessImageApi {
    override fun processHandleCommand(pid: Long): String? = try {
        ProcessHandle.of(pid).flatMap { it.info().command() }.orElse(null)
    } catch (_: Exception) {
        null
    }

    override fun queryFullImagePath(pid: Long): String? {
        if (!isWindows || pid !in 1..Int.MAX_VALUE.toLong()) return null
        val process = runCatching {
            Kernel32ProcessImage.INSTANCE.OpenProcess(
                Kernel32ProcessImage.PROCESS_QUERY_LIMITED_INFORMATION,
                false,
                pid.toInt()
            )
        }.getOrNull() ?: return null
        return try {
            val buffer = CharArray(32_768)
            val length = IntByReference(buffer.size)
            if (Kernel32ProcessImage.INSTANCE.QueryFullProcessImageNameW(process, 0, buffer, length) &&
                length.value > 0
            ) {
                String(buffer, 0, length.value)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { Kernel32ProcessImage.INSTANCE.CloseHandle(process) }
        }
    }

    override fun storeAppChildProcessId(hostPid: Long, foregroundWindow: Pointer?): Long? {
        if (!isWindows || foregroundWindow == null) return null
        return runCatching {
            var match: Long? = null
            val callback = object : EnumChildProc {
                override fun callback(hwnd: HWND, lParam: Pointer?): Boolean {
                    val className = CharArray(256)
                    val length = User32Extra.INSTANCE.GetClassNameW(hwnd, className, className.size)
                    if (length > 0 && String(className, 0, length) == "Windows.UI.Core.CoreWindow") {
                        val pid = IntArray(1)
                        User32Extra.INSTANCE.GetWindowThreadProcessId(hwnd, pid)
                        if (pid[0] > 0 && pid[0].toLong() != hostPid) {
                            match = pid[0].toLong()
                            return false
                        }
                    }
                    return true
                }
            }
            User32Extra.INSTANCE.EnumChildWindows(HWND(foregroundWindow), callback, null)
            match
        }.getOrNull()
    }
}

object DefaultProcessNameResolver {
    val instance = ProcessNameResolver(WindowsProcessImageApi) { message ->
        EnforcementLog.warn("ProcessNameResolver", message)
    }
}
