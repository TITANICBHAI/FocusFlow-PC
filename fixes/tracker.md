# FocusFlow Desktop — SQLite / Database Fix Tracker

- **Source plan:** [plan.md](plan.md)
- **Agent instructions:** [preagent-prompt.md](preagent-prompt.md)
- **Audit evidence:** [focusflow-audit-evidence/README.md](focusflow-audit-evidence/README.md) and [focusflow-audit-evidence.zip](focusflow-audit-evidence.zip)
- **Overall status:** Planning package created; implementation has not started. Await owner authorization and any additional owner rules before Phase 0.

## Tracking rules

- Follow `plan.md` in order: **Phase 0 → Phase 1 → Phase 2 → Phase 3**. Within each phase, use its explicit finding order and dependencies.
- Before starting a phase, add a dated **In progress** note. Update this tracker after meaningful changes; append history rather than erasing failures.
- Tick an item only when its required tests and acceptance criteria pass. A source review, compile, or Linux test does not verify Windows behavior.
- Keep owner decisions and Windows checks open until the owner/tester supplies the required decision or evidence. The agent must not tick W1–W15.
- For every phase, record changed/read files, exact commands and results, failures and reruns, discrepancies, owner gates, and outstanding Windows checks in `fixes/PHASE-<n>-REPORT.md`.
- Follow R1–R16 in [plan.md](plan.md) and the operating instructions in [preagent-prompt.md](preagent-prompt.md).

## Current findings and preparation record

### 2026-10-14 — Planning package prepared

- **Files added:** `fixes/plan.md`, this tracker, `fixes/preagent-prompt.md`, the supplied evidence ZIP, and its 29 extracted files.
- **Plan integrity:** `fixes/plan.md` matches the supplied Markdown byte-for-byte; SHA-256: `92abc004b1e807dfc04e4c4a70841e1c9f243dab8b40460966674b94036119b2`.
- **Evidence handling:** ZIP listing was inspected and files were extracted to a temporary directory before copying. The reproduction scripts were not run.
- **Source snapshot caveat:** The plan audits an app snapshot labeled `2.0.3`; the current project was updated to `2.0.5`. Treat current source, build configuration, and working tree as authoritative; re-check all paths, line references, versions, and assumptions.
- **Known discrepancy — F26:** The current checkout contains a `recovery/` directory and module files, whereas the audited archive reportedly did not. Do not remove `include("recovery")`; verify current Gradle behavior and document the discrepancy in root `AUDIT-ADDENDUM.md` before addressing F26.
- **Verification state:** No app code, tests, audit harness, or Windows procedure has been run as part of preparing these documents.

## Phase 0 — Baseline and tooling

**Status:** Not started — no production-code changes allowed in this phase.

- [ ] **P0-1 — Baseline:** Run the existing Gradle test suite on the untouched tree; record pass/fail counts, duration, and actual JDK. Do not modify existing `DatabaseTest` tests. If the build cannot run, report why in `fixes/PHASE-0-REPORT.md` and stop; do not change the toolchain to force a pass.
- [ ] **P0-2 — Test helpers:** Add and smoke-test `TempHome`, `ForkedJvm`, and `StatementLeakAssert` under `src/test/kotlin/com/focusflow/testutil/`. `TempHome` must redirect `user.home` to a scratch directory, disable telemetry, set a test app version, reset the database before/after, and restore JVM properties. `ForkedJvm` must always disable telemetry and use a fresh JVM. Add the clock seam when beginning F6. Add `FakeTelemetrySink` only after F4.0 in Phase 1.
- [ ] **P0-3 — Owner facts:** Ask which machine produced the reports, request the local crash report, and ask about `%TEMP%`/security software; record answers in root `AUDIT-ADDENDUM.md`. These facts do not block Phase 1.
- [ ] **Phase 0 exit:** Baseline recorded; helper smoke tests pass; `fixes/PHASE-0-REPORT.md` is complete.

## Phase 1 — P0 startup failure and blast radius

- **Status:** Not started.
- **Required order:** **F28 → F4.0 → F2 → F1 → F3 → F4 remaining work → F5.**

