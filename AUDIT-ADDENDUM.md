# FocusFlow Database Audit Addendum

**Review date:** 2026-10-14  
**Current app version:** 2.0.5  
**Source plan:** [`fixes/plan.md`](fixes/plan.md)  
**Status:** Static source/test-inventory review only. No application code, tests, audit scripts, telemetry, or Windows checks were run.

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

