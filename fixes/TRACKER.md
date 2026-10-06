# FocusFlow Reliability Fixes — Batch Tracker

**Source plan:** [FOCUSFLOW_IMPLEMENTATION_PLAN.md](FOCUSFLOW_IMPLEMENTATION_PLAN.md)  
**Overall status:** Batches 1–2 complete; Batch 3 implementation present with verification gaps; Batch 4 implementation and automated checks complete, with interactive/Windows acceptance pending; Batch 5 implementation and Linux automated verification complete, with Windows manual acceptance pending.
**Rule:** Work one batch at a time. Tick these items and the matching task checkboxes in the source plan as work is completed. Record evidence before marking a batch complete.

## Batch 0 — Evidence and spikes

**Status:** Complete with limitations · **Plan section:** Phase 0

- [x] Record startup/tracker diagnostics and investigate repeated database initialization.
- [x] Owner confirmed the original crash/enforcement log files are unavailable; only truncated exception excerpts were supplied, so timing and holder identification remain unknown.
- [x] Document safe Windows checks for identifying the database lock holder.
- [ ] **BLOCKED — Windows host:** Verify read-only WAL access and Restart Manager lock-holder lookup on Windows; current recommendation is no-go for shipping either optional capability until then.
- [x] Reproduce `SQLITE_BUSY` deterministically in scratch files without touching the user's database.
- [ ] **BLOCKED — Windows host:** Confirm the installed process name and watchdog matching.
- [x] Record ranked causes, evidence, and temporary-logging cleanup.

**Acceptance:** Findings and available spike outcomes are recorded and temporary diagnostics are removed. Windows-specific test items remain explicitly blocked/no-go and must be validated before any related feature is shipped.

### Batch 0 evidence — 2026-10-06

**Environment:** Linux x86_64; GraalVM JDK 19.0.2; Gradle 8.14.2; Kotlin 1.9.22; Compose Desktop 1.6.1; sqlite-jdbc 3.47.1.0. No test source set or test dependencies are currently configured.

**Runtime startup/recomposition probe:** Temporary `EnforcementLog` instrumentation recorded init count, caller stack, WAL pragma result, open duration, and tracker tick timing. The app was run with a fresh `/tmp` home, never the user's home/database. An idle run recorded one init and `PRAGMA journal_mode=WAL result=wal`. A separate temporary Compose pulse forced three invalidations at one-second intervals: logs showed init calls 1–4, with calls 2–4 entering through `RecomposeScopeImpl.compose`. Each repeated call reopened/migrated the same scratch DB (subsequent attempts completed in 7–12 ms). This confirms H3 as a runtime startup-repeat/connection-leak risk. `FocusSessionService` also updates the state collected by `Main.kt` every second during an active session, providing a normal trigger for this recomposition path. The temporary code was removed.

**Tracker diagnostics:** The no-allowance isolated run recorded successive ticks approximately 10 seconds apart, with zero credited seconds. On Linux the tracker uses its weaker running-process fallback and has no foreground executable name. A run with a scratch `sleep` allowance produced one logged tick (`foreground=unknown`); this was insufficient to establish Windows foreground timing, so tracker foreground/credit accuracy still needs a Windows run.

**Deterministic BUSY reproduction:** In a new `/tmp` SQLite database using sqlite-jdbc 3.47.1.0, the first connection held `BEGIN EXCLUSIVE` in default DELETE journal mode. A second connection used the current driver's 10,000 ms busy timeout and attempted `PRAGMA journal_mode=WAL`. It returned `SQLITE_BUSY` (error code 5) after 10,027 ms. The database SHA-256 was unchanged. No project/user database was opened or modified. This confirms the busy handler waits for the configured interval and that an exclusive holder can reproduce the reported failure.

**Owner-provided failure evidence:** The submitted note contained truncated stack excerpts (no log-file attachments or timestamps). It confirms `SQLITE_BUSY` at `Database.tryOpenAndMigrate` on `PRAGMA journal_mode=WAL`, called from `Database.init()` in `Main.kt`, followed by `UninitializedPropertyAccessException` at `Database.getDailyAllowances()` when `DailyAllowanceTracker.start()` runs. This matches the causal chain in the plan. It does not reveal how long the busy handler waited, whether there were repeated attempts, or which process held the lock; the complete `crash.log` and `enforcement.log` are still needed.

