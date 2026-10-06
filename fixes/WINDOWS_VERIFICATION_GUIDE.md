# FocusFlow reliability verification — Windows runbook

**Scope:** outstanding Windows, interactive, and app-level checks across Batches 0–7.  
**Important:** the v2.0.2 working tree is currently only in this Replit workspace. It has not been pushed to GitHub or packaged as a Windows installer. An installed v2.0.1 build cannot verify the new source changes.

## 1. Use an isolated test profile

Run this in Windows Sandbox, a disposable VM, or a dedicated Windows test account. Allowance enforcement can terminate the selected test process; avoid important work and unsaved documents. Do not enable Nuclear Mode or test registry lockdown on your everyday Windows installation.

The app stores its database under `<user.home>\.focusflow\focusflow.db`. When running from source, set `user.home` only in the current PowerShell process so the test app uses a scratch profile. Do not use `setx`, and do not point this at your normal Windows profile.

The repository currently has a Unix `gradlew` script but no `gradlew.bat`; use an installed Gradle 8.14.2. The project was last verified with GraalVM JDK 19.0.2.

```powershell
# From the FocusFlow source checkout
java -version
gradle --version
gradle clean check --no-daemon --console=plain
```

Expected automated result for this source snapshot: 62 tests, 0 failures, 0 errors, 0 skipped. Record the actual result if it differs.

To run the app with a separate database:

```powershell
$testHome = 'C:\FF-UAT'
New-Item -ItemType Directory -Force -Path $testHome | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Duser.home=$testHome"
gradle :run --no-daemon
```

After the app reaches its main screen, confirm the scratch database exists:

```powershell
$db = Join-Path $testHome '.focusflow\focusflow.db'
Get-Item "$testHome\.focusflow\focusflow.db*" -ErrorAction SilentlyContinue |
  Select-Object FullName, Length, LastWriteTime
```

If the database is not under `C:\FF-UAT\.focusflow`, stop and do not continue. Keep `JAVA_TOOL_OPTIONS` set in each PowerShell window used to launch the test app. Closing that window clears the setting. When testing a packaged EXE/MSI instead of `gradle :run`, use a separate Windows account; do not install it over the everyday profile for these tests.

Do not delete, rename, or overwrite any real `focusflow.db`, `focusflow.db-wal`, or `focusflow.db-shm`. Keep the scratch profile until results have been recorded.

## 2. Phase 0 — Windows evidence and feasibility checks

### 0.7 — Identify the process used by the Windows build

With the test app running, use PowerShell:

```powershell
Get-Process -Name FocusFlow,java,javaw -ErrorAction SilentlyContinue |
  Select-Object Id, ProcessName, Path, StartTime
```

Record the process name and executable path that belong to FocusFlow. `gradle :run` is expected to run under Java and does **not** settle the installed-launcher/watchdog check; repeat this check with a packaged Windows build when one is available. Do not terminate any process as part of this check.

### Manual lock-holder observation

Open Resource Monitor (`resmon.exe`) → **CPU** → **Associated Handles**, and search for the scratch database name `focusflow.db` and `focusflow.db-wal`. Record whether the expected test process appears. In PowerShell, record the scratch files and candidate processes:

```powershell
Get-Process -Name FocusFlow,java,javaw -ErrorAction SilentlyContinue |
  Select-Object Id, ProcessName, Path, StartTime
Get-Item "$testHome\.focusflow\focusflow.db*" -ErrorAction SilentlyContinue |
  Select-Object Name, Length, LastWriteTime
```

This is only a manual Resource Monitor observation. It does not prove that the app's optional Restart Manager integration works.

### 0.4 / 0.5 — Read-only WAL and Restart Manager spikes

These are developer feasibility spikes, not user-facing app features. The current app does not ship read-only startup, and the project does not currently contain the Restart Manager `LockHolderLookup` implementation or a Windows spike harness. Do not try to enable read-only mode or mark either spike passed based on the manual Resource Monitor check. They remain no-go until a Windows-specific probe is added and run.

## 3. Phase 3 — Single-instance and locked-database startup

### Two launches

1. Start the app from the isolated profile and wait for the main screen.
2. From a second PowerShell window, set the same `JAVA_TOOL_OPTIONS` value and run `gradle :run --no-daemon` again.
3. Confirm the second launch exits after focusing the first instance; the first remains usable.
4. Record whether any duplicate tray icon, duplicate enforcement behavior, or startup error appears.

This checks the guard when launched from Gradle. It does not replace the packaged process-name check in 0.7.

### Real SQLite lock and recovery gate

Use the scratch database only. First close every FocusFlow test-app instance normally. If SQLite command-line tools are installed, open a second PowerShell window and hold an exclusive transaction:

```powershell
sqlite3.exe "$testHome\.focusflow\focusflow.db"
```

At the SQLite prompt:

```sql
PRAGMA journal_mode=DELETE;
BEGIN EXCLUSIVE;
SELECT 1;
```

Leave the SQLite prompt open. Confirm the journal mode result is `delete`. In another PowerShell window, set the same test profile and start the app:

```powershell
$testHome = 'C:\FF-UAT'
$env:JAVA_TOOL_OPTIONS = "-Duser.home=$testHome"
gradle :run --no-daemon
```

Wait for the database-busy startup gate; the initial retry policy can take up to about 35 seconds. Confirm the app remains open and does not enter the normal screens with missing settings. Do not select a read-only option if one is shown; read-only startup is not approved.

At the SQLite prompt, release the lock with:

```sql
.rollback
.quit
```

Confirm the app continues to normal startup automatically after the retry, without launching a second instance or showing an uninitialized-database error. Record the time from releasing the lock to reaching the main screen. Do not kill the lock holder or remove database sidecars.

