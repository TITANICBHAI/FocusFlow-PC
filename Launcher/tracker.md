# FocusFlow Focus Launcher Reliability — Tracker

- **Source plan:** [plan.md](plan.md)
- **Agent instructions:** [preagent-prompt.md](preagent-prompt.md)
- **Supplemental rules:** [rules.md](rules.md)
- **Overall status:** Planning package prepared on 2026-10-16. Reliability implementation has not started. All code, automated-test, and Windows-runtime items remain open.

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

**Status:** Not started. Follow the ordered P0 sequence above.

### P0.1 — Per-attempt launch tracing

**Status:** In progress — 2026-10-16.

### 2026-10-16 — P0.1 — In progress

- **Scope:** Add per-attempt IDs, bounded phase/event logging, exactly one typed terminal summary, foreground-lock call-result logging, and T22 coverage. No P0.2–P0.5 behavior changes.
- **Current findings:** The coordinator currently emits per-refresh polling logs without attempt IDs; the keyboard hook ignores lock/unlock return values; the existing coordinator test suite has no T22 summary assertion.
- **Files read:** `Launcher/plan.md`, `Launcher/tracker.md`, `Launcher/rules.md`, `Launcher/preagent-prompt.md`, `replit.md`, `.agents/memory/focus-launcher-task-brief.md`, `LauncherLaunchCoordinator.kt`, `JvmLauncherLaunchPlatform.kt`, `WindowRaiser.kt`, `GlobalKeyboardHook.kt`, `EnforcementLog.kt`, and `LauncherLaunchCoordinatorTest.kt`.
- **Working tree:** `main` is ahead of `origin/main` by two commits; the only untracked path is the newly supplied implementation prompt. No application source changes have been made in this batch.
- **Verification:** Pending.

- [ ] Add attempt IDs and phase timings; log meaningful polling changes only.
- [ ] Emit exactly one terminal summary per attempt with the fields required by plan §4/P0.1.
- [ ] Record foreground-lock call results; stay within the plan's logging and privacy limits.
- [ ] **Acceptance:** T22 passes; one failed attempt can be diagnosed by attempt ID with about ten or fewer lines.
- **Implementation note:** Add a dated In progress entry here before editing.

### P0.2 — Remove sticky retry state

- [ ] Decide spawn versus attach from live process state; remove wait-only and spawn-budget decision flags.
- [ ] Keep only a short per-app debounce that prevents duplicate spawns without forcing a wait.
- [ ] **Acceptance:** T1, T2, T5, T6, and T15 pass.

### P0.3 — Liveness-based wait and watchdog

- [ ] Add liveness-based waiting, typed early exit/no-window outcomes, per-attempt tokens, watchdog, and per-app cancellation.
- [ ] Ensure stale workers cannot write state for a newer attempt and cancellation leaves no state/jobs behind.
- [ ] **Acceptance:** T2, T3, T4, T7, and T12 pass.

### P0.4 — Process lineage and window selection

- [ ] Add/reuse a shared process snapshot with PID, parent PID, creation time, image name, and lazy cached full paths.
- [ ] Track the spawn PID, descendants, and matching live instances as a union; reject PID reuse by creation time.
- [ ] Preserve window enumeration order and select/re-evaluate the best candidate as specified in the plan.
- [ ] Handle Store/UWP windows as specified or fail immediately and clearly.
- [ ] **Acceptance:** T3, T8, T16, T17, and T21 pass; applicable Windows archetypes B–D and F are recorded for owner verification.

### P0.5 — Protect launch chains from kiosk enforcement

- [ ] Implement one shared kiosk process decision and use it in the allowed launcher branches.
- [ ] Apply the plan's lineage/install-root and FocusFlow-owned utility rules without weakening escape-tool name/path detection.
- [ ] Use the plan's D1-B default unless the owner explicitly changes the decision.
- [ ] **Acceptance:** T13, T14, and T18 pass; Windows archetype D and escape-tool row 12 remain for the owner.

### P0 execution notes

Add dated entries here before and after each meaningful implementation change. Preserve failed checks and document successful reruns.

## P1 — Correctness and robustness

**Status:** Not started. Respect the dependencies stated in the plan.

### P1.1 — Raise reliability

- [ ] Verify foreground ownership by tracked process chain/root owner, reselect windows on every attempt, and bound native raise calls.
- [ ] Preserve the foreground-lock kiosk guarantee and align raise behavior with overlay handling.
- [ ] **Acceptance:** T8 and T9 pass; Windows archetypes A–E remain owner-verified.

### P1.2 — Pre-flight checks and typed failures