**Spike A — read-only WAL:** Linux smoke test passed: a read-only connection opened the WAL database while a writer held an uncommitted write transaction, read the last committed row, then saw the new row after commit. This does not answer the plan's Windows question. **No-go for shipping read-only startup until the same test passes on Windows.**

**Spike B — Restart Manager:** Not run; this environment is Linux and cannot call `rstrtmgr.dll`. **No-go for adding holder details until validated on Windows** with the database/WAL/SHM resource set.

**Safe Windows lock-holder checks:** Open Resource Monitor → CPU → Associated Handles and search `focusflow.db` (also search `focusflow.db-wal`). In PowerShell, inspect rather than terminate candidates:

```powershell
Get-Process -Name FocusFlow,java,javaw -ErrorAction SilentlyContinue |
  Select-Object Id, ProcessName, Path, StartTime
Get-Item "$HOME\.focusflow\focusflow.db*" -ErrorAction SilentlyContinue |
  Select-Object Name, Length, LastWriteTime
```

Do not kill a suspected holder or delete/rename `focusflow.db`, `-wal`, or `-shm`. Close a known app normally. For SQLite inspection, work on a copy of the database and its sidecars taken only after the owning process has closed; run `sqlite3 <copy> "PRAGMA journal_mode"` on that copy, never on the live database.

**Process/watchdog check:** The package name is `FocusFlow`, so the packaged launcher is expected to be `FocusFlow.exe`; the watchdog checks `Get-Process -Name 'FocusFlow'` (PowerShell omits `.exe` from the process name), which matches that launcher. `WindowsStartupManager` explicitly allows the running JVM command to be `runtime\bin\java.exe`, however, so the actual process name under an installed Windows launch remains unverified and the watchdog check may not see a java/javaw-only process. Windows verification is pending.

**Ranked likely causes (provisional; only truncated failing-launch excerpts supplied):**
1. A live SQLite lock during the journal-mode transition is confirmed by the exception. The holder is unidentified; overlapping/manual/Run-key/watchdog launches or the separate uninstall-wizard JVM are possible, and there is no single-instance guard.
2. Confirmed repeated `Database.init()` during recomposition opens extra connections and repeats startup work. This is a definite defect and contention amplifier, but the scratch run does not prove it caused the reported lock.
3. External lock holders (antivirus, sync software, or a DB viewer) remain plausible but unobserved.
4. A previous instance still running during shutdown is possible; current teardown does not call `Database.close()`. No log evidence establishes this.

**Pending checks:** Windows host needed for read-only WAL, Restart Manager, and installed process-name checks. Owner confirmed the original failure logs are unavailable, so the wait time and lock holder cannot be determined from the supplied excerpt.

## Batch 1 — Test seams

**Status:** Complete · **Plan section:** Phase 1

- [x] Identify current build/JDK/Compose/SQLite/test versions and test source set.
- [x] Add or confirm test infrastructure.
- [x] Add injectable database initialization/test reset seams.
- [x] Add tracker ports for clock, foreground source, running-process source, killer, break state, usage store, and blocked-set sink.
- [x] Extract the allowance engine while preserving the existing public tracker API.
- [x] Verify behavior is unchanged and a fake-port test runs on Linux.

**Acceptance:** Build and tests are green; isolated app startup and usage persistence smoke passed; engine tests run with fake ports.

### Batch 1 evidence — 2026-10-06

