# FocusFlow — Linux Port Plan

> **What this is:** A complete, ordered blueprint for adding Linux support alongside the existing
> Windows code. Every file, every method, every decision is covered. A new agent with zero prior
> context should be able to open this document and know exactly what to do next.
>
> **What this is NOT:** A rewrite. Windows code is never deleted or modified — only wrapped.
>
> **Progress tracker:** `LINUX_PORT_PROGRESS.md` — check tasks off there as you complete them.

---

## STOP — Read This First

### The single most important rule
**Phase 0 (OS detection utilities) must be completed before any other phase.**
Every other section in this document calls `isLinux`, `isX11`, `isWayland`, or `hasXdotool`.
These do not exist yet. If you skip Phase 0 and edit any other file first, it will not compile.

### Step ordering — what blocks what
```
Phase 0  →  ALL other phases depend on this
Phase 2  →  Phase 3 depends on it (nuclear mode uses ProcessMonitor)
Phase 1  →  can run any time (build system, no code dependencies)
Phase 4, 5, 6, 7, 8, 9, 10  →  can all run in parallel after Phase 0
Phase 11 →  runs last (testing requires everything else done)
```

### The universal edit pattern — how to wrap existing methods
When the plan says "add a Linux branch to `foo()`", this is the exact procedure:
1. Read the target file first (always — never edit blind)
2. Rename the existing private implementation from `foo()` to `fooWindows()`
3. If the method was `public`, create a new public `foo()` that dispatches:
```kotlin
fun foo() {
    when {
        isWindows -> fooWindows()   // existing code — DO NOT TOUCH
        isLinux   -> fooLinux()     // new code
    }
}
private fun fooLinux() { /* new implementation */ }
```
4. If the method was already private and called from one public entry point, just wrap
   that entry point. Do not rename private helpers that are only called internally.

### Import statement for isLinux, isX11, isWayland, hasXdotool
Once Phase 0 is done, every file that uses these properties needs this import:
```kotlin
import com.focusflow.enforcement.isLinux
import com.focusflow.enforcement.isWindows
import com.focusflow.enforcement.isX11
import com.focusflow.enforcement.isWayland
import com.focusflow.enforcement.hasXdotool
```
These are top-level properties in `WinApiBindings.kt`. They are not inside an object or class.

### Graceful degradation rule
If a Linux feature is not yet implemented, a no-op is acceptable. The app must **never crash**
on Linux. A missing enforcement feature is acceptable. An uncaught exception is not.
Every `ProcessBuilder` call must be wrapped in `try/catch(_: Exception)`.

---

## Table of Contents

