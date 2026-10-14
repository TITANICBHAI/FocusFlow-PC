# FocusFlow PC — Fix Plan

**For:** implementing agent  **From:** planner agent
**Code analysed:** `FocusFlow.zip` → app `2.0.4` (Kotlin 1.9.22, Compose Desktop 1.6.1, JNA 5.14.0, Gradle 8.6, JDK 19 toolchain)

This plan comes from reading the source only. Nothing was compiled or run, and no Windows machine was available. Tags used below:

- **[verified]** — read directly in the code (`file:line`).
- **[hypothesis]** — best explanation that fits the symptoms; the plan adds diagnostics to confirm it.
- **[VERIFY ON WINDOWS]** — Win32/MSIX behaviour that cannot be proven from source. Implement defensively and list it as "unverified" in your final report.

---

## 0. Read first

### 0.1 Scope — four reported problems

| ID | Problem | Section |
|----|---------|---------|
| A | "Relaunch as Admin" works in the EXE build but often fails in the MSIX-installed build | §4 |
| B | The Run-as-Admin button keeps blinking on the Dashboard even though admin was already granted; it goes away after switching screen | §5 |
| C | Focus Launcher: the session PIN typed to end the session is not visible | §2 |
| D | Focus Launcher: an app must be clicked several times to open; when the session ends a long row of never-shown duplicate instances appears; no "opening…" feedback for the user | §3 |

Everything else is out of scope. Do not refactor unrelated code. Do not edit `i18n/Translations.kt`. Do not change enforcement rules (allow-list, kill logic, hard lock, breaks), the DB schema, or how PINs are stored/hashed.

### 0.2 Order of work

1. **C — PIN visibility.** Small and near-certain. Do it first: testing D on a real machine means entering kiosk mode, and with an invisible PIN the tester can get stuck in a session.
2. **D — launcher launch flow.**
3. **A + B — elevation.** Together; they share one new `ElevationManager`.

### 0.3 Environment caveats — check before starting

- `settings.gradle.kts` has `include("recovery")` but the zip has **no `recovery/` directory**. If your build trips on it, do **not** delete the line in your deliverable — report it to the user.
- `gradle/wrapper/gradle-wrapper.jar` is missing (only the `.properties` file is there). `gradle.properties` pins a Nix GraalVM-19 path, so the original dev environment is probably Linux/Replit.
- There is **no `src/test`**. JUnit 5 and coroutines-test are already in `build.gradle.kts`; add tests under `src/test/kotlin/com/focusflow/...`.
- **MSIX packaging is not in the zip** (no `AppxManifest.xml`, signing, or CI; `build.gradle.kts` only builds `Exe` and `Msi`). Every fix below must work **without** manifest changes. Manifest tweaks appear only as user-owned fallbacks (§4.7).
- Win32 code compiles on Linux (JNA is plain Java) but cannot be exercised there. Keep decision logic **pure and unit-testable** and put Win32 calls behind small interfaces.

### 0.4 Definition of done

1. Compiles (`./gradlew compileKotlin` or equivalent), or you state exactly why it could not.
2. The unit tests named per section pass.
3. Every acceptance criterion is either covered by a test or written into your final report as a manual check for the user (use the §7 test matrix).
4. Final report lists: files changed, what you could not verify, deviations from this plan, and the assumptions you made for each §8 decision.

---

## 1. Code map (only what matters here)

| Area | Location |
|------|----------|
| Admin detection #1 (PowerShell `IsInRole`) | `enforcement/WinApiBindings.kt:321` — used by `App.kt:137`, `NuclearMode.kt:245`, `NetworkBlocker.kt:112,203` (**spawns PowerShell on every call**) |
| Admin detection #2 (`net session`) | `ui/components/OsBanner.kt:65` — used by `OsBanner.kt:85`, `OnboardingScreen.kt:932`, `FocusLauncherScreen.kt:96`, `WindowsSetupScreen.kt:30` (called synchronously inside `remember {}` on the UI thread) |
| Relaunch-as-admin, **four copies** | `OsBanner.kt:139-154` (callers: `OnboardingScreen.kt:1026`, `FocusLauncherScreen.kt:254,821`) · `App.kt:551-566` (registry-orphan dialog) · `App.kt:624-639` (floating button) · `WindowsSetupScreen.kt:105-116` |
| Floating admin button | `App.kt:424-428` (placement), `App.kt:87` (`ADMIN_BUTTON_SCREENS`), `App.kt:287-303` (3 s poll), `App.kt:593-690` (composable) |
| Install variant / exe path | `enforcement/InstallVariant.kt`, `enforcement/WindowsStartupManager.kt:61-88` (`resolveExePath()`) |
| Single instance | `services/SingleInstanceGuard.kt`, `Main.kt:90-104, 182-207` |
| Shutdown / quit gate | `Main.kt:157-180` (`doShutdown`), `services/UninstallProtectionService.kt` (`authorizeQuit()`) |
| Launcher UI | `ui/launcher/LauncherWindow.kt`, `ui/launcher/LauncherContent.kt` |
| Launcher service | `services/FocusLauncherService.kt` |
| Launcher enforcement | `enforcement/ProcessMonitor.kt:413-552` (250 ms sweep), `enforcement/WinEventHook.kt:~150-170`, `enforcement/GlobalKeyboardHook.kt` |
| Launcher setup UI / PIN reveal | `ui/screens/FocusLauncherScreen.kt:897-958` |
| Theme | `ui/theme/Theme.kt` |
| Win32 bindings | `enforcement/WinApiBindings.kt` (`User32Extra`, `focusWindowByPid` at `:346-362`) |

