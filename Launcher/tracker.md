# FocusFlow Focus Launcher Reliability — Tracker

- **Source plan:** [plan.md](plan.md)
- **Agent instructions:** [preagent-prompt.md](preagent-prompt.md)
- **Supplemental rules:** [rules.md](rules.md)
- **Overall status:** P0.1/P0.2/P0.5 and P1.1/P1.2 have Linux implementation evidence. P1.3/P1.4 implementation and Linux test evidence are complete; their Windows acceptance remains owner-pending. P0.3/P0.4 acceptance still has open gates. P1.5/P1.6 and P2.2/P2.3 have not started; P2.1 is complete with Linux T19 evidence.

## Tracking rules

- The plan is the scope and acceptance contract. Work only on tasks in §§4–6, and only change files listed for that task. Record other findings as out-of-scope; do not fix them.
- Complete P0 in order: **P0.1 → P0.2/P0.3/P0.4 as one coordinator refactor → P0.5**. P0.5 depends on P0.4. P1/P2 tasks may follow in any order except where the plan states a dependency.
- Before starting each implementation batch, add a dated **In progress** note to that section. Update the notes after each meaningful change.
- Check a task or test only after its complete acceptance criteria pass. A compile, source review, Linux test, or attempted Windows check is not evidence for a different kind of acceptance.
- Keep Windows-only checks in §9 unchecked until a human owner/tester supplies evidence from Windows. Do not claim Linux/Replit proves Windows behavior.
- Record changed and read files, exact commands and results, source evidence, failed attempts and reruns, decisions, blockers, and deferred work. Append outcomes; do not erase failures.
- Reconcile every checkbox with the current code and evidence before finishing. Keep incomplete items open and explain why.
- Follow all safety and operating rules in [rules.md](rules.md) and [preagent-prompt.md](preagent-prompt.md).

## Required agent rules

- Work alone. Do not create, invoke, or delegate to sub-agents.
- Follow the plan's implementation order and this tracker. Do not silently widen scope or change the plan's contract.
- Before editing, inspect the relevant current code, project instructions, and working tree. Preserve unrelated user changes and user data.
- Respect the plan's no-touch list unless the owner explicitly changes the scope.
- Treat task replacement and any other deletion of user data as destructive. Keep replacement disabled by default, preserve explicit confirmation and required safety guards, and do not perform destructive actions without the owner's authorization.
- Run applicable Gradle tests using the current workflow or scripts under `scripts/`. Android tests do not apply to this desktop project. Use GitHub Actions only if the owner explicitly requests APK-building verification; otherwise state clearly which checks were not run.
- Do not push to GitHub or start, poll, or monitor GitHub Actions unless the owner explicitly asks.
- Do not expose, copy into files, or print secrets or credentials.

## Planning-package record

### 2026-10-16 — Package prepared; implementation not started

- **Scope of this batch:** Prepare the launcher-specific plan, tracker, rules, and pre-agent prompt from the supplied plan and guidance.
- **Plan integrity:** `Launcher/plan.md` is an exact copy of the supplied plan. SHA-256 for source and copy: `daf1370b64b343115a862a2be501ea4d8a1e35db827db619555488f470ca66c7`.
- **Rules integrity:** `Launcher/rules.md` is an exact copy of the supplied supplemental instructions, renamed from `.txt` to `.md`. SHA-256 for source and copy: `b211b1e7a13b25c46837d3c89e1ab75ed4d3c54f52c7699515477241d8d8d4d2`.
- **Files added:** `Launcher/plan.md`, `Launcher/tracker.md`, `Launcher/preagent-prompt.md`, `Launcher/rules.md`.
- **Files read for scope and format:** the complete attached reliability plan and supplemental rules; `fixes/tracker.md`, `fixes/preagent-prompt.md`, `Plan/tracker.md`, `Plan/preagent-prompt.md`, `replit.md`, and `.agents/memory/focus-launcher-task-brief.md`.
- **Working tree before edits:** `main` matched `origin/main`; only the two supplied attachments were untracked.
- **Application source changes:** None. No implementation, Gradle test, Windows check, GitHub push, or GitHub Actions run was performed.
- **Document verification:** Confirmed all four package files are present and non-empty; `cmp -s` confirmed exact plan/rules copies; `grep -nE '[[:blank:]]+$' Launcher/tracker.md Launcher/preagent-prompt.md Launcher/rules.md` returned no matches. Final working-tree status shows only `Launcher/` and the two supplied attachments as untracked.

### 2026-10-16 — Supplemental rules embedded in tracker and prompt

- **Change:** Added the owner-provided working rules directly to the tracker's **Required agent rules** and the pre-agent prompt's **Required working rules**, following the sectioned format used by the existing `fixes/` documents. Kept `rules.md` as the unchanged source copy.
- **Files changed:** `Launcher/tracker.md`, `Launcher/preagent-prompt.md`.
- **Verification:** The shell checks using `grep -Fqi` for the required phrases in both docs passed; `grep -nE '[[:blank:]]+$' Launcher/tracker.md Launcher/preagent-prompt.md Launcher/rules.md` found no trailing whitespace; `cmp -s` confirmed `Launcher/rules.md` remains identical to the supplied rules.
- **Application source changes:** None.
- **Check note:** An initial literal-phrase coverage check did not match the prompt's path-specific equivalent for following the plan. Standardized the wording to “Follow the plan's implementation order”; the `grep -Fqi` rerun passed in both docs.

## P0 — Reported launch-reliability failures

**Status:** P0.1 and P0.2 complete with Linux automated evidence; P0.3 coordinator behavior is implemented, but T4's visible elapsed-progress notice remains P1.6; P0.4 acceptance remains open for T8/P1.1 and owner-run Windows checks; P0.5 implementation and Linux acceptance are complete, with Windows rows 4 and 12 still due from the owner.

### P0.1 — Per-attempt launch tracing

**Status:** Complete — 2026-10-16. Automated acceptance passed on Linux; Windows runtime verification remains open.

### 2026-10-16 — P0.1 — Initial in-progress entry

- **Scope:** Add per-attempt IDs, bounded phase/event logging, exactly one typed terminal summary, foreground-lock call-result logging, and T22 coverage. No P0.2–P0.5 behavior changes.
- **Initial findings at entry creation:** The coordinator was reported to emit per-refresh polling logs without attempt IDs; the keyboard hook was reported to ignore lock/unlock return values; the coordinator test suite was reported to lack a T22 summary assertion. The resumed source review below supersedes these findings for the current checkout.
- **Files read:** `Launcher/plan.md`, `Launcher/tracker.md`, `Launcher/rules.md`, `Launcher/preagent-prompt.md`, `replit.md`, `.agents/memory/focus-launcher-task-brief.md`, `LauncherLaunchCoordinator.kt`, `JvmLauncherLaunchPlatform.kt`, `WindowRaiser.kt`, `GlobalKeyboardHook.kt`, `EnforcementLog.kt`, and `LauncherLaunchCoordinatorTest.kt`.
- **Working tree at entry creation:** `main` was reported as ahead of `origin/main` by two commits, with the supplied implementation prompt untracked. The resumed review below records the current Git state.
- **Verification at entry creation:** Pending.

### 2026-10-16 — P0.1 — Resumed for implementation and verification

