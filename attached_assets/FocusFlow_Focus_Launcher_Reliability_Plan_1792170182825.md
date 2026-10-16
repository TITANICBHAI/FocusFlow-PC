# FocusFlow — Focus Launcher Reliability Plan

**For:** the implementing agent (assume no prior context — this document is self-contained)
**Product:** FocusFlow for Windows (Kotlin, Compose Desktop, JVM + JNA), package `com.focusflow`
**Feature:** Focus Launcher (kiosk mode) — opening an allowed app by tapping its tile
**Basis of review:** static read of `focusflow.zip` (151 `.kt` files, `src/main` only — no tests, no build files were included). Changelog shows 2.0.5 shipped and 2.0.6 planned.

**How to read the evidence tags**
- **[code]** — verified by reading the source; file and line numbers are given.
- **[verify]** — Windows runtime behaviour that could not be executed during the review. Do not treat it as fact: P0.1 adds the logging that confirms or kills each one.

---

## 0. Ground rules

1. **Scope.** Only the tasks in §4–§6. Each task lists the files it may change. Anything else you notice goes in your final report under "Out-of-scope observations" — do not fix it.
2. **Priority numbering.** Do P0 in order (P0.1 → P0.5). P1 and P2 can follow in any order unless a task says "Depends on".
3. **"Direction" is the outcome, not the recipe.** If you know a better mechanism, use it. "Done when" is the contract. Any code or pseudo-code in this document is illustrative only.
4. **You cannot run Windows.** Keep every Win32 call behind `isWindows` and the existing `LaunchPlatform` seam. Unit-test the state machine against fakes (these run on Linux). Anything Windows-specific is verified by the owner with the sheet in §9 — never claim it is verified.
5. **Kiosk safety is non-negotiable.** No change may weaken the session-PIN gate, the exit / break / kill-switch paths, `FocusLauncherService.isLaunchAllowed`, or NuclearMode's escape-tool blocking (including its *path-based* detection of renamed `cmd.exe` / `taskmgr.exe` etc.). A change that could leave the user locked out of their own PC is a failed change.
6. **Threading.** Never call Win32 or enumerate processes on the Compose UI thread. `LauncherLaunchCoordinator.request()` must stay non-blocking and must still reject a second click in the same UI frame.
7. **Logging.** Use `EnforcementLog` with tag `Launcher`. File: `%USERPROFILE%\.focusflow\enforcement.log` (rotates at 512 KB). Log phase transitions and one summary line per attempt — never per poll.
8. **Strings.** Follow the convention of the file you are editing (`LauncherContent.kt` hard-codes English; the setup screen uses `strings.*`).
9. **Changelog.** Add plain-language lines (tags `FIX` / `IMP`, as used there) to the **2.0.6 "PLANNED"** entry in `ChangelogScreen.kt`.
10. **Tests.** The sources you were given contain no tests, but the code is written for injection (`LaunchPlatform`, `ProcessImageApi`, `ProcessCommandCache`). Search the repo for existing tests of `LauncherLaunchCoordinator`, `ProcessSnapshot`, `ProcessNameResolver` and update them instead of adding duplicates.

---

## 1. Summary for the agent

**Owner's report:** tapping an app in Focus Launcher shows a spinner ("Opening…") that sometimes runs 30–45 s and then fails; other times the app opens at once; sometimes it only opens on the second tap. Goal: make it reliable.

**What the code review found (ranked by how directly it produces that symptom):**

| # | Finding | Task |
|---|---|---|
| 1 | The retry design is sticky and time-based: a stale "wait-only" flag and a 20 s "spawn budget" make a tap wait 20 s for a window **even when no process exists**. This accounts for a 40–46 s spin (two 20 s waits) and the "2nd / 3rd tap" pattern — derived from the code, not observed. | P0.2 |
| 2 | Waiting is a fixed 20 s guess with no liveness check: dead launches spin the full 20 s, slow-but-healthy launches fail at 20 s and then succeed on retry. No hard deadline exists outside the worker, so a blocked native call can spin forever. | P0.3 |
| 3 | The launched app is tracked by exe name only, and the pid returned by `spawn` is dropped after ~1 s. Stub/launcher apps, unreadable (elevated) processes and Store apps are never matched; the window picked is the lowest handle (often a splash). | P0.4 |
| 4 | Kiosk enforcement fights the launch: the sweep and the foreground check kill any process not on the allow-list — including the app's own helpers/children and the `cmd.exe` fallback spawner — and the foreground hook steals focus back. Timing-dependent → "sometimes works". | P0.5 |
| 5 | Raise verification is too strict (exact HWND) and runs under a foreground lock; native calls can block on a busy target. | P1.1 |
| 6 | The launcher doesn't know FocusFlow's *own* block rules; a blocked app is spawned, killed, and the tile spins. | P1.2 |
| 7 | Spawn method inherits FocusFlow's elevation; raw OS errors surface to the user. | P1.3 |
| 8 | The app picker can offer uninstallers/updaters/Store apps and stale paths. | P1.4 |
| 9 | Several independent full-process scans every second during a session (NuclearMode alone does two per 500 ms tick, plus the kiosk sweep and the coordinator) add CPU load right when an app starts. | P1.5 |
| 10 | UI copy and affordances (no cancel, "a few seconds" for a 20 s wait, substring-matched labels). | P1.6 |

**Recommended order:** P0.1 (tiny, do first so before/after is measurable) → P0.2 + P0.3 + P0.4 as one coordinator refactor → P0.5 → P1.x → P2.x.

---

## 2. How the feature works today (self-contained context)

### 2.1 Pipeline: tap → app on screen

| Step | What happens | Where |
|---|---|---|
| 1 | Tile click → `FocusLauncherService.launchApp(app)` → `LauncherLaunchCoordinator.shared.request(app)` | `ui/launcher/LauncherContent.kt:339–341`, `services/FocusLauncherService.kt:149–150` |
| 2 | `request()` rejects if not allowed / already `Launching` / `Failed(canRetry=false)`; marks the tile `Launching`; starts a lazy worker on `Dispatchers.IO` | `services/LauncherLaunchCoordinator.kt:100–141` |
| 3 | `executeRequest`: `findInstances` → `findWindows`; decides **raise existing window** vs **wait** vs **spawn** | `LauncherLaunchCoordinator.kt:160–256` |
| 4 | Platform (`JvmLauncherLaunchPlatform`): `findInstances` = `ProcessSnapshot.findInstances` (name/exact-path match via `ProcessHandle`); `findWindows` = `WindowRaiser.findWindows` (JNA `EnumWindows`); `spawn` = `ProcessBuilder(exePath)` or `cmd /c start "" <exe>`; `raise` = `WindowRaiser.raise` | `services/JvmLauncherLaunchPlatform.kt`, `services/ProcessSnapshot.kt`, `enforcement/WindowRaiser.kt` |
| 5 | `awaitWindow` polls every 150 ms for a visible top-level window of the tracked pids, up to **20 s** | `LauncherLaunchCoordinator.kt:258–285` |
| 6 | `raiseWithRetries`: up to 3 attempts × 4 activation tiers (`SetForegroundWindow`, `AllowSetForegroundWindow`, `AttachThreadInput`, synthetic Alt), each verified after 300 ms by exact HWND | `LauncherLaunchCoordinator.kt:287–322`, `WindowRaiser.kt:129–187` |
| 7 | Success → `onRaised` → `FocusLauncherService.onForegroundChanged` → launcher windows drop below the app → state `Idle`. Failure → `Failed(reason)` shown 6 s on the tile, then `Idle` | `LauncherLaunchCoordinator.kt:302–308, 324–351` |