- Added JUnit 5/kotlin-test, `kotlinx-coroutines-test`, and JUnit temporary-directory support.
- `Database.init()` retains no-argument Kotlin use while accepting an isolated DB file, busy-timeout policy, and recovery flag. `resetForTest()` closes and clears the connection. Busy logs for custom DB paths stay beside that test DB rather than under the user's home.
- Extracted tracker state and tick behavior into `AllowanceEngine`; `DailyAllowanceTracker` remains a facade with its existing UI-facing methods and production adapters.
- Added `RunningProcessSource` beyond the original port list so process enumeration can be faked. This keeps engine tests deterministic and preserves the existing skip-tick behavior if enumeration fails.
- `gradle test --no-daemon`: 3 tests passed, 0 failed. Tests cover a scratch SQLite init/reset and engine start plus credited/persisted usage using fake ports.
- Isolated runtime smoke: launched `gradle :run` with `user.home` set to a fresh `/tmp` directory. Inserted a test `python3.13` allowance only into that scratch DB, ran a matching long-lived process, and observed a `daily_usage` row with 41 seconds after about 68 seconds. The original app workflow was not started against the user's real home/database.
- A first Linux process-name probe using `sleep` did not match because this Nix environment reports its command path as `coreutils`; the smoke was repeated with the separately named Python executable. This is an environment/process-name observation, not a Windows validation.

## Batch 2 — Tracker hotfix

**Status:** Implementation and automated acceptance complete; Windows manual check pending · **Plan section:** Phase 2

- [x] Cover long uptime, sleep gaps, null foreground, loop/store/killer failures, and cumulative timing with deterministic tests.
- [x] Bound credited time and update timing state before any early return.
- [x] Track sub-minute usage without per-tick integer truncation.
- [x] Keep the loop alive after recoverable tick/store/killer errors.
- [x] Retry failed persistence writes and avoid starting when the usage store is unavailable.
- [x] Prevent tracking dates from moving backwards; skip the Windows process scan unless a blocked app needs killing.
- [x] Verify a newly added allowance does not inherit hours of past runtime, including after a failed process scan.

**Automated acceptance:** Regression tests pass; normal tracking remains covered; the first allowance after long uptime credits only the next ~10-second interval and remains unblocked.
**Remaining verification:** A manual Windows foreground-app check could not be run in this Linux environment and remains pending.

### Batch 2 evidence — 2026-10-06

- Reviewed the existing `AllowanceEngine` implementation against Phase 2. The elapsed-time cap, millisecond accumulation, per-row pending-write retry, store availability guard, loop error handling, monotonic date advancement, and optional Windows scan reduction were already implemented.
- Tightened the first-allowance regression to assert persisted usage is 9–11 seconds after the allowance is added and that the process is not blocked.
- Added a regression where process enumeration returns unavailable across a five-hour gap, then recovers; only the next 10-second interval is credited and no block is set.
- Targeted verification: `gradle test --tests 'com.focusflow.services.allowance.AllowanceEngineTest' --no-daemon` passed after the test additions.
- Full suite: `gradle test --no-daemon` passed. `git diff --check` passed after removing trailing whitespace from this tracker entry. No user database was opened or modified.

## Batch 3 — Database startup safety and recovery gate

**Status:** Implementation present; core automated checks pass; end-to-end and Windows evidence remain limited · **Plan section:** Phase 3

- [x] Add deterministic busy-lock and failure-classification tests.
- [x] Replace uninitialized connection state with explicit unavailable/readiness state.
- [x] Publish the connection only after migrations succeed; close failed local connections.
- [x] Make initialization synchronized/idempotent, retry BUSY/LOCKED within policy, and expose startup state.
- [x] Restrict recovery to confirmed corruption; preserve and verify the complete DB/WAL/SHM set before moving originals.
- [x] Prevent the uninstall wizard from running recovery.
- [ ] **NOT SHIPPED — Windows Spike A is still no-go.** Read-only startup remains disabled pending Windows WAL validation.
- [x] Add a single-instance guard before startup side effects.
- [x] Move startup side effects out of Compose recomposition and run bootstrap only once after database readiness.
- [x] Add the retryable startup gate and guard services from running before readiness.
- [x] Verify DB retry, second-instance handoff, migration failure, corruption preservation, and no-recovery initialization.

**Acceptance status:** Core source and automated DB/guard tests support the locked-DB safety path. The Main.kt gate-to-service path is implemented and retries automatically, but has no dedicated end-to-end UI test. Windows-only checks and Spike A/B remain blocked; do not claim those as verified.

### Batch 3 evidence — 2026-10-06 repository audit