- **Current source review:** The checked-out coordinator already contains attempt IDs, phase timings, one-summary guarding, bounded poll-change logging, and typed terminal outcomes. `GlobalKeyboardHook` already records and logs both foreground-lock call results. The coordinator test file already includes terminal-summary assertions, including failed and opened attempts, unique IDs, and a per-attempt line-count check.
- **Working tree:** `main` is ahead of `origin/main` by one commit and clean. This differs from the earlier note's two-commit/untracked-file snapshot; current Git state takes precedence.
- **Files read this batch:** `LauncherLaunchCoordinator.kt`, `JvmLauncherLaunchPlatform.kt`, `WindowRaiser.kt`, `GlobalKeyboardHook.kt`, `EnforcementLog.kt`, `LauncherLaunchCoordinatorTest.kt`, `build.gradle.kts`, and `gradle.properties`, in addition to the plan and project instructions.
- **Batch-start plan:** Verify current P0.1 behavior against T22 and the logging constraints, then make only necessary P0.1 corrections. No P0.2–P0.5 launch behavior changes.
- **Verification attempt 1:** `export JAVA_HOME=/nix/store/c8hr2f0b0dm685yx1dkp6bw24bpx495n-graalvm19-ce-22.3.1; export PATH="$JAVA_HOME/bin:$PATH"; gradle :test --no-daemon` exceeded the 300-second shell timeout after `:processTestResources`; output did not reach `:test` or `BUILD SUCCESSFUL`, and no test XML report was produced. This is not test evidence.
- **JVM observation:** The explicit `JAVA_HOME` binary reports GraalVM Java 19.0.2, while `gradle --version` reports launcher and daemon JVM 21.0.7. The test-worker JVM remains unverified.
- **Verification attempt 2 (existing source, before the follow-up changes below):** `export JAVA_HOME=/nix/store/c8hr2f0b0dm685yx1dkp6bw24bpx495n-graalvm19-ce-22.3.1; export PATH="$JAVA_HOME/bin:$PATH"; gradle :test --no-daemon --console=plain` completed with `BUILD SUCCESSFUL in 2m 27s`. Inspected XML: 60 suites, 237 tests, 0 failures, 0 errors, 0 skipped; `LauncherLaunchCoordinatorTest` had 10 passing tests.
- **P0.1 gap found after the passing baseline run:** The coordinator recorded wait results and raise timing, but did not consistently log the `awaitWindow` start on the spawn path or an explicit raise-phase start. Add these phase transitions, retain the existing bounded line count, and assert them in the trace test.
- **Changes made:** Added a snapshot-start event, one await-window-start event with a reason on each path, and a raise-start event. Extended the trace test to assert phase events and no more than ten captured attempt lines. Added the required plain-language `IMP` item to the 2.0.6 planned changelog.
- **Final files changed:** `src/main/kotlin/com/focusflow/services/LauncherLaunchCoordinator.kt`, `src/test/kotlin/com/focusflow/services/LauncherLaunchCoordinatorTest.kt`, `src/main/kotlin/com/focusflow/ui/screens/ChangelogScreen.kt`, and `Launcher/tracker.md`. Existing per-attempt IDs, terminal summaries, typed codes, foreground-lock result logging, and T22 coverage were present before this batch; `JvmLauncherLaunchPlatform.kt`, `WindowRaiser.kt`, and `GlobalKeyboardHook.kt` required no code edits.
- **Final verification:** `export JAVA_HOME=/nix/store/c8hr2f0b0dm685yx1dkp6bw24bpx495n-graalvm19-ce-22.3.1; export PATH="$JAVA_HOME/bin:$PATH"; gradle :test --no-daemon --console=plain` completed with `BUILD SUCCESSFUL in 45s`. Inspected test XML: 60 suites, 237 tests, 0 failures, 0 errors, 0 skipped; `LauncherLaunchCoordinatorTest` had 10 passing tests. T22 assertions verified one summary per attempt, unique IDs, required summary fields, phase-start events, and at most ten logged lines per attempt. `git diff --check` passed. Existing Kotlin opt-in and deprecation warnings remain.
- **Report-inspection note:** The first targeted XML lookup used test method names without JUnit's trailing `()` and returned `KeyError`; the corrected lookup confirmed the three trace-related test cases passed.
- **Evidence limitations and safety:** Linux fake-platform tests do not verify Win32 behavior. No P0.2–P0.5 behavior or enforcement safety path was changed. No §9 Windows checks were run; leave all rows pending. The foreground-lock call-result behavior is source-confirmed, not Windows-runtime-confirmed.
- **Safety paths re-read after the code changes:** `FocusLauncherService.verifyPin`, `isLaunchAllowed`, `exit`, `startBreak`, `endBreak`, kill-switch activation/deactivation and emergency restore; `KillSwitchService.activate/deactivate`; the PIN-gated launcher exit UI; `NuclearMode` escape-tool name and path checks plus kill selection; and `GlobalKeyboardHook` shortcut suppression and foreground-lock result recording. The diff confirms none of these safety branches were modified.
- **Constants / decisions:** No launch-budget constants changed; existing `MAX_POLL_CHANGE_LOGS=1` remains. No owner decision was needed; P0.5 D1-B is not part of this batch.

- [x] Add attempt IDs and phase timings; log meaningful polling changes only.
- [x] Emit exactly one terminal summary per attempt with the fields required by plan §4/P0.1.
- [x] Record foreground-lock call results; stay within the plan's logging and privacy limits.
- [x] **Acceptance:** T22 passes; one failed attempt can be diagnosed by attempt ID with about ten or fewer lines.
- **Implementation note:** Add a dated In progress entry here before editing.

### P0.2 — Remove sticky retry state

- [x] Decide spawn versus attach from live process state; remove wait-only and spawn-budget decision flags.
- [x] Keep only a short per-app debounce that prevents duplicate spawns without forcing a wait.
- [x] **Acceptance:** T1, T2, T5, T6, and T15 pass.

### P0.3 — Liveness-based wait and watchdog

- [x] Add liveness-based waiting, typed early exit/no-window outcomes, per-attempt tokens, watchdog, and per-app cancellation.
- [x] Ensure stale workers cannot write state for a newer attempt and cancellation leaves no state/jobs behind.
- [x] **Acceptance:** T2, T3, T4, T7, and T12 pass; the user-visible elapsed-progress notice in T4 is implemented and covered by P1.6 tests.

### P0.4 — Process lineage and window selection

- [x] Add/reuse a shared process snapshot with PID, parent PID, creation time, image name, and lazy cached full paths.
- [x] Track the spawn PID, descendants, and matching live instances as a union; reject PID reuse by creation time.
- [x] Preserve window enumeration order and select/re-evaluate the best candidate as specified in the plan.
- [x] Handle Store/UWP windows as specified or fail immediately and clearly.
- [ ] **Acceptance:** T3, T8, T16, T17, and T21 pass; applicable Windows archetypes B–D and F are recorded for owner verification.

### P0.5 — Protect launch chains from kiosk enforcement

- [x] Keep app-chain exceptions disabled by default; enable only through the Settings toggle after Global PIN verification, and allow free disable.
- [x] If Global PIN is unset, require setup and a second entry/verification before enabling. If PIN state or database is unavailable, fail closed; removing the Global PIN forces the setting off.
- [x] Require fresh Global PIN verification on each Enter Launcher attempt while exceptions are enabled; provide direct Settings navigation to disable, then require a fresh entry click.
- [x] Show a live On/Off status in Focus Launcher only after the setting has been manually enabled once.
- [x] Implement one shared kiosk process decision and use it in the launcher sweep, foreground enforcement, focus reclaim, and NuclearMode ownership exception.
- [x] Apply D1-B lineage/install-root and FocusFlow-owned utility rules without weakening escape-tool name/path detection.
- [x] Explain the opt-in's scope and Steam-launched-app example in Settings before enabling.
- [x] **Acceptance:** T13, T14, T18, and T23–T26 pass on Linux/source review; Windows archetype D and escape-tool row 12 remain for the owner.

### P0 execution notes

Add dated entries here before and after each meaningful implementation change. Preserve failed checks and document successful reruns.

### 2026-10-16 — P0.2/P0.3/P0.4 combined coordinator refactor — In progress

- **Scope:** Remove sticky retry/spawn-budget decision state; add liveness-based waiting, per-attempt tokens, watchdog and per-app cancellation; track process lineage and improve window selection. Follow the task-specific allowlists in `Launcher/plan.md`. No P0.5 enforcement changes.
- **Completed dependency:** P0.1 passed its Linux acceptance tests; Windows verification remains pending.
- **Working tree at batch start:** `main` is one commit ahead of `origin/main`. P0.1 changes are present and uncommitted in `Launcher/tracker.md`, `.agents/memory/gradle-jvm-selection.md`, `LauncherLaunchCoordinator.kt`, `LauncherLaunchCoordinatorTest.kt`, and `ChangelogScreen.kt`; preserve them.
- **Files inspected:** `LauncherLaunchCoordinator.kt`, `LauncherLaunchCoordinatorTest.kt`, `JvmLauncherLaunchPlatform.kt`, `ProcessSnapshot.kt`, `ProcessCommandCacheTest.kt`, `ProcessNameResolver.kt`, `ProcessNameResolverTest.kt`, `WindowRaiser.kt`, and the `FocusLauncherApp` model.
- **Current source findings:** P0.2's sticky retry and 20-second spawn-budget flags remain. P0.3 has no independent watchdog, per-attempt token, or per-app cancellation. P0.4 currently snapshots only name/path-matched instances, replaces tracked PIDs during polling, has no parent/creation-time lineage, and sorts windows by PID/handle.
- **Plan acceptance note:** P0.4 lists T8 while its allowlist permits only `WindowRaiser.findWindows`; process-based foreground verification belongs to P1.1 and its `raise` path. Do not widen the P0.4 allowlist; record any T8 portion that cannot be satisfied here as deferred to P1.1.
- **Verification:** Pending.

### 2026-10-16 — P0.2/P0.3/P0.4 — Resumed implementation batch

