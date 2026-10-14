---
name: Gradle test completion evidence
description: How to confirm Gradle tests actually ran in this workspace.
---

Treat a Gradle test run as verified only when the output shows the `:test` task and a final `BUILD SUCCESSFUL` result, and the generated test XML reports match the expected suite. If a command ends after `testClasses` or times out during compilation, it has not established a test result. For long runs, use a background command, monitor for the final Gradle result, and inspect test report counts.

**Why:** On 2026-10-14, multiple foreground Gradle invocations returned after `testClasses` without a `:test` result or test reports, while a forced background test run completed and produced the expected XML reports.

**How to apply:** Use explicit test filters for targeted verification, force reruns when Gradle may consider prior filtered outputs up to date, and do not describe an incomplete or report-less run as passing.
