# FocusFlow Reliability Fixes — Batch Tracker

**Source plan:** [FOCUSFLOW_IMPLEMENTATION_PLAN.md](FOCUSFLOW_IMPLEMENTATION_PLAN.md)  
**Overall status:** Batch 1 complete — Batch 0 Windows-only limitations remain recorded
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

**Status:** Not started · **Plan section:** Phase 3

- [ ] Add deterministic busy-lock and failure-classification tests.
- [ ] Replace uninitialized connection state with explicit unavailable/readiness state.
- [ ] Publish the connection only after migrations succeed; close failed local connections.
- [ ] Make initialization synchronized/idempotent, retry BUSY/LOCKED within policy, and expose startup state.
- [ ] Restrict recovery to confirmed corruption; preserve and verify the complete DB/WAL/SHM set before moving originals.
- [ ] Prevent the uninstall wizard from running recovery.
- [ ] Implement read-only mode only if Phase 0 spike supports it.
- [ ] Add a single-instance guard before startup side effects.
- [ ] Move startup side effects out of Compose recomposition and run bootstrap only once after database readiness.
- [ ] Add the retryable startup gate and guard services from running before readiness.
- [ ] Verify retry recovery, second-instance behavior, migration failure, data preservation, and wizard behavior.

**Acceptance:** Locked DB causes no data loss, no uninitialized-connection crash, and no dependent services running with an unavailable database; releasing the lock allows startup to continue.

## Batch 4 — Allowance state, PIN policy, and Emergency Break

**Status:** Not started · **Plan section:** Phase 4

- [ ] Add failing reconciliation, edit-policy, and break-behavior tests.
- [ ] Reconcile tracked allowances and blocked processes after every successful reload/change.
- [ ] Implement shared allowance edit classification and Global PIN policy.
- [ ] Make add/edit/delete unavailable while the database is unavailable or read-only.
- [ ] Normalize process keys and migrate case-variant allowance/usage rows safely.
- [ ] Make persisted usage updates resistant to stale writers.
- [ ] Verify limit raise/lower/delete, break, and migration behavior.

**Acceptance:** Raising/deleting follows the PIN policy, blocked state updates immediately, and Emergency Break pauses kills without clearing blocks or stopping usage counting.

## Batch 5 — Hybrid event-based tracking

**Status:** Not started · **Plan section:** Phase 5

- [ ] Add pure foreground-ledger tests for switching, short sessions, null foreground, missed events, long gaps, and date rollover.
- [ ] Add WinEventHook listeners without changing existing ProcessMonitor behavior.
- [ ] Resolve foreground process names robustly, including elevated and Store-hosted apps.
- [ ] Wire foreground events and heartbeat into the engine; retain/document the weaker non-Windows fallback.
- [ ] Handle lock/unlock and suspend/resume; assess display-off support and document any omission.
- [ ] Flush on app switch, break end, periodic interval, and stop.
- [ ] Remove the old polling-credit path only after parity tests pass.
- [ ] Add diagnostics for foreground process, credited usage, missed events, and discarded gaps.
- [ ] Complete the Windows accuracy and limitation checks.

**Acceptance:** Ledger/engine tests pass; Windows manual checklist passes; counting limitations are documented.

## Batch 6 — Allowance UX

**Status:** Not started · **Plan section:** Phase 6

- [ ] Add navigation from Focus to Blocker → Daily Allowance.
- [ ] Show meaningful usage/status and unavailable/retry state instead of false zero values.
- [ ] Add explicit loading/error states to allowance loading and save/delete flows.
- [ ] Keep failed-save dialogs open with clear errors; disable changes when DB is unavailable/read-only.
- [ ] Make blocked, remaining, and Emergency Break messages consistent.
- [ ] Add matching load-error behavior to Dashboard, Active, and Profile screens.
- [ ] Add/update translated strings consistently across all supported languages.
- [ ] Verify a user can reach, edit, save, and recover from failed allowance operations.

**Acceptance:** Focus navigation reaches the editor; database failures are visible; no spinner hangs or silent failed saves.

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
