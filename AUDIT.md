# FocusFlow Stability Audit Guide

This document is a practical workflow for investigating and fixing crashes,
UI hangs, deadlocks, coroutine leaks, and shutdown failures in FocusFlow.

## Project context

FocusFlow is a Windows productivity application built with:

- Kotlin/JVM
- Compose Multiplatform Desktop
- Kotlin coroutines
- SQLite
- JNA and Win32 APIs
- Windows process monitoring and process termination
- Windows Firewall and registry operations
- AWT system tray
- JVM shutdown hooks

Google AI Studio can help analyze the repository and reason about stack traces,
but it cannot reliably reproduce Windows-specific behavior from a
Linux/cloud environment. Every proposed fix must be tested on Windows.

Never upload secrets, private keys, signing certificates, `.env` files, API
keys, webhook URLs, or values such as `SESSION_SECRET`.

## Recommended workflow

1. Collect real crash reports and hang evidence.
2. Ask Google AI Studio for an audit only; do not ask it to modify the whole
   project immediately.
3. Fix one confirmed or high-confidence issue at a time.
4. Compile after every patch.
5. Test the patch on Windows.
6. Re-test the enforcement and shutdown paths before moving to another issue.

The most useful evidence is:

- Full stack trace
- FocusFlow version
- Windows version and architecture
- What the user was doing
- Whether the issue happened during startup, normal use, enforcement, or exit
- Reproduction frequency
- Relevant application logs
- Three thread dumps for a hang

## Prompt 1 — Repository stability audit

Use this first. Ask for analysis only and do not allow broad code changes yet.

```text
You are a senior Kotlin/JVM and Compose Multiplatform Desktop stability engineer.

This is FocusFlow, a Windows productivity app built with:
- Kotlin/JVM
- Compose Multiplatform Desktop
- Kotlin coroutines
- SQLite
- JNA/Win32 APIs
- Windows process monitoring and process killing
- Windows Firewall and registry operations
- AWT system tray
- JVM shutdown hooks

The app experiences unexplained crashes and hangs.

Do not modify any files yet. Perform a stability audit only.

Analyze the repository for likely causes of:
1. Application crashes
2. UI freezes
3. Deadlocks
4. Coroutine leaks
5. Background jobs that do not cancel
6. Database calls on the Compose/UI thread
7. Blocking ProcessBuilder calls on the UI or AWT thread
8. Unsafe JNA/native calls
9. Shutdown-order races
10. Unhandled exceptions inside coroutines or callbacks
11. System tray/AWT thread failures
12. Windows-only failures that would not reproduce on Linux

Pay special attention to:
- Main.kt
- App.kt
- CrashReporter.kt
- Database.kt
- ProcessMonitor.kt
- NuclearMode.kt
- FocusLauncherService.kt
- RegistryLockdown.kt
- NetworkBlocker.kt
- WinEventHook.kt
- GlobalKeyboardHook.kt
- all services with CoroutineScope, Job, Thread, ProcessBuilder, or shutdown hooks

Return a prioritized report with this format:

| Priority | Category | File and line | Evidence | Failure scenario | Confidence | Minimal fix |
|---|---|---|---|---|---|---|

Separate:
- Confirmed bugs
- High-probability bugs
- Things that require runtime evidence

Do not recommend a rewrite, architecture migration, or replacing Compose.
Do not change behavior of app blocking or Nuclear Mode unless absolutely necessary.
```

## Prompt 2 — Analyze one crash

Use the complete stack trace, not only the exception name.

```text
Analyze this FocusFlow crash.

Do not guess from the exception name alone. Trace the stack from the first
application-owned frame and identify the root cause.

Repository context:
- Kotlin/JVM Compose Desktop Windows app
- JNA and Win32 APIs
- SQLite
- Coroutines
- System tray and shutdown hooks

Crash details:
Version:
Windows version:
What the user was doing:
Reproduction frequency:

Stack trace:
[PASTE THE FULL STACK TRACE HERE]

Return:
1. Root cause
2. Exact application-owned file and line
3. Why this happens
4. Whether it is UI-thread, coroutine, native/JNA, database, or shutdown related
5. Minimal safe fix
6. Regression risk
7. A test or reproduction procedure

Do not propose unrelated refactoring.
Do not swallow the exception unless you explain why that is safe.
```

## Prompt 3 — Analyze a hang with thread dumps

Capture three dumps while the app is frozen, approximately 5–10 seconds apart:

```text
jstack <PID> > dump-1.txt
timeout /t 5
jstack <PID> > dump-2.txt
timeout /t 5
jstack <PID> > dump-3.txt
```

Use the `jstack.exe` bundled with the app's Java runtime if `jstack` is not
available on `PATH`.

Then use:

```text
These are three thread dumps from the same FocusFlow process while the UI
appeared frozen.

Compare the dumps carefully.

Identify:
- The Compose/UI thread
- The AWT Event Dispatch Thread
- Coroutine worker threads
- Threads blocked in Database, SQLite, ProcessBuilder, waitFor, readText,
  JNA, registry, firewall, or synchronized blocks
- Deadlocks
- Lock ownership
- Repeated identical stack frames
- Native calls that do not return
- Whether the UI is actually blocked or whether only enforcement is stuck

Do not call something a deadlock from one dump alone.
Use all three dumps to distinguish:
1. deadlock
2. long-running blocking operation
3. infinite loop
4. thread starvation
5. normal idle state
6. native Windows API stall

Return:
- Most likely hang cause
- Evidence from all three dumps
- Exact code location
- Minimal fix
- How to verify the fix

FocusFlow source and dumps:
[UPLOAD OR PASTE THE RELEVANT SOURCE AND ALL THREE DUMPS]
```

## Prompt 4 — Implement one narrow fix

Use this only after obtaining a diagnosis.

```text
Implement only the specific stability fix described below.

Before changing code:
1. Confirm the suspected root cause from the repository.
2. Identify all callers and thread contexts.
3. Explain why the proposed change will not affect app blocking, Nuclear Mode,
   Focus Launcher, local database behavior, or crash reporting.

Requirements:
- Make the smallest safe patch.
- Preserve the existing Kotlin/Compose architecture.
- Do not introduce a new framework.
- Do not replace real enforcement with a mock or fallback.
- Do not silently swallow exceptions.
- Do not use runBlocking on UI, AWT, or shutdown-sensitive paths.
- Move blocking database/process/firewall work off the UI thread where appropriate.
- Preserve cancellation and shutdown behavior.
- Add or update a regression test if possible.
- Show the complete diff.

Bug diagnosis:
[PASTE THE DIAGNOSIS HERE]
```

## Prompt 5 — Review a proposed patch

Run this as a separate review after AI Studio proposes a change.

```text
Review the proposed patch as a skeptical stability engineer.

Look specifically for:
- New race conditions
- Coroutine cancellation mistakes
- UI-thread blocking
- AWT thread blocking
- Shutdown regressions
- Stale StateFlow reads
- Missing @Volatile or atomic operations
- Double cleanup
- Process handles or streams not being closed
- Windows-only behavior changes
- Swallowed exceptions

Do not rewrite the patch. List concrete problems only, with file and line
references. If the patch is safe, explain why each risk area is covered.
```

## High-risk areas to investigate first

### Database and UI threading

SQLite work should not run directly on the Compose UI thread. Look for
synchronous database reads or writes inside composables and UI callbacks.

### External process calls

Inspect `ProcessBuilder` calls involving:

- `waitFor()`
- `readText()`
- `tasklist`
- `taskkill`
- PowerShell
- Windows Firewall commands
- Registry commands

A slow or non-returning Windows process can make the application appear frozen
if called from the UI or AWT thread.

### AWT system tray

A slow database, firewall, registry, or process operation inside a tray callback
can freeze the AWT Event Dispatch Thread and make the whole application appear
unresponsive.

### JNA and Windows hooks

Pay particular attention to:

- `WinEventHook`
- `GlobalKeyboardHook`
- registry calls
- taskbar manipulation
- process enumeration
- native callback lifetimes

### Shutdown and cleanup

FocusFlow has multiple services, jobs, native hooks, firewall cleanup, registry
cleanup, and shutdown hooks. Check:

- Cleanup ordering
- Jobs that are cancelled but never joined
- Non-daemon threads
- Blocking cleanup on the UI/AWT thread
- Duplicate cleanup from multiple paths
- Shutdown races between normal exit and crash recovery

### Coroutine lifecycle

Look for jobs that:

- Are launched but never cancelled
- Are cancelled from the wrong thread
- Continue after a screen or session ends
- Access mutable state without synchronization
- Perform blocking work on the wrong dispatcher
- Catch and discard exceptions without logging

### Nuclear Mode and Focus Launcher transitions

These paths combine process killing, firewall changes, registry lockdown,
keyboard hooks, taskbar changes, and cleanup. Test:

- Entering and leaving Focus Launcher
- Enabling and disabling Nuclear Mode
- Starting and ending an Emergency Break
- Taking a Focus Launcher break
- Closing the app during each transition
- Restarting after a forced termination

## Windows regression checklist

After every stability fix, test at least:

- Normal startup
- Startup after forced termination
- System tray minimize and restore
- App blocking
- Focus session start, pause, resume, and end
- Nuclear Mode enable and disable
- Focus Launcher enter and exit
- Emergency Break activation and expiry
- Standalone block start and stop
- Website/network blocking
- Settings changes
- Normal application exit
- Exit during active enforcement
- Exit while a firewall or registry operation is running

The safest approach is evidence first, one narrow patch second, and Windows
verification third.