---

## 2. Issue C — Session PIN not visible while typing (do first)

**Symptom.** In kiosk mode, *End Session* (also *Take Break* and *Unlock hard lock*) opens a PIN dialog. Typed characters are not visible, not even after pressing the eye toggle.

### 2.1 Root cause

- `LauncherWindowHost()` is called from `Main.kt:318`, directly inside `application { }`. `FocusFlowTheme` wraps only `App()` (`App.kt:304`) and the startup gate (`Main.kt:382`). **The launcher windows therefore run under Material3's default light colour scheme** (`onSurface ≈ #1C1B1F`). [verified: `grep FocusFlowTheme`; no theme in `ui/launcher/*`]
- `SessionPinDialog` (`LauncherContent.kt:411-483`) gives its `OutlinedTextField` only border colours (`:452-455`). Typed text, the masking dots and the revealed characters all use the default near-black `onSurface`, on a hard-coded dark dialog (`containerColor = Color(0xFF1A1828)`, `:425`) → dark on dark. [verified]
- Same defect class, wider impact: the launcher uses the global `OnSurface` / `OnSurface2` (all over `LauncherContent.kt`). Those **flip to dark navy in the app's light theme** (`Theme.kt:37-44`) while the launcher backgrounds are hard-coded dark (`#0C0B14`, `#13121F`, `#1C1A2E`, `#1A1828`). In light mode, titles and app labels in the launcher are unreadable too. [verified from values]
- Contributing factor: the generated session PIN is **20–28 random characters** (`PinPolicy.kt:15-29`), shown at only 16 sp (`FocusLauncherScreen.kt:~923-927`), and compared exactly. Typing that blind is unrealistic.

### 2.2 Tasks

**C-1 — `ui/launcher/LauncherTheme.kt` (new).**
- `object LauncherColors { Background=0xFF0C0B14; Bar=0xFF13121F; Tile=0xFF1C1A2E; Dialog=0xFF1A1828; Text=0xFFE8E6F0; TextDim=0xFFB0AEC8 }` — move the inline hex values out of `LauncherContent.kt` (`:89, :129, :234, :284, :425`).
- `@Composable fun LauncherTheme(content)` = `MaterialTheme(colorScheme = darkColorScheme(primary = Purple80, onPrimary = Color(0xFF1A1830), background = Background, onBackground = Text, surface = Dialog, onSurface = Text, onSurfaceVariant = TextDim, outline = Color(0xFF49475E), error = Error))` wrapping a `Surface(color = Background, contentColor = Text)` so any `Text` without an explicit colour is readable. The launcher is **always dark**, independent of the app theme.

**C-2 — wrap both launcher windows.** In `LauncherWindow.kt` (`:107`) render `LauncherTheme { if (isPrimary) LauncherContent() else SecondaryScreenLock() }`.

**C-3 — stop using theme-dependent globals in the launcher.** In `ui/launcher/*.kt` replace `OnSurface` → `LauncherColors.Text` and `OnSurface2` → `LauncherColors.TextDim`. Do not touch these globals themselves (the main app relies on them).

**C-4 — fix `SessionPinDialog` (`LauncherContent.kt:411-483`).**
- Set explicitly in `OutlinedTextFieldDefaults.colors(...)`: `focusedTextColor`, `unfocusedTextColor`, `errorTextColor` = `LauncherColors.Text`; `cursorColor = Purple80`; placeholder colours = `TextDim`; keep the existing border colours.
- `textStyle`: monospace, 16 sp, slight letter-spacing (the PIN has no ambiguous characters, but monospace makes miscounts visible). `placeholder = "Session PIN"`.
- Autofocus the field when the dialog opens (`FocusRequester` + `LaunchedEffect(Unit)`), submit on Enter (`ImeAction.Done` + `KeyboardActions(onDone = …)`) — same code path as the *Confirm* button, including the `verifying` guard.
- Keep masked-by-default with the eye toggle; with the colours fixed both states are visible.
- Add small supporting text with the current length (`"${pin.length} characters"`) — helps catch typos.

**C-5 — tolerant verification.** Extract a pure function (e.g. `object SessionPinInput { fun normalize(raw: String): String; fun matches(raw: String, storedHash: String?): Boolean }`) that strips all whitespace and `-`, rejects blank, and compares the SHA-256 hex with the stored hash. `FocusLauncherService.verifyPin` (`:127-132`) delegates to it. Still **fail closed** if the stored hash is missing/blank. The generated alphabet (`PinPolicy.kt:18`) contains neither whitespace nor `-`, so normalising cannot create false matches.

**C-6 — PIN reveal dialog (`FocusLauncherScreen.kt:897-958`).** Show the PIN in monospace at ~22–24 sp, **grouped in blocks of 4** separated by spaces (it is 20–28 chars; grouping makes it writable by hand). Keep "This PIN will not be shown again." Do not add a Copy button (see §8).

### 2.3 Acceptance criteria

1. Dark **and** light app theme: start a launcher session, click *End Session* → typed characters appear as masked dots; the eye toggle shows readable characters; Enter submits; a wrong PIN shows "Incorrect PIN" and clears the field.
2. Entering the PIN with or without the group spaces works.
3. In light app theme the launcher's title bar, tile labels, break screen and dialogs are readable.
4. `Take Break` and `Unlock hard lock` dialogs behave identically (same composable).

### 2.4 Tests

- `SessionPinInputTest`: normalisation (spaces, tabs, newlines, `-`), blank rejected, correct/incorrect match, missing/blank stored hash → `false`.

---

## 3. Issue D — Launcher: multiple clicks, hidden duplicate instances, no feedback

