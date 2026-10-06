---
name: Enforcement PIN ownership
description: Which PIN should guard persistent enforcement controls and why the distinction matters.
---

Persistent enforcement controls such as Always-On Enforcement and removing or disabling blocked-app rules must use the Global PIN. The Session PIN is only for ending an active focus session. Allowance changes must remain disabled until both database readiness and Global PIN state are confirmed; an unavailable PIN lookup is not equivalent to "no PIN set."

**Why:** Mixing the two PINs makes a persistent protection setting either unexpectedly bypassable or unnecessarily tied to a single focus session. Database-unavailable PIN reads can return false, so treating an unknown PIN state as unset would let users bypass the configured guard.

**How to apply:** When adding or changing a guard around a persistent block, schedule, network, enforcement setting, or allowance, verify GlobalPin; reserve SessionPin for session lifecycle actions. Keep mutations disabled until the PIN lookup succeeds and recheck DB readiness on the IO write path.