# Focus Launcher Agent Task Plan — Implementation Comparison

> Compared against the current workspace on 2026-08-30.
>
> The imported task brief is preserved unchanged in
> [AGENT_TASKS_1788110587234.md](AGENT_TASKS_1788110587234.md). This file is the
> live tracking and gap-analysis companion for that brief.

## Overall status

**Not started in the current implementation.** The application still uses the
legacy single-window `FocusLauncherOverlay` architecture. The task brief
describes a migration to dedicated undecorated launcher windows per monitor,
with foreground-aware Z-order, configurable breaks, and a session PIN.

## Task-by-task comparison

| Task | Status | Current implementation | Remaining work |
|---|---|---|---|
| T-01 — `SetWindowPos` binding | ⬜ Missing | `User32Extra` exposes `ShowWindow`, but no `SetWindowPos`, topmost handles, or `SWP_*` flags. | Add the JNA declaration and constants. |
| T-02 — launcher foreground callback | ⬜ Missing | `ProcessMonitor.onForegroundChanged()` performs enforcement only; there is no launcher callback field or invocation. | Add the volatile callback and notify it for launcher foreground changes. |
| T-03 — `FocusLauncherService` overhaul | ⬜ Missing | The service uses `BREAK_USED_KEY`, a fixed five-minute `BREAK_SECONDS`, the two-argument `enter()`, and no overlay/break-count/session-PIN flows or taskbar guard. | Apply the state, PIN, configurable-break, callback, and taskbar-guard changes from the brief. |
| T-04 — `LauncherWindow.kt` | ⬜ Missing | No `src/main/kotlin/com/focusflow/ui/launcher/` package exists. | Add the multi-monitor window host and Win32 Z-order handling. |
| T-05 — `LauncherContent.kt` | ⬜ Missing | No dedicated launcher content file exists; content is still in `FocusLauncherOverlay.kt`. | Add the launcher UI, app tiles, hard-lock controls, break screen, secondary-screen lock, and PIN dialog. |
| T-06 — `Main.kt` integration | ⬜ Old architecture | `Main.kt` still derives `isKioskMode`, toggles the main window between floating/fullscreen, and sets `alwaysOnTop = isKioskMode`. | Render `LauncherWindowHost()` and hide the normal main window while the launcher is active. |
| T-07 — setup screen | 🟨 Partial | The picker loads apps from block rules and daily allowances, and the confirm dialog calls `enter()` directly. | Load all curated apps, add break count/duration chips, and show the generated session PIN before entering. |
| T-08 — remove legacy overlay | ⬜ Not done | `FocusLauncherOverlay.kt` exists and `App.kt` imports/calls both `FocusLauncherOverlay` and `FocusLauncherBreakBanner`. | Remove the call sites, then delete the legacy overlay file. |

## Evidence from the current code

- `WinApiBindings.kt` ends its `User32Extra` interface at `ShowWindow`; no
  `SetWindowPos` declaration is present.
- `ProcessMonitor.kt` has `launcherAllowedProcesses`, but no
  `onLauncherForegroundChanged` callback.
- `FocusLauncherService.enter()` currently accepts only
  `(apps, durationMinutes)`.
- `FocusLauncherScreen.kt` currently constructs the initial list from
  `Database.getBlockRules()` and `Database.getDailyAllowances()`.
- `Main.kt` currently uses the old `LaunchedEffect(isKioskMode, launcherBreak)`
  and fullscreens the primary application window.
- `App.kt` still renders the legacy overlay inside the root content box.

## Verification checklist

These checks are intentionally not marked complete by static comparison alone.
The Windows-only behavior must be exercised after implementation on an elevated
Windows build.

- [ ] App starts normally, no crash
- [x] Normal main window is configured at 1100×720
- [ ] Start a launcher session → main window hides to tray
- [ ] Launcher window appears fullscreen, undecorated, and without close controls
- [ ] Taskbar remains hidden during launcher mode
- [ ] Clicking an app tile lowers the launcher and launches the selected app
- [ ] Closing/minimizing the launched app raises the launcher again
- [ ] A non-allowed foreground app is killed
- [ ] Hard Lock requires the session PIN to end/unlock
- [ ] Wrong session PIN leaves the session locked
- [ ] Break requires the session PIN and shows a configurable countdown
- [ ] Break ends automatically and enforcement re-engages
- [ ] Secondary monitors show the lock screen
- [ ] A crash during a session restores the taskbar on next launch

## Scope exclusions from the brief

The plan explicitly leaves `NuclearMode`, `RegistryLockdown`,
`GlobalKeyboardHook`, the existing allowlist kill loop, real app icons, and
`InstalledAppsScanner.searchApps()` unchanged.