**Symptom.** In kiosk mode you click an app tile and nothing visible happens; after clicking again (sometimes several times) it opens. When the session ends, a long row of extra instances appears that were never shown.

### 3.1 Evidence

1. **No in-flight guard, no feedback.** `AppTile` onClick (`LauncherContent.kt:285-323`) starts a new coroutine for every click. It scans **every process on the machine** (`ProcessHandle.allProcesses().toList()` plus `info().command()` per process, `:290-301`) before doing anything — slow, with no UI reaction meanwhile. Until the first launch has produced a visible window, every extra click finds nothing and launches another instance. [verified]
2. **The launcher is demoted before the app window exists.** After `delay(400)` the click handler unconditionally calls `FocusLauncherService.onForegroundChanged(app.processName)` (`:317-318`), which sets `overlayVisible = false` (`FocusLauncherService.kt:139-143`) because the app is in the allowed set. `LauncherWindow.kt:74-105` then moves the launcher from `HWND_TOPMOST` to `HWND_NOTOPMOST` — long before a slow app has created its window. [verified]
3. **The new window ends up behind the launcher, and the taskbar is hidden.** The launcher is a full-screen `alwaysOnTop = true` window (`LauncherWindow.kt:65`); the taskbar is re-hidden every 500 ms (`FocusLauncherService.kt:96-104`). A window created by a process that cannot take foreground is placed *behind* the foreground window. `HWND_NOTOPMOST` puts the launcher at the top of the *normal* z-band, i.e. still above the app. The user sees nothing and has no taskbar button. [hypothesis on z-order mechanics; consistent with every symptom]
4. **Why the second click "works".** On click 2, `focusWindowByPid` (`WinApiBindings.kt:346-362`) finds the now-existing window and calls `SetForegroundWindow`, which raises it above the (now non-topmost) launcher. If the app's cold start takes seconds, clicks 2…N happen before the window exists → N launches → the row of instances revealed at session end. [hypothesis; matches the report exactly]
5. **`focusWindowByPid` returns `false` both for "no window" and for "window found but `SetForegroundWindow` failed"** (`:356`). The caller treats `false` as "launch normally" (`LauncherContent.kt:309`), spawning a *new instance even though one exists*. [verified]
6. **Silent failures.** `catch (_: Exception) {}` (`:319-321`). Packaged/UWP apps whose `exePath` lives in `WindowsApps` cannot be started by path → nothing happens, no message. [verified; UWP behaviour VERIFY ON WINDOWS]
7. **Heavy background load.** `launcherSweep()` (`ProcessMonitor.kt:518-552`) runs every 250 ms (`:413-448`), enumerates all processes and calls `info().command()` twice per process. This can make the UI sluggish (→ more repeat clicks). It can also kill short-lived launcher/stub processes whose name is not in the allow-list. [hypothesis — measure first, do not change enforcement in this task]
8. **Elevation side-effect (low confidence).** `ProcessBuilder(exePath).start()` from an *elevated* FocusFlow starts the app *elevated*. For apps that are already running non-elevated, or that forward to an existing instance over IPC, this can produce a second instance instead of forwarding. [hypothesis — see diagnostics D-8]

### 3.2 Design

**D-1 — `services/LauncherLaunchCoordinator.kt` (new, singleton, own `CoroutineScope(SupervisorJob() + Dispatchers.IO)`).**
Per-app state keyed by `processName.lowercase()`, exposed as `StateFlow<Map<String, LaunchState>>`:

```kotlin
sealed interface LaunchState {
    data object Idle : LaunchState
    data class Launching(val startedAtMs: Long) : LaunchState
    data class Failed(val reason: String, val canRetry: Boolean) : LaunchState
}
```

Put all OS access behind an interface so the state machine is unit-testable:

```kotlin
interface LaunchPlatform {
    fun nowMs(): Long
    fun findInstances(app: FocusLauncherApp): List<RunningInstance>   // ONE process enumeration (see D-5)
    fun findWindows(pids: Set<Long>): List<WindowRef>                 // visible top-level windows
    fun spawn(app: FocusLauncherApp): SpawnResult                     // Started(pid) | Failed(message)
    fun raise(window: WindowRef): Boolean                             // tiered, see D-2
}
```

Rules:
1. `request(app)` runs on the UI thread. If the app's state is `Launching` → **ignore** the click. Otherwise set `Launching` **synchronously, before any IO**, so the tile reacts immediately.
2. Worker: `instances = findInstances(app)`, `windows = findWindows(instances.pids)`.
   - Window(s) exist → `raise` (retry up to 3× at 150 ms). Success → `Idle`. Still failing → `Failed("Couldn't bring <App> to the front", canRetry = true)`. **Never spawn when a window exists.**
   - No window, but an instance younger than 20 s exists → treat as already starting; go to the wait loop.
   - Otherwise → `spawn`. `Failed` → `Failed(message)` and log. `Started(pid)` → wait loop.
3. Wait loop: poll every 150 ms, up to **20 s**, for a window belonging to the spawned PID **or any instance of the same exe** (single-instance apps hand off to another process and exit; re-scan instances about once per second, not every 150 ms). Found → `raise` → `Idle`. Timeout → `Failed("<App> is taking longer than usual", canRetry = true)`; a retry only waits/focuses again.
4. **Spawn budget:** at most one `spawn` per app per 20 s.
5. `cancelAll()` — call from `FocusLauncherService.exit()`, `startBreak()`, and `onKillSwitchActivated()` so nothing is raised after the kiosk is lifted.
6. Only apps in `FocusLauncherService.sessionApps` are launched or raised (enforcement semantics unchanged).