Current constants (`LauncherLaunchCoordinator.kt:62–68`): `WINDOW_WAIT_MS=20000`, `SPAWN_BUDGET_MS=20000`, `WINDOW_POLL_MS=150`, `PROCESS_REFRESH_MS=1000`, `FAILURE_DISPLAY_MS=6000`, `RAISE_RETRY_DELAY_MS=150`, `MAX_RAISE_ATTEMPTS=3`.

### 2.2 Session start side effects

`FocusLauncherService.enter()` (`FocusLauncherService.kt:158–219`) sets `ProcessMonitor.launcherAllowedProcesses` (the allow-list = exe names of the selected tiles, lower-cased), enables **NuclearMode** (silent), installs the **global keyboard hook**, applies **registry lockdown**, starts the **taskbar guard**, and the primary monitor shows the tile grid while other monitors show a lock screen (`ui/launcher/LauncherWindow.kt`). The normal FocusFlow window is hidden (`Main.kt:472–474`).

### 2.3 Everything that runs concurrently with a launch

| Actor | Cadence | What it does that can affect a launch |
|---|---|---|
| `ProcessMonitor.tickPoll` → `launcherSweep()` | once per poll tick: **2 s** with the WinEvent hook, **750 ms** without (`ProcessMonitor.kt:51–54, 426–443, 484–491`) | Enumerates all processes and kills (`destroyForcibly` + `taskkill /F /PID`) every one whose image name is not in the allow-list or `launcherSafeProcesses` (`:559–603`). The `250 ms` constant (`:461`) is only a minimum gap — it is *not* the cadence. |
| `ProcessMonitor.checkProcess` (fed by `WinEventHook`) | instant, every foreground change | Kills the foreground process if its image isn't allowed/safe (`:630–668`) |
| `WinEventHook` callback | every foreground change | Resolves the exe via `ProcessHandle.info()`; if not allowed/safe calls `SetForegroundWindow(FocusFlow window)` ("focus reclamation", `WinEventHook.kt:158–169`); drives the launcher overlay via `onLauncherForegroundChanged` |
| `NuclearMode.enforceTick` | every ~500 ms (`NuclearMode.kt:280–285, 323–328`) | Spawns `tasklist`, does a second full process scan by path, and `taskkill`s "escape" tools by name **and** path — list includes `cmd.exe`, `powershell.exe`, `wscript.exe`, `msiexec.exe`, `unins000.exe`, `uninstall.exe`… (`:52–76`) |
| `GlobalKeyboardHook` | always while kiosk is on | Suppresses Win / Alt+Tab / Alt+Esc / Alt+F4 / Alt+Space / Ctrl+Esc and calls `LockSetForegroundWindow(LSFW_LOCK)` (`GlobalKeyboardHook.kt:175`) |
| Taskbar guard | 500 ms | Hides the taskbar |
| `LauncherWindowHost` | — | One undecorated fullscreen window per monitor, `alwaysOnTop` while `overlayVisible` |

---

## 3. The reported symptom and how the code produces it

### 3.1 Worked timeline of the "40+ second" failure  [code]

| t | Event | Evidence |
|---|---|---|
| 0 s | Tap. State → `Launching`. Worker finds no window, no "recent instance", no spawn budget → spawns. `lastSpawnAtMs[key]` is stamped | `LauncherLaunchCoordinator.kt:100–141, 181–233` |
| 0–20 s | `awaitWindow` polls. After ≈1 s the tracked pid set is **replaced** by a name-based lookup, dropping the pid returned by `spawn` | `:258–285` (replacement at `:277–282`) |
| ≈20 s | No window matched (process died / was killed / window owned by another exe / app just slow) → `Failed("<app> is taking longer than usual")` and **`waitOnlyRetryKeys += key`** | `:233–241, 324–351` |
| 20–26 s | Tile shows "Taking longer than usual — tap to check again" for 6 s, then `Idle`. The flag is **not** cleared by the reset | `LauncherContent.kt:418–424`; `LauncherLaunchCoordinator.kt:338–349` |
| ≈26 s | Second tap. Flag consumed → "retry will only wait/focus; no spawn" **even if nothing is running**; waits another 20 s | `:118, 191–213` |
| ≈46 s | App was merely slow → window finally appears → "opens in 2 tries". Process was dead → fails again, and the flag is now off (`waitOnlyNextRetry = !waitOnlyRetry`) | `:206, 240` |
| ≈52 s | Third tap finally spawns | |

The 30–45 s the owner sees = one or two 20 s waits plus whatever the blocking snapshot / spawn calls add. This timeline is **derived from the code, not observed**.

### 3.2 Symptom variants → mechanism

| Variant | Mechanism | Task |
|---|---|---|
| Spins ~20 s, then "Taking longer than usual" | Window not matched in the fixed wait: app killed or hijacked by kiosk enforcement, window owned by a different exe, spawn pid dropped, unreadable (elevated) process, or simply slow | P0.3, P0.4, P0.5 |
| Second tap opens it | Slow app; the wait-only retry gives it another 20 s | P0.2, P0.3 |
| Needs three taps / ~45 s+ and fails | Stale wait-only flag with no live process | P0.2 |
| Sometimes opens at once | Same-name multi-process apps (no enforcement collision), or app already running with a visible window → raise path | — |
| Opened, then the launcher covers it again *(predicted, not reported)* | Overlay flips to topmost on any non-allowed foreground event and re-asserts topmost *with activation* | P1.1, P2.2 |
| Failed tile although the app is in front *(predicted)* | Raise verified by exact HWND | P1.1 |
| App-specific "never opens" | Blocked by a FocusFlow rule; uninstaller/updater picked as the exe; Store app; app requires elevation | P1.2–P1.4 |

### 3.3 What the code alone cannot tell us

The *first* trigger differs per app type and per machine (cold start, antivirus, elevation, which exe owns the first window). P0.1 makes every attempt self-diagnosing. Owner-supplied context worth collecting if problems persist after the fix is in Appendix B.

---

## 4. P0 — directly cause the reported symptom (do in order)

### P0.1 — Per-attempt launch tracing (do first)

