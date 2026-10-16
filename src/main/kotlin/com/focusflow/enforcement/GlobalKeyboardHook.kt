package com.focusflow.enforcement

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser.MSG
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * GlobalKeyboardHook
 *
 * Installs a WH_KEYBOARD_LL (low-level keyboard hook) during kiosk / Focus
 * Launcher sessions to suppress all system shortcuts that allow the user to
 * escape the overlay without going through our PIN flow.
 *
 * Keys suppressed while the hook is active
 * ─────────────────────────────────────────
 *   VK_LWIN / VK_RWIN  — Windows key (covers ALL Win+X combos: Win+D, Win+Tab,
 *                         Win+R, Win+E, Win+I, Win+Space, Win+X, Win+A, etc.)
 *   Alt+Tab             — Task-switcher UI
 *   Alt+Esc             — Cycle windows without switcher UI
 *   Alt+F4              — Close foreground window (would close the kiosk overlay)
 *   Ctrl+Esc            — Start menu on older Windows / Task Manager shortcut
 *
 * Keys that CANNOT be blocked by any user-mode code (kernel/SAS level):
 *   Ctrl+Alt+Del  — Secure Attention Sequence; handled by winlogon at ring 0
 *   Win+L         — Lock workstation; also kernel-level before hook fires
 *
 * Architecture
 * ─────────────
 * WH_KEYBOARD_LL hooks are delivered on the thread that installed them,
 * via that thread's Win32 message pump (GetMessage / DispatchMessage).
 * The JVM main thread and Compose UI thread do NOT run a Win32 message pump,
 * so a dedicated daemon thread is created for the sole purpose of installing
 * the hook and spinning the pump.  This mirrors the pattern used in WinEventHook.
 *
 * GC safety: the [hookProc] field holds a strong reference to the JNA callback
 * object.  If the GC collects it while the hook is live, native code calls a
 * dangling pointer → crash.  The field is cleared only after UnhookWindowsHookEx.
 *
 * LockSetForegroundWindow: enabled on [enable], released on [disable].
 * This prevents other apps from stealing foreground focus from the kiosk overlay
 * via SetForegroundWindow — belt-and-suspenders alongside the keyboard hook.
 */
object GlobalKeyboardHook {

    // ── Hook constants ────────────────────────────────────────────────────────
    private const val WH_KEYBOARD_LL = 13
    private const val HC_ACTION      = 0

    // KBDLLHOOKSTRUCT.flags bit masks
    private const val LLKHF_ALTDOWN  = 0x20   // Alt key is held
    // LLKHF_UP (0x80) is NOT checked deliberately: we suppress the blocked keys on
    // both KEYDOWN and KEYUP.  Suppressing only KEYDOWN would let the KEYUP through
    // and some shell components would still act on the incomplete sequence.

    // Virtual-key codes
    private const val VK_LWIN    = 0x5B
    private const val VK_RWIN    = 0x5C
    private const val VK_TAB     = 0x09
    private const val VK_ESCAPE  = 0x1B
    private const val VK_F4      = 0x73
    private const val VK_SPACE   = 0x20   // Alt+Space opens the system/window menu
    private const val VK_CONTROL = 0x11   // for GetAsyncKeyState Ctrl check

    // LockSetForegroundWindow constants
    private const val LSFW_LOCK   = 1
    private const val LSFW_UNLOCK = 2

    // Message pump
    private const val WM_QUIT = 0x0012

    // ── JNA — LowLevelKeyboardProc callback ──────────────────────────────────

    /**
     * Callback interface for WH_KEYBOARD_LL.
     * The lParam points to a KBDLLHOOKSTRUCT; we read it directly as a Pointer
     * (offset 0 = vkCode : Int, offset 8 = flags : Int) rather than going
     * through JNA Structure marshalling — the layout is stable across all Windows
     * versions and the direct read is faster inside the hot-path callback.
     */
    interface LowLevelKeyboardProc : StdCallLibrary.StdCallCallback {
        fun callback(nCode: Int, wParam: WPARAM, lParam: Pointer): LRESULT
    }

