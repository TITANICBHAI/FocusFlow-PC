# FocusFlow PC — Build Tracker

> Last updated: May 2026 · Version 1.0.6

---

## Project Goal

A real-enforcement productivity & focus app for Windows, built with Kotlin + Compose Multiplatform Desktop. Real Win32 enforcement (process monitor + WinEventHook + firewall rules + Focus Launcher kiosk mode) — not just stored settings.

---

## Architecture

```
focusflow-pc/
├── build.gradle.kts                   Gradle build (Compose Desktop + JNA + SQLite)
├── settings.gradle.kts
├── gradle/wrapper/                    Gradle wrapper (8.14.2)
├── src/main/kotlin/com/focusflow/
│   ├── Main.kt                        Entry point — window, tray, crash handler, shutdown hook
│   ├── App.kt                         Root composable, nav state, overlay wiring, onboarding
│   ├── ui/
│   │   ├── theme/Theme.kt             Material 3 dark colour scheme
│   │   ├── screens/
│   │   │   ├── DashboardScreen.kt     Today overview, streak, quick start
│   │   │   ├── TasksScreen.kt         Full task CRUD, recurring tasks
│   │   │   ├── FocusScreen.kt         Active session timer + controls
│   │   │   ├── FocusLauncherScreen.kt CBT kiosk launcher configuration + launch
│   │   │   ├── AppBlockerScreen.kt    App block rules management
│   │   │   ├── StatsScreen.kt         Session history, temptation log charts
│   │   │   ├── SettingsScreen.kt      Blocking config, PIN, enforcement
│   │   │   ├── HabitsScreen.kt        Habit tracker + streak grid
│   │   │   ├── ReportsScreen.kt       Weekly reports + focus insights
│   │   │   ├── DailyNotesScreen.kt    Per-day notes journal
│   │   │   ├── ProfileScreen.kt       User profile + backup/export
│   │   │   ├── ActiveScreen.kt        Live block status dashboard
│   │   │   ├── BlockDefenseScreen.kt  Enforcement layer configuration
│   │   │   ├── KeywordBlockerScreen.kt Window-title keyword blocking
│   │   │   ├── WindowsSetupScreen.kt  Admin/permissions setup wizard
│   │   │   └── PrivacyPermissionsScreen.kt
│   │   └── components/
│   │       ├── SideNav.kt             Left sidebar navigation
│   │       ├── TaskCard.kt            Priority-coloured card
│   │       ├── BlockOverlay.kt        Full-screen animated block overlay
│   │       ├── FocusLauncherOverlay.kt Full-screen kiosk overlay composable
│   │       ├── AppLogo.kt
│   │       ├── EmptyStateCard.kt
│   │       ├── ScrollUtils.kt
│   │       ├── OsBanner.kt            Windows-only enforcement warning for non-Windows
│   │       └── OnboardingScreen.kt    First-run onboarding flow
│   ├── data/
│   │   ├── Database.kt                SQLite via sqlite-jdbc (isReady guard on all ops)
│   │   └── models/Models.kt           Data classes
│   ├── enforcement/
│   │   ├── WinApiBindings.kt          JNA: GetForegroundWindow, TerminateProcess, taskbar ops
│   │   ├── ProcessMonitor.kt          Dual-mode: WinEventHook + 500ms polling; UWP resolution
│   │   ├── AppBlocker.kt              Kill + overlay bridge
│   │   ├── NetworkBlocker.kt          netsh advfirewall + PowerShell New-NetFirewallRule
│   │   ├── NuclearMode.kt             Maximum blocking (30+ escape routes)
│   │   ├── WinEventHook.kt            Instant foreground detection (WINEVENT_OUTOFCONTEXT)
│   │   ├── InstalledAppsScanner.kt    Curated + running process scanner
│   │   └── WindowsStartupManager.kt   HKCU Run key auto-start
│   └── services/
│       ├── FocusSessionService.kt
│       ├── FocusLauncherService.kt    Kiosk session state + taskbar show/hide + safety
│       ├── TemptationLogger.kt
│       ├── SessionPin.kt
│       ├── SoundAversion.kt
│       ├── SystemTrayManager.kt
│       ├── NotificationService.kt
│       ├── TaskAlarmService.kt
│       ├── RecurringTaskService.kt
│       ├── BlockScheduleService.kt
│       ├── StandaloneBlockService.kt
│       ├── DailyAllowanceTracker.kt
│       ├── WeeklyReportService.kt
│       ├── BreakEnforcer.kt
│       ├── FocusInsightsService.kt
│       ├── BackupService.kt
│       ├── AutoBackupService.kt
│       ├── HostsBlocker.kt
│       └── PrivacyPolicyService.kt
├── docs/
│   ├── index.html                     Public website (GitHub Pages)
│   ├── privacy-policy/index.html
│   └── terms-of-service/index.html
├── .github/workflows/
│   ├── build-windows.yml              EXE + MSI + MSIX + GitHub Release
│   ├── build-windows-arm64.yml        ARM64 build
│   └── deploy-pages.yml               GitHub Pages deploy
└── BUILD_TRACKER.md
```

