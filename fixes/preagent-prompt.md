# Pre-agent Prompt — FocusFlow SQLite / Database Audit

You are the implementation agent for the database reliability plan in this repository. **This database reliability assignment remains planning-only. Do not implement audit findings or change production behavior until the owner explicitly authorizes that work and any additional rules they intend to send have been incorporated.** The owner separately authorized only the v2.0.6 planned-changelog/version-metadata update; this does not authorize implementing the database plan.

## Read first

1. `replit.md` — current project and environment instructions.
2. `fixes/plan.md` — full scope, evidence, ordered phases, owner decisions, exclusions, and Windows matrix.
3. `fixes/tracker.md` — live status, gates, current discrepancies, and evidence requirements.
4. `fixes/focusflow-audit-evidence/README.md` — safety and limits for the supplied evidence kit. The scripts and raw outputs are evidence, not permission to execute them.

## Project context and source-of-truth

FocusFlow is a Windows-focused Kotlin/Compose Desktop app using Gradle and SQLite. The attached audit plan was prepared from a source snapshot labeled app version `2.0.3`; the database findings were reviewed against the `2.0.5` source. Release metadata has since been staged at `2.0.6` with the database reliability work still pending. Paths, line numbers, dependency/toolchain assumptions, tests, and findings may have drifted. Inspect the current checkout and `git status` before every implementation batch; the current source and owner instructions take precedence.

F26 is closed as stale: the current checkout contains `recovery/` and its Gradle build file, despite the older audit archive omitting it. Root `AUDIT-ADDENDUM.md` records this; do not remove `include("recovery")` or spend implementation time on F26.

## Required execution rules

- Work directly; do not create or invoke sub-agents.
- Follow the reconciled execution sequence in `fixes/tracker.md` where it corrects the older plan’s sequencing or stale assumptions. Phase 0 is baseline/tooling only and changes no production code.
- Before a phase, add a dated **In progress** note to `fixes/tracker.md`; update it after each meaningful change. Preserve failed attempts and append successful reruns.
- Work one finding per small branch/commit, with the finding ID in the commit title. Follow existing repository conventions. Do not push to GitHub or start/poll/monitor GitHub Actions unless the owner explicitly asks.
- Stop and ask on every owner decision. A recommendation in `plan.md` is not approval. Never implement a degraded enforcement design, data deletion, packaging exception, dependency upgrade, PIN redesign, or other owner-gated change by inference.
- Do not run a real-profile data repair before F15’s backup verification and a dry run. Do not add a production `"unknown"` app-version fallback without owner approval; set the Gradle test property instead.
- Preserve unrelated working-tree changes and user data. Do not run database repair, reset, migration, restore, deletion, or sidecar operations on a real profile.
- Do not claim a Windows behavior is verified by Linux/Replit tests. Only a human tester may tick W1–W15.
- At the end of each phase, write `fixes/PHASE-<n>-REPORT.md` with changed and read files, exact commands/results, failures and reruns, tests, discrepancies, owner gates, and outstanding Windows checks.

## Non-negotiable technical and safety rules (R1–R16)

**R1 — Read before editing.** Read every file to be changed in full and the callers of changed behavior. List files read in the commit/PR note. Do not edit from grep excerpts.

**R2 — Evidence discipline.** Mark claims as verified, inferred, or unverified; cite file/line or command. Never call Windows-dependent behavior “fixed” without running it on Windows; say “implemented, WIN-VERIFY pending”.

**R3 — User data is untouchable.** Never delete, truncate, move, migrate, or reset `%USERPROFILE%\.focusflow\*` or any real user DB. Tests redirect `user.home` to a temporary directory. Any approved data repair requires a verified backup and dry run; never delete rows without explicit owner approval; prefer marking.

**R4 — Secrets.** Never print, decode, copy, log, quote in a diff, or commit webhook values. Do not inspect the secret-bearing lines in `CrashReporter.kt` or `ReviewPromptService.kt`. Treat existing endpoints as compromised; rotation is an owner action. Do not ask the user to paste secret values; use the workspace secrets flow for any approved credential configuration.

**R5 — Scope.** No drive-by refactors, renames, formatting sweeps, or dependency upgrades. sqlite-jdbc upgrades require owner approval. New defects go in root `AUDIT-ADDENDUM.md` with evidence; do not fix them inside another finding.

**R6 — Error handling.** No `catch (_: Exception) {}`. Log context or return a typed result. At DB/startup/telemetry/background boundaries catch `Throwable`, rethrow only `CancellationException` and `VirtualMachineError`, and convert other failures to typed errors. Distinguish absent from unreadable settings. Security reads fail closed; availability failures show a message. Transactions roll back on `Throwable` and never commit by setting auto-commit back to true.

**R7 — Threading.** New DB I/O must not run on Compose/AWT threads. Enforcement hot paths use caches only. Never use `runBlocking`.

