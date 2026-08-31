# FocusFlow — Deep Focus App Blocker for Windows

> **Real enforcement. No soft timers. No workarounds.**

FocusFlow is a Windows productivity app built with **Kotlin + Compose Multiplatform Desktop**. It kills blocked processes via Win32/JNA, adds live Windows Firewall rules, and now includes a full **Focus Launcher** — a CBT-style kiosk mode that replaces your desktop during deep work sessions.

Available on the **Microsoft Store** as *FocusFlow — Deep Focus App Blocker* by TBTechs.

---

## Download

| Format | Where |
|--------|-------|
| **Microsoft Store** | Search *FocusFlow Deep Focus App Blocker* |
| **EXE installer** | [GitHub Actions](../../actions) → latest build → `FocusFlow-Windows-EXE` |
| **MSI package** | [GitHub Actions](../../actions) → latest build → `FocusFlow-Windows-MSI` |
| **MSIX package** | [GitHub Actions](../../actions) → latest build → `FocusFlow-Windows-MSIX` |

Every push to `main` automatically builds EXE + MSI + MSIX via GitHub Actions and creates a GitHub Release.

---

## Features

### Focus Launcher — CBT-Style Kiosk Mode
| Feature | Detail |
|---------|--------|
| **Kiosk desktop** | Hides the Windows taskbar and replaces the desktop with a full-screen focus workspace |
| **Nuclear Mode integration** | Activates maximum blocking the moment a launcher session starts |
| **PIN-gated breaks** | 5-minute breaks require a SHA-256 PIN — no impulsive escapes |
| **Hard lock mode** | No breaks allowed — pure deep work until the session ends |
| **Crash recovery** | Taskbar is unconditionally restored on next launch, JVM shutdown hook, and global crash handler |
| **Safe process whitelist** | 55+ input/system processes are never killed (keyboard drivers, touchpad, mouse peripherals, audio, UAC, accessibility) |

### App & Website Blocking
| Feature | Status | How |
|---------|--------|-----|
| **App blocking** | ✅ Real | `SetWinEventHook` (instant) + 500ms polling fallback via JNA |
| **Network blocking** | ✅ Real (admin) | `netsh advfirewall` + PowerShell `New-NetFirewallRule` |
| **Keyword blocker** | ✅ | Window-title keyword detection |
| **Website blocking** | ✅ | Hosts-file domain blocking |
| **Block schedules** | ✅ | Automatic blocking on a weekly timetable |
| **Daily allowances** | ✅ | Per-app time limits per day |
| **Standalone blocks** | ✅ | One-shot timed block sessions |
| **Block overlay** | ✅ | Always-on-top full-screen Compose overlay |
| **Nuclear Mode** | ✅ | Maximum enforcement — blocks Task Manager + 30+ escape routes |
| **Block Defense** | ✅ | Hardened enforcement layer configuration |

### Focus Sessions & Productivity
| Feature | Status |
|---------|--------|
| Focus session timer (Pomodoro + custom) | ✅ |
| Session PIN gate (SHA-256) | ✅ |
| Sound aversion tones on app kill | ✅ |
| Break enforcer | ✅ |
| Habit tracker with streak grid | ✅ |
| Task management with recurring tasks | ✅ |
| Task alarms & reminders | ✅ |
| Daily notes | ✅ |
| Stats, charts & session history | ✅ |
| Temptation log (every block attempt) | ✅ |
| Weekly focus report (auto every Sunday) | ✅ |
| Focus insights | ✅ |

### System & Privacy
| Feature | Status |
|---------|--------|
| System tray — minimise, toggle, quit | ✅ |
| Auto-start with Windows (HKCU Run key) | ✅ |
| Auto-backup (rolling local backups) | ✅ |
| All data stored locally — no accounts, no cloud | ✅ |
| Windows notifications (balloon + toast) | ✅ |

---

## Enforcement Details

**Dual-mode foreground detection:**
1. `SetWinEventHook` (WINEVENT_OUTOFCONTEXT) — fires instantly on any foreground change
2. 500ms polling fallback — catches UWP apps routed through ApplicationFrameHost.exe

**When a blocked app is detected:**
1. `ProcessHandle.of(pid).destroyForcibly()` — JVM 9+ process kill
2. Fallback: `taskkill /F /IM processname.exe` via `ProcessBuilder`
3. Block overlay window appears full-screen
4. Block attempt logged to SQLite temptation log
5. Optional: `New-NetFirewallRule` via PowerShell adds a live outbound firewall rule

**UWP app support:** When ApplicationFrameHost.exe is foreground, the monitor scans running processes to resolve the actual hosted UWP app.

**Focus Launcher safety:** 55+ system processes are always whitelisted — keyboard stack (`ctfmon.exe`, `tabtip.exe`, `textinputhost.exe`), OEM touchpad drivers (Synaptics, Elan, Precision), mouse peripherals (Logitech, Razer, SteelSeries), audio (`audiodg.exe`), UAC (`consent.exe`), and accessibility tools (`narrator.exe`, `magnify.exe`).

---

## Build Locally

Requires JDK 17+.

```bash
# Run the app (UI is cross-platform; enforcement is Windows-only)
./gradlew run

# Build standalone Windows EXE (~150 MB with embedded JRE)
./gradlew packageExe

# Build MSI installer
./gradlew packageMsi

# Build distributable (for MSIX)
./gradlew createDistributable
```

