# FocusFlow Desktop — SQLite / Database Fix Tracker

- **Source plan:** [plan.md](plan.md)
- **Agent instructions:** [preagent-prompt.md](preagent-prompt.md)
- **Audit evidence:** [focusflow-audit-evidence/README.md](focusflow-audit-evidence/README.md) and [focusflow-audit-evidence.zip](focusflow-audit-evidence.zip)
- **Overall status:** Database reliability implementation has not started. The owner authorized v2.0.6 planned-changelog/version-metadata prep only; audit findings remain unimplemented and require separate authorization.

## Tracking rules

- Follow **Reconciled execution sequence** below where it corrects stale assumptions or dependencies in `plan.md`; the original plan remains the scope/evidence reference.
- Before starting a phase, add a dated **In progress** note. Update this tracker after meaningful changes; append history rather than erasing failures.
- Tick an implementation item only when its required tests and acceptance criteria pass. A source review, compile, or Linux test does not verify Windows behavior. A finding may be checked as **Closed—not applicable** only with source evidence recorded below.
- Keep owner decisions and Windows checks open until the owner/tester supplies the required decision or evidence. The agent must not tick W1–W15.
- For every phase, record changed/read files, exact commands and results, failures and reruns, discrepancies, owner gates, and outstanding Windows checks in `fixes/PHASE-<n>-REPORT.md`.
- Follow R1–R16 in [plan.md](plan.md) and the operating instructions in [preagent-prompt.md](preagent-prompt.md).

## Current findings and preparation record

### 2026-10-14 — v2.0.6 planned release metadata

- Set the app/recovery package version source to `2.0.6` and added an explicitly **PLANNED** in-app changelog entry summarizing the database reliability plan.
- The Windows build derives EXE/MSI package versions and the four-part MSIX version from `appVersion`; no packaging workflow logic was changed.
- This is release preparation only. No database finding, test, audit script, telemetry path, live profile, or Windows behavior has been implemented or exercised. Owner decisions and the Windows checklist remain open.

### 2026-10-14 — Planning package prepared

- **Files added:** `fixes/plan.md`, this tracker, `fixes/preagent-prompt.md`, the supplied evidence ZIP, and its 29 extracted files.
- **Plan integrity:** `fixes/plan.md` matches the supplied Markdown byte-for-byte; SHA-256: `92abc004b1e807dfc04e4c4a70841e1c9f243dab8b40460966674b94036119b2`.
- **Evidence handling:** ZIP listing was inspected and files were extracted to a temporary directory before copying. The reproduction scripts were not run.
- **Source snapshot caveat:** The plan audits an app snapshot labeled `2.0.3`; the database findings were reviewed against the `2.0.5` source. Release metadata was later staged at `2.0.6` for the planned scope only. Treat current source, build configuration, and working tree as authoritative; re-check all paths, line references, versions, and assumptions.
- **Initial discrepancy — F26 (superseded below):** The audit archive reportedly omitted `recovery/`; current-source review and Gradle configuration verification now confirm it is present and included. See the F26 closure and root `AUDIT-ADDENDUM.md`.
- **Verification state:** No app code, tests, audit harness, or Windows procedure has been run as part of preparing these documents.

### 2026-10-14 — Current-code review

- Reviewed the relevant v2.0.5 startup, database, telemetry, PIN, recurring-task, session, backup, export, and test sources. These are source-confirmed findings, not runtime reproductions.
- Existing protection already present: bounded SQLite busy retries, `PRAGMA quick_check` on DB open, preservation of corrupt DB/WAL/SHM files, a visible startup-unavailable screen, and an existing SQLite smoke test that checks the Windows x64 DLL is in the jar.
- **F26 closed as stale:** `settings.gradle.kts` includes `recovery`, and the current checkout contains `recovery/build.gradle.kts`. The audit archive omitted a current module; no source change is needed. See root `AUDIT-ADDENDUM.md`.
- **Gradle verification:** `gradle help --no-daemon` succeeds with the configured JDK 19 and Gradle 8.14.2. The checked-in `gradlew` script cannot launch because `gradle/wrapper/gradle-wrapper.jar` is absent; use the configured system `gradle` command for Phase 0 and later verification. Do not generate a wrapper as unrelated work.
- **Test inventory correction:** `SqliteJdbcNativeSmokeTest` exists, but it does not test restricted Windows temp paths or native-loader fallback. Targeted tests for settings failure, PINs, recurrence, sessions, backups, consent, CSV, uninstall, VPN cache failures, and date queries are still absent.
- No application source was changed and no Gradle suite, evidence script, telemetry path, or Windows scenario was executed. `tasks.test` currently has neither the version property nor an effective telemetry-off sink; do not add failure-injection tests until F4.0 is in place.

