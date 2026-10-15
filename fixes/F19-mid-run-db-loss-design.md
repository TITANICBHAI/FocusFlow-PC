# F19 — Mid-run database loss: health, reopen, and banner

**Date:** 2026-10-15

**Status:** Design proposal only. D11 approval is required before implementation.

## Current behavior

`Database.startupState` covers startup only; after `Ready`, calls may throw `DatabaseUnavailableException`, while setting reads/writes return typed failures. `ProcessMonitor.refreshCaches()` publishes volatile caches sequentially and logs failures, so old or partial cache values may remain until a later refresh. F8/F12 consumers fail closed for unavailable security reads.

`init()` is synchronized and short-circuits to `Ready` when `isReady`; its failure path handles sidecar snapshots and can preserve a corrupt set before opening a fresh DB. `close()` attempts a WAL checkpoint. A recovery path cannot blindly call `init()` or `close()` after an error.

## Proposed design

1. Add a separate `Database.health: StateFlow<DatabaseHealth>` with `Healthy`, `Degraded` (classified transient failure; retrying), and `Lost` (no connection after bounded retries). Keep `startupState` and the startup gate startup-only. Expose safe reason/retry metadata, never SQL details.
2. Classify failures centrally: distinguish `BUSY`/`LOCKED`, closed connections, and persistent I/O errors. Do not treat arbitrary query, schema, or programming errors as connection loss; feed typed setting failures into the same health state.
3. Use one IO recovery coordinator to serialize retries, wait for in-flight DB operations, and invalidate/close the failed connection without checkpointing. Reuse bounded open, integrity, migration, and schema checks, but provide a safe path around `init()`'s ready short-circuit and sidecar/corruption behavior. Never silently quarantine a corrupt DB and continue on a fresh empty one; destructive recovery needs separate approval.
4. Refresh enforcement/VPN caches as one candidate snapshot before publishing or returning to `Healthy`. Do not claim stale reads are current, replay failed writes, or start duplicate services. D11 must decide whether enforcement continues from last-known-good values, pauses selected rules, or stops.
5. Show a persistent banner on every main-app screen below the OS banner; keep F18's blocking-off message startup-only. Distinguish reconnecting from lost, state only what the approved enforcement policy guarantees, and clear it only after a validated reopen and cache refresh. Retry/dismiss controls require approval.

## Required approval and verification

D11 must approve failure classes/retry limits, enforcement during `Degraded`/`Lost`, corrupt-DB handling, and banner/retry semantics before code changes. Tests should use temporary DBs and injected failures for busy/closed connections, failed and successful reopen, single-flight retries, coherent caches, surfaced write failures, and banner transitions. No real profile, live repair, or DB reset is part of F19.