- **Scope:** Continue only P0.2, P0.3, and P0.4 together. P0.5 remains out of scope for this authorization.
- **Working tree:** `main` is one commit ahead of `origin/main` and clean; no pre-existing edits need preservation.
- **Files read before editing:** `replit.md`, `Launcher/plan.md`, `Launcher/tracker.md`, `Launcher/rules.md`, `Launcher/preagent-prompt.md`, `.agents/memory/focus-launcher-task-brief.md`, `.agents/memory/gradle-test-completion.md`, `.agents/memory/gradle-jvm-selection.md`, `LauncherLaunchCoordinator.kt`, `LauncherLaunchCoordinatorTest.kt`, `JvmLauncherLaunchPlatform.kt`, `ProcessSnapshot.kt`, `ProcessCommandCacheTest.kt`, `ProcessNameResolver.kt`, `ProcessNameResolverTest.kt`, `WindowRaiser.kt`, and the `FocusLauncherApp` model.
- **Current source findings:** The coordinator still has wait-only retry and 20-second spawn-budget state, refreshes by replacing PIDs, and has no independent watchdog or per-app cancellation. `ProcessSnapshot` already captures parent PID/creation time and lazily caches full paths, but the JVM platform does not expose the full snapshot to the coordinator. `WindowRaiser.findWindows` still sorts by PID/handle and does not filter/rank candidate windows.
- **Verification:** Pending.
- **Implementation update:** Removed the coordinator's wait-only and 20-second spawn-budget decisions; added the first per-attempt/watchdog/cancellation and lineage/window-selection changes. Work remains partial and unchecked.
- **Verification attempt 1:** `export JAVA_HOME=/nix/store/c8hr2f0b0dm685yx1dkp6bw24bpx495n-graalvm19-ce-22.3.1; export PATH="$JAVA_HOME/bin:$PATH"; java -version; gradle :compileKotlin --no-daemon --console=plain` failed at `:compileKotlin` with `ProcessSnapshot.kt:54:17 Variable 'SNAPSHOT_TTL_MS' must be initialized`. Moved the constant above the cache initializer; rerun pending.
- **Verification attempt 2:** The same command completed with `BUILD SUCCESSFUL` in 2m 7s. Existing CsvExport and Compose icon deprecation warnings remain; acceptance tests are still pending.

### 2026-10-17 — P0.2/P0.3/P0.4 — Resumed verification and completion

- **Scope:** Finish and verify only P0.2, P0.3, and P0.4. P0.5 and all P1 work remain out of scope.
- **Working tree:** `main` at `a44531a`; source tree is clean apart from the user's two newly attached text files. Preserve both attachments.
- **Current code review:** The prior batch's implementation is present in the coordinator, platform, process snapshot, window enumeration, and tests. Re-check stale-worker boundaries and PID identity tracking before marking any acceptance complete.
- **Plan boundary:** P0.4's T8 foreground-by-process portion remains deferred to P1.1; do not change `WindowRaiser.raise` or otherwise widen the allowlist.
- **Implementation update:** The spawned PID now carries its captured creation time into lineage tracking; asynchronous snapshot/window/spawn/raise returns re-check the active attempt token before continuing. Spawn debounce uses monotonic time so wall-clock changes cannot extend it. Added regression coverage for PID reuse before the first snapshot, a stale worker returning from a blocked snapshot, cancellation during window wait, backward wall-clock movement, and T5's ten-minute delay. Changed `LauncherLaunchCoordinator.kt`, `JvmLauncherLaunchPlatform.kt`, `ProcessSnapshot.kt`, `LauncherLaunchCoordinatorTest.kt`, and `ProcessLineageTrackerTest.kt`. Verification pending.
- **Verification attempt 1:** Targeted Gradle tests (`LauncherLaunchCoordinatorTest`, `ProcessLineageTrackerTest`, `ProcessCommandCacheTest`) exceeded the 300-second shell timeout after `:compileKotlin` and `:processTestResources`; no test task result or XML report was produced. No Gradle/Kotlin worker remained afterward. Treat as incomplete, not a test failure; retry pending.
- **Verification attempt 2:** The background targeted test run failed in `compileTestKotlin` with `LauncherLaunchCoordinatorTest.kt:716:9 'monotonicNowMs' overrides nothing`. The interface method was added while this run was already compiling, so source and test compilation observed different revisions. No tests ran; rerun after edits settle.
- **Verification attempt 3:** `export JAVA_HOME=/nix/store/c8hr2f0b0dm685yx1dkp6bw24bpx495n-graalvm19-ce-22.3.1; export PATH="$JAVA_HOME/bin:$PATH"; gradle :test --no-daemon --console=plain --tests com.focusflow.services.LauncherLaunchCoordinatorTest --tests com.focusflow.services.ProcessLineageTrackerTest --tests com.focusflow.services.ProcessCommandCacheTest` completed with `BUILD SUCCESSFUL` in 1m 45s. Inspected XML: 32 tests across the three targeted suites, 0 failures, 0 errors, 0 skipped. Full `:test` remains pending.
- **Verification attempt 4:** `gradle :test --no-daemon --console=plain` returned without a visible Gradle build summary; generated XML still contained only the three filtered suites (32 tests), so this does not establish a full-suite result. Retry with forced test-task execution pending.
- **Final verification:** `export JAVA_HOME=/nix/store/c8hr2f0b0dm685yx1dkp6bw24bpx495n-graalvm19-ce-22.3.1; export PATH="$JAVA_HOME/bin:$PATH"; gradle :test --no-daemon --console=plain --rerun-tasks` completed with `BUILD SUCCESSFUL` in 2m 49s. Inspected fresh XML: 61 suites, 256 tests, 0 failures, 0 errors, 0 skipped; the launch coordinator suite had 24 passing tests, lineage 5, and process cache 3. `git diff --check` passed. Source search confirmed no wait-only retry/spawn-budget identifiers remain.
- **JVM observation:** With the configured `JAVA_HOME`, `java -version` reported GraalVM 19.0.2; `gradle --version` reported launcher/daemon JDK 21.0.7, and the running app process also used JDK 21.0.7. The test-worker JVM was not captured.
- **Workflow smoke check:** Restarted `Start application`; `getWorkflowStatus` reported `running` in `:run`, with no errors in the latest workflow logs. No Windows runtime behavior was exercised.
- **Safety review:** Re-read `FocusLauncherService.isLaunchAllowed`, PIN verification and exit, break and kill-switch paths, `SessionPin`, `ProcessMonitor` launcher sweep, and `NuclearMode` name/path escape-tool lists. No enforcement/PIN code was changed.
- **Final status:** P0.2 is complete and verified on Linux. P0.3 coordinator behavior is implemented and T2/T3/T4-core/T7/T12 pass, but its acceptance remains open for the visible elapsed-progress notice assigned to P1.6. P0.4 implementation checks and T3/T16/T17/T21 pass, but its acceptance stays open because T8 belongs to P1.1 per the allowlist and Windows archetypes B–D/F still require owner verification. P0.5 and all P1 work remain untouched.
- **Constants:** `WINDOW_WAIT_MS=60000`, `CHAIN_GONE_GRACE_MS=3000`, `ATTACH_WINDOW_MS=90000`, `SPAWN_DEBOUNCE_MS=2000`, `WATCHDOG_MARGIN_MS=5000`, `WINDOW_SELECTION_SETTLE_MS=300`, `WINDOW_POLL_MS=150`, `PROCESS_REFRESH_MS=500`.

### 2026-10-17 — P0.5 — User-approved opt-in and Global PIN gate

- **Scope:** Begin P0.5 using D1-B only when manually enabled. User requires a Settings toggle, clear description with a Steam launch-chain example, free disable, Global PIN verification to enable, a persistent live On/Off launcher status after first enable, and a PIN-or-Settings gate on every Enter Launcher attempt while enabled.
- **Security behavior:** If Global PIN is unset, require Global PIN setup followed by explicit PIN entry before enabling. Database/PIN read failures block enablement and launcher entry. Removing the Global PIN disables the feature. Escape-tool name and path checks remain higher priority than every chain exemption.
- **Working tree:** Source implementation from P0.2–P0.4 is present in local project history; no GitHub push is part of this task. Preserve the user's two supplied plan notes.
- **Plan update:** P0.5 file scope expanded to include the narrowly scoped Settings, Focus Launcher setup, app navigation, and new policy/service code needed for the user-approved flow. No P1.1/P1.3/P1.6 work is authorized here.
- **Implementation files:** Pending final code inspection; expected scope is enforcement launcher branches, launcher service, the two launcher/settings screens, app navigation, and focused allowance/policy tests only.
- **Verification:** Pending.

### 2026-10-17 — P0.5 — Implementation batch started

