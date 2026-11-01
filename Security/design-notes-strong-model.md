> # SUPERSEDED IN PLACES — read `plan.md` first
> This is the earlier, analysis-style draft written for a strong model. It is kept only for the reasoning behind the findings.
> Known problems with this file (do NOT rely on it for implementation):
> - **Nuclear escape-list task (old P0.7 / H6):** adding `taskkill`/`tskill`/`schtasks`/`reg` to the polling list is **not** an effective fix. The sweep runs every 500 ms; one-shot CLI tools often exit before the next sweep (inference, not measured; W-7 in `plan.md` measures it), and the sweep could kill FocusFlow's own helper children. The task was dropped from the weak-agent pack (see N3).
> - **"T1 Closed (P0)" claims are overstated.** After the cards in `plan.md`, only the in-app routes are closed (restore / backup management / Forgot-PIN / Store quit). Explorer-level DB replacement, killing the app, one-shot tools, clock changes are NOT closed (see `plan.md` §3 and §7).
> - **Line references** are partly unverified; re-grep before relying on any.
> - **Appendix A** ("what was read") is unreliable; see `plan.md` §11 for the honest version.
> - The merge-on-restore design here predates the verified implementation (ATTACH cannot run inside a transaction, so the merge runs after `Database.close()` on a temp copy of the safety backup). Use `plan.md` card B1.

---

# FocusFlow PC — Anti-Bypass Hardening Plan

**For:** the implementing agent (assume no prior context — this document is self-contained)
**Product:** FocusFlow for Windows (Kotlin 1.9.22, Compose Desktop 1.6.1, JVM 19 + JNA 5.14, SQLite via sqlite-jdbc), package `com.focusflow`
**Goal:** close the ways the *owner of the PC* can lift FocusFlow's restrictions in a weak moment ("cheating"), without ever locking anyone out of their own computer.
**Basis:** static read of `FocusFlow-PC-main.zip` (app version 2.0.6 per `gradle.properties:6`; the 2.0.6 changelog entry is still marked PLANNED, `ChangelogScreen.kt:34-37`). Appendix A lists what was read and what was not. The `file:line` references were re-checked against the code in a final pass on 2026-10-31 (those written with `~` are approximate). **Nothing was run** — no Windows, no tests.

**Evidence tags**
- **[code]** — verified by reading the source; `file:line` given. Lines are from this snapshot: re-grep before relying on them.
- **[verify]** — Windows runtime behaviour that could not be executed. Not a fact until the owner confirms it with the sheet in §9.
- **[owner-doc]** — stated in the repo's own docs (`fixes/plan.md`, `fixes/tracker.md`, `.agents/memory/*`, `ChangelogScreen.kt`).

---

## 0. Read this first