## Current-code disposition by finding

| Finding | Priority | Current-code assessment |
|---|---|---|
| F1 — Native SQLite loading | P0 | **Open.** `Database` uses the driver loader with no app-owned fallback. The current smoke test checks a normal runtime and DLL packaging, not a blocked Windows temp directory. |
| F2 — Throwable-safe startup | P0 | **Open.** DB and caller paths catch `Exception`; a `LinkageError` can escape and leave startup at `Opening`. |
| F3 — Root cause | P0 | **Partial.** Local reports already include a full cause chain; the startup gate shows only the immediate cause, while telemetry truncates raw stack text. Narrow the work to missing local/JUL/UI details and defer remote-payload changes until F4 redaction is active. |
| F4 — Consent, PII, endpoints | P0 | **Open; urgent.** Missing/unreadable DB consent currently permits reports, including a DB-open failure before the dialog can be shown. Stack text is not path-redacted. Keep owner decisions D1–D3. |
| F5 — Uninstall on DB failure | P0 | **Open; owner-gated.** Current code blocks uninstall when protection state cannot be read. Decide D4; implement only after F11’s read-only path. |
| F6 — Recurring task multiplication | P1 | **Open; confirmed by current logic.** Generated copies remain recurring templates and the existing-key set is not updated in the loop. `@Synchronized` does not prevent this growth. |
| F7 — JDBC statement closure | P1 | **Open.** Many query sites close the result set but not the owning statement; the enabled-block-process query is reached from the two-second cache refresh. Recount exact sites against current code. |
| F8 — PIN fail-open | P1 | **Open; high security impact.** Global, Session, and Nuclear PIN lookups treat unavailable/null as “not set” or successful verification. |
| F9 — Orphan focus sessions | P1 | **Open.** The unfinished-session query has no age bound; asynchronous end writes can be lost. Live repair needs F15 backup verification and D8. |
| F10 — Transactions | P1 | **Open.** Transaction sites catch only `Exception`, suppress rollback errors, and restore auto-commit in `finally`; keep changes narrowly tested. |
| F11 — Wizard DB access | P1 | **Open.** The wizard calls full `Database.init()`, which snapshots/restores sidecars and runs DB pragmas/migrations. Separate from F5’s policy decision. |
| F12 — Missing vs. unreadable settings | P1 | **Open.** Reads collapse absent, unavailable, and failed into `null`; writes silently ignore errors. |
| F13 — Session write ordering | P1 | **Open.** Start/end writes are independent IO jobs with swallowed errors; `dispose()` cancels their scope. |
| F14 — Migration schema verification | P2 | **Open, partly inferred.** Existing `ALTER TABLE` catches may hide non-duplicate failures. Add a tested guard for new migrations before F6; do not edit v1–v9. |
| F15 — Backup verification/restore | P1/P2 | **Partial.** `VACUUM INTO` and SHA-256 sidecars already exist. Hashing does not prove SQLite validity; hashless files are treated as verified, failures can be ignored, and the unsafe restore method has no callers. Keep the targeted validation/reporting/deprecation work. |
| F16 — DB contention | P2 | **Measure only.** DB methods are synchronized, but reviewed UI paths already use IO dispatchers. No contention measurement justifies a threading rewrite yet. |
| F17 — VPN cache after read failure | P1 | **Open.** Failed reads become cached defaults indefinitely. Current `ProcessMonitor.refreshCaches()` does not refresh `VpnBlocker`; revise the plan’s proposed refresh hook. |
| F18 — Enforcement while DB unavailable | P1 / owner decision | **Policy, not a stand-alone code fix.** Startup deliberately leaves DB-dependent services stopped and tells the user. Decide the product behavior before changing it. |
| F19 — Mid-run DB loss | P2 / owner decision | **Design note only.** No specific mid-run recovery behavior has been approved or reproduced. |
| F20 — Date queries/N+1 | P3 | **Open, low urgency.** Indexed timestamps are wrapped in `DATE(...)`; daily views also make repeated queries. Optimize after correctness work. |
| F21 — Task completion idempotence | P3 | **Open.** Repeated calls and unknown IDs still increment the daily counter. |
| F22 — Clear activity | P3 / destructive scope | **Open.** UI promises to clear settings, but clears four activity areas in separate operations without a verified backup. Clarify wording and atomicity; do not broaden to PINs/settings without D13. |
| F23 — CSV output | P3 | **Open.** Current paths alter quotes/newlines rather than RFC-4180 escaping and have different row limits. |
| F24 — Crash-log retention | P3 | **Open.** Reports are written Desktop-first and have no current cap/deduplication. Keep Desktop deletion out of scope; resolve D15 first. |
| F25 — AppPaths | P3 | **Optional/defer.** No `AppPaths` exists, but no actual path mismatch was found; this is primarily consistency/testability work. |
| F26 — Recovery module missing | P3 | **Closed—not applicable.** Current `recovery/` directory and Gradle project exist. Do not remove the include or spend implementation time on this finding. |
| F27 — SelectorProvider | P3 / WIN-VERIFY | **Unverified.** Current build targets JDK 19 and EXE/MSI; no MSIX target is configured. The plan’s JDK 21/MSIX claim does not establish behavior here. Test the actual shipped runtime before any change. |
| F28 — App version property | Test setup | **Narrow.** Normal app launch gets the version from Gradle; `tasks.test` does not. Add test JVM properties, including telemetry-off after F4.0. Do not silently weaken the production fail-fast without approval. |
| F29 — Test gaps | Cross-cutting | **Open.** Coverage gaps are real, but the plan understated the existing `SqliteJdbcNativeSmokeTest`; add finding-specific tests with fixes. |
| F30 — 90-day streak cap | P3 / owner decision | **Open.** Current streak is capped at 90 while best streak is not. Ask D18 before changing product behavior. |