- **Authorization and scope:** The owner explicitly asked to start P0.5 and restated the manually enabled Settings toggle, explanatory Steam example, mandatory Global PIN, free disable, fresh PIN gate on every launcher entry, direct Settings link, and persistent live status after first enable. No P1/P2 work is included.
- **Plan clarification:** Make the setup-then-re-enter-PIN sequence and the “return to Focus Launcher and click Enter again” behavior explicit. Add the central Global PIN removal hook to the P0.5 file allowlist so every removal path forces the opt-in off.
- **Plan updates applied:** Expanded P0.5 to allow the narrowly scoped Global PIN removal hook; specified fail-closed setup/verification, fresh verification before the normal launcher flow, a direct Settings route with no auto-resume, and persistent “enabled once” status. Updated the pre-agent verification reference from T1–T22 to T1–T26.
- **Scope clarification:** The shared process policy also decides whether a launcher-launched foreground window should raise the kiosk overlay; otherwise the Steam example could pass process enforcement but still be covered by FocusFlow. No broader overlay redesign is included.
- **Implementation progress:** Added typed opt-in persistence, verified-PIN enable/entry checks, the Global PIN removal hook, shared launcher process policy, enforcement/reclaim/overlay integration, Settings toggle/setup UI, entry gate with direct Settings navigation, persistent live status, localized copy, and focused service/policy tests. Full verification is still pending.
- **Failed verification attempt:** `:compileKotlin` reached the Kotlin compile task but the shell command timed out at five minutes without a final result. This is not passing evidence; rerun after the remaining edits.
- **Compile diagnostics from the background retry:** Found a callback-signature mismatch, nullable executable paths, incorrect warning argument/cause names, and a set/map API mismatch. These source issues have been corrected; no successful compile is claimed yet.
- **Initial source review:** No shared kiosk policy/chain-allowance service exists. Current sweep and foreground checks use only process names and the safe-name set; the Settings route has no direct navigation callback from Focus Launcher. Global PIN removal can also occur through the shared reset method, not only the Settings dialog.
- **Files read before editing:** `replit.md`, `Launcher/plan.md`, `Launcher/tracker.md`, `Launcher/rules.md`, `Launcher/preagent-prompt.md`, `.agents/memory/focus-launcher-task-brief.md`, `FocusLauncherService.kt`, `GlobalPin.kt`, `PinGateDialog.kt`, `GlobalPinSetupDialog.kt`, `ProcessMonitor.kt`, `WinEventHook.kt`, `NuclearMode.kt`, `SettingsScreen.kt`, `FocusLauncherScreen.kt`, `App.kt`, `Database.kt`, `ProcessSnapshot.kt`, and `WinApiBindings.kt`.
- **Working tree at batch start:** Clean.
- **Verification:** Pending. All Windows runtime rows remain owner-only.

### 2026-10-17 — P0.5 — Resumed implementation and acceptance audit — Complete

- **Authorization and scope:** The owner explicitly resumed P0.5. Continue only the listed P0.5 work, preserving D1-B and all PIN, kiosk-exit, and escape-tool safety invariants.
- **Working tree at batch start:** `main` matches `origin/main`; the only untracked path is the newly attached implementation brief. Existing P0.5 implementation and tests are present in the checkout; their presence is not completion evidence.
- **Initial audit:** `LauncherChainAllowance`, `LauncherKioskPolicy`, Settings/entry UI, Global PIN removal hooks, ProcessMonitor, WinEventHook, and NuclearMode already contain P0.5 changes. Reconcile their behavior and test evidence before deciding which changes are still needed.
- **Files read this batch:** the P0.5 service implementations and tests; relevant `GlobalPin`, `FocusLauncherService`, `ProcessMonitor`, `WinEventHook`, `NuclearMode`, `SettingsScreen`, `FocusLauncherScreen`, `GlobalPinSetupDialog`, `PinPolicy`, `App.kt`, localization symbols, and P0.5 plan/rules/tracker.
- **Safety gaps found and corrected:** Chain membership trusted a parent PID without checking that the parent process instance started before its child, allowing PID reuse to falsely prove ancestry. ProcessMonitor also invoked the overlay policy twice per foreground event, including a redundant name-only callback without the foreground PID; removed that duplicate so event-driven overlay decisions use the PID-aware shared policy.
- **Files changed this batch:** `Launcher/tracker.md`, `src/main/kotlin/com/focusflow/services/LauncherKioskPolicy.kt`, `src/test/kotlin/com/focusflow/services/LauncherKioskPolicyTest.kt`, `src/main/kotlin/com/focusflow/enforcement/ProcessMonitor.kt`, and `src/main/kotlin/com/focusflow/services/FocusLauncherService.kt`. Added the parent/child creation-time consistency check and removed the duplicate legacy callback.
- **Verification:** `export JAVA_HOME=/nix/store/c8hr2f0b0dm685yx1dkp6bw24bpx495n-graalvm19-ce-22.3.1; export PATH="$JAVA_HOME/bin:$PATH"; gradle :test --no-daemon --console=plain --rerun-tasks` completed with `BUILD SUCCESSFUL` in 3m 43s. Fresh XML: 63 suites, 268 tests, 0 failures, 0 errors, 0 skipped; `LauncherKioskPolicyTest` 6/6, `LauncherChainAllowanceTest` 6/6, and `NuclearModeSettingsTest` 3/3. New regression `reusedParentPidCannotMakeAnOlderProcessPartOfTheAllowedChain` passed. `git diff --check` passed.
- **Flow review:** Verified the Settings setup-then-re-enter-PIN sequence, fresh entry gate and direct Settings route/no auto-entry, persistent `everEnabled` status, Global PIN removal hooks, and fail-closed unavailable-state handling. The existing Compose UI has no interaction-test dependency, so these UI routes were source-reviewed rather than driven by an automated UI test.
- **Workflow smoke check:** Restarted `Start application`; workflow reports `running` in `:run`, with no errors in the available logs. Replit/Linux does not verify Win32 behavior.
- **Safety paths re-read after the changes:** `FocusLauncherService.isLaunchAllowed`, PIN-gated `enter`, `exit`, `startBreak`, `endBreak`, kill-switch activation/deactivation, interrupted-session restoration, `SessionPin`, `KillSwitchService`, keyboard suppression/foreground-lock handling, `NuclearMode.isEscapeTool` name/path checks and targeted kills, and the ProcessMonitor foreground/sweep decisions. No session exit, break, kill-switch, or escape-tool rule was weakened.
- **Constants and owner decisions:** No launch timing constants changed. D1-B remains the default when manually opted in; app-chain exceptions remain off by default.
- **Outstanding:** All §9 Windows rows remain unchecked. P0.5 owner checks due are row 4 (Steam/Epic/Battle.net/Minecraft launch chains) and row 12 (escape tools and PIN exit). The 2.0.6 changelog was not edited because `ChangelogScreen.kt` is outside P0.5's file allowlist, despite the plan-wide changelog instruction; this scope conflict is recorded for the owner.
- **Out-of-scope observation:** `LauncherLaunchCoordinator.onRaised` still bases its immediate post-raise overlay update on the selected tile's process name. This is the separately scoped P2.2 item; no change was made here.

## P1 — Correctness and robustness

**Status:** P1.1–P1.4 implementation and Linux automated acceptance are complete. P1.5/P1.6 implementation and Linux automated checks are complete; their Windows owner acceptance is still open.

### P1.1 — Raise reliability

- [x] Verify foreground ownership by tracked process chain/root owner, reselect windows on every attempt, and bound native raise calls.
- [x] Preserve the foreground-lock kiosk guarantee and align raise behavior with overlay handling.
- [x] **Automated acceptance:** T8 and T9 pass on Linux.
- [ ] **Owner verification:** Windows archetypes A–E.

### P1.2 — Pre-flight checks and typed failures

- [x] Add bounded no-spawn checks for allow status, blocking rules, executable/path support, and unsupported candidates.
- [x] Carry typed failures to the UI; keep raw OS errors in logs and friendly text on tiles.
- [x] **Automated acceptance:** T10 and T20 pass; the fake-platform test confirms a blocked app is not spawned and receives a specific reason promptly.
- [ ] **Owner verification:** Windows archetype H, row 8.

### P1.3 — Spawn method

#### 2026-10-17 — P1.3/P1.4 — Resumed implementation and acceptance audit — In progress

