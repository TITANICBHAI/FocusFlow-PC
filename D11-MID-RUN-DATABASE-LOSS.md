# If FocusFlow loses database access while running

**Status:** This document records a proposed design, not an implemented feature. The design direction is approved for documentation; runtime behavior and the enforcement policy remain unapproved.

## The problem

FocusFlow can open its SQLite database successfully at startup and then lose access to it later. This is different from a database that is unavailable before the app starts. During a mid-run failure, reads or writes may fail while focus protections and other services are already active. Some consumers may still hold old settings in memory, and a partial refresh could leave different parts of the app using inconsistent settings.

The app must not pretend a failed write succeeded, silently switch to an empty database, or make security decisions from a mixture of stale and partially refreshed settings.

## Proposed recovery behavior

1. Track database health separately from startup status:
   - **Healthy:** the database is usable and its required checks have passed.
   - **Degraded:** a classified temporary failure occurred and bounded retries are underway.
   - **Lost:** recovery attempts could not restore a usable connection.
2. Distinguish temporary contention from a closed connection or persistent I/O failure. Do not classify arbitrary query, schema, or programming errors as connection loss.
3. Run recovery through one serialized background coordinator. It should wait for in-flight database operations, avoid unsafe checkpointing after a failure, and reopen the existing database using validated integrity and schema checks.
4. Build a complete candidate settings snapshot before publishing it. Keep the last validated snapshot until the replacement is complete; never publish a partial mixture or report stale values as current.
5. Surface failed writes to the relevant screen or action. Do not silently replay writes after reconnecting, because the user may have changed their mind or repeated the action.
6. Show a persistent, plain-language status on the main app screens while reconnecting or when access is lost. Clear it only after the database and refreshed settings have passed validation.
7. Do not silently quarantine a possibly corrupt database and continue with a fresh empty one. Destructive recovery or restoration must be a separate, explicitly approved action.

## Enforcement policy still needs approval

The app needs an explicit policy for what its blockers do while the database is degraded or lost. The proposed options are:

- Continue enforcement using the last validated settings.
- Pause selected protections while database access is unavailable.
- Stop enforcement until the database is usable again.

**Recommended for consideration, but not approved:** keep protections that were already active using the last validated settings, and do not start or change enforcement based on stale or partial data. This avoids suddenly dropping an active block, but may keep a restriction active longer than intended. The policy for ending a session or overriding a restriction during a prolonged outage also needs an explicit decision.

## Decisions required before implementation

- Which failures are temporary, how many retries to make, and how long to wait.
- Whether to continue, pause, or stop enforcement in each health state.
- What users may do if the database remains unavailable, including whether they can end an active session.
- How to handle a database that fails integrity or schema validation. No automatic data reset is approved.
- What the persistent status message says and whether it offers retry or dismissal controls.

## Verification and safety limits

Before release, test with temporary databases and injected failures: temporary contention, closed connections, unsuccessful and successful reopen, overlapping recovery requests, consistent settings refresh, surfaced write failures, and status changes in the UI. Test that a failed recovery never replaces user data with an empty database.

No real user profile, live repair, database reset, or data deletion is part of this proposal. Until the decisions above are approved and the behavior is implemented and tested, FocusFlow has no new mid-run database recovery guarantee.