## Reconciled execution sequence

The following dependencies take precedence over conflicting ordering in the attached plan:

1. **Phase 0:** inspect the untouched test suite for default-path DB failures/network calls, then run `gradle :test --no-daemon` with the workflow’s JDK environment and add isolated test helpers. Current source scan found DB initialization tests pass explicit temp files and no direct telemetry/network test references, but recheck before running. No new DB-failure injection before F4.0.
2. **Phase 1:** **F28 test configuration → F4.0 → F2 → F1 → F3 local diagnostics/UI → F4 consent and redaction → F3 remote-payload summary.** Keep the production app-version property fail-fast unless the owner approves a fallback. Do not expand remote diagnostic content before F4 redaction.
3. Ask D4 during Phase 1 if possible, but **defer F5 implementation/sign-off until after F11** in Phase 2; F5 depends on the read-only wizard path and is not a Phase 1 exit criterion.
4. **Phase 2:** F10 → F12 → F7 → F8 → F14a new-migration guard → F6 generator/schema fix → F9 age-gate/dry-run implementation → F13 → F11 → F5 (if D4 is resolved) → F17 → F15 → approved F6/F9 live-data repairs → F14b legacy-migration audit → F16/F18/F19.
5. F6/F9 code and dry-run tests may be prepared earlier, but **do not run a live repair until F15 can produce and verify a backup**. The F6 v10 migration must have a tested column guard before it is added; do not edit migrations v1–v9.
6. **Phase 3:** proceed only after Phase 2. F22 is already ordered after F15; keep F20–F25/F30 low priority and owner-gated items deferred. F26 is closed.

## Phase 0 — Baseline and tooling

**Status:** Complete — 2026-10-14. The untouched baseline passed, helper smoke tests passed, and the report is complete. Loopback-only IPC was allowed for the suite; no external network or telemetry calls were made. No production-code changes were made.

