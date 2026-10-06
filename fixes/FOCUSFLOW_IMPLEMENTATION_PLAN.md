# FocusFlow: Startup DB Lock and Daily Allowance Reliability

Implementation plan for the coding agent. Read the whole document before starting. Work phase by phase.

## 0. Rules of engagement

1. One phase = one branch/PR (or one clearly separated commit set). Do not start phase N+1 until phase N meets its acceptance criteria and build + tests are green.
2. Write the failing test that reproduces a bug BEFORE fixing it (phases 2, 3, 4, 5). Keep the test.
3. Never delete, truncate or recreate `~/.focusflow/focusflow.db` (or its `-wal` / `-shm`) automatically. User data loss is the worst outcome in this project.
4. Keep Windows-only native code (JNA) behind small interfaces so logic is testable on any OS. No test may call Win32.
5. Keep existing public call sites working (`DailyAllowanceTracker.start/stop/reload/getUsageMinutes/getRemainingMinutes/getUsageSummary/blockedProcesses`, `Database.*`) unless a phase says otherwise.
6. Follow existing conventions: `object` services, `EnforcementLog.warn/info` for logging, theme constants (`Surface2`, `Purple80`, `Warning`, `Error`, `OnSurface`), i18n via `LocalizationManager.strings`.
7. Do not change behaviour outside this plan. If you find another bug, note it in the PR description and leave it.
8. First, discover and record in the PR: build tool and versions, JDK, Compose Desktop version, `sqlite-jdbc` version, test framework and test source-set location. The provided source zip had no build files or tests.

## 1. Background

Reported errors (the log order given by the user is reversed; causal order is 1 then 2):

1. `SQLiteException [SQLITE_BUSY]` at `Database.tryOpenAndMigrate` (Database.kt:73, `PRAGMA journal_mode=WAL`), called from `Database.init()` at Main.kt:54. `init()` swallows BUSY and returns, startup continues.
2. `UninitializedPropertyAccessException: lateinit property connection` at `Database.getDailyAllowances()` (Database.kt:819), called from `DailyAllowanceTracker.start()` (line 59), Main.kt:100.
3. A user cannot change an app's daily allowance and doubts the usage counting.

## 2. Findings

### 2.1 Confirmed by code reading (reproduce with a test before fixing)

Database / startup
- C1. Only 9 of ~72 `Database` methods check `isReady`; 63 access `connection` directly. `getDailyAllowances`, `upsertDailyAllowance`, `deleteDailyAllowance` are unguarded. Guarded ones return null/empty silently, so a locked DB looks like "no settings"; `GlobalPin.isSet()` then reads false and PIN gates are open (fail-open).
- C2. `connection` is assigned before `migrate()`; on migration failure the catch block closes it, leaving `isReady == true` with a closed connection.
- C3. `init()` treats every non-BUSY failure as corruption: moves the DB aside, recreates it, and as last resort calls `dbFile.delete()`. I/O errors, SQLITE_LOCKED, CANTOPEN, antivirus interference can destroy a good DB. `init()` is not synchronized or idempotent and is also called from `UninstallProtectionService.prepareForUninstallWizard()` (separate JVM, concurrent with the main app).
- C4. BUSY goes through `CrashReporter.reportCritical`, which sends a Discord alert worded like corruption.
- C5. No single-instance guard exists anywhere (grep for lock/mutex/ServerSocket found nothing).
- C6. `Main.kt` runs ALL startup side effects directly inside the `application { }` composable body. That body reads Compose state (`sessionState`, `launcherActive`, `ksRemaining`, `ksActive`, `windowVisible`), so recomposition can re-run them. Several `start()` methods have idempotence guards; `Database.init()`, `WatchdogInstaller.install()` (spawns `schtasks` twice), `SystemTrayManager.install(...)` and others are not known to. (Hypothesis H3 confirms whether this really happens at runtime.)
- C7. `RegistryLockdown.disable()` runs unconditionally at startup (Main.kt janitor). A second overlapping instance would remove registry keys the first instance (e.g. Nuclear Mode) set. The single-instance guard must run before it.

