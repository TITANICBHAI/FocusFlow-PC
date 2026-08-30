---
name: Focus Launcher task brief
description: Imported requirements and current comparison for the planned Focus Launcher window migration.
---

The workspace contains an imported, dependency-ordered Focus Launcher task
brief. Its intended product direction is a dedicated undecorated launcher
window on each monitor, foreground-aware Z-order changes, a taskbar guard,
configurable breaks, and a session PIN shown once before a session starts.

**Why:** The existing application has a legacy single-window overlay, so future
Focus Launcher work must be judged against the imported brief rather than
assuming the existing overlay is the target architecture.

**How to apply:** Read the root brief and its tracking comparison before
implementing or reviewing Focus Launcher changes. Keep the task order
dependency-aware and do not mark the Windows runtime checklist complete from a
compile-only result.