- **2026-10-14 pre-run review:** All `Database.init` calls in `DatabaseTest` pass explicit `@TempDir` files, and the native smoke test uses `@TempDir`. `Database.finishFailed` calls `CrashReporter` only for the default database path, so these temporary-path failures do not reach that telemetry call. No direct external HTTP/telemetry client references were found in test sources. `SingleInstanceGuardTest` uses TCP only on loopback; this is the explicitly allowed local IPC boundary.
- **Baseline:** The untouched root `gradle :test --no-daemon` run passed. Test sources remain unmodified. The observed Java/Gradle runtime discrepancy is recorded in `AUDIT-ADDENDUM.md` and the phase report.
- **P0-3:** Owner reports the files were produced by a Claude agent running in Claude's app; the underlying host machine/OS is unknown. `%TEMP%`/security-software context is unknown.
- **P0-2 and final verification:** The targeted helper smoke suite passed 4/4 after correcting its leak probe to execute the statement before measuring `DB.stmts`. The post-helper root suite passed 102/102; see `fixes/PHASE-0-REPORT.md` for the initial probe failure, rerun, XML totals, and timings.

- [x] **P0-1 — Baseline:** Rechecked default-path DB and network safety, then ran the untouched root `gradle :test --no-daemon` with the workflow environment. **Result:** 98 tests, 0 failures/errors/skips, 19 JUnit XML files, 160.54 s wall time; test worker GraalVM CE 22.3.1 / OpenJDK 19.0.2. Details and the AWT shutdown warning are in `fixes/PHASE-0-REPORT.md`. No change to `DatabaseTest`; `./gradlew` remains unavailable because its wrapper JAR is missing.
- [x] **P0-2 — Test helpers:** Added and smoke-tested `TempHome`, `ForkedJvm`, and `StatementLeakAssert` under `src/test/kotlin/com/focusflow/testutil/`; they use scratch `user.home`, set app-version and telemetry-off properties, and reset/restore JVM state. The initial leak probe omitted execution and did not increase `DB.stmts`; the probe was corrected and the targeted 4-test suite passed. `FakeTelemetrySink` remains deferred until F4.0 and the clock seam until F6.
- [x] **P0-3 — Owner facts recorded:** The owner reports a Claude agent in Claude's app produced the reports; the host OS and Windows `%TEMP%`/security context are unknown. No raw crash-log excerpt or user path was collected; these unknowns do not block Phase 1.
- [x] **Phase 0 exit:** Baseline recorded; helper smoke tests pass; `fixes/PHASE-0-REPORT.md` is complete. Final root suite: 102 tests, 0 failures/errors/skips across 22 XML files.

## Phase 1 — P0 startup failure and blast radius

- **Status:** In progress — 2026-10-14 (F28).
- **Required order:** **F28 test configuration → F4.0 → F2 → F1 → F3 local diagnostics/UI → F4 consent/redaction → F3 remote-payload summary. F5 is deferred until after F11.**

- **2026-10-14 start note:** Began Phase 1 after explicit owner authorization. Work follows the reconciled sequence above; D1–D3 and F1b remain approval gates. No database finding was implemented before this authorization.
- **2026-10-14 F28:** Gradle supplies the project version to test workers and sets `focusflow.telemetry=off`. Tests cover the worker property and the packaged-app missing-version fail-fast in a fresh JVM; targeted root `:test` passed 2/2 with zero failures/errors.
- **2026-10-14 F4.0:** Automatic critical and fatal reports now route through `TelemetrySink`; tests select a no-op sink when telemetry is off, while an injected recording fake captures both paths without network I/O. Targeted root `:test` passed 5/5 with zero failures/errors.

