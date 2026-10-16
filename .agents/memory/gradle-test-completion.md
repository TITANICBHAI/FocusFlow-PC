---
name: Gradle test completion evidence
description: How to confirm Gradle tests actually ran in this workspace.
---

Treat a Gradle test run as verified only when the output shows the `:test` task and a final `BUILD SUCCESSFUL` result, and the generated test XML reports match the expected suite. If a command ends after `testClasses` or times out during compilation, it has not established a test result. For long runs, use a background command, monitor for the final Gradle result, and inspect test report counts.

**Why:** On 2026-10-14, foreground multi-project `gradle test` invocations returned before the root `:test` task after `:recovery:test NO-SOURCE`. On 2026-10-17, an unfiltered `:test` invocation after filtered suites returned without a final build summary while XML still contained only the filtered suites; forcing the rerun and recounting XML established the full result.

**How to apply:** If the multi-project command stops around a no-source subproject, target `:test` explicitly in a background shell. After targeted `--tests` runs, force a whole-suite rerun with `--rerun-tasks`; require a final `BUILD SUCCESSFUL` result and XML suite totals matching the intended scope.