- [ ] **F28 — CrashReporter startup safety (pulled forward):** Provide a safe `"unknown"` app-version fallback and set app-version/telemetry-off test properties. Test a fresh JVM without the property and verify reporting does not throw.
- [ ] **F4.0 — Telemetry test seam (before failure tests):** Route automatic reports through `TelemetrySink`; support a no-op sink when telemetry is disabled and a recording fake in tests. No behavior change beyond the explicit kill switch; tests/scripts must never send network traffic.
- [ ] **F2 — Throwable-safe database startup:** Convert non-fatal `Throwable` failures to typed failed startup results, rethrow `VirtualMachineError` and `CancellationException` as specified, and ensure startup cannot remain `Opening`. Add the injected data-source and background-loop audit. Tests F2-a…c.
- [ ] **F1 — Controlled SQLite native bootstrap:** Implement the planned candidate ladder, diagnostic attempts, content-addressed extraction, and safe retry/restart behavior without touching the DB on load failure. Tests F1-a…d; Windows items W1–W3 and W5 remain human checks. **F1b (shipping the DLL in the install) is owner-gated and must wait for W1–W3.**
- [ ] **F3 — Root-cause reporting:** Capture sqlite-jdbc JUL output; produce bounded, cycle-safe root-cause summaries; redact identifying values from telemetry; show the full cause and local report path in the startup gate. Tests F3-a…d.
- [ ] **F4 — Consent, privacy, and secrets:** Use a DB-independent consent store, fail closed unless explicitly granted, migrate the old setting once, redact payloads, and preserve the no-network test seam. Resolve owner decisions D1–D3 before touching secret/consent wording or endpoint behavior. Tests F4-a…d.
- [ ] **F5 — Safe uninstall when DB is unavailable:** Stop until owner decision D4. Implement only the selected policy; BUSY/LOCKED must continue to block. Depends on the read-only wizard path in F11; resolve this cross-phase dependency before claiming F5 complete. Tests F5-a…c; W6 remains human-only.
- [ ] **Phase 1 exit:** F1–F5 acceptance is met except explicitly outstanding WIN-VERIFY checks; tests pass; `fixes/PHASE-1-REPORT.md` records open owner decisions D1–D5, including F1b approval.

## Phase 2 — P1/P2 data integrity and security findings

- **Status:** Not started.
- **Required order:** **F10 → F12 → F7 → F8 → F6 → F9 → F13 → F11 → F17 → F15 → F14 → F16/F18/F19.**

- [ ] **F10 — Correct transaction helper:** Use `BEGIN IMMEDIATE` / `COMMIT` / rollback on any `Throwable`; migrate the six listed call sites without changing their SQL or nesting transactions. Tests F10-a…d; preserve the v9 migration fixture requirement after checking the current schema version.
- [ ] **F12 — Distinguish absent from unreadable settings:** Add typed read/write results and migrate consumers in the exact order in the plan: PINs, VPN blocker, Standalone Block, Nuclear Mode/Kill Switch, uninstall requirements, then remaining listed services/UI. Unreadable security reads fail closed; restore-state reads retain last-known-good values; failed writes are surfaced. One injected-unavailable-store test per migrated consumer.
- [ ] **F7 — Close JDBC statements:** Close all 18 leaking `executeQuery` statements, then the seven `executeUpdate` statements; add leak and source-hygiene tests. Tests F7-a…c; scanner must find no unwrapped `createStatement()`.
- [ ] **F8 — PIN checks fail closed:** Build on F12; distinguish `NOT_SET`, `SET`, and `UNKNOWN`, deny on unknown storage, and report write failures. Verify PIN read-back before returning an auto-generated PIN. Do not change hash strength or add recovery design without owner approval D12. Tests F8-a…e.
- [ ] **F6 — Recurring task duplication:** Add the new numbered migration and recurring-template parent relation; make generation idempotent. Repair existing rows only after verified backup and a dry run; mark, never delete, unless owner explicitly approves a separate deletion policy (D7). Tests F6-a…d.
- [ ] **F9 — Reconcile orphan sessions:** Back up first; add dry-run/idempotent reconciliation and the planned 12-hour active-session bound. Ask owner to approve the end-time policy (D8). Tests F9-a…d.
- [ ] **F13 — Order focus-session writes:** Ensure end waits for start, log failed writes, and allow bounded completion on dispose. Tests F13-a…c; note whether the dispose race already passes before changes.
- [ ] **F11 — Read-only uninstall wizard:** Add an open that does not snapshot, mutate, checkpoint, migrate, or recover the DB. Tests F11-a…d; W6 remains human-only.
- [ ] **F17 — Recover VPN settings after read failures:** Keep last-known-good volatile state, refresh outside the enforcement hot path, and report failed writes. Tests F17-a…c.
- [ ] **F15 — Verifiable backups:** Run SQLite `quick_check`, distinguish unverified backups, report failures, correct sidecar naming, and make unsafe `restoreBackup` uncallable. Real restore, second destination, and post-recovery UI require owner approval (D9). Tests F15-a…d.
- [ ] **F14 — Verify schema instead of swallowing migration errors:** Leave migrations v1–v9 untouched; verify required columns and use a guarded helper for new migrations. Tests F14-a…b.
- [ ] **F16 — Measure database monitor contention:** Add the planned debug-only measurement, run a 10-minute session, and report the top 10 offenders. Do not restructure threading without D19 approval.
- [ ] **F18 — Database unavailable behavior:** No degraded blocker without a separate approved design. The plan permits the small visible “Blocking is OFF” message as option A; confirm task authorization before applying it. Ask the owner before choosing another behavior.
- [ ] **F19 — Mid-run DB loss:** Write only the one-page health/reopen/banner design note; do not implement it without owner approval D11.
- [ ] **Phase 2 exit:** All non-owner-gated findings have passing tests; report open gates D7–D11 and other F6/F9/F15 decisions in `fixes/PHASE-2-REPORT.md`.

## Phase 3 — P3 hardening and cleanup

**Status:** Not started. Begin only after Phase 2; the plan allows any order here except F28 is already pulled forward.