- **Authorization and scope:** The owner asked to continue P1.3 and P1.4. Continue only the spawn-method and app-picker/session-resolution work, including the previously approved `FocusLauncherService.kt` and `ChangelogScreen.kt` support edits. Do not start P1.5, P1.6, or P2 work.
- **Working tree:** `main` is one commit ahead of `origin/main`; `git status --short --branch` showed no uncommitted changes.
- **Current review:** The resolver, unelevated launch path, candidate classification, picker issue badges/tooltips, persisted-session re-resolution, and planned changelog edits are already present in the checkout. Reviewed these against P1.3/P1.4 and verified them with the existing focused tests and full Gradle suite; no source change was needed in this continuation.
- **Implementation audit update:** Confirmed session entry re-resolves selected apps, refuses to start if none remain, and warns when only some remain; interrupted-session recovery repairs the saved app list and warns when apps are omitted. No duplicate notice or UI change is needed.
- **Files read this batch:** `Launcher/tracker.md`, `JvmLauncherLaunchPlatform.kt`, `InstalledAppsScanner.kt`, `FocusLauncherService.kt`, `FocusLauncherScreen.kt`, the Focus Launcher session model, `LauncherExecutableResolutionTest.kt`, `InstalledAppsScannerTest.kt`, and the spawn-failure mapping in `LauncherLaunchCoordinator.kt`.
- **Focused verification:** `export JAVA_HOME=/nix/store/c8hr2f0b0dm685yx1dkp6bw24bpx495n-graalvm19-ce-22.3.1; export PATH="$JAVA_HOME/bin:$PATH"; java -version; gradle --version; gradle :test --no-daemon --console=plain --tests 'com.focusflow.services.LauncherExecutableResolutionTest' --tests 'com.focusflow.enforcement.InstalledAppsScannerTest'` completed with `BUILD SUCCESSFUL` in 4m 20s. Fresh XML: 2 suites, 15 tests, 0 failures, 0 errors, 0 skipped. `java -version` reported GraalVM 19.0.2; Gradle launcher and daemon reported JDK 21.0.7; test-worker JVM not captured.
- **Build warnings:** Existing opt-in/deprecation warnings remain; `InstalledAppsScanner.kt:268` also reports a redundant Elvis expression. No warning failed compilation.
- **Full verification:** `export JAVA_HOME=/nix/store/c8hr2f0b0dm685yx1dkp6bw24bpx495n-graalvm19-ce-22.3.1; export PATH="$JAVA_HOME/bin:$PATH"; gradle :test --no-daemon --console=plain --rerun-tasks` completed with `BUILD SUCCESSFUL` in 3m 8s. Inspected fresh XML: 65 suites, 292 tests, 0 failures, 0 errors, 0 skipped. `git diff --check` passed.
- **Files changed this continuation:** `Launcher/tracker.md` only; P1.3/P1.4 source and focused tests were already present in the clean checkout. No workflow restart was needed because no application source or run configuration changed.
- **Final status:** P1.3/P1.4 implementation and Linux automated evidence are complete. Do not mark Windows acceptance complete: row 5 (elevation), row 6 (Store/UWP), row 8 (blocked app), row 13(b) (missing executable after session setup), and persisted-session recovery still need owner evidence. All §9 rows remain unchecked; no GitHub push or Actions run was performed.

#### 2026-10-17 — Resumed P1.3 and P1.4 — In progress

- **Authorization and scope:** The owner clarified that P1.3 and P1.4 were the active work and supplied the P1.3 progress note. Continue only P1.3 spawn-method work and P1.4 app-picker hygiene, including its already approved `FocusLauncherService.kt` and `ChangelogScreen.kt` support edits.
- **Working tree:** `main` is one commit ahead of `origin/main`; only the newly supplied progress note is untracked. No pre-existing source edits are present.
- **Files read before edits:** The P1.3 progress note; project instructions and Focus Launcher plan/tracker/rules/pre-agent prompt/memory; `JvmLauncherLaunchPlatform.kt`, `ProcessSnapshot.kt`, `ElevationManager.kt`, `WindowsStartupManager.kt`, `InstalledAppsScanner.kt`, relevant `FocusLauncherScreen.kt` and `FocusLauncherService.kt` sections, `LauncherLaunchCoordinator.kt`, the executable-resolution tests, and the planned changelog entry.
- **Current review:** P1.3's resolver, shell-token launch, working-directory selection, stdin closure, and friendly coordinator error classification are already present. The running-process fallback can accept an unrelated process when its process name is unreadable. Focused tests and the full test task remain pending; Windows elevation row 5 is owner-only.
- **P1.4 current review:** The picker still trusts registry `DisplayIcon`, presents arbitrary running executables, and does not expose candidate warnings or resolved paths. Persisted selections default back to all apps when no saved name matches. Session entry and interrupted-session restore still trust saved executable paths without re-resolution.
- **Verification:** Pending.

#### 2026-10-17 — In progress

- **Authorized scope:** P1.3 only: executable resolution, no `cmd /c start` fallback, child-stdin closure, preserved executable-directory working directory, de-elevated launch when FocusFlow is elevated, and friendly spawn failures.
- **Working tree:** `main` is one commit ahead of `origin/main`; clean at batch start.
- **Files read before edits:** `replit.md`, `Launcher/plan.md`, `Launcher/tracker.md`, `Launcher/preagent-prompt.md`, `Launcher/rules.md`, `.agents/memory/focus-launcher-task-brief.md`, `JvmLauncherLaunchPlatform.kt`, `ProcessSnapshot.kt`, `ElevationManager.kt`, `WindowsStartupManager.kt`, and the relevant validation/spawn/failure-classification sections of `LauncherLaunchCoordinator.kt`.
- **Current source finding:** The platform still launches a pathless app with `cmd /c start`; `ProcessBuilder` stdin is left open. Coordinator-side error classification already provides friendly 2/3/5/193/740 messages.
- **Implementation progress:** Added ordered executable resolution (tile path, App Paths, PATH, then a matching running process), preflight use of the resolver, removal of the command-shell fallback, child-stdin closure, executable-directory working directory, and a shell-token launch path for elevated FocusFlow. The token path fails closed if the interactive shell token is missing or elevated. Existing coordinator mapping supplies user-facing errors for Windows codes 2/3/5/193/740. Focused tests and compile are pending.
- **Verification attempt 1:** `export JAVA_HOME=/nix/store/c8hr2f0b0dm685yx1dkp6bw24bpx495n-graalvm19-ce-22.3.1; export PATH="$JAVA_HOME/bin:$PATH"; java -version; gradle :compileKotlin --no-daemon --console=plain` timed out at the 300-second shell limit after `:compileKotlin` began; no final Gradle result was captured. Treat as incomplete, not a pass. Retry after adding focused tests and resolving any source issues.
- **Test progress:** Added focused tests for executable-source precedence, stale-path fallback, updater/uninstaller rejection, path parsing, working directory, and direct versus user-token spawn selection. These tests exercise path and decision helpers only; they do not execute Windows APIs.
- **Verification attempt 2:** `gradle :compileKotlin --no-daemon --console=plain` completed with `BUILD SUCCESSFUL` in 2m39s. Existing warnings were in unrelated files. Focused tests and the final full test run remain pending.
- **Verification:** Pending. Windows elevation matrix row 5 remains owner-only.

- [x] Resolve the executable before spawn; remove `cmd /c start`; close child stdin and preserve the working-directory rule.
- [x] Apply the planned elevation behavior and friendly spawn-error mapping.
- [ ] **Acceptance:** Windows elevation matrix row 5 remains owner-verified.

- **Resumed implementation update — 2026-10-17:** Tightened the unreadable-process fallback so it accepts only a resolved path whose image name matches the requested executable. Added a pure session-app resolution helper for use by P1.4 entry/restore validation. The de-elevated token launch, stdin closure, working directory, and coordinator's friendly 2/3/5/193/740 error mapping remain in place. Added focused regression tests; verification pending.
- **Verification attempt 1:** `export JAVA_HOME=/nix/store/c8hr2f0b0dm685yx1dkp6bw24bpx495n-graalvm19-ce-22.3.1; export PATH="$JAVA_HOME/bin:$PATH"; gradle :test --no-daemon --console=plain --tests 'com.focusflow.services.LauncherExecutableResolutionTest' --tests 'com.focusflow.enforcement.InstalledAppsScannerTest'` timed out after 300 seconds during test compilation, before `:test`; no fresh XML report was created. This is incomplete, not a test result. Retry in background.
- **Verification attempt 2:** The background targeted test command failed in `:compileKotlin` because the no-PIN branch still returned `false` after `enter()` changed to `FocusLauncherEntryResult`. Replaced that with the typed blocked result and tightened session validation to reject hidden system utilities from saved sessions. No tests ran; rerun pending.

### P1.4 — App-picker hygiene

#### 2026-10-17 — Resumed P1.4 — In progress

- **Authorized scope:** Continue P1.4 plus the previously approved `FocusLauncherService.kt` restore/entry validation and `ChangelogScreen.kt` planned-release notes. Do not widen into P1.5/P1.6 or general picker redesign.
- **Current review:** Candidate classification, safe handling of stale selected names, re-resolution on entry/restore, and path visibility in the picker remain to be implemented and verified.
- **Verification:** Pending; Windows archetypes F/H and restored-session behavior remain owner-only.

#### 2026-10-17 — In progress

