# FocusFlow Focus Launcher — V3 report (2026-11-14)

## Are the original failures fixed?

**Not provably.** The logic bugs are fixed and tested. The one root failure, `CreateProcessWithTokenW` error 5, has no known cause. Your log contains no privilege, session, desktop or service data. V3 adds a retry chain and a one-time diagnostics line so the next log will show the cause. Whether any strategy works on your PC can only be settled on Windows.

| Original problem | Status |
|---|---|
| App fails to start under elevated FocusFlow (error 5) | **Unresolved root cause.** Three automatic strategies + diagnostics. Unknown until run on Windows. |
| Works fresh, then fails after minimising, persists across sessions | Fixed in logic: minimised windows are kept as candidates and restored; "attach" only waits for a process FocusFlow itself started; background-only apps spawn instead of waiting 60 s. Tested offline. |
| PIN dialog has no way back | Fixed: "Cancel – Go back" button (not run on Windows). |
| Need an automatic fallback | Done: `user-shell-token` → `user-shell-token-full` → `explorer-handoff`. |

## Changes in V3 (on top of V2)

- **No explicit `lpDesktop`** anywhere (documentation: NULL lets the API grant desktop access itself).
- **Error 5 investigation in code:** `SeImpersonatePrivilege` is read and enabled only if present-but-disabled; on the first token failure one log line records FocusFlow/shell session ids, both token elevation types, the privilege state and the Secondary Logon service start type.
- **Elevation fails safe:** elevation is `Elevated` / `NotElevated` / `Unknown`. Unknown for the window owner means the window is not raised; after 5 s the attempt fails with a clear message and nothing is killed.
- **Only this hand-off's processes are judged**, by PID + creation time; the process is re-verified before and after the token read, and termination re-checks the start time. Pre-existing (even elevated) instances are never touched.
- **Checks run early and often:** every process refresh, before a window is accepted, and before each raise retry. A later attempt that attaches to an earlier hand-off inherits the check.
- **Preferred strategy is remembered only after verification:** window raised, and for Explorer hand-off at least one process it created was proven unelevated.
- Reused `ProcessSnapshot.isAlive`, `ProcessIdentity`, `ProcessLineageTracker`; no new snapshot infrastructure.
- Changelog and `Launcher/tracker.md` updated (tracker says "NOT verified on Windows").

## What was actually executed

| Item | Result |
|---|---|
| Kotlin 2.0.21 compile of `LauncherLaunchCoordinator.kt`, `ProcessSnapshot.kt`, `LauncherKioskPolicy.kt` + tests (stubs for untouched classes, shimmed coroutines-test / kotlin.test) | Compiled |
| `LauncherLaunchCoordinatorTest` (all tests, incl. 15 new) | **58 / 58 passed** |
| `LauncherKioskPolicyTest` (incl. 2 new; `NuclearMode` stubbed) | **8 / 8 passed** |
| Mutation checks (unknown→safe, remember-always, no creation-time filter) | New tests failed as intended |
| `JvmLauncherLaunchPlatform.kt` | Type-checked only, against minimal JNA stubs; not run |

## NOT executed

- The real Gradle build and the real `kotlinx-coroutines-test`/JUnit (plugins unresolvable offline). I did not run Gradle or any build on your machine.
- `WindowRaiser.kt`, `FocusLauncherService.kt`, both Compose screens, `ChangelogScreen.kt`: not compiled here.
- Anything on Windows. No Windows behaviour is verified.

## Real-Windows verification steps (after you approve a build)

1. `gradlew test` — expect all green, especially the two test classes above.
2. Run FocusFlow elevated, start a Focus Launcher session, tap an app that is closed (e.g. Edge). Open `enforcement.log` and look for:
   - `token-launch SeImpersonatePrivilege=…`
   - `spawn strategy=… app=…` lines, and which strategy ends with `verified; it will be tried first`
   - on error 5: `token-launch diagnostics …` — send me that line (secondaryLogonStart=4 means the service is disabled; `absent` privilege or different sessions would also explain it).
3. Confirm the opened app is **not** elevated (Task Manager → Details → "Elevated" column).
4. Tap an app that is already open → it is raised, no second instance.
5. Minimise it, tap again → restored, no second instance (also after a new session).
6. Close the app but leave background processes (Edge), tap → it starts within seconds, not after 60 s.
7. If `explorer-handoff` was the one used: confirm no admin-level app opens; if one does, it should be closed and show "administrator rights".
8. Task Manager, cmd/PowerShell and other escape tools stay blocked; the app's own helpers (e.g. Edge/WebView) survive.
9. "Your Session PIN" dialog: "Cancel – Go back" returns to the picker and a new PIN is generated next time.
