---
name: Nix process names in tracker tests
description: ProcessHandle can report the Nix coreutils binary for shell aliases such as sleep.
---

On Replit's Linux/Nix environment, a running `sleep` process appeared through `ProcessHandle.Info.command()` as a path ending in `coreutils`, even though `ps` showed `sleep`. Matching the executable basename to an allowance named `sleep` therefore did not find that process. A distinct executable such as `python3.13` worked for a real-process smoke test; fake process data is more deterministic for engine tests.

**Why:** A process-name mismatch initially looked like a tracker regression, but came from how the shell alias resolved to Nix coreutils.

**How to apply:** Before diagnosing tracker behavior from a single CLI alias, inspect the full `ProcessHandle` command path or supply a fake process source.