**D-2 — `enforcement/WindowRaiser.kt` (new): bring an app window above the launcher, deterministically.**
Order matters (a normal window can never be placed above a TOPMOST one):
1. Demote **all** launcher windows now: `SetWindowPos(launcher, HWND_NOTOPMOST, 0,0,0,0, SWP_NOMOVE|SWP_NOSIZE|SWP_NOACTIVATE)`. Needs a small `LauncherWindowRegistry` (HWNDs registered from `LauncherWindowForScreen` via `DisposableEffect`, using the same `Native.getComponentPointer(window)` already used at `LauncherWindow.kt:77`) so this does not wait for the Compose effect.
2. `ShowWindow(app, SW_RESTORE)` if iconic; `SetWindowPos(app, HWND_TOP, …, SWP_NOMOVE|SWP_NOSIZE|SWP_SHOWWINDOW)`; `SetForegroundWindow(app)`.
3. If that returns `false` → fallbacks, in order, logging which tier worked: `AllowSetForegroundWindow(ASFW_ANY)` + retry → `AttachThreadInput` trick → synthetic Alt key tap + retry.
4. Verify `GetForegroundWindow() == app` after ~300 ms, otherwise retry (max 3).
5. Set `FocusLauncherService`'s overlay state to match (`onForegroundChanged(app.processName)`), **after** the raise — not on a timer.
- New Win32 bindings are needed (`EnumWindows`, `GetWindowLongW` for `WS_EX_TOOLWINDOW`, `AllowSetForegroundWindow`, `AttachThreadInput`, `keybd_event`, `DwmGetWindowAttribute` with `DWMWA_CLOAKED` to skip cloaked ghost windows). Prefer `com.sun.jna.platform.win32.User32` / `Dwmapi` from jna-platform over extending `User32Extra`; add `HWND_TOP = HWND(Pointer(0))`.
- Window filter: visible, top-level, not cloaked, not `WS_EX_TOOLWINDOW` (fall back to tool windows only if nothing else exists).
- **Related check [VERIFY ON WINDOWS]:** `LauncherWindow.kt:65` hard-codes `alwaysOnTop = true` while the native flag is toggled by `SetWindowPos`. Try `alwaysOnTop = shouldBeTopmost` so AWT's view matches the native state (keep the `SetWindowPos` calls).

**D-3 — remove the timer hack.** Delete `delay(400)` + `FocusLauncherService.onForegroundChanged(app.processName)` from the tile click (`LauncherContent.kt:317-318`). The overlay state is driven by `WindowRaiser` (D-2) and by the existing foreground events.

**D-4 — UI feedback (the "app is opening" request).**
- `AppTile` observes `LauncherLaunchCoordinator.states`. While `Launching`: spinner (`CircularProgressIndicator`, ~20 dp, 2 dp stroke) over/replacing the icon, label becomes "Opening…", tile at ~65 % alpha, click disabled.
- A status line in `LauncherTopBar`: "Opening <App>… this can take a few seconds". Show it only after ~800 ms so instant focus-switches do not flicker.
- `Failed`: red outline on the tile + short text ("Couldn't open — tap to retry" / "Taking longer than usual — tap to check again") for ~6 s, then back to `Idle`. The detailed reason goes to `EnforcementLog` (`tag = "Launcher"`) and a tooltip.
- Strings stay hard-coded English in the launcher files, matching the existing launcher code (it is not localised today). Do not add keys to `AppStrings`/`Translations`.

**D-5 — cheaper process lookup.** A small `ProcessSnapshot` helper: one `ProcessHandle.allProcesses()` pass per call, caching `command()` per `(pid, startInstant)` (PIDs are reused). Used by `LaunchPlatform.findInstances`. Do not alter `launcherSweep()` in this task; just log its duration when above 150 ms (D-8).

**D-6 — spawn robustness.** `ProcessBuilder(exePath).directory(File(exePath).parentFile).redirectOutput(Redirect.DISCARD).redirectError(Redirect.DISCARD).start()`. For `exePath == null` keep the existing `cmd /c start "" <processName>` fallback. Surface exceptions (D-1 rule 2). Never swallow.