**R8 — Tests ship with changes.** Each finding’s tests must fail before and pass after. Anything touching sqlite-jdbc process-global loading state must run in a fresh JVM using `ForkedJvm`.

**R9 — Telemetry/privacy.** No test or script makes a network call. Do not run any DB-init failure test/script against the default DB path before F4.0’s telemetry seam exists. Use the fake/no-op telemetry sink, `focusflow.telemetry=off`, and temporary `user.home`; no payload may expose username, home path, or machine name. Consent is fail-closed after F4.

**R10 — Plan/source mismatch.** If code differs, citations moved, or a claim fails to reproduce, stop that finding, record evidence in root `AUDIT-ADDENDUM.md`, then continue other eligible work. Do not silently adapt.

**R11 — Windows reality.** Do not guess at Windows file-sharing or registry behavior from Linux. Add required cases to the human Windows matrix and leave them marked WIN-VERIFY.

**R12 — No-touch areas.** Do not change enforcement semantics, UI visual design, installer/MSIX/Store/signing, or dependencies. F1b is the sole packaging exception and still requires owner approval.

**R13 — Commit hygiene.** Follow the repository’s existing commit style and prefix commit subjects with the finding ID. Never commit jars, databases, logs, scratch files, or webhook values.

**R14 — Compatibility.** Preserve existing user DBs. Schema changes require a new numbered migration and a migration test from the current schema. Never edit an existing `migrateVn()`.

**R15 — Phase reports.** Report at the end of every phase in `fixes/PHASE-<n>-REPORT.md`: completed work, test names, exact commands/results, outstanding WIN-VERIFY items, and discrepancies.

**R16 — Task completion.** A task is complete only when the root `:test` task passes and its generated JUnit XML is checked, no new banned catch exists, all task acceptance lines are true, and its report entry exists. Use `gradle :test --no-daemon` with the workflow’s JDK environment in this checkout; `./gradlew` cannot start because `gradle/wrapper/gradle-wrapper.jar` is absent. Do not generate a wrapper as unrelated work.

## Mandatory order and known gates

1. **Phase 0:** Review existing tests for default-path DB failures/network calls, then capture the untouched-tree baseline and add isolated test helpers. The current source scan found DB init tests use explicit temporary paths and no direct telemetry/network test references; recheck before running. If the suite cannot safely run, record why and stop; do not “fix” the toolchain.
2. **Phase 1:** F28 test configuration → F4.0 → F2 → F1 → F3 local diagnostics/UI → F4 consent/redaction → F3 remote-payload summary. F4.0 must exist before any failure-injection tests. Do not expand telemetry payloads before F4 redaction.
3. Ask for D4 during Phase 1 if possible, but defer F5 implementation/sign-off until after F11 in Phase 2.
4. **Phase 2:** F10 → F12 → F7 → F8 → F14a new-migration guard → F6 code → F9 gate/dry-run → F13 → F11 → F5 → F17 → F15 → approved F6/F9 live repairs → F14b legacy-migration audit → F16/F18/F19.
5. F6/F9 dry runs may be prepared earlier, but apply no live repair until F15 can produce and verify a backup and the required owner decisions are recorded. The F6 migration guard must exist before adding v10; never edit v1–v9.
6. **Phase 3:** F20–F30 after Phase 2, except F26 is closed as stale and F28 was pulled forward. Test F27 only on the actual shipped JDK/current package formats; include MSIX only if it is actively distributed.
7. Stop at owner decisions D1–D19 as specified in the tracker; do not interpret defaults/recommendations as authorization.

## Supplied audit evidence

The archive was extracted under `fixes/focusflow-audit-evidence/`. Its README explicitly warns that reproductions must not use the real `CrashReporter`, must set `-Duser.home` to a fresh empty scratch directory, and must not expose webhook values. Do not run scripts until reviewing the exact script and dependencies, confirming the telemetry seam is safe, and confirming all outputs remain in scratch space. The evidence was inspected but not executed for this planning task. `SqliteJdbcNativeSmokeTest` already exists; it does not replace the restricted-temp Windows test.

## Verification and final report

Use the project’s current Gradle setup and report the actual JDK/Gradle versions. In this checkout, use the configured system `gradle` command with the workflow’s JDK environment: `./gradlew` cannot start because `gradle/wrapper/gradle-wrapper.jar` is absent. Do not create a wrapper as unrelated work. Require the final root `:test` result and generated JUnit XML; compilation or `testClasses` alone is not test evidence. Never use GitHub Actions as a substitute unless the owner explicitly requests it.

At completion, report:

1. Findings implemented and files changed/read.
2. Exact tests/build commands and results, with report paths.
3. Owner decisions, discrepancies, failures, and deferred items.
4. Windows-only checks not run and the evidence still required.

Do not mark the audit fully complete while required owner decisions, phase criteria, or Windows-only checks remain open.

