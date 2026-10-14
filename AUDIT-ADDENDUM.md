# FocusFlow Database Audit Addendum

- **Review date:** 2026-10-14
- **App version at review:** 2.0.5
- **Source plan:** [`fixes/plan.md`](fixes/plan.md)
- **Status:** Static source/test-inventory review plus the Phase 0 root test baseline. No application code, audit scripts, external telemetry, or Windows checks were run.

## Current-source corrections

- **F26 — close as stale:** `settings.gradle.kts` includes the `recovery` project, and the current checkout contains `recovery/build.gradle.kts`. The audit archive omitted a module that exists in the current project. No removal or Gradle change is warranted.
- **F3 — partially already implemented:** local crash reports already include a full exception chain. The startup gate displays only the immediate exception class/message, and remote stack text is truncated. Keep only missing local/JUL/UI work before F4; do not add more remote diagnostics until consent and redaction are enforced.
- **F15 — partially already implemented:** backups use `VACUUM INTO` and SHA-256 sidecars. The hash does not validate SQLite structure; legacy hashless backups return verified, backup failures may be ignored, and `restoreBackup()` has no current callers. Keep targeted validation, failure reporting, and deprecation; do not replace the working snapshot mechanism.
- **F17 — adjust proposed hook:** `ProcessMonitor.refreshCaches()` runs every two seconds but does not refresh `VpnBlocker` settings. Its current one-time cache can retain a failed-read default indefinitely; design an explicit refresh path rather than assuming the monitor already owns it.
- **F27 — environment mismatch:** current Gradle targets JDK 19 and EXE/MSI packages; it does not configure an MSIX target. The plan’s JDK 21 source analysis and MSIX scenario do not establish behavior for this build. Test the actual shipped JDK/package on Windows before changing the flag.
- **F28 — test-only gap:** the normal application JVM receives `focusflow.app.version` from Gradle, while `tasks.test` sets no version or telemetry properties. Configure test JVM properties; preserve the production fail-fast unless the owner approves a fallback.
- **F29 — coverage correction:** `SqliteJdbcNativeSmokeTest` exists and checks normal SQLite use plus the Windows x64 DLL resource. It does not cover restricted temp paths or the broader missing finding-specific cases.
- **Gradle verification:** `gradle help --no-daemon` succeeds with configured JDK 19 and Gradle 8.14.2. The checked-in `gradlew` script cannot launch because `gradle/wrapper/gradle-wrapper.jar` is absent; use the configured system Gradle command for this work, without generating a wrapper as unrelated scope.
- **F6/F9/F14 — dependency correction:** F6’s v10 migration needs a tested new-column guard before it is added. F6/F9 live-data repairs must wait for F15’s verified backup path and dry runs; F14’s legacy-migration audit must not edit v1–v9.
- **F5/F11 — order correction:** F5’s uninstall policy can be decided early, but its implementation/sign-off depends on F11’s read-only wizard path. Complete F11 before F5; keep D4 owner-gated.
- **F3/F4 — privacy order correction:** implement F3’s local cause details first. Do not expand remote diagnostic payloads until F4’s fail-closed consent and PII redaction are active.

## Test safety observation

The current test task does not set `focusflow.app.version` or `focusflow.telemetry=off`. The source scan found existing `Database.init` calls in `DatabaseTest` use explicit temporary DB files, and no direct telemetry/network client references in `src/test`. Recheck before the untouched-tree baseline; no new default-path failure-injection test may run until F4.0 provides an effective no-op/fake sink.

## Phase 0 safety recheck — 2026-10-14

- **Verified:** `DatabaseTest` uses JUnit `@TempDir` and passes explicit temporary DB files to every `Database.init` call; `SqliteJdbcNativeSmokeTest` also uses `@TempDir`. No direct external HTTP or telemetry-client references were found in `src/test`.
- **Safety discrepancy:** `SingleInstanceGuardTest` calls `SingleInstanceGuard.acquire()`. That implementation starts a `ServerSocket` bound to `InetAddress.getLoopbackAddress()` and exercises a `Socket` handoff to the same loopback address. This is local-only IPC, not an external endpoint, but it is still a TCP network call under the literal test rule.
- **Decision/effect:** The full `gradle :test --no-daemon` baseline was not run. No helpers, helper tests, or production changes were made. The owner left the loopback permission unanswered and said to use best judgment; this does not explicitly permit a network call, so Phase 0 remains blocked under the strict no-network rule. Do not silently exclude or change the existing test.
- **Owner clarification (2026-10-14):** The owner authorized loopback-only IPC for the baseline; external network and telemetry calls remain prohibited. `Database.finishFailed()` calls `CrashReporter.reportCritical()` only when the failed path matches the default DB path; existing `DatabaseTest` failures pass explicit `@TempDir` paths and do not reach this telemetry call. The baseline is now proceeding without test-source changes.
- **Baseline result (2026-10-14):** Root `gradle :test --no-daemon` passed with 98 tests, 0 failures/errors/skips, and 19 JUnit XML files. The test worker used GraalVM CE 22.3.1 / OpenJDK 19.0.2; Gradle launcher/daemon used JDK 21.0.7. See `fixes/PHASE-0-REPORT.md` for duration and the non-failing AWT shutdown exception.
- **Runtime observation:** With the workflow's GraalVM `JAVA_HOME`, `java -version` reports GraalVM CE 19.0.2. `gradle --version` reports Gradle 8.14.2 with launcher JVM 21.0.7 and daemon JVM 21.0.7+6; `build.gradle.kts` configures Kotlin's toolchain as Java 19. No test-worker JVM was observed because tests were not run.
- **P0-3 owner facts:** The owner says the reports were produced by a Claude agent running in Claude's app; the underlying execution host/OS is unknown. The owner does not know the `%TEMP%`/security-software context. No crash-log excerpt or raw user path was requested or inspected.

## F7 live-source recount — 2026-10-15

- The current `Database.kt` has 43 `createStatement()` calls: 17 are immediately scoped with `.use`, and 26 are not. Of the unscoped calls, 19 execute queries and 7 execute updates.
- This differs from the plan's 18 query / 7 update estimate. The extra query is the second `PRAGMA user_version` read inside the migration transaction (`lockedVersion`); it is included in F7's closure and scanner acceptance checks.

