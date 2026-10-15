# FocusFlow Desktop — SQLite / Database Fix Tracker

- **Source plan:** [plan.md](plan.md)
- **Agent instructions:** [preagent-prompt.md](preagent-prompt.md)
- **Audit evidence:** [focusflow-audit-evidence/README.md](focusflow-audit-evidence/README.md) and [focusflow-audit-evidence.zip](focusflow-audit-evidence.zip)
- **Overall status:** Phase 1 implementation and Linux acceptance through F4 and the gated F3 remote summary are complete. F28, F4.0, F2, F1, and F3 local diagnostics/UI were already complete. D1–D3 remain as recorded on 2026-10-14: direct Discord delivery is owner-selected (webhook rotation/secrets risk remains open), crash reporting is opt-in, and factual consent/privacy disclosure is approved. F5 is deferred until after F11 and D4; F1b and Windows checks remain open.

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
| F1 — Native SQLite loading | P0 | **Implemented and Linux-tested.** Candidate installation, diagnostics, and restart-safe failure handling are covered by F1 tests; Windows W1–W3/W5 remain unverified and F1b remains owner-gated. |
| F2 — Throwable-safe startup | P0 | **Implemented; Phase 1 Linux tests pass.** Database and startup callers now produce a stable failed state for nonfatal `Throwable`s and rethrow cancellation/fatal VM errors. |
| F3 — Root cause | P0 | **Implemented and Linux-tested.** Local full cause-chain reporting remains intact; remote critical/fatal telemetry now uses only bounded root-cause-first summaries, with JSON-safe fields and F4 redaction applied before the sink. Windows remains unverified. |
| F4 — Consent, PII, endpoints | P0 | **Implemented and Linux-tested.** Consent remains DB-independent, opt-in and fail-closed; the one-time legacy migration is covered, and serialized Windows paths plus current home/user/host values are redacted before the sink. D1’s delivery choice and exposure risk remain open; Windows W10 remains unverified. |
| F5 — Uninstall on DB failure | P0 | **Open; owner-gated.** Current code blocks uninstall when protection state cannot be read. Decide D4; implement only after F11’s read-only path. |
| F6 — Recurring task multiplication | P1 | **Open; confirmed by current logic.** Generated copies remain recurring templates and the existing-key set is not updated in the loop. `@Synchronized` does not prevent this growth. |
| F7 — JDBC statement closure | P1 | **Open.** Many query sites close the result set but not the owning statement; the enabled-block-process query is reached from the two-second cache refresh. Recount exact sites against current code. |
| F8 — PIN fail-open | P1 | **Partially mitigated by F12; remaining work is open.** Unavailable lookups now fail closed; F8 still covers UI tri-state/read-back behavior and generated-PIN verification. |
| F9 — Orphan focus sessions | P1 | **Open.** The unfinished-session query has no age bound; asynchronous end writes can be lost. Live repair needs F15 backup verification and D8. |
| F10 — Transactions | P1 | **Implemented and tested.** `BEGIN IMMEDIATE` transactions roll back on `Throwable`, preserve rollback failures, and convert the six approved call sites without auto-commit toggling. |
| F11 — Wizard DB access | P1 | **Open.** The wizard calls full `Database.init()`, which snapshots/restores sidecars and runs DB pragmas/migrations. Separate from F5’s policy decision. |
| F12 — Missing vs. unreadable settings | P1 | **Implemented for the ordered consumers.** Typed reads distinguish absent/unreadable, security consumers fail closed, restore consumers keep known state, and failed writes are logged or surfaced. |
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

- **Status:** In progress — 2026-10-14 (F1).
- **Required order:** **F28 test configuration → F4.0 → F2 → F1 → F3 local diagnostics/UI → F4 consent/redaction → F3 remote-payload summary. F5 is deferred until after F11.**