1. FocusFlow is a **friction layer, not a security product.** Its protection state lives in files the user can write. This plan raises the cost of bypassing from "seconds, no tools" to "needs a program and knowledge", adds tamper detection, and closes every low-skill route. It does **not** stop an administrator, Safe Mode, an offline edit, or a determined programmer (§2, tier T4). Never write UI, Store or docs text that claims otherwise (owner's own D12 wording note: say "friction", not "secure").
2. Tasks are either **open** (implement now) or **[GATED]** (they reopen an owner decision that is recorded as settled). A gated task needs the owner's written answer in the tracker under its `O-n` id *before any code is written*. Do not infer approval.
3. Order: Phase P0 → P1 → P2 (§6). Inside a phase follow the dependency notes. Recommended path: **P0.1 → P0.3 → P0.7 → P0.6 → P1.3 → P1.4 → (gated tasks as answers arrive) → P1.6 → P2**.
4. **Never lock the user out** (R6). Every new guard must have a way out that exists today (PIN, time, uninstall) and must fail back to the previous behaviour on an internal error.
5. Anything you notice outside the listed tasks goes in your final report under "Out-of-scope observations". Do not fix it.

---

## 1. Ground rules

| # | Rule |
|---|---|
| R1 | **Work alone.** Do not create, invoke or delegate to sub-agents. |
| R2 | **Scope.** Only the tasks in §6. `fixes/plan.md` §11 ("enforcement semantics out of scope") belonged to the database-reliability project; for *this* plan it is lifted **only** for the files each task lists. |
| R3 | **User data is untouchable.** Never read, write, move, delete or "reset" the real `%USERPROFILE%\.focusflow\*` (database, `-wal`, `-shm`, `backups\`, `*.snapshot`, logs). Tests use `@TempDir` and redirect `user.home` (see `src/test/.../testutil/TempHome.kt`, `ForkedJvm*.kt`). Code that repairs or reseeds data must take a verified backup first. |
| R4 | **Gated tasks wait for the owner** (R-gate). Settled decisions you must not reopen on your own: D4 (uninstall with unreadable DB), D9 (backup/recovery design), D10 (DB-down behaviour = "Blocking is OFF"), D11 (mid-run DB loss), D12 (**no PIN hashing/recovery redesign**), MSIX removal is OS-owned, `SelectorProvider` flag stays. See §5. Read `fixes/D9-backup-recovery-design.md` before P0.3/P0.4, and `fixes/F19-mid-run-db-loss-design.md` plus `D11-MID-RUN-DATABASE-LOSS.md` before P1.1/P1.5. |
| R5 | **You cannot run Windows.** Keep every Win32/JNA/registry/scheduled-task call behind `isWindows` and an injectable seam (like `ProcessSnapshot`, `LaunchPlatform`, allowance `Clock`). Unit-test against fakes (these run on Linux). Windows behaviour is verified by the owner with §9 — never claim it is verified. |
| R6 | **No lock-outs.** After every task all of these still hold, and where feasible a test proves it: (1) the PIN holder can loosen or end every protection through the UI; (2) sessions and standalone blocks still end by time; (3) crash/shutdown still restores OS state — windows/taskbar, registry lockdown, Nuclear Mode off (`CrashReporter.safetyCleanup`, `CrashReporter.kt:561-575`; `FocusLauncherService.emergencyRestoreWindows`, `FocusLauncherService.kt:753`; the `FocusFlowTaskbarGuard` logon task); (4) an *internal error* in new code degrades to the previous behaviour (log + continue), never to a lock; a *detected tamper* acts; (5) critical Windows processes are never killed — if a path cannot be resolved (access denied), keep today's name-based behaviour and re-check next tick. |
| R7 | **Honest wording.** Functional/security copy says "friction". No new claim of absolute protection anywhere (UI, changelog, docs, Store text). |
| R8 | **Verify before you change.** Task 0 of every task: re-grep its `[code]` evidence. If a claim no longer holds, record that in the tracker and adjust or skip the task. |
| R9 | **Threading.** UI code never calls `Database.*` directly or enumerates processes — use `withContext(Dispatchers.IO)` (`.agents/memory/ui-db-freeze-patterns.md`). `NuclearMode.disable()` must never use `runBlocking` (background thread + `awaitCleanup()`). |
| R10 | **Privacy.** No new telemetry, no new network calls. Never log or print PIN hashes, snapshot contents, or user paths unredacted. Do not expose or copy secrets (the Discord webhook strings are an owner action, D1). |
| R11 | **Tests & evidence.** `gradlew` cannot launch (the wrapper jar is absent, `AUDIT-ADDENDUM.md`). Use system Gradle: `gradle :test --no-daemon`. Completion evidence = the **final** `:test` result plus the XML reports under `build/test-results/test` — partial `testClasses` output is not evidence (`gradle-test-completion.md`). Tests run with `focusflow.telemetry=off`. The test worker may use a different JVM than Gradle: record which. The Phase-0 report recorded 98 tests / 0 failures (`fixes/PHASE-0-REPORT.md`); the repo now has 69 `*Test.kt` files, so that number is stale — run the suite first and record the baseline you actually get. |
| R12 | **Git.** Do not push, and do not start, poll or monitor GitHub Actions, unless the owner asks. |
| R13 | **Tracker.** Create `Security/tracker.md` (same pattern as `Plan/`, `Launcher/`, `fixes/`). Mark a task in progress when you start. Tick a box only when its work is complete **and verified**. Record changed files, the exact verification run, and its result. Keep failed attempts and blockers in the record — add the outcome, never erase. Reconcile the tracker against code and test evidence before finishing; leave incomplete items unchecked and say why. |
| R14 | **Changelog.** Add plain-language `SEC`/`FIX`/`IMP` lines to the 2.0.6 entry (still marked PLANNED, `ChangelogScreen.kt:34-37`; if it has shipped by the time you start, ask the owner before opening a new entry). Follow the file's convention. Describe the benefit, not the bypass. |
| R15 | **Style.** Match the surrounding Kotlin. Comments say *why*. No new dependencies and no version bumps (D6: "no upgrade in this work" for sqlite-jdbc; `jna-platform` 5.14.0 is already a dependency, `build.gradle.kts:24`). The `pinGate*` reset strings in the language packs may stay unused — do not edit every pack for that. Follow the localisation approach of the screen you edit (`LocalizationManager.strings` or hard-coded, whichever that file uses). |

---

## 2. Threat model — the bypass-cost ladder

The attacker is the person who installed FocusFlow, on their own PC, wanting out *right now*. The owner is a developer, so assume tier T3 is available to them.

| Tier | Attacker can | Example routes in this codebase | Target after this plan |
|---|---|---|---|
| **T0** | Use only FocusFlow's own UI | "Forgot PIN" reset; restore an old backup; tray Quit on a Store build; Emergency Break | **Closed** (P0) |
| **T1** | Use built-in Windows UI: Explorer, Win+R, Start search, Settings, Task Scheduler UI, Startup apps | delete the DB file; delete the watchdog/Run entries; run `taskkill`/`schtasks`/`reg` from Win+R in Nuclear Mode; run a renamed copy of `cmd.exe` | **Closed** (P0; DB deletion and renamed copies in P1) |
| **T2** | Type command-line one-liners and use GUI SQLite tools | edit `settings`/`block_rules` while the app runs; change time zone; rename an exe | **Closed or detected** (P0–P1) |
| **T3** | Write a small program or script | hold a lock on `instance.lock`/`focusflow.db`; re-seal a snapshot with DPAPI; spoof a process name | **Raised**: needs code + knowledge, is logged, and is reverted on the next check. Not closed. |
| **T4** | Administrator, Safe Mode, boot another OS, offline file edits, uninstall by an admin, stop/replace the install (it is per-user: `build.gradle.kts:86`) | — | **Out of scope.** Only P2.4 (a design note) touches it. |

Why T3 cannot be fully closed: a process cannot protect itself from the same Windows user. The only structural fix is privilege separation (a LocalSystem service that owns the state, with the PC owner on a standard account). That is a separate project — P2.4 produces a design note only.

---

## 3. Findings register (re-verified in the second pass)

Severity is for a T0–T2 attacker. "Task" says where it is fixed.

| ID | Finding | Tier | Evidence | Task |
|---|---|---|---|---|
| H1 | **"Forgot PIN" clears the Global PIN.** After 2 wrong attempts a link appears; typing `RESET` calls `GlobalPin.resetWithoutPin()`. Reachable from 5 call sites (Vpn/Network, Keyword Blocker, Profile, Focus break-skip, one Settings gate); the blocked-app, schedule and always-on gates pass `allowReset=false` or use a private dialog. The repo's own docs say a forgotten PIN "cannot currently be recovered" — code and docs disagree. | T0 | `PinGateDialog.kt:34` (steps `ENTER_PIN`/`FORGOT_CONFIRM`/`FORGOT_DONE`), `:46` (default `allowReset=true`), `:152`, `:251-265`; `GlobalPin.kt:98` (callers: `PinGateDialog.kt:256` and the Settings **remove-PIN** dialog `SettingsScreen.kt:1838-1841`, which verifies in step 0 and then clears without re-verifying); call sites `VpnNetworkScreen.kt:640`, `KeywordBlockerScreen.kt:673`, `ProfileScreen.kt:591`, `FocusScreen.kt:294`, `SettingsScreen.kt:1250`; false at `AppBlockerScreen.kt:816,1321`, `SettingsScreen.kt:1240`; `ChangelogScreen.kt:98`; `enforcement-pin-ownership.md`. **[verify]** the owner reports it "not usable in practice". | P0.2 **[GATED O1]** |
| H2 | **Restore needs no PIN.** Any older, weaker backup can be staged from the UI and is applied on the next launch. Deleting backups/archives and changing the secondary folder are also ungated. | T0 | `ContactScreen.kt:564` (`stageRestore`), `:585,:605,:624` (deletes), `:519,:683` (folder); `BackupRecoveryService.kt:359,:434` | P0.3, P0.4 **[GATED O3]** |
| H3 | **A missing or unreadable DB becomes a fresh empty DB with no PIN and no rules** (`PinPolicy.verify`: absent row ⇒ accepted; absent `always_on_enforcement` ⇒ off; absent `nuclear_mode` ⇒ off). A deleted file produces a silent fresh DB (the recovery notice only appears when a `.broken_*` archive exists); a corrupt one is quarantined first. | T1 | `Database.kt:199-282,:603`; `PinPolicy.kt:64`; `StartupBootstrap.kt:58`; `NuclearMode.kt:519-531`; `RecoveryNoticeService.kt:33-77` | P1.1 **[GATED O2]** |
| H4 | **Store/MSIX builds: tray Quit is ungated** (`authorizeQuit()` returns true when not a direct install). In the DB-unavailable window `quitGate` is ungated for every build. | T0/T1 | `UninstallProtectionService.kt:156-160`; `Main.kt:247,:301-304`; `windows-uninstall-protection.md` only says package *removal* is Windows-owned and must not be vetoed — it does not mention tray Quit, so the exemption in `authorizeQuit()` looks broader than that note requires (owner to confirm, O4) | P0.5 **[GATED O4]** |
| H5 | **Killing FocusFlow gives a free window; the watchdog is weak.** The watchdog is a per-user scheduled task (every 2 min) that checks `Get-Process -Name 'FocusFlow'` — **by name only**, so a decoy process named `FocusFlow` satisfies it. Task and Run-key entries are removable without admin. Nothing detects "was killed while protection was active". | T1/T2 | `WatchdogInstaller.kt:23-24` (comment: "This is crash recovery only"), `:49-60`, `WindowsStartupManager.kt`; a grep for `SetKernelObjectSecurity|SetSecurityInfo|SetNamedSecurityInfo|SECURITY_ATTRIBUTES|DACL` over `src/main/kotlin` finds nothing (no process or file ACL hardening). **[verify]** deletion without admin. | P0.6 |
| H6 | **Nuclear Mode's escape list misses the tools that end or edit FocusFlow**: `taskkill.exe`, `schtasks.exe`, `reg.exe`, `sc.exe`, `net.exe`, `net1.exe`, `icacls.exe`, `takeown.exe`, … (none is in `escapeProcesses`, `NuclearMode.kt:50-73`). They run from Win+R/Start/Explorer without any shell. `windowsterminal.exe` is not listed (only the `wt.exe` stub) **[verify the image name]**. | T1 | `NuclearMode.kt:50-73,:82-102,:280-352` | P0.7 |
| H6b | **Renamed copies of listed tools are not detected.** Detection is image name **or** canonical System32 path (`getRunningEscapeProcesses`, `getEscapeProcessesByPath`, `isEscapeTool`). Copy `cmd.exe` (or any listed tool) into a user folder under another name and neither check matches; the comment at `:75-81` implies renamed copies are caught, but that only holds for a copy that still lives in System32. The kiosk policy reuses `isEscapeTool`, so it has the same gap. | T1/T2 | `NuclearMode.kt:75-102,:142-196`; `LauncherKioskPolicy.kt:72-73`. **[verify]** that a copy of `cmd.exe`/`powershell.exe` runs from another folder. | P1.4 |
| H7 | **Emergency Break**: no PIN; 5 min/day; pauses *everything* including Nuclear Mode and kiosk (so the escape tools of H6 are free for the 5 minutes); the budget is stored in the DB keyed to `LocalDate.now()`. | T0/T2 | `KillSwitchService.kt:62-85,:110,:126-170`; `NuclearMode.kt:283`; `ProcessMonitor.kt:450-451` | P0.8 **[GATED O5]** |
| H8 | **Time manipulation.** Schedules use `LocalDateTime.now()` every 60 s; daily allowance uses `LocalDate.now()` as the day key; standalone blocks and uninstall requirements use `System.currentTimeMillis()`; the kill-switch day key uses `LocalDate.now()`. A standard user can usually change the **time zone** (shifts local time/date); an admin can change the clock. The allowance engine discards a gap after a wall/monotonic mismatch but the day keys still follow `LocalDate.now()`. There is no clock-tamper detection anywhere. | T2 | `BlockScheduleService.tick()`; `DailyAllowanceTracker.kt:56-58`; `StandaloneBlockService.kt:38-48,:216-258`; `UninstallProtectionService.kt:210,:286`; `AllowanceEngine.kt:~298-326`. **[verify]** time-zone change allowed for standard users. | P1.3 |
| H9 | **The DB can be edited while the app runs.** The block-rule cache reloads from SQLite every 2 s, so `UPDATE block_rules SET enabled=0` (any SQLite tool) takes effect within ~2 s with no PIN. WAL mode, no exclusive locking, no integrity check on protection rows. | T2 | `ProcessMonitor.kt:60,:332-357`; `Database.kt:554`. **[verify]** another process can open the file while FocusFlow holds it. | P1.1, P1.2 |
| H10 | **Availability attacks stop enforcement from starting.** Holding the DB file → "Busy" → enforcement never starts and Quit is free; holding `instance.lock` → "waiting for another instance" forever, services never start. Both are recoverable by a script. | T3 | `Main.kt:362-378,:430-452`; `SingleInstanceGuard.kt:81-130`; D10 chose "Blocking is OFF" (`DATABASE_UNAVAILABLE_BLOCKING_STATUS`, `Main.kt:83`) | P1.5 **[GATED O2]** |
| H11 | **Name-only identity.** User block rules match the process **image name**. The kiosk "safe list" (`java.exe`, `javaw.exe`, `explorer.exe`, `svchost.exe`, `conhost.exe`, OEM driver exes …) is also matched by name only, so a renamed copy of any program is ALLOWED in kiosk mode unless it matches a listed escape-tool path. A renamed copy of a blocked app (same folder) evades the rule. | T2 | `ProcessMonitor.kt:197-318,:802,:870-879`; `LauncherKioskPolicy.kt:72-73`. **[verify]** which apps still run when renamed. | P1.4 |
| H12 | **Uninstall is a normal-path guard only.** The original MSI/EXE command stays in the registry; a missing DB lets the wizard through; an unreadable DB needs only the typed phrase `UNINSTALL FOCUSFLOW`; `msiexec`/uninstaller processes are blocked only while Nuclear Mode is active. | T1/T2 | `WindowsUninstallRegistration.kt:28`; `UninstallProtectionService.kt:93-98` (comment: "A missing database contains no persisted protection state"), `:168-198`, `:423-444`; `NuclearMode.kt:42-47,:196-208` (direct installs only; MSIX is exempt) | **Settled** for the typed phrase and the installer blocking (D4, `windows-uninstall-protection.md`, §5); the missing-DB branch is **O8** |
| H13 | **Slow Nuclear sweep on an old PC.** Every 500 ms the snapshot loop calls `ProcessHandle.info()`/`parent()` for every process; a 250 ms cache means roughly one full scan per tick; if an escape tool is present a `taskkill` is spawned per tick. The owner measured it slow on a busy old PC. | perf | `NuclearMode.kt:280-352`; `ProcessSnapshot.kt:64,:133-160`; `WinApiBindings.kt:169-215`; the allowance engine also does its own full scan, `DailyAllowanceTracker.kt:71-84` (called from `AllowanceEngine.kt:244,:607`, 10 s tick) | P1.6 |
| H14 | **The UI can overstate protection when not elevated.** Nuclear's firewall layer returns early without administrator rights; `HostsBlocker.blockDomain` returns `NoAdmin` and the 30 s monitor just returns. Whether every screen tells the user is **[verify]**. | UX | `NuclearMode.kt:258`; `HostsBlocker.kt:70,:231` | P2.1 |

### 3.1 Corrections to the first-pass summary (so nothing here is stale)

- **H1** — first pass said the reset appears "in the PIN dialogs". Precisely: five call sites, not the three main flows (blocked apps, schedules, always-on). The owner also reports it is unusable in practice — still **[verify]**.
- **H3** — first pass said "corrupt DB → fresh empty DB". Wider: a plain *deleted* DB also yields a silent fresh DB. The corrupt-DB quarantine itself is owner-approved D9 recovery UX, so the fix keeps it and only stops it from *weakening protections*.
- **H5/H6** — new this pass: the watchdog's name-only check; the missing `taskkill`/`schtasks`/`reg`/`sc`/`net` entries; and the **own-child exemption** that adding them requires (§6 P0.7).
- **H7** — first pass said "5 minutes". New: those 5 minutes are enough to run the unlisted escape tools, because the break suspends Nuclear Mode entirely.
- **H4, H8, H10, H11** — new this pass. **H13** — refined: the shared snapshot with a 250 ms TTL already exists; the remaining cost is the per-process `info()` loop. **[verify]** with the existing `nuclearSweep durationMs=` / `processSnapshot durationMs=` lines in `%USERPROFILE%\.focusflow\enforcement.log`.
- **Nothing was retracted.**

### 3.2 Checked and found fine (do not "fix")

- Disabling/deleting block rules, schedules, VPN/network rules and the keyword blocker, and loosening/deleting allowances, are Global-PIN-gated in the UI: `AppBlockerScreen.kt:752-770`, `BlockDefenseScreen.kt:~108-125`, `VpnNetworkScreen.kt:~555-610`, `AllowanceEditPolicy.kt:30-37`. Always-on off is gated (`BlockDefenseScreen.kt:143`, `SettingsScreen.kt:~1062`); tray toggle refuses when a PIN is set (`StartupBootstrap.kt`).
- The hosts monitor re-applies missing entries every 30 s (`HostsBlocker.kt:207-244`) — when elevated.
- `ClearUserActivityService` only clears sessions/tasks/notes/temptation log (D13) and requires the PIN to be set (`ClearUserActivityService.kt:19-22`) — the UI must still verify it (it does via `ProfileScreen.kt:591`).
- The native SQLite DLL is extracted with a recorded SHA-256 (`NativeSqlite.kt`, `NativeSqliteFileStore`). I did **not** verify how a hash mismatch is handled **[verify]**.
- The PIN gates are UI-layer by design (`NuclearPin.kt` doc comment). That is fine against a UI-only attacker and is not a task here.

---

## 4. Owner decisions (gates)

The agent must **stop and ask** at each of these. Recommended answers are mine; none is the agent's to decide.

| # | Decision | Options | Recommendation | Reopens | Blocks |
|---|---|---|---|---|---|
| **O1** | What should "Forgot Global PIN" do? | **A** remove every reachable reset (code then matches the documented "cannot be recovered"; last resorts stay: uninstall, Store removal). **B** keep it but it only starts a visible **72-hour cooling-off**; protections stay locked meanwhile; a correct PIN cancels. **C** leave as is. | **B** — no permanent lock-out for a forgotten PIN, no impulsive bypass. A is acceptable if you prefer "cannot be recovered". | **D12** | P0.2 |
| **O2** | What should enforcement do when the DB is unavailable, deleted or replaced? | **A** status quo ("Blocking is OFF", fresh DB without protections). **B** keep a sealed, tamper-evident copy of the protection state; reseed a fresh DB from it; if the DB is Busy/Failed, enforce from it in *degraded* mode, read-only. | **B** — this is D10's option B and D11's "keep last validated settings", now justified because DB loss is user-inducible. | **D10-B, D11** | P1.1, P1.2, P1.5, P0.6(d), P0.5(DB branch) |
| **O3** | Restore semantics. | **A** PIN to stage only. **B** A + *merge-on-restore*: restore the user's data but **never weaken protections** (strictest of current vs backup). **C** leave. | **B** — your "merge instead" idea, scoped to protections. | D9 contract | P0.4 |
| **O4** | Store/MSIX tray Quit. | **A** keep exempt. **B** gate Quit with the Global PIN when Always-On, Nuclear Mode (+its PIN) or a session is active — this is *not* a package-removal veto. | **B** — the Store build is the main channel and currently has no quit gate. Confirm it is compatible with Store policy (owner call). | the MSIX note | P0.5 |
| **O5** | Emergency Break. | **A** unchanged. **B** monotonic budget + keep the command-line escape tools (`taskkill/schtasks/reg/sc/net/net1`) blocked during a break. **C** B + require PIN/typed delay to start a break. | **B**. | fixes/plan §11 semantics | P0.8 |
| **O6** | Opt-in "Strict" Nuclear extras (python, node, sqlite/DB tools). | add as setting default OFF / don't | add, default OFF | — | P0.7(c) |
| **O7** | Optional cool-off for *loosening* a protection when a PIN is set (e.g. 10 min, cancellable, with a notification). The equaliser for T3. | add as setting default OFF / don't | add, default OFF — only after P0–P1 land | new feature | (not scheduled; P2 follow-up) |

Until an `O-n` answer is in `Security/tracker.md`, its task stays **blocked** — do the open tasks and report.

---

## 5. Settled or accepted — do not change

- **D12** PIN hashing strength and recovery redesign: not done (unsalted SHA-256, 4-char Nuclear PIN, no attempt limiter). *Exception*: O1 (the reset path) once the owner answers.
- **D4** uninstall with an unreadable/missing DB (typed phrase), the direct-installer wrapper, and the "normal-path friction layer" policy; **MSIX removal is OS-owned**; Store/MSIX stays an active channel (`windows-uninstall-protection.md`).
- **D9** backup/recovery mechanism (`VACUUM INTO`, SHA-256 sidecars, restart-only restore, safety backups never auto-deleted). P0.3/P0.4 add gates around it, they do not replace it.
- **D10-A / D11** behaviour stays until O2 is answered.
- **Network limits**: hosts/firewall need elevation; DoH/VPN/caches can bypass domain cutoffs (`windows-network-enforcement.md`). Not fixable in-app.
- `SelectorProvider` flag, SQLite DLL packaging (D5/F1b), threading restructure (D19), Discord delivery route (D1).
- T4 routes (administrator, Safe Mode, offline edits, install-dir replacement).

---

## 6. Tasks

Each task: **Files** it may change · **Evidence** · **Direction** (the outcome — if you know a better mechanism, use it; any code here is illustrative) · **Done when** (the contract) · **Tests** · **Depends on**. Tests live under `src/test/kotlin/com/focusflow/...` mirroring the production package, using `TempHome`.

### Phase P0 — close T0/T1 routes

#### P0.1 Foundation: `ProtectionState`, diff and merge (open)
**Files:** new `services/protection/ProtectionState.kt`, `ProtectionDiff.kt`, `ProtectionMerge.kt`, `ProtectionStateReader.kt` + tests.
**Direction:**
- `ProtectionState` is an immutable snapshot of everything whose weakening is a bypass:
  - settings keys: `global_pin_hash`, `nuclear_pin_hash` (opaque strings, never logged), `always_on_enforcement`, `nuclear_mode`, `standalone_block_processes|until|start`, `killswitch_remaining_today`, `killswitch_reset_date`, `vpn_block_enabled`, `vpn_custom_processes`, `keyword_blocker_enabled`;
  - tables: `block_rules` (`process_name`, `enabled`, `block_network`), `block_schedules`, `daily_allowances`, `network_cutoff_rules`, the blocked-keyword store (`Database.getBlockedKeywords`, `Database.kt:1950`).
- Two readers: **live** (via `Database`) and **candidate file, read-only** — a separate `SQLiteDataSource` with `SQLiteConfig` read-only, as `AutoBackupService.kt:370-380` does. The candidate reader never touches the live `Database` singleton and never writes.
- `weakenings(current, candidate): List<Weakening>` — pure; one typed reason per way the candidate is weaker (PIN removed/changed to absent, always-on or Nuclear off, rule removed/disabled, schedule removed/disabled, allowance loosened/removed, standalone block shortened/removed, VPN/keyword/network cutoff off, kill-switch budget refilled).
- `strictest(a, b): ProtectionState` — never weaker than either: OR for booleans; union for rules/schedules/keywords/network rules; `max(until)` for standalone blocks; `min` minutes per process for allowances; PIN hashes — keep the *current* one when set, else the other; kill-switch — keep the smaller remaining budget for the same day.
**Done when:** every field has a unit test; property-style test: `weakenings(strictest(a,b), a)` and `…(…, b)` are both empty for generated pairs; candidate reader test opens a temp file read-only and the file's bytes are unchanged afterwards; runs on Linux with no network.

#### P0.2 Neutralise the Global-PIN reset path — **[GATED O1]** (reopens D12)
**Files:** `ui/components/PinGateDialog.kt`, `services/GlobalPin.kt`, `ui/screens/SettingsScreen.kt:~1831-1850`, `ui/screens/ContactScreen.kt` (forgotten-PIN notice text), `ChangelogScreen.kt`.
**Direction:**
- **Option A:** remove the `FORGOT_*` steps and the `allowReset` parameter. Make `resetWithoutPin()` `internal`/removed. The Settings "clear PIN" flow must use the *verified* `GlobalPin.clearResult(rawPin)` (it already verifies; do not call an unverified clear after a separate verify). Extract the dialog's step logic into a small pure class so a test can assert there is no unverified path.
- **Option B:** the link stays, but confirming only writes `global_pin_reset_not_before` (epoch ms from `TrustedClock`, P1.3) and shows a persistent, plain banner "PIN reset pending until …". All gated actions stay locked until then; a correct PIN entry cancels it; the next gate evaluation after the deadline clears the PIN. Depends on P1.3.
- Either way: no copy may say a PIN can be recovered unless it can.
**Done when:** (A) a test/source-scan fails the build if `resetWithoutPin(` has any caller outside the verified clear flow; the state-machine test has no unverified clear; (B) tests: countdown, cancel with correct PIN, clock rollback does not shorten it. Text in `ContactScreen.kt:~201`, the changelog and the dialog agree with behaviour. **[verify]** W-1.

#### P0.3 PIN for restore and backup management (open)
**Files:** `ui/screens/ContactScreen.kt:519,:564,:585,:605,:624,:683`; new pure `services/BackupActionPolicy.kt` (like `AllowanceEditPolicy.requiresPin`); `BackupRecoveryService.kt` untouched except if a parameter is needed.
**Direction:** when `GlobalPin.state() != NOT_SET` (UNKNOWN counts as set — the existing fail-closed convention), require `GlobalPin.verifyResult` before: staging a restore; deleting a safety backup, restore archive or secondary backup; choosing/removing the secondary folder. Keep the buttons disabled until DB readiness **and** PIN state are known (the allowance-edit rule in `enforcement-pin-ownership.md`). Use a PIN dialog **without** any reset link. No PIN set → behaviour unchanged.
**Done when:** `BackupActionPolicy` unit tests (NOT_SET/SET/UNKNOWN × each action); no restore/delete code path in `ContactScreen` is reachable without the policy check (source-scan test); **[verify]** W-2.

#### P0.4 Merge-on-restore: restore data, never weaken protections — **[GATED O3-B]**
**Files:** `services/BackupRecoveryService.kt` (`initializeDatabaseForStartup`, `:434-~560`, `rollbackAndReopen` `:561`), `RecoveryNoticeService.kt`, tests. Depends on P0.1.
**Direction:** after the staged candidate is installed and the DB opens, compute `strictest(preRestoreState, restoredState)` — `preRestoreState` read from the safety archive the restore already made (`journal.preRestoreDirectory`) — and apply it to the restored DB **in one transaction**. If anything fails, use the existing rollback path so a restore never lands half-merged. Tasks, notes, stats come from the backup untouched. The restore notice lists what was kept ("Kept N protections your backup did not have"). If the current DB had no protections (never configured), restore is exactly today's behaviour. A deliberate loosening happens through the PIN-gated UI only — never through restore.
**Done when:** test: current = {PIN, always-on, rule X on, schedule S}, candidate = {no PIN, always-on off, X disabled, no S, different tasks} → after startup the DB has current's protections and the candidate's tasks; a forced failure rolls back to the pre-restore DB; existing `BackupRecoveryServiceTest` passes (update only the assertions that assumed settings were replaced wholesale — record each change in the tracker). **[verify]** W-2.

#### P0.5 Quit gating on Store/MSIX builds — **[GATED O4]**
**Files:** `services/UninstallProtectionService.kt:156-160` (or a new `QuitProtectionService`), `Main.kt:247,:301-304`, tests.
**Direction (option B):** split "may the app quit?" from "may the app be uninstalled?". For **all** install variants, `authorizeQuit()` requires the same PINs the direct build already requires (Always-On+Global PIN; Nuclear Mode with its PIN or "disable it first"; focus session with the Session PIN). MSIX remains exempt from *uninstall/installer* handling (`windows-uninstall-protection.md`). `doShutdown(skipQuitAuthorization=true)` callers (elevation relaunch) and crash/JVM-shutdown paths stay fail-open. The DB-unavailable `quitGate` (`Main.kt:247`): while the sealed snapshot (P1.1) says protection is active, require the PIN verified against the snapshot's hash; until P1.1 exists leave it.
**Done when:** tests with a fake `InstallVariant`: direct builds unchanged; MSIX now prompts under each active protection; crash/handoff paths do not prompt. **[verify]** W-5.

#### P0.6 Watchdog and persistence hardening (open)
**Files:** `enforcement/WatchdogInstaller.kt`, `enforcement/WindowsStartupManager.kt`, new `services/Heartbeat.kt`, wiring in `StartupBootstrap.kt`/`Main.kt`, tests.
**Direction:**
- **(a) Identity.** The watchdog script must decide "FocusFlow is running" from the **executable path** plus **heartbeat freshness**, not `Get-Process -Name 'FocusFlow'` (`WatchdogInstaller.kt:52`). A decoy named `FocusFlow` must not satisfy it.
- **(b) Heartbeat.** `~/.focusflow/heartbeat` (epoch, pid, start time, "protection active" flag) written every ~10–15 s. The watchdog relaunches when it is stale (> ~45 s) even if a process with that name exists. Interval 1 minute if `schtasks` allows it **[verify]**.
- **(c) Re-assertion.** The running app re-checks every few minutes that the watchdog task, taskbar-guard task and Run key still exist; if one is missing while a protection is active it recreates it **and records an event** (P2.2) — never silently.
- **(d) Unclean exit.** Write a clean-exit marker only after `authorizeQuit()` passed in `doShutdown`. On start: heartbeat says protection was active and no marker → record an "enforcement gap from…to…" event and re-assert the last-known protection state (from the sealed snapshot once P1.1 exists; until then from the DB).
- Disabling auto-start in Settings is already PIN-gated (`SettingsScreen.kt:578`); leave the onboarding toggle alone (first run only).
**Done when:** pure-logic tests for heartbeat evaluation and for the generated script/command (assert the name-only check is gone); marker logic tests (clean vs unclean vs first run). **[verify]** W-6.

#### P0.7 Nuclear Mode: close the tool gaps without self-sabotage (open; (c) is **[GATED O6]**)
**Files:** `enforcement/NuclearMode.kt` (`:50-73`, `:82-102`, `:280-352`), `services/LauncherKioskPolicy.kt` (reuse `isFocusFlowOwnedUtility`), tests.
**Direction:**
- **(a) Core additions** — tools that can end FocusFlow, edit its persistence or data. At minimum: `taskkill.exe`, `schtasks.exe`, `reg.exe`, `sc.exe`, `net.exe`, `net1.exe`, `icacls.exe`, `takeown.exe`, and the real Windows Terminal image `windowsterminal.exe` **[verify the name]**. Keep each name justified in a comment. Add canonical-path matching for the Windows ones (System32/SysWOW64) like the existing suffix set, so renamed copies **inside System32** are caught.
- **(b) Own-child exemption (required, or this task breaks the app).** FocusFlow itself spawns `taskkill` (`WinApiBindings.kt:169-215`), `schtasks` (`WatchdogInstaller`), `reg` (`WindowsStartupManager.kt:38,:52`), `netsh`/`powershell`/`nslookup`/`ipconfig` (network/hosts code). Today only the launcher-chain branch has an exemption (`NuclearMode.kt:303-312`). Add one to the **normal** branch: a listed process is NOT killed only if it is a **direct child** of FocusFlow's own PID, its resolved path is under `%SystemRoot%` System32/SysWOW64 (or the PowerShell directory), and the parent/child creation times are consistent (PID-reuse safe). Grandchildren and same-named processes from any other parent or path are still killed. Note the startup order: `NuclearMode.loadFromDb()` runs before `WatchdogInstaller.install()` (`StartupBootstrap.kt`) **[verify]** whether `loadFromDb` enables synchronously.
- **(c) Strict extras — GATED O6.** A setting `nuclear_strict_tools` (default OFF): `python.exe`, `pythonw.exe`, `py.exe`, `node.exe`, `sqlite3.exe`, common SQLite GUI tools. Off by default because they are legitimate developer tools.
- Do not touch the kiosk safe list here (P1.4) and do not change the 500 ms cadence (P1.6).
**Done when:** tests with fake snapshots: `taskkill.exe` whose parent is Explorer → BLOCK; same name as a direct FocusFlow child under System32 → ALLOW; same name under a user-writable path → BLOCK; PID-reuse case → BLOCK; grandchild → BLOCK; existing `NuclearModeSnapshotTest`/`NuclearModeSettingsTest` and kiosk-policy tests pass. **[verify]** W-7 (also confirms FocusFlow's own enforcement still works while Nuclear is on).

#### P0.8 Emergency Break hardening — **[GATED O5]**
**Files:** `enforcement/KillSwitchService.kt`, `enforcement/NuclearMode.kt:281-282`, `enforcement/ProcessMonitor.kt:450-451`, tests. Depends on P1.3 (clock) and P0.7.
**Direction (option B):**
- Budget accounting on `TrustedClock`: persist `killswitch_consumed_today` and a day anchor; **consumed never decreases within an anchored day**; it refills only when the anchored day rolls. A DB edit that lowers it is caught by P1.1/P1.2 (the value is part of `ProtectionState`).
- During a break, keep enforcing the **self-protection subset** of Nuclear's list (`taskkill/schtasks/reg/sc/net/net1`, with the own-child exemption). Task Manager and ordinary apps stay allowed so a real emergency (a frozen program) is still serviceable.
- Option C adds a PIN or a typed-delay step before a break can start (`kill_switch_requires_pin`, default off).
**Done when:** tests: clock rollback or zone change does not refill; consumed is monotonic within a day; during a break the self-protection subset is still BLOCK while an ordinary app is ALLOW; kiosk `onKillSwitchActivated/Deactivated` behaviour unchanged. **[verify]** W-8.

### Phase P1 — raise T2/T3

#### P1.1 Sealed protection snapshot and reseeding — **[GATED O2]**
**Files:** new `services/protection/ProtectionSnapshotStore.kt` (+ key provider seam), hooks in `data/Database.kt` mutation functions for protection-critical data, startup reconciliation in `Main.kt:402-412` / `StartupBootstrap.startServices` **before** it reads any setting, tests. Depends on P0.1.
**Direction — the invariant:** *the app is the only sanctioned writer of protection state. A change that is not explained by an in-process write is tamper and is reverted.*
- **Seal.** After every in-process write to protection-critical data (settings keys above; `upsert/delete` of block rules, schedules, allowances, network rules, keywords; restore apply), write `~/.focusflow/protection.snapshot` (+ `.prev`): the `ProtectionState`, a monotonic sequence number, and an HMAC-SHA256. The HMAC key is 32 random bytes protected with **DPAPI, CurrentUser scope** (`Crypt32Util.cryptProtectData` from the JNA platform jar already shipped) stored in `~/.focusflow/protection.key`. Atomic write (temp + move). Behind a key-provider seam so Linux tests use a fake. Sealing is best-effort and off the UI thread; for a *loosening* it is synchronous right after the DB commit (a crash in between simply reverts that one change — safe).
- **Reconcile.** At startup (before services read settings) and on every cache refresh (P1.2): if the live DB is **weaker than the valid snapshot** in any way not explained by an in-process write → restore the stronger values into the DB, `ProcessMonitor.invalidateCaches()`, record an event (P2.2), show a tray notice. If the live DB is **stronger**, update the snapshot. A *fresh/empty* DB (no protection rows at all) is treated the same way — **seed protections only**, never user data — then show the existing recovery notice with one extra sentence.
- **Degraded sources.** Snapshot HMAC invalid or key missing → ignore it, fall back to the newest verified backup's protection state (read-only candidate reader, P0.1), mark "unsealed", and re-seal from the DB. Neither available → today's behaviour. First run after upgrade with no snapshot → trust-on-first-use from the current DB.
- **Never** block startup, never write plaintext PINs, never put the snapshot in telemetry or logs.
- Honest limit (put it in the code comment and the changelog wording): this stops SQLite tools, Explorer deletes and lax restores; a program that calls DPAPI as the same user can re-seal it (T3).
**Done when (fake key provider, `TempHome`):** (1) empty DB + valid snapshot → protections seeded, tasks/notes untouched; (2) external `UPDATE` that weakens a rule/flag/PIN with a valid snapshot → reverted at the next reconcile and an event is recorded; (3) an in-process loosening (simulated UI call) is sealed and **not** reverted; (4) bad HMAC → ignored, falls back to backup state; (5) key deleted → snapshot regenerated from the DB and flagged; (6) snapshot write failure never fails a UI action; (7) no log line contains a hash or a path. **[verify]** W-3, W-4.

#### P1.2 Continuous integrity check; DB locking evaluation (open once P1.1 lands — **[GATED O2]**)
**Files:** `enforcement/ProcessMonitor.kt:332-357` (`refreshCaches`), `data/Database.kt`, tests.
**Direction:** run the P1.1 reconcile from the existing 2 s cache cycle, reusing the rows that cycle already reads so it adds no new queries beyond a few cheap settings reads. **Evaluate** `PRAGMA locking_mode=EXCLUSIVE` (the DB is WAL today, `Database.kt:554`): it would stop *cooperating* SQLite clients while the app runs, but may break the uninstall wizard's read-only open (`Database.initReadOnlyForWizard`, which would then see "busy" and block uninstall while the app runs), `VACUUM INTO` backups, and diagnostics. **Do not ship it unless** tests plus the owner's Windows run show all three still work; record the decision either way. It does not stop raw byte writes.
**Done when:** a test using a second JDBC connection weakens a protection mid-run and it is reverted within two refresh cycles; the locking evaluation (what was tried, results, decision) is in the tracker.

#### P1.3 `TrustedClock` — time and time-zone tamper (open)
**Files:** new `services/TrustedClock.kt`; consumers `KillSwitchService.kt:110,:129`, `BlockScheduleService.tick()`, `DailyAllowanceTracker.kt:56-58` (the allowance `Clock` port in `services/allowance/AllowancePorts.kt`), `StandaloneBlockService.kt:38-55,:216-258`, `UninstallProtectionService.kt:210,:286`; tests.
**Direction:**
- Persist `lastSeenWallMs`, `lastSeenZoneId` and `lastSeenOffsetSeconds` about once a minute (DB + snapshot once P1.1 exists). Within a run use the monotonic clock as the truth; across reboots use `lastSeenWallMs` with a tolerance (~2 min).
- Flags: `ROLLBACK` (wall < last seen − tolerance), `FORWARD_JUMP` (wall advanced more than monotonic + ~5 min), `ZONE_CHANGED` (zone **id** changed — a DST change inside the same zone is *not* a change).
- Day rollover for the kill-switch budget, allowance day key and schedule windows is evaluated in the **anchored zone** and by elapsed time, not by comparing `LocalDate.now()` strings. A flag never grants a new day or budget by itself.
- On a flag: keep using the anchored zone for schedules; record an event; if a Global PIN is set require it to accept the new zone/clock (low-friction "I'm travelling" confirm); with no PIN, accept and notify.
- The allowance engine's existing wall/monotonic gap handling (`AllowanceEngine.kt:~298-326`) stays.
**Done when:** fake-clock tests: rollback by a day → no kill-switch/allowance refill; forward jump past midnight → today's usage not reset and event recorded; zone UTC+5:30 → UTC+14 → a 09:00–17:00 schedule does not move; DST in the same zone → no flag; reboot with a correct clock → no flag. **[verify]** W-9 (that a standard user can change the time zone).

#### P1.4 Spoof-resistant process identity (open)
**Files:** `enforcement/ProcessMonitor.kt:197-318,:802,:870-879`, `services/LauncherKioskPolicy.kt:72-73`, new `services/ExecutableIdentity.kt`, `services/ProcessSnapshot.kt` (path resolution already exists), tests.
**Direction:**
- **Safe list.** Split entries by who may legitimately own them: *Windows* names (explorer, svchost, csrss, dwm, …) count as safe **only if the resolved path is under `%SystemRoot%`**; *FocusFlow* names (`focusflow.exe`, `java.exe`, `javaw.exe`) only under FocusFlow's install directory or the own PID; *OEM driver* names only under `Program Files*`, `ProgramData` or `%SystemRoot%` (owner may extend). **If the path cannot be resolved, keep today's name-based ALLOW and re-check next tick** (R6 — never kill a critical process).
- **Block rules.** Besides the image name, match the PE version-info `OriginalFilename`/`InternalName`, cached per (path, size, mtime) so there is no per-tick disk read; and the recorded `exePath` when the rule has one. A renamed copy of a *user-blocked* app is then blocked. Never apply version-info matching to the Windows safe list.
- Keep the P1.6 cost budget: identity lookups are cached and only for processes that are not already known-good.
**Done when:** tests: a renamed copy in a user-profile folder named `javaw.exe`/`explorer.exe` during kiosk → BLOCK; the real `C:\Windows\explorer.exe` → ALLOW; unresolved path → ALLOW (+ flagged); a rule for `chrome.exe` blocks a process with `OriginalFilename=chrome.exe` named `x.exe`; the version-info cache hits on the second tick. **[verify]** W-10, W-11.

#### P1.5 Enforce from the sealed snapshot when the DB or instance lock is unavailable — **[GATED O2]**
**Files:** `Main.kt:362-378,:430-452`, `services/StartupBootstrap.kt`, `ui/startup/StartupGateWindow.kt`, `services/SingleInstanceGuard.kt` (no behaviour change to the lock itself), tests. Depends on P1.1.
**Direction:** (a) `DbInitResult.Busy/Failed` at startup with a valid snapshot → start **degraded enforcement**: process/app blocking, Nuclear Mode, schedules and standalone blocks from the snapshot, **read-only** (no DB writes, no replay of anything — D11 rule 5); the startup gate shows the persistent status "Running from saved protection settings — the database is unavailable" instead of "Blocking is OFF…"; keep retrying the DB every 10 s as today. (b) `HolderUnresponsive` for longer than ~30 s with a stale heartbeat → the same degraded enforcement (no UI), plus an event; do not try to steal the lock. (c) Quit while degraded requires the PIN verified against the snapshot. Exceptions inside degraded mode fall back to today's behaviour (R6).
**Done when:** tests with injected `DbInitResult.Busy`/`Failed` and a fake snapshot: `ProcessMonitor` receives the snapshot rules; Quit is gated; no `Database.write*` is attempted; the status string state is exposed for the UI. **[verify]** W-12.

#### P1.6 Nuclear sweep cost on old PCs (open — measure first)
**Files:** `enforcement/NuclearMode.kt`, `services/ProcessSnapshot.kt:133-160`, `enforcement/WinApiBindings.kt`, tests.
**Direction:** **R6.4 applies: do not widen the detection window.** (1) Make cost visible: a rolling one-line summary every 60 s while Nuclear Mode is on (tick count, p50/p95 duration, number of spawned `taskkill`s) at INFO. (2) Cut cost, not coverage: behind the `ProcessSnapshot` seam, enumerate with one `CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS)` call (name, pid, parent pid in one syscall) instead of `ProcessHandle.info()`/`parent()` per process; resolve the **full path** lazily, only for names not already known-good, through the existing pid+creation-time cache. (3) Terminate by PID (`OpenProcess(PROCESS_TERMINATE)` + `TerminateProcess` via JNA, falling back to `taskkill`) instead of spawning `taskkill` per tick. (4) Keep the 500 ms cadence; any cadence change needs the owner's OK. Do not claim a speed-up without the owner's before/after numbers.
**Done when:** a unit test with a fake 400-process snapshot counts native calls per tick (fewer than today's per-process `info()` loop) and still returns identical escape-tool detection results for the existing fixtures; no behaviour change in `NuclearModeSnapshotTest`. **[verify]** W-13 (owner records `nuclearSweep durationMs` before/after on the old PC).

### Phase P2 — visibility, wording, design note

#### P2.1 Honest layer status (open)
**Files:** `ui/screens/NuclearModeScreen.kt`, `ui/screens/WindowsSetupScreen.kt`, `ui/screens/BlockDefenseScreen.kt`, strings where used.
**Direction:** show per-layer status derived from real state: Nuclear firewall layer inactive when not elevated (`NuclearMode.kt:257-259`), hosts blocker "needs administrator" (`HostsBlocker.kt:70`), degraded mode (P1.5), snapshot unsealed (P1.1). Copy says "friction".
**Done when:** status comes from state (unit-tested view-model), not constants; no new claim of absolute protection.

#### P2.2 Protection event log (open; used by P0.6, P1.1, P1.3, P1.5)
**Files:** new `services/protection/ProtectionEvents.kt`, a `protection_events` table through the existing migration pattern (next schema version; follow `Database.kt` migration conventions, add the column/table guard), Reports screen section.
**Direction:** append-only (from the app's side) rows `{ts, type, detail}`; types: `UNCLEAN_EXIT`, `TIME_ROLLBACK`, `TIME_FORWARD`, `ZONE_CHANGED`, `SNAPSHOT_REVERT`, `DB_REPLACED`, `PERSISTENCE_RECREATED`, `RESTORE_MERGE`, `DEGRADED_ENFORCEMENT`. Shown in Reports as "Protection events". Local only — **never sent by telemetry** (privacy-first, D2/D3). No paths or hashes in `detail`.
**Done when:** migration test (fresh and upgraded DB); append/read/limit tests (cap the table, e.g. last 500); the Reports section renders from the view-model; redaction test.

#### P2.3 Wording and docs (open)
**Files:** `ChangelogScreen.kt`, `ContactScreen.kt` (forgotten-PIN text), `README.md`/docs claims, Store text only if the owner points at it.
**Direction:** every claim says friction; the forgotten-PIN text matches O1; list what the sealed snapshot does and does not stop (in plain words). No bypass how-tos in public text.
**Done when:** a grep for absolute words ("unbreakable", "can't be bypassed", "secure") in the touched files finds none; text and behaviour agree.

#### P2.4 Design note: privilege separation (no code)
**File:** new `Security/privilege-separation-design.md`.
**Direction:** compare (a) a LocalSystem Windows service that owns the protection state in an ACL-protected `%ProgramData%` folder, with a named-pipe client in the app and the PC owner on a standard account; (b) the direct-build installer changes (a WiX custom action; see `windows-uninstall-protection.md`); (c) what MSIX allows for packaged services — **[verify]**, do not assert; (d) "someone else holds the admin/PIN" as a non-code option; (e) install, support and Store-policy costs. Recommend nothing; list the open questions.
**Done when:** the note exists, every claim is tagged [code]/[verify]/[owner-doc], and **no application code changed**.

---

## 7. Test strategy

- New tests go under `src/test/kotlin/com/focusflow/...` mirroring production packages; follow the existing seams (`TempHome`, `ForkedJvm`, fakes for `ProcessSnapshot`, allowance `Clock`, `InstallVariant`).
- Add one **bypass regression suite** (`BypassRegressionTest`) with a test per closed route at the *logic* level: restore of a weaker backup is merged; a weaker external DB edit is reverted; a fresh DB is reseeded; RESET has no unverified path; kill-switch budget cannot be refilled by clock/zone; `taskkill` from a non-FocusFlow parent is blocked; renamed safe-list name is blocked. It is the checklist the tracker links to.
- Run the existing suite first and record the number (baseline 98 tests). Finish with a full `gradle :test --no-daemon`; record the final line, test count, and the XML report directory. Say clearly that Windows checks were not run.
- Property-style tests for `strictest`/`weakenings` (P0.1) and for `TrustedClock` flag logic.
- No test may touch the real profile (R3) or the network (loopback-only IPC is allowed, as for `SingleInstanceGuardTest`).

## 8. Tracker and final report

Create `Security/tracker.md` with: batch status, one checkbox per task above plus the `O-n` answers, a per-task evidence block (changed files, exact command, result), failures/blockers/deferred work, and the reconciliation line you add at the end.

Your **final report** contains, in this order: (1) task table — status, files changed; (2) the baseline and final `:test` results (counts, JVM used); (3) tasks blocked on an owner answer; (4) Windows checks **not run** (list the W-ids); (5) deviations from this plan and why; (6) out-of-scope observations; (7) any `[code]` claim from §3 that no longer held when you re-grepped it.

## 9. Windows verification sheet (owner-run — agent never claims these)

Run on a **test profile or a copy**, with a manual backup first. Each line: action → expected.

| ID | Check |
|---|---|
| W-1 | Open the VPN/Network gate, enter a wrong PIN 3× → per O1 (A: no reset link; B: link starts a visible countdown, protections stay locked). |
| W-2 | Stage a restore with a Global PIN set → PIN prompt; wrong PIN refused. Restore a backup that has no PIN/always-on → after restart the live protections are still on and the backup's tasks are back. |
| W-3 | Quit via PIN, delete `focusflow.db*` with FocusFlow closed, start FocusFlow → protections reseeded, tasks empty, recovery notice shown. |
| W-4 | With FocusFlow running, disable a block rule with a SQLite GUI tool → within a few seconds it is enabled again, a notice shows, an event is logged. |
| W-5 | Store build: Always-On + Global PIN, tray → Quit → PIN prompt (O4-B). |
| W-6 | `taskkill /f /im FocusFlow.exe` → restarts within the watchdog interval and an "unclean exit" event appears; a decoy process named `FocusFlow.exe` does **not** stop the relaunch; deleting the scheduled task → recreated within minutes with an event. |
| W-7 | Nuclear Mode on: Win+R → `taskkill /?` is killed; FocusFlow's own enforcement, watchdog install and hosts/firewall setup still work (no self-kill). |
| W-8 | Use the Emergency Break, then change the time zone/clock → the budget is not refilled; during the break `taskkill` is blocked while an ordinary app is allowed. |
| W-9 | A 09:00–17:00 schedule; change the time zone so local time is outside → the schedule still applies; a PIN-confirm notice appears. (Confirms a standard user can change the time zone.) |
| W-10 | In kiosk, copy any program to the Desktop as `javaw.exe`/`explorer.exe` and run it → killed; the real shell, taskbar, input and logon still work. |
| W-11 | Copy a blocked app's exe to another name in the same folder and run it → blocked. |
| W-12 | Hold `instance.lock` (and separately the DB file) with your own script, then start FocusFlow → after ~30 s degraded enforcement with the banner; releasing the lock returns to normal. |
| W-13 | On the old PC, with Nuclear Mode on for 10 minutes, record `nuclearSweep durationMs` / the 60 s summary line **before and after** P1.6. |
| W-14 | **No lock-out regression:** crash FocusFlow during kiosk → taskbar returns at next login; Windows sign-in, Explorer, keyboard and mouse are normal after every task. |

---

## Appendix A — files read for this plan

`Main.kt`, `AppPaths.kt`, `StartupBootstrap.kt`, `data/Database.kt` (init, recovery, settings, migrations, queries), `data/NativeSqlite.kt`, `services/{GlobalPin, NuclearPin, SessionPin, PinPolicy, BackupRecoveryService, AutoBackupService, RecoveryNoticeService, ClearUserActivityService, UninstallProtectionService, UninstallWizard, UninstallWizardFlag, WindowsUninstallRegistration, SingleInstanceGuard, LauncherKioskPolicy, ProcessSnapshot, StandaloneBlockService, BlockScheduleService, DailyAllowanceTracker, HostsBlocker, DatabaseRepairService, allowance/AllowanceEditPolicy, allowance/AllowanceEngine}`, `enforcement/{NuclearMode, KillSwitchService, ProcessMonitor, WatchdogInstaller, WindowsStartupManager, InstallVariant, RegistryLockdown}`, `ui/components/{PinGateDialog, NuclearPinDialogs}`, `ui/screens/{ContactScreen, AppBlockerScreen, BlockDefenseScreen, VpnNetworkScreen, SettingsScreen, FocusScreen, NuclearModeScreen, ChangelogScreen}` (relevant regions), `build.gradle.kts`, and the repo docs `AUDIT-ADDENDUM.md`, `D11-MID-RUN-DATABASE-LOSS.md`, `fixes/plan.md` (§10–11), `fixes/tracker.md`, `.agents/memory/*`, `Launcher/rules.md`, `Launcher plan` format.

**Not read (so not covered):** `NetworkBlocker.kt`, `VpnBlocker.kt` internals, `FocusSessionService.kt`, `FocusLauncherService.kt` (beyond call sites), `ElevationManager.kt`/`RelaunchHandoff.kt` internals, `WinEventHook.kt`, `GlobalKeyboardHook.kt`, `CrashReporter.kt`, telemetry/Discord code, and everything under `ui/` not listed above.