- [x] **F28 — Test JVM configuration (pulled forward):** Set `focusflow.app.version` from the Gradle project version for tests and set `focusflow.telemetry=off` for tests after F4.0. Keep the packaged-app fail-fast unless the owner approves a production fallback. Assert the test worker gets the version property; in an isolated JVM, verify the missing-property case remains an intentional fail-fast rather than masking a packaging error.
- [x] **F4.0 — Telemetry test seam (before failure tests):** Route automatic reports through `TelemetrySink`; support a no-op sink when telemetry is disabled and a recording fake in tests. No behavior change beyond the explicit kill switch; tests/scripts must never send network traffic.
- [ ] **F2 — Throwable-safe database startup:** Convert non-fatal `Throwable` failures to typed failed startup results, rethrow `VirtualMachineError` and `CancellationException` as specified, and ensure startup cannot remain `Opening`. Add the injected data-source and background-loop audit. Tests F2-a…c.
- [ ] **F1 — Controlled SQLite native bootstrap:** Implement the planned candidate ladder, diagnostic attempts, content-addressed extraction, and safe retry/restart behavior without touching the DB on load failure. Tests F1-a…d; Windows items W1–W3 and W5 remain human checks. **F1b (shipping the DLL in the install) is owner-gated and must wait for W1–W3.**
- [ ] **F3 — Root-cause reporting:** First capture sqlite-jdbc JUL output and show the full cause/local report path in the startup gate. Only after F4’s fail-closed consent and redaction are active, add the bounded summary to remote payloads. Reuse the existing full local cause-chain report. Tests F3-a…d.
- [ ] **F4 — Consent, privacy, and secrets:** Use a DB-independent consent store, fail closed unless explicitly granted, migrate the old setting once, redact payloads, and preserve the no-network test seam. Resolve owner decisions D1–D3 before touching secret/consent wording or endpoint behavior. Tests F4-a…d.
- [ ] **F5 decision intake — D4:** Ask/record the owner’s uninstall policy if they answer in Phase 1. Do not implement F5 until F11’s read-only path exists in Phase 2.
- [ ] **Phase 1 exit:** F1–F4 acceptance is met except outstanding WIN-VERIFY checks; F5 is explicitly deferred to Phase 2 after F11. Record D1–D5 and the deferral in `fixes/PHASE-1-REPORT.md`.

## Phase 2 — P1/P2 data integrity and security findings

- **Status:** Not started.
- **Required order:** **F10 → F12 → F7 → F8 → F14a new-migration guard → F6 code → F9 gate/dry-run → F13 → F11 → F5 → F17 → F15 → approved F6/F9 live repairs → F14b legacy audit → F16/F18/F19.**

- [ ] **F10 — Correct transaction helper:** Use `BEGIN IMMEDIATE` / `COMMIT` / rollback on any `Throwable`; migrate the six listed call sites without changing their SQL or nesting transactions. Tests F10-a…d; preserve the v9 migration fixture requirement after checking the current schema version.
- [ ] **F12 — Distinguish absent from unreadable settings:** Add typed read/write results and migrate consumers in the exact order in the plan: PINs, VPN blocker, Standalone Block, Nuclear Mode/Kill Switch, uninstall requirements, then remaining listed services/UI. Unreadable security reads fail closed; restore-state reads retain last-known-good values; failed writes are surfaced. One injected-unavailable-store test per migrated consumer.
- [ ] **F7 — Close JDBC statements:** Recount current sites, then close every confirmed unclosed `executeQuery`/`executeUpdate` statement; the plan’s 18/seven counts are estimates, not acceptance criteria. Add leak and source-hygiene tests F7-a…c; scanner must find no unwrapped `createStatement()`.
- [ ] **F8 — PIN checks fail closed:** Build on F12; distinguish `NOT_SET`, `SET`, and `UNKNOWN`, deny on unknown storage, and report write failures. Verify PIN read-back before returning an auto-generated PIN. Do not change hash strength or add recovery design without owner approval D12. Tests F8-a…e.
- [ ] **F14a — New-migration guard (before F6):** Add and test a column/schema guard for new migrations without editing v1–v9. This is an explicit F6 prerequisite.
- [ ] **F6 — Recurring task duplication:** After the F14 new-migration guard, add the numbered migration and recurring-template parent relation; make generation idempotent. Add a dry-run repair; do not run it on a real DB until F15 has verified backup support. Mark, never delete, unless the owner explicitly approves deletion (D7). Tests F6-a…d.
- [ ] **F9 — Reconcile orphan sessions:** Add the 12-hour gate and dry-run/idempotent reconciliation. Do not apply the repair to a real DB until F15 has verified backup support and the owner approves the end-time policy (D8). Tests F9-a…d.
- [ ] **F13 — Order focus-session writes:** Ensure end waits for start, log failed writes, and allow bounded completion on dispose. Tests F13-a…c; note whether the dispose race already passes before changes.
- [ ] **F11 — Read-only uninstall wizard:** Add an open that does not snapshot, mutate, checkpoint, migrate, or recover the DB. This is a prerequisite for F5. Tests F11-a…d; W6 remains human-only.
- [ ] **F5 — Safe uninstall when DB is unavailable:** After F11 and owner decision D4, implement only the selected policy; BUSY/LOCKED must continue to block. Tests F5-a…c; W6 remains human-only.
- [ ] **F17 — Recover VPN settings after read failures:** Keep last-known-good volatile state, refresh outside the enforcement hot path, and report failed writes. Tests F17-a…c.
- [ ] **F15 — Verifiable backups:** Keep the existing `VACUUM INTO` snapshot and hash checks; add SQLite `quick_check`, distinguish unverified backups, report failures, correct sidecar naming, and make the unused unsafe `restoreBackup` uncallable. Complete this before any F6/F9 live repair. Real restore, second destination, and post-recovery UI require owner approval (D9). Tests F15-a…d.
- [ ] **F14b — Legacy schema audit (after F15):** Verify required schema state and audit swallowed legacy migration errors without editing v1–v9. Tests F14-a…b.
- [ ] **F16 — Measure database monitor contention:** Add the planned debug-only measurement, run a 10-minute session, and report the top 10 offenders. Do not restructure threading without D19 approval.
- [ ] **F18 — Database unavailable behavior:** No degraded blocker without a separate approved design. The plan permits the small visible “Blocking is OFF” message as option A; confirm task authorization before applying it. Ask the owner before choosing another behavior.
- [ ] **F19 — Mid-run DB loss:** Write only the one-page health/reopen/banner design note; do not implement it without owner approval D11.
- [ ] **Phase 2 exit:** All non-owner-gated findings have passing tests; report open gates D4 and D7–D11 plus other F6/F9/F15 decisions in `fixes/PHASE-2-REPORT.md`.