### Windows install and uninstall flow

The EXE and MSI packages use the standard minimal `jpackage` install wizard.
On the first launch after installation, FocusFlow registers a small
FocusFlow-owned uninstall wizard in the Windows uninstall entry. This
registration happens before normal database/service startup, so the app only
needs to start successfully once; it does not need to finish onboarding. The
wizard does not replace Windows Installer: it checks active Standalone Blocks,
Always-On/active-session/Nuclear Mode protection, then hands the original MSI
or EXE removal command back to Windows.

The same protection gate is used by the tray icon's **Quit** action. A
Standalone Block must finish, and configured PIN protections must be
authorized. Nuclear Mode without a Nuclear PIN must first be disabled from
inside FocusFlow. Microsoft Store/MSIX removal remains Windows-controlled and
is intentionally not intercepted.

Cross-compilation is not supported by jpackage. Use GitHub Actions for Windows EXE/MSI/MSIX.

---

## Tech Stack

| Layer | Choice | Version |
|-------|--------|---------|
| Language | Kotlin/JVM | 1.9.22 |
| UI | Compose Multiplatform Desktop | 1.6.1 |
| UI design system | Material 3 dark theme | — |
| Native interop | JNA + jna-platform | 5.14.0 |
| Database | org.xerial:sqlite-jdbc | 3.45.1.0 |
| Async | kotlinx.coroutines-swing | 1.7.3 |
| Build | Gradle (Kotlin DSL) | 8.14.2 |
| Packaging | jpackage (Compose Desktop plugin) | bundled JRE |
| CI/CD | GitHub Actions `windows-latest` | — |

---

## Project Structure

```
src/main/kotlin/com/focusflow/
├── Main.kt                          Entry point; wires all services + tray
├── App.kt                           Root composable; onboarding check + nav
├── ui/
│   ├── theme/Theme.kt               Material 3 dark theme
│   ├── screens/
│   │   ├── DashboardScreen.kt
│   │   ├── TasksScreen.kt
│   │   ├── FocusScreen.kt
│   │   ├── FocusLauncherScreen.kt   CBT kiosk launcher UI
│   │   ├── AppBlockerScreen.kt
│   │   ├── StatsScreen.kt
│   │   ├── SettingsScreen.kt
│   │   ├── HabitsScreen.kt
│   │   ├── ReportsScreen.kt
│   │   ├── DailyNotesScreen.kt
│   │   ├── ProfileScreen.kt
│   │   ├── ActiveScreen.kt
│   │   ├── BlockDefenseScreen.kt
│   │   ├── KeywordBlockerScreen.kt
│   │   ├── WindowsSetupScreen.kt
│   │   └── PrivacyPermissionsScreen.kt
│   └── components/
│       ├── SideNav.kt
│       ├── TaskCard.kt
│       ├── BlockOverlay.kt
│       ├── FocusLauncherOverlay.kt  Full-screen kiosk overlay
│       ├── AppLogo.kt
│       ├── EmptyStateCard.kt
│       ├── ScrollUtils.kt
│       ├── OsBanner.kt
│       └── OnboardingScreen.kt
├── data/
│   ├── Database.kt                  SQLite via sqlite-jdbc
│   └── models/Models.kt             Data classes
├── enforcement/
│   ├── WinApiBindings.kt            JNA Win32 bindings
│   ├── ProcessMonitor.kt            Dual-mode: WinEventHook + 500ms polling
│   ├── AppBlocker.kt                Kill + overlay bridge
│   ├── NetworkBlocker.kt            netsh advfirewall rules
│   ├── NuclearMode.kt               Nuclear blocking (30+ escape routes blocked)
│   ├── WinEventHook.kt              Instant foreground event detection
│   ├── InstalledAppsScanner.kt      Curated + live running process scanner
│   └── WindowsStartupManager.kt     HKCU Run key auto-start
└── services/
    ├── FocusSessionService.kt
    ├── FocusLauncherService.kt      Kiosk session state + taskbar control
    ├── TemptationLogger.kt
    ├── SessionPin.kt
    ├── SoundAversion.kt
    ├── SystemTrayManager.kt
    ├── NotificationService.kt
    ├── TaskAlarmService.kt
    ├── RecurringTaskService.kt
    ├── BlockScheduleService.kt
    ├── StandaloneBlockService.kt
    ├── DailyAllowanceTracker.kt
    ├── WeeklyReportService.kt
    ├── BreakEnforcer.kt
    ├── FocusInsightsService.kt
    ├── BackupService.kt
    ├── AutoBackupService.kt
    ├── HostsBlocker.kt
    └── PrivacyPolicyService.kt
```

---

## MSIX / Microsoft Store Identity

| Field | Value |
|-------|-------|
| Identity Name | `TBTechs.FocusFlowDeepFocusAppBlocker` |
| Publisher | `CN=E08824C8-6F22-4DC2-8025-DD8C707E2BE9` |
| Version | `1.0.6.0` |
| Display Name | `FocusFlow — Deep Focus & App Blocker` |
| Publisher Display Name | `TBTechs` |

---

## Links

- **Website**: https://focusflowpc.pages.dev/
- **Privacy Policy**: https://focusflowpc.pages.dev/privacy-policy/
- **Terms of Service**: https://focusflowpc.pages.dev/terms-of-service/
- **Android version**: https://focusflowapp.pages.dev
