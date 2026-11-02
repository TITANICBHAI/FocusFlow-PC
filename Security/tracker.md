# Security tracker
Started: 2026-11-01 · Agent: Claude (implementing agent) · Git: no

## Owner answers (copied from plan.md §2)
O1: A · O3: B · O4: B · O2: restore last known protections · O5: Global PIN to start Emergency Break · O6: not wanted (not built) · O7: cool-off as Settings toggle per PIN, default OFF · O8: wizard uses restored protections, else lets uninstall through

## Baseline (Card A0)
- gradle / java versions: gradle NOT available in the agent sandbox; java 21.0.10 (OpenJDK). No kotlinc either.
- `gradle :test --no-daemon`: NOT RUN (no gradle) — baseline unknown. Owner must run it.
- failing tests at baseline: unknown

## Cards
- [~] A0 baseline — STOP condition ("no gradle available"); owner to run baseline
- [~] A1 backup actions need the PIN — edits applied + grep checks pass; NOT compiled, NOT tested (see evidence)
- [~] B1 merge on restore — edits applied; NOT compiled, NOT tested
- [~] B2 Store quit gate — edit applied; NOT compiled, NOT tested
- [~] B3 remove "Forgot PIN?" — edits applied; NOT compiled, NOT tested
- [~] C1 watchdog checks the real exe path (plan N2, first part) — new WatchdogScript.kt, edited WatchdogInstaller.kt, WatchdogScriptTest.kt (5 tests PASS, compiled with kotlinc 2.0.21 on the pure file), CL-C1. Direct installs only; Store/MSIX, dev runs, missing exe or an over-long task command keep the old name-only check.
- [~] C2 process kill protection (plan N2, second part) — HIDDEN, OFF by default. New ProcessHardening.kt (JNA, NOT compiled here: no JNA jar offline), ProcessHardeningSddl.kt + ProcessHardeningSddlTest.kt (4 tests PASS); edited NuclearMode.kt (enable / disable / elevation handoff). Active only while Nuclear Mode is on AND settings key `process_kill_protection` = `true`. Adds one deny-PROCESS_TERMINATE entry for interactive users in front of the existing process permissions and restores the original when Nuclear Mode ends.
- [~] D1 N8 honest text when not elevated — SettingsScreen Nuclear row + Nuclear-ON tray notice now say the firewall lock is off and admin-started tools may survive when FocusFlow is not elevated. VPN/hosts screen already reported failures. Text only. CL-D1.
- [~] D2 N6 renamed copy of a Windows program is no longer "safe" in kiosk/Nuclear — LauncherKioskPolicy.isImpostorOfWindowsComponent (16 core Windows names; only when path is a drive path outside the Windows folder; unknown/device/UNC path or no Windows folder = old behaviour). LauncherKioskImpostorTest (5 tests) + existing LauncherKioskPolicyTest (6) = 11/11 PASS, compiled with kotlinc against stubs for NuclearMode/ProcessSnapshotEntry. CL-D2.
- [~] E1 N1+O8 restore last known protections — NEW ProtectionSnapshotService.kt (snapshot via VACUUM INTO about every 60 s; on start, if the live DB has no PIN/Always-On/Nuclear but the snapshot has, RestoreProtectionMerge adds them back + tray notice); StartupBootstrap calls restoreIfNeeded() before any protection setting is read; UninstallProtectionService opens the snapshot read-only for the wizard when the DB lost its protections. NOT compiled (JDBC/JNA jars offline). Checked in Python sqlite3: the protection-count SQL (fresh 0, empty values 0, PIN 1, Nuclear 1) and VACUUM INTO on a read-only connection. Merge SQL verified earlier (B1).
- [~] E2 N4+O5 Emergency Break needs the Global PIN to start — NEW EmergencyBreakPin.kt (EmergencyBreakPolicy + Swing password prompt, fail-closed); StartupBootstrap tray callback asks before toggle(). Ending a break needs no PIN. EmergencyBreakPolicyTest 3/3 PASS (kotlinc, PinState stub).
- [~] E3 O7 cool-off — NEW CoolOffScheduler.kt (5 tests PASS) + LooseningCoolOff.kt; Settings card with two switches (Always-On/Global PIN, Nuclear/Nuclear PIN), default OFF; turning a switch off is itself cooled; asking again cancels a running cool-off. Wired at the PIN-checked turn-off sites only (Settings Always-On dialog, Settings + NuclearModeScreen Nuclear dialog). Session-end auto-disable, Focus Launcher and the no-PIN branches stay immediate. Compose edits NOT compiled.
- [~] E4 SecurityWiringSourceTest (5 source-scan checks; emulated in Python: all True).
- [~] F1 log findings (enforcement.log from the Store build 2.0.5.0, Nov 2): (a) WindowsSessionStateMonitor 'Exception reading field style' = JNA cannot read a private structure class by reflection; SessionWindowClass and WindowProcedure are now internal (public in bytecode). First time that lock/unlock path will actually run: it still falls back to the old behaviour on any error. (b) FocusFlowWatchdog fails to register on the Store build (code -2147467259). I first changed it to a script-file task started through shell:AppsFolder; then REVERTED that: on a Store install FocusFlow's writes under %APPDATA% are redirected to a package-private store (MS Learn, flexible virtualization; our manifest has no unvirtualizedResources), so Task Scheduler's powershell would not find watchdog.ps1 and the log would say 'registered' for a task that does nothing. Same likely applies to the existing taskbar-guard.ps1 on the Store build (pre-existing, not fixed). Cause of the registration failure still unproven. (c) schtasks and 'Firewall rule was not confirmed' warnings now include the tool output / created flag so the next log shows the reason. WatchdogScriptTest 5 tests PASS (9/9 with the SDDL tests).
- [~] Z finish — report given in chat; owner runs gradle later