    /** Minimal User32 extensions not present in the JNA platform User32 class. */
    interface KbdHookUser32 : StdCallLibrary {
        fun SetWindowsHookExW(
            idHook:      Int,
            lpfn:        LowLevelKeyboardProc,
            hMod:        Pointer?,
            dwThreadId:  Int
        ): Pointer?

        fun UnhookWindowsHookEx(hhk: Pointer): Boolean

        fun CallNextHookEx(
            hhk:    Pointer?,
            nCode:  Int,
            wParam: WPARAM,
            lParam: Pointer
        ): LRESULT

        fun LockSetForegroundWindow(uLockCode: Int): Boolean

        fun PostThreadMessageW(idThread: Int, msg: Int, wParam: Long, lParam: Long): Boolean

        companion object {
            val INSTANCE: KbdHookUser32 = Native.load(
                "user32", KbdHookUser32::class.java, W32APIOptions.DEFAULT_OPTIONS
            )
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    @Volatile private var hookHandle:    Pointer? = null
    @Volatile private var hookProc:      LowLevelKeyboardProc? = null   // strong GC ref
    @Volatile private var running:       Boolean  = false
    @Volatile private var win32ThreadId: Int      = 0
    private var pumpThread: Thread? = null
    @Volatile var lastForegroundLockCall: String? = null
        private set

    /** True while the hook is installed and active. */
    val isActive: Boolean get() = hookHandle != null

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Install the low-level keyboard hook and start the message pump.
     * Synchronized so rapid enable/disable cycles from different coroutines cannot
     * start a second pump thread before the first has fully torn down.
     */
    @Synchronized fun enable() {
        if (!isWindows || running) return
        running = true

        pumpThread = Thread({
            // Win32 thread ID (NOT the JVM thread ID — they are completely different)
            win32ThreadId = try {
                Kernel32.INSTANCE.GetCurrentThreadId()
            } catch (_: Exception) { 0 }

            val proc = object : LowLevelKeyboardProc {
                override fun callback(nCode: Int, wParam: WPARAM, lParam: Pointer): LRESULT {
                    if (nCode == HC_ACTION) {
                        // Read KBDLLHOOKSTRUCT fields directly from the pointer:
                        //   offset  0 → vkCode  (DWORD)
                        //   offset  8 → flags   (DWORD)
                        val vk    = lParam.getInt(0)
                        val flags = lParam.getInt(8)
                        val altDown = (flags and LLKHF_ALTDOWN) != 0

                        if (shouldSuppress(vk, altDown)) {
                            // Return non-zero to swallow the event entirely.
                            // We suppress both KEYDOWN and KEYUP for the same key
                            // so the OS never sees a "dangling" key-up for a
                            // down event it never received.
                            return LRESULT(1)
                        }
                    }
                    return KbdHookUser32.INSTANCE.CallNextHookEx(hookHandle, nCode, wParam, lParam)
                }
            }

            hookProc   = proc   // keep alive — GC would crash us if collected
            hookHandle = KbdHookUser32.INSTANCE.SetWindowsHookExW(WH_KEYBOARD_LL, proc, null, 0)

            // Prevent other apps from stealing foreground focus via SetForegroundWindow.
            // Paired with the keyboard hook this makes Alt+Tab and programmatic focus
            // theft both impossible without going through our exit flow.
            recordForegroundLockCall("lock", LSFW_LOCK)

            // ── Message pump ─────────────────────────────────────────────────
            // Hooks are delivered on this thread via GetMessage.  Without a pump
            // Windows silently drops hook callbacks after LowLevelHooksTimeout (200 ms).
            val msg     = MSG()
            val user32  = User32.INSTANCE
            while (running) {
                val ret = user32.GetMessage(msg, null, 0, 0)
                if (ret <= 0) break
                user32.TranslateMessage(msg)
                user32.DispatchMessage(msg)
            }

            // ── Cleanup ───────────────────────────────────────────────────────
            recordForegroundLockCall("unlock", LSFW_UNLOCK)
            hookHandle?.let { KbdHookUser32.INSTANCE.UnhookWindowsHookEx(it) }
            hookHandle = null
            hookProc   = null   // now safe to release the GC reference
        }, "GlobalKeyboardHook-Pump")

        pumpThread!!.isDaemon = true
        pumpThread!!.start()
    }

    /**
     * Uninstall the hook and release the foreground lock.
     * Safe to call if the hook is not active.
     * Synchronized with enable() — prevents enable() from starting a new thread
     * while the old one is still joined (up to 1.5 s).
     */
    @Synchronized fun disable() {
        if (!running) return
        running = false
        // Brief spin-wait for the pump thread to register its Win32 thread ID.
        // A rapid enable → disable sequence (e.g. KillSwitch activate immediately
        // followed by deactivate) can reach here before pumpThread has had a chance
        // to call GetCurrentThreadId(). Without this wait, PostThreadMessageW is
        // called with tid=0, the message is dropped, and the pump thread stays
        // blocked in GetMessage indefinitely — surviving past join(1500).
        val deadline = System.currentTimeMillis() + 200
        @Suppress("ControlFlowWithEmptyBody")
        while (win32ThreadId == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        val tid = win32ThreadId
        if (tid != 0) {
            try {
                KbdHookUser32.INSTANCE.PostThreadMessageW(tid, WM_QUIT, 0L, 0L)
            } catch (_: Exception) {}
        }
        pumpThread?.join(1_500)
        pumpThread    = null
        win32ThreadId = 0
    }

    // ── Suppression decision ──────────────────────────────────────────────────

    /**
     * Returns true if this key event should be swallowed during kiosk mode.
     *
     * Reasoning per key:
     *   VK_LWIN/VK_RWIN — Windows key down/up.  Blocking this single virtual
     *     key suppresses EVERY Win+X combination (Win+D, Win+Tab, Win+R, Win+E,
     *     Win+Space …) because the OS only triggers those combos when it has
     *     seen a VK_LWIN/VK_RWIN KEYDOWN event.
     *
     *   VK_TAB + altDown — Alt+Tab task switcher.
     *
     *   VK_ESCAPE + altDown — Alt+Esc cycles windows without the switcher UI.
     *
     *   VK_F4 + altDown — Alt+F4 closes the foreground window; would dismiss
     *     the kiosk overlay and expose the underlying settings screen.
     *
     *   VK_ESCAPE + Ctrl held — Ctrl+Esc opens Start menu on all Windows
     *     versions (legacy; Win key does the same on modern systems but Ctrl+Esc
     *     is a common fallback).
     */
    private fun recordForegroundLockCall(operation: String, lockCode: Int) {
        try {
            val result = KbdHookUser32.INSTANCE.LockSetForegroundWindow(lockCode)
            lastForegroundLockCall = "$operation=$result"
            EnforcementLog.info(
                "Launcher",
                "foregroundLockCall operation=$operation result=$result"
            )
        } catch (failure: Exception) {
            lastForegroundLockCall = "$operation=error:${failure.javaClass.simpleName}"
            EnforcementLog.warn(
                "Launcher",
                "foregroundLockCall operation=$operation result=error " +
                    "error=${failure.javaClass.simpleName}"
            )
        }
    }

    private fun shouldSuppress(vk: Int, altDown: Boolean): Boolean = when (vk) {
        VK_LWIN, VK_RWIN -> true
        VK_TAB           -> altDown
        VK_ESCAPE        -> altDown || isCtrlDown()
        VK_F4            -> altDown
        VK_SPACE         -> altDown   // Alt+Space opens system/window menu — must suppress
        else             -> false
    }

    /**
     * Returns true when the Ctrl key is physically held at call time.
     * GetAsyncKeyState returns a SHORT; the high bit (sign bit) is set when the
     * key is down.  Cast to Short preserves the sign, so < 0 means key is down.
     */
    private fun isCtrlDown(): Boolean = try {
        User32.INSTANCE.GetAsyncKeyState(VK_CONTROL) < 0
    } catch (_: Exception) { false }
}
