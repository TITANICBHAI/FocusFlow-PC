# FocusFlow enforcement reliability: investigation, decisions, changes

Evidence labels: **[Unit]** unit-tested and run in a standalone harness; **[Runtime-JVM]** run against real child processes on Linux; **[Static]** read in code only; **[Unverified]** not run. Nothing here was run on Windows, with PowerShell, or through Gradle (see section 6).

## 1. What was discovered
Three separate enforcement paths treat processes differently:
- **Nuclear Mode** (`NuclearMode.enforceTick`, every 500 ms): matches an escape list by name and known system path. Before this change it killed by image name (`taskkill /F /IM`) unless a Focus Launcher chain-allowance exemption was active.
- **Focus Launcher** (`LauncherKioskPolicy.decide`): allow/safe lists; FocusFlow's own helpers were not recognised unless a user opted into chain allowance.
- **Ordinary blocking** (`ProcessMonitor`): kills by PID, but only processes whose name is in the person's blocked sets. It does not use the escape list. The changelog line "CMD, PowerShell... killed during any enforcement" does not match the current code **[Static]**.

FocusFlow itself starts PowerShell for (a) every firewall operation (`NetworkBlocker.runPs`, bare `powershell`) and (b) the Watchdog / Taskbar Guard scheduled tasks (generated `.ps1`, started by Task Scheduler, usually via `conhost.exe --headless`, as the interactive user at LeastPrivilege). Task Scheduler starts (b), so no own-PID or parent-PID exemption can cover it.

## 2. What was actually wrong
Confirmed from code **[Static]** (Windows runtime effect not observed):
1. **Name-wide kill of `powershell.exe`** ended FocusFlow's own firewall PowerShell, and any Watchdog task run, as well as the person's. Nuclear's own firewall lock runs PowerShell, so Nuclear could kill the process that applies its lock.
2. **Session end called `removeAllRules()`**, removing Nuclear Mode's rules and the person's own rules, not just session cutoffs.
3. **Firewall state race**: `removeRules` cleared state, then an apply already in flight re-added it, so state said "blocked" with no rule (or the reverse).
4. **Path spoofing**: a rule's program path came from any running process with the same image name, so a copy of `cmd.exe` in a temp folder could receive the rule.
5. **No timeouts** on `schtasks` (`readText()` before `waitFor()`), `schtasks /query`, `nslookup`, `ipconfig`: a hang blocked its caller indefinitely.
6. **Ordinary background sweep** killed by PID any process named in a blocked set with no trust check, so blocking `powershell.exe` as an app also killed FocusFlow's helper.
7. **One PowerShell per queued program** from `requestRule`; no shutdown handling for queued or in-flight firewall work.
8. **A rule is a path, but was verified by name.** Read-back confirmed "a rule with this name is enabled, outbound, block" and never what program it pointed at. The resolved path was also cached forever, so an app that updates into a new folder kept a rule for its old file while the new file ran unblocked, and the app still counted as blocked **[Static]**. A rule left over from an earlier run was adopted without looking at its path.

Probable, not measured: a cold PowerShell start with a many-rule script takes seconds, longer than the 500 ms tick, so the kill would regularly land mid-run.
Not found: an own-PID or parent-PID exemption that was broken; the Launcher exemption existed but only for the chain-allowance case.

## 3. Architecture assessment
- Identity beats names. Name, parent PID, folder and "is PowerShell" can all be copied by a person; (PID, creation time, exact image) recorded at spawn, and for scheduled runs (exact image, exact arguments, trusted parent, script SHA-256) cannot be forged without editing FocusFlow's own files.
- Killing by PID after `isAlive(pid, creationTime)` removes both the self-kill and PID-reuse risks.
- The firewall needed one owner of state with a replaceable backend; batching was kept (it is cheap) but coalesced behind one worker.
- Too big to do blind: a native COM firewall backend (see `native-firewall-backend.md`). The seam for it now exists.

## 4. What was decided
Not done, on purpose: no broad exemption of PowerShell, children, a folder or a parent. Fail closed on any unknown image, command line or parent. Rules have owners; session end releases only session claims. Absolute System32 paths for every tool FocusFlow spawns. All hard-to-test logic extracted behind seams so it runs without Windows.