| # | Section | Files Touched |
|---|---|---|
| 0 | [Prerequisites & Dev Environment](#0-prerequisites--dev-environment) | none |
| 1 | [OS Detection Utilities](#1-os-detection-utilities) | `WinApiBindings.kt` |
| 2 | [Build System](#2-build-system) | `build.gradle.kts` |
| 3 | [Foreground Window Detection](#3-foreground-window-detection) | `WinEventHook.kt`, `ProcessMonitor.kt`, `WinApiBindings.kt` |
| 4 | [Keyword Blocking — Window Title](#4-keyword-blocking--window-title) | `WinApiBindings.kt` |
| 5 | [Process Killing](#5-process-killing) | none (already works) |
| 6 | [Nuclear Mode](#6-nuclear-mode) | `NuclearMode.kt` |
| 7 | [Launcher Safe Process List](#7-launcher-safe-process-list) | `ProcessMonitor.kt` |
| 7b | [Always-Kill Shells](#7b-always-kill-shells) | `ProcessMonitor.kt` |
| 7c | [Process Name Normalization](#7c-process-name-normalization) | `ProcessMonitor.kt` |
| 8 | [Block Presets](#8-block-presets) | `BlockPresets.kt` |
| 9 | [Hosts File Blocking](#9-hosts-file-blocking) | `HostsBlocker.kt` |
| 10 | [Network / Firewall Blocking](#10-network--firewall-blocking) | `NetworkBlocker.kt` |
| 11 | [VPN Blocker](#11-vpn-blocker) | `VpnBlocker.kt` |
| 12 | [Floating Block Overlay](#12-floating-block-overlay) | `FloatingBlockOverlay.kt` |
| 13 | [Kiosk Mode — Taskbar & Window](#13-kiosk-mode--taskbar--window) | `FocusLauncherService.kt` |
| 14 | [Keyboard Hook](#14-keyboard-hook) | `GlobalKeyboardHook.kt` |
| 15 | [Installed Apps Scanner](#15-installed-apps-scanner) | `InstalledAppsScanner.kt` |
| 16 | [App Icon Extraction](#16-app-icon-extraction) | `AppIconExtractor.kt` |
| 17 | [Startup Persistence](#17-startup-persistence) | `WindowsStartupManager.kt` |
| 18 | [Watchdog](#18-watchdog) | `WatchdogInstaller.kt` |
| 19 | [Registry Lockdown](#19-registry-lockdown) | `RegistryLockdown.kt` — no-op, no changes |
| 20 | [Crash Reporter & Main.kt](#20-crash-reporter--mainkt) | `CrashReporter.kt`, `Main.kt` |
| 21 | [System Tray](#21-system-tray) | `SystemTrayManager.kt` |
| 22 | [Sound Aversion](#22-sound-aversion) | `SoundAversion.kt` — no changes |
| 23 | [Confirmed Cross-Platform (No Changes)](#23-confirmed-cross-platform-no-changes) | `ResourceMonitorService.kt`, `StandaloneBlockService.kt`, `KillSwitchService.kt` |
| 24 | [UI — Nav, Setup Screen & App.kt](#24-ui--nav-setup-screen--appkt) | `SideNav.kt`, `Models.kt`, `App.kt`, `WindowsSetupScreen.kt`, `BlockDefenseScreen.kt`, `OsBanner.kt`, `VpnNetworkScreen.kt` |
| 25 | [Packaging — .deb Build](#25-packaging--deb-build) | `build.gradle.kts` |
| 26 | [Recovery Tool](#26-recovery-tool) | none |
| 27 | [Testing Checklist](#27-testing-checklist) | — |
| 28 | [Known Limitations](#28-known-limitations) | — |

---

## 0. Prerequisites & Dev Environment

### What must be installed on the Linux development/test machine

```bash
# Java 17+ (Temurin recommended — same JDK used for Windows builds)
sudo apt install temurin-17-jdk   # or use SDKMAN: sdk install java 17-tem

# Tools used at runtime by the Linux enforcement layer
sudo apt install xdotool wmctrl xbindkeys iptables

# Tools used for testing
sudo apt install pgrep procps curl
```

### How to run the app in dev mode on Linux
```bash
./gradlew run
```
This starts the Compose Desktop app. No special flags needed. Java 17+ on PATH is the
only hard requirement. The first run will download Gradle and all dependencies.

### Display server — how to check which one is running
```bash
echo $XDG_SESSION_TYPE   # prints "x11" or "wayland"
echo $WAYLAND_DISPLAY    # empty on X11, set on Wayland
echo $DISPLAY            # empty on pure Wayland, set on X11
```

### Environment variables the Linux enforcement code reads
| Variable | Used for |
|---|---|
| `WAYLAND_DISPLAY` | Detecting Wayland |
| `XDG_SESSION_TYPE` | Detecting Wayland (alternate method) |
| `DISPLAY` | Detecting X11 / XWayland |

---

## 1. OS Detection Utilities

**File:** `src/main/kotlin/com/focusflow/enforcement/WinApiBindings.kt`

**Do this first. Nothing else compiles without it.**

Read the file. Find the line where `isWindows` is declared (it's a top-level `val` near
the bottom of the file). Directly after it, add:

```kotlin
val isLinux:   Boolean get() = System.getProperty("os.name").lowercase().contains("linux")
val isMac:     Boolean get() = System.getProperty("os.name").lowercase().contains("mac")

val isWayland: Boolean get() = isLinux && (
    System.getenv("WAYLAND_DISPLAY") != null ||
    System.getenv("XDG_SESSION_TYPE")?.lowercase() == "wayland"
)
val isX11: Boolean get() = isLinux && !isWayland && System.getenv("DISPLAY") != null

/** True if xdotool is installed and callable. Evaluated once at startup. */
val hasXdotool: Boolean by lazy {
    try {
        ProcessBuilder("xdotool", "version")
            .redirectErrorStream(true).start().waitFor() == 0
    } catch (_: Exception) { false }
}
```

No other changes to `WinApiBindings.kt` are needed in this step. The
`getForegroundProcessNameAndPid()` and `getForegroundWindowTitle()` functions in this file
are updated in Sections 3 and 4 respectively.

---

## 2. Build System

**File:** `build.gradle.kts`

Read the file and find the `nativeDistributions { ... }` block. It already has a `windows { }`
block. Add a `linux { }` block alongside it:

```kotlin
linux {
    packageName    = "focusflow"
    debMaintainer  = "support@focusflow.app"
    appCategory    = "Utility"
    shortcut       = true
    iconFile.set(project.file("src/main/resources/focusflow_512.png"))
}
```

You also need a **512×512 PNG** at `src/main/resources/focusflow_512.png`. Check if the
`src/main/resources/` directory already has an icon. If it has one at a smaller resolution,
upscale it. If nothing exists there, create a placeholder 512×512 solid-colour PNG for now.

Add a GitHub Actions workflow at `.github/workflows/build-linux.yml`:

```yaml
name: Build Linux .deb
on: [push, workflow_dispatch]
jobs:
  build-deb:
    runs-on: ubuntu-22.04
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Install runtime deps
        run: sudo apt-get install -y xdotool wmctrl xbindkeys
      - name: Build .deb
        run: ./gradlew packageDeb
      - uses: actions/upload-artifact@v4
        with:
          name: focusflow-linux-deb
          path: build/compose/binaries/main/deb/*.deb
```

Build commands for reference:
```bash
./gradlew packageDeb    # → build/compose/binaries/main/deb/focusflow_*.deb
./gradlew packageRpm    # optional RPM for Fedora/RHEL
```

---

## 3. Foreground Window Detection

> This is the most important enforcement piece. Without it, zero enforcement happens on Linux.

**Files:** `WinEventHook.kt`, `ProcessMonitor.kt`, `WinApiBindings.kt`

### 3a — WinEventHook.kt

Read the file. Understand the existing `start(callback)` and `stop()` method signatures.
Then add the Linux polling implementation at the bottom of the file (outside the existing
Windows implementation, inside the same `object`):

```kotlin
// ── Linux foreground window poller ──────────────────────────────────────────
// Polls xdotool every 500ms. Fires callback only when the focused window changes.
// 500ms matches the Windows tickPoll fallback rate.

private var linuxPollJob: Job? = null
private val linuxScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

private fun startLinuxPoller(callback: (String, Long) -> Unit) {
    var lastPid = -1L
    linuxPollJob = linuxScope.launch {
        while (isActive) {
            val result = getLinuxForegroundProcess()
            if (result != null && result.second != lastPid && result.second > 0) {
                lastPid = result.second
                callback(result.first, result.second)
            }
            delay(500)
        }
    }
}

private fun stopLinuxPoller() {
    linuxPollJob?.cancel()
    linuxPollJob = null
}

/**
 * Returns (processName, pid) of the currently focused window.
 * X11: uses xdotool — reliable, ~10ms latency.
 * Wayland: attempts XWayland first (most Wayland sessions run XWayland),
 *          then gives up — Wayland intentionally blocks cross-app window queries.
 */
fun getLinuxForegroundProcess(): Pair<String, Long>? {
    return when {
        isX11 && hasXdotool -> getX11ForegroundProcess()
        isWayland            -> getWaylandForegroundProcessFallback()
        else                 -> null
    }
}

private fun getX11ForegroundProcess(): Pair<String, Long>? {
    return try {
        val proc = ProcessBuilder("xdotool", "getactivewindow", "getwindowpid")
            .redirectErrorStream(true).start()
        val pidStr = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        val pid = pidStr.toLongOrNull() ?: return null
        val handle = ProcessHandle.of(pid).orElse(null) ?: return null
        val name = handle.info().command().orElse(null)?.substringAfterLast("/") ?: return null
        Pair(name, pid)
    } catch (_: Exception) { null }
}

private fun getWaylandForegroundProcessFallback(): Pair<String, Long>? {
    // Many Wayland sessions run XWayland for legacy apps — try that first
    return if (System.getenv("DISPLAY") != null && hasXdotool) {
        getX11ForegroundProcess()
    } else null   // pure Wayland with no XWayland: no reliable cross-app detection
}
```

Then modify the existing `start()` and `stop()` methods — follow the universal edit pattern
(rename the existing Windows code to `startWindows()`, then make `start()` dispatch):

```kotlin
fun start(callback: (String, Long) -> Unit) {
    when {
        isWindows -> startWindows(callback)   // renamed from the original start()
        isLinux   -> startLinuxPoller(callback)
    }
}

fun stop() {
    when {
        isWindows -> stopWindows()            // renamed from the original stop()
        isLinux   -> stopLinuxPoller()
    }
}

// Add isActive to the Linux branch — the Windows side already has its own check
val isActive: Boolean get() = when {
    isWindows -> /* existing hookHandle != null check */
    isLinux   -> linuxPollJob?.isActive == true
    else      -> false
}
```

### 3b — ProcessMonitor.kt — enable hook on Linux

Read the file. Find the `start()` method. It currently has `if (isWindows) { WinEventHook.start { ... } }`.
Change to:
```kotlin
if (isWindows || isLinux) {
    WinEventHook.start { pName, pid -> onForegroundChanged(pName, pid) }
}
```

Find `tickPoll()`. Line 429 has `if (!isWindows) return`. Change to:
```kotlin
if (!isWindows && !isLinux) return
```
Leave the rest of `tickPoll()` unchanged — it already calls `getForegroundProcessNameAndPid()`
which will use the Linux path after Section 3c below.

### 3c — WinApiBindings.kt — getForegroundProcessNameAndPid()

Read the function (line ~64 area). It currently only works on Windows. Add a Linux branch
following the universal edit pattern:

```kotlin
fun getForegroundProcessNameAndPid(): Pair<String, Long>? {
    return when {
        isWindows -> getForegroundProcessNameAndPidWindows()  // renamed original
        isLinux   -> WinEventHook.getLinuxForegroundProcess()
        else      -> null
    }
}
```

---

### 3d — WinApiBindings.kt — getForegroundProcessName() missing guard

**File:** `src/main/kotlin/com/focusflow/enforcement/WinApiBindings.kt`

`getForegroundProcessName()` at line ~81 accesses `User32Extra.INSTANCE` with no
`if (!isWindows) return null` guard. Its only current call site — `DailyAllowanceTracker.kt`
line 133 — wraps the call in `if (isWindows)`, so there is no crash risk today. However
the function itself must be hardened before adding a Linux implementation in Section 4:

```kotlin
fun getForegroundProcessName(): String? {
    if (!isWindows) return null   // ← ADD THIS LINE
    return try {
        val user32 = User32Extra.INSTANCE
        // ... rest unchanged ...
    } catch (_: Exception) { null }
}
```

Once the guard is in place, Section 4 can replace this with an OS-dispatch wrapper
(same pattern as `getForegroundProcessNameAndPid()` in Section 3c).

> **Note:** `getForegroundProcessNameAndPid()` has the same missing-guard issue — its
> call site (`tickPoll()`) is guarded by `if (!isWindows) return`, making it safe
> for now. Add the defensive guard to both functions in the same commit.

---

## 4. Keyword Blocking — Window Title

**File:** `src/main/kotlin/com/focusflow/enforcement/WinApiBindings.kt`

`getForegroundWindowTitle()` is at line 64. It currently uses Win32 `GetWindowText`.
`ProcessMonitor` calls it at lines 598 and 620 to check if a website URL contains a
blocked keyword. Without this, keyword blocking silently does nothing on Linux.

Follow the universal edit pattern — rename the existing implementation, then dispatch:

```kotlin
fun getForegroundWindowTitle(): String? {
    return when {
        isWindows -> getForegroundWindowTitleWindows()   // renamed original
        isLinux   -> getLinuxWindowTitle()
        else      -> null
    }
}

private fun getLinuxWindowTitle(): String? {
    if (!isX11 || !hasXdotool) return null
    return try {
        val proc = ProcessBuilder("xdotool", "getactivewindow", "getwindowname")
            .redirectErrorStream(true).start()
        val title = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        title.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }
}
```

---

## 5. Process Killing

**No code changes needed.** `killProcessByName()` and `killProcessByPid()` in
`WinApiBindings.kt` already have non-Windows fallbacks via `ProcessHandle.destroyForcibly()`.

One thing to be aware of: on Linux, process names have no `.exe` suffix. `ProcessMonitor`
compares names with `lowercase()` — this is fine. No changes needed there either.

---

## 6. Nuclear Mode

**File:** `src/main/kotlin/com/focusflow/enforcement/NuclearMode.kt`

Read the file. The `escapeProcesses` and `knownEscapePathSuffixes` properties reference
only `.exe` names. These won't match anything on Linux.

Follow the universal edit pattern: extract the existing Windows sets into named constants,
add Linux equivalents, then use `when` to select by OS:

```kotlin
private val escapeProcesses: Set<String> get() = when {
    isWindows -> windowsEscapeProcesses
    isLinux   -> linuxEscapeProcesses
    else      -> emptySet()
}

private val windowsEscapeProcesses = setOf(
    // THE EXISTING LIST — copy every entry exactly, do not change anything
)

private val linuxEscapeProcesses = setOf(
    // Process / system monitors
    "gnome-system-monitor", "ksysguard", "lxtask", "xfce4-taskmanager",
    "htop", "top", "btop", "glances", "atop", "bpytop",
    // Terminals (all common ones)
    "gnome-terminal", "konsole", "xterm", "xfterm4", "lxterminal",
    "xfce4-terminal", "mate-terminal", "tilix", "alacritty", "kitty",
    "wezterm", "terminator", "rxvt", "urxvt", "st", "foot",
    "bash", "zsh", "fish", "sh", "dash", "ksh",
    // File managers (can open terminals)
    "nautilus", "thunar", "dolphin", "nemo", "pcmanfm", "caja",
    // GUI package managers / app stores
    "synaptic", "gdebi", "gnome-software", "discover", "pamac",
    // Text editors (can edit /etc/hosts, sudoers, etc.)
    "gedit", "kate", "mousepad", "pluma", "xed", "featherpad",
    // System config UIs
    "dconf-editor", "gnome-tweaks", "unity-tweak-tool",
    // Low-level X11 tools
    "xkill", "wmctrl", "xdotool"
)

private val knownEscapePathSuffixes: Set<String> get() = when {
    isWindows -> windowsEscapePathSuffixes
    isLinux   -> linuxEscapePathSuffixes
    else      -> emptySet()
}

private val windowsEscapePathSuffixes = setOf(
    // THE EXISTING SET — copy every entry exactly, do not change anything
)

private val linuxEscapePathSuffixes = setOf(
    "/usr/bin/bash", "/usr/bin/zsh", "/usr/bin/fish",
    "/bin/bash", "/bin/sh", "/bin/zsh",
    "/usr/bin/gnome-terminal", "/usr/bin/konsole", "/usr/bin/xterm",
    "/usr/bin/alacritty", "/usr/bin/kitty", "/usr/bin/foot",
    "/usr/bin/gnome-system-monitor", "/usr/bin/htop", "/usr/bin/btop"
)
```

Then find `getRunningEscapeProcesses()`. It currently calls `tasklist` on Windows.
The non-Windows fallback already exists using `ProcessHandle` (look for it — it's the
`else` branch). Wrap it:

```kotlin
private fun getRunningEscapeProcesses(): Set<String> {
    return when {
        isWindows -> getRunningEscapeProcessesWindows()   // rename existing tasklist path
        isLinux   -> getRunningEscapeProcessesFallback()  // the existing ProcessHandle path
        else      -> emptySet()
    }
}
```

`killAndLog()` uses `killProcessByName()` internally — that already works cross-platform
(see Section 5). No changes needed there.

---

## 7. Launcher Safe Process List

**File:** `src/main/kotlin/com/focusflow/enforcement/ProcessMonitor.kt`

**This is a safety-critical section.** The `launcherSafeProcesses` set (line 153) contains
Windows process names that kiosk mode must never kill. On Linux, if this list is not updated,
kiosk mode will attempt to kill `Xorg` or `gnome-shell`, which will crash the entire display
session and lock the user out of their machine until reboot.

Read the existing set at line 153. It's a `val` inside `ProcessMonitor`. Convert it from
a static set to a computed property that returns the correct set per OS:

```kotlin
val launcherSafeProcesses: Set<String> get() = when {
    isWindows -> windowsLauncherSafeProcesses
    isLinux   -> linuxLauncherSafeProcesses
    else      -> emptySet()
}

// THE EXISTING SET renamed — copy every entry exactly, do not change anything
private val windowsLauncherSafeProcesses = setOf(
    "focusflow.exe", "java.exe", "javaw.exe",
    "explorer.exe", "dwm.exe", "winlogon.exe", "csrss.exe",
    // ... copy all existing entries ...
)

// Linux system processes that must never be killed
private val linuxLauncherSafeProcesses = setOf(
    // FocusFlow itself
    "focusflow", "java",

    // Display server — killing either of these crashes the entire desktop
    "Xorg", "X", "Xwayland",

    // Desktop environment shells — killing these ends the session immediately
    "gnome-shell",       // GNOME
    "plasmashell",       // KDE Plasma
    "xfwm4",             // XFCE window manager
    "openbox",           // Openbox
    "i3",                // i3
    "sway",              // Sway (Wayland)
    "mutter",            // GNOME Mutter compositor
    "kwin_x11",          // KDE KWin (X11)
    "kwin_wayland",      // KDE KWin (Wayland)
    "marco",             // MATE window manager
    "xfdesktop",         // XFCE desktop
    "lxsession",         // LXDE session manager
    "lxqt-session",      // LXQt session manager

    // Core system daemons — never touch these
    "systemd",
    "dbus-daemon",       // inter-process communication bus — killing this breaks everything
    "pulseaudio",        // audio
    "pipewire",          // audio (modern)
    "wireplumber",       // PipeWire session manager
    "NetworkManager",    // networking
    "networkd",          // systemd-networkd
    "polkitd",           // privilege escalation daemon
    "systemd-logind",    // login/session management

    // Input handling
    "ibus-daemon",       // input method bus
    "fcitx",             // input method
    "fcitx5",            // input method

    // Compositor/display managers — present on some systems
    "lightdm",           // display manager
    "gdm", "gdm3",       // GNOME display manager
    "sddm",              // KDE display manager
)
```

The three places in `ProcessMonitor.kt` that reference `launcherSafeProcesses` (lines 482,
553, 681) use it as a `Set<String>` membership check — no other changes needed there as long
as it's converted from `val` to `get()` (Kotlin allows this transparently).

---

## 7b. Always-Kill Shells

**File:** `src/main/kotlin/com/focusflow/enforcement/ProcessMonitor.kt`

`ProcessMonitor` has a `systemShells` set (around line 103) — processes that are **always**
killed whenever any enforcement is active, regardless of Nuclear Mode. This is separate from
`launcherSafeProcesses`. It currently contains only Windows shell names:
```
"cmd.exe", "powershell.exe", "pwsh.exe", "wt.exe", "bash.exe", "zsh.exe" ...
```
On Linux, `bash`, `zsh`, and `gnome-terminal` run freely because none of these names match
`.exe` suffixes. Without a Linux equivalent, a user can open any terminal during a focus
session on Linux.

Follow the same pattern as Section 7 — convert to a computed property:

```kotlin
private val systemShells: Set<String> get() = when {
    isWindows -> windowsSystemShells
    isLinux   -> linuxSystemShells
    else      -> emptySet()
}

// THE EXISTING SET renamed — copy every entry exactly, do not change anything
private val windowsSystemShells = setOf(
    "cmd.exe", "powershell.exe", "powershell_ise.exe", "pwsh.exe",
    "wt.exe", "mintty.exe", "conemu64.exe", "conemu.exe", "cmder.exe",
    "bash.exe", "zsh.exe", "sh.exe",
    "taskmgr.exe",
    "regedit.exe", "regedt32.exe",
    "mmc.exe"
)

private val linuxSystemShells = setOf(
    // Terminals — direct command execution
    "gnome-terminal", "konsole", "xterm", "alacritty", "kitty",
    "xfce4-terminal", "mate-terminal", "tilix", "foot", "wezterm",
    "lxterminal", "terminator", "rxvt", "urxvt", "st",
    // Shell processes themselves
    "bash", "zsh", "fish", "sh", "dash", "ksh",
    // System monitors — can kill FocusFlow
    "gnome-system-monitor", "ksysguard", "lxtask", "xfce4-taskmanager",
    "htop", "btop",
    // Policy/config editors — can undo enforcement config
    "dconf-editor", "gnome-tweaks"
)
```

---

## 7c. Process Name Normalization

**File:** `src/main/kotlin/com/focusflow/enforcement/ProcessMonitor.kt`

**This is a silent data compatibility bug.** The blocked app list is stored in the database.
If a user set up their block list on Windows, every entry is stored with a `.exe` suffix
(`firefox.exe`, `discord.exe`). On Linux, `ProcessHandle` returns just `firefox` or `discord`.
The comparison will always be false, and blocking silently does nothing for every app.

Find the place in `ProcessMonitor` where a detected foreground process name is compared
against the blocked app list (look for where `sessionActive`, `scheduleBlockedProcesses`,
or `standaloneBlockedProcesses` are checked). The comparison likely looks like:

```kotlin
val exeName = processName.lowercase()
val isBlocked = blockedSet.contains(exeName)
```

Change the comparison to normalize the `.exe` suffix away on Linux before comparing:

```kotlin
/**
 * Normalize a process name for cross-platform comparison.
 * On Windows: keep as-is (e.g. "firefox.exe")
 * On Linux:   strip .exe suffix (e.g. "firefox.exe" → "firefox", "firefox" → "firefox")
 * This ensures a block list built on Windows still works when the app runs on Linux.
 */
fun normalizeProcessName(name: String): String {
    return if (isLinux) name.lowercase().removeSuffix(".exe")
    else name.lowercase()
}
```

Then at every place where `exeName` is compared against any blocked set (systemShells,
scheduleBlockedProcesses, standaloneBlockedProcesses, dailyAllowanceBlockedProcesses,
launcherAllowedProcesses, launcherSafeProcesses), wrap both sides with `normalizeProcessName()`:

```kotlin
// BEFORE:
val exeName = processName.lowercase()
val isBlocked = blockedSet.contains(exeName)

// AFTER:
val exeName = normalizeProcessName(processName)
val isBlocked = blockedSet.map { normalizeProcessName(it) }.contains(exeName)
// OR, more efficiently — normalize the sets once on startup and cache them
```

For performance, normalize the blocked sets once when they are assigned rather than on
every comparison. The sets are small (tens of entries) so either approach is acceptable.

---

## 8. Block Presets

**File:** `src/main/kotlin/com/focusflow/enforcement/BlockPresets.kt`

Read the file. Every `BlockPreset` has a `processNames: List<String>` containing `.exe`
names. When a user applies a preset on Linux, the process names are added to the blocked
list as `discord.exe`, `steam.exe`, etc. Because of the normalization fix in Section 7c,
these will now be normalized to `discord`, `steam` before comparison — so presets **will
work correctly on Linux once Section 7c is done**, without any changes to `BlockPresets.kt`.

However, the preset **descriptions** mention Windows app names / ecosystem-specific names
that don't exist on Linux (e.g., "Microsoft Edge", "Windows Media Player"). These are UI
strings — no enforcement impact. Update them to be OS-neutral:

Find each `BlockPreset` description string and make it OS-agnostic where it mentions
Windows-specific apps:

```kotlin
// BEFORE:
description = "Chrome, Firefox, Edge, Opera, Brave"

// AFTER:
description = "Chrome, Firefox, Brave, Opera, and other browsers"

// BEFORE:
description = "Spotify, Netflix, VLC, Twitch, Windows Media Player"

// AFTER:
description = "Spotify, Netflix, VLC, Twitch, and media players"
```

Also add Linux-native process names to presets where the Windows name differs significantly.
The most important ones (since normalization handles `.exe` stripping, these are for apps
whose binary name is completely different on Linux):

```kotlin
BlockPreset(
    id = "browsers",
    processNames = listOf(
        "chrome.exe", "firefox.exe", "msedge.exe", "opera.exe", "brave.exe",
        // Linux equivalents (different binary names)
        "google-chrome", "google-chrome-stable", "chromium", "chromium-browser",
        "brave-browser", "opera"
        // firefox, msedge normalize correctly via removeSuffix(".exe") — no extra entry needed
    )
),
BlockPreset(
    id = "gaming",
    processNames = listOf(
        "steam.exe", "epicgameslauncher.exe", "battle.net.exe",
        "leagueclient.exe", "origin.exe",
        // Linux
        "steam",           // Steam Linux client
        "heroic",          // Heroic Games Launcher (Epic/GOG on Linux)
        "lutris"           // Lutris game manager
    )
),
```

Other preset entries (`discord`, `spotify`, `telegram`, `slack`, `zoom`) have identical
binary names on Linux and Windows (minus the `.exe`) — the normalization in Section 7c
handles them automatically. No extra entries needed.

---

## 9. Hosts File Blocking

**File:** `src/main/kotlin/com/focusflow/services/HostsBlocker.kt`

### Change 1 — Hardcoded Windows path
Find the `HOSTS_PATH` constant. It is hardcoded to `C:\Windows\...`. Change to:
```kotlin
private val HOSTS_PATH: String get() = when {
    isWindows -> "C:\\Windows\\System32\\drivers\\etc\\hosts"
    else      -> "/etc/hosts"
}
```

### Change 2 — Remove isWindows guards
Every public method starts with `if (!isWindows) return ...`. Remove these guards entirely.
`/etc/hosts` is standard on all Unix systems. The logic underneath already works cross-platform
(it's just file I/O).

### Change 3 — DNS cache flush
Find `flushDnsCache()`. It currently calls `ipconfig /flushdns`. Add a Linux branch:
```kotlin
private fun flushDnsCache() {
    when {
        isWindows -> { /* existing ipconfig /flushdns — untouched */ }
        isLinux   -> {
            // Try systemd-resolved first (Ubuntu 22.04+, Debian 12+)
            try { ProcessBuilder("systemd-resolve", "--flush-caches")
                .redirectErrorStream(true).start().waitFor() } catch (_: Exception) {}
            // Fallback: resolvectl (same tool, different name on some distros)
            try { ProcessBuilder("resolvectl", "flush-caches")
                .redirectErrorStream(true).start().waitFor() } catch (_: Exception) {}
            // Fallback: nscd (older systems)
            try { ProcessBuilder("nscd", "-i", "hosts")
                .redirectErrorStream(true).start().waitFor() } catch (_: Exception) {}
        }
    }
}
```

### Change 4 — canWriteHostsFile()
Find this function. It returns `false` on non-Windows. Remove the guard:
```kotlin
fun canWriteHostsFile(): Boolean {
    return try { java.io.File(HOSTS_PATH).canWrite() } catch (_: Exception) { false }
}
```

### Change 5 — BlockResult.NotWindows
Find the `BlockResult` sealed class / enum. Rename `NotWindows` to `NotSupported` or
add a `NoPermission` result so Linux users see a meaningful error when `/etc/hosts`
is not writable (they need to run FocusFlow with `sudo` or configure sudoers for hosts editing).

---

## 9. Network / Firewall Blocking

**File:** `src/main/kotlin/com/focusflow/enforcement/NetworkBlocker.kt`

Read the file to understand the existing data structures (`activeRules`, `pendingRules`,
the `addRule()` signature, `syncFromFirewall()`).

On Windows, this uses PowerShell `New-NetFirewallRule`. On Linux, the equivalent is
`iptables`. Both require root. The Linux implementation uses `sudo -n` (passwordless sudo)
and degrades gracefully if not available — blocking still works via process kill.

Follow the universal edit pattern for `isRunningAsAdmin()`, `addRule()`, `removeRule()`,
and `syncFromFirewall()`. Add these private Linux implementations:

```kotlin
private fun canRunSudoWithoutPassword(): Boolean {
    return try {
        ProcessBuilder("sudo", "-n", "true")
            .redirectErrorStream(true).start().waitFor() == 0
    } catch (_: Exception) { false }
}

private fun addRuleLinux(processName: String): Boolean {
    if (!canRunSudoWithoutPassword()) return false
    val baseName = processName.removeSuffix(".exe").trim()
    if (activeRules.contains(baseName.lowercase())) return true

    return try {
        val proc = ProcessBuilder(
            "sudo", "-n", "iptables", "-A", "OUTPUT",
            "-m", "owner", "--cmd-owner", baseName,
            "-j", "DROP",
            "-m", "comment", "--comment", "FocusFlow_Block_$baseName"
        ).redirectErrorStream(true).start()
        val exit = proc.waitFor()
        if (exit == 0) { activeRules.add(baseName.lowercase()); true } else false
    } catch (_: Exception) { false }
}

private fun removeRuleLinux(processName: String) {
    val baseName = processName.removeSuffix(".exe").trim()
    try {
        ProcessBuilder(
            "sudo", "-n", "iptables", "-D", "OUTPUT",
            "-m", "owner", "--cmd-owner", baseName,
            "-j", "DROP",
            "-m", "comment", "--comment", "FocusFlow_Block_$baseName"
        ).redirectErrorStream(true).start().waitFor()
    } catch (_: Exception) {}
    activeRules.remove(baseName.lowercase())
    pendingRules.remove(baseName.lowercase())
}

private fun syncFromFirewallLinux() {
    if (!canRunSudoWithoutPassword()) return
    try {
        val proc = ProcessBuilder(
            "sudo", "-n", "iptables", "-L", "OUTPUT", "-n", "--line-numbers"
        ).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        activeRules.clear()
        output.lineSequence()
            .filter { it.contains("FocusFlow_Block_") }
            .forEach { line ->
                val name = line.substringAfter("FocusFlow_Block_").substringBefore(" ").trim()
                if (name.isNotBlank()) activeRules.add(name.lowercase())
            }
    } catch (_: Exception) {}
}
```

---

## 10. VPN Blocker

**File:** `src/main/kotlin/com/focusflow/enforcement/VpnBlocker.kt`

Read the file. There are two issues to fix:

### Issue 1 — KNOWN_VPN_PROCESSES is all .exe names
The process names won't match on Linux. Add a parallel Linux set:

```kotlin
val KNOWN_VPN_PROCESSES: Set<String> get() = when {
    isWindows -> KNOWN_VPN_PROCESSES_WINDOWS
    isLinux   -> KNOWN_VPN_PROCESSES_LINUX
    else      -> emptySet()
}

// THE EXISTING SET renamed — copy every entry exactly, do not change anything
private val KNOWN_VPN_PROCESSES_WINDOWS = setOf(
    "nordvpn.exe", "nordvpn-service.exe",
    // ... all existing entries ...
)

private val KNOWN_VPN_PROCESSES_LINUX = setOf(
    // NordVPN
    "nordvpn", "nordvpnd",
    // ExpressVPN
    "expressvpn", "expressvpnd",
    // ProtonVPN
    "protonvpn", "protonvpn-app",
    // Windscribe
    "windscribe",
    // CyberGhost
    "cyberghost",
    // Surfshark
    "surfshark",
    // Private Internet Access
    "pia", "piactl",
    // Mullvad
    "mullvad", "mullvad-daemon",
    // IPVanish
    "ipvanish",
    // TunnelBear
    "tunnelbear",
    // OpenVPN (generic)
    "openvpn", "openvpn3",
    // WireGuard (generic)
    "wireguard", "wg-quick",
    // Cisco AnyConnect
    "vpnagentd", "vpnui", "anyconnect",
    // Palo Alto GlobalProtect
    "pangpa", "pangps", "globalprotect",
    // Fortinet FortiClient
    "forticlient", "fortisvpn",
    // Pulse Secure / Ivanti
    "pulsesvc", "pulsesecure",
    // Zscaler
    "zscalerd",
    // F5 VPN
    "f5fpc",
    // IVPN
    "ivpnd",
    // Tor
    "tor"
)
```

### Issue 2 — addCustomProcess() force-appends .exe
The method does `if (!it.endsWith(".exe")) "$it.exe"`. On Linux this is wrong.
Wrap the `.exe` appending in an `isWindows` check:

```kotlin
fun addCustomProcess(processName: String) {
    val lower = processName.trim().lowercase().let {
        if (isWindows && !it.endsWith(".exe")) "$it.exe" else it  // only append .exe on Windows
    }
    // rest of the method is unchanged
}
```

### Issue 3 — isVpnProcess() comparison
The existing check `KNOWN_VPN_PROCESSES.contains(lower)` works fine once `KNOWN_VPN_PROCESSES`
returns the correct OS-specific set (Issue 1 fix above). No additional changes needed.

---

## 11. Floating Block Overlay

**File:** `src/main/kotlin/com/focusflow/enforcement/FloatingBlockOverlay.kt`

Read the file. Line 44 has: `if (!isWindows) return`.

The entire AWT/Swing implementation underneath (`JWindow`, `GraphicsEnvironment`,
`SwingUtilities`, `JPanel`) is 100% standard Java — it works identically on Linux with X11.

**The only change needed is one line:**
```kotlin
// BEFORE (line 44):
if (!isWindows) return

// AFTER:
if (!isWindows && !isLinux) return
```

On Wayland, `JWindow.setAlwaysOnTop(true)` may or may not work depending on the compositor's
policy. If it doesn't work, the overlay appears but isn't truly always-on-top. This is
acceptable degradation — the process is still killed even if the overlay sits behind it briefly.

`AppBlocker.kt` calls `FloatingBlockOverlay.show()` — no changes needed there.

---

## 12. Kiosk Mode — Taskbar & Window

**File:** `src/main/kotlin/com/focusflow/services/FocusLauncherService.kt`

Read the file. Find `hideTaskbar()` and `showTaskbar()`. They use Win32 `FindWindowW` /
`ShowWindow` to literally hide/show the Windows taskbar.

On Linux, there is no universal taskbar API — every desktop environment (GNOME, KDE, XFCE)
has its own. The practical cross-platform approach: make FocusFlow go fullscreen and
always-on-top, which visually covers the taskbar without needing to hide it.

The Compose window already has `WindowPlacement.Fullscreen` support (cross-platform). The
Linux `hideTaskbar()` implementation just sets EWMH hints via `wmctrl`:

```kotlin
private fun hideTaskbarLinux() {
    try {
        // Best-effort: make the FocusFlow window fullscreen + above everything via EWMH
        // This works on GNOME, KDE, XFCE, Openbox, and most X11 window managers
        ProcessBuilder("wmctrl", "-r", ":ACTIVE:", "-b", "add,fullscreen,above")
            .redirectErrorStream(true).start()
    } catch (_: Exception) {}
}

private fun showTaskbarLinux() {
    try {
        ProcessBuilder("wmctrl", "-r", ":ACTIVE:", "-b", "remove,fullscreen,above")
            .redirectErrorStream(true).start()
    } catch (_: Exception) {}
}
```

Follow the universal edit pattern: rename the existing Win32 methods to `hideTaskbarWindows()`
/ `showTaskbarWindows()`, then create dispatching `hideTaskbar()` / `showTaskbar()`.

`emergencyRestoreWindows()` is called by `CrashReporter` — see Section 19 for that guard.
The method itself only needs its internals checked for OS-specific calls; read the method
and wrap any Win32-specific calls with `if (isWindows)`.

---

## 13. Keyboard Hook

**File:** `src/main/kotlin/com/focusflow/enforcement/GlobalKeyboardHook.kt`

Read the file. The existing implementation uses `SetWindowsHookExW` (Win32) — Windows-only.

On Wayland, a system-wide keyboard hook is intentionally impossible by design (security model).
On X11, Phase 1 uses `xbindkeys` (easy, no JNA). Phase 2 (evdev grab) is a future task.

Follow the universal edit pattern for `enable()` and `disable()`. Add:

```kotlin
// Phase 1 Linux keyboard suppression — xbindkeys on X11 only
// Writes a temporary config and runs xbindkeys in foreground-daemon mode.
// On Wayland: no-op (logged but not an error).

private var xbindkeysProcess: Process? = null

private fun enableLinux() {
    if (!isX11) return  // Wayland: graceful no-op
    try {
        val configFile = java.io.File(System.getProperty("user.home"), ".focusflow-xbindkeys")
        configFile.writeText("""
            # FocusFlow keyboard suppression — auto-generated
            "echo suppressed"
              Super_L
            "echo suppressed"
              Super_R
            "echo suppressed"
              Alt+Tab
            "echo suppressed"
              Alt+F4
            "echo suppressed"
              ctrl+alt+t
        """.trimIndent())
        xbindkeysProcess = ProcessBuilder("xbindkeys", "-f",
            configFile.absolutePath, "--nodaemon")
            .redirectErrorStream(true).start()
    } catch (_: Exception) {
        // xbindkeys not installed — log and continue without key suppression
    }
}

private fun disableLinux() {
    xbindkeysProcess?.destroyForcibly()
    xbindkeysProcess = null
    try {
        java.io.File(System.getProperty("user.home"), ".focusflow-xbindkeys").delete()
    } catch (_: Exception) {}
}
```

Also update the `isActive` property:
```kotlin
val isActive: Boolean get() = when {
    isWindows -> /* existing hookHandle check — untouched */
    isLinux   -> xbindkeysProcess?.isAlive == true
    else      -> false
}
```

> **Future work (Phase 2):** Proper evdev exclusive keyboard grab requires opening
> `/dev/input/event*` devices, calling `ioctl(fd, EVIOCGRAB, 1)`, and forwarding
> non-suppressed events to a `uinput` virtual device. This requires the user to be in
> the `input` group. Do not implement this now — track it as a separate follow-up task.

---

## 14. Installed Apps Scanner

**File:** `src/main/kotlin/com/focusflow/enforcement/InstalledAppsScanner.kt`

Read the file to understand the `InstalledApp` data class (it has at minimum `displayName`,
`processName`, `exePath`). On Linux, installed apps are described by `.desktop` files in
`/usr/share/applications/` and `~/.local/share/applications/`.

### 14a — InstalledAppsScanner.kt — getRunningApps() exe filter, curated map, systemIgnore

Before implementing `scanLinux()`, three additional gaps in `InstalledAppsScanner.kt` must
be fixed. These are **separate from** the installed-apps registry scan and affect the
running-process list shown in `AppBlockerScreen`:

**Gap 1 — `getRunningApps()` exe filter (critical):**
The function ends its `.filter { }` with `app.processName.endsWith(".exe")` — this returns
an **empty list on Linux** because Linux process names have no `.exe` suffix. Fix:
```kotlin
// Before:
.filter { app ->
    app.processName.isNotBlank() &&
    app.processName !in systemIgnore &&
    app.processName.endsWith(".exe")  // ← kills all results on Linux
}
// After:
.filter { app ->
    app.processName.isNotBlank() &&
    app.processName !in if (isLinux) linuxSystemIgnore else windowsSystemIgnore &&
    if (isWindows) app.processName.endsWith(".exe") else !app.processName.contains(".")
}
```

**Gap 2 — `curated` display-name map (medium):**
All keys are `.exe` names (`"chrome.exe" to "Google Chrome"`, etc.). On Linux, running
process names are `chrome`, `firefox`, etc. The map will never match. Add a Linux-native
curated map and dispatch by OS:
```kotlin
private val windowsCurated: Map<String, String> = mapOf(
    "chrome.exe" to "Google Chrome", /* ... existing entries ... */
)
private val linuxCurated: Map<String, String> = mapOf(
    "chrome"        to "Google Chrome",
    "chromium"      to "Chromium",
    "firefox"       to "Mozilla Firefox",
    "discord"       to "Discord",
    "slack"         to "Slack",
    "zoom"          to "Zoom",
    "telegram-desk" to "Telegram",
    "signal-deskto" to "Signal",
    "spotify"       to "Spotify",
    "steam"         to "Steam",
    "obs"           to "OBS Studio",
    "vlc"           to "VLC Media Player",
    "code"          to "VS Code",
    "idea"          to "IntelliJ IDEA",
    "pycharm"       to "PyCharm",
)
private val curated: Map<String, String> get() = if (isLinux) linuxCurated else windowsCurated
```

**Gap 3 — `systemIgnore` set (medium):**
All entries are Windows system process names (`.exe`). On Linux, equivalent system processes
that should be ignored are named differently. Add a Linux-native ignore set:
```kotlin
private val windowsSystemIgnore: Set<String> = setOf(
    "system", "registry", "smss.exe", "csrss.exe", /* ... existing entries ... */
)
private val linuxSystemIgnore: Set<String> = setOf(
    "systemd", "kthreadd", "kworker", "ksoftirqd", "rcu_sched", "migration",
    "watchdog", "kdevtmpfs", "kauditd", "scsi_eh", "dbus-daemon",
    "pulseaudio", "pipewire", "at-spi-bus-laun", "gvfsd",
    "focusflow"  // ← own process
)
private val systemIgnore: Set<String> get() = if (isLinux) linuxSystemIgnore else windowsSystemIgnore
```

> **Dependency:** These three fixes must be done BEFORE or alongside `scanLinux()` — they
> all affect the same file and should be in the same commit.

---

Follow the universal edit pattern for `scan()`. Add:

```kotlin
private fun scanLinux(): List<InstalledApp> {
    val apps = mutableListOf<InstalledApp>()
    val searchDirs = listOf(
        java.io.File("/usr/share/applications"),
        java.io.File(System.getProperty("user.home"), ".local/share/applications"),
        java.io.File("/var/lib/flatpak/exports/share/applications"),
        java.io.File(System.getProperty("user.home"),
            ".local/share/flatpak/exports/share/applications")
    )
    searchDirs.filter { it.exists() }.forEach { dir ->
        dir.walkTopDown()
            .filter { it.extension == "desktop" }
            .forEach { f -> parseDesktopFile(f)?.let { apps.add(it) } }
    }
    return apps.distinctBy { it.processName }
}

private fun parseDesktopFile(file: java.io.File): InstalledApp? {
    val props = mutableMapOf<String, String>()
    file.forEachLine { line ->
        val t = line.trim()
        if (t.startsWith("#") || t.startsWith("[")) return@forEachLine
        val idx = t.indexOf('=')
        if (idx > 0) props[t.substring(0, idx).trim()] = t.substring(idx + 1).trim()
    }
    val name = props["Name"] ?: return null
    val exec = props["Exec"] ?: return null
    if (props["Hidden"]?.lowercase() == "true") return null
    if (props["NoDisplay"]?.lowercase() == "true") return null

    // Strip args and %field-codes from Exec to get the binary name
    val processName = exec
        .substringBefore(" ")
        .substringAfterLast("/")
        .replace(Regex("%[a-zA-Z]"), "")
        .trim()
        .takeIf { it.isNotBlank() } ?: return null

    return InstalledApp(
        displayName = name,
        processName = processName,
        exePath     = exec.substringBefore(" ")
    )
}
```

---

## 15. App Icon Extraction

**File:** `src/main/kotlin/com/focusflow/enforcement/AppIconExtractor.kt`

Read the file to understand the return type (likely `ImageBitmap?`). On Linux, icons are
`.png`/`.svg`/`.xpm` files in `/usr/share/icons/` and `/usr/share/pixmaps/`.

Follow the universal edit pattern for `extract()`. Add:

```kotlin
private fun extractLinux(processName: String): ImageBitmap? {
    val iconName = processName.removeSuffix(".exe").lowercase()
    val iconDirs = listOf(
        "/usr/share/pixmaps",
        "/usr/share/icons/hicolor/48x48/apps",
        "/usr/share/icons/hicolor/64x64/apps",
        "/usr/share/icons/hicolor/128x128/apps",
        "/usr/share/icons/hicolor/256x256/apps"
    )
    val extensions = listOf(".png", ".xpm", "")

    for (dir in iconDirs) {
        for (ext in extensions) {
            val f = java.io.File(dir, "$iconName$ext")
            if (f.exists()) {
                return try {
                    org.jetbrains.skia.Image.makeFromEncoded(f.readBytes())
                        .toComposeImageBitmap()
                } catch (_: Exception) { null }
            }
        }
    }
    return null
}
```

SVG icons are intentionally skipped — Skia doesn't support SVG decoding without extra libraries.
The `.png` fallback in `/usr/share/icons/` covers 95%+ of apps.

---

## 16. Startup Persistence

**File:** `src/main/kotlin/com/focusflow/enforcement/WindowsStartupManager.kt`

Read the file to understand the existing `enable()`, `disable()`, `isEnabled()` interface.
On Windows it writes to the `HKCU\...\Run` registry key. On Linux, use the XDG autostart spec:
`~/.config/autostart/<app>.desktop`.

Follow the universal edit pattern for all three methods:

```kotlin
private fun enableLinux() {
    val autostartDir = java.io.File(System.getProperty("user.home"), ".config/autostart")
    autostartDir.mkdirs()
    val exePath = ProcessHandle.current().info().command().orElse("focusflow")
    java.io.File(autostartDir, "focusflow.desktop").writeText("""
        [Desktop Entry]
        Type=Application
        Name=FocusFlow
        Exec=$exePath
        Hidden=false
        NoDisplay=false
        X-GNOME-Autostart-enabled=true
        Comment=FocusFlow productivity enforcement
    """.trimIndent())
}

private fun disableLinux() {
    java.io.File(System.getProperty("user.home"),
        ".config/autostart/focusflow.desktop").delete()
}

private fun isEnabledLinux(): Boolean {
    return java.io.File(System.getProperty("user.home"),
        ".config/autostart/focusflow.desktop").exists()
}
```

---

## 17. Watchdog

**File:** `src/main/kotlin/com/focusflow/enforcement/WatchdogInstaller.kt`

Read the file to understand the existing `install()`, `uninstall()`, `isInstalled()` interface.
On Windows it uses Windows Task Scheduler (`schtasks`). On Linux, use a systemd user service
with a cron fallback for systems without systemd.

Follow the universal edit pattern. Add:

```kotlin
private fun installLinux() {
    if (isSystemdAvailable()) installSystemdWatchdog() else installCronWatchdog()
}

private fun isSystemdAvailable(): Boolean {
    return try {
        ProcessBuilder("systemctl", "--user", "is-system-running")
            .redirectErrorStream(true).start().waitFor() != 127  // 127 = not found
    } catch (_: Exception) { false }
}

private fun installSystemdWatchdog() {
    val serviceDir = java.io.File(System.getProperty("user.home"), ".config/systemd/user")
    serviceDir.mkdirs()
    val exePath = ProcessHandle.current().info().command().orElse("focusflow")
    java.io.File(serviceDir, "focusflow-watchdog.service").writeText("""
        [Unit]
        Description=FocusFlow Watchdog
        After=graphical-session.target

        [Service]
        Type=simple
        ExecStart=$exePath
        Restart=on-failure
        RestartSec=120

        [Install]
        WantedBy=default.target
    """.trimIndent())
    try {
        ProcessBuilder("systemctl", "--user", "daemon-reload")
            .redirectErrorStream(true).start().waitFor()
        ProcessBuilder("systemctl", "--user", "enable", "--now",
            "focusflow-watchdog.service")
            .redirectErrorStream(true).start().waitFor()
    } catch (_: Exception) {}
}

private fun installCronWatchdog() {
    val exePath = ProcessHandle.current().info().command().orElse("focusflow")
    val cronLine = "*/2 * * * * pgrep -x focusflow > /dev/null || $exePath &  # focusflow-watchdog"
    try {
        val existing = ProcessBuilder("crontab", "-l")
            .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
        if (!existing.contains("focusflow-watchdog")) {
            val proc = ProcessBuilder("crontab", "-").redirectErrorStream(true).start()
            proc.outputStream.bufferedWriter().use { it.write("$existing\n$cronLine\n") }
            proc.waitFor()
        }
    } catch (_: Exception) {}
}

private fun uninstallLinux() {
    try {
        ProcessBuilder("systemctl", "--user", "disable", "--now",
            "focusflow-watchdog.service").redirectErrorStream(true).start().waitFor()
        java.io.File(System.getProperty("user.home"),
            ".config/systemd/user/focusflow-watchdog.service").delete()
    } catch (_: Exception) {}
    try {
        val existing = ProcessBuilder("crontab", "-l")
            .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
        val cleaned = existing.lines()
            .filter { !it.contains("focusflow-watchdog") }.joinToString("\n")
        val proc = ProcessBuilder("crontab", "-").redirectErrorStream(true).start()
        proc.outputStream.bufferedWriter().use { it.write(cleaned) }
        proc.waitFor()
    } catch (_: Exception) {}
}
```

---

## 19. Registry Lockdown

**File:** `src/main/kotlin/com/focusflow/enforcement/RegistryLockdown.kt`

**No changes needed.** Read the file and confirm that `enable()` and `disable()` both already
return early if not on Windows. Leave them as-is. There is no universal Linux equivalent.

The combination of NuclearMode (kills escape processes) + fullscreen kiosk window + keyboard
hook provides equivalent enforcement on Linux.

---

## 20. Crash Reporter & Main.kt

`emergencyRestoreWindows()` is called unconditionally in **two separate files**. Both must
be guarded. Do not fix one and miss the other.

### 20a — CrashReporter.kt (line ~529)

**File:** `src/main/kotlin/com/focusflow/services/CrashReporter.kt`

```kotlin
// BEFORE:
try { FocusLauncherService.emergencyRestoreWindows() } catch (_: Throwable) {}

// AFTER:
if (isWindows) {
    try { FocusLauncherService.emergencyRestoreWindows() } catch (_: Throwable) {}
}
```

Also fix the crash log Desktop path (line 482). `~/Desktop` may not exist on minimal Linux:
```kotlin
val desktop = java.io.File(System.getProperty("user.home"), "Desktop")
    .takeIf { it.exists() }
    ?: java.io.File(System.getProperty("user.home"))
```

### 20b — Main.kt (line ~81)

**File:** `src/main/kotlin/com/focusflow/Main.kt`

`Main.kt` calls `FocusLauncherService.emergencyRestoreWindows()` in the startup sequence
as an absolute fallback if `loadFromDb()` throws. Same fix:

```kotlin
// BEFORE:
try { FocusLauncherService.loadFromDb() } catch (_: Throwable) {
    try { FocusLauncherService.emergencyRestoreWindows() } catch (_: Throwable) {}
}

// AFTER:
try { FocusLauncherService.loadFromDb() } catch (_: Throwable) {
    if (isWindows) {
        try { FocusLauncherService.emergencyRestoreWindows() } catch (_: Throwable) {}
    }
}
```

### 20c — FocusLauncherService.emergencyRestoreWindows() itself (line ~359)

**File:** `src/main/kotlin/com/focusflow/services/FocusLauncherService.kt`

Read this method. If it contains Win32 JNA calls (User32, kernel32, taskbar manipulation),
add `if (!isWindows) return` as the very first line of the method body so it can never
execute on Linux even if a call site is accidentally not guarded.

---

## 21. System Tray

**File:** `src/main/kotlin/com/focusflow/services/SystemTrayManager.kt`

Read the file. Java's `java.awt.SystemTray` works on Linux with KDE, XFCE, and most
non-GNOME desktops. On GNOME, `SystemTray.isSupported()` returns `false` (GNOME removed
tray support in 3.26 — extensions re-add it, but we can't rely on them).

The fix is minimal — check if `SystemTray.isSupported()` returns `false` and return gracefully.
This may already be handled. If `SystemTrayManager.init()` calls `SystemTray.getSystemTray()`
without checking `isSupported()` first, add that check:

```kotlin
fun init() {
    if (!java.awt.SystemTray.isSupported()) {
        // GNOME doesn't support system tray natively — degrade silently
        return
    }
    // existing code continues unchanged
}
```

No other changes needed here.

---

## 22. Sound Aversion

**File:** `src/main/kotlin/com/focusflow/services/SoundAversion.kt`

**No changes needed.** Java's `javax.sound.sampled` API (used for playing sounds) works
on Linux via PulseAudio/PipeWire's ALSA compatibility layer. Verify by running the app
on Linux and confirming sound plays — but no code changes are expected.

---

## 23. Confirmed Cross-Platform (No Changes)

The following files were fully reviewed and contain **zero** Windows-specific code. No
edits are needed. This section exists to confirm they were not overlooked.

| File | Status | Notes |
|---|---|---|
| `ResourceMonitorService.kt` | ✅ No changes | Uses `ProcessHandle` + coroutines — fully cross-platform |
| `StandaloneBlockService.kt` | ✅ No changes | Database + timer logic only — no OS API calls |
| `KillSwitchService.kt` | ✅ No changes | State machine + coroutines — no OS API calls |

---

## 24a. FocusSessionService — .exe Forcing on Session Start ← CRITICAL

**File:** `src/main/kotlin/com/focusflow/services/FocusSessionService.kt`

**This silently breaks ALL process blocking on Linux.** At session start (line 92), the service
forces `.exe` onto every process name before handing the set to `ProcessMonitor`:

```kotlin
// CURRENT (line 92) — WRONG on Linux:
ProcessMonitor.sessionExtraBlockedProcesses =
    blockedProcesses.map { it.lowercase().let { n -> if (!n.endsWith(".exe")) "$n.exe" else n } }.toSet()
```

On Linux, `firefox` → `firefox.exe`. The running process is `firefox`. The comparison
never matches. No blocking happens for the entire session.

Fix — normalize using the same `normalizeProcessName()` added in Section 7c:

```kotlin
// AFTER:
ProcessMonitor.sessionExtraBlockedProcesses =
    blockedProcesses.map { normalizeProcessName(it) }.toSet()
```

If `normalizeProcessName` is not accessible from `FocusSessionService`, inline the logic:
```kotlin
ProcessMonitor.sessionExtraBlockedProcesses =
    blockedProcesses.map { name ->
        when {
            isWindows -> name.lowercase().let { if (!it.endsWith(".exe")) "$it.exe" else it }
            isLinux   -> name.lowercase().removeSuffix(".exe")
            else      -> name.lowercase()
        }
    }.toSet()
```

---

## 24b. AppBlockerScreen — .exe Forcing When Adding Processes ← CRITICAL

**File:** `src/main/kotlin/com/focusflow/ui/screens/AppBlockerScreen.kt`

Three places in `AppBlockerScreen` unconditionally force `.exe` when the user manually
enters a process name. On Linux, entering `firefox` saves `firefox.exe` to the database.
Combined with Section 24a, this means a user can never successfully add or block an app
on Linux.

### Fix 1 — `addManual()` function (line ~256)

```kotlin
// BEFORE:
val proc = trimmed.lowercase().let { if (it.endsWith(".exe")) it else "$it.exe" }
if (proc == ".exe" || proc.length <= 4) { manualError = "Name must end in .exe (e.g. chrome.exe)"; return }

// AFTER:
val proc = if (isWindows) {
    trimmed.lowercase().let { if (it.endsWith(".exe")) it else "$it.exe" }
} else {
    trimmed.lowercase().removeSuffix(".exe")  // strip .exe if user typed it; store without
}
if (proc.isBlank() || proc.length < 2) {
    manualError = if (isWindows) "Enter a process name (e.g. chrome.exe)"
                  else           "Enter a process name (e.g. firefox)"
    return
}
```

### Fix 2 — inline `.exe` coercion at line ~1244 and ~1264 (same pattern)

Both occurrences look like:
```kotlin
val proc = manualExe.trim().lowercase().let { if (it.endsWith(".exe")) it else "$it.exe" }
```
Apply the same OS-conditional fix as above.

### Fix 3 — `processColorMap` (lines 63–97)

The color map uses `.exe` keys (`"discord.exe" to Color(...)`, etc.). On Linux, the
running process is `discord` (no `.exe`), so no color is assigned and the colored chip
UI shows no branding. Add Linux process names alongside the Windows ones:

```kotlin
private val processColorMap = mapOf(
    // Windows names (existing — do not change)
    "chrome.exe"   to Color(0xFF4285F4),
    "firefox.exe"  to Color(0xFFFF6611),
    "discord.exe"  to Color(0xFF5865F2),
    // ... rest of existing entries unchanged ...
    // Linux names (new — same colors, no .exe)
    "chrome"             to Color(0xFF4285F4),
    "google-chrome"      to Color(0xFF4285F4),
    "google-chrome-stable" to Color(0xFF4285F4),
    "chromium"           to Color(0xFF4285F4),
    "chromium-browser"   to Color(0xFF4285F4),
    "firefox"            to Color(0xFFFF6611),
    "msedge"             to Color(0xFF0078D7),
    "opera"              to Color(0xFFCC1A22),
    "brave-browser"      to Color(0xFFFF3800),
    "discord"            to Color(0xFF5865F2),
    "slack"              to Color(0xFF4A154B),
    "zoom"               to Color(0xFF2196F3),
    "telegram"           to Color(0xFF2AABEE),
    "whatsapp"           to Color(0xFF25D366),
    "signal"             to Color(0xFF3A76F0),
    "spotify"            to Color(0xFF1DB954),
    "steam"              to Color(0xFF1B2838),
    "heroic"             to Color(0xFF2C2C2C),  // Heroic Games (Epic/GOG on Linux)
    "obs"                to Color(0xFF302E31),
    "vlc"                to Color(0xFFFF8800),
    "code"               to Color(0xFF007ACC),  // VS Code
    "idea"               to Color(0xFFFF318C),  // IntelliJ IDEA
    "pycharm"            to Color(0xFF21D789),
    "webstorm"           to Color(0xFF00CDD7)
)
```

The `processColorMap` lookup should also normalize the key before lookup:
```kotlin
// Change from:
processColorMap[processName.lowercase()]
// To:
val lookupName = normalizeProcessName(processName)
processColorMap[lookupName]
```

### Fix 4 — placeholder text (line ~489)

```kotlin
// BEFORE:
placeholder = { Text("e.g. discord.exe", color = OnSurface2) }
// AFTER:
placeholder = { Text(if (isWindows) "e.g. discord.exe" else "e.g. discord", color = OnSurface2) }
```

---

## 24c. SettingsScreen — Windows-Specific Quick-Add Presets and Labels

**File:** `src/main/kotlin/com/focusflow/ui/screens/SettingsScreen.kt`

### Fix 1 — Quick-add presets (line ~474) force `.exe`

```kotlin
// BEFORE:
val presets = listOf(
    "Discord"    to "discord.exe",
    "Steam"      to "steam.exe",
    ...
)

// AFTER:
val presets = if (isWindows) listOf(
    "Discord"    to "discord.exe",
    "Steam"      to "steam.exe",
    "Spotify"    to "Spotify.exe",
    "Twitch"     to "twitch.exe",
    "Epic Games" to "EpicGamesLauncher.exe",
    "WhatsApp"   to "WhatsApp.exe",
    "Telegram"   to "Telegram.exe",
    "Battle.net" to "Battle.net Launcher.exe"
) else listOf(
    "Discord"    to "discord",
    "Steam"      to "steam",
    "Spotify"    to "spotify",
    "Telegram"   to "telegram",
    "WhatsApp"   to "whatsapp",
    "VLC"        to "vlc",
    "Brave"      to "brave-browser",
    "Lutris"     to "lutris"
)
```

### Fix 2 — Process monitor status text (lines 149–155)

```kotlin
// BEFORE:
subtitle = if (isWindows) "Active — 500ms polling + instant WinEventHook"
           else "Inactive — only enforced on Windows",

// AFTER:
subtitle = when {
    isWindows -> "Active — 500ms polling + instant WinEventHook"
    isLinux   -> "Active — 500ms xdotool polling (X11)"
    else      -> "Inactive — only enforced on Windows/Linux"
},
// Same for the icon/tint:
if (isWindows || isLinux) Icons.Default.CheckCircle else Icons.Default.Warning
tint = if (isWindows || isLinux) Success else Warning
```

### Fix 3 — Startup toggle (lines 371–378)

```kotlin
// BEFORE:
label    = strings.settingsStartWithWindows,
subtitle = if (!isWindows) "Only available on Windows"
           else if (startWithWin) "FocusFlow launches at login (HKCU\\Run)"
           else "FocusFlow does not start automatically",
trailing = {
    Switch(
        checked  = startWithWin,
        enabled  = isWindows,
        ...
    )
}

// AFTER:
label    = if (isWindows) strings.settingsStartWithWindows else strings.settingsStartWithSystem,
subtitle = when {
    isWindows && startWithWin  -> "FocusFlow launches at login (HKCU\\Run)"
    isWindows && !startWithWin -> "FocusFlow does not start automatically"
    isLinux   && startWithWin  -> "FocusFlow launches at login (~/.config/autostart)"
    isLinux   && !startWithWin -> "FocusFlow does not start automatically"
    else                       -> "Only available on Windows and Linux"
},
trailing = {
    Switch(
        checked  = startWithWin,
        enabled  = isWindows || isLinux,  // enable on Linux once WindowsStartupManager supports it
        onCheckedChange = { enabled ->
            startWithWin = enabled
            scope.launch {
                withContext(Dispatchers.IO) {
                    if (enabled) WindowsStartupManager.enable()
                    else         WindowsStartupManager.disable()
                }
            }
        }
    )
}
```

> `settingsStartWithSystem` string key needs to be added to `Translations.kt` (see Section 24d).

---

## 24d. Translations.kt — Windows-Specific String Keys

**File:** `src/main/kotlin/com/focusflow/i18n/Translations.kt`

Read `Translations.kt` to understand the `Strings` data class structure. Several fields
contain Windows-specific strings that need Linux equivalents or OS-conditional values.

### New fields to add to the `Strings` data class
```kotlin
data class Strings(
    // ... existing fields ...
    val navLinuxSetup: String,                  // "Linux Setup"
    val settingsStartWithSystem: String,        // "Start with system"
    val settingsFirewallNoteLinux: String,      // "Adds iptables outbound rules (requires sudo)"
    val settingsSysTrayDescLinux: String,       // "FocusFlow runs in your system tray"
)
```

For each language's `Strings(...)` constructor call, add the new fields with appropriate
translations (or English fallbacks for non-English languages where translation is unavailable):

```kotlin
// English:
navLinuxSetup           = "Linux Setup",
settingsStartWithSystem = "Start with system",
settingsFirewallNoteLinux = "Adds iptables outbound rules (requires passwordless sudo)",
settingsSysTrayDescLinux  = "FocusFlow runs in your system tray (KDE/XFCE).",

// Spanish (es) — fallback to English where translation not available
navLinuxSetup           = "Configuración de Linux",
settingsStartWithSystem = "Iniciar con el sistema",
// etc.
```

### Fields that need OS-conditional display (no code change in Translations.kt — handled in UI)
| Field | Current value | Fix location |
|---|---|---|
| `settingsProcessNameHint` | "Process name (e.g. chrome.exe)" | SettingsScreen.kt — show OS-conditional hint |
| `blockerManualEntryHint` | "Know the .exe name? Type it directly" | AppBlockerScreen.kt — OS-conditional |
| `blockerNoAppsBlockedBody` | "...type a .exe name to add..." | AppBlockerScreen.kt — OS-conditional |
| `blockerTypeName` | "Type .exe name…" | AppBlockerScreen.kt — OS-conditional |
| `settingsFirewallNote` | "Adds a Windows Firewall rule..." | SettingsScreen.kt — use `settingsFirewallNoteLinux` on Linux |
| `settingsSysTrayDesc` | "FocusFlow runs in your Windows system tray." | SettingsScreen.kt — use `settingsSysTrayDescLinux` on Linux |

---

## 24e. DailyAllowanceTracker — Linux Foreground Tracking Improvement

**File:** `src/main/kotlin/com/focusflow/services/DailyAllowanceTracker.kt`

**Current state:** The tracker already has Linux handling. Line 133:
```kotlin
val foregroundProcess = if (isWindows) getForegroundProcessName()?.lowercase() else null
```
On Linux, `foregroundProcess` is always `null`, so the `else` branch at line 159 falls
back to "is the process running?" — which works, but over-counts (accumulates time even
when the app is in the background).

**Fix:** Once Phase 2 (foreground detection) is complete, plug in the Linux API here:
```kotlin
val foregroundProcess = when {
    isWindows -> getForegroundProcessName()?.lowercase()
    isLinux   -> getLinuxForegroundProcess()?.first?.lowercase()  // from WinApiBindings
    else      -> null
}
```

> **Dependency:** This fix cannot be done until Section 3 (WinEventHook / `getLinuxForegroundProcess()`)
> is complete. It is a quality improvement, not a crash fix. The existing `else` fallback
> is acceptable for the initial release.

**Also note:** The `runningMap` at line 144 builds process names as `java.io.File(cmd).name.lowercase()`.
On Linux, cmd is e.g. `/usr/bin/firefox` → `File.name` → `firefox` (no `.exe`). The lookup
`runningMap.containsKey(proc)` where `proc = allowance.processName.lowercase()` will fail
if the stored name is `firefox.exe`. The same `normalizeProcessName()` fix from Section 7c
must be applied here — normalize `proc` before the `containsKey` lookup:
```kotlin
val proc = normalizeProcessName(allowance.processName)
val isRunning = runningMap.containsKey(proc)
```

---

## 24f. OnboardingScreen — Windows-Specific Row Titles

**File:** `src/main/kotlin/com/focusflow/ui/components/OnboardingScreen.kt`

All action buttons are already guarded by `if (isWindows)` — they simply don't render on
Linux. However, the `OnboardingPermRow` titles and subtitles are always visible and contain
Windows-specific text. On Linux, a user will see row titles like "Windows Defender Exclusion",
"Windows Firewall Rules", and "Auto-Start with Windows" even though those rows have no
actionable content (the buttons are hidden).

The rows are approximately:
| Row Title | Issue | Fix |
|---|---|---|
| "Windows Defender Exclusion" | Title is Windows-only | Hide entire row on Linux with `if (isWindows)` |
| "Disable Focus Assist (Do Not Disturb)" | Subtitle says "Windows DND" | Wrap row in `if (isWindows)` or change to "System Do Not Disturb" |
| "Auto-Start with Windows" | Title wrong on Linux | OS-conditional: `if (isWindows) "Auto-Start with Windows" else "Auto-Start at Login"` |
| "Windows Firewall Rules" | Title is Windows-only | OS-conditional: `if (isWindows) "Windows Firewall Rules" else "iptables Firewall Rules"` |
| Bottom text "Settings → Windows Setup & Permissions" | Windows-specific | `if (isWindows) "Settings → Windows Setup & Permissions" else "Settings → Linux Setup"` |

For the "Auto-Start" row specifically, the `Switch` `onCheckedChange` calls `WindowsStartupManager.enable()/disable()` without an OS guard. Once Section 17 (Startup Persistence) is complete, `WindowsStartupManager.enable()` dispatches by OS and is safe on Linux. Until then, wrap the `onCheckedChange` body with `if (isWindows)` as a temporary guard.

---

## 25. UI — Nav, Setup Screen & App.kt

**Files:** `SideNav.kt`, `Models.kt`, `App.kt`, `WindowsSetupScreen.kt`, `BlockDefenseScreen.kt`, `OsBanner.kt`, `VpnNetworkScreen.kt`

### 24a — Models.kt — add LINUX_SETUP screen enum value
Read `Models.kt`. Find the `Screen` enum at line 123. It has `WINDOWS_SETUP`. Add:

```kotlin
enum class Screen {
    // ... existing values ...
    WINDOWS_SETUP,
    LINUX_SETUP,   // ADD THIS
    VPN_NETWORK, CONTACT
}
```

### 24b — SideNav.kt — conditional setup screen in nav
Read `SideNav.kt`. Line 87 hardcodes `Screen.WINDOWS_SETUP` in the nav item list.
Make it OS-conditional:

```kotlin
// BEFORE (line 87):
NavItem(Screen.WINDOWS_SETUP, s.navWindowsSetup, Icons.Default.AdminPanelSettings),

// AFTER:
NavItem(
    screen = if (isWindows) Screen.WINDOWS_SETUP else Screen.LINUX_SETUP,
    label  = if (isWindows) s.navWindowsSetup else "Linux Setup",
    icon   = Icons.Default.AdminPanelSettings
),
```

### 24c — WindowsSetupScreen.kt — create a Linux equivalent screen
Read `WindowsSetupScreen.kt` to understand its structure (it explains admin rights, Task
Scheduler, etc.). Create a new file `LinuxSetupScreen.kt` in the same directory that
explains the Linux setup steps:
- How to install xdotool: `sudo apt install xdotool wmctrl xbindkeys`
- How to enable passwordless sudo for iptables (for network blocking)
- Note about Wayland limitations
- How to check current display server: `echo $XDG_SESSION_TYPE`

### 24d — App.kt — add LINUX_SETUP routing (line 206)
**File:** `src/main/kotlin/com/focusflow/App.kt`

The screen router is at line ~206. It currently has:
```kotlin
Screen.WINDOWS_SETUP  -> WindowsSetupScreen()
```
Add the Linux case immediately after:
```kotlin
Screen.WINDOWS_SETUP  -> WindowsSetupScreen()
Screen.LINUX_SETUP    -> LinuxSetupScreen()   // ADD THIS
```

### 24e — App.kt — guard the registry orphan dialog (line ~272)
**File:** `src/main/kotlin/com/focusflow/App.kt`

Around line 272 there is a dialog titled "Task Manager May Be Disabled". This dialog:
- Checks whether Windows Task Manager is disabled in the registry
- Offers a "Restart as Admin" button that runs PowerShell `Start-Process -Verb RunAs`

Both the check and the dialog are Windows-specific. On Linux, the check will always fail
or throw, and the PowerShell command will crash. Find the `showRegistryOrphanDialog` state
and its setter, and wrap the entire dialog (and the code that sets the state) with
`if (isWindows)`:

```kotlin
// Guard wherever showRegistryOrphanDialog is set to true:
if (isWindows && /* existing registry orphan check condition */) {
    showRegistryOrphanDialog = true
}

// Guard the dialog composable itself:
if (isWindows && showRegistryOrphanDialog) {
    AlertDialog(
        // ... existing dialog code unchanged ...
    )
}
```

The "Restart as Admin" `confirmButton` block that runs
`ProcessBuilder("powershell", ..., "Start-Process -FilePath ... -Verb RunAs")` is inside
the dialog and is already protected if the entire dialog is behind `if (isWindows)`.
Do not change the PowerShell command itself — it must remain unchanged for Windows.

### 24f — OsBanner.kt — show Linux (beta) instead of Windows-only
Read `OsBanner.kt`. It currently shows a "Windows only" warning on non-Windows systems.
Change the non-Windows branch to distinguish Linux from other platforms:

```kotlin
when {
    isWindows -> { /* existing Windows banner — untouched */ }
    isLinux   -> LinuxBetaBanner()    // show "Linux (beta)" banner
    else      -> WindowsOnlyBanner()  // still show unsupported for Mac/other
}
```

### 24g — BlockDefenseScreen.kt — OS-conditional text
Read `BlockDefenseScreen.kt`. Where it describes Windows-specific features (Task Manager
disable, Registry Editor lockdown, PowerShell blocking), add `isLinux` branches:

```kotlin
if (isWindows) {
    Text("Disables Task Manager, Registry Editor, and sign-out shortcuts")
} else if (isLinux) {
    Text("Kills system monitors, terminals, and file managers. Registry lockdown has no Linux equivalent.")
}
```

### 24h — VpnNetworkScreen.kt — four issues

**File:** `src/main/kotlin/com/focusflow/ui/screens/VpnNetworkScreen.kt`

There are four distinct issues in this file:

**Issue 1 — Quick-add VPN preset map (lines ~40–57): all `.exe` keys (medium)**
The map of known VPN names used for one-tap adding is entirely `.exe`-keyed. On Linux,
clicking "NordVPN" adds `nordvpn.exe` to the blocked list, which never matches. Make the
preset map OS-conditional:
```kotlin
private val vpnQuickAdd: Map<String, String> get() = if (isWindows) mapOf(
    "NordVPN"       to "nordvpn.exe",
    "ExpressVPN"    to "expressvpn.exe",
    // ... all existing Windows entries ...
) else mapOf(
    "NordVPN"       to "nordvpnd",
    "ExpressVPN"    to "expressvpn",
    "ProtonVPN"     to "protonvpn",
    "Surfshark"     to "surfshark",
    "CyberGhost"    to "cyberghost",
    "Windscribe"    to "windscribe",
    "Mullvad"       to "mullvad",
    "PIA"           to "piactl",
    "OpenVPN"       to "openvpn",
    "WireGuard"     to "wg-quick",
    "Tor"           to "tor",
)
```

**Issue 2 — `.exe` forcing on network rule target process (line ~438): critical**
When a user adds an app-specific keyword/domain rule and types a target process name,
this code forces `.exe`:
```kotlin
if (!it.endsWith(".exe")) "$it.exe" else it
```
On Linux this stores `firefox.exe` in the DB — the rule never fires. Apply the same
fix as AppBlockerScreen (Section 24b):
```kotlin
if (isWindows && !it.endsWith(".exe")) "$it.exe" else it
```

**Issue 3 — Placeholder text (lines ~242 and ~415): low**
Two `TextField` placeholders say `"e.g. myvpn.exe"` and `"e.g. chrome.exe"`. Make
them OS-conditional:
```kotlin
placeholder = { Text(if (isWindows) "e.g. myvpn.exe" else "e.g. nordvpnd", ...) }
// and
placeholder = { Text(if (isWindows) "e.g. chrome.exe" else "e.g. firefox", ...) }
```

**Issue 4 — Windows-specific description texts (lines ~374, ~376): low**
```kotlin
// Before:
"Blocks the domain via the Windows hosts file (requires admin)."
"...cuts network for the matching app via Windows Firewall — without killing the process."
// After:
if (isWindows) "Blocks the domain via the Windows hosts file (requires admin)."
else            "Blocks the domain via /etc/hosts (requires sudo)."
if (isWindows) "...cuts network via Windows Firewall..."
else           "...cuts network via iptables..."
```

---

### 24i — AppStrings.kt — settingsStartWithWindows field

**File:** `src/main/kotlin/com/focusflow/i18n/AppStrings.kt`

The data class field (line ~347) and property accessor (line ~991) use the Windows-specific
name `settingsStartWithWindows`. This is the key used by every locale in `Translations.kt`
and referenced in `SettingsScreen.kt` line 371. All three sites must be updated together.

**Step 1 — AppStrings.kt data class field** (~line 347):
```kotlin
// Before:
val settingsStartWithWindows: String,
// After:
val settingsStartWithSystem: String,
```

**Step 2 — AppStrings.kt property accessor** (~line 991):
```kotlin
// Before:
val settingsStartWithWindows: String get() = appstringsc.settingsStartWithWindows
// After:
val settingsStartWithSystem: String get() = appstringsc.settingsStartWithSystem
```

**Step 3 — Translations.kt** — rename the key in ALL 7 locales (English + 6 others).
Change the string value to be OS-neutral ("Start at Login") since `SettingsScreen.kt`
will provide the OS-conditional label at the call site:
```kotlin
// Before (each locale):
settingsStartWithWindows = "Start with Windows",  // or translated equivalent
// After (each locale):
settingsStartWithSystem = "Start at Login",        // or translated equivalent
```

**Step 4 — SettingsScreen.kt** line 371 — already handled in Section 24c Step 2:
```kotlin
label = if (isWindows) strings.settingsStartWithSystem
        else           strings.settingsStartWithSystem  // same key, same string
```
(The OS-conditional label logic is in `SettingsScreen.kt`; the string key just needs
to be renamed.)

> **Dependency:** Must be done after Section 24c (SettingsScreen). Rename across all
> four files in a single commit to keep the build consistent.

---

### 24j — KeywordBlockerScreen.kt — "on Windows" description text

**File:** `src/main/kotlin/com/focusflow/ui/screens/KeywordBlockerScreen.kt`

Line 125 contains:
```
"Keywords are matched against the foreground window title on Windows. When a match is detected, the browser window is closed."
```

Once Section 4 (keyword blocking via `xdotool getactivewindow getwindowname`) is
implemented, this description is true on Linux too. Make it OS-neutral:

```kotlin
Text(
    if (isWindows)
        "Keywords are matched against the foreground window title on Windows. When a match is detected, the browser window is closed."
    else
        "Keywords are matched against the foreground window title (requires xdotool on X11). When a match is detected, the browser window is closed.",
    ...
)
```

> **Dependency:** Section 4 should be complete first so the Linux description is accurate.
> Safe to do in isolation — the text change has no behavioural effect.

---

### 24k — ShareDialog.kt — "PC (Windows)" download link

**File:** `src/main/kotlin/com/focusflow/ui/components/ShareDialog.kt`

Line 39 contains a hardcoded share text string:
```
- PC (Windows): https://github.com/TITANICBHAI/FocusFlow-PC
```

Make it OS-conditional so Linux users see a relevant label:
```kotlin
val downloadLine = if (isWindows)
    "- PC (Windows): https://github.com/TITANICBHAI/FocusFlow-PC"
else
    "- PC (Linux): https://github.com/TITANICBHAI/FocusFlow-PC"

// Use `downloadLine` in place of the hardcoded string
```

---

## 25. Packaging — .deb Build

This is documented in Section 2 (Build System). Nothing additional is needed. The `linux { }`
block in `build.gradle.kts` plus the 512×512 icon is all that's required.

Build command: `./gradlew packageDeb`
Output: `build/compose/binaries/main/deb/focusflow_*.deb`
Install test: `sudo dpkg -i build/compose/binaries/main/deb/focusflow_*.deb`

---

## 26. Recovery Tool

**`recovery/` subproject confirmed present** (`settings.gradle.kts` line 3: `include("recovery")`).
It contains three files: `Main.kt`, `RecoveryEngine.kt`, `RecoveryLogger.kt`.

`RecoveryEngine.kt` uses JNA (`WinReg`, `User32`, `Advapi32`) to restore the taskbar, clear
registry policies, remove firewall rules, and clean `hosts`. Every Windows-specific call is
already guarded with `if (!isWindows) return StepResult(step, StepStatus.SKIPPED, "Not running on Windows")`.

**Compile safety:** `jna-platform:5.14.0` is a fat JAR that ships all platform classes
(including `WinReg`) even on Linux — the classes exist in the classpath and compile fine;
they just fail at runtime if called without `isWindows` guards. The existing guards make
this safe. ✅

**Linux recovery tool:** Not needed because:
1. The watchdog uses a `systemd` user service — stops automatically if FocusFlow crashes
2. The XDG autostart entry can be deleted from any file manager
3. There is no registry state to clean up

**`recovery/build.gradle.kts`:** Only targets `TargetFormat.Exe, TargetFormat.Msi`.
`./gradlew :recovery:packageDeb` is not a valid task and will not be run. The main
`./gradlew packageDeb` command only builds the main app. ✅

**No action needed.** Leave `recovery/` entirely unchanged.

---

## 27. Testing Checklist  <!-- formerly Section 25 -->

Run these tests on Ubuntu 22.04 LTS (X11 and Wayland) and Debian 12. Mark as done in
the progress tracker when confirmed.

### Display detection
- `isWindows` returns false, `isLinux` returns true on Linux
- `isX11` returns true on X11 session
- `isWayland` returns true on Wayland session
- `hasXdotool` returns true when xdotool is installed

### Foreground detection
- Opening a blocked app triggers `onForegroundChanged()` within 1 second (X11)
- `ProcessMonitor.blockedAttempts` increments when a blocked app is opened

### Process killing
- A blocked process is killed within 1 second of gaining focus
- A process killed by name no longer appears in `pgrep`

### Hosts file blocking
- `blockDomain("reddit.com")` writes entries to `/etc/hosts`
- `curl reddit.com` returns connection refused after blocking
- `unblockDomain("reddit.com")` removes the entries cleanly

### Nuclear mode
- Opening `gnome-terminal` during Nuclear Mode session gets killed
- Opening `gnome-system-monitor` during Nuclear Mode gets killed
- `Xorg`, `gnome-shell`, `systemd` are never killed (safe list works)

### Kiosk mode
- FocusFlow window covers the taskbar during kiosk mode
- Window returns to normal after session ends

### Startup & watchdog
- `~/.config/autostart/focusflow.desktop` is created when startup enabled
- `~/.config/systemd/user/focusflow-watchdog.service` is created when watchdog enabled
- App relaunches automatically after being killed (watchdog test)

### VPN blocking
- `nordvpn` process (if running) is detected by `isVpnProcess()`
- Linux process names (no `.exe`) match correctly
- `addCustomProcess("somevpn")` does not append `.exe` on Linux

### Block overlay
- Block overlay appears on top of blocked app (floating AWT window)
- Overlay dismisses automatically after 4 seconds

### Packaging
- `./gradlew packageDeb` succeeds without errors
- `sudo dpkg -i focusflow_*.deb` installs cleanly
- App launches from the application menu
- App icon appears correctly in the launcher

### Regression — Windows must still work
- `./gradlew packageMsi` and `./gradlew packageExe` still succeed
- Run on Windows and confirm all enforcement still functions
- Confirm no Windows code paths were accidentally modified

---

## 28. Known Limitations

| Feature | Linux Status | Detail |
|---|---|---|
| Foreground detection | ✅ X11 / ⚠️ Wayland | Wayland blocks cross-app window queries by design; XWayland fallback helps |
| Process killing | ✅ Full | Already worked before this port |
| Hosts file blocking | ✅ Full | Needs `sudo` or file permissions |
| Network firewall | ⚠️ Requires passwordless sudo | User must configure sudoers for iptables |
| Nuclear mode | ✅ X11 / ⚠️ Wayland | Foreground detection limits on Wayland |
| Kiosk taskbar | ⚠️ Approximate | Fullscreen cover rather than literal hide; varies by DE |
| Keyboard hook | ⚠️ X11 only (Phase 1) | xbindkeys; Wayland intentionally prevents this |
| Keyboard hook | ❌ Phase 2 not done | evdev exclusive grab — future work |
| Registry lockdown | ❌ No equivalent | Intentional no-op; NuclearMode compensates |
| App icon extraction | ⚠️ PNG only | SVG icons not decoded (Skia limitation) |
| System tray | ⚠️ No GNOME | GNOME removed tray support; KDE/XFCE work |
| Block overlay (Wayland) | ⚠️ May not be always-on-top | Compositor policy controls this |

---

*Document version: 3.0 — June 2026 — added Sections 7b, 7c, 8, 20b/20c, 23, 24d/24e; renumbered Sections 8–26*