- [ ] Add bounded no-spawn checks for allow status, blocking rules, executable/path support, and unsupported candidates.
- [ ] Carry typed failures to the UI; keep raw OS errors in logs and friendly text on tiles.
- [ ] **Acceptance:** T10 and T20 pass; a known blocked app is not spawned and gives a specific reason promptly.

### P1.3 — Spawn method

- [ ] Resolve the executable before spawn; remove `cmd /c start`; close child stdin and preserve the working-directory rule.
- [ ] Apply the planned elevation behavior and friendly spawn-error mapping.
- [ ] **Acceptance:** Windows elevation matrix row 5 remains owner-verified.

### P1.4 — App-picker hygiene

- [ ] Classify/badge or hide unsupported, stale, system, uninstaller, updater, and installer candidates.
- [ ] Re-resolve selected paths on session entry and restore; expose the resolved path in a tooltip.
- [ ] **Acceptance:** Applicable archetypes F and H and stale-path behavior are verified.

### P1.5 — Shared process snapshot and scan cost

- [ ] Depends on P0.4. Reuse the shared snapshot for the plan-listed sweeps/resolvers while preserving detection latency and path-based escape detection.
- [ ] **Acceptance:** No per-tick `tasklist.exe`; record timing/performance evidence and leave Windows row 15 for owner verification.

### P1.6 — Honest progress, cancel, and labels

- [ ] Depends on P0.3. Show elapsed progress and Cancel after the planned delay; cancel the attempt without killing an already-started app.
- [ ] Show typed, specific failure labels and preserve the planned failure-display behavior.
- [ ] **Acceptance:** Windows rows 13 and 16 remain owner-verified.

### P1 execution notes

Add dated entries for each selected task. Record dependencies, changed files, checks, failures, and open Windows checks.

## P2 — Hardening and polish

**Status:** Not started.

### P2.1 — Setup-screen loader recovery

- [ ] Ensure independent loaders cannot leave the screen loading forever; show a useful error/retry state and log failures.
- [ ] **Acceptance:** T19 passes.

### P2.2 — Overlay and activation

- [ ] Use the shared kiosk decision; avoid activating the launcher when merely restoring topmost state; base post-raise visibility on actual foreground state.
- [ ] **Acceptance:** Allowed apps are not covered by transient system windows; closing the app returns to the launcher.

### P2.3 — Comment and cache debt

- [ ] Correct/document the sweep cadence and handle `ProcessCommandCache` only within the plan's permitted scope.
- [ ] **Acceptance:** Source matches the documented actual cadence; no unrelated log or cache refactor.

### P2 execution notes

Add dated entries for each selected task and preserve the plan's out-of-scope boundary.

## Automated test scenarios — plan §8

All remain unchecked until the named behavior is tested with the fake platform/virtual time as required by the plan.

- [ ] **T1** Spawn, window appears, raise succeeds; no later-attempt influence remains.
- [ ] **T2** Dead-on-arrival process exits quickly; next tap spawns.
- [ ] **T3** Stub exits and a same-root/descendant hand-off process appears.
- [ ] **T4** Slow live process opens on the first attempt with soft progress and no early failure.
- [ ] **T5** After a hard-cap failure and delay, next tap spawns rather than waits.
- [ ] **T6** Close shortly after opening; next tap spawns immediately.
- [ ] **T7** Hung native call is bounded by watchdog; stale worker cannot alter newer attempt.
- [ ] **T8** Foreground verification accepts another HWND of the chain and rejects another process.
- [ ] **T9** Failed raise tiers end within budget and allow a fresh retry.
- [ ] **T10** FocusFlow-blocked app fails pre-flight without a spawn call.
- [ ] **T11** Old windowless/tray instance results in one spawn and successful open.
- [ ] **T12** `cancelAll()` and per-app cancel work in every phase without lingering jobs/state.
- [ ] **T13** Chain members survive; unrelated/renamed escape tools remain blocked.
- [ ] **T14** FocusFlow-owned system utility is exempt only under the specified ownership proof.
- [ ] **T15** Two same-frame taps produce one spawn.
- [ ] **T16** Reused PID with a different creation time is not in the chain.
- [ ] **T17** Unreadable process remains tracked by PID and can own a found window.
- [ ] **T18** Unresolvable lineage is not killed on first sighting; next snapshot decides.
- [ ] **T19** A setup loader exception does not strand the screen in loading.
- [ ] **T20** Spawn errors 2, 3, 5, 193, and 740 map to typed, friendly failures.
- [ ] **T21** Main window supersedes splash; Z-order preference is respected.
- [ ] **T22** Exactly one terminal summary line is emitted per attempt with its attempt ID.

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
