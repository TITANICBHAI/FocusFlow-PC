package com.focusflow.enforcement

import com.focusflow.data.Database
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

internal interface Advapi32Security : StdCallLibrary {
    companion object {
        val INSTANCE: Advapi32Security =
            Native.load("advapi32", Advapi32Security::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }

    fun GetKernelObjectSecurity(
        handle: HANDLE,
        requestedInformation: Int,
        descriptor: Pointer?,
        length: Int,
        lengthNeeded: IntByReference
    ): Boolean

    fun SetKernelObjectSecurity(handle: HANDLE, securityInformation: Int, descriptor: Pointer): Boolean

    fun ConvertSecurityDescriptorToStringSecurityDescriptorW(
        descriptor: Pointer,
        revision: Int,
        securityInformation: Int,
        stringDescriptor: PointerByReference,
        stringLength: IntByReference?
    ): Boolean

    fun ConvertStringSecurityDescriptorToSecurityDescriptorW(
        stringDescriptor: String,
        revision: Int,
        descriptor: PointerByReference,
        size: IntByReference?
    ): Boolean
}

internal interface KernelLocalMemory : StdCallLibrary {
    companion object {
        val INSTANCE: KernelLocalMemory =
            Native.load("kernel32", KernelLocalMemory::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }

    fun LocalFree(memory: Pointer?): Pointer?
}

/**
 * While Nuclear Mode is on (and only if the hidden setting `process_kill_protection` is "true"),
 * ordinary "end task" / taskkill / Stop-Process against FocusFlow fail with "access denied".
 *
 * It changes FocusFlow's own process permissions only: it adds one deny-terminate entry in front
 * of the existing ones and puts the original back when Nuclear Mode ends. The permissions die
 * with the process, so nothing is left behind after an exit. FocusFlow can still exit itself, and
 * Windows can still end it at sign-out or shutdown. A program that is allowed to change process
 * permissions can still undo it (see Security/plan.md N2). Needs a hands-on Windows check (W-8).
 */
internal object ProcessHardening {
    private const val TAG = "ProcessHardening"
    private const val DACL_SECURITY_INFORMATION = 0x4
    private const val SDDL_REVISION_1 = 1
    const val SETTING_KEY = "process_kill_protection"

    private val lock = Any()
    @Volatile private var originalDacl: String? = null
    @Volatile var isHardened: Boolean = false
        private set

    /** Hardens only when the hidden setting is "true". Safe to call repeatedly. */
    fun enableIfConfigured() {
        if (!InstallVariant.isWindows) return
        val configured = runCatching { Database.getSetting(SETTING_KEY) == "true" }.getOrDefault(false)
        if (configured) enable()
    }

    fun enable(): Boolean = synchronized(lock) {
        if (!InstallVariant.isWindows) return false
        if (isHardened) return true
        try {
            val handle = Kernel32.INSTANCE.GetCurrentProcess()
            val current = readDacl(handle) ?: return false
            val hardened = ProcessHardeningSddl.withDenyTerminate(current)
            if (hardened == null) {
                EnforcementLog.warn(TAG, "Process permissions have an unexpected shape; leaving them unchanged")
                return false
            }
            if (!writeDacl(handle, hardened)) return false
            originalDacl = current
            isHardened = true
            EnforcementLog.info(TAG, "Process kill protection ON")
            true
        } catch (failure: Throwable) {
            EnforcementLog.warn(TAG, "Could not enable process kill protection", failure)
            false
        }
    }

    fun disable(): Boolean = synchronized(lock) {
        if (!isHardened) return true
        try {
            val original = originalDacl ?: return false
            val handle = Kernel32.INSTANCE.GetCurrentProcess()
            if (!writeDacl(handle, original)) return false
            isHardened = false
            originalDacl = null
            EnforcementLog.info(TAG, "Process kill protection OFF")
            true
        } catch (failure: Throwable) {
            EnforcementLog.warn(TAG, "Could not remove process kill protection", failure)
            false
        }
    }

    private fun readDacl(handle: HANDLE): String? {
        val api = Advapi32Security.INSTANCE
        val needed = IntByReference()
        api.GetKernelObjectSecurity(handle, DACL_SECURITY_INFORMATION, null, 0, needed)
        val size = needed.value
        if (size <= 0) return null
        val buffer = Memory(size.toLong())
        if (!api.GetKernelObjectSecurity(handle, DACL_SECURITY_INFORMATION, buffer, size, needed)) return null
        val text = PointerByReference()
        if (!api.ConvertSecurityDescriptorToStringSecurityDescriptorW(
                buffer, SDDL_REVISION_1, DACL_SECURITY_INFORMATION, text, null
            )
        ) return null
        val pointer = text.value ?: return null
        return try {
            pointer.getWideString(0)
        } finally {
            KernelLocalMemory.INSTANCE.LocalFree(pointer)
        }
    }

    private fun writeDacl(handle: HANDLE, sddl: String): Boolean {
        val api = Advapi32Security.INSTANCE
        val descriptor = PointerByReference()
        if (!api.ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl, SDDL_REVISION_1, descriptor, null)) {
            return false
        }
        val pointer = descriptor.value ?: return false
        return try {
            api.SetKernelObjectSecurity(handle, DACL_SECURITY_INFORMATION, pointer)
        } finally {
            KernelLocalMemory.INSTANCE.LocalFree(pointer)
        }
    }
}