- **Authorized scope:** P1.4 plus the user-approved supporting edits to `FocusLauncherService.kt` for persisted-session restore and `ChangelogScreen.kt` for the planned 2.0.6 entry.
- **Files read before edits:** `InstalledAppsScanner.kt`, the relevant loading, picker, entry, and row sections of `FocusLauncherScreen.kt`, `FocusLauncherService.kt` session model/restore sections, `ChangelogScreen.kt` planned release entry, and P1.4 / Windows verification sections of `Launcher/plan.md`.
- **Current source finding:** Registry candidates trust `DisplayIcon`, running candidates include arbitrary executables, picker selection persists process names, and session restore reuses the saved executable path without checking that it still resolves.
- **Verification:** Pending. Windows archetypes F/H and stale persisted-path behavior require owner verification; Linux tests will cover classification and resolver behavior.

- [x] Classify/badge or hide unsupported, stale, system, uninstaller, updater, and installer candidates.
- [x] Re-resolve selected paths on session entry and restore; expose the resolved path in a tooltip.
- [ ] **Acceptance:** Applicable archetypes F and H and stale-path behavior are verified.

- **Resumed implementation update — 2026-10-17:** Added candidate issue classification, hidden system-utility filtering, recovery of a clearly named main executable when the registry icon points to an updater, and tests for those rules. The picker now badges and disables unsupported entries, exposes the executable path in a tooltip, and restores only saved supported selections instead of selecting every app when saved names are stale. Session entry and interrupted-session recovery re-resolve paths; unresolved apps are omitted with a warning, and recovery ends safely if none remain. Changelog entries are drafted. Verification pending; §9 Windows checks remain owner-only.

### P1.5 — Shared process snapshot and scan cost

- [x] Depends on P0.4. Reuse the shared snapshot for the plan-listed sweeps/resolvers while preserving detection latency and path-based escape detection.
- [ ] **Acceptance:** Source/tests confirm no per-tick `tasklist.exe`, and a Linux timing sample is recorded. Typical Windows performance and the kiosk-on/off comparison remain open for owner verification in row 15.

### P1.6 — Honest progress, cancel, and labels

- [x] Depends on P0.3. Show elapsed progress and Cancel after the planned delay; cancel the attempt without killing an already-started app.
- [x] Show typed, specific failure labels and preserve the planned failure-display behavior.
- [ ] **Acceptance:** Windows rows 13 and 16 remain owner-verified.

### 2026-10-17 — P1.5/P1.6 — Initial implementation and focused verification

- **Authorization and dependencies:** The owner selected P1.5 and P1.6. P0.4's shared snapshot and P0.3's typed coordinator states are present; their separate Windows acceptance gates remain open. Work only on the shared process-snapshot consumers and Focus Launcher progress/cancel/labels. Do not start P2.
- **Working tree:** `main` is two commits ahead of `origin/main`; the working tree was clean before this batch.
- **Initial source review:** The 250 ms `ProcessSnapshot` cache exists, but `ProcessMonitor.backgroundSweep` and both UWP child resolvers enumerate `ProcessHandle` directly; Nuclear Mode starts `tasklist` and separately enumerates for paths; `WinEventHook` queries the PID command and then resolves it again. `LaunchState.Launching` already carries a start time, `LauncherLaunchCoordinator.cancel(app)` cancels only the attempt, typed failures and a six-second reset already exist, and the UI has no delayed elapsed progress or tile cancel control.
- **Files reviewed:** `ProcessSnapshot.kt`, `ProcessMonitor.kt`, `NuclearMode.kt`, `WinEventHook.kt`, `LauncherLaunchCoordinator.kt`, `LauncherContent.kt`, `ChangelogScreen.kt`, and the P1.5/P1.6 plan and tracker sections.
- **P1.5 implementation update:** Routed background and UWP process resolution through the shared snapshot; a UWP child miss forces one refresh so a fresh cache cannot delay detection. Nuclear Mode now uses one snapshot for name and path escape checks instead of `tasklist` plus a second enumeration; WinEventHook uses the snapshot for ordinary foreground names with a targeted fallback for new PIDs/UWP hosts. Snapshot refreshes and slow sweep work now log duration only above 50 ms. Focused tests passed in the initial focused run below; the final full-suite result is in the resumed audit.
- **P1.6 implementation update:** Removed the inaccurate “this can take a few seconds” copy. Tiles now show elapsed time and Cancel at 10 seconds; cancellation calls the existing per-app coordinator cancel path, which clears attempt state without killing the app. Typed short failure labels remain, retryable failures show “Tap to retry,” details remain in the tooltip, and the coordinator's six-second failure display is unchanged. Focused tests passed in the initial focused run below; the final full-suite result is in the resumed audit.
- **Focused verification:** The P1.5/P1.6 tests plus coordinator, process-command-cache, kiosk-policy, and chain-allowance coverage completed with `BUILD SUCCESSFUL` in 50s. Fresh XML: 7 suites, 54 tests, 0 failures, 0 errors, 0 skipped. The runtime snapshot probe saw 20 processes in 9 ms on this Linux host; this is not a Windows performance result.
- **Earlier focused verification:** P1.5/P1.6 tests passed (7 suites, 54 tests); the final full-suite results are recorded in the resumed audit below. Preserve snapshot freshness, PID/creation-time identity, path-based escape detection, session PIN/exit/break/kill-switch behavior, and leave Windows rows 13, 15, and 16 for owner evidence. No GitHub push or Actions run was performed.

### 2026-10-17 — P1.5/P1.6 — Resumed audit — Implementation complete; Linux checks passed; Windows owner checks pending

- **Authorization:** The owner resumed only P1.5 shared process-snapshot consumers and P1.6 Focus Launcher progress/cancel/labels. P2, GitHub pushes, and Actions remain out of scope.
- **Working tree:** Tracked source was clean at batch start; only the two supplied progress/instruction attachments were untracked. Preserve both.
- **Current source review:** The shared snapshot is used by background and launcher sweeps, UWP child lookup, Nuclear Mode name/path checks, and ordinary WinEvent foreground resolution. Launcher tiles show elapsed time and wire Cancel to the per-app coordinator cancellation path; typed labels and retry hints are present.
- **Review finding:** `ProcessCommandCache` keys use exact `Instant` equality, while the lazy full-path cache is keyed from millisecond `creationTimeMs`; verify and cover retention across that precision boundary so path lookups remain cached across snapshots.
- **Changes in this continuation:** Normalized process-cache identity/retention to the snapshot's millisecond creation-time precision and added a fractional-Instant regression test. Added tests for the 10-second progress/Cancel thresholds and that cancellation leaves the spawned app alive. A new tap now clears a displayed non-retryable failure before re-running preflight; added UI-state and coordinator coverage.
- **Additional P1.6 gap found and fixed:** The tile had disabled clicks for `Failed(canRetry=false)`, and the coordinator rejects such a state. The tile is now tappable while failed and clears the old terminal state before launching again, as §5/P1.6 requires.
- **Verification attempt 1:** `export JAVA_HOME=/nix/store/c8hr2f0b0dm685yx1dkp6bw24bpx495n-graalvm19-ce-22.3.1; export PATH="$JAVA_HOME/bin:$PATH"; java -version; gradle :test --no-daemon --console=plain --rerun-tasks` hit the 300-second shell timeout after Gradle started. No final task result or `build/test-results/test` XML was produced. Incomplete, not a pass or test failure.
- **Verification attempt 2:** Started the same forced full-suite command in the background, then stopped it before `:compileKotlin` completed after the additional P1.6 gap was found. No `:test` result or XML reports were produced. Incomplete; superseded by attempt 3.
- **Verification attempt 3 (final source state):** `export JAVA_HOME=/nix/store/c8hr2f0b0dm685yx1dkp6bw24bpx495n-graalvm19-ce-22.3.1; export PATH="$JAVA_HOME/bin:$PATH"; java -version; gradle :test --no-daemon --console=plain --rerun-tasks` completed with `BUILD SUCCESSFUL` in 5m 24s. Fresh XML: 68 report files, 301 tests, 0 failures, 0 errors, 0 skipped. Relevant passing tests include `slowLiveProcessOpensOnTheFirstAttempt`, `cancelDuringAwaitLeavesNoStateOrLateRaise`, `clearingANonRetryableFailureAllowsAFreshTapImmediately`, `retainsCacheWhenSnapshotCreationTimeHasMillisecondPrecision`, `keepsPathBasedEscapeDetectionWhenTheProcessNameDiffers`, and `elapsedProgressAppearsAtTenSecondsAndShowsTheCurrentDuration`. The runtime snapshot sample recorded 11 processes in 9 ms on this Linux host; this is not evidence for a typical Windows machine or the kiosk-on/off comparison.
- **Runtime and diff checks:** The shell `java -version` was GraalVM 19.0.2; Gradle was 8.14.2 with launcher JVM 21.0.7. `git diff --check` passed. No test-worker JVM claim is made.
- **Workflow smoke check:** Restarted `Start application`; Gradle `:run` is active and `com.focusflow.MainKt` is running. No workflow errors were observed. This confirms Linux process startup, not Windows runtime or UI behavior.
- **Safety review after the edits:** Re-read the Global and Session PIN gates, `FocusLauncherService` enter/exit/break/kill-switch and interrupted-session recovery paths, `KillSwitchService`, Nuclear Mode name/path detection and enforcement, ProcessMonitor sweeps, the shared snapshot, and the launcher cancel path. No PIN, exit, break, kill-switch, or escape-tool behavior was changed; cancellation leaves the spawned app alive in the coordinator test.
- **Scope and owner checks:** The P1.5/P1.6 changes are limited to `ProcessSnapshot.kt`, `LauncherContent.kt`, their tests, and this tracker. Keep Windows rows 13, 15, and 16 open; no Windows runtime or performance comparison was run. The changelog was not edited because it is outside the P1.5/P1.6 file allowlists, despite the plan-wide changelog guidance. No GitHub push or Actions run was performed.
- **Out-of-scope observations retained:** The launcher safe-process list exempts all Java programs; Nuclear Mode targets shells regardless of ownership, including possible firewall-helper shells; `AppIconExtractor` uses reflective access to `sun.awt.shell.ShellFolder`; `InstalledAppsScanner.getRunningApps()` offers all running executables as candidates. None was changed under this scope.