- `DatabaseTest` covers typed unavailable access, SQLite failure classification, unchanged DB files on BUSY, successful initialization after lock release, migration failure without publishing a closed connection, verified DB/WAL/SHM recovery copies, copy failure preserving originals, and recovery-disabled initialization.
- `SingleInstanceGuardTest` covers SHOW handoff, lock release, and an unresponsive holder. `StartOnceTest` covers once-only bootstrap and remembered failures.
- `Main.kt` acquires the instance guard before pre-DB side effects, runs DB initialization in a `LaunchedEffect` on IO, retries BUSY after 10 seconds, and calls `StartupBootstrap.startServices()` only on Ready. `StartupBootstrap` wraps service startup in `StartOnce`.
- Full suite currently passes 38 tests. The app-level gate-to-service transition was verified by source inspection, not an end-to-end UI test. Windows checks remain unavailable in this Linux environment.

## Batch 4 — Allowance state, PIN policy, and Emergency Break

**Status:** Implementation and automated checks complete; interactive UI/PIN and Windows acceptance pending · **Plan section:** Phase 4

- [x] Add failing reconciliation, edit-policy, and break-behavior tests before the behavior changes.
- [x] Reconcile tracked allowances and blocked processes after every successful reload/change.
- [x] Implement pure allowance edit classification and PIN-required decision.
- [x] Disable allowance mutations until database readiness and Global PIN state are known; fail closed on unavailable database/write errors. No `Database.mode`/read-only mode is exposed (read-only startup remains no-go).
- [x] Normalize process keys and migrate case-variant allowance/usage rows safely.
- [x] Make persisted usage updates resistant to stale writers.
- [x] Wire the PIN policy into edit-save, delete, and case-insensitive picker collisions; normalize manual entry and append `.exe` only on Windows.
- [ ] Verify the complete UI/PIN flow and Windows manual behavior.

**Acceptance status:** The engine, policy, database changes, editor PIN routing, and fail-closed DB readiness guard are implemented; the full suite passes. Interactive UI/PIN behavior and the Windows blocking/Emergency Break checklist have not been run here, so Batch 4 is not accepted and Batch 5 must not start yet.

### Batch 4 evidence — 2026-10-06

- Four engine regressions were run before fixes and failed for raise/delete reconciliation, lowering a limit, and Emergency Break enforcement.
- Added tests for failed reload retaining the previous allowance list, deferred limit notification during a break, and preserving the block after the break.
- Added pure edit-policy classification/PIN tests and SQLite tests for v9 case-variant merging, normalized upsert keys, and non-decreasing usage totals.
- `gradle test --no-daemon`: 38 tests passed, 0 failures/errors. `git diff --check` passed.
- Initial audit identified missing `AppBlockerScreen` PIN-on-save/delete wiring and DB-unavailable disabling; the follow-up below records their implementation. Windows manual acceptance remains pending.

### Batch 4 implementation follow-up — 2026-10-06

- `DailyAllowanceTab` classifies changes through the pure policy: adding and tightening remain ungated; raising an existing limit and deleting require the configured Global PIN. PIN is requested on Save/delete, not when opening Edit. Picker collisions compare normalized process names and cannot silently replace a higher allowance.
- Add/Edit/Delete controls stay disabled until the database is ready and the Global PIN state has loaded. Each write rechecks DB readiness on IO; errors fail closed. `Database.mode`/read-only is not implemented or exposed, consistent with the Phase 0 read-only no-go; any future read-only state must be added to this guard.
- Manual picker process names are normalized and receive `.exe` only on Windows. Unit coverage now includes gate decisions, DB/PIN readiness gating, process-name case/whitespace, and the platform-specific suffix.
- `gradle test --no-daemon`: 42 tests passed, 0 failures/errors. `git diff --check` passed.
- The configured desktop workflow was not launched because it starts FocusFlow against the normal user profile/database. No user database was opened. Interactive Windows app-blocking, PIN, and Emergency Break checks remain pending in a Windows environment.

## Batch 5 — Hybrid event-based tracking

**Status:** Implementation and Linux automated verification complete; Windows manual acceptance pending · **Plan section:** Phase 5