- **2026-10-14 start note:** Began Phase 1 after explicit owner authorization. Work follows the reconciled sequence above; F1b remains owner-gated. No database finding was implemented before this authorization.
- **2026-10-14 F28:** Gradle supplies the project version to test workers and sets `focusflow.telemetry=off`. Tests cover the worker property and the packaged-app missing-version fail-fast in a fresh JVM; targeted root `:test` passed 2/2 with zero failures/errors.
- **2026-10-14 F4.0:** Automatic critical and fatal reports now route through `TelemetrySink`; tests select a no-op sink when telemetry is off, while an injected recording fake captures both paths without network I/O. Targeted root `:test` passed 5/5 with zero failures/errors.
- **2026-10-14 F2:** `Database.init` and `tryOpenAndMigrate` now distinguish recoverable throwables from cancellation/fatal VM errors, preserve a terminal failed state, and expose an injected `DataSource` seam. The Main startup caller converts unexpected throwables to `DbInitResult.Failed`; the audit added fatal-safe reporting/retry boundaries to DB-backed background loops (ProcessMonitor, allowance tracking, backups, recurring tasks, schedules, alarms, weekly reports, Nuclear Mode) and the one-shot service bootstrap. F2 tests cover native-loader `LinkageError`, repeated init, `OutOfMemoryError`, startup caller mapping, and allowance/bootstrap fatal propagation. Targeted root `:test` passed 25/25; definitive full root `gradle :test --no-daemon --console=plain --rerun-tasks` passed 111/111, 0 failures/errors/skips across 25 XML files. Two earlier synchronous full-suite invocations produced no final task summary; this rerun’s persisted log showed `BUILD SUCCESSFUL` (1m26s). No network or telemetry calls were made.
- **2026-10-14 F1 start note:** Began controlled native SQLite bootstrap on branch `F1-controlled-native-bootstrap`. Current source already contains an unreferenced content-addressed native-library file-store helper; inspect and reuse it if it meets F1’s atomic-install contract. No F1 changes or tests have run yet. F1b remains owner-gated; Windows checks remain human-only.
- **2026-10-14 F1 verification attempt:** `gradle :test --no-daemon --console=plain --rerun-tasks` timed out at the shell’s 300-second limit after reporting `:compileKotlin` and `:processTestResources`; no final `:test` result or JUnit XML was produced. This is incomplete verification, not a pass; investigate before rerunning.
- **2026-10-14 F1 compile check:** The first `:compileTestKotlin` attempt found a test fixture type mismatch (`TempHome` supplies a `Path`, not a `File`); corrected the fixture and reran. `gradle :compileTestKotlin --no-daemon --console=plain` passed in 1m05s. This verifies compilation only; the full test suite is still pending.
- **2026-10-14 F1 verification attempt:** The first root `:test` run crashed its GraalVM 19 test worker with SIGSEGV in sqlite-jdbc `NativeDB.column_name_utf8` during the existing `DatabaseTest.failedMigrationNeverPublishesOrLeavesAClosedConnectionReady`; it produced no JUnit XML. The new test seam had cleared the process-lifetime native-load memo during database resets, allowing repeated native loads. Preserve successful JNI load state across database test resets; rerun focused F1 checks before the root suite.
- **2026-10-14 F1 focused checks:** Four of five `NativeSqliteBootstrapTest` cases passed, including writable-candidate startup/`SELECT 1`, default-loader fallback, and repeated failure without changing DB/WAL/SHM bytes. The Linux noexec probe picked `/sys/fs/cgroup`, whose mount reports writable but denies temp-file creation; strengthen mount selection to verify an actual write before using it.
- **2026-10-14 F1 focused rerun:** All five `NativeSqliteBootstrapTest` cases passed (0 skipped), including a writable candidate, real sqlite-jdbc default-loader fallback, retryable all-failure with unchanged database/sidecar bytes and `NativeDB.isLoaded == false`, and Linux noexec loading. The test mount probe now verifies actual file creation, and legacy database tests use scratch profile homes and a cached default-loader seam.
- **2026-10-14 F1 root-suite verification:** `gradle :test --no-daemon --console=plain --info` passed: 116 tests, 0 failures/errors/skips across 26 JUnit XML files. This is Linux test evidence only; the application was not started and W1–W3/W5 remain human-only.
- **2026-10-14 test-home caution:** The earliest F1 root-suite attempts preceded the scratch-home fixture. `DatabaseTest` used explicit `@TempDir` database files, but native-library candidate discovery may have created a cache under the test worker's normal `user.home`. I did not inspect or remove any such cache because its prior state is unknown. The final root-suite run used the scratch-home fixture.
- **2026-10-14 F3 start note:** Continuing the authorized Phase 1 work with local diagnostics only. The F1 bootstrap implementation and tests are present in this checkout; no F1 rerun is planned. Add bounded sqlite-jdbc JUL capture and expose the full startup failure chain; defer all remote-payload changes until F4 consent/redaction is active.
- **2026-10-14 F3 verification attempt:** The focused Gradle test invocation hit the 300-second shell limit after `:compileKotlin` started; it produced no final `:test` result, so this is incomplete verification. Rerun in the background and record its final result and XML.
- **2026-10-14 F3 focused rerun:** `gradle :test --no-daemon --console=plain --info --rerun-tasks --tests com.focusflow.data.NativeSqliteBootstrapTest --tests com.focusflow.StartupFailureDetailsTest` passed: 7 tests, 0 failures/errors/skips across 2 JUnit XML files. F3-d confirmed actual sqlite-jdbc JUL SEVERE output in both the bounded local log and the local failure diagnostics; startup detail formatting test confirmed deepest cause, retained DB path, local report path, and loader-log path. Test JVM telemetry was off and homes were scratch-only.
- **2026-10-14 F3 root-suite verification:** `gradle :test --no-daemon --console=plain --info --rerun-tasks` passed: 118 tests, 0 failures/errors/skips across 27 JUnit XML files. No test telemetry or external-network calls were made.
- **2026-10-14 owner decisions D1–D3:** D1: owner chose to keep direct Discord delivery and the current approach; endpoint constants remain untouched, and webhook rotation/shipped-secret exposure remains an open risk. D2: opt-in; no report is sent before explicit consent. D3: owner approved factual wording that discloses the receiving service sees the sender IP and diagnostic text may contain local paths.
- **2026-10-14 F4 start note:** Begin DB-independent opt-in consent, one-time migration, and payload redaction. Keep tests on scratch homes with the no-network telemetry seam. Do not alter direct webhook endpoint/secret lines.
- **2026-10-15 F4/F3 remote continuation note:** Owner authorized resuming Phase 1 at F4 and its gated F3 remote summary. Recheck the existing implementation and tests against F4-a…d and F3-a…c; preserve the existing telemetry sink seam and do not inspect or change webhook constants.
- **2026-10-15 F4 redaction regression:** A new assertion against JSON-serialized doubled Windows separators failed before the fix because the redactor matched only a single path separator. Updated path matching to recognize raw and JSON-escaped separators; the assertion now passes.
- **2026-10-15 F4 completion:** Verified DB-independent consent, fail-closed states, one-time legacy migration, and the fake/no-network sink. The first-run flow now offers consent immediately after onboarding when state is UNKNOWN or UNREADABLE. Existing consent/settings copy already matched D3; no endpoint or secret wording was changed.
- **2026-10-15 F3 remote completion:** Critical and fatal remote payloads now carry bounded root-cause-first summaries instead of raw stack traces. Summaries cap each cause/message/frame, include at most three deepest frames, stop on cause cycles, and JSON-escape every dynamic field, including source.
- **2026-10-15 Phase 1 verification:** Final focused run passed 11 tests across `TelemetryConsentTest`, `TelemetrySinkTest`, and `CrashReporterSummaryTest`. `gradle :test --no-daemon --console=plain --rerun-tasks` passed 127 tests, 0 failures/errors/skips, across 29 JUnit XML files. Tests used temp homes and fake/no-network sinks; no external telemetry was sent.
- **2026-10-15 preserved test attempts:** Before the fixes, the escaped-path regression failed at its `<home>` assertion. The first summary-test compile also rejected a `getCause()` method override; changing the test throwable to override Kotlin’s `cause` property fixed compilation, and the focused suite then passed.

