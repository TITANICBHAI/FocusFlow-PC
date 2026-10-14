# Phase 0 — Baseline and tooling report

- **Date:** 2026-10-14
- **Status:** Complete — baseline recorded, all helper smoke tests pass, and this report is complete. No production-code changes were made.

## Work completed

- Rechecked the current test sources for default-profile database access and network paths before running anything.
- Confirmed every `Database.init` call in `DatabaseTest` supplies an explicit file beneath JUnit `@TempDir`; the standalone sqlite-jdbc smoke test also uses `@TempDir`.
- Found no direct HTTP or telemetry-client references in `src/test`.
- Found an indirect local TCP path: `SingleInstanceGuardTest` calls `SingleInstanceGuard.acquire()`, whose implementation binds a `ServerSocket` to `InetAddress.getLoopbackAddress()` and connects a `Socket` to that same loopback address for the SHOW handoff. The owner authorized this loopback-only IPC for the baseline; external network and telemetry calls remain prohibited.
- Verified `Database.finishFailed()` calls `CrashReporter.reportCritical()` only when the failed path equals the default DB path. Existing `DatabaseTest` initialization failures use explicit `@TempDir` files and do not satisfy that gate.
- Updated the tracker and `AUDIT-ADDENDUM.md` with the safety boundary and evidence.
- Added test-only `TempHome`, `ForkedJvm`, and `StatementLeakAssert` helpers and three helper smoke-test classes under `src/test/kotlin/com/focusflow/testutil/`.
- Left `DatabaseTest`, production sources, build configuration, the clock seam, and `FakeTelemetrySink` unchanged/deferred.

## Baseline result

- **Command:** `export JAVA_HOME=/nix/store/c8hr2f0b0dm685yx1dkp6bw24bpx495n-graalvm19-ce-22.3.1; export PATH="$JAVA_HOME/bin:$PATH"; gradle :test --no-daemon`
- **Result:** `:test` ran without exclusions or test-source edits; Gradle reported `BUILD SUCCESSFUL`.
- **Counts:** 98 tests; 0 failures, 0 errors, 0 skipped. Parsed all 19 `TEST-*.xml` files under `build/test-results/test/`; combined JUnit test time was 1.601 s.
- **Duration:** 160,540 ms wall time measured around the command (Gradle printed `BUILD SUCCESSFUL in 2m 37s`).
- **Actual test-worker JDK:** GraalVM CE 22.3.1, OpenJDK 19.0.2 (`19.0.2+7-jvmci-22.3-b12`), observed from the test-worker executable under the configured GraalVM 19 store path.
- **Gradle JVM comparison:** Gradle 8.14.2 launcher JVM was Oracle 21.0.7; daemon JVM was OpenJDK 21.0.7+6. The test worker did use the Java 19 toolchain, confirming the JVMs differ.
- **Database safety:** Source review found no test path to the real/default database; database tests use explicit temporary files.
- **External network safety:** No direct external HTTP/telemetry client reference was found. The test DB-failure telemetry call is gated to the app's default DB path; tests use temporary paths. Local loopback is the only known socket activity and is allowed for this run.
- **Observed non-failing shutdown output:** The test JVM printed `Exception in thread "TimerQueue": java.lang.IllegalStateException: Shutdown in progress` from AWT `XToolkit` while exiting. The Gradle task succeeded and the JUnit XML contains no errors; this is recorded, not fixed in Phase 0.
- **P0-3:** The owner reports that a Claude agent running in Claude's app produced the reports; the underlying host machine/OS is unknown. The owner does not know the `%TEMP%`/security-software context. No raw crash-log excerpt or raw user path was requested or inspected.

## Helper smoke tests and final suite

