# Batch 7 — Final verification and cleanup

**Date:** 2026-10-06  
**Status:** Linux-verifiable work complete; Windows/manual acceptance remains blocked.  
**Release preparation:** v2.0.2 changelog and version references are aligned; nothing was published.

The consolidated Windows and interactive runbook is [WINDOWS_VERIFICATION_GUIDE.md](WINDOWS_VERIFICATION_GUIDE.md). It covers outstanding checks across Batches 0–7 and distinguishes runnable app checks from engineering spikes that need a probe first.

## Findings

### Confirmed

- The supplied exception excerpt shows the causal sequence `SQLITE_BUSY` during database startup followed by an uninitialized database connection being used by allowance startup. The owner's original crash and enforcement logs were unavailable, so the excerpt does not identify who held the database lock.
- H3 was reproduced in an isolated home: forced Compose invalidations caused four `Database.init()` calls (one initial call and three recompositions).
- A scratch database held with an exclusive lock reproduced `SQLITE_BUSY` without changing the database hash. This did not use the user's database.
- The earlier source audit and regression suite confirmed the reported failure classes around unsafe database-unavailable states, long allowance-credit gaps, recoverable tracking-loop failures, stale allowance enforcement after edits, and missing editor error/retry feedback.
- Allowance diagnostic publishing was only event-count limited; rapid foreground changes could write repeatedly. A failing-first test demonstrated this, and publishing is now also limited to once per 60 seconds.

### Unresolved, not disproven

- H1 (overlapping instances), H2 (previous process still shutting down), H4 (external lock holder), H5 (SQLite busy-handler/extended-code behavior), and H6 (Windows process identity and Store-app resolution) were not established from the available evidence.
- Windows Spike A/B, installed-process-name checks, and the manual Windows checklist remain unrun. Read-only startup remains disabled; lock-holder attribution is not claimed.
- No evidence establishes that the user's database was corrupt. The reported lock error must not be treated as proof of corruption.

## Decisions and cleanup

- A locked or unavailable database must remain recoverable; do not silently treat it as empty, bypass PIN protection, or delete database files.
- Keep the Global PIN policy for allowance increases/deletions and the documented Emergency Break semantics.
- Do not enable read-only startup before the Windows Spike A result is known.
- Keep the hybrid foreground-event/heartbeat tracker. Do not add a keyboard/mouse idle cutoff; non-Windows counting and display-off limitations remain documented.
- No temporary Phase 0 instrumentation remains in application source. The source scan's `.tmp` match is the normal atomic-write temporary file in `SingleInstanceGuard`; `TemptationLogger` is a product feature, not debug instrumentation.
- Permanent allowance diagnostics require six events and a minimum 60-second interval. Store warnings are suppressed until recovery; repeated loop failures are escalated rather than logged on every tick. `EnforcementLog` has a 512 KB ceiling and trims to about 256 KB on rotation.
- Added the v2.0.2 user-facing changelog entry and synchronized the app, crash-report, settings, root package, and recovery package version references. The release workflow's version-to-changelog parser recognizes all four new items.

## Verification evidence

- `gradle clean check --no-daemon --console=plain` — passed; 62 tests, 0 failures, 0 errors, 0 skipped. The recovery project has no test sources.
- Failing-first verification for diagnostic rate limiting — the new test failed before the throttle and passed after it.
- Release parser check — passed; detected v2.0.2 and four changelog entries, with app and recovery version references aligned.
- `git diff --check` — passed.
- Temporary-instrumentation search found no matching debug probes in application source. Its only marker hit is the intended atomic-write `.tmp` file; the filename scan found `TemptationLogger.kt`, which is not temporary instrumentation.
- No desktop launch/restart was performed for this verification, and no user database or sidecar was opened by these checks. The existing application workflow was left running.
- Build output still reports existing deprecated Compose icon usages, two coroutine-test opt-in warnings, and Gradle 9 deprecation notices; these were not introduced by this batch and were not changed.

## Windows and interactive checks not completed

This workspace is Linux, so none of the following Windows checks is reported as passed:

1. Two simultaneous launches, focusing the first instance, and ensuring Nuclear Mode/registry lockdown are unaffected.
2. Holding the real database lock, observing the startup gate/retry, releasing the lock, and confirming startup continues.
3. Alt+Tab tracking across ordinary, Store, and elevated apps; multiple monitors; lock/unlock; sleep/resume; display-off; Emergency Break while blocked; and midnight rollover.
4. Comparing allowance minutes with a stopwatch for 15 minutes of mixed use (expected error under one minute).

The earlier interactive acceptance for the isolated startup-gate flow, allowance PIN/editor behavior, and Focus-to-editor/save-recovery flow also remains pending. Therefore the source plan's definition of done is **not yet met**, despite the automated suite passing.

## Rollback notes

- The current Git history exposes only a grafted baseline, not independent phase commits. Do not assume that each phase can be reverted with a separate commit. Prefer a Replit checkpoint from before these changes if a source rollback is needed.
- These checks did not modify the user's database. Before downgrading after a future run that applies the allowance schema migration, preserve a compatible database backup; restoring code alone may not reverse a schema migration.
- Never delete the database or its `-wal` / `-shm` sidecars as a rollback step. Verify backup compatibility before starting an older app version.