- [x] **F28 — Test JVM configuration (pulled forward):** Set `focusflow.app.version` from the Gradle project version for tests and set `focusflow.telemetry=off` for tests after F4.0. Keep the packaged-app fail-fast unless the owner approves a production fallback. Assert the test worker gets the version property; in an isolated JVM, verify the missing-property case remains an intentional fail-fast rather than masking a packaging error.
- [x] **F4.0 — Telemetry test seam (before failure tests):** Route automatic reports through `TelemetrySink`; support a no-op sink when telemetry is disabled and a recording fake in tests. No behavior change beyond the explicit kill switch; tests/scripts must never send network traffic.
- [x] **F2 — Throwable-safe database startup:** Convert non-fatal `Throwable` failures to typed failed startup results, rethrow `VirtualMachineError` and `CancellationException` as specified, and ensure startup cannot remain `Opening`. Add the injected data-source and background-loop audit. Targeted F2 tests and the full Linux root suite pass; Windows behavior remains unverified.
- [x] **F1 — Controlled SQLite native bootstrap:** Implemented the candidate ladder, diagnostic attempts, content-addressed extraction, and safe retry/restart behavior before DB/sidecar access. Five F1 tests and the 116-test Linux root suite pass. Windows items W1–W3 and W5 remain human checks. **F1b (shipping the DLL in the install) is owner-gated and must wait for W1–W3.**
- [x] **F3 local diagnostics/UI — complete:** Bounded sqlite-jdbc JUL capture, full cause chain, retained DB path, local report path, and loader-log diagnostics appear in the startup gate. Reuses the existing full local cause-chain report; F3-d passed.
- [x] **F3 remote summary — gated on F4:** Added bounded root-cause-first summaries to both critical and fatal remote payloads only after F4’s fail-closed consent/redaction checks. F3-a…c pass: bounded/root-first/deepest-frame behavior, cause-cycle termination, and valid JSON for quotes, backslashes, newlines, and non-ASCII text.
- [x] **F4 — Consent, privacy, and secrets:** DB-independent opt-in consent, fail-closed unknown/unreadable states, one-time legacy migration, serialized Windows-path redaction, and the no-network sink seam pass F4-a…d. Consent appears after first-run onboarding when no decision exists. D1–D3 were resolved before this work; webhook constants remain untouched.
- [ ] **F5 decision intake — D4:** Ask/record the owner’s uninstall policy if they answer in Phase 1. Do not implement F5 until F11’s read-only path exists in Phase 2.
- [x] **Phase 1 exit:** F1–F4 Linux acceptance is met except outstanding WIN-VERIFY checks; F5 is explicitly deferred until after F11 and D4. `fixes/PHASE-1-REPORT.md` records D1–D5, the D4/F1b gates, test evidence, and the deferral.