## Phase 3 — P3 hardening and cleanup

**Status:** Not started. Begin only after Phase 2; the plan allows any order here except F28 is already pulled forward.

- [ ] **F20 — Index-friendly date queries:** Replace date-wrapped indexed columns and per-day N+1 queries while preserving results/storage; add boundary, query-plan, grouping, and empty-table tests F20-a…d.
- [ ] **F21 — Idempotent task completion:** Update only incomplete existing tasks and increment daily completion once; add tests F21-a…c.
- [ ] **F22 — Atomic activity clear:** Clear only the four explicitly listed activity areas in one transaction and take a verified backup first; abort on backup failure. Do not broaden deletion or add a Global PIN gate before owner decisions D13. Tests F22-a…c.
- [ ] **F23 — Consistent CSV exports:** Use RFC-4180 quoting, correct row counts, and distinct saved/cancelled/failed results across export paths. Ask owner about formula-prefix protection and UTF-8 BOM (D14). Tests F23-a…c.
- [ ] **F24 — Bounded crash reports:** De-duplicate matching reports and cap only the specified app-owned locations; do not delete Desktop reports. Ask owner before changing the Desktop-first policy (D15). Tests F24-a…b.
- [ ] **F25 — Central application paths:** Add getter-based `AppPaths` that honors changing `user.home`, replace the four DB path computations, and keep other replacements limited to trivial cases. Tests F25-a…b.
- [x] **F26 — Closed as stale:** Current checkout includes `recovery/` and its Gradle build file; no code change or removal is warranted. Evidence is recorded in root `AUDIT-ADDENDUM.md`.
- [ ] **F27 — SelectorProvider flag:** No code change until human W9 is complete on the actual shipped JDK (current toolchain JDK 19) and current EXE/MSI formats. Include MSIX only if another active distribution path actually ships it; do not remove the flag based on the audit alone.
- [ ] **F28 — Test-configuration regression:** Completed earlier in Phase 1; retain its test/configuration coverage in the final audit.
- [ ] **F29 — Test-gap closure:** Reconcile all required test classes and finding-specific cases against the current tree; do not claim coverage from compilation alone.
- [ ] **F30 — Current streak cap:** Ask whether 90 days is intentional (D18); change only after approval. If approved as a defect, pass F30-a…c.
- [ ] **Phase 3 exit:** All non-owner-gated acceptance/tests pass; `fixes/PHASE-3-REPORT.md` lists remaining owner choices and Windows checks W9/W15 as applicable.

## Owner decisions — unresolved unless answered or explicitly closed

Recommendations in the source plan are not approvals. Record the owner’s answer and date before implementing a gated item.