---

## Technology Stack

| Layer | Choice | Version |
|-------|--------|---------|
| Language | Kotlin/JVM | 1.9.22 |
| UI | Compose Multiplatform Desktop | 1.6.1 |
| Build | Gradle (Kotlin DSL) | 8.14.2 |
| Native interop | JNA + jna-platform | 5.14.0 |
| Database | org.xerial:sqlite-jdbc | 3.45.1.0 |
| Async | kotlinx.coroutines-swing | 1.7.3 |
| Packaging | jpackage (Compose Desktop plugin) | bundled JRE |
| CI/CD | GitHub Actions `windows-latest` | build-windows.yml |
| Java runtime | GraalVM CE 19 (Replit dev) / Temurin 19 (CI) | 19 |

---

## Enforcement Stack

| Capability | Mechanism | Status |
|-----------|-----------|--------|
| Instant foreground detection | `SetWinEventHook` (WINEVENT_OUTOFCONTEXT) via JNA | ✅ |
| Polling fallback | `GetForegroundWindow()` every 500ms | ✅ |
| UWP app resolution | Scans processes when ApplicationFrameHost.exe is foreground | ✅ |
| Kill blocked app | `ProcessHandle.destroyForcibly()` (JVM 9+) | ✅ |
| Kill fallback | `taskkill /F /IM processname.exe` via `ProcessBuilder` | ✅ |
| Network block | `New-NetFirewallRule` via PowerShell (admin required) | ✅ |
| Block overlay | Compose always-on-top animated overlay | ✅ |
| Nuclear Mode | Kills 30+ escape-route processes | ✅ |
| Focus Launcher kiosk | Taskbar hide/show + NuclearMode + PIN-gated breaks | ✅ |
| Safe input whitelist | 55+ keyboard/touchpad/mouse/audio/UAC/accessibility processes | ✅ |
| Auto-start | Windows Registry `HKCU\Run` | ✅ |
| Website blocking | Hosts-file domain blocking | ✅ |
| Keyword blocking | Window-title substring match | ✅ |

---

## Data Safety — 4 Independent Recovery Layers

| Layer | Trigger | What it does |
|-------|---------|-------------|
| `loadFromDb()` unconditional restore | Every app startup | Calls `ShowWindow(taskbar, SW_SHOW)` regardless of DB state |
| Global crash handler | Any uncaught exception on any thread | Calls `emergencyRestoreWindows()` |
| JVM shutdown hook | Normal exit, OOM, SIGTERM, process kill | Calls `emergencyRestoreWindows()` |
| `isReady` DB guard | `Database.getSetting()` / `setSetting()` | Returns null/no-op if DB never opened |

---

## Progress Tracker

### Foundation
- [x] Architecture planned and documented
- [x] Gradle build files (build.gradle.kts, settings.gradle.kts, wrapper)
- [x] GitHub Actions workflow (EXE + MSI + MSIX + GitHub Release)
- [x] Data layer (Database.kt, Models.kt — SQLite)
- [x] DB safety: `isReady` guard on all `getSetting`/`setSetting` calls

