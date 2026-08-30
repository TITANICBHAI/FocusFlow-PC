# Focus Launcher Agent Task Plan — Implementation Comparison

> Compared against the current workspace on 2026-08-30.
>
> The imported task brief is preserved unchanged in
> [AGENT_TASKS_1788110587234.md](AGENT_TASKS_1788110587234.md). This file is the
> live tracking and gap-analysis companion for that brief.

## Overall status

**Implementation complete; Windows runtime verification pending.** The
application now uses dedicated undecorated launcher windows per monitor, with
foreground-aware Z-order, configurable breaks, a taskbar guard, and a
one-time session PIN. Kotlin compilation and the Replit desktop workflow pass.

## Task-by-task comparison

| Task | Status | Current implementation | Remaining work |
|---|---|---|---|
| T-01 — `SetWindowPos` binding | ✅ Complete | `User32Extra` now exposes `SetWindowPos`, topmost handles, and `SWP_*` flags. | Verified by successful Kotlin compilation. |
| T-02 — launcher foreground callback | ✅ Complete | `ProcessMonitor` now exposes a volatile launcher callback and invokes it before enforcement cooldown filtering. | Verified by successful Kotlin compilation. |
| T-03 — `FocusLauncherService` overhaul | ✅ Complete | The service now supports taskbar guarding, foreground-aware visibility, configurable break counts/durations, and one-time session PINs. | Verified by successful Kotlin compilation. |
| T-04 — `LauncherWindow.kt` | ✅ Complete | Added the multi-monitor undecorated window host with Win32 Z-order handling. | Verified by successful Kotlin compilation. |
| T-05 — `LauncherContent.kt` | ✅ Complete | Added launcher UI, app tiles, hard-lock controls, break screen, secondary-screen lock, and PIN dialog. | Verified by successful Kotlin compilation. |
| T-06 — `Main.kt` integration | ✅ Complete | `Main.kt` renders `LauncherWindowHost()` and hides the normal main window while the launcher is active. | Verified by successful Kotlin compilation. |
| T-07 — setup screen | ✅ Complete | The picker loads curated installed apps, restores selection, configures breaks, and shows the generated session PIN before entering. | Verified by successful Kotlin compilation. |
| T-08 — remove legacy overlay | ✅ Complete | Removed the legacy overlay call sites and deleted `FocusLauncherOverlay.kt`. | Verified by successful Kotlin compilation and source reference check. |

## Evidence from the current code

- `User32Extra` now includes `SetWindowPos`, topmost handles, and `SWP_*` flags.
- `ProcessMonitor` now emits launcher foreground callbacks before cooldown filtering.
- `FocusLauncherService.enter()` now accepts break count and duration and owns the
  launcher visibility, taskbar guard, and session-PIN state.
- `FocusLauncherScreen.kt` now loads curated installed apps and stages a one-time
  session PIN before starting.
- `Main.kt` now hides the normal window and hosts dedicated launcher windows.
- `App.kt` no longer renders the legacy overlay.

## Verification checklist

These checks are intentionally not marked complete by static comparison alone.
The Windows-only behavior must be exercised after implementation on an elevated
Windows build.

- [x] App starts normally, no crash (successful Kotlin build and restarted desktop workflow)
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

> The unchecked launcher behavior items require an elevated Windows runtime and
> cannot be exercised in the Linux Replit environment.

## Scope exclusions from the brief

The plan explicitly leaves `NuclearMode`, `RegistryLockdown`,
`GlobalKeyboardHook`, the existing allowlist kill loop, real app icons, and
`InstalledAppsScanner.searchApps()` unchanged.