| ID | Decision | Blocks / notes |
|---|---|---|
| D1 | Rotate exposed Discord webhooks and move delivery behind an owner-provided relay | F4. Owner performs rotation; never request, inspect, or write secret values into files/chat. |
| D2 | Crash-report consent default: opt-in or opt-out | F4. |
| D3 | Final consent dialog and Store privacy wording | F4. Owner/legal wording decision. |
| D4 | Uninstall outcome when DB state is unreadable: confirmation phrase, manual path, or external flag | F5. |
| D5 | Ship SQLite DLL inside installation | F1b; wait for W1–W3. |
| D6 | sqlite-jdbc upgrade timing | No version bump without explicit approval; separate change. |
| D7 | Whether to offer deletion of recurring-task duplicates | F6 repair defaults to mark-only; never delete user rows without approval. |
| D8 | End-time rule for orphan focus sessions | F9. |
| D9 | Second backup location, restore UI, and recovery notice | F15; separate design/work. |
| D10 | Behavior while DB is down | F18. The plan permits only its small visible status message without a separate degraded-mode design. |
| D11 | Mid-run DB-loss design | F19; design note first, implementation only after approval. |
| D12 | PIN hashing/recovery design and security wording | F8; separate project per source plan. |
| D13 | “Clear all data” scope and Global PIN requirement | F22. |
| D14 | CSV formula-injection guard and UTF-8 BOM | F23; BOM recommendation requires Excel verification. |
| D15 | Crash-log placement: Desktop-first or app log directory | F24. |
| D16 | Whether the recovery Gradle module is still needed | **Closed—not required for this audit:** F26 is stale; revisit only if the owner proposes removing the existing module. |
| D17 | Keep/replace/remove SelectorProvider flag | F27; only after W9. |
| D18 | Whether the 90-day current-streak cap is intentional | F30. |
| D19 | Threading restructure | F16; only after measurement. |

## Windows verification matrix — human only

All items stay unchecked until a human runs the named scenario on Windows and records build, OS, result, and evidence. Use a throw-away VM/profile for destructive environment scenarios. Never run W2/W3/W8 against a user's normal environment.

- [ ] **W1:** Clean per-user EXE install loads DB/native DLL in the expected location without temp extraction.
- [ ] **W2:** Broken `%TEMP%`/`%TMP%`: compare released and fixed builds; establish whether the reported failure reproduces.
- [ ] **W3:** DLL blocked from temp by audit-mode policy; verify fallback and safe failure UI.
- [ ] **W4:** Space/non-ASCII profile path; verify DB, library, reports, and backups.
- [ ] **W5:** Install/start/uninstall matrix for current EXE/MSI packages; include MSIX only if a separate active distribution path exists.
- [ ] **W6:** Uninstall wizard against active writer, unavailable native library, and active protection.
- [ ] **W7:** 30-minute watchdog failure soak using a test endpoint only; verify bounded reports/telemetry.
- [ ] **W8:** Backup/WAL/SHM behavior while app is open; record actual Windows sharing errors.
- [ ] **W9:** SelectorProvider with/without flag on the actual shipped JDK; include MSIX only if currently distributed.
- [ ] **W10:** Fresh-profile telemetry consent and network behavior; no send before explicit consent if opt-in is selected.
- [ ] **W11:** 24-hour memory soak before/after F7.
- [ ] **W12:** Recurring-task behavior and dry-run repair on a copy of a polluted DB.
- [ ] **W13:** Crash-log destination with Desktop redirected through OneDrive.
- [ ] **W14:** Installed packaged layout and Compose resource directory for F1b.
- [ ] **W15:** CSV row counts above 1,000 and clear-activity atomicity.

## Cross-cutting completion checks

- [ ] Each finding has tests that fail before and pass after, with fresh-JVM isolation for sqlite-jdbc static state.
- [ ] No tests/scripts make network requests; no production webhook is contacted. The telemetry-off property is not a safety control until F4.0’s sink honors it.
- [ ] No test or script touches a real profile/database; all use a scratch `user.home`.
- [ ] No new `catch (_: Exception) {}`; no UI-thread database I/O or `runBlocking`.
- [ ] Existing migration methods and user data remain intact; new schema uses a numbered migration.
- [ ] All OWNER DECISION items are resolved or remain explicitly deferred.
- [ ] Final reconciliation matches implementation, test XML, phase reports, and Windows evidence; final report lists unverified behavior and discrepancies.