**Size:** S  **Files you may change:** `LauncherLaunchCoordinator.kt`, `JvmLauncherLaunchPlatform.kt`, `WindowRaiser.kt` (log fields only), `GlobalKeyboardHook.kt` (log the lock call's result only)

**Why:** The failure cannot be reproduced on demand and several suspected causes are Windows-runtime-dependent. Today's log is noisy and has no timings, so a bad launch cannot be explained after the fact.

**Evidence**
- [code] `awaitWindow` logs "refresh instances …" every second (`LauncherLaunchCoordinator.kt:279`); `executeRequest` logs several lines per attempt with no attempt id, no phase timings and no single outcome line.
- [code] `GlobalKeyboardHook.enable()` ignores the boolean returned by `LockSetForegroundWindow` (`GlobalKeyboardHook.kt:175`).

**Direction**
- Give every request an attempt id and put it on every log line of that attempt.
- Record phase timings: snapshot, spawn call, time to first tracked window, raise (with per-tier results), total.
- Emit **exactly one summary line per attempt** at the terminal state: attempt id, exe, result (`Opened` / `Failed` + typed code), total ms, spawn pid and whether it was still alive at the end, tracked pids at the end, windows seen, FocusFlow elevated?, foreground-lock call result, tier results.
- During polling, log only *changes*.
- Log the boolean result of `LockSetForegroundWindow` (lock and unlock) every time it is called.
- Optional — only if the existing telemetry plumbing supports it under the current consent rules: typed result + timings + elevated flag. **No app names or paths.**

**Done when:** one failed launch can be diagnosed from a single grep of `[Launcher]` by attempt id; no more than ~10 lines per attempt; T22 (§8) passes.

---

### P0.2 — Remove sticky retry state; decide spawn vs. attach from live process state

**Size:** M  **Files you may change:** `LauncherLaunchCoordinator.kt` (+ tests)

**Evidence**
- [code] `waitOnlyRetryKeys` (`:90`) is set when an attempt times out (`:201–207`, `:235–241`, `:334`) and is consumed only by the next *tap* (`:118`). The Failed→Idle reset (`:338–349`) does not clear it. A tap minutes later still takes the "will only wait/focus; no spawn" branch (`:191–199`) even when nothing is running, then waits the full 20 s.
- [code] When a wait-only attempt also times out the flag flips off (`waitOnlyNextRetry = !waitOnlyRetry`, `:206`, `:240`), so a fresh spawn needs the third tap.
- [code] `lastSpawnAtMs` (`:89`, `:215–218`) is never cleared. For 20 s after *any* spawn, `withinSpawnBudget` (`:186–189`) forces the wait-without-spawn branch — including after the app opened fine and was closed, crashed on start, or was killed. (Open → close → tap again within 20 s = a dead 20 s wait.)
- [code] `hasRecentInstance` (`:182–185`): `ProcessSnapshot` sets `startedAtMs = nowMs` when the start time is unavailable (`ProcessSnapshot.kt:57`), so such a process looks "recent" at every snapshot.

**Direction**
- Stop using elapsed-time flags as decision inputs. Decide at tap time from live state (decision table in §7).
- "Live" = the process exists now (pid + creation time). "A spawn happened recently" is not evidence of anything.
- Keep only a short spawn debounce (≥ ~2 s) against double-spawn races. It must never cause a wait.
- Nothing may outlive its attempt except information derived from live processes.
- A chain that already hit the hard cap (`NoWindow`) is stale: the next tap spawns instead of attaching.

**Done when:** T1, T2, T5, T6, T15 (§8) pass; no code path reads a wait-only flag or a spawn-budget timestamp.

---

### P0.3 — Liveness-based waiting, early exit, hard cap, watchdog, per-attempt tokens

**Size:** M–L  **Files you may change:** `LauncherLaunchCoordinator.kt`, the `LaunchPlatform` interface (additive only), tests

**Evidence**
- [code] `awaitWindow` (`:258–285`) always runs the full `WINDOW_WAIT_MS`, even if the spawned process exited at second 1 and nothing matching exists.
- [code] 20 s is a fixed constant. A start that is still healthy at 20 s is reported as failed and only succeeds on the retry. **[verify per app]** Large apps, first runs under antivirus and cold disks are the likely candidates (Steam, Electron and Java apps in particular).
- [code] There is no deadline outside the worker. `findInstances` (all-process scan), `findWindows`, `spawn` and `raise` are blocking native calls; coroutine cancellation cannot interrupt them. If one blocks, the tile stays `Launching` indefinitely — nothing forces a terminal state.
- [code] `generation` (`:93–94`) is global (bumped only by `cancelAll`), not per attempt: a stale worker that outlives its attempt could call `setIdle` / `fail` for a newer attempt of the same app.

**Direction** (behaviour spec in §7)
- Wait by liveness: keep waiting while the tracked chain (P0.4) has live processes, up to a generous hard cap; fail fast when the chain is gone and nothing replaced it (short grace for stub → real-process hand-off) with typed `ExitedEarly`.
- Soft notice at ~10 s (UI in P1.6); hard cap → typed `NoWindow`.
- A watchdog independent of the worker forces a terminal state at hard cap + margin even if the worker is stuck in a native call. Every state write carries an attempt token; writes from superseded workers are discarded.
- Native calls that can block on a foreign UI thread are bounded (see P1.1) or run on a disposable thread with a timeout.
- Add a per-app `cancel(app)` next to `cancelAll()`. Both must leave no state and no jobs behind.

**Done when:** T2, T3, T4, T7, T12 pass.

---

### P0.4 — Track by lineage, not just exe name; pick the right window

**Size:** L  **Files you may change:** a new shared snapshot/lineage component (name is yours), `ProcessSnapshot.kt`, `WindowRaiser.kt` (`findWindows` only), `LauncherLaunchCoordinator.kt`, `JvmLauncherLaunchPlatform.kt`

**Evidence**
- [code] `awaitWindow` replaces the tracked set with a name-based `findInstances` result every `PROCESS_REFRESH_MS` (`:277–282`). The pid returned by `spawn` (added at `:232`) is dropped after ≈1 s; if the name lookup cannot see the process, no window is ever found.
- [code] `ProcessSnapshot.findInstances` (`ProcessSnapshot.kt:29–65`) identifies processes via `ProcessHandle.info().command()` and skips blanks (`:48`). **[verify]** On Windows that value is empty for processes the JVM cannot open with the access it requests — typically elevated or protected processes when FocusFlow is not elevated. The codebase already has a more robust path (`WindowsProcessImageApi.queryFullImagePath`: `QueryFullProcessImageNameW` with `PROCESS_QUERY_LIMITED_INFORMATION`, `enforcement/ProcessNameResolver.kt:77–101`) that the launcher does not use.
- [code] Matching is by image name or exact path only. Stub/updater launchers (the stub exits after starting the real app), multi-exe launchers and Store apps (window owned by `ApplicationFrameHost.exe`) never yield a window for the tracked pids.
- [code] `WindowRaiser.findWindows` (`:92–127`) returns every visible, non-cloaked top-level window of the pids, then sorts by (pid, handle) (`:120–122`). That discards `EnumWindows`' Z-order, and `windows.first()` (coordinator `:177`, `:270`) is the **lowest handle** — typically the oldest window (splash / helper), not the main window or the one used last.

**Direction**
- One shared, thread-safe, cheap process snapshot: pid, parent pid, creation time, image name for every process; full path resolved lazily with a limited-rights query and cached per (pid, creation time). Short TTL so concurrent callers share one snapshot. Avoid per-process `ProcessHandle.info()` in loops (P1.5 adopts the same component elsewhere).
- Tracked set for an attempt = spawn pid ∪ its live descendants ∪ live instances matching the app (name/path). **Union, never replace.** A pid leaves the set only when it is gone; compare creation time to defeat PID reuse.
- Stub hand-off: when the spawn pid exits, newly created processes that descend from it, or whose image lives under the same install root as the app's exe, join the chain. (Install root: the exe's directory; for Squirrel-style layouts `...\<App>\app-x.y.z\<exe>` the parent of the `app-*` directory.)
- Window choice: prefer owner-less, titled, non-tool windows of reasonable size, top-most in Z-order; ignore zero-size / off-screen helper windows; keep `EnumWindows` order as the tiebreak. Re-evaluate every poll — a splash window must be superseded by the main window when it appears.
- Store/UWP: either recognise the hosted window (frame ↔ `Windows.UI.Core.CoreWindow` child; `ProcessNameResolver.storeAppChildProcessId` already does this for foreground events) or fail immediately and clearly (P1.2). Never wait for a window that cannot be matched.