This also supplies Windows evidence for the app-level gate-to-ready flow (Phase 3.13), but the separate non-Windows integration verification remains a developer-side gap.

## 4. Phases 4 and 6 — Allowance editing, PIN, and failure recovery

Use only harmless test programs such as a blank Notepad window. Do not choose an app with unsaved work.

1. Set a Global PIN using the app's Global PIN protection flow.
2. From **Focus → Daily Allowance** (or **Blocker → Daily Allowance**), add an allowance. Adding should not require the PIN.
3. Lower/tighten the allowance. This should not require the PIN.
4. Raise the limit. Confirm the Global PIN is requested when configured. Cancel or enter an incorrect PIN and confirm the old limit remains. Enter the correct PIN and confirm the new limit is saved and any block is reconciled when current usage is below the new limit.
5. Delete the allowance. Confirm the PIN is required and that cancel/incorrect PIN leaves the allowance in place.
6. Return to Focus and verify the allowance row opens the editor directly. Check that loading, remaining, blocked-until-midnight, and Emergency Break text is understandable and consistent.

### Failed-save behavior with an isolated WAL writer lock

This tests save recovery without corrupting the database. It requires the app to be running from the isolated test profile and the external SQLite tool to be pointed at the same scratch database:

1. In a second terminal run `sqlite3.exe "$testHome\.focusflow\focusflow.db"`.
2. At the SQLite prompt run `BEGIN IMMEDIATE;` and leave the prompt open. This holds the writer slot while allowing WAL readers.
3. In the app, try to save a harmless allowance change. Wait for the configured database timeout. Confirm the UI remains responsive, shows a clear save error, and leaves the editor/dialog open with the entered values.
4. Release the lock with `.rollback` and `.quit`.
5. Retry the save in the app. Confirm it succeeds and the editor closes only after success.

This is a save-failure test, not a load-failure test. A WAL writer lock normally does not prevent reads. Do not corrupt the scratch database or change ACLs to force a load failure. The load-error state is covered by the automated UI-state tests; a safe live UI injection would require a dedicated test hook.

### Emergency Break and actual blocking

1. Add a one-minute allowance for a harmless test app.
2. Keep that app in the foreground until the limit is reached. Confirm it becomes blocked and the status says it is blocked until midnight.
3. Activate Emergency Break. Confirm the blocked app can run while the break is active and usage continues to accumulate.
4. End the break. Confirm the process is blocked/killed promptly and the block remains in place.

This uses the daily Emergency Break budget, so do it only in the disposable profile. Avoid testing Nuclear Mode or registry lockdown outside a disposable VM.

## 5. Phases 2 and 5 — Windows allowance tracking accuracy

Add allowances for two harmless test apps, then:

1. Switch A → B → A with Alt+Tab, including several short foreground visits. Confirm usage follows the foreground app rather than merely whether the process is running.
2. If available, test a Store app and an elevated app. Record the app shown in usage/logs; unresolved foreground identity should be reported, not inferred.
3. Lock Windows with **Win+L** for at least one minute, unlock, and check that locked time was not charged.
4. Put the machine to sleep for at least one minute while a tracked app is foregrounded; resume and confirm there is no multi-minute usage jump.
5. For the stopwatch check, run 15 minutes of known mixed foreground use and compare the per-app totals. The plan's target is less than one minute of error.
6. If using multiple monitors, record the focused app and whether a visible but unfocused second-monitor app was charged. Tracking is foreground-based, not visible-window based.
7. Display-off monitoring is intentionally omitted. Record the observation, but do not report display-off exclusion as a passing requirement.
8. Do not change the Windows system date to test midnight rollover; that scenario is covered by automated fake-clock tests.

The isolated-profile log is at:

```powershell
Get-Content "$testHome\.focusflow\enforcement.log" -Tail 50
```

Allowance diagnostic snapshots are rate-limited to at most one per 60 seconds. They include foreground identity, credited seconds, missed events, and discarded gaps. Redact account names and unrelated process details before sharing excerpts.

## 6. Dashboard, Active, Profile, and load-error states

Open Dashboard, Active, and Profile and confirm their normal allowance summaries load without a false zero. The app's startup gate prevents normal screens from opening while the database is unavailable, and a SQLite writer lock in WAL mode does not reliably make reads fail. Do not damage the scratch database to force these errors. The retry/error rendering is covered by automated state tests; live injection requires a dedicated test hook and is not currently available in the UI.

## 7. Automated cases already covered by the suite

The fake-clock and injected-store tests cover long uptime before adding an allowance, sleep-sized gaps, null/missed foreground events, tracking-loop/store failures, exact switching and short intervals, midnight rollover, allowance reconciliation, PIN policy, persistence, and DB recovery classification. Do not simulate these by changing the Windows clock or modifying a real database.

The project also has an optional Phase 3.12 watchdog-hardening item. It is an implementation choice, not a manual acceptance test; it is not part of this runbook.

## 8. Report results

For each runnable item, send **PASS / FAIL / NOT RUN**, observed behavior, approximate elapsed time, and the app/JDK/Windows versions. For failures, include only a short sanitized excerpt; do not send the database, `-wal`/`-shm` files, credentials, or full logs.

```text
Windows edition/build:
FocusFlow source/build tested (confirm whether it is the updated v2.0.2 source):
JDK:
Gradle:
Test profile path:

Automated check:
0.7 process name/path:
Two launches:
Busy DB gate and automatic recovery:
Allowance PIN add/tighten/raise/delete:
Failed save and retry:
Emergency Break:
Alt+Tab / short sessions:
Store/elevated app:
Lock/unlock:
Sleep/resume:
15-minute stopwatch error:
Dashboard/Active/Profile:
Other observations:
```
