---
name: Enforcement PIN ownership
description: Which PIN should guard persistent enforcement controls and why the distinction matters.
---

Persistent enforcement controls such as Always-On Enforcement and removing or disabling blocked-app rules must use the Global PIN. The Session PIN is only for ending an active focus session.

**Why:** Mixing the two PINs makes a persistent protection setting either unexpectedly bypassable or unnecessarily tied to a single focus session.

**How to apply:** When adding or changing a guard around a persistent block, schedule, network, or enforcement setting, verify GlobalPin; reserve SessionPin for session lifecycle actions.