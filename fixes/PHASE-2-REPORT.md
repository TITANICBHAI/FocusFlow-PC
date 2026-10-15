# Phase 2 Report — F16 Measurement and F18 Startup Status

**Date:** 2026-10-15

**Status:** F16 measurement complete; Phase 2 remains in progress.

## Outcome

The opt-in measurement captured 9 contended enters on the `com.focusflow.data.Database` monitor over 600,216 ms. Their combined wait was 9.248 ms. No wait occurred on an AWT event thread. There were 7 distinct callsites, so the report lists all 7 rather than padding the top 10.

The longest single wait was 7.633 ms during startup. This run does not provide evidence for a threading restructure. **D19 approval remains required before any such change; none was requested, granted, or implemented.**

## Capture details

- **Started:** 2026-10-15 05:15:05.500515101 UTC
- **Ended:** 2026-10-15 05:25:05.723454452 UTC
- **Requested / actual duration:** 10 minutes / 600,216 ms
- **Runtime:** OpenJDK 21.0.7
- **OS:** Linux amd64
- **Profile:** Fresh temporary `user.home`; telemetry disabled. The regular user profile and its database were not used.
- **Workload limits:** The app was left running through startup and background activity. No UI navigation was scripted, so this is not a representative active-use workload.
- **Collection:** The debug-only `focusflow.db.contention.enabled` system property opts into a ten-minute JFR `jdk.JavaMonitorEnter` recording with stack traces. The summary filters to the Database monitor and ranks caller sites by accumulated wait. The raw JFR file was removed after the summary was written.

## Observed top callsites

Ranked by accumulated monitor wait:

| Rank | Waits | Total wait | Max wait | Caller → Database method |
|---:|---:|---:|---:|---|
| 1 | 1 | 7.633 ms | 7.633 ms | `StartupBootstrap$startServices$1.invoke:81` → `Database.readSetting$focusflow_pc` |
| 2 | 1 | 0.581 ms | 0.581 ms | `DailyAllowanceTracker$createProductionPorts$5.allowances:91` → `Database.getDailyAllowances` |
| 3 | 2 | 0.425 ms | 0.238 ms | `AppKt.readAppSetting:94` → `Database.readSetting$focusflow_pc` |
| 4 | 1 | 0.239 ms | 0.239 ms | `KillSwitchService.loadPersistedSettings:149` → `Database.writeSettings$focusflow_pc` |
| 5 | 2 | 0.184 ms | 0.128 ms | `TaskAlarmService.checkAlarms:150` → `Database.getTasksForDate` |
| 6 | 1 | 0.102 ms | 0.102 ms | `DashboardScreenKt$DashboardScreen$reload$1$t$1.invokeSuspend:114` → `Database.getTasksForDate` |
| 7 | 1 | 0.083 ms | 0.083 ms | `AppKt.writeAppSetting:119` → `Database.writeSetting$focusflow_pc` |

**AWT event-thread waits:** 0 enters, 0.000 ms.

## Implementation and verification

- Added the opt-in JFR measurement and started it before database initialization; it auto-stops after ten minutes and records a text summary under `.focusflow`.
- Added a synthetic contention test that verifies monitor filtering, wait duration, AWT-thread detection, and callsite reporting.
- No database synchronization, dispatcher, or thread behavior was changed.
- Focused test `gradle :test --no-daemon --console=plain --tests com.focusflow.data.DatabaseMonitorDiagnosticsTest`: **1 passed**.
- Full root test `gradle :test --no-daemon --console=plain`: **204 passed, 0 failures, 0 errors, 0 skipped**, across 51 JUnit XML files.
- The first focused run exposed a duration-parsing mismatch (`getValue("duration")` returns raw ticks here); the parser was corrected to use `RecordedEvent.getDuration()`. A subsequent test failure was only a stale expected test-class name; that assertion was corrected. The final focused test and full suite passed.
- The temporary app workflow was stopped and restored to its original command.

## Limits and remaining gates

This was a Linux fresh-profile capture, not a Windows validation or a busy-user workload. The absence of AWT waits applies only to this ten-minute session. F19 remains open, and the Phase 2 exit criteria are not met. D19 remains an owner-approval gate for any threading restructure.

## F18 — Database unavailable status

**Date:** 2026-10-15
**Owner decision:** D10 option A — show “Blocking is OFF until the database opens” on the startup gate and in the system tray. No degraded blocker or enforcement-policy change.

### Outcome

- The gate displays the status for both database Busy and Failed states.
- A limited tray mode displays the same status and provides Open and Quit actions only; it does not expose Toggle Blocking.
- When the database opens, the limited tray is removed or replaced by the regular service tray.
- The status is removed on quit. Database-dependent enforcement services still start only after `Database.init()` returns Ready.

### Files read and changed

- **Read:** `replit.md`; `fixes/plan.md`; `fixes/tracker.md`; `fixes/focusflow-audit-evidence/README.md`; `fixes/PHASE-2-REPORT.md`; `.agents/memory/silent-startup-gate.md`; `src/main/kotlin/com/focusflow/Main.kt`; `src/main/kotlin/com/focusflow/ui/startup/StartupGateWindow.kt`; `src/main/kotlin/com/focusflow/services/SystemTrayManager.kt`; `src/main/kotlin/com/focusflow/services/StartupBootstrap.kt`; `src/test/kotlin/com/focusflow/StartupFailureDetailsTest.kt`; `build.gradle.kts`.
- **Changed:** `src/main/kotlin/com/focusflow/Main.kt`; `src/main/kotlin/com/focusflow/ui/startup/StartupGateWindow.kt`; `src/main/kotlin/com/focusflow/services/SystemTrayManager.kt`; `src/test/kotlin/com/focusflow/StartupUnavailableStatusTest.kt`; `fixes/tracker.md`; this report.

### Verification

- The first focused command, `gradle :test --no-daemon --console=plain --tests com.focusflow.StartupUnavailableStatusTest`, hit the shell's 300-second timeout during `:compileKotlin`; it produced no final result and is not counted as a test result.
- The background rerun of that exact command completed with `BUILD SUCCESSFUL`; JUnit XML: 2 tests, 0 failures, 0 errors, 0 skipped.
- `gradle :test --no-daemon --console=plain`: `BUILD SUCCESSFUL`; 206 tests, 0 failures, 0 errors, 0 skipped across 52 JUnit XML files.
- `JAVA_HOME` Java: GraalVM CE 22.3.1 / OpenJDK 19.0.2. Gradle: 8.14.2; launcher and daemon JVM: OpenJDK 21.0.7. The test worker JVM was not independently captured in this run.
- `git diff --check` passed. A scan of changed production files found no empty `catch (_: Exception) {}` blocks.

### Limits and remaining gates

No Windows verification was performed. The desktop workflow was not launched because its configured command initializes the normal profile database; no real profile was accessed. The Compose screen and system tray were compiled and their presentation rules were unit-tested, but the failure UI was not visually exercised. F19 remains open; Phase 2 exit criteria and the remaining owner/data-repair gates are not met.
