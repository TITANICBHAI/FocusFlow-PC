# D9 — Secondary backups, safe restore, and recovery notice

**Date:** 2026-10-15  
**Status:** Owner approved the three capabilities; this document defines their safety design before runtime implementation.

## Approved scope

1. Let the user choose a secondary backup folder in addition to the existing app-data backup directory.
2. Provide a UI to select and restore a verified backup.
3. Notify the user when automatic corruption recovery has preserved the old database and opened a fresh database.

This does not authorize a repair against a real profile during development. F6/F9 repairs remain gated on a dry run and a verified backup.

## Current safeguards and constraints

- Primary snapshots use `VACUUM INTO`; `AutoBackupService` runs read-only SQLite `quick_check`, verifies SHA-256 sidecars, and distinguishes verified, unverified, and invalid backups.
- The current `restoreBackup` method is compile-time disabled because it overwrites the live database without closing it or handling WAL/SHM safely.
- Automatic corruption recovery preserves the database and any `-wal`/`-shm` files as `.broken_*` files, then opens a fresh database. The preserved files must not be deleted by the notice or restore UI.
- The database is normally open while the app and background services run. A restore must not replace files in that process.

## Proposed behavior

### Secondary backup folder

- The user selects a directory through a native folder chooser. Save the selected path outside SQLite so it remains readable if the database is unavailable.
- For each successful primary backup, copy the verified database and its metadata to the selected directory using temporary sibling files and atomic promotion where supported.
- Verify the copied database with `quick_check` and verify its digest before reporting the secondary copy as complete.
- A secondary-copy failure must not invalidate the verified primary backup; show and log a partial-success warning.
- Never delete unrelated files in the selected directory. Retention and automatic deletion policy for FocusFlow-created secondary copies must be explicit in the UI and tests before implementation.

### Restore

1. Show the source, timestamp, file size, and verification state. Only `Verified` backups are eligible; legacy hashless backups remain unverified and cannot be restored silently.
2. Explain that restoring replaces the current database state with the selected snapshot. Require a distinct confirmation.
3. Before scheduling the restore, create and verify a fresh safety backup of the current database. If that fails, do not schedule or mutate anything.
4. Copy the selected snapshot into an app-owned staging directory and verify the staged copy again. Write a small, versioned pending-restore journal atomically.
5. Close the app cleanly and apply the restore on a later process start, after the single-instance lock is held and before normal database initialization. Do not replace files while the current connection or background services are active.
6. Preserve the pre-restore database set, including existing WAL/SHM companions, under unique names. Install only the verified staged database; never pair it with stale sidecars.
7. Keep a recovery journal until the new database passes normal initialization and schema checks. If installation or initialization fails, restore the preserved pre-restore set and surface the failure; do not silently continue with an empty database.
8. Retain the safety backup and pre-restore files until the user removes them through an explicit cleanup flow.

### Notice after automatic recovery

- Detect a newly preserved `.broken_*` database set and show a persistent notice after startup succeeds.
- State plainly that the old database was preserved and FocusFlow opened a fresh database; do not imply that old data was restored.
- Offer navigation to the verified-backup list and an option to continue with the fresh database.
- Record dismissal outside SQLite, keyed to the preserved archive identity, so the notice does not repeat for the same recovery but can appear for a later one.
- Never automatically restore or delete the preserved corrupt database.

## Required implementation tests

- A secondary copy is independently verified; failure leaves the primary backup intact and visible.
- Directory changes survive an unavailable database; no unrelated destination files are changed.
- Invalid, hash-mismatched, and unverified backups cannot be scheduled for restore.
- Failed safety backup or failed staging leaves the current database and sidecars unchanged.
- Restore interruption and initialization failure recover the pre-restore database set.
- A successful restore is acknowledged only after normal schema initialization succeeds.
- A new corruption archive triggers one notice; dismissing it suppresses only that archive's notice.
- All automated tests use temporary homes and databases. No test uses the normal profile.

## Human verification

Windows testing remains required for file replacement and sidecar behavior. Extend W8 to cover selecting a backup stored in a second directory, staging a restore, closing FocusFlow, reopening it, and confirming that the pre-restore files remain available. Do not run this matrix against a user's normal profile.