**Done when:** T3, T8, T16, T17, T21 pass; Windows archetypes B–D and F (§9) behave as specified.

---

### P0.5 — Stop kiosk enforcement from killing or fighting the app's own launch chain

**Size:** L  **Depends on:** P0.4  **Files you may change:** `ProcessMonitor.kt` (launcher branches only), `WinEventHook.kt` (reclaim block only), `NuclearMode.kt` (ownership exemption only), `FocusLauncherService.kt` (allow-set plumbing only)

**Evidence**
- [code] `launcherSweep` (`ProcessMonitor.kt:559–603`) kills every process whose image name is not in the allow-list or `launcherSafeProcesses` (`:578`), excluding only FocusFlow's own pid (`:561`). Nothing exempts the app's own children or helpers.
- [code] `checkProcess`'s launcher branch (`:630–668`) does the same instantly for the foreground process, and `WinEventHook.kt:158–169` additionally calls `SetForegroundWindow(FocusFlow window)` ("focus reclamation") for it.
- **[verify per app]** An allowed app whose first window or helper processes carry a different exe name than its tile — e.g. Steam → `steamwebhelper.exe`, Epic → `EpicWebHelper.exe`, Squirrel apps → `Update.exe`, launchers that hand off to a differently named binary, WebView2-based apps — is killed during startup or has focus taken away while the launcher keeps spinning.
- [code] `JvmLauncherLaunchPlatform.spawn` falls back to `cmd /c start "" <exe>` when there is no exe path (`:36`). `cmd.exe` is in NuclearMode's escape list (`NuclearMode.kt:61`), NuclearMode is enabled for every kiosk session (`FocusLauncherService.kt:198`), and the sweep also treats it as "not allowed". **[verify]** Whether the spawn helper lives long enough to run `start` is then a matter of timing → "sometimes works".
- [code] FocusFlow's own helper processes are not exempt either (the sweep excludes only FocusFlow's own pid): `tasklist` (`NuclearMode.kt:144–171`, every tick), `taskkill` (`WinApiBindings.kt:222–242`), `powershell` (`NetworkBlocker.kt:240–262`, used for firewall rules), `schtasks` (`WatchdogInstaller.kt`), `ipconfig` / `nslookup` (`HostsBlocker.kt`). NuclearMode also kills `powershell.exe` / `cmd.exe` regardless of who started them. **[verify]** Because these helpers are short-lived the sweep would catch them only occasionally — which would show up as random failures of FocusFlow's own enforcement calls while a session is on.

**Direction**
- One shared kiosk decision function ("may this process exist in kiosk mode?") used by the sweep, the foreground check, the focus-reclaim block and the overlay logic (P2.2). It replaces four copies of "allowed or safe?" that have drifted apart (e.g. `FocusLauncherService.onForegroundChanged` ignores the safe list).
- A process is acceptable if: it is FocusFlow itself or on the safe list (as today); **or** an allowed app (as today); **or** it belongs to an allowed app's chain per Decision D1 below; **or** it is a FocusFlow-owned system utility.
- *FocusFlow-owned utility* = direct child of FocusFlow's pid, image under `%SystemRoot%`, and not the root of a launcher chain. Apply this ownership proof in **both** the sweep and NuclearMode's kill so FocusFlow's own `tasklist` / `taskkill` / `powershell` / `schtasks` are not killed. Everything NuclearMode kills today for any *other* parent stays killed.
- NuclearMode's escape-tool detection (name list **and** path suffixes) still wins over every chain exemption, so a renamed shell inside an app's chain is still killed.
- Chain membership must be proven by lineage and/or install path, never by name alone (name-only exemptions are spoofable: rename `taskmgr.exe` to `steamwebhelper.exe`).
- When lineage cannot be determined (snapshot race), prefer waiting one more tick over a wrong kill.
- Remove the `cmd /c start` spawn path (P1.3).

**Decision D1 — what belongs to an allowed app's chain?** (owner can override; **default = B**)
- **A. Strict:** only the launch chain during a startup grace (default 45 s after spawn). Helpers that outlive the grace and aren't allowed are killed — today's behaviour plus a grace period.
- **B. Default:** descendants of an allowed process **and** processes whose image sits under that app's install root, for as long as the allowed root process is alive; escape tools always excluded. Trade-off: an allowed app that can start other programs (Steam → games, VS Code → tools) can run them. Add a one-line notice on the setup screen: "Apps you allow can start their own helper programs; those are allowed too."
- **C. Curated:** option A plus a maintained helper map for popular apps (Steam, Epic, Battle.net, Discord, Teams…). Strict, but needs upkeep.

**Done when:** T13, T14, T18 pass; archetype D (Steam / Epic / Battle.net / Minecraft Launcher) opens on the first tap; the log never shows a kill of a chain member; escape tools are still killed (§9 row 12).

---

## 5. P1 — correctness and robustness in the same pipeline

### P1.1 — Raise reliability

**Size:** M  **Files you may change:** `WindowRaiser.kt`, `GlobalKeyboardHook.kt` (foreground-lock handling only), `LauncherLaunchCoordinator.raiseWithRetries`

**Evidence**
- [code] Success = `GetForegroundWindow()` equals the **exact HWND** that was found (`WindowRaiser.kt:166–170`). Apps often foreground a *different* top-level window of the same process (splash → main window, owned popups). Verification then fails although the app is in front → up to 3 attempts × 4 tiers × 300 ms (≈3.6 s) → `Failed("Couldn't bring X to the front")` on an app that is open.
- [code] `GlobalKeyboardHook.enable()` calls `LockSetForegroundWindow(LSFW_LOCK)` and ignores the result (`:175`). **[verify]** While the lock is in effect `SetForegroundWindow` is expected to fail for every process, including launched apps, so tiers 1–3 probably fail until the synthetic-Alt tier (4th) releases it. After a break or kill-switch cycle `enable()` runs from a background coroutine (FocusFlow is not the foreground process), where the lock call itself probably fails — so behaviour may differ between a fresh session and a re-engaged one.
- [code] `ShowWindow(SW_RESTORE)` and `SetWindowPos(HWND_TOP, SWP_SHOWWINDOW)` (`WindowRaiser.kt:138–147`) are synchronous cross-process calls. If the target's UI thread is not pumping messages (typical in the first seconds of an Electron / Java / Unity app) the call can block the worker, with no timeout and no way to cancel it.
- [code] The raise works on one window chosen once per attempt; the retry refreshes by pid only (coordinator `:312–313`).