**Audit updated — 2026-10-06:** The current repository already contains most Phase 5 work that an earlier audit marked missing: foreground ledger and deterministic tests, nullable listener registry, elevated/Store-host resolver, event+heartbeat engine wiring, lock/suspend monitor, flush cadence, and diagnostics. That earlier audit/work-log entry is retained as history of the previous snapshot, not current status. This pass added a missing break-end flush for the still-open foreground interval. Display-off monitoring is intentionally omitted and the limitation is documented in all seven allowance-help translations.

- [x] Add pure foreground-ledger tests for switching, short sessions, null foreground, missed events, long gaps, and date rollover.
- [x] Add WinEventHook listeners without changing existing ProcessMonitor behavior; unknown foreground events reach listeners.
- [x] Resolve foreground process names robustly, including elevated and Store-hosted apps.
- [x] Wire foreground events and heartbeat into the engine; retain and document the weaker non-Windows fallback.
- [x] Handle lock/unlock and suspend/resume; assess display-off support and document the omission.
- [x] Flush on app switch, break end, periodic interval, and stop.
- [x] Remove the Windows polling-credit path after parity tests; retain only the non-Windows/event-unavailable fallback.
- [x] Add diagnostics for foreground process, credited usage, missed events, and discarded gaps.
- [ ] Complete the Windows foreground-accuracy and manual behavior checklist.

**Acceptance:** Ledger/engine tests pass and counting limitations are documented. Windows manual acceptance is still pending; Phase 5 must not be represented as fully accepted until that checklist passes.

### Batch 5 work log — 2026-10-06

- Began Phase 5 after an audit that found existing ports and bounded polling but did not find the Phase 5 event-accounting implementation in that snapshot.
- Added failing-first pure ledger tests for app switching, sub-second intervals, null foreground, heartbeat correction, long/backwards gaps, and day rollover.
- Batch 4's interactive Windows acceptance is still pending. The user explicitly asked to proceed with Batch 5; Windows-only verification remains a release blocker and will be recorded as blocked rather than inferred from Linux tests.
- The initial audit/work-log assessment above was stale relative to the current source and is superseded by the verification update below.

### Batch 5 verification update — 2026-10-06

- Confirmed the ledger, listener hub, resolver, session-state monitor, engine integration, flush cadence, diagnostics, and seven-language limitation text were already present in the current source. The earlier “not implemented” audit was stale.
- Added `endingEmergencyBreakFlushesTheOpenForegroundInterval`; before the fix it failed because only 30 of 35 seconds had been persisted. The engine now closes and credits the open foreground interval before resuming enforcement and persisting at break end.
- Focused tests for `ForegroundLedgerTest`, `AllowanceEventTrackingTest`, `ForegroundEventHubTest`, `ProcessNameResolverTest`, and `SessionActivityGateTest` passed. Full `gradle test --no-daemon --console=plain` passed: 59 tests, 0 failures, 0 errors, 0 skipped. `git diff --check` passed.
- No desktop app was launched, so the user's normal database was not opened. Windows-only accuracy and interaction checks remain unverified in this Linux environment.

## Batch 6 — Allowance UX

**Status:** In progress · **Plan section:** Phase 6

- [ ] Add navigation from Focus to Blocker → Daily Allowance.
- [ ] Show meaningful usage/status and unavailable/retry state instead of false zero values.
- [ ] Add explicit loading/error states to allowance loading and save/delete flows.
- [ ] Keep failed-save dialogs open with clear errors; disable changes when DB is unavailable/read-only.
- [ ] Make blocked, remaining, and Emergency Break messages consistent.
- [ ] Add matching load-error behavior to Dashboard, Active, and Profile screens.
- [ ] Add/update translated strings consistently across all supported languages.
- [ ] Verify a user can reach, edit, save, and recover from failed allowance operations.

**Acceptance:** Focus navigation reaches the editor; database failures are visible; no spinner hangs or silent failed saves.

### Batch 6 work log — 2026-10-06

