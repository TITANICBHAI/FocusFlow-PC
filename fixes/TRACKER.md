# FocusFlow Reliability Fixes — Batch Tracker

**Source plan:** [FOCUSFLOW_IMPLEMENTATION_PLAN.md](FOCUSFLOW_IMPLEMENTATION_PLAN.md)  
**Overall status:** Not started  
**Rule:** Work one batch at a time. Tick these items and the matching task checkboxes in the source plan as work is completed. Record evidence before marking a batch complete.

## Batch 0 — Evidence and spikes

**Status:** Not started · **Plan section:** Phase 0

- [ ] Record startup/tracker diagnostics and investigate repeated database initialization.
- [ ] Collect crash/enforcement logs from the owner when available.
- [ ] Document safe Windows checks for identifying the database lock holder.
- [ ] Test read-only WAL access and Restart Manager lock-holder lookup; record go/no-go.
- [ ] Reproduce `SQLITE_BUSY` deterministically without touching the user's database.
- [ ] Verify installed process name and watchdog matching.
- [ ] Record ranked causes, evidence, and temporary-logging cleanup.

**Acceptance:** Findings and spike results are recorded; temporary diagnostics are removed or reduced to low-noise permanent logging.

## Batch 1 — Test seams

**Status:** Not started · **Plan section:** Phase 1

- [ ] Identify current build/JDK/Compose/SQLite/test versions and test source set.
- [ ] Add or confirm test infrastructure.
- [ ] Add injectable database initialization/test reset seams.
- [ ] Add tracker ports for clock, foreground source, killer, break state, usage store, and blocked-set sink.
- [ ] Extract the allowance engine while preserving the existing public tracker API.
- [ ] Verify behavior is unchanged and a fake-port test runs on Linux.

**Acceptance:** Build is green; smoke test works; engine test runs with fake ports.

## Batch 2 — Tracker hotfix

**Status:** Not started · **Plan section:** Phase 2

- [ ] Add failing tests for long uptime, sleep gaps, null foreground, loop exceptions, and cumulative timing.
- [ ] Bound credited time and update timing state before any early return.
- [ ] Track sub-minute usage without per-tick integer truncation.
- [ ] Keep the loop alive after recoverable tick/store/killer errors.
- [ ] Retry failed persistence writes and avoid starting when the usage store is unavailable.
- [ ] Prevent tracking dates from moving backwards; assess optional process-scan reduction.
- [ ] Verify a newly added allowance does not inherit hours of past runtime.

**Acceptance:** New tests pass; normal tracking works; first allowance after long uptime starts near zero and does not block immediately.

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
| — | — | No implementation work started. | — | Not started |