**Direction**
- Success criterion: the foreground window belongs to the tracked chain (same process set, or its root owner is the target window) and stays so for a short confirmation (≈200–300 ms).
- Re-select the best window on every attempt (P0.4 rules).
- Use non-blocking variants (`ShowWindowAsync` / `SWP_ASYNCWINDOWPOS`) or run native raise work on a disposable thread with a hard time budget (≈2.5 s for the whole raise phase).
- Order tiers by what actually works in kiosk state (use the P0.1 logs); skip tiers known to be futile while the lock is active. Keep the synthetic-Alt trick only as a last resort and make sure it cannot leave the target's menu bar activated.
- Do **not** leave the foreground lock released after a raise — the kiosk guarantee (apps cannot steal focus) must remain. If the lock must be lifted for the raise, re-apply it immediately after. Log the lock call's boolean result every time.
- The raise sequence and the overlay logic (P2.2) must agree, so a successful raise is not immediately covered again.

**Done when:** T8, T9 pass; Windows archetypes A–E raise on the first tap in a fresh session **and** in a session re-engaged after a break / kill switch.

---

### P1.2 — Pre-flight checks and typed failures

**Size:** M  **Files you may change:** `LauncherLaunchCoordinator.kt`, `ProcessMonitor.kt` (add a read-only "why is this blocked?" accessor), `LauncherContent.kt` (label mapping only)

**Evidence**
- [code] Allowed launcher apps still go through **all** normal enforcement (`ProcessMonitor.kt:664–668`): always-on rules, focus-session extras, schedules, standalone blocks, daily-allowance caps, VPN-client blocking, keyword and network rules. The launcher knows nothing about this: it spawns the app, ProcessMonitor kills it when it reaches the foreground (with the block overlay), and the tile spins until the 20 s timeout → "taking longer than usual". Nothing in `FocusLauncherScreen.kt` warns about it either.
- [code] `LaunchState.Failed.reason` is free text and the UI picks a label by substring (`launchFailureLabel`, `LauncherContent.kt:418–424`) — brittle.
- [code] `JvmLauncherLaunchPlatform.spawn` surfaces raw Java exception text (`:43–52`), e.g. "Cannot run program … CreateProcess error=740 …".

**Direction**
- A pre-flight step before any spawn (≈0.5 s budget, **no process creation**) that fails immediately with a typed reason for: not allowed; blocked by a FocusFlow rule (say which: always-on / schedule / daily limit / focus session / timed block); exe missing or unreadable; Store/MSIX path unsupported; uninstaller/updater-looking exe (P1.4).
- Typed failure codes carried in `LaunchState.Failed`: `BlockedByRule`, `NotFound`, `NeedsElevation`, `StoreAppUnsupported`, `SpawnFailed`, `ExitedEarly`, `NoWindow`, `RaiseFailed`, `Cancelled`, `Internal`. The UI maps code → short label + tooltip detail. Raw OS errors go to the log; the tile gets a friendly sentence.
- Map common Win32 spawn errors to friendly reasons: 2 / 3 not found, 5 access denied, 193 not a valid Win32 application, 740 requires elevation.
- Optional but recommended: if a tile is already known to be blocked at session start, show that on the tile in advance (dimmed + reason) instead of discovering it on the tap.

**Done when:** T10, T20 pass; an app covered by an always-on block rule shows "Blocked by …" within about a second and is never spawned.

---

### P1.3 — Spawn method

**Size:** M  **Files you may change:** `JvmLauncherLaunchPlatform.kt`

**Evidence**
- [code] `ProcessBuilder(exePath)` (`:28–41`) creates a child of FocusFlow. **[verify]** If FocusFlow runs elevated (Settings → relaunch as Administrator), every app opened from the launcher also runs elevated. Several apps misbehave or refuse when elevated (Squirrel-based apps such as Discord, Spotify, browsers whose single-instance forwarding targets an existing non-elevated copy), and an elevated copy cannot hand off to a non-elevated one → no window.
- If FocusFlow is **not** elevated and the app's manifest requires elevation, `CreateProcess` fails immediately with error 740.
- [code] `cmd /c start "" <exe>` is used when `exePath` is null (`:36`): it does not de-elevate, it returns cmd's pid (which exits at once), and it is a kill target (P0.5).
- [code] The child's stdin pipe (`process.outputStream`) is never closed; the handle lives until GC.

**Direction**
- Resolve the executable before spawning: tile path → App Paths registry → PATH lookup → path of a currently running instance. If none resolves, fail with `NotFound` — never `cmd /c start`.
- When FocusFlow is elevated, start apps **de-elevated** through the user's shell so they run with the normal user token. When it is not elevated, start normally and report `NeedsElevation` clearly if Windows says so.
- Close the child's stdin; do not redirect std streams for GUI apps unless something needs it.
- Keep using the exe's directory as the working directory (as today).

**Done when:** with FocusFlow elevated, an app opened from the launcher is *not* elevated in Task Manager; error-740 apps produce the `NeedsElevation` message in under a second. (Owner-verified, §9 row 5.)

---

### P1.4 — App-picker hygiene

**Size:** M  **Files you may change:** `InstalledAppsScanner.kt`, `FocusLauncherScreen.kt`

**Evidence**
- [code] `scanRegistry` (`InstalledAppsScanner.kt:152–214`) takes the exe from each Uninstall key's `DisplayIcon` (`:174–189`). That value is frequently an uninstaller (`unins000.exe`, `uninstall.exe`), an updater (`Update.exe`), a setup stub or a wrapper — not the program the user thinks they are allowing. Selecting such an entry puts the wrong exe name into the kiosk allow-list and spawns the wrong program. NuclearMode additionally kills `unins000.exe` / `uninstall.exe` / `uninstaller.exe` / `msiexec.exe` in direct-install builds (`NuclearMode.kt:44–49, 73–74`).
- [code] `getRunningApps` (`:84–110`) lists every running exe, including Store apps under `WindowsApps` (cannot be spawned) and per-version Squirrel paths (`…\app-1.0.9xxx\Discord.exe`) that go stale after an auto-update. The session persists `exePath` (`FocusLauncherSessionApp`) and restores it after a crash/restart (`FocusLauncherService.kt:481–483`).
- [code] Nothing validates that a stored path still exists when a session starts.