- **Targeted command:** `gradle :test --no-daemon --tests 'com.focusflow.testutil.*'`
- **First targeted run:** 4 tests; 1 failed. The intentional leak probe created a statement but did not execute it, so the SQLite driver's `DB.stmts` registry did not count it and the expected assertion was not raised.
- **Correction and rerun:** The probe now executes `SELECT 1` while leaving the statement open. The same targeted command then passed all 4 tests, with 0 failures, errors, or skips. The test closes the leaked statement in `finally`.
- **Final full-suite command:** `export JAVA_HOME=/nix/store/c8hr2f0b0dm685yx1dkp6bw24bpx495n-graalvm19-ce-22.3.1; export PATH="$JAVA_HOME/bin:$PATH"; gradle :test --no-daemon`
- **Final result:** `BUILD SUCCESSFUL`; 102 tests, 0 failures, 0 errors, 0 skipped across 22 JUnit XML files in `build/test-results/test/`. Combined JUnit test time was 2.422 s; measured command wall time was 13,009 ms.
- **Helper coverage:** `TempHomeTest` verifies scratch home/properties and restoration after normal and exceptional exits; `ForkedJvmTest` verifies the child JVM receives scratch-home, app-version, and telemetry-off properties without mutating the parent; `StatementLeakAssertTest` verifies stable closed-statement cycles and detection of an executed open statement.
- **Inspection note:** `javap` was not available on the shell `PATH`; the helper followed the existing read-only `Repro3.kt` reflection pattern and was validated by the runtime smoke test. No package or toolchain was installed.
- **Scope check:** `git diff --check` passed. No production file or existing `DatabaseTest` edit is present. The helpers make no network calls; the full suite used only the previously authorized loopback IPC.

## Toolchain observation

Commands used:

- `export JAVA_HOME=/nix/store/c8hr2f0b0dm685yx1dkp6bw24bpx495n-graalvm19-ce-22.3.1; export PATH="$JAVA_HOME/bin:$PATH"; java -version`
- `export JAVA_HOME=/nix/store/c8hr2f0b0dm685yx1dkp6bw24bpx495n-graalvm19-ce-22.3.1; export PATH="$JAVA_HOME/bin:$PATH"; gradle --version`

Results:

- `java -version`: GraalVM CE 19.0.2.
- `gradle --version`: Gradle 8.14.2; launcher JVM 21.0.7 and daemon JVM 21.0.7+6.
- `build.gradle.kts` sets the Kotlin toolchain to Java 19.
- The test worker was separately observed using GraalVM CE 22.3.1 / OpenJDK 19.0.2. Do not infer worker runtime from the Gradle launcher/daemon JVM.

## Files reviewed

- Planning/status: `fixes/plan.md`, `fixes/tracker.md`, `fixes/preagent-prompt.md`, `.agents/memory/gradle-test-completion.md`
- Build/environment: `build.gradle.kts`, `gradle.properties`, `.gitignore`
- Audit record: `AUDIT-ADDENDUM.md`
- Database/network tests and implementation: `src/test/kotlin/com/focusflow/data/DatabaseTest.kt`, `src/test/kotlin/com/focusflow/data/SqliteJdbcNativeSmokeTest.kt`, `src/test/kotlin/com/focusflow/services/SingleInstanceGuardTest.kt`, `src/main/kotlin/com/focusflow/services/SingleInstanceGuard.kt`, relevant `Database.kt` ranges
- Statement registry reference: `fixes/focusflow-audit-evidence/harness/Repro3.kt` (read only; not run)
- Static scan: all existing Kotlin test sources under `src/test`

## Files added or updated

- `src/test/kotlin/com/focusflow/testutil/TempHome.kt`
- `src/test/kotlin/com/focusflow/testutil/ForkedJvm.kt`
- `src/test/kotlin/com/focusflow/testutil/StatementLeakAssert.kt`
- `src/test/kotlin/com/focusflow/testutil/TempHomeTest.kt`
- `src/test/kotlin/com/focusflow/testutil/ForkedJvmTest.kt`
- `src/test/kotlin/com/focusflow/testutil/StatementLeakAssertTest.kt`
- `fixes/tracker.md`, `AUDIT-ADDENDUM.md`, and `.agents/memory/gradle-jvm-selection.md`

## Remaining context

- The report source was identified as a Claude agent in Claude's app, but its host machine/OS remains unknown. `%TEMP%`/`%TMP%` and security-software context are unknown. No raw crash-log excerpt or user path was collected.
- Windows verification items W1–W15 remain unverified; Phase 0 does not claim Windows behavior was tested.
- `FakeTelemetrySink` remains deferred until F4.0, and the clock seam remains deferred until F6.

**Phase 0 exit: COMPLETE.** Baseline recorded, helper smoke tests passed, and this report is complete.