**D-7 — packaged apps (limited).** If `exePath` contains `\WindowsApps\`, do not try `ProcessBuilder(path)`; return `Failed("This is a Microsoft Store app and can't be started from here yet")`. AUMID-based launching is a follow-up, not part of this plan.

**D-8 — diagnostics (keep; cheap).** `EnforcementLog` lines, tag `Launcher`: click time, state transitions, instances found, window found after N ms, raise tier that worked, spawn exceptions, whether FocusFlow is elevated, and `launcherSweep()` duration when >150 ms. These let the user report precisely if anything remains.

### 3.3 Acceptance criteria

1. One click opens the app **on top of the launcher** within its normal startup time — no second click (check with a fast app, a slow Electron app, and a browser).
2. During startup the tile shows a spinner and "Opening…"; extra clicks on it are ignored; exactly one process/window is created (for single-instance apps verify in Task Manager).
3. App already running with a window → click raises it above the launcher; **no** new process.
4. Start failure or timeout → visible message on the tile; nothing fails silently.
5. After the session ends there is no pile of hidden windows.
6. Taskbar stays hidden; allow-list, hard lock, breaks, PIN, and kill-switch behaviour are unchanged. Closing an app or switching to a non-allowed process brings the launcher back on top as before.

### 3.4 Tests (pure logic, fake `LaunchPlatform`)

Double click → one spawn · spawn error → `Failed` · window appears after 3 polls → `Idle` · timeout → `Failed(canRetry)` and the retry does not spawn · running with window → no spawn · running without window and older than 20 s → exactly one spawn · spawn budget respected · `cancelAll()` stops pending raises.

---

## 4. Issue A — Relaunch as Admin fails in the MSIX build

**Symptom.** Works in the EXE install; in the MSIX install it fails "sometimes or many times".

### 4.1 What the code does today [verified]

There are four independent copies (see §1). All run `powershell … Start-Process '<exe>' -Verb RunAs` and then kill the JVM — `System.exit(0)` after a fixed 600 ms (`OsBanner.kt:148-153`) or `exitProcess(0)` immediately (`App.kt:562, 635`; `WindowsSetupScreen.kt:~113`). Nothing checks whether the elevated process actually started. The `App.kt` copies use the raw `ProcessHandle.current().info().command()` rather than `resolveExePath()`.

### 4.2 Why EXE works and MSIX does not

- **A1 — wrong launch target under MSIX [hypothesis; VERIFY ON WINDOWS].** The path is inside `C:\Program Files\WindowsApps\<package>\…`. Launching that exe by path through UAC is typically denied, or starts the exe *without package identity*. Packaged desktop apps should be started through the package (AUMID / App Execution Alias). The app's own help text (`WindowsSetupScreen.kt:98-102`) already tells MSIX users to use *Start menu → Run as administrator* instead.
- **A2 — blind exit [verified].** The old process always exits (`OsBanner.kt:148-153`), even if the user cancels UAC or `Start-Process` fails. The doc-comment at `:135-137` claims it stays alive on cancel; the code does not. The app simply disappears.
- **A3 — race with `SingleInstanceGuard` [hypothesis; strongly consistent with "sometimes"].** If the elevated instance starts while the old one still holds the lock, `acquire()` finds the lock held, the old instance answers `SHOW` (`SingleInstanceGuard.kt:104-111`, `requestShow:129-144`) → `AlreadyRunning` → `Main.kt:99` returns → the new process exits; 600 ms later the old one exits too → nothing running. With the EXE the human latency of the UAC prompt hides the race; MSIX activation timing makes it intermittent.
- **A4 — PowerShell dependency.** Cold PowerShell start, no `-NoProfile` in the relaunch call, hidden window; every failure is invisible.
- **A5 — teardown skipped.** `System.exit(0)` bypasses `doShutdown` (`Main.kt:157-180`) — taskbar/registry/Nuclear state can be left behind (the app even has an "orphaned registry key" dialog for this) — and bypasses `UninstallProtectionService.authorizeQuit()` on direct installs.
- **A6 — weak MSIX detection.** `InstallVariant.isMsix` (`InstallVariant.kt:22-42`) relies on env-var names (`PACKAGE_FAMILY_NAME`, `APPX_PACKAGE_*`) that are not documented Windows package-identity signals (unverified), and otherwise on the path containing `\windowsapps\`. The documented API is `GetCurrentPackageFullName`.

### 4.3 Target behaviour

- Click → UI shows "Waiting for the Windows permission prompt…" (button disabled).
- UAC declined → a clear message; the app **stays running**, unchanged.
- UAC accepted → the elevated instance starts and takes over; the old instance exits only after the new one has confirmed it is alive. If the new one never appears, or comes up non-elevated, the old one stays alive and says so.
- Same code path for EXE and MSIX; no PowerShell.

### 4.4 Design

**A-1 — package identity in `InstallVariant`.** Add `packageFullName`, `packageFamilyName`, `aumid` via kernel32 `GetCurrentPackageFullName`, `GetCurrentPackageFamilyName`, `GetCurrentApplicationUserModelId`. Call pattern: pass length 0 → `ERROR_INSUFFICIENT_BUFFER (122)` + required length → allocate → call again; `0` = ok; `15700` (`APPMODEL_ERROR_NO_PACKAGE`) = not packaged. `isMsix = packageFullName != null || <existing path heuristic>` (keep the heuristic as fallback). If `aumid` is unavailable, fall back to `<familyName>!<Application Id>` parsed from `AppxManifest.xml` at `GetCurrentPackagePath` (readable from inside the package).

**A-2 — `enforcement/ElevationManager.kt` (new).**

```kotlin
object ElevationManager {
    val isElevated: Boolean   // computed once (lazy), in-process, never spawns a process
    fun relaunchElevated(): RelaunchResult   // blocking: call from an IO thread
}
sealed interface RelaunchResult {
    data object HandedOff : RelaunchResult
    data object UserDeclined : RelaunchResult
    data class Failed(val reason: String) : RelaunchResult
    data object NotSupported : RelaunchResult      // non-Windows, or no safe strategy → show manual instructions
}
```

- `isElevated`: `OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY)` + `GetTokenInformation(TokenElevation = 20)` → one `DWORD`. Non-Windows → `true`. Query failure → `false` + warning log.
- Strategy selection is a **pure function** (unit-testable): `chooseTarget(variant, aumid, exePath, aliasPath): RelaunchTarget` →
  1. direct install → `DirectExe(exePath, workingDir)` via `ShellExecuteEx(verb = "runas")`;
  2. MSIX with AUMID → `AppsFolder("shell:AppsFolder\\<AUMID>")` via `ShellExecuteEx(verb = "runas")` **[VERIFY ON WINDOWS]**;
  3. MSIX with an `focusflow.exe` execution alias present in `%LOCALAPPDATA%\Microsoft\WindowsApps` → `ExecutionAlias(path)` via `runas` (only if the user adds the alias, §4.7);
  4. otherwise → `Manual` → `NotSupported` (dialog with "Start → right-click FocusFlow → More → Run as administrator").
- `ShellExecuteEx` must run on a dedicated thread that first calls `CoInitializeEx(null, COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE)` and `CoUninitialize()` afterwards — Microsoft requires COM initialisation because the shell may delegate to COM-based handlers, and AppsFolder items do. `FALSE` return + `Native.getLastError() == 1223` (`ERROR_CANCELLED`) → `UserDeclined`.

**A-3 — self-verifying hand-off (replaces the fixed 600 ms exit).** Files under `~/.focusflow/` (data dir is outside AppData, so MSIX file virtualisation does not apply). Small helper `services/RelaunchHandoff.kt`:

```
OLD (non-elevated)                          NEW (elevated, started by Windows)
1. refuse if a session is active (§8-1)
2. write relaunch.marker {oldPid, nonce, ts, userName}
3. ShellExecuteEx(runas) ──────────────►   starts; SingleInstanceGuard.acquire() finds the lock held
   declined → delete marker, stay alive    AND fresh marker (<60 s) AND ElevationManager.isElevated
   error    → delete marker, show reason       AND same userName:
4. wait ≤10 s for relaunch.hello  ◄─────── 4. write relaunch.hello {newPid, nonce}
5. hello seen → graceful shutdown                (do NOT send SHOW, do NOT exit)
   (skip authorizeQuit), release the lock,   5. poll acquire() every 200 ms, ≤25 s
   exit                                      6. lock acquired → delete marker + hello, continue startup
   no hello in 10 s → delete marker,
   STAY ALIVE, show "The elevated instance
   did not start" (+ manual instructions)
```

- If the new instance starts **non-elevated** (the `runas` verb was ignored), or `userName` differs (standard user entering another admin's credentials → different `user.home`), it must behave as a normal second launch (send `SHOW`, exit). The old instance then times out at step 4, stays alive, and shows the manual-instructions message — an honest degrade instead of a vanished app.
- `Main.kt:99` currently returns on `AlreadyRunning`; make that conditional on "no fresh marker + elevated". In the retry loop (`Main.kt:187-207`) use a 300 ms delay instead of 10 s while a fresh marker exists. While waiting, show a "Switching to administrator mode…" message instead of the misleading "holder did not respond" text (`Main.kt:354-355, 369-370`). Skip `requestShow` in `SingleInstanceGuard.acquire` when the marker is fresh (add a parameter) to avoid flashing the old window.
- The marker/hello files carry no authority: a rogue file can only make a fresh instance wait ≤25 s. There is **no** remote "quit" command on the socket.

**A-4 — graceful shutdown for relaunch.** Add `object AppLifecycle { @Volatile var shutdown: ((skipQuitAuthorization: Boolean) -> Unit)? }`, assigned inside `FocusFlowDesktop`. Refactor `doShutdown` (`Main.kt:157-180`) to take the parameter and skip only `UninstallProtectionService.authorizeQuit()` when true. Relaunch calls it after the hand-off succeeds (A-3 step 5). Never `System.exit` directly.

**A-5 — replace all four copies** with one call site helper (e.g. `requestElevatedRestart(scope, onState)`) built on `ElevationManager.relaunchElevated()` + A-3/A-4. Callers: `OsBanner.kt` (`relaunchAsAdmin` becomes a thin wrapper or is removed), `App.kt` button and orphan dialog, `WindowsSetupScreen.kt`, `FocusLauncherScreen.kt:254,821`, `OnboardingScreen.kt:1026`. UI states: pending ("Waiting for the Windows permission prompt…"), declined, failed, not-supported (manual instructions) — use the existing dialog/notification patterns (`AlertDialog`, `SystemTrayManager.showNotification`).

**A-6 — one admin check.** Delete both `isRunningAsAdmin()` implementations (`WinApiBindings.kt:321`, `OsBanner.kt:65`) and the unused `AdminBanner` (`OsBanner.kt:78-129`). Update callers: `App.kt:137`, `FocusLauncherScreen.kt:96`, `WindowsSetupScreen.kt:30`, `OnboardingScreen.kt:932`, `NuclearMode.kt:245`, `NetworkBlocker.kt:112,203` → `ElevationManager.isElevated`. This also removes a PowerShell spawn from every `NetworkBlocker`/`NuclearMode` call.

**A-7 — diagnostics.** At startup one `EnforcementLog.info("Elevation", …)` line: `variant=exe|msix pfn=… aumid=… tokenElevated=… exe=… pid=… user=…`. On relaunch: chosen strategy, `ShellExecuteEx` result + Win32 error code, hand-off outcome and timings. This is what the user sends back if anything still fails.

### 4.5 Acceptance criteria

1. **EXE** — standard click path: UAC accepted → elevated instance appears, old one gone, no duplicate windows, data intact (same `~/.focusflow`). UAC declined → app keeps running, message shown.
2. **MSIX** — same two checks. If strategy 2 (AppsFolder + `runas`) does not elevate on the tester's Windows build: the app stays alive, shows the manual-instructions message, and the log explains why — and the user is told to send the manifest (§8-2).
3. UAC set to "never notify" / built-in admin (instant elevation): no vanished app (this is the A3 race).
4. No PowerShell process is spawned for the admin check or the relaunch.
5. Relaunch is unavailable (button disabled with tooltip) while a focus/launcher/Nuclear session is active (default of §8-1).

### 4.6 Tests

- `RelaunchTargetTest`: `chooseTarget` for exe / msix+aumid / msix+alias / msix-without-anything / non-Windows.
- `RelaunchHandoffTest` (temp dir, fake clock): marker freshness; hello written only when elevated and same user; old instance stays alive on timeout; marker cleaned on decline/error.
- `InstallVariant` parsing helpers (error-code handling, buffer-size dance) behind an interface with a fake kernel.

### 4.7 Manifest fallbacks (user-owned; the manifest is not in the zip)

- App Execution Alias, only if strategy 2 fails on the user's Windows build:
  ```xml
  <uap3:Extension Category="windows.appExecutionAlias" Executable="app\FocusFlow.exe" EntryPoint="Windows.FullTrustApplication">
    <uap3:AppExecutionAlias><desktop:ExecutionAlias Alias="focusflow.exe" /></uap3:AppExecutionAlias>
  </uap3:Extension>
  ```
  (needs the `uap3` and `desktop` namespaces declared; adjust `Executable` to the real in-package path.)
- `rescap:Capability Name="allowElevation"` — add **only** if testing shows elevation is refused; it is a restricted capability and may need Store justification.
- Confirm the package declares `runFullTrust`. `build.gradle.kts` comments call MSIX an "AppContainer"; a true AppContainer could not run the enforcement code at all.

---

## 5. Issue B — Run-as-Admin button blinks on the Dashboard when already admin

**Symptom.** On the Dashboard the "Run as Admin" button keeps pulsing although admin was granted; after switching to another screen it is gone.

### 5.1 Evidence

- `App.kt:121-122`: `isAdmin` **defaults to `false`**; it is set once, in an IO coroutine, by `isRunningAsAdmin()` (`:136-137`), which spawns PowerShell. The button is composed whenever `!isAdmin && currentScreen in ADMIN_BUTTON_SCREENS` (`:424`). [verified]
- `RestartAsAdminButton` (`App.kt:593-690`) creates `rememberInfiniteTransition` **unconditionally** (`:613`, comment: "always composed, applied conditionally"). When `urgent` (`activeAdminFeatures.isNotEmpty()`, `:598`) it applies a 0.65↔1.0 alpha pulse every 700 ms. `activeAdminFeatures` is polled every 3 s while `!isAdmin` (`:287-303`). The app renders with `-Dskiko.renderApi=SOFTWARE` (`build.gradle.kts`), so a perpetual animation forces continuous full-window repaints. [verified]
- `ADMIN_BUTTON_SCREENS` (`App.kt:87`) includes Dashboard but not Tasks, Focus, Stats, … — so "disappears on another screen" is consistent with `isAdmin` being a **false negative** while elevated, with the button simply not rendered elsewhere. [hypothesis]
- Admin state is derived from a spawned process (slow; can return anything but exactly `True`; `net session` additionally needs the Server service) and exists in two inconsistent implementations (§1). [verified]

### 5.2 Root cause

Elevation state is (1) asynchronous with a "not admin" default, (2) derived from spawned processes that can be slow or wrong, (3) duplicated — and the UI answers with a perpetual animation.

### 5.3 Tasks

- **B-1** `App()` uses `val isAdmin = remember { ElevationManager.isElevated }` (A-2). Remove the IO elevation check from the startup effect (`App.kt:136-137`) — no initial "not admin" flash, and a faster startup.
- **B-2** Button visibility unchanged (`!isAdmin && currentScreen in ADMIN_BUTTON_SCREENS`) but **disabled with a tooltip** while a session blocks relaunch (§8-1), and showing the pending state from A-5 after a click.
- **B-3** Remove the always-on transition. Compose the pulse only in the urgent state (split an `UrgentPulse` composable), **bounded**: ~3 pulses when `urgent` becomes true (`Animatable` in `LaunchedEffect(urgent)`), then static. No frame requests while idle.
- **B-4** Keep the `activeAdminFeatures` poll only while `!isAdmin`; raise the interval from 3 s to 5 s.
- **B-5** The elevation diagnostics line (A-7) covers this too: if blinking persists, the log shows whether detection lied.
- Leave the Dashboard session-banner dot (`DashboardScreen.kt:198-208`) as is; it only runs during an active session.

### 5.4 Acceptance criteria

1. Elevated process (right-click → Run as administrator, and after a successful relaunch): no admin button on any screen; CPU on the Dashboard is idle.
2. Non-elevated: button appears immediately (no flash), pulses at most ~3 times when admin-requiring features are active, then stays steady.
3. Settings, Windows Setup, Focus Launcher setup and Onboarding all agree on the elevation state.

### 5.5 Tests

Reuse A-2 tests. If cheap, a small test that the pulse composable is not composed when `urgent == false`; otherwise state it as a manual check.

---

## 6. Cross-cutting rules

- **Logging:** use `EnforcementLog.info/warn(tag, msg)` (`enforcement/EnforcementLog.kt`, writes under `~/.focusflow`) with tags `Elevation` and `Launcher`.
- **Strings:** launcher and admin-button code are hard-coded English today. Keep that. Do not touch `AppStrings.kt` / `Translations.kt`.
- **Threading:** no process spawning, `Thread.sleep`, or blocking Win32 calls on the UI thread. All new Win32 helpers are called from IO threads.
- **Do not change:** enforcement decisions in `ProcessMonitor`, `NuclearMode`, `GlobalKeyboardHook`; allow-list semantics; PIN hashing; DB schema.
- **New files (suggested):** `ui/launcher/LauncherTheme.kt`, `services/LauncherLaunchCoordinator.kt`, `enforcement/WindowRaiser.kt`, `enforcement/ElevationManager.kt`, `services/RelaunchHandoff.kt`, `services/SessionPinInput.kt` (or inside `FocusLauncherService`), `ProcessSnapshot` helper. Tests under `src/test/kotlin/com/focusflow/`.

---

## 7. Manual test matrix (include in your final report for the user)

| # | Build | Scenario | Expected |
|---|-------|----------|----------|
| 1 | EXE | Click Run as Admin → accept UAC | Elevated instance only; button gone; data intact |
| 2 | EXE | Click Run as Admin → cancel UAC | App stays running; message shown |
| 3 | MSIX | Click Run as Admin → accept UAC | Elevated instance only, **or** (if Windows ignores `runas` on AppsFolder) app stays alive + manual-instructions message |
| 4 | MSIX | Click Run as Admin → cancel UAC | App stays running; message shown |
| 5 | both | UAC "never notify" / built-in admin | No vanished app |
| 6 | both | Dashboard, elevated | No blinking, no button; idle CPU |
| 7 | both | Dashboard, non-elevated, a feature needing admin active | Button pulses ~3×, then steady |
| 8 | both | Launcher: End Session in dark **and** light theme | PIN characters visible; Enter submits; wrong PIN rejected |
| 9 | both | Launcher: click a slow app once | Spinner + "Opening…"; app appears on top; one instance |
| 10 | both | Launcher: click an already-running app | Raised on top; no new process |
| 11 | both | Launcher: click an app that fails to start | Visible error on the tile |
| 12 | both | End session after #9–#11 | No hidden pile of windows |
| 13 | both | Launcher #9 with FocusFlow elevated vs not elevated | Same result (if not, see D evidence 8) |

---

## 8. Decisions for the user (defaults chosen so you are not blocked)

1. **Relaunch during an active session.** Default: **blocked** (disabled button + tooltip "End your current session first") whenever a focus session, launcher session or Nuclear Mode is active. Today it hard-exits and bypasses the quit PIN.
2. **MSIX elevation strategy 2** (AppsFolder + `runas`) is unverified. If it fails on the user's machine, the user must provide `AppxManifest.xml` and the MSIX build/signing script so an execution alias can be added (§4.7).
3. **PIN reveal "Copy" button.** Default: **no** (the PIN is meant to add friction; grouping + larger monospace is enough).
4. **Launch timing.** Default: wait up to **20 s** for a window; at most **one spawn per app per 20 s**.
5. **Store/UWP apps in the launcher.** Default: clear error message only; AUMID launching is a follow-up.
6. **Launching apps de-elevated** when FocusFlow runs as admin. Default: no change; investigate only if D persists and the diagnostics point at elevation.

## 9. Files the user still needs to supply (not in the zip)

`AppxManifest.xml` and the MSIX packaging/signing script or CI workflow · the `recovery/` Gradle module referenced by `settings.gradle.kts` · `gradle/wrapper/gradle-wrapper.jar`.

## 10. Noticed, deliberately out of scope

- `launcherSweep()` is heavy (250 ms interval, whole process list, `info().command()` twice per process). Measure with the D-8 log before touching enforcement code.
- Under MSIX, `WindowsStartupManager.enable()` (HKCU `Run` entry) and `WatchdogInstaller` (scheduled task pointing at the `WindowsApps` exe path) will probably not work or persist; MSIX normally uses the `windows.startupTask` manifest extension. Verify separately.
- A standard (non-admin) user who enters another admin's credentials at UAC gets an instance running as *that* user with a different `user.home`; A-3 only refuses the takeover, it does not solve this.
- `OsBanner.kt` `AdminBanner` is dead code (removed in A-6).

## Appendix — JNA sketches (verify signatures against jna-platform 5.14.0)

```kotlin
// Package identity (kernel32, default options; char[] maps to wchar_t*)
private interface KernelPkg : StdCallLibrary {
    fun GetCurrentPackageFullName(len: IntByReference, buf: CharArray?): Int
    fun GetCurrentPackageFamilyName(len: IntByReference, buf: CharArray?): Int
    fun GetCurrentApplicationUserModelId(len: IntByReference, buf: CharArray?): Int
    companion object { val I: KernelPkg = Native.load("kernel32", KernelPkg::class.java) }
}
// len = IntByReference(0); rc = fn(len, null) → 122 (need len.value chars) ; buf = CharArray(len.value); rc = fn(len, buf) → 0 ok; 15700 = not packaged.