**Direction**
- Classify candidates and hide or badge them: uninstaller / installer / updater / setup-looking names; `WindowsApps` paths (badge "Store app — not supported"); non-existent paths; system utilities. Prefer resolving the real program for an entry (main exe in the same install dir, Start Menu shortcut target, App Paths) over trusting `DisplayIcon` blindly.
- Re-resolve every selected app's exe path at `enter()` time and when restoring a persisted session; apps that no longer resolve are dropped with a notice instead of becoming dead tiles.
- Show the resolved path in the picker tooltip so the user can see what will be launched.

**Done when:** archetypes F and H list entries are badged/hidden as specified; a stale persisted path is repaired or dropped on restore.

---

### P1.5 — One shared process snapshot for all sweeps (CPU contention)

**Size:** M  **Depends on:** P0.4  **Files you may change:** `ProcessMonitor.kt` (`launcherSweep`, `backgroundSweep`, the UWP resolvers), `NuclearMode.kt` (`getRunningEscapeProcesses*`, `getEscapeProcessesByPath`), `ProcessSnapshot.kt`, `WinEventHook.kt` (name resolution)

**Evidence**
- [code] While a kiosk session is active all of these run concurrently:
  - `launcherSweep` once per poll tick: `ProcessHandle.allProcesses()` and **two** `info()` calls per process (`ProcessMonitor.kt:565, 574`);
  - NuclearMode every ~500 ms: spawns `tasklist /FO CSV /NH` (`NuclearMode.kt:144–171`) **and** a second full `allProcesses()` + two `info()` per process for the path check (`:193–213`);
  - the coordinator's `findInstances` at tap time and every second while waiting;
  - `backgroundSweep` whenever any block rule is active (`ProcessMonitor.kt:503–543`);
  - `WinEventHook` resolves the foreground exe twice per event (`WinEventHook.kt:146` and via the resolver `:174–177`).
- [code] `ProcessSnapshot`'s cache does not save the native query: `process.info()` (`ProcessSnapshot.kt:42`) is evaluated **before** the cache lookup (`:45–47`), so only a trivial getter is cached.
- **[verify]** Net effect: sustained CPU / antivirus-hook load at exactly the moment an app starts → slower cold starts → more timeouts. Worst on low-end machines.

**Direction**
- Replace the per-process `info()` loops with the shared snapshot from P0.4 (one cheap OS snapshot per tick, short TTL so concurrent callers share it, lazy limited-rights path lookup cached per pid + creation time).
- Make NuclearMode's name-based detection use the same snapshot instead of spawning `tasklist` every tick (keep its path-based detection semantics).
- Detection latency must stay the same or get better; the goal is cost per tick.