### Enforcement Engine
- [x] WinApiBindings.kt — JNA Win32 bindings
- [x] ProcessMonitor.kt — dual-mode: WinEventHook + 500ms polling
- [x] ProcessMonitor.kt — UWP/ApplicationFrameHost resolution
- [x] ProcessMonitor.kt — system frame process ignore list
- [x] AppBlocker.kt — kill + overlay bridge
- [x] NetworkBlocker.kt — netsh firewall rules
- [x] NuclearMode.kt — 30+ escape-route processes blocked (WSL, WMI, script engines, package managers, perfmon, resource monitor)
- [x] WinEventHook.kt — instant WINEVENT_OUTOFCONTEXT detection
- [x] InstalledAppsScanner.kt
- [x] WindowsStartupManager.kt

### Services
- [x] FocusSessionService.kt
- [x] FocusLauncherService.kt — kiosk session state, taskbar control, crash safety, PIN breaks, hard lock, 55+ safe processes
- [x] TemptationLogger.kt
- [x] SessionPin.kt (SHA-256)
- [x] SoundAversion.kt
- [x] SystemTrayManager.kt
- [x] NotificationService.kt
- [x] TaskAlarmService.kt
- [x] RecurringTaskService.kt
- [x] BlockScheduleService.kt
- [x] StandaloneBlockService.kt
- [x] DailyAllowanceTracker.kt
- [x] WeeklyReportService.kt
- [x] BreakEnforcer.kt
- [x] FocusInsightsService.kt
- [x] BackupService.kt + AutoBackupService.kt
- [x] HostsBlocker.kt
- [x] PrivacyPolicyService.kt

### UI Screens (all functional)
- [x] DashboardScreen
- [x] TasksScreen
- [x] FocusScreen
- [x] FocusLauncherScreen (CBT kiosk launcher)
- [x] AppBlockerScreen
- [x] StatsScreen
- [x] SettingsScreen
- [x] HabitsScreen
- [x] ReportsScreen
- [x] DailyNotesScreen
- [x] ProfileScreen
- [x] ActiveScreen
- [x] BlockDefenseScreen
- [x] KeywordBlockerScreen
- [x] WindowsSetupScreen
- [x] PrivacyPermissionsScreen

### UI Components
- [x] BlockOverlay
- [x] FocusLauncherOverlay
- [x] TaskCard
- [x] SideNav (with Focus Launcher nav entry)
- [x] AppLogo
- [x] EmptyStateCard
- [x] ScrollUtils
- [x] OsBanner
- [x] OnboardingScreen

### MSIX / Microsoft Store
- [x] MSIX identity correct: `TBTechs.FocusFlowDeepFocusAppBlocker`
- [x] Publisher correct: `CN=E08824C8-6F22-4DC2-8025-DD8C707E2BE9`
- [x] Triple-field manifest verification before `makeappx` runs in CI
- [x] Store logo generation (CI PowerShell — all required sizes)
- [x] `runFullTrust` capability declared
- [x] Privacy Policy hosted: https://titanicbhai.github.io/FocusFlow-PC/privacy-policy/
- [x] Terms of Service hosted: https://titanicbhai.github.io/FocusFlow-PC/terms-of-service/
- [x] Store listing copy written (short + long description + notes to certification)

### Website & Docs
- [x] docs/index.html — public website (GitHub Pages)
- [x] docs/privacy-policy/index.html
- [x] docs/terms-of-service/index.html
- [x] README.md updated
- [x] MICROSOFT_STORE_PUBLISH.md — copy-paste ready Partner Center content
- [x] BUILD_TRACKER.md updated

---

## Build Instructions

```bash
# Run the app (UI cross-platform; enforcement Windows-only)
gradle run

# Build Windows EXE (GitHub Actions only — jpackage requires Windows)
gradle packageExe

# Build Windows MSI
gradle packageMsi

# Build distributable (for manual MSIX)
gradle createDistributable
```

---

## GitHub

- **Repo**: https://github.com/TITANICBHAI/FocusFlow-PC
- **CI**: https://github.com/TITANICBHAI/FocusFlow-PC/actions
- **Website**: https://titanicbhai.github.io/FocusFlow-PC/