- Started Batch 6 at the user's direction. Current audit confirms the editor exists, but Focus has no navigation callback, load failures are silently swallowed or can leave the editor in an ambiguous empty state, write failures have no visible feedback, and Dashboard/Active/Profile can show stale or empty allowance data without a retry action.
- Phase 4 already disables allowance mutations until DB and Global PIN state are known. Read-only startup is not shipped, so UI writes must continue to fail closed on `Database.isReady == false`; no read-only state will be invented.

## Batch 7 — Final verification and cleanup

**Status:** Not started · **Plan section:** Phase 7

- [ ] Run the full automated test matrix.
- [ ] Run the manual Windows checklist and record results.
- [ ] Remove temporary diagnostics and keep useful permanent diagnostics low-noise.
- [ ] Add the user-facing changelog entry.
- [ ] Document confirmed/dropped findings, decisions, limitations, verification, and rollback notes.

**Acceptance:** Definition of done in the source plan is met; verification evidence is recorded.

## Work log

Add an entry whenever work starts or finishes on a batch. Include evidence for completed checks; do not mark a checkbox complete based only on code inspection when runtime/test evidence is required.

| Date | Batch | Work completed / findings | Verification evidence | Status |
|---|---|---|---|---|
| 2026-10-06 | 0 | Temporary diagnostics added, exercised in isolated homes, then removed. H3 confirmed under forced recomposition. Owner confirmed full logs are unavailable; supplied exception excerpt confirms the startup failure chain. Safe Windows inspection steps and ranked causes documented. Linux read-only WAL smoke test passed; Windows-specific checks remain no-go until validated. | `gradle compileKotlin` passed with instrumentation; isolated Compose run showed four init calls; scratch sqlite-jdbc 3.47.1.0 exclusive-lock test returned code 5 after 10,027 ms with unchanged DB hash; Linux read-only WAL reader passed; excerpt matches BUSY-at-WAL then uninitialized tracker connection. | Complete with limitations |
| 2026-10-06 | 1 | Added test infrastructure, injectable Database initialization/reset, tracker ports, and the `AllowanceEngine`; kept `DailyAllowanceTracker` as the production facade. Added deterministic process enumeration seam after Nix's `sleep` alias could not be identified reliably. | `gradle test --no-daemon`: 3 passed; isolated app launch with scratch DB recorded 41 seconds of `python3.13` allowance usage; `git diff --check` passed. No user DB accessed. | Complete |
| 2026-10-06 | 2 | Audited existing tracker hotfix implementation; strengthened first-allowance and process-enumeration-gap regressions. | Targeted allowance-engine tests and full `gradle test --no-daemon` passed; `git diff --check` passed. Windows manual foreground-app verification unavailable in Linux. No user DB accessed. | Automated complete; manual check pending |
| 2026-10-06 | 3 | Reconciled the stale “not started” tracker entry with existing startup/database code and tests. Fixed two compile errors in the startup gate so the project suite could run. Read-only startup remains intentionally absent pending Windows Spike A. | Full `gradle test --no-daemon`: 38 passed, 0 failed/errors; `git diff --check` passed. DB/guard/start-once cases are in `DatabaseTest`, `SingleInstanceGuardTest`, and `StartOnceTest`. Gate-to-service behavior was source-inspected but not exercised end-to-end. | Implementation present; integration/Windows evidence limited |
| 2026-10-06 | 4 | Added failing-first tests, then implemented engine reconciliation, edit policy, Emergency Break pause/resume behavior, process-key normalization, v9 deduplication, and monotonic usage persistence. UI PIN wiring and DB-unavailable action disabling remain unfinished. | Four targeted regressions failed before fixes; full suite after implementation: 38 passed, 0 failed/errors; `git diff --check` passed. No Windows manual tests run. | In progress |
| 2026-10-06 | 5 | Audited current source, completed the missing break-end ledger flush, and corrected the stale earlier audit. Linux-verifiable implementation is complete; Windows accuracy validation is still pending. | Five focused Phase 5 test classes passed; full suite: 59 passed, 0 failed/errors; `git diff --check` passed. No desktop app launched and no user DB opened. | Implementation/automated checks complete; Windows manual acceptance pending |