**Done when:** a sweep tick costs a few milliseconds on a typical 300–400-process machine (log duration at INFO when > 50 ms; today's `launcherSweep durationMs` line only appears above 150 ms); no `tasklist.exe` spawn per tick; the owner sees app start times with kiosk on within noise of kiosk off.

---

### P1.6 — Launcher UI: honest progress, cancel, labels

**Size:** S–M  **Depends on:** P0.3 (typed states)  **Files you may change:** `LauncherContent.kt`

**Evidence**
- [code] The top bar says "Opening X… this can take a few seconds" after 800 ms (`LauncherContent.kt:242–248, 281–290`) although the current worst case is 20 s+ per attempt.
- [code] The tile shows only a spinner (`:353–358`) and is disabled while launching (`:339`) — there is no way to cancel.
- [code] The failure label "Taking longer than usual — tap to check again" (`:421–422`) implies a different action than a normal tap, which is exactly the hidden wait-only retry from P0.2.

**Direction**
- After ≈10 s show progress with elapsed time ("Still opening X… 12 s") and a **Cancel** affordance on the tile (cancels the attempt; does not kill an already-started app).
- Typed failure labels (P1.2): short and specific, detail in the tooltip. Retryable failures say "Tap to retry"; non-retryable ones explain why.
- Keep the 6 s failure display, but a new tap during the display starts a fresh attempt immediately.
- Do not show the "a few seconds" copy for the whole duration.

**Done when:** §9 row 16 passes, including the Cancel check in row 13.

---

## 6. P2 — hardening and polish

### P2.1 — Setup screen: loading can hang forever

**Size:** S  **Files you may change:** `FocusLauncherScreen.kt`

- [code] `FocusLauncherScreen.kt:119–151`: one `LaunchedEffect(Unit)` does the registry scan, the running-process scan and three DB reads, and sets `isLoading = false` only on its last line (`:150`). Any exception (database unavailable, registry or `ProcessHandle` failure) kills the coroutine and leaves the spinner on forever; there is no timeout or error state.
- **Direction:** always clear the loading flag (`try/finally`); load pieces independently (the app list must not depend on presets or notices); show an inline error with Retry if the app scan fails; log the failure.
- **Done when:** forcing an exception in any single loader still shows the screen (error or empty state) — T19.

### P2.2 — Overlay flip and activation side effects

**Size:** S  **Files you may change:** `FocusLauncherService.kt` (`onForegroundChanged`), `LauncherWindow.kt`

- [code] `FocusLauncherService.onForegroundChanged` (`:137–141`) shows the overlay for any foreground exe not in the *allowed* set — it ignores the safe list. Any system/helper window that takes the foreground (shell hosts, IME/emoji panels, FocusFlow's own windows) flips the overlay on top of the allowed app.
- [code] `LauncherWindow.kt:109–118` re-asserts topmost with `SWP_SHOWWINDOW` and **without** `SWP_NOACTIVATE`, so every flip also *activates* the launcher and takes the foreground from the app.
- [code] `onRaised` (coordinator `:78`) decides the overlay state from the tile's `app.processName`, not from the actual foreground process.
- **Direction:** use the shared kiosk decision from P0.5 so safe/transient system windows do not re-cover an allowed app; make re-topmost non-activating unless the launcher should truly take focus (e.g. the allowed app was closed); base the post-raise overlay state on the real foreground process.
- **Done when:** after opening an allowed app, a toast / IME panel / dialog does not cover it; closing the app returns to the launcher.

### P2.3 — Comment and cache debt

**Size:** S

- [code] `ProcessMonitor.kt:456–461` says the launcher sweep runs every 250 ms. It runs once per poll tick (2 s with the hook, 750 ms without) because `tickPoll` is the only caller (`:426–443, 484–491`). Fix the comment — or, once P1.5 makes sweeps cheap, set the cadence deliberately.
- `ProcessCommandCache` (see P1.5): make it effective or remove it as part of P0.4 / P1.5.
- `EnforcementLog.appendLine` rotates by `readText` / `writeText` of the whole file, unsynchronised across threads. Fine at current volume; the P0.1 log reduction makes it moot.

---

## 7. Target launch behaviour (the contract the tasks add up to)

All numbers are defaults — keep them as named constants in one place (Appendix A).

### 7.1 Decision at tap time

| Situation at tap | Action |
|---|---|
| App not allowed, or already `Launching` | Ignore (as today) |
| Pre-flight fails (blocked by rule / exe missing / Store app / elevation known) | `Failed(typed)` immediately — **no spawn** |
| A live instance has a visible, matchable window | Raise it (within the raise budget) → `Idle` |
| A live launch chain exists, started within the attach window, no window yet | **Attach**: wait on it; never spawn again |
| Live instance(s) but no window and older than the attach window (e.g. tray app) | Spawn once (single-instance apps forward the activation), then wait |
| The chain previously hit the hard cap (`NoWindow`) | Treat as stale → spawn |
| No live instance | Spawn |

### 7.2 Phases and budgets

| Phase | Default budget | Outcome |
|---|---|---|
| Pre-flight | ≤ 0.5 s, no process creation | `Ok` or typed `Failed` |
| Reuse (raise existing) | ≤ 2.5 s | `Idle` or `RaiseFailed` |
| Spawn call | ≤ 5 s | `SpawnFailed` on error or timeout |
| Await window | soft notice at 10 s; hard cap 60 s; "chain gone" grace 3 s | `Opened` / `ExitedEarly` / `NoWindow` |
| Raise | ≤ 2.5 s for all tiers together | `Idle` or `RaiseFailed` |
| Watchdog | hard cap + 5 s | forces a terminal state |
| Attach window | 90 s from chain start | — |
| Spawn debounce | ≥ 2 s between spawns of one app | prevents double-spawn only; never causes a wait |

### 7.3 Invariants

1. Every attempt reaches a terminal state (`Idle` or `Failed`) within hard cap + watchdog margin, whatever the native calls do.
2. No flag, timestamp or counter from one attempt changes the behaviour of a later attempt — only live-process facts do.
3. A stale worker never writes state for a newer attempt (attempt token on every write).
4. A dead chain fails in seconds, not tens of seconds. A live chain keeps waiting (cancellable) instead of failing at 20 s.
5. Kiosk guarantees are unchanged: no allow-list bypass, escape tools still killed, the foreground lock still in force after a raise.
6. Duplicate taps never spawn twice.
7. Break / kill switch / session exit cancel everything in flight and leave no spinner behind.

---

## 8. Unit-test scenarios

Use virtual time and a fake `LaunchPlatform` that models processes (pid, parent, creation time, install root), windows (handle, owner pid, Z-order, title, size) and blocking behaviour. Extend the interface additively; keep the coordinator constructor injectable (`platform`, `scope`, `isAllowed`, `onRaised`).

| ID | Scenario | Expect |
|---|---|---|
| T1 | Happy path: spawn; window at 3 s; raise succeeds | `Opened` → `Idle`; nothing left behind that influences the next tap |
| T2 | Dead on arrival: spawn pid exits at 1 s; nothing else alive; no window | `ExitedEarly` within the grace (≈3–4 s after the tap), **not** 20 s; the *next* tap spawns immediately |
| T3 | Stub hand-off: spawn pid exits at 1 s; a process under the same install root appears at 2 s; its window at 6 s | `Opened`; the hand-off pid joined the chain |
| T4 | Slow app: chain alive, window at 35 s | `Opened` on the **first** tap; soft notice at 10 s; no `Failed` in between |
| T5 | Stale-flag regression: attempt hits the hard cap (`NoWindow`); 10 virtual minutes pass; tap | Spawns — no dead wait |
| T6 | Quick re-open: app opens, is closed at +5 s, tap at +8 s | Spawns immediately — no dead wait |
| T7 | Hung native call: `findInstances` (variant: `raise`) blocks forever | Watchdog ends the attempt at hard cap + margin; releasing the blocked call later does not change the state of a newer attempt for the same app |
| T8 | Raise by process: foreground window is a *different* HWND of the chain → success; foreground belongs to another process → failure after the raise budget | As stated |
| T9 | All raise tiers fail | `RaiseFailed` within the raise budget; retryable; next tap re-evaluates windows |
| T10 | App blocked by a FocusFlow rule | Immediate `BlockedByRule` naming the rule; the fake records **no** spawn call |
| T11 | Tray app: old windowless instance (older than the attach window) | Exactly one spawn; window appears → `Opened` |
| T12 | `cancelAll()` and per-app `cancel(app)` during each phase | No state write after cancel; job maps empty; no leaked watchdog |
| T13 | Kiosk policy: chain member kept; unrelated unknown exe killed; escape tool inside a chain killed; renamed escape tool (path match) killed | As stated |
| T14 | FocusFlow-owned helper (child of FocusFlow's pid, under `%SystemRoot%`, not a chain root) is never killed; the same exe started by another parent is handled as today | As stated |
| T15 | Two taps in the same frame | One spawn |
| T16 | PID reuse: a tracked pid is reused by an unrelated process (different creation time) | Not treated as part of the chain |
| T17 | Unreadable process: spawn pid stays tracked although name lookup cannot read it | Window found by pid → `Opened` |
| T18 | Unresolvable lineage on first sighting | No kill on the first tick; decided on the next snapshot |
| T19 | Setup-screen loader: one loader throws | Screen still shows (error / empty state); loading flag cleared |
| T20 | Spawn errors 2, 3, 5, 193, 740 | Typed codes (`NotFound` / `SpawnFailed` / `NeedsElevation`) with friendly text; raw error only in the log |
| T21 | Window choice: splash then main; several windows of one app | Main window chosen once it exists; top-of-Z-order window chosen among several |
| T22 | Logging | Every terminal state emits exactly one summary line carrying the attempt id |

---

## 9. Manual Windows verification sheet (owner runs this after the agent's work)

Run with the kiosk on, one allowed app at a time unless stated. Every launch should leave one summary line in `%USERPROFILE%\.focusflow\enforcement.log` (P0.1). *Fresh* = a newly started session; *re-engaged* = a session after a break or the emergency break ended.

**App archetypes used below:** **A** single-process (Notepad) · **B** same-name multi-process (Chrome / Edge, Discord, Spotify, VS Code) · **C** stub / updater launchers (Squirrel-style: Discord, Slack, GitHub Desktop) · **D** multi-exe launchers (Steam, Epic Games Launcher, Battle.net, Minecraft Launcher) · **E** elevation cases · **F** Store / UWP (Calculator, Photos) · **G** tray apps · **H** apps blocked by a FocusFlow rule.

| # | Scenario | Steps | Expected |
|---|---|---|---|
| 1 | **A** repeated | Allow Notepad only. Tap, close the app, tap again within 5 s; repeat 5× | Opens on every first tap in < 3 s; no failure label; no stall when re-tapping within 20 s of closing |
| 2 | **B** same-name multi-process | Cold start; then with the app already open; then minimized | Opens on the first tap; an open window is raised (no new instance); minimized is restored |
| 3 | **C** stub / updater launchers | Cold start each | Opens on the first tap; log shows the hand-off and no kill of chain members |
| 4 | **D** multi-exe launchers | Cold start each, first tap | Opens on the first tap; helper processes are not killed; tile never reports failure while the app is still starting |
| 5 | **E** elevation matrix | FocusFlow elevated vs. not × a normal app vs. an app that requires administrator | FocusFlow elevated → normal apps are **not** elevated (Task Manager → Details → *Elevated*); requires-admin app with non-elevated FocusFlow → "needs administrator" message in < 1 s; an already-elevated app still raises |
| 6 | **F** Store / UWP | Tap Calculator / Photos | Opens, **or** an immediate clear "unsupported" message; never a long spinner; no silent dead tile |
| 7 | **G** tray app | Close Discord / Spotify to the tray (window hidden, process alive); tap the tile | Window comes up on the first tap |
| 8 | **H** blocked app | Put the app in an always-on rule (or exhaust its daily allowance, or schedule-block it) and allow it in the launcher; tap | "Blocked by …" within ~1 s; the app is never spawned or flashed |
| 9 | Rapid taps | Double-click / five fast clicks on one tile | Exactly one process started |
| 10 | Interruptions | Start a break / the emergency break / end the session while a slow app is *Opening* | Spinner ends at once; no orphan state; re-tap works afterwards |
| 11 | Fresh vs. re-engaged | Repeat rows 1–2 after a break ends and after the emergency break ends | Identical behaviour to a fresh session (foreground-lock handling) |
| 12 | Escape tools still blocked | With an allowed app open try Task Manager, cmd, PowerShell and a renamed copy of `cmd.exe` | All killed; the PIN exit still works |
| 13 | Slow / dead launches | (a) first launch of a large app after boot; (b) delete or rename the app's exe after the session started; (c) kill the app's process right after it starts | (a) spinner continues without failing while the process lives, **Cancel** works; (b) immediate "not found"; (c) fails within seconds and the next tap spawns at once |
| 14 | Multi-monitor | Two monitors | Launcher on the primary, lock screen on the other; the raised app appears on the primary and is not covered |
| 15 | Performance | Watch Task Manager CPU with kiosk idle and during launches, before vs. after | No sustained load from sweeps; no `tasklist.exe` appearing every half second |
| 16 | UI | Tap a slow app | After ≈10 s: "Still opening… N s" with a Cancel control; Cancel ends the attempt without killing the app; failure tooltips show a specific reason |

---

## 10. Out-of-scope observations (do not fix here — confirm and list in your report)

1. `launcherSafeProcesses` contains `java.exe` / `javaw.exe` (`ProcessMonitor.kt:142`) to protect FocusFlow, but that exempts **every** Java program (Minecraft, IDEs) from the sweep and the foreground check. FocusFlow should be identified by its own pid / path, not by a runtime image name.
2. NuclearMode kills `powershell.exe` / `cmd.exe` whoever started them — including the PowerShell calls FocusFlow's own `NetworkBlocker` makes for firewall rules during a session (P0.5's ownership exemption should cover this; confirm firewall changes during kiosk are reliable afterwards).
3. `AppIconExtractor` reaches into `sun.awt.shell.ShellFolder` by reflection (`AppIconExtractor.kt:58–64`). On newer JDKs that package is not exported and the call silently falls back to 16×16 icons (blurry tiles). Cosmetic — verify on the shipped runtime.
4. `getRunningApps()` offers every running exe as a launcher candidate, including background utilities (partly addressed by P1.4's classification).

---

## 11. Final report format (what you return)

For each task: **status** (Done / Partial / Skipped + why), **files changed**, **tests added** (names), any **deviation from "Direction"** with the reason, and **open [verify] items** for the owner. Also include: the final constants table (Appendix A), the §0.5 kiosk-safety paths you re-read after your changes, which **§9 rows** are now due for the owner, and your list of out-of-scope observations.

---

## Appendix A — Constants (today → proposed)

| Constant | Today | Proposed |
|---|---|---|
| `WINDOW_WAIT_MS` | 20 000 | Replaced by soft notice 10 000 + hard cap 60 000 |
| `SPAWN_BUDGET_MS` | 20 000 | Removed as a decision input; spawn debounce ≥ 2 000 |
| `WINDOW_POLL_MS` | 150 | Keep |
| `PROCESS_REFRESH_MS` | 1 000 | ≈ 500, served from the shared snapshot |
| `FAILURE_DISPLAY_MS` | 6 000 | Keep |
| `RAISE_RETRY_DELAY_MS` / `MAX_RAISE_ATTEMPTS` | 150 / 3 | Replaced by one raise budget (≈ 2 500 total) |
| `FOREGROUND_VERIFY_DELAY_MS` | 300 | 200–300 confirmation after a process-based success |
| `LAUNCHER_SWEEP_INTERVAL_MS` | 250 (comment) — actual cadence 2 000 / 750 | Document the real cadence or set it deliberately (P2.3) |
| chain-gone grace | — | 3 000 |
| attach window | — | 90 000 |
| startup grace (Decision D1-A only) | — | 45 000 |
| watchdog margin | — | 5 000 |

## Appendix B — Evidence to collect if problems persist after the fix

- The `[Launcher]` summary line(s) for the failed attempt id(s) from `%USERPROFILE%\.focusflow\enforcement.log`, plus ~20 lines either side.
- App name, install location, and whether it is a Store app.
- Whether FocusFlow was running elevated (Settings shows it).
- Fresh session or after a break / the emergency break.
- First launch after boot, or a repeat.
- Whether the app was already running (tray / minimized) before the tap.

## Appendix C — File map (launcher-related)

| File | Lines | Role |
|---|---|---|
| `services/LauncherLaunchCoordinator.kt` | 377 | Launch state machine (per-tile `Idle` / `Launching` / `Failed`) |
| `services/JvmLauncherLaunchPlatform.kt` | 65 | Windows platform operations (find / spawn / raise) |
| `services/ProcessSnapshot.kt` | 66 | Name/path-based instance lookup via `ProcessHandle` |
| `services/FocusLauncherService.kt` | 666 | Session lifecycle, allow-list, overlay flag, break / kill-switch hooks |
| `enforcement/WindowRaiser.kt` | 242 | Window enumeration and activation tiers |
| `enforcement/ProcessMonitor.kt` | 872 | Kiosk sweep / foreground kills, `launcherSafeProcesses` |
| `enforcement/WinEventHook.kt` | 255 | Foreground hook, focus reclamation |
| `enforcement/GlobalKeyboardHook.kt` | 270 | Keyboard suppression + `LockSetForegroundWindow` |
| `enforcement/NuclearMode.kt` | 509 | Escape-tool kills (enabled with every kiosk session) |
| `enforcement/InstalledAppsScanner.kt` | 224 | Tile candidates (registry `DisplayIcon`, running processes) |
| `enforcement/ProcessNameResolver.kt` | 132 | Robust exe-name resolution (not yet used by the launcher) |
| `enforcement/AppIconExtractor.kt` | 99 | Tile icons |
| `ui/launcher/LauncherContent.kt` | 575 | Tile grid, top bar, tile states, labels |
| `ui/launcher/LauncherWindow.kt` | 137 | Per-monitor fullscreen windows, topmost handling |
| `ui/screens/FocusLauncherScreen.kt` | 1186 | Setup screen (app picker, PIN, session start) |
| `ui/screens/ChangelogScreen.kt` | — | `CHANGELOG` list (add to 2.0.6 "PLANNED") |