## 5. What changed
New (main, package `com.focusflow.enforcement`): `OwnedProcessRegistry`, `OwnedProcesses`, `TaskRunTrust`, `EnforcementTrust`, `EscapeTargetSelector`, `EscapeEnforcement`, `Taskkill`, `SystemTools`, `SystemCommand`, `FirewallRuleManager`, `PowerShellFirewallBackend`, `ExePathResolver`, `ResolvedPathCache`.
Modified: `NuclearMode` (PID-based plan/kill, trust, owner NUCLEAR, removed name-wide fallback and `getRunningEscapeProcesses`/`getEscapeProcessesByPath`), `ProcessMonitor` (trust at 3 launcher sites and the background sweep), `LauncherKioskPolicy` (`isTrustedHelper` hook), `NetworkBlocker` (facade over the manager; new optional owner, `removeSessionRules()`, `shutdown()`), `WinApiBindings` (`killProcessesByPid`, taskkill via absolute path as owned child), `WatchdogScript` (script-name constants, `taskbarGuardScript()`, `expectedTaskRuns`, shared argument builders), `WatchdogInstaller` (timeouts, owned `schtasks`), `WindowsStartupManager` (owned `reg.exe`), `HostsBlocker` (owned, time-limited `nslookup`/`ipconfig`), `FocusSessionService` (`removeSessionRules()`), `Main` (`NetworkBlocker.shutdown()`).
Tests new: `OwnedProcessRegistryTest`, `TaskRunTrustTest`, `EscapeTargetSelectorTest`, `EscapeEnforcementTest`, `OwnedProcessLauncherTest`, `SystemToolsTest`, `EnforcementTrustTest`, `TaskkillTest`, `FirewallRuleManagerTest`, `ExePathResolverTest`, `PowerShellFirewallBackendTest`, `FirewallPathVerificationTest`, `SystemCommandTest`, `LauncherKioskTrustedHelperTest`; extended `WatchdogScriptTest`; rewrote `NuclearModeSnapshotTest`.
Firewall rule correctness (item 8): read-back now also checks the rule's program path (`Get-NetFirewallRule | Get-NetFirewallApplicationFilter`, only for the batch just applied, because the lookup is slow per rule). A rule pointing elsewhere is not confirmed. The resolved path is cached for 10 minutes and dropped at once if the file is gone. Every 10 minutes a blocked program is looked up again; if it moved, its rule is applied again. The recheck is *triggered* by use (a timestamp comparison in `request`) and *performed* on the background worker, so the enforcement path stays cheap. Rules found in the firewall at startup are adopted with their real path and checked on first use. If a path cannot be read (PowerShell failure, or the application filter is missing) the rule is accepted by name and a warning is logged, rather than leaving the app unblocked because the check itself was unavailable. Duplicate output readers removed: `PowerShellRunner` now delegates to `SystemCommand` and keeps only one-at-a-time and `cancel()`.
Not done, by the owner's decision: the name-wide kill fallback when a PID is unknown (risk 5).

Docs: this file, `native-firewall-backend.md`, `.agents/memory/enforcement-helper-trust.md`, `MEMORY.md` (dangling link fixed).
Removed: no files. `NetworkBlocker.removeAllRules()` now has no callers (kept for exit/uninstall).

