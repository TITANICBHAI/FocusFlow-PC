---
name: Enforcement helper trust
description: Nuclear Mode and the Focus Launcher must tell FocusFlow's own helpers from a person's escape tools by identity, never by image name, parent PID or folder.
---
- Never `taskkill /IM <name>` an escape tool. It ends FocusFlow's own firewall PowerShell and the Watchdog's scheduled-task PowerShell too. Kill by PID via `killProcessesByPid` (`Taskkill`), after `ProcessSnapshot.isAlive(pid, creationTimeMs)`.
- A helper FocusFlow starts must go through `OwnedProcesses.start(builder, expectedImage)` with the ABSOLUTE tool path (`SystemTools.path(...)`, `SystemTools.powerShell()`). It is recorded by (PID, creation time, exact image) in `OwnedProcessRegistry` and released on exit. A bare `powershell` / `taskkill` name cannot be matched.
- Scheduled-task runs (Watchdog) are started by Windows, so they are recognised by `TaskRunTrust`: exact System32 powershell.exe, exact arguments from `WatchdogScript`'s builders, parent conhost/svchost in System32, and (script runs) the script file's SHA-256. Fail closed on anything unknown.
- Do NOT exempt PowerShell broadly, all children, a folder, or "any process with parent X". Tests in `enforcement/*Test.kt` mutation-check these rules.
- Firewall rules have owners (`FirewallOwner`: NUCLEAR / USER / SESSION / RECOVERED). Session end calls `NetworkBlocker.removeSessionRules()`, never `removeAllRules()` (that is for exit/uninstall).
- Any new `ProcessBuilder` for a Windows tool should use `SystemCommand.run` (timeout, off-thread read, owned child).
- Windows runtime behaviour of all of this was NOT verified in the authoring sandbox (no Windows/PowerShell); see docs/engineering/enforcement-reliability-report.md.

## Firewall rules are paths (added with the path-verification change)
- A rule only blocks the file it points at. `FirewallRuleManager` remembers the path each rule was applied with and re-verifies every 10 minutes (use triggers it, the worker does it). `readActive()` returns name -> program path.
- `ResolvedPathCache` expires after 10 minutes and drops a path whose file is gone. Do not go back to a forever-cache: an updated app would keep a rule for its old folder.
- If PowerShell cannot report a rule's path, the rule is accepted by name and logged, not dropped. The `Get-NetFirewallApplicationFilter` read-back has never run on Windows.
