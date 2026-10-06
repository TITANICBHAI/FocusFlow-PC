---
name: Silent startup recovery gate
description: Product expectation for normal startup and the failure-only recovery window.
---

Do not show a loading window while JVM and database initialization proceed normally. Show a recovery window only when a real startup failure or blocked startup requires user action; keep retry progress inside that recovery window.

**Why:** the user said JVM startup is already slow and an additional loading screen is annoying, and repeated that the window should appear only if a check fails.

**How to apply:** when changing startup UI, keep routine startup invisible while retaining actionable diagnostics for database, instance-lock, or service failures.