Tracker (`services/DailyAllowanceTracker.kt`)
- T1. Sampling: every 10 s the whole elapsed interval is credited to whichever process is foreground at that instant. Real but coarse; short sessions can be missed or over-credited and the cadence can be gamed.
- T2. UNBOUNDED CREDIT (most likely cause of report #3): `elapsedSecs = (now - lastTickMs)/1000` has no cap; `lastTickMs` is only updated at the end of a full tick (line 204); early returns at line 129 (`allowances.isEmpty()`) and line 137 (ProcessHandle failure) skip it. Adding the first allowance after hours of uptime, or resuming from sleep with the tracked app in front, credits hours at once, then blocks and kills the app for the day (persisted).
- T3. `tick()` is not wrapped in try/catch inside the loop (lines 81-86). One exception (e.g. DB write throwing BUSY) ends the loop forever; nothing restarts it, so counting, enforcement and midnight reset all stop. Compare `BlockScheduleService.tick`, which catches.
- T4. `reload()` (line 96) only refreshes `allowances`. It never recomputes `blockedToday` or `ProcessMonitor.dailyAllowanceBlockedProcesses`: raising a limit leaves the app blocked; deleting an allowance leaves it blocked (WinEventHook uses the stale set); UI shows "N min remaining" while blocked.
- T5. Persistence: flush every 6 ticks (~60 s) and in `stop()`. `upsertDailyUsage` silently no-ops when DB not ready and throws on BUSY. `INSERT OR REPLACE` writes absolute totals, so overlapping instances overwrite each other.
- T6. Minor: integer-second truncation each tick (~3% under-count), `coerceAtLeast(1)`, foreground sample taken before `ProcessHandle.allProcesses()` (0.1-0.5 s skew). On non-Windows the tracker counts "running", not "foreground".
- T7. Emergency Break: `ProcessMonitor.isAnyEnforcementActive()` returns false when `killSwitchActive`, but the tracker still kills allowance-blocked apps during a break (inconsistent).
- T8. `daily_allowances.process_name` is a case-sensitive PRIMARY KEY while the tracker lowercases keys; case-variant duplicate rows would be counted twice into one usage key.

UI
- U1. Editor lives at Blocker -> Daily Allowance tab (`AppBlockerScreen.kt`, `DailyAllowanceTab` ~line 914; tab index 1). Focus screen only shows a non-clickable row `"Daily Allowance: N apps"` (`FocusScreen.kt:1044`); `FocusScreen` has no navigation callback and `AppBlockerScreen` has no `initialTab` parameter.
- U2. `reload()`, delete, add and edit-save in `DailyAllowanceTab` run in `scope.launch` with no try/catch: a failed load leaves the spinner forever; a failed save leaves the dialog open with no message. `FocusScreen.kt:107` shows "0 apps" when the load fails.
- U3. Edit/raise has no PIN gate; only delete is gated (`guardedRemoval`).

### 2.2 Hypotheses to verify in Phase 0

- H1. Overlapping instances (Run-key autostart, 2-min watchdog `Get-Process -Name 'FocusFlow'` check, manual launches, uninstall-wizard JVM).
- H2. Previous instance still shutting down (connection never closed in `doShutdown`).
- H3. Recomposition re-running `Database.init()` (leaks one connection per recomposition, ~1/s during focus sessions).
- H4. External lock holders (antivirus, cloud sync, DB viewer).
- H5. SQLite busy handler not invoked (deadlock-avoidance path) or not applied; extended BUSY result codes not matching `resultCode == SQLITE_BUSY`.
- H6. `ProcessHandle.info().command()` empty for elevated processes; Store apps appear as `ApplicationFrameHost.exe`.

## 3. Decisions already made (do not re-litigate)

1. Locked DB at startup must NOT quit the app. Use a recoverable startup gate (Phase 3).
2. Raising an allowance and deleting an allowance require the Global PIN. Adding a new allowance and lowering one do not. Gate on Save, not on opening the dialog. If no PIN is set, no gate (current behaviour).
3. Assumption (flip only if the owner says so): a PIN-verified raise unblocks the app for today when usage < new limit; a PIN-verified delete removes today's block.
4. Emergency Break is honoured but not generous: no kills of allowance-blocked apps during the break; usage KEEPS counting; crossing a limit during the break still marks the app blocked; the break does not clear blocks and never extends the 5-minute daily budget; when the break ends or the budget runs out, enforcement resumes immediately.
5. Count foreground time. Exclude only locked / asleep / display-off time. NO keyboard/mouse idle cutoff in this work (it would under-count video and controller-driven games).
6. Hybrid event-based tracking (foreground events + heartbeat), delivered after the polling hotfix.
7. Do NOT add an "End process" button to the startup gate. Ending the lock holder could kill a protected instance and bypass Nuclear Mode/PIN while the PIN cannot be verified (DB locked). Show holder name/PID and instructions only.

## 4. Non-goals

Per-website limits, multi-monitor visible-window counting, background-audio counting, tamper-proofing against clock changes beyond "never move the tracking date backwards", changing kill mechanics (`taskkill`), usage history beyond today.

## 5. Phases

Suggested new files are suggestions; keep the package layout consistent with the repo.

### Phase 0: Evidence and spikes (no behaviour change)

Goal: rank the lock causes and prove the risky assumptions before building on them.

Tasks
- [x] 0.1 Add temporary logging (EnforcementLog): count of `Database.init()` calls with timestamp and caller stack; time from `init()` start to failure; `PRAGMA journal_mode` result; count of tracker ticks and credited seconds per tick (foreground exe, elapsed ms). The instrumentation was removed after isolated runtime probes.
- [x] 0.2 Owner confirms the original `~/.focusflow/crash.log` and `enforcement.log` are unavailable; retain the supplied truncated exception excerpts and record that timing/holder analysis cannot be done from them.
- [x] 0.3 Document how to identify the lock holder manually on Windows (Resource Monitor -> Associated Handles -> `focusflow.db`; `Get-Process FocusFlow,java,javaw`; check for `-wal`/`-shm` files; `sqlite3 focusflow.db "PRAGMA journal_mode"` on a copy). See the Batch 0 evidence in `fixes/TRACKER.md`.
- [ ] 0.4 **BLOCKED — Windows validation required.** Linux read-only WAL smoke test passed, but Spike A's Windows result is unverified; do not enable read-only startup until tested on Windows.
- [ ] 0.5 **BLOCKED — Windows validation required.** Restart Manager lookup (`rstrtmgr.dll`: `RmStartSession`, `RmRegisterResources`, `RmGetList`, `RmEndSession`) via JNA returning PID + exe name of processes holding `focusflow.db`. Record whether it works for the WAL file set.
- [x] 0.6 Reproduce BUSY deterministically in a scratch test: create a DB in default (DELETE) journal mode, hold `BEGIN EXCLUSIVE` from a second connection, then run the current `tryOpenAndMigrate`. The first statement fails with BUSY. This recipe is reused in Phase 3 tests.
- [ ] 0.7 **BLOCKED — Windows process check required.** Confirm the installed process name (FocusFlow.exe vs java/javaw) and whether the watchdog's `Get-Process -Name 'FocusFlow'` check matches it.

Acceptance
- A short findings note in the PR: ranked likely causes with evidence; H3 confirmed or dropped; Spike A and B results (go / no-go for read-only mode and for lock-holder details).
- Temporary logging either removed or reduced to permanent low-noise diagnostics.

Progress on 2026-10-06: Phase 0 findings, available spike outcomes, deterministic SQLITE_BUSY reproduction (0.6), and temporary-diagnostic cleanup are recorded. H3 was confirmed under forced Compose invalidations in an isolated home: one initial `Database.init()` plus three calls from `RecomposeScopeImpl.compose`. The owner confirmed the original crash/enforcement log files are unavailable; the supplied truncated excerpts confirm the BUSY → uninitialized connection sequence. Windows-only Spike A/B and installed-process verification remain explicitly blocked/no-go. Detailed evidence and limitations are in `fixes/TRACKER.md`.

### Phase 1: Test seams (pure refactor, behaviour identical)

Goal: make DB startup and the tracker testable without Windows, a real clock or the global `object`s.

Tasks
- [x] 1.1 Test infrastructure: add JUnit5/kotlin-test, `kotlinx-coroutines-test`, and a temp-dir helper if missing.
- [x] 1.2 `Database`: add `init(dbFile: File = defaultDbFile(), policy: InitPolicy = InitPolicy.Default, allowRecovery: Boolean = true)`; add `internal fun resetForTest()` that closes and clears state. Keep `Database.init()` callable with no args.
- [x] 1.3 Tracker ports (implemented in `services/allowance/AllowancePorts.kt`):
  - `Clock { wallMs(); monoNs(); today(): LocalDate }`
  - `ForegroundSource { current(): ForegroundInfo? }` where `ForegroundInfo(exe: String, pid: Long)`; real impl wraps `getForegroundProcessName*()`
  - `RunningProcessSource { all(): List<RunningProcess>? }` to isolate process enumeration and keep tick tests deterministic
  - `ProcessKiller { kill(processName: String) }` real impl wraps `killProcessByName` / ProcessHandle fallback
  - `BreakState { val isActive: StateFlow<Boolean> }` real impl wraps `KillSwitchService.isActive`
  - `UsageStore { allowances(): List<DailyAllowance>; usage(date): Map<String, Long>; upsertUsage(date, proc, seconds); deleteUsageBefore(date) }` real impl wraps `Database`
  - `BlockedSetSink { set(blocked: Set<String>) }` real impl writes `ProcessMonitor.dailyAllowanceBlockedProcesses`
- [x] 1.4 Extract the tracker logic into `class AllowanceEngine(ports, scope)`. `object DailyAllowanceTracker` keeps its exact public API and delegates to a default engine wired to real ports. No logic change yet.

Acceptance
- Build green; app behaves identically (smoke: start app, add an allowance, see usage move).
- A trivial engine test with fake ports runs on Linux CI.

Progress on 2026-10-06: Phase 1 is complete. `gradle test --no-daemon` passes three tests, including isolated SQLite initialization/reset and fake-port usage persistence. A live app run used a temporary `user.home`; after seeding only its scratch DB with a `python3.13` allowance, the tracker persisted 41 seconds while that process ran. No user database was accessed. The production facade remains source-compatible; no tracker behavior changes were intended.

### Phase 2: Tracker hotfix (polling kept; fixes T2, T3, T5-partial, T6)

Goal: stop wrongful blocks and silent loop death now. This phase does not depend on the DB work.

Write these tests first (they must fail on current code), using the fake clock:
- first allowance added after 5 h of simulated uptime with the tracked app foreground: credited about 10 s, NOT 5 h, not blocked.
- simulated 8 h sleep gap (clock jumps, no ticks) then tick with tracked app foreground: credited 0.
- `getForegroundProcessName` returns null for several ticks, then app: no credit for null ticks.
- an exception thrown by `UsageStore.upsertUsage` / `ProcessKiller` in a tick: loop survives and the next tick runs.
- 60 consecutive 10.3 s ticks with the app foreground: usage between 615 s and 621 s (no 3% truncation loss).

Tasks
- [ ] 2.1 Baseline timing: at the very top of `tick()` compute `now`, `elapsedMs = now - lastTickMs`, then set `lastTickMs = now` BEFORE any early return. Remove the end-of-tick update (line 204).
- [ ] 2.2 Gap rule: credit = `min(wallDelta, monoDelta)`; if credit exceeds `MAX_GAP_MS` (25 s, i.e. 2.5x the interval) or is negative, credit 0 and log once ("suspended or clock jump, discarded N s"). Never credit more than `MAX_GAP_MS`.
- [ ] 2.3 Track usage internally in milliseconds (`usageMs`); persist `ms/1000` as `seconds_used`; load multiplies by 1000. `getUsageMinutes` = `ms/60_000`. Remove `coerceAtLeast(1)`.
- [ ] 2.4 Loop safety: `while (isActive) { try { tick() } catch (e: CancellationException) { throw e } catch (t: Throwable) { EnforcementLog.warn(...) } ; delay(INTERVAL) }`. Count consecutive failures; log escalation after 5.
- [ ] 2.5 `flushUsageToDB`: per-row try/catch; keep failed keys dirty and retry on the next flush; never throw.
- [ ] 2.6 `start()` must not throw: if the store is unavailable, log and return without starting the loop (the Phase 3 gate prevents this state, this is defense in depth).
- [ ] 2.7 Never move `trackingDate` backwards (ignore a wall date earlier than the last seen date).
- [ ] 2.8 (optional, low risk) On Windows avoid `ProcessHandle.allProcesses()` unless a blocked app needs killing; keep the kill behaviour identical.

Acceptance
- All new tests pass; existing behaviour for normal foreground counting unchanged.
- Manual: add the first allowance after the app has been running a while, with the tracked app foreground: usage starts near 0 and the app is not blocked.

### Phase 3: Database startup safety and recovery gate (fixes C1-C7)

Goal: no lateinit crash, no data loss, no services running without a DB, no dead end for the user.

Design

```kotlin
sealed interface DbInitResult {
    object Ready : DbInitResult
    data class Busy(val cause: SQLException, val attempts: Int, val waitedMs: Long) : DbInitResult
    data class ReadOnly(val reason: String) : DbInitResult   // only if Spike A is go
    data class Failed(val cause: Throwable, val untouchedPath: File) : DbInitResult
}
data class InitPolicy(val busyTimeoutMs: Int = 10_000, val maxAttempts: Int = 3,
                      val backoffMs: List<Long> = listOf(1_000, 2_000), val totalDeadlineMs: Long = 35_000) {
    companion object { val Default = InitPolicy() }
}
class DatabaseUnavailableException(val state: DbInitResult?) : IllegalStateException()
```

Error classification (one pure function, unit-tested): from `SQLException.errorCode`, take `code and 0xFF`: 5 = BUSY, 6 = LOCKED, 11 = CORRUPT, 26 = NOTADB, 10 = IOERR, 14 = CANTOPEN, 13 = FULL; fall back to message contains "database is locked"/"SQLITE_BUSY". BUSY, LOCKED, IOERR, CANTOPEN, FULL are NEVER corruption.

Tasks
- [ ] 3.1 Failing tests first (see Test matrix, DB section), using the Phase 0 BUSY recipe with a short `busyTimeoutMs`.
- [ ] 3.2 Connection handling: rename the `lateinit` field to `@Volatile private var conn: Connection?` and add `private val connection: Connection get() = conn ?: throw DatabaseUnavailableException(...)`. The 60+ existing methods then compile unchanged and throw a typed exception instead of lateinit. `isReady = conn?.isClosed == false`.
- [ ] 3.3 Assign `conn` only AFTER `migrate()` succeeds; on any failure close the local connection and leave `conn` null.
- [ ] 3.4 `init(...)`: `@Synchronized`, returns `Ready` immediately if already ready (idempotent). Attempts loop per `InitPolicy` for BUSY/LOCKED only; report progress through a `StateFlow<DbStartupState>` the UI can observe. No Discord `reportCritical` for BUSY (log only); keep it for real failures.
- [ ] 3.5 Recovery (replaces `safeBackupBrokenDb` + last-resort delete): only when classification is CORRUPT/NOTADB or `quick_check != ok`, and only if `allowRecovery`. Steps: copy `db`, `-wal`, `-shm` as a set to `*.broken_<ts>`, verify each copy (size, optionally SHA-256); only if ALL copies verify, MOVE (not delete) the originals aside and create a fresh DB. If any copy fails, abort and return `Failed(untouchedPath)`. Delete the `dbFile.delete()` last-resort branch.
- [ ] 3.6 Uninstall-wizard JVM: `UninstallProtectionService.prepareForUninstallWizard()` calls `Database.init(allowRecovery = false)` and treats anything other than `Ready` as "not ready" (it already fails closed on `!isReady`).
- [ ] 3.7 (only if Spike A is go) Read-only mode: open with `SQLiteConfig.setReadOnly(true)`, skip WAL pragma, checkpoint and migration, require `PRAGMA user_version >= TARGET_VERSION` else treat as unavailable. Add `Database.mode`. All write methods (`upsert*`, `set*`, `insert*`, `delete*`, `clear*`) call `requireWritable()`; `setSetting`/`upsertDailyUsage` become logged no-ops that buffer in memory where the caller supports it (tracker usage). A background retry calls `tryUpgradeToReadWrite()` (inside the same `@Synchronized` scope) and flushes buffers on success.
- [ ] 3.8 `SingleInstanceGuard` (suggested `services/SingleInstanceGuard.kt`): `FileChannel.open(~/.focusflow/instance.lock, CREATE, WRITE).tryLock()`, channel kept open for the process lifetime. Holder also opens a 127.0.0.1 ephemeral `ServerSocket`, writing the port to `instance.port`. A second launch connects, sends `SHOW`, and exits 0. Not taken by the `--uninstall` wizard path. If the lock is held but the holder does not answer, continue to the gate with an explanatory message instead of exiting silently.
- [ ] 3.9 Restructure `Main.kt`: `main()` must run, in this order and EXACTLY ONCE (outside composition state reads): `UninstallWizardFlag` branch (unchanged semantics) -> `SingleInstanceGuard` -> `CrashReporter.install()` -> `WindowsUninstallRegistration.ensureRegistered()` -> `RegistryLockdown.disable()` -> `Database.init()`. Inside `application { }` hold a `startup` state (`Starting | Gate | Ready`) driven by a `LaunchedEffect(Unit)` that runs the bootstrap on `Dispatchers.IO`. Only when the DB is `Ready` (or `ReadOnly`), run `Bootstrap.startServices()` guarded by an `AtomicBoolean` so every service `start()/load*()`, `WatchdogInstaller.install()`, `SystemTrayManager.install()`, `NetworkBlocker.syncFromFirewall()`, `HostsBlocker.startMonitor()`, `WeeklyReportService.startScheduler()` runs once. Audit every side-effecting call currently in the composable body and move it. Keep `doShutdown` as is, and add `Database.close()` (checkpoint + close) to its teardown before `exitApplication()`.
- [ ] 3.10 `StartupGateWindow` (suggested `ui/startup/StartupGateWindow.kt`), shown while `startup == Gate`:
  - "Starting" with spinner; "Waiting for the database (attempt n, elapsed s)".
  - After the policy is exhausted: "Database busy" screen that KEEPS auto-retrying every ~10 s in the background and proceeds into normal startup by itself when the lock clears. The process stays alive (this also stops the 2-minute watchdog from relaunching).
  - Buttons: Retry now; Show details; Start read-only (only if 3.7 shipped and a probe succeeded); Quit.
  - Details: error text, DB path (with "Open folder"), log path, and, if Spike B is go, the lock-holder process names/PIDs from `LockHolderLookup` with plain-language guidance ("close X, or restart Windows; a restart always clears the lock"). No "End process" button (Decision 7).
  - `Failed` result: explain the file is untouched and where it is; offer Retry, Open folder, Quit. No automatic reset.
- [ ] 3.11 Guard against startup leaks: `Bootstrap` services must not start while the gate is showing; the tray icon may be installed so the user can quit.
- [ ] 3.12 Optional hardening: make the watchdog PowerShell check robust (compare by exe path), or at least log; the single-instance guard already makes duplicate launches harmless.

Acceptance
- Locked DB (Phase 0 recipe): `init` returns `Busy` within the policy bound; DB files byte-identical; no `.broken_*` files; no service started; no `UninitializedPropertyAccessException` from any `Database` accessor (typed exception instead).
- Releasing the lock while the gate is open continues startup automatically.
- Second launch focuses the running instance and exits; `RegistryLockdown.disable()` is not reached by the second launch.
- `init()` called twice yields one connection; recomposition cannot re-run startup (verified by the call counter from Phase 0).
- Corrupt-file test: set aside copy exists and verifies; original never deleted without a verified copy; copy failure aborts with `Failed` and the original untouched.
- Wizard JVM path never runs recovery.

### Phase 4: Allowance state, PIN policy, Emergency Break (fixes T4, T7, T8, U3)

Goal: one source of truth for "blocked", PIN-gated loosening, and a correct Emergency Break.

Write these tests first:
- raise limit above usage -> not blocked, `ProcessMonitor.dailyAllowanceBlockedProcesses` updated.
- delete allowance -> block removed and sink updated.
- lower limit below usage -> blocked immediately; kill happens on the next tick/event (not during a break).
- usage keeps counting during a break; crossing the limit during the break marks blocked but does not kill; when `BreakState` goes false the engine kills immediately (no waiting for the next tick).
- break never clears an existing block.
- `AllowanceEditPolicy` classification table (see Test matrix).

Tasks
- [ ] 4.1 Replace `reload()` with `reconcile()`: recompute `blocked = { a | usage(a) >= a.allowanceMinutes*60 }` as a pure function of (allowances, usage), then push to `BlockedSetSink`. `reload(): Result<Unit>`: on store failure keep the old list and return the failure (never throw into the UI thread).
- [ ] 4.2 Emergency Break: engine collects `BreakState.isActive`. While active: accumulate usage, run `reconcile()`, skip kills and skip the "Daily Limit Reached" notification (show it when the break ends if the limit was crossed). On transition to inactive: call `enforceNow()` (kill any blocked, running app immediately). The shared source is `KillSwitchService.isActive` / `ProcessMonitor.killSwitchActive`; do not duplicate state.
- [ ] 4.3 `AllowanceEditPolicy` (suggested `services/allowance/AllowanceEditPolicy.kt`): `classify(old: DailyAllowance?, new: DailyAllowance?)` -> `Add | Tighten | Loosen | Unchanged | Delete`; `requiresPin(change) = change is Loosen || change is Delete`. Pure, no I/O.
- [ ] 4.4 Wire the policy in `AppBlockerScreen.kt` `DailyAllowanceTab`: edit-save and delete go through the existing `PinGateDialog` / `pendingGlobalAction` pattern when `requiresPin` and `GlobalPin.isSet()`. The gate is on Save (not on opening `EditAllowanceDialog`). Title/subtitle: "Global PIN required" / "Enter your Global PIN to raise this daily limit." (delete keeps its current text). Picker `onConfirm`: if the process already has an allowance (case-insensitive), route through the policy instead of silently `INSERT OR REPLACE`-ing a higher value.
- [ ] 4.5 DB-unavailable defense: `GlobalPin.isSet()` returns false when the DB is not ready, which would bypass the gate. Disable Add/Edit/Delete whenever `!Database.isReady` or `Database.mode == READ_ONLY`.
- [ ] 4.6 Normalize process keys in one function (`trim().lowercase()`), used by tracker matching and by `upsertDailyAllowance`. Add `migrateV9()` (never edit older migrations; bump `TARGET_VERSION` to 9): lowercase `daily_allowances.process_name`; on case-variant duplicates keep the STRICTEST (smallest) allowance; merge same-day `daily_usage` rows by lowercase name keeping the MAX seconds. Test the migration on a DB containing `Discord.exe` + `discord.exe`.
- [ ] 4.7 In the picker, if there is a manual-entry path, append `.exe` on Windows when missing (match `FocusSessionService` behaviour).
- [ ] 4.8 Persistence write: change `upsertDailyUsage` to `INSERT ... ON CONFLICT(date, process_name) DO UPDATE SET seconds_used = MAX(seconds_used, excluded.seconds_used)` (check sqlite-jdbc >= 3.24). Totals can no longer decrease from a stale writer.

Acceptance
- All Phase 4 tests pass.
- Manual: block an app, raise its limit with the PIN -> app usable again; without the PIN the raise is refused; lowering needs no PIN; Emergency Break lets a blocked app run for the break and the app is killed immediately when the break ends; after a break the app is still blocked.

### Phase 5: Event-based tracking (hybrid) (fixes T1, H6; supersedes the polling loop)

Goal: exact switch timing without missing short sessions; safe around sleep/lock.

Design: `ForegroundLedger` (pure class, no OS calls). State: `current: Key?`, `openedAtNs`.
- `onForeground(key, nowNs)`: close the open interval (credit `min(nowNs - openedAtNs, MAX_GAP)`; if larger than `MAX_GAP`, discard as suspended), then `current = key; openedAtNs = nowNs`. `key == null` means unknown/none (credited to nobody).
- `heartbeat(nowNs, sampledKey)` every 5-10 s: if `sampledKey != current` count it as a missed event (log) and call `onForeground(sampledKey, nowNs)`; then flush the open interval (credit and set `openedAtNs = nowNs`) so limits trigger and the UI updates.
- Credit goes only to keys with an allowance. Date rollover is checked before every credit (error at midnight <= one heartbeat).

Tasks
- [ ] 5.1 Tests first for the ledger with fake time: A 3 s / B 7 s / A 5 s switches credit exactly; sub-second switches are not lost; missed event corrected by heartbeat (error <= heartbeat interval); null foreground credits nobody; gap > `MAX_GAP` discarded; rollover credits the old day then starts the new day at 0.
- [ ] 5.2 `WinEventHook`: today `start(onForegroundChange)` takes ONE callback and ignores events whose exe name is null. Add a listener registry (`addListener/removeListener`) with an event type `ForegroundEvent(exe: String?, pid: Long, monoNs: Long)`; the hook starts once; `ProcessMonitor` keeps its current behaviour unchanged. Unknown (null) exe must reach the new listeners.
- [ ] 5.3 `ProcessNameResolver.resolve(pid)` (new; do NOT change `getForegroundProcessName()` used by other features): try `ProcessHandle.info().command()`, then fall back to `QueryFullProcessImageNameW` with `PROCESS_QUERY_LIMITED_INFORMATION` through JNA (elevated processes). For `applicationframehost.exe`, look up the child window of class `Windows.UI.Core.CoreWindow` and resolve its owning process; if that fails, treat as unknown and log.
- [ ] 5.4 Engine wiring: hook events -> `ledger.onForeground`; a coroutine heartbeat samples `ForegroundSource.current()` and calls `ledger.heartbeat`. Keep the non-Windows fallback (running-process check) unchanged and clearly commented as a weaker mode.
- [ ] 5.5 Session awareness (P1): lock/unlock and suspend/resume. Use a message-only window on a pump thread with `WTSRegisterSessionNotification` (`WM_WTSSESSION_CHANGE`: lock 0x7, unlock 0x8) and `WM_POWERBROADCAST` (suspend 0x4, resume 0x12). While locked/suspended: `onForeground(null)`. If registration fails, fall back to gap detection (already required) plus `GetForegroundWindow() == NULL` on the lock screen; log the degraded mode.
- [ ] 5.6 Display-off (P2): `RegisterPowerSettingNotification(GUID_CONSOLE_DISPLAY_STATE)`; treat display-off as `null` foreground. Skip if it adds fragile native code; document the limitation instead.
- [ ] 5.7 Persistence cadence: flush dirty keys at most every 60 s, when switching AWAY from a tracked app, on break end, and on `stop()`.
- [ ] 5.8 Remove the old polling credit path after parity tests pass; keep one clearly separated safety poll only if the heartbeat cannot cover its role.
- [ ] 5.9 Diagnostics: a small debug log or hidden diagnostics panel showing current foreground exe, credited seconds per tracked app, missed-event count, discarded gaps. This is how the owner can verify the feature really counts.

Acceptance
- Ledger tests pass; engine tests pass with a fake `ForegroundSource` and fake time.
- Manual Windows checklist (below) passes.
- Documented limitations are written into the PR and the UI help text: foreground is not "active use"; second monitor and background audio are not counted; browser web apps cannot be split by site; idle time counts; a changed system date can reset usage.

### Phase 6: Allowance UX (fixes U1, U2)

Tasks
- [ ] 6.1 Navigation: `AppBlockerScreen(initialTab: Int = 0, ...)` with named tab constants (`TAB_ALWAYS = 0`, `TAB_ALLOWANCE = 1`; verify the other indices) and `selectedTab by remember(initialTab) { mutableStateOf(initialTab) }`. `App.kt`: add `blockerInitialTab` state; `FocusScreen(preloadTask, onOpenAllowances = { blockerInitialTab = TAB_ALLOWANCE; currentScreen = Screen.BLOCK_APPS })`; reset `blockerInitialTab = 0` after it is consumed and when navigating via the side nav.
- [ ] 6.2 Focus screen: make the "Daily Allowance" row clickable (chevron + "Manage"), and show a useful summary from `DailyAllowanceTracker.getUsageSummary()` (e.g. "2 apps - 1 blocked today"). On load failure show "Unavailable - retry", NOT "0 apps".
- [ ] 6.3 Editor states: `sealed interface LoadState { Loading; Loaded; Error(message) }`. Wrap `reload()`, add, edit-save and delete in try/catch (rethrow `CancellationException`). Load error -> inline banner with Retry; spinner never runs forever. Save error -> keep the dialog open and show the message in it; never close on failure.
- [ ] 6.4 Card consistency: when blocked show "Blocked until midnight" and remaining = 0; when an Emergency Break is active show "Emergency Break active - limit not enforced right now"; in read-only mode show "Database busy - read-only, changes disabled" and disable editing (ties to 4.5).
- [ ] 6.5 Same "couldn't load" handling in `DashboardScreen` (~line 95), `ActiveScreen` (~line 62), `ProfileScreen` (~line 259).
- [ ] 6.6 i18n: `AppStrings`/`Translations.kt` has 7 languages in one `translations` map plus the wrapper properties. Check whether the `AppStrings` constructor has default values. Add new strings the same way as neighbouring ones in each touched file (some screens use hardcoded English; follow the local convention, and if you add keys add them to all 7 language blocks so it compiles).

Acceptance
- From Focus, one click opens Blocker -> Daily Allowance.
- With the DB unavailable the editor shows an error with Retry, edit controls are disabled, and nothing hangs or crashes.
- A failed save shows a message and keeps the dialog open.
- Raising a limit requires the PIN (when set); blocked/remaining/break messages are consistent.

### Phase 7: Final verification and cleanup

- [ ] 7.1 Run the full test matrix and the manual Windows checklist.
- [ ] 7.2 Remove temporary spikes/logging; keep permanent diagnostics low-noise.
- [ ] 7.3 Add a ChangelogScreen entry summarizing user-visible fixes (startup recovery screen, PIN to raise/delete, more accurate counting).
- [ ] 7.4 Write the PR summary: findings confirmed/dropped, decisions, limitations, rollback notes (each phase is independently revertable).

## 6. Test matrix

Database / startup (temp dir, short `InitPolicy`)
- BUSY at startup: DB in DELETE journal mode + second connection `BEGIN EXCLUSIVE` -> `Busy` within bound; files byte-identical; no `.broken_*`; no services started.
- Lock released during retry -> `Ready`, services start once.
- BUSY/LOCKED/IOERR/CANTOPEN/FULL are never classified as corruption (table test of the classifier incl. extended codes).
- Corrupt file -> verified copy set made, originals moved (not deleted), fresh DB created; copy failure -> `Failed`, original untouched.
- Failed migration -> `isReady == false`, `conn == null`, no closed connection left behind.
- `init()` twice -> one connection (idempotent); concurrent `init()` calls -> one wins.
- Missing connection: every `Database` accessor before `init` throws `DatabaseUnavailableException` (or returns the documented default for the guarded methods), never `UninitializedPropertyAccessException`; `DailyAllowanceTracker.start()` with unavailable store does not throw and does not start the loop.
- Wizard path (`allowRecovery = false`) never moves or deletes files.
- Single instance: second `acquire()` fails and handoff message is received; lock released when the holder exits.
- Read-only mode (if shipped): reads work, writes throw/no-op, usage buffered and flushed after upgrade to read-write.

Tracker / ledger (fake clock, fake foreground, fake killer, fake break state, in-memory store)
- Continuous 60 s foreground -> 60 s +/- 1 s.
- A/B/A switching exact; short sessions counted; null foreground credits nothing.
- First allowance after 5 h uptime -> no instant block; 8 h sleep gap -> credit 0; clock moved backwards -> no negative credit, date not moved backwards.
- Exception in store/killer/tick -> loop survives.
- Limit reached -> blocked, killer called for that process only, notification once.
- Reconcile: raise unblocks, lower blocks, delete unblocks; sink updated each time.
- Emergency Break: no kills during break, usage still counted, block survives the break, immediate kill when the break ends, budget untouched.
- `AllowanceEditPolicy`: table of (old,new) -> Add / Tighten / Loosen / Unchanged / Delete and `requiresPin`.
- Save/reload: upsert then `reload()` -> engine sees the new value; failed `reload()` keeps the old list and returns failure.
- Restart persistence: flush, build a new engine on the same store -> usage restored, blocked set recomputed from new limits; crash without flush loses at most one flush interval; stale writer cannot lower a total (`MAX` upsert).
- Daily reset: crossing midnight flushes the old date, new day starts at 0, blocked cleared, sink cleared; resume after midnight; DST days; migration v9 dedupe test.

Manual Windows checklist (record results in the PR)
- Two launches at once -> second exits and focuses the first; Nuclear/registry lockdown unaffected.
- Real locked DB (hold with an external tool) -> gate appears, retries, continues when released.
- Tracked app switching with Alt+Tab; Store app (ApplicationFrameHost); elevated app; two monitors; lock/unlock; sleep/resume; display off; Emergency Break during a blocked app; midnight rollover (or fake via a debug date).
- Compare displayed minutes against a stopwatch over 15 minutes of mixed use; expected error under 1 minute.

## 7. Definition of done

- No `UninitializedPropertyAccessException` reachable from startup, UI or services.
- A locked DB never causes data loss, never silently starts the app with empty settings/open PIN gates, and never leaves the user without a way forward.
- Allowance usage cannot jump by hours (sleep, empty allowance list, early returns) and the counting loop cannot die silently.
- Editing flow: raise/delete need the PIN, changes take effect immediately and consistently, failures are visible.
- Emergency Break semantics as in Decision 4.
- All tests green; manual checklist complete; limitations documented.

## 8. Appendix: code map

- `Main.kt` - startup (lines ~50-100 DB init and service start; `application {}` body); `doShutdown` ~line 163.
- `data/Database.kt` - `init` / `tryOpenAndMigrate` / `safeBackupBrokenDb` / `migrate` (~lines 1-200), allowances (~819-851), usage (~854-889), `TARGET_VERSION = 8`.
- `services/DailyAllowanceTracker.kt` - tracker (lines 57-205 are the logic to fix).
- `enforcement/WinEventHook.kt` - foreground hook; `enforcement/ProcessMonitor.kt` - `killSwitchActive` (91), `dailyAllowanceBlockedProcesses` (100), `isAnyEnforcementActive` (~353), hook start (~390).
- `enforcement/WinApiBindings.kt` - `getForegroundProcessName()` (~100), `killProcessByName` (~129).
- `enforcement/KillSwitchService.kt` - Emergency Break (5 min/day budget, `isActive`, `isExhausted`, `activate/deactivate`).
- `enforcement/WatchdogInstaller.kt`, `WindowsStartupManager.kt` - relaunch paths.
- `services/UninstallProtectionService.kt` (~47), `services/GlobalPin.kt` (reads PIN via guarded `getSetting`), `services/AutoBackupService.kt` (uses `VACUUM INTO`).
- `ui/screens/AppBlockerScreen.kt` - `DailyAllowanceTab` (~914), `AllowanceCard` (~1124), `AllowancePickerDialog` (~1289), `EditAllowanceDialog` (~1660).
- `ui/screens/FocusScreen.kt` (~107 load, ~1044 row), `DashboardScreen.kt` (~95, ~571), `ActiveScreen.kt` (~62), `ProfileScreen.kt` (~259).
- `App.kt` (~302 navigation), `data/models/Models.kt` (`Screen` enum line 165, `DailyAllowance` line 106), `i18n/*`.
