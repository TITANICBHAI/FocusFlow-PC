# Pre-agent Prompt — FocusFlow Focus Launcher Reliability

You are the implementation agent for the Focus Launcher reliability plan. Read the following in full before implementation:

1. `replit.md` — current project and environment instructions.
2. `Launcher/plan.md` — scope, evidence, constraints, task order, acceptance tests, and Windows matrix.
3. `Launcher/tracker.md` — live status, completion criteria, tracking procedure, and recorded evidence.
4. `Launcher/rules.md` — supplemental owner-provided working rules.
5. `.agents/memory/focus-launcher-task-brief.md` — existing launcher architecture and enforcement caveats.

The current request prepared these planning documents only. Do not implement P0/P1/P2 code until the owner explicitly asks to begin implementation or identifies the task(s) to resume. That authorization does not permit work outside the selected plan scope.

## Project context and source of truth

FocusFlow is a Windows-focused Kotlin/Compose Desktop application using Gradle and JNA. The attached plan was based on a `focusflow.zip` snapshot labeled 2.0.5 and says 2.0.6 is planned. Paths, line numbers, assumptions, and test inventory may be stale. The current source, project instructions, working tree, and owner instructions take precedence. Re-check every plan claim against current code before relying on it.

This is a kiosk/lockdown feature. A launch fix must not weaken the session PIN, exit/break/kill-switch paths, `FocusLauncherService.isLaunchAllowed`, or NuclearMode's name- and path-based escape-tool blocking. Do not create a state that can lock the user out of their PC.

## Required working rules

- Work alone. Do not create, invoke, or delegate to sub-agents.
- Follow the plan's implementation order in `Launcher/plan.md` and `Launcher/tracker.md`. Do not silently widen scope or change the plan's contract.
- Before editing, inspect the relevant current code, project instructions, and working tree. Read files to be changed and their callers in full; preserve unrelated user changes and user data.
- Respect the plan's no-touch list and each task's file allowlist unless the owner explicitly changes the scope. Put out-of-scope findings in the final report; do not fix them as drive-by work.
- Treat task replacement and any other deletion of user data as destructive. Keep replacement disabled by default, preserve explicit confirmation and required safety guards, and do not perform destructive actions without the owner's authorization.
- At the start of each implementation batch, add a dated **In progress** note to the tracker before editing. Update it after every meaningful change.
- Tick a tracker item only when the work is complete and verified against its acceptance criteria. Keep failed, partial, attempted, and unverified work unchecked. Record changed files, exact verification and results, decisions, blockers, and deferred work. Preserve failures and append successful reruns; do not erase history.
- Run applicable Gradle tests using the current workflow or scripts under `scripts/`. Android tests do not apply to this desktop project. Use GitHub Actions only if the owner explicitly requests APK-building verification; otherwise state clearly which checks were not run.
- Do not push to GitHub or start, poll, or monitor GitHub Actions unless the owner explicitly asks.
- Do not expose, copy into files, print, or commit secrets or credentials.
- Keep Win32 calls and process enumeration off the Compose UI thread. Keep `LauncherLaunchCoordinator.request()` non-blocking and able to reject a second same-frame click. Keep Windows calls behind `isWindows` and the existing `LaunchPlatform` seam.
- Use `EnforcementLog` with tag `Launcher`; log phase changes and one terminal summary per attempt, not each polling iteration. Keep to the plan's line-volume and privacy limits; optional telemetry must not include app names or paths.
- Follow the edited file's existing string convention. Add plain-language `FIX`/`IMP` lines to the 2.0.6 **PLANNED** changelog entry as required by the plan.
- Linux/Replit compile or unit-test results do not prove Windows behavior. Keep §9 items unchecked until the owner/tester supplies Windows evidence.
- Follow plan §11 for the final report and keep `Launcher/tracker.md` reconciled with the code and evidence.

## Scope and order

The allowed implementation scope is only plan §§4–6, using the per-task file allowlists:

1. **P0 in order:** P0.1 diagnostics; P0.2/P0.3/P0.4 together as a coordinator refactor; then P0.5 (depends on P0.4).
2. **P1:** follow each task's dependencies. P1.5 depends on P0.4; P1.6 depends on P0.3.
3. **P2:** follow each task's dependencies. Do not use P2 as permission for general cleanup.

The plan's defaults and safety invariants are binding. For P0.5, use decision D1-B unless the owner explicitly changes it. Do not remove the escape-tool path detection or allow a chain exemption to override it.

## Verification and evidence

- Use the plan's fake `LaunchPlatform` and virtual-time scenarios T1–T26; search for existing coverage before adding tests.
- Record exact commands, results, files read/changed, failures/reruns, and outstanding owner decisions in `Launcher/tracker.md`. Add phase reports when a phase is completed.
- Require final Gradle test task results and inspect generated reports before marking automated tests complete. Compilation alone is not test evidence.
- Clearly distinguish source-confirmed, inferred, and runtime-unverified claims. Never call Windows-specific behavior verified without Windows evidence.
- Do not run a destructive operation or use a real user's FocusFlow data/profile as a test fixture.
- Keep all §9 Windows rows pending until a human owner/tester completes them.

## Final response

Report completed and partial tasks, files read/changed, exact verification commands and results, deviations, failures/reruns, owner decisions, deferred work, Windows checks still required, final constants, safety paths re-read, and out-of-scope observations. Do not claim the plan is fully complete while required checks remain open.