## 6. What was verified
- `kotlinc` 2.2.21 + JUnit 4 harness over the pure sources and tests: **170 tests, 0 failures** (re-run fresh at the end on the latest codebase: 162 for the enforcement work plus the 8 `WindowsSettingsLauncherTest` tests that were already there). Includes real child processes killed on timeout and `cancel()` **[Runtime-JVM]**.
- Mutation checks: removing the image, start-time, parent, hash, trust-skip, own-PID and race/owner logic each fails a test **[Unit]**. For the path verification: making `sameProgramPath` always true, making the recheck never due, not re-applying a moved program, a path cache that never expires, and a cache that ignores a deleted file each fail a test; dropping `cancel()`'s process capture after the runner refactor fails `cancelEndsARunThatIsInProgress` **[Unit]**.
- Path verification logic **[Unit]**: the manager (what is re-applied, when, what happens when the re-apply is not confirmed, adoption of recovered rules, a program that cannot be found during a recheck keeps its working rule), the output parsing, the confirm/mismatch/unverifiable split, the script text, and the cache. The PowerShell itself is **[Unverified]**: the cmdlets and the `.Program` property come from Microsoft's documentation, not from a run.
- Guard script text moved byte-identical (SHA-256) **[Unit]**.
- `NetworkBlocker` compiles against stubs and is a no-op off Windows **[Unit]**.
- **Type-checked, not built:** the whole `src/main/kotlin` tree (178 files) was compiled with `kotlinc` 2.2.21 with no external libraries on the classpath (Compose, JNA and SQLite unresolved, so the compile fails by design). Result: **0 errors and 0 warnings in `NuclearMode`, `ProcessMonitor`, `HostsBlocker`, `FocusSessionService`, `LauncherKioskPolicy`, `NetworkBlocker` and every new file; no error anywhere mentions any new API** (`FirewallOwner`, `killProcessesByPid`, `removeSessionRules`, `SystemCommand`, ...). The only errors in `WinApiBindings`, `WatchdogInstaller` and `WindowsStartupManager` are unresolved JNA types at lines outside my edits; `Main.kt`'s are Compose cascades, none at the `NetworkBlocker.shutdown()` line. Re-run after the path-verification change: the same compile of the codebase as received and of the codebase with this change produce an identical set of diagnostics (2,312 distinct messages, same counts), and none is in a file this change touches. Caveat: calls into unresolved library types are checked leniently, so this is strong evidence, not a full build **[Static, compiler-checked]**.
- **Not run:** `gradle build`, the root `:test` suite, the `recovery` module, CI parity (all Gradle/Maven hosts returned 403 from the sandbox, re-checked at the end). `NuclearModeSnapshotTest` (needs the real `NuclearMode` and its dependencies) was not run. **All Windows behaviour is [Unverified].**

## 7. Performance impact
Not measured. By construction **[Static]**: one `taskkill` per sweep per 20 targets (was one per sweep); `requestRule` never blocks the enforcement path and queued programs collapse into batched PowerShell runs (was one run per request); command-line reads happen once per process per sweep and only for PowerShell candidates.

## 8. Remaining risks (by severity)
1. **All Windows behaviour is unverified.** Highest-value checks: image paths reported by Windows match `%SystemRoot%\System32\...` exactly (case, 8.3 names); `ProcessHandle.info().commandLine()` is readable for the Watchdog's PowerShell (fail-closed means it would be killed if not); `conhost.exe --headless` parentage on Win10 1809+/Win11.
2. No full Gradle build: the changed files type-check cleanly against the real tree (section 6) but resolve Compose/JNA types leniently, and the root test suite was not run.
3. Behaviour change to confirm with the owner: ending a session no longer removes USER-owned firewall rules (App Blocker / VPN screen). Previously they were silently deleted at every session end while the UI still showed them on.
4. Path verification is bounded and unverified on Windows: a moved program is noticed within the 10-minute recheck, not instantly, so an updated app can run unblocked for up to that long; on a Squirrel-style updater the old versioned folder's rule is replaced, not kept. Whether `Get-NetFirewallApplicationFilter` returns the same path string in the same form (case, 8.3 names) that `New-NetFirewallRule -Program` stored is not confirmed; a mismatch of form would cause a harmless re-apply every recheck, not a missed block. An app that installs per-version folders and runs from several at once is blocked only for the folder that was resolved.
5. `ProcessMonitor` name-wide fallback (`killProcessByName`) remains when a PID is unknown (rare). Left unchanged by the owner's decision; sites: `ProcessMonitor` (three), `DailyAllowanceTracker.RealProcessKiller`.
6. `UninstallWizard` launches `cmd.exe`, an escape tool under Nuclear Mode. Left as is (likely intended).
   Also observed, not changed: `WindowsSettingsLauncher` starts `rundll32.exe` with a plain `ProcessBuilder` instead of `OwnedProcesses`. That is harmless today, because `rundll32.exe` is not an escape tool and is in the installed-apps scanner's system-ignore list, so it cannot be offered as a blocked app. If it is ever added to a block list, route it through `OwnedProcesses.start`.
7. A native COM firewall backend would remove PowerShell from the firewall path entirely (documented, not built).
8. A stray `.gradle/` directory from the failed Gradle attempt exists in the working tree; it is excluded from the patch and zip.