## Phase 2 — P1/P2 data integrity and security findings

- **Status:** In progress — 2026-10-15 (F10, F12, F7, and F8 complete; F14a next).
- **Required order:** **F10 → F12 → F7 → F8 → F14a new-migration guard → F6 code → F9 gate/dry-run → F13 → F11 → F5 → F17 → F15 → approved F6/F9 live repairs → F14b legacy audit → F16/F18/F19.**

- **2026-10-15 Phase 2 start:** Owner authorized Phase 2 in the prescribed order. Checked the current schema target is v9 before adding the F10-d v9 fixture comparison; the existing v8→v9 migration test remains unchanged.
- **2026-10-15 F10 completion:** Added the `BEGIN IMMEDIATE` / SQL `COMMIT` / rollback-on-`Throwable` helper and converted `migrate`, `completeTask`, `insertSession`, `deleteHabit`, `saveFocusLauncherSession`, and `clearFocusLauncherSession`. SQL text and transaction nesting were preserved; `user_version` is still bumped last. No `autoCommit = false` remains in `Database.kt`.
- **2026-10-15 F10 verification:** `DatabaseTransactionTest` (4 tests) and `DatabaseTest` (13 tests) passed: 17 tests, 0 failures/errors/skips.
- [x] **F10 — Correct transaction helper:** `BEGIN IMMEDIATE` / `COMMIT` / rollback on any `Throwable`; six call sites converted without changing their SQL or nesting transactions. F10-a…d pass; current schema v9 comparison is covered.
- **2026-10-15 F12 API foundation:** Added `SettingRead`/`SettingWrite`, typed single and transactional batch operations, and a shared injected-store seam. Preserved the existing telemetry-consent migration result type and `getSetting`/`setSetting` compatibility API.
- **2026-10-15 F12 API verification:** `SettingsReadWriteTest` (4 tests) and `DatabaseTransactionTest` (4 tests) passed: 8 tests, 0 failures/errors/skips. PIN consumers are next; F12 remains incomplete until all ordered consumer groups are migrated.
- **2026-10-15 F12 PIN group:** `GlobalPin`, `SessionPin`, and `NuclearPin` now distinguish absent hashes from unreadable storage: unknown counts as configured and verification denies. PIN writes use typed results and throw a contextual persistence exception on failure. Three injected-unavailable-store tests pass; F8's later tri-state UI work remains separate.
- **2026-10-15 F12 PIN verification:** `PinSettingsUnavailableTest` (3) and `SessionPinInputTest` (5) passed: 8 tests, 0 failures/errors/skips.
- **2026-10-15 F12 VPN blocker:** `VpnBlocker` uses typed reads/writes. An unreadable enable value fails closed without caching a fallback; custom-list reads preserve cached values or remain retryable, and mutations refuse to overwrite an unreadable list. The screen surfaces failed VPN-setting mutations in a snackbar.
- **2026-10-15 F12 VPN verification:** `VpnBlockerSettingsTest` passed, covering injected unavailability, fallback recovery, last-known-good cache retention, and failed-write surfacing. F17's dedicated hot-path refresh work remains pending.
- **2026-10-15 F12 continuation:** Owner authorized implementation to continue. Current source and `StandaloneBlockSettingsTest` already cover the Standalone Block consumer group with typed settings, so the prior “Standalone Block next” status was stale; continue with Nuclear Mode/Kill Switch, then uninstall requirements, Weekly Report, Task Alarm, and App settings.
- **2026-10-15 F12 consumer completion:** Migrated the remaining Nuclear Mode/Kill Switch, uninstall protection, Weekly Report, Task Alarm, app preferences, and Always-On tray/bootstrap paths. Nuclear Mode, uninstall gates, and budget activation fail closed on unreadable/malformed values; alarm and report state is not reset or published until persistence succeeds. UI preference and tray write failures are logged or shown.
- **2026-10-15 F12 test attempt:** The first focused Weekly Report run lacked an initialized database and failed with `DatabaseUnavailableException`; changed those tests to use an isolated scratch database. The focused consumer suite then passed, and the final full suite passed without touching the user database.
- **2026-10-15 F12 verification:** `gradle :test --no-daemon --console=plain` passed 162 tests, 0 failures/errors/skips across 41 JUnit XML reports.
- [x] **F12 — Distinguish absent from unreadable settings:** Typed read/write results and all listed consumer groups are implemented in plan order. Unreadable security reads fail closed; restore-state reads retain last-known-good values; failed writes are surfaced. Injected-store tests cover each consumer group.
- **2026-10-15 F7 start:** Recounted the current source: 43 `createStatement()` calls, 17 already `.use`-scoped, 26 unscoped (19 query and 7 update sites). Recorded the extra migration `lockedVersion` query against the plan estimate in `AUDIT-ADDENDUM.md`.
- **2026-10-15 F7 verification:** The new F7-a…c tests passed, including 1,000 repetitions of each of the five cache-refresh reads and the Dashboard/Stats read set. The balanced source scanner finds all 43 calls scoped with `.use`; the pre-fix run failed the three expected leak/hygiene checks. Full root suite: 166 tests, 0 failures/errors/skips across 42 JUnit XML reports.
- [x] **F7 — Close JDBC statements:** Closed all 26 live unscoped sites (19 queries, 7 updates); preserved SQL and query behavior. Leak and balanced source-hygiene tests F7-a…c pass.
- **2026-10-15 F8 start:** Building on F12's typed settings results; tracing the three PIN helpers, entry/verification screens, and existing fail-closed tests before changing caller messaging.
- **2026-10-15 F8 verification:** Added `NOT_SET`/`SET`/`UNKNOWN` status and typed verification/clear results across Global, Session, and Nuclear PINs. Unknown denies and displays “Couldn't read PIN settings — try again”; PIN write failures are surfaced. Both generated-PIN flows verify the stored hash before returning. F8-a…e tests pass; full root suite: 171 tests, 0 failures/errors/skips across 43 JUnit XML reports.
- [x] **F8 — PIN checks fail closed:** Unknown reads deny and remain distinguishable from wrong PIN; writes report persistence failures and generated PINs require read-back verification. Hash strength and recovery design are unchanged.
- **2026-10-15 F14a start:** Add a required-column check after migration plus a PRAGMA-based, idempotent helper for future migrations; preserve all v1–v9 migration bodies.
- **2026-10-15 F14a verification:** Post-migration schema validation reports the first missing required column via `SchemaMismatchException`; the guarded migration helper is idempotent. F14a…c pass; full root suite: 174 tests, 0 failures/errors/skips across 44 JUnit XML reports. Updated the v9 migration test fixture to retain the full core schema.
- [x] **F14a — New-migration guard (before F6):** Required columns are checked after migration; future migrations have a PRAGMA-based `addColumnIfMissing` helper. Existing v1–v9 migration bodies are unchanged. F6 prerequisite satisfied.
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

