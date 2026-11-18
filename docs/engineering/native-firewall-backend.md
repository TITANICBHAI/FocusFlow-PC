# Follow-up: native Windows Firewall backend (not implemented)

**Status:** design note. Deliberately not built in this change, because it cannot be validated without a Windows host.

## Why
`PowerShellFirewallBackend` costs 1–3 s per run (PowerShell start-up plus the NetFirewall cmdlets), needs a PowerShell process, and is the only reason FocusFlow has to keep a *helper* PowerShell alive next to an enforcement loop that kills PowerShell. The helper is now identified and spared (see `EnforcementTrust`), but a backend with no child process removes the whole class of problem.

## Shape
`FirewallBackend` (in `FirewallRuleManager.kt`) is the seam: `apply`, `remove`, `removeAll`, `readActive` (rule name to the program path it points at, or null when unreadable), `close`. A native backend only has to implement that interface; `FirewallRuleManager` (owners, races, queue, back-off) and `NetworkBlocker` do not change.

- API: `INetFwPolicy2` (`HNetCfg.FwPolicy2`) and `INetFwRule`/`INetFwRule3` through COM (JNA `com.sun.jna.platform.win32.COM` late binding, already a project dependency family).
- `apply`: create `INetFwRule` (Name/Grouping `FocusFlow_Block_<name>`, Direction=OUT, Action=BLOCK, ApplicationName=<path>, Profiles=ALL, Enabled=true), `Rules.Add`, then read the rule back by name and check Enabled/Direction/Action and that ApplicationName is the requested path (the manager's re-verification depends on `readActive` reporting it) — the same "confirmed only after read-back" rule as today.
- `remove`/`removeAll`/`readActive`: enumerate `Rules` and filter by the `FocusFlow_Block_` prefix.
- `close`: COM calls are not interruptible; run them on a dedicated thread and abandon it on shutdown.

## Risks to validate on Windows before shipping
- COM apartment/threading (STA vs MTA) with JNA; every call from one thread.
- Behaviour when Windows Firewall service is stopped or policy is managed by Group Policy.
- Enumerating thousands of rules is slow; prefer `Rules.Item(name)` lookups.
- Parity of read-back semantics with `Get-NetFirewallRule` for rules created by older versions.

## Acceptance
Run the same `FirewallRuleManagerTest` behaviours against a real-firewall integration test on a Windows CI runner (add, confirm, release by owner, race with release, `removeAll`), and compare latency against the PowerShell backend.