// Elevation (declare a minimal binding if WinNT.TOKEN_ELEVATION / the overload is missing)
private interface AdvapiMin : StdCallLibrary {
    fun OpenProcessToken(process: Pointer, access: Int, token: PointerByReference): Boolean
    fun GetTokenInformation(token: Pointer, infoClass: Int, info: Pointer?, len: Int, ret: IntByReference): Boolean
    companion object { val I: AdvapiMin = Native.load("advapi32", AdvapiMin::class.java, W32APIOptions.DEFAULT_OPTIONS) }
}
// TOKEN_QUERY = 0x0008, TokenElevation = 20, result = one DWORD (Memory(4).getInt(0) != 0). CloseHandle the token.

// Relaunch (dedicated thread)
//   Ole32.INSTANCE.CoInitializeEx(null, COINIT_APARTMENTTHREADED or COINIT_DISABLE_OLE1DDE)
//   val info = ShellAPI.SHELLEXECUTEINFO().apply { cbSize = size(); lpVerb = "runas"; lpFile = target; lpDirectory = dir; nShow = WinUser.SW_SHOWNORMAL }
//   val ok = Shell32.INSTANCE.ShellExecuteEx(info); val err = if (ok) 0 else Native.getLastError()   // 1223 = user cancelled
//   Ole32.INSTANCE.CoUninitialize()
// target = exePath (direct) | "shell:AppsFolder\\<AUMID>" (MSIX) | alias path
```