## Evidence per card
### A1
- New: BackupActionPolicy.kt, BackupActionPolicyTest.kt, BackupUiPinGateSourceTest.kt (copied verbatim from plan.md)
- Edited: ContactScreen.kt (A1-1..A1-10), ChangelogScreen.kt (CL-A1). Every old_string occurred exactly once; files are LF.
- Checks run: `grep -c "withBackupPin {"` = 5; requiresPin( = 1; allowReset = false = 1; imports (BackupActionPolicy, PinGateDialog, GlobalPin) = 1 each; `pinGateAction?.let { pending ->` = 1; changelog line = 1.
- Hand-run equivalent of source-scan test 2: callers of the restore/management functions = {BackupRecoveryService.kt, ContactScreen.kt} (matches expected set).
- NOT run: RED check, GREEN check, `gradle :compileKotlin`, full `:test`. No gradle in the sandbox.

### B1
- New: RestoreProtectionMerge.kt, RestoreProtectionMergeTest.kt, RestoreKeepsProtectionsTest.kt. Edited: BackupRecoveryService.kt (B1-1, B1-2), ChangelogScreen.kt (CL-B1). Greps: mergeFailure call = 1, helper = 1.
- Verified in sandbox: the 12 merge SQL statements, run with Python sqlite3 on the plan's schema, give the expected result (PIN hash, always-on, nuclear, standalone block, block rules, schedule, smaller allowance, network rule all kept). Kotlin list-union (blocked_keywords / vpn_custom_processes) NOT run. Symbols safetyBackup.file, AppPaths.dbFile, Database.close/init, File import confirmed present.
### B2
- New: UninstallProtectionQuitGateTest.kt. Edited: UninstallProtectionService.kt (B2-1), ChangelogScreen.kt (CL-B2). `if (!isDirectInstall) return true` count 4 -> 3 as expected.
### B3
- New: PinResetPathSourceTest.kt. Edited: PinGateDialog.kt (B3-1, B3-2), ChangelogScreen.kt (CL-B3). `allowReset = true` appears only inside the test file; no caller passes it.
- NOT run for any B card: RED checks, GREEN checks, compile, full :test.

## Plan §7 items NOT done (and why)
- N1 beyond E1: sealing the snapshot (DPAPI) and catching edits made while the app runs (H9) are not done. E1 only covers deleted/replaced/reset databases between runs.
- N5 trusted clock: NOT done on purpose. As far as I know the JVM reads the Windows time zone once at start, so a zone change cannot affect a running FocusFlow; changing the clock needs admin. The only real gap is across restarts and needs a persisted floor plus an owner decision on legitimate travel (zone change).
- N3 Nuclear escape list (partial strict list dropped): naive list additions are wrong (500 ms sweep vs one-shot tools; would kill FocusFlow's own taskkill helpers). C2 covers the main case (taskkill/End task against FocusFlow itself).
- N4 monotonic budget idea not done (PIN part is E2). N7 performance (needs the owner's W-6 numbers), N10 variants (O6/O7/O8).

## Out-of-scope observations
(none)

## Windows checks NOT run
W-1 … W-7 (owner), plus W-8 (C2) and W-9 (C1) below

## Extra Windows checks
- W-8 (C2): set settings key process_kill_protection=true (SQLite tool, app closed), start FocusFlow, turn Nuclear Mode ON → Task Manager 'End task' on FocusFlow and `taskkill /f /im FocusFlow.exe` say access denied; Task Manager still lists it normally; turn Nuclear Mode OFF → End task works again; sign out / shut down still works with Nuclear ON; elevated relaunch from the app still works.
- W-9 (C1): direct install: `schtasks /query /tn FocusFlowWatchdog /xml` shows a -File %APPDATA%\FocusFlow\watchdog.ps1 command; kill FocusFlow → it returns within ~2 min; rename any exe to FocusFlow.exe and run it from Downloads, then kill the real one → real one still returns (decoy no longer satisfies it); with FocusFlow running no second window appears every 2 minutes.

- W-10 (D2): in Focus Launcher / Nuclear Mode, copy cmd.exe to Downloads renamed explorer.exe and run it: it is killed; real explorer.exe, taskbar and Start keep working.
- W-11 (D1): run FocusFlow NOT as admin, turn Nuclear Mode on: the Settings row and the notification say it is not running as administrator.
- W-12 (E1): set a Global PIN, wait 2 min, close FocusFlow (tray Quit), delete focusflow.db (+ -wal/-shm), start FocusFlow: the PIN is back and a 'Protections restored' notice appears within ~20 s; Always-On/Nuclear state also back. Then: with the DB deleted again and FocusFlow closed, run the uninstall wizard: it asks for the PIN.
- W-13 (E2): with a PIN set, tray > Emergency Break asks for the PIN; wrong PIN / Cancel starts nothing; correct PIN starts it; ending the break needs no PIN; with no PIN set it starts at once.
- W-14 (E3): Settings > Cool-off: switch on for Always-On, turn Always-On off with the PIN: notification 'Cool-off started', still on; ask again: 'Cool-off cancelled'; ask and wait 10 min: it turns off. Same for Nuclear. Turning the cool-off switch itself off also waits 10 min. Default (switches off): everything turns off at once as before.
- W-15 (F1a): start FocusFlow; enforcement.log no longer has 'Session notification setup failed'; instead 'Lock/unlock notifications registered'; Win+L then unlock logs 'Windows session locked/unlocked'.
- W-16 (F1b): on the Store build the next enforcement.log shows the real schtasks text and commandLength after 'FocusFlowWatchdog exited with code': send that line. Also check whether %APPDATA%\FocusFlow\taskbar-guard.ps1 exists in the REAL folder (not under Packages\...\LocalCache\Roaming).