### P1 execution notes

Add dated entries for each selected task. Record dependencies, changed files, checks, failures, and open Windows checks.

### 2026-10-17 — P1.1/P1.2 — Implementation started

- **Authorization and dependencies:** The owner selected P1.1 Raise reliability and P1.2 Pre-flight checks and typed failures. P1.1 builds on the P0.4 launch/window pipeline; P1.2 uses the current Focus Launcher allow predicate and ProcessMonitor's active in-memory enforcement sets. P1.3/P1.4 remain out of scope except the P1.2 pre-flight rejection explicitly required for unsupported launch candidates.
- **Initial evidence:** `WindowRaiser` currently checks only exact-HWND foreground equality, uses synchronous `ShowWindow`/`SetWindowPos`, and runs four 300 ms tiers inside each of three coordinator attempts. Retries enumerate only the last window PID. The GlobalKeyboardHook already records lock/unlock results. The coordinator silently rejects a now-disallowed app in `request()`, the Store-app check is the only current unsupported-candidate pre-flight, active block sets have no reason accessor, and tile labels use free-text substring matching while spawn text includes raw OS details.
- **Files read before editing:** P1.1/P1.2 plan and tracker sections; `LauncherLaunchCoordinator.kt`, `WindowRaiser.kt`, `GlobalKeyboardHook.kt`, `ProcessMonitor.kt`, `JvmLauncherLaunchPlatform.kt`, `LauncherContent.kt`, and `LauncherLaunchCoordinatorTest.kt`; relevant P0.5 memory and Gradle verification notes.
- **Scope/verification boundary:** Follow the P1.1/P1.2 allowlists; use Linux fake-platform tests for T8/T9/T10/T20, but leave all Windows archetype verification to the owner. No implementation edits have been made yet.

### 2026-10-17 — P1.1/P1.2 — Implementation and Linux verification

- **Implemented behavior:** The coordinator refreshes the tracked process chain and reselects a window on every raise attempt, accepts foreground ownership only from a live tracked PID or the selected window's root owner, and limits the raise phase to 2,500 ms. `WindowRaiser` uses asynchronous show/position APIs, grants foreground permission only to the selected target, and does not unlock the kiosk foreground lock. Pre-flight rejects disallowed apps, active FocusFlow block rules, unsupported Store/candidate types, and missing/unsupported executable paths before spawn; pre-flight is capped at 500 ms. Failure codes reach the launcher tile as friendly labels while raw spawn errors remain in logs.
- **Working-tree changes in this continuation:** `LauncherLaunchCoordinator.kt` and `LauncherLaunchCoordinatorTest.kt`; the integrated window-raising, cache-only block-reason, and typed tile-label paths were source-reviewed in the current checkout. `Launcher/tracker.md` records this outcome. No P1.3/P1.4 picker or spawn-method redesign was included.
- **Verification attempt 1:** `export JAVA_HOME=/nix/store/c8hr2f0b0dm685yx1dkp6bw24bpx495n-graalvm19-ce-22.3.1; export PATH="$JAVA_HOME/bin:$PATH"; gradle :test --no-daemon --console=plain --rerun-tasks` compiled and ran 277 tests, with 4 failures in the coordinator suite. The cases exposed an eligibility failure being cleared to Idle, expected poll/tier-log values that did not account for raise-time reselection and ownership results, and a watchdog fixture whose process had been marked dead before the raise phase.
- **Correction and verification attempt 2:** The active-attempt guard now emits a typed `NotAllowed` failure instead of silently dropping it. Updated the expected reselect count and ownership-tier log assertion, and kept the process live in the hung-raise fixture. A forced focused rerun then had one remaining watchdog test assertion (`spawnCount` expected 2, actual 1): the retry correctly reused the still-live process. Updated the assertion to verify reuse. `gradle :test --no-daemon --console=plain --tests 'com.focusflow.services.LauncherLaunchCoordinatorTest'` completed with `BUILD SUCCESSFUL` in 42s; XML: 33 tests, 0 failures, 0 errors, 0 skipped.
- **Final verification:** `export JAVA_HOME=/nix/store/c8hr2f0b0dm685yx1dkp6bw24bpx495n-graalvm19-ce-22.3.1; export PATH="$JAVA_HOME/bin:$PATH"; gradle :test --no-daemon --console=plain --rerun-tasks` completed with `BUILD SUCCESSFUL` in 3m 52s. Fresh XML: 63 suites, 277 tests, 0 failures, 0 errors, 0 skipped; `LauncherLaunchCoordinatorTest` had 33 passing tests. `git diff --check` passed. Existing Kotlin opt-in and deprecation warnings remain.
- **Workflow smoke check:** Restarted `Start application`; `getWorkflowStatus` reported `running` in `:run`, and the latest workflow logs showed no errors. No Windows runtime or browser-based UI check was run.
- **UI and safety review:** The typed failure label mapping was source-reviewed; the Compose UI has no interaction-test dependency, so tile interaction was not automated. The Global PIN, session exit/break, kill-switch, and escape-tool safety paths are unchanged from the P0.5 safety audit; the current diff does not touch them or `GlobalKeyboardHook`.
- **Windows and scope limits:** No Windows runtime/archetype check was run. Leave §9 rows 1–5 and row 8 unchecked; P0.5 owner rows 4 and 12 also remain pending. `ChangelogScreen.kt` was not edited because it is outside the P1.1/P1.2 allowlists despite the plan-wide changelog guidance. P2.2 post-raise overlay behavior remains out of scope.

## P2 — Hardening and polish

**Status:** P2.1 complete with Linux automated evidence; P2.2/P2.3 not started.

### P2.1 — Setup-screen loader recovery

#### 2026-10-17 — P2.1 — In progress

- **Authorized scope:** P2.1 only. Audit the setup screen's independent loaders, failure recovery, retry state, and T19 evidence; do not start P2.2 or P2.3.
- **Working tree:** `main` matches `origin/main`; the supplied Focus Launcher brief is the only untracked file.
- **Current source review:** `FocusLauncherScreen.kt` already has a shared loader recovery boundary, separate effects for app scanning, saved selection, chain settings, presets, and notice preferences, plus an inline app-scan error and Retry control. `FocusLauncherSetupLoaderTest.kt` already exercises loader failures, scan retry, and a slow preset loader alongside the app scan.
- **Files reviewed:** `Launcher/plan.md`, `Launcher/tracker.md`, `replit.md`, `FocusLauncherScreen.kt` loader and UI sections, and `FocusLauncherSetupLoaderTest.kt`.
- **Verification:** See the completion and verification record below.

- [x] Ensure independent loaders cannot leave the screen loading forever; show a useful error/retry state and log failures.
- [x] **Acceptance:** T19 passes.

#### 2026-10-17 — P2.1 — Completion and Linux verification