- [ ] **F20 — Index-friendly date queries:** Replace date-wrapped indexed columns and per-day N+1 queries while preserving results/storage; add boundary, query-plan, grouping, and empty-table tests F20-a…d.
- [ ] **F21 — Idempotent task completion:** Update only incomplete existing tasks and increment daily completion once; add tests F21-a…c.
- [ ] **F22 — Atomic activity clear:** Clear only the four explicitly listed activity areas in one transaction and take a verified backup first; abort on backup failure. Do not broaden deletion or add a Global PIN gate before owner decisions D13. Tests F22-a…c.
- [ ] **F23 — Consistent CSV exports:** Use RFC-4180 quoting, correct row counts, and distinct saved/cancelled/failed results across export paths. Ask owner about formula-prefix protection and UTF-8 BOM (D14). Tests F23-a…c.
- [ ] **F24 — Bounded crash reports:** De-duplicate matching reports and cap only the specified app-owned locations; do not delete Desktop reports. Ask owner before changing the Desktop-first policy (D15). Tests F24-a…b.
- [ ] **F25 — Central application paths:** Add getter-based `AppPaths` that honors changing `user.home`, replace the four DB path computations, and keep other replacements limited to trivial cases. Tests F25-a…b.
- [ ] **F26 — Recovery Gradle module discrepancy:** Current checkout contains `recovery/`, unlike the audited archive. Follow the current source; verify `gradle help`, record the discrepancy in root `AUDIT-ADDENDUM.md`, and ask the owner whether any change is needed (D16). Do not remove the include blindly.
- [ ] **F27 — SelectorProvider flag:** No code change until human W9 is complete on the same major JDK and the MSIX case. Then ask/record the owner’s decision (D17); do not remove the flag based on the audit alone.
- [ ] **F28 — Test-configuration regression:** Completed earlier in Phase 1; retain its test/configuration coverage in the final audit.
- [ ] **F29 — Test-gap closure:** Reconcile all required test classes and finding-specific cases against the current tree; do not claim coverage from compilation alone.
- [ ] **F30 — Current streak cap:** Ask whether 90 days is intentional (D18); change only after approval. If approved as a defect, pass F30-a…c.
- [ ] **Phase 3 exit:** All non-owner-gated acceptance/tests pass; `fixes/PHASE-3-REPORT.md` lists remaining owner choices and Windows checks W9/W15 as applicable.

## Owner decisions — pending unless the owner explicitly answers

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
| D16 | Whether the recovery Gradle module is still needed | F26; current checkout already has `recovery/`. |
| D17 | Keep/replace/remove SelectorProvider flag | F27; only after W9. |
| D18 | Whether the 90-day current-streak cap is intentional | F30. |
| D19 | Threading restructure | F16; only after measurement. |

## Windows verification matrix — human only

All items stay unchecked until a human runs the named scenario on Windows and records build, OS, result, and evidence. Use a throw-away VM/profile for destructive environment scenarios. Never run W2/W3/W8 against a user's normal environment.

- [ ] **W1:** Clean per-user EXE install loads DB/native DLL in the expected location without temp extraction.
- [ ] **W2:** Broken `%TEMP%`/`%TMP%`: compare released and fixed builds; establish whether the reported failure reproduces.
- [ ] **W3:** DLL blocked from temp by audit-mode policy; verify fallback and safe failure UI.
- [ ] **W4:** Space/non-ASCII profile path; verify DB, library, reports, and backups.
- [ ] **W5:** EXE, MSI, and MSIX install/start/uninstall matrix; record native-library location.
- [ ] **W6:** Uninstall wizard against active writer, unavailable native library, and active protection.
- [ ] **W7:** 30-minute watchdog failure soak using a test endpoint only; verify bounded reports/telemetry.
- [ ] **W8:** Backup/WAL/SHM behavior while app is open; record actual Windows sharing errors.
- [ ] **W9:** SelectorProvider with/without flag on the bundled JDK major, including MSIX.
- [ ] **W10:** Fresh-profile telemetry consent and network behavior; no send before explicit consent if opt-in is selected.
- [ ] **W11:** 24-hour memory soak before/after F7.
- [ ] **W12:** Recurring-task behavior and dry-run repair on a copy of a polluted DB.
- [ ] **W13:** Crash-log destination with Desktop redirected through OneDrive.
- [ ] **W14:** Installed packaged layout and Compose resource directory for F1b.
- [ ] **W15:** CSV row counts above 1,000 and clear-activity atomicity.

## Cross-cutting completion checks

- [ ] Each finding has tests that fail before and pass after, with fresh-JVM isolation for sqlite-jdbc static state.
- [ ] No tests/scripts make network requests; no production webhook is contacted.
- [ ] No test or script touches a real profile/database; all use a scratch `user.home`.
- [ ] No new `catch (_: Exception) {}`; no UI-thread database I/O or `runBlocking`.
- [ ] Existing migration methods and user data remain intact; new schema uses a numbered migration.
- [ ] All OWNER DECISION items are resolved or remain explicitly deferred.
- [ ] Final reconciliation matches implementation, test XML, phase reports, and Windows evidence; final report lists unverified behavior and discrepancies.

