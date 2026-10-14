# Phase 0 — Baseline and tooling report

- **Date:** 2026-10-14
- **Status:** Blocked at P0-1 safety gate; Phase 0 exit criteria are not met.

## Work completed

- Rechecked the current test sources for default-profile database access and network paths before running anything.
- Confirmed every `Database.init` call in `DatabaseTest` supplies an explicit file beneath JUnit `@TempDir`; the standalone sqlite-jdbc smoke test also uses `@TempDir`.
- Found no direct HTTP or telemetry-client references in `src/test`.
- Found an indirect local TCP path: `SingleInstanceGuardTest` calls `SingleInstanceGuard.acquire()`, whose implementation binds a `ServerSocket` to `InetAddress.getLoopbackAddress()` and connects a `Socket` to that same loopback address for the SHOW handoff.
- Updated the tracker and `AUDIT-ADDENDUM.md` with this discrepancy.

## Baseline result

- **Command required:** `gradle :test --no-daemon`
- **Result:** Not run. The suite includes local loopback TCP communication, which is a network call under the current no-network rule. The baseline must remain blocked until the owner resolves whether loopback-only IPC is permitted. No test was excluded or modified.
- **Pass/fail counts and duration:** Not available; the test task did not run.
- **Database safety:** Source review found no test path to the real/default database; database tests use explicit temporary files.
- **External network safety:** No direct external HTTP/telemetry client reference was found. The loopback IPC call remains unresolved under the strict rule.
- **P0-2:** Not started. No helper or helper smoke-test files were added because the required baseline gate did not pass.
- **P0-3:** Owner facts are pending; no raw crash-log excerpt or raw user path was requested or inspected.

## Toolchain observation

Commands used:

- `export JAVA_HOME=/nix/store/c8hr2f0b0dm685yx1dkp6bw24bpx495n-graalvm19-ce-22.3.1; export PATH="$JAVA_HOME/bin:$PATH"; java -version`
- `export JAVA_HOME=/nix/store/c8hr2f0b0dm685yx1dkp6bw24bpx495n-graalvm19-ce-22.3.1; export PATH="$JAVA_HOME/bin:$PATH"; gradle --version`

Results:

- `java -version`: GraalVM CE 19.0.2.
- `gradle --version`: Gradle 8.14.2; launcher JVM 21.0.7 and daemon JVM 21.0.7+6.
- `build.gradle.kts` sets the Kotlin toolchain to Java 19.
- No test-worker JVM was observed because `:test` was not run. Do not infer the test-worker runtime from `JAVA_HOME` or the Gradle toolchain declaration.

## Files reviewed

- Planning/status: `fixes/plan.md`, `fixes/tracker.md`, `fixes/preagent-prompt.md`, `.agents/memory/gradle-test-completion.md`
- Build/environment: `build.gradle.kts`, `gradle.properties`, `.gitignore`
- Audit record: `AUDIT-ADDENDUM.md`
- Database/network tests and implementation: `src/test/kotlin/com/focusflow/data/DatabaseTest.kt`, `src/test/kotlin/com/focusflow/data/SqliteJdbcNativeSmokeTest.kt`, `src/test/kotlin/com/focusflow/services/SingleInstanceGuardTest.kt`, `src/main/kotlin/com/focusflow/services/SingleInstanceGuard.kt`
- Static scan: all existing Kotlin test sources under `src/test`

## Outstanding items

- Owner decision: whether the baseline may include the existing loopback-only IPC test. Until then, do not run `:test`, exclude tests, or change their behavior.
- P0-3: identify the machine/OS that produced the reports and provide any known Windows `%TEMP%`/`%TMP%` or security-software context. Share only reviewed/redacted excerpts if an excerpt is later needed; omit raw paths and secrets.
- After the P0-1 gate is resolved, continue with the baseline, then add and smoke-test `TempHome`, `ForkedJvm`, and `StatementLeakAssert`.
- Windows verification items W1–W15 remain unverified.