- **Audit result:** The P2.1 recovery implementation and its focused tests were already present in the checkout. The app scan has its own effect and always clears `isLoading` through the loader's `finally` completion; scan failures show an inline error with Retry. Saved selection, chain settings, presets, and notices load independently, with safe fallback behavior and logged loader failures. No screen or test source change was needed.
- **Files changed:** `Launcher/tracker.md` only. **Files reviewed:** `FocusLauncherScreen.kt`, `FocusLauncherSetupLoaderTest.kt`, `Launcher/plan.md`, `Launcher/tracker.md`, and `replit.md`.
- **Verification:** `export JAVA_HOME=/nix/store/c8hr2f0b0dm685yx1dkp6bw24bpx495n-graalvm19-ce-22.3.1; export PATH="$JAVA_HOME/bin:$PATH"; java -version; gradle --version; gradle :test --no-daemon --console=plain --rerun-tasks` completed with `BUILD SUCCESSFUL in 2m 29s`. Fresh XML: 69 suites, 305 tests, 0 failures, 0 errors, 0 skipped. `FocusLauncherSetupLoaderTest` passed all 4 tests: independent loader failure completion/logging, app-scan retry, preset/app-scan independence, and cancellation cleanup. `git diff --check` passed.
- **Runtime evidence:** Shell Java reported GraalVM 19.0.2; Gradle 8.14.2 launcher/daemon used JDK 21.0.7; the Gradle test worker was observed running the configured GraalVM 19.0.2 binary. Existing Kotlin/Gradle warnings remain and did not fail the build.
- **UI evidence limits:** Loader recovery and retry are covered by unit tests and the Compose state/render branches were source-reviewed; no Compose UI interaction test was run. No Windows-specific behavior was changed or claimed. No application workflow restart was needed because application source and run configuration were unchanged.
- **Deferred:** P2.2 and P2.3 remain untouched. The tracker’s pre-existing P1 summary has status inconsistencies outside this task’s scope; no P1 status was reconciled here.

### P2.2 — Overlay and activation

#### 2026-10-17 — P2.2 — In progress

- **Authorized scope:** P2.2 only. P2.2 and P2.3 are independent; do not make P2.3 cadence/cache changes.
- **Working tree:** `main` is one commit ahead of `origin/main`; clean at batch start.
- **Current source review:** `FocusLauncherService.onForegroundChanged` already calls the shared kiosk policy, but Explorer is in the safe-process set, so a safe shell foreground event can leave the overlay hidden after an allowed app closes. The launch coordinator's post-raise callback supplies only the tile name (PID 0), not the actual foreground process. `LauncherWindow` currently reasserts topmost with `SWP_SHOWWINDOW` and has no activation-state input.
- **Files read:** P2.2 in `Launcher/plan.md`; `Launcher/tracker.md`; `replit.md`; `FocusLauncherService.kt`; `LauncherWindow.kt`; `LauncherKioskPolicy.kt`; relevant `ProcessMonitor.kt`, `ProcessSnapshot.kt`, `WinEventHook.kt`, `WinApiBindings.kt`, `WindowRaiser.kt`, and the launch-coordinator callback.
- **Implementation plan:** Use the actual foreground identity for post-raise overlay decisions; keep transient safe windows from covering a still-visible allowed app, but return the launcher when no allowed app window remains. Make topmost activation explicit so non-focus transitions use `SWP_NOACTIVATE`. Preserve the shared kiosk policy and all existing PIN, break, exit, kill-switch, and escape-tool behavior.
- **Implementation update:** The service now resolves PID-less post-raise callbacks from the actual foreground window, uses the shared kiosk policy for foreground and candidate processes, and distinguishes visible allowed windows, live windowless apps, and closed apps. The window host applies `SWP_NOACTIVATE` when topmost without focus is appropriate. P2.3 remains untouched.
- **Verification:** Pending. Windows runtime behavior remains owner-only.

- [ ] Use the shared kiosk decision; avoid activating the launcher when merely restoring topmost state; base post-raise visibility on actual foreground state.
- [ ] **Acceptance:** Allowed apps are not covered by transient system windows; closing the app returns to the launcher.

### P2.3 — Comment and cache debt

- [ ] Correct/document the sweep cadence and handle `ProcessCommandCache` only within the plan's permitted scope.
- [ ] **Acceptance:** Source matches the documented actual cadence; no unrelated log or cache refactor.

### P2 execution notes

Add dated entries for each selected task and preserve the plan's out-of-scope boundary.

## Automated test scenarios — plan §8

Mark each scenario complete only after its named behavior is tested with the fake platform/virtual time as required by the plan.

- [x] **T1** Spawn, window appears, raise succeeds; no later-attempt influence remains.
- [x] **T2** Dead-on-arrival process exits quickly; next tap spawns.
- [x] **T3** Stub exits and a same-root/descendant hand-off process appears.
- [x] **T4** Slow live process opens on the first attempt with soft progress and no early failure. Coordinator liveness and the P1.6 elapsed-progress threshold are covered by automated tests.
- [x] **T5** After a hard-cap failure and delay, next tap spawns rather than waits.
- [x] **T6** Close shortly after opening; next tap spawns immediately.
- [x] **T7** Hung native call is bounded by watchdog; stale worker cannot alter newer attempt.
- [x] **T8** Foreground verification accepts another HWND of the chain and rejects another process.
- [x] **T9** Failed raise tiers end within budget and allow a fresh retry.
- [x] **T10** FocusFlow-blocked app fails pre-flight without a spawn call.
- [ ] **T11** Old windowless/tray instance results in one spawn and successful open.
- [x] **T12** `cancelAll()` and per-app cancel work in every phase without lingering jobs/state.
- [x] **T13** Chain members survive; unrelated/renamed escape tools remain blocked.
- [x] **T14** FocusFlow-owned system utility is exempt only under the specified ownership proof.
- [x] **T15** Two same-frame taps produce one spawn.
- [x] **T16** Reused PID with a different creation time is not in the chain.
- [x] **T17** Unreadable process remains tracked by PID and can own a found window.
- [x] **T18** Unresolvable lineage is not killed on first sighting; next snapshot decides.
- [x] **T19** A setup loader exception does not strand the screen in loading.
- [x] **T20** Spawn errors 2, 3, 5, 193, and 740 map to typed, friendly failures.
- [x] **T21** Main window supersedes splash; Z-order preference is respected.
- [x] **T22** Exactly one terminal summary line is emitted per attempt with its attempt ID.
- [x] **T23** Chain-exception setting defaults off; setup is followed by a second Global PIN entry; enabling requires verification and disabling is free.
- [x] **T24** Every launcher-entry click refreshes the setting and requires a fresh PIN while enabled; Settings navigation does not auto-enter.
- [x] **T25** Launcher On/Off status stays hidden until the first successful manual enable, then follows the live state.
- [x] **T26** Global PIN removal disables the exception; unavailable PIN/database state never enables it or bypasses the entry gate.

## Manual Windows verification — plan §9

These are owner/tester checks. Do not mark complete from Linux/Replit evidence.

- [ ] **1 — A, repeated Notepad launch:** Reopen on each first tap, including within five seconds of closing.
- [ ] **2 — B, same-name multi-process apps:** Cold/open/minimized cases use first tap and raise existing windows.
- [ ] **3 — C, stub/updater apps:** First-tap hand-off works and chain members are not killed.
- [ ] **4 — D, multi-exe launchers:** Steam/Epic/Battle.net/Minecraft launch on first tap without helper kills.
- [ ] **5 — E, elevation matrix:** Normal apps de-elevate as specified; needs-admin and already-elevated cases behave clearly.
- [ ] **6 — F, Store/UWP:** Opens or fails immediately with a clear unsupported message.
- [ ] **7 — G, tray apps:** Hidden live app window is raised on first tap.
- [ ] **8 — H, blocked app:** FocusFlow rule is identified promptly and the app is never spawned.
- [ ] **9 — Rapid taps:** Double/five-click sequence starts exactly one process.
- [ ] **10 — Interruptions:** Break, emergency break, and session end stop progress and leave no orphan state.
- [ ] **11 — Fresh/re-engaged:** Repeat launch tests after break/emergency-break re-engagement.
- [ ] **12 — Escape tools:** Task Manager, shells, renamed shell, and PIN exit behave as specified.
- [ ] **13 — Slow/dead launch:** Live slow app can be cancelled; missing/dead chain fails quickly and retry spawns.
- [ ] **14 — Multi-monitor:** Launcher/lock-screen placement and raised-app visibility are correct.
- [ ] **15 — Performance:** Compare idle/launch CPU; confirm no repeated `tasklist.exe` polling.
- [ ] **16 — UI:** Delayed elapsed-time progress, Cancel, and specific failure details work.

## Out-of-scope observations

Do not fix these under this plan. Record evidence in the final report and route any follow-up separately:

1. Broad Java process safe-list behavior.
2. FocusFlow-owned PowerShell/cmd behavior except for the plan's explicitly scoped P0.5 ownership work.
3. `AppIconExtractor` reflection and icon quality.
4. General app-picker candidate breadth beyond the P1.4 scope.

## Completion reconciliation

Before finalizing an implementation phase, compare this tracker with current code, exact test results, and Windows evidence. Leave unmet items unchecked. The final report must follow plan §11 and state the final constants, safety paths re-read, Windows rows due, deviations, and out-of-scope observations.
