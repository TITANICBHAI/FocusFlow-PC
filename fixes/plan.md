# FocusFlow Desktop — SQLite / Database Fix Plan

**For:** the coding agent working in the FocusFlow Desktop repo (Kotlin 1.9.22, Compose Multiplatform 1.6.1, sqlite-jdbc 3.47.1.0).
**Audited build:** `appVersion=2.0.3` from `790bdc9a-focusflowpc.zip`. **Audit date:** 2026-10-11.
**Author:** Claude (analysis only). No project file was modified during the audit. Line numbers below refer to the audited sources; if they have drifted, locate by symbol name.

> Read this whole file before you touch any code. Then follow the phases in order. Anything marked **OWNER DECISION** is not yours to decide: stop that task and ask Himanshu.

**Contents:** §0 summary · §1 how to work + evidence labels · §2 **rules R1–R16** · §3 root cause of the SQLite error · §4 findings register F1–F30 · §5 Phase 0 · §6 Phase 1 (F1–F5) · §7 Phase 2 (F6–F19) · §8 Phase 3 (F20–F30) · §9 Windows checklist · §10 owner decisions · §11 out of scope · Appendix A reproduction evidence · B upstream facts · C unverified items · D coverage.

---

## 0. One-screen summary

1. **The two reported errors are one failure seen twice.** The sqlite-jdbc *native library* (`sqlitejdbc.dll`) never loaded in that JVM.
   - First `Database.init()` → `java.sql.SQLException: Error opening connection` (Trace A).
   - Clicking **Retry** → `java.lang.UnsatisfiedLinkError: 'void org.sqlite.core.NativeDB._open_utf8(byte[], int)'` (Trace B). It is an `Error`, so `catch (Exception)` in `Database.kt:377` and `Main.kt:221` does not catch it and the app crashes.
   - Reproduced two different ways on the real app code + real sqlite-jdbc 3.47.1.0 sources (Appendix A). **Mechanism: verified.**
2. **Why the native library failed on the affected machine is NOT known.** I could not see the machine. §3.4 lists the three facts that settle it.
3. The app turns an environment problem into a **dead end**: the real reason is invisible to the user and to telemetry, Retry cannot succeed, telemetry is sent without consent, and on direct (EXE/MSI) installs the uninstall wizard refuses to run (F1–F5).
4. Beyond that, the database layer has **real, reproduced defects** (not hypothetical): recurring tasks double every day (F6), JDBC statements leak on a 2-second hot path (F7), PIN/protection checks fail *open* when a read fails (F8), orphaned "active session" rows (F9), transactions that are committed half-done when an `Error` interrupts them (F10).
5. Work order: **Phase 0** (tooling) → **Phase 1** (F28 and F4.0 first, then F2 → F1 → F3 → F4 → F5) → **Phase 2** (F6–F19) → **Phase 3** (F20–F30, cleanup). Blocking owner decisions are in §10; Windows-only checks the agent cannot do are in §9.
6. **Safety trap for whoever runs tests:** before F4, any test that makes `Database.init` fail on the default path makes `CrashReporter` POST to the production Discord webhook (R9, F4.0, F28). Do the seam first.

---

## 1. How to work with this plan

- **Order matters.** Phase 1 first. Inside a phase, take tasks in numeric order unless a task says otherwise.
- **One finding = one branch = small commits.** Failing test first, then the fix, then the passing test.
- **Every task has the same shape:** *Problem → Evidence → Root cause → Required change → Must NOT → Tests → Acceptance.* A task is done only when every acceptance line is true and you have written its entry in the phase report (R15).
- **If this plan disagrees with the code, the code wins.** Stop the affected task, record the discrepancy in `AUDIT-ADDENDUM.md` (new file at repo root), and move on (R10).
- **You are probably not on Windows.** `gradle.properties` points at a Nix GraalVM path, so you may be developing on Linux/Replit. Linux runs prove logic, **not** Windows behaviour. Anything marked **WIN-VERIFY** goes to the human checklist in §9. You may not tick those boxes.

### 1.1 Evidence labels used everywhere in this file

| Label | Meaning |
|---|---|
| **[V-repro]** | I ran code in a sandbox (Linux, OpenJDK 21.0.12) against the app's real classes and observed the behaviour. Details and numbers: Appendix A. |
| **[V-src]** | I read it in the sqlite-jdbc **3.47.1.0** source tag (Appendix B). |
| **[V-code]** | I read it in FocusFlow v2.0.3 source; the behaviour follows from the code but I did not execute that exact path. |
| **[INF]** | Inference from verified facts. Could be wrong; the task says how to confirm. |
| **[UNV]** | Not verified. Treat as a hypothesis. |

### 1.2 Confidence in the headline claims

| Claim | Confidence | Why |
|---|---|---|
| Both traces = native-library load failure + app-level handling gaps | 0.95 | Line-exact match of both traces to app and sqlite-jdbc sources; reproduced two ways. |
| Specific trigger on the affected machine | unknown (0.0 asserted) | Needs data from that machine (§3.4). |
| F6/F7/F8/F9/F10 exist as described | 0.9 | Each reproduced; F6 used a verbatim copy of the function body (the real coroutine service was not run). |
| Windows-specific consequences (F5 trap in practice, WIN-VERIFY items) | 0.6 | Code-path reasoning only; nothing was run on Windows. |
| Size of the F7 leak per day | 0.5 | Measured at 15,000 statements; per-day figure is a linear extrapolation. |

---

## 2. Rules for the agent (non-negotiable)

**R1 — Read before you edit.** Read every file you will change **in full**, plus the callers of any function whose behaviour you change. In each commit/PR note list the files you read. Do not edit from grep excerpts.

**R2 — Evidence discipline.** Every claim in a commit message, PR text, code comment or report must be one of: *verified* (you ran it or read it — cite `file:line` or the command), *inferred* (say "inferred"), or *unverified* (say so). Never write "fixed" for anything that depends on Windows-only behaviour you did not run; write "implemented, WIN-VERIFY pending".

**R3 — User data is untouchable.** Never delete, truncate, move, migrate or "reset" `%USERPROFILE%\.focusflow\*` (database, `-wal`, `-shm`, `backups\`, `crash.log`) or any real user database — not in code you run, not in scripts, not in tests. Tests use a temp directory and redirect the JVM property `user.home` to it. Any data-repair code must (a) take a verified backup first, (b) support dry-run, (c) never delete user rows without owner approval (prefer flagging/marking).

**R4 — Secrets.** The two Discord webhook strings are hard-coded in `CrashReporter.kt` (lines 60-66) and `ReviewPromptService.kt` (lines 57-63). **Never print, decode, copy, log, diff-quote or commit them**, and never paste them into a PR, test output or report. View those files with line ranges that exclude those lines (e.g. `sed -n '100,200p'`). Treat both webhooks as compromised: Base64 is encoding, not encryption, and the strings ship in every installer. Rotation is an **owner action** (task F4).

**R5 — Scope.** No drive-by refactors, renames, formatting sweeps or dependency upgrades. Upgrading sqlite-jdbc is an **OWNER DECISION** (§10). If you find a new defect, append it to `AUDIT-ADDENDUM.md` with evidence; do not fix it inside another task.

**R6 — Error-handling policy (new and changed code).**
1. `catch (_: Exception) {}` is banned. Log with context, or return a typed result.
2. JNI/linkage failures are `Error`s (`UnsatisfiedLinkError`, `NoClassDefFoundError`, `ExceptionInInitializerError`). At **boundaries** (DB init, startup, telemetry, background loops) catch `Throwable`; rethrow only `CancellationException` and `VirtualMachineError` (`OutOfMemoryError`, `StackOverflowError`); convert everything else into a typed failure.
3. Distinguish **"absent"** from **"unreadable"**. A settings read must never return `null` for both. Use a tri-state/`Result` (F12).
4. Security-relevant reads (PINs, protection flags) **fail closed**. Availability UX fails open *with a visible message*. If the policy is not written in this plan, it is an **OWNER DECISION**.
5. A transaction wrapper must roll back on `Throwable`, never call `setAutoCommit(true)` while a transaction is open (F10).

**R7 — Threading.** No database I/O on the Compose/AWT thread in new code. Enforcement hot paths (foreground-change callbacks) read caches only. No `runBlocking`.

**R8 — Tests ship with the change.** Every task lists required tests. They must **fail before** your fix and **pass after**. Anything touching `org.sqlite.core.NativeDB` / `SQLiteJDBCLoader` static state **must run in a fresh JVM** (use the `ForkedJvm` helper from Phase 0): that state is process-global and one-shot (`NativeDB.java:42-43, 66-74`).

**R9 — Telemetry/privacy.** No test or script makes a network call. **Until the F4.0 seam exists, do not run any test or script that makes `Database.init` fail on the default database path:** `Database.finishFailed` then calls `CrashReporter.reportCritical` (`Database.kt:463-470`), which POSTs to the production webhook when consent is unset or unreadable — the very defect F4 fixes. Nothing leaves the device containing the Windows username, home path or machine name. After F4, consent is **fail-closed**.

**R10 — If the plan is wrong, say so.** If code differs from what is described, a line number moved, or a claim does not reproduce: stop that task, write the discrepancy in `AUDIT-ADDENDUM.md`, continue with other tasks. Do not silently adapt.

**R11 — Windows reality.** Do not "fix" Windows file-sharing or registry behaviour from Linux by guessing. Implement, add the item to §9, and mark **WIN-VERIFY**.

**R12 — Don't touch (unless the owner says so):** enforcement semantics (Nuclear Mode, block lists, overlays), UI visual design, installer/MSIX packaging, Store listing. This plan is database-adjacent only. (F1b below is the one explicit packaging exception and is owner-gated.)

**R13 — Commit hygiene.** Follow the repo's existing commit style; prefix with the finding ID (`F2: …`). Never commit jars, DBs, logs, scratch files, or webhook values.

**R14 — Compatibility.** Existing user databases must keep working. Schema changes only through a new numbered `migrateVn()` (`Database.kt:499-505` documents the convention; current `TARGET_VERSION = 9`) with a migration test from a v9 database. Never edit an existing `migrateVn()`.

**R15 — Report back at the end of every phase** in `PHASE-<n>-REPORT.md`: tasks done, tests added (names), exact commands run and their results, WIN-VERIFY items outstanding, discrepancies found.

**R16 — Definition of done (any task).** `./gradlew test` passes; no new `catch (_: Exception) {}`; the task's acceptance lines are all true; its report entry exists.

---
## 3. Root cause of the reported SQLite error

### 3.1 The two traces are one failure

| Trace | Top frame | What it means |
|---|---|---|
| **A** | `java.sql.SQLException: Error opening connection` at `SQLiteConnection.java:283`, then app frames `Database.kt:341 → :286 → :137 → :121`, `Main.kt:217` | sqlite-jdbc's `open()` caught an `Exception` thrown while loading/creating the native DB and re-wrapped it. The wrapped cause is **not** in the pasted text (it was cut by `... [truncated]`). |
| **B** | `java.lang.UnsatisfiedLinkError: 'void org.sqlite.core.NativeDB._open_utf8(byte[], int)'` at `SQLiteConnection.java:287 → DB.java:216 → NativeDB.java:82`, same app frames | The JNI method `_open_utf8` has **no linked native implementation** in this JVM, i.e. the native library never loaded. |

Both traces map line-for-line onto sqlite-jdbc **3.47.1.0** and onto the audited `Database.kt`/`Main.kt` **[V-src] [V-code]**, and my reproductions printed the same frame sequence **[V-repro]**.

### 3.2 Mechanism (each step cited)

1. `Main.kt:217` calls `Database.init()` on `Dispatchers.IO` → `Database.kt:120-121` → `openWithRetries` (`:137`, body `:275-330`) → `tryOpenAndMigrate` (`:286`, body `:332-385`) → `dataSource.connection` (`:341`).
2. sqlite-jdbc `SQLiteConnection.open` (`SQLiteConnection.java:278-288`) calls `NativeDB.load()` (L280) and `new NativeDB(...)` (L281) inside `try { } catch (Exception e)`. On any `Exception` it throws `new SQLException("Error opening connection")` with the cause attached (L282-285). **This is Trace A.** **[V-src]**
3. `NativeDB.load()` (`NativeDB.java:66-74`) runs `loadSucceeded = SQLiteJDBCLoader.initialize()` inside `try { } finally { isLoaded = true; }`. If `initialize()` threw, `isLoaded` is **still set to true** and `loadSucceeded` stays `false`. The state is process-global and one-shot (fields at `NativeDB.java:42-43`). **[V-src]**
4. On the **next** call (`Retry`: `Main.kt:393` `onRetry = { retryToken++ }` → `Main.kt:187` → `:217`), `NativeDB.load()` returns `false` immediately (`NativeDB.java:67`) **without throwing**. `SQLiteConnection.open` ignores the return value and goes on to `db.open(...)` (L287) → `DB.java:216` → `NativeDB.java:82` → `_open_utf8`, whose native code was never linked → `UnsatisfiedLinkError`. **This is Trace B.** **[V-src] [V-repro]**
5. `UnsatisfiedLinkError` is an `Error`. `Database.kt:377` (`catch (failure: Exception)`) and `Main.kt:221` (`catch (failure: Exception)`) do not catch it. It leaves the `LaunchedEffect` coroutine and reaches the fatal-crash handler, which sends the report through `sendToDiscord` (1500-character cap — the `... [truncated]` suffix in Trace B). **[V-code]; the fatal-crash hand-off itself is [INF] from the trace shape.**
6. **Why step 2 failed at all** — the loader order is (`SQLiteJDBCLoader.java:300-369`):
   `org.sqlite.lib.path` + `org.sqlite.lib.name` → extract the DLL from the jar into `org.sqlite.tmpdir` or `java.io.tmpdir` as `sqlite-3.47.1.0-<uuid>-sqlitejdbc.dll` (+ `.lck`), compare contents, `System.load` (L179-229, L262-283) → every entry of `java.library.path` → `System.loadLibrary("sqlitejdbc")` (L285-293) → else `NativeLibraryNotFoundException("No native library found for os.name=…, os.arch=…, paths=[…]")` (L362-369).
   **Every intermediate failure (`IOException`, `UnsatisfiedLinkError`) is only passed to sqlite-jdbc's own logger** (`SQLiteJDBCLoader.java:226, 271, 290`). That logger uses SLF4J if present, otherwise `java.util.logging` at SEVERE → `System.err` (`LoggerFactory.java`). SLF4J is not a dependency of this app and the packaged app has no console. So the **specific reason** (access denied, policy block, missing/unwritable temp dir, disk full…) is lost, and the final exception lists only the *paths tried*. **[V-src] [V-repro]**

### 3.3 Reproduced (Linux sandbox, real app classes + real sqlite-jdbc 3.47.1.0 sources; Appendix A)

- **Fault 1:** `-Dorg.sqlite.tmpdir=/nonexistent/...` → `init()` #1 returns `Failed(SQLException "Error opening connection" ← NativeLibraryNotFoundException "No native library found for os.name=Linux, os.arch=x86_64, paths=[…]")`. The real reason (`NoSuchFileException`) appears **only** in the JUL SEVERE text on stderr.
- **Fault 2:** temp dir on a `noexec` mount → extraction succeeds, `System.load` is refused ("failed to map segment from shared object") → the same two outcomes. This is the closest Linux analogue of an antivirus / AppLocker / WDAC block on a DLL in `%TEMP%`.
- In both: `init()` #2 (what Retry does) **throws** `UnsatisfiedLinkError: 'void org.sqlite.core.NativeDB._open_utf8(byte[], int)'` with `is Exception=false is Error=true`, and the frames are `NativeDB.java:82 ← DB.java:216 ← SQLiteConnection.java:287` — identical to Trace B. The control run (no fault) returned `Ready` twice.
- The first failure called `CrashReporter.reportCritical(source=Database.open, throwable=SQLException: Error opening connection)` — i.e. Trace A is the `reportCritical` report (1200-character cap, `CrashReporter.kt:582`).

### 3.4 What is NOT known — and what settles it

**I do not know why the native library failed to load on the machine that produced the traces.** The Windows analogues of fault 1/2 (unusable `%TEMP%`, antivirus/AppLocker/WDAC blocking a DLL loaded from `%TEMP%`, quota) go through the same code path and are the most likely class of cause **[INF]**, but I did not run anything on Windows.

Ruled out **[V-src] / [V-code]**:
- *Wrong or mismatched DLL.* The Windows x86_64 DLL in the 3.47.1.0 source tree exports `Java_org_sqlite_core_NativeDB__1open_1utf8`. (Caveat: I could not download the jar from Maven Central — blocked from my sandbox — so I checked the tag's `src/main/resources`, not the exact jar your build pulls.)
- *Unsupported architecture.* The 3.47.1.0 tree contains `Windows/x86`, `x86_64`, `armv7` and `aarch64` DLLs.
- *Missing runtime modules.* `java.logging` and `java.sql` are both in the `modules(...)` list (`build.gradle.kts:67-76`).
- *Busy/locked/corrupt DB, schema, migrations.* The failure happens before any SQL runs; the exception has `errorCode 0`, so `classifySqliteFailure` returns `OTHER` (`Database.kt:63-81`, `:289-290`) and `init` goes straight to `finishFailed` (`Database.kt:155-157`).

**Three facts to collect from the owner (Phase 0, task P0-3):**
1. **Which machine produced the traces** — a real Windows PC (yours or a user's) or a Linux/Replit/NixOS run? The Discord embeds carry `OS` and `Java` fields (`CrashReporter.kt:587-599`); read them.
2. **The local report file** written next to the crash: `FocusFlow-crash-<timestamp>.log` in `Desktop`, else `~/.focusflow`, else the temp dir (`CrashReporter.writeLog`, `:477-515`). It contains the **full** cause chain (`appendExceptionChain`, `:327-372`), unlike Discord.
3. **`%TEMP%` and security-software context** on that machine.

Untested helper for fact 3 (I have not run it; redact your username before sharing):

```powershell
$env:TEMP; $env:TMP
[Environment]::OSVersion.VersionString; [Environment]::Is64BitOperatingSystem
(Get-CimInstance Win32_Processor).Architecture      # 0=x86, 5=ARM, 9=x64, 12=ARM64
Get-ChildItem "$env:TEMP\sqlite-3.47.1.0-*" -ErrorAction SilentlyContinue | Select-Object Name,Length,LastWriteTime
Get-ChildItem "$env:USERPROFILE\Desktop\FocusFlow-crash-*.log","$env:USERPROFILE\.focusflow\FocusFlow-crash-*.log" -ErrorAction SilentlyContinue | Select-Object FullName,Length,LastWriteTime
Get-WinEvent -FilterHashtable @{LogName='Microsoft-Windows-CodeIntegrity/Operational'; Level=2,3} -MaxEvents 20 -ErrorAction SilentlyContinue | Select-Object TimeCreated,Id,Message
Get-WinEvent -LogName 'Microsoft-Windows-AppLocker/EXE and DLL' -MaxEvents 20 -ErrorAction SilentlyContinue | Select-Object TimeCreated,Id,Message
```

**How to read the answer [INF — none of these rows was observed on Windows]:**

| What the local report / OS field shows | Likely meaning | Action |
|---|---|---|
| OS field says **Linux** | Traces come from a Linux/Replit/Nix run, not a user's PC | Windows users are not hit by *this* trigger. F1–F3 are still worth doing (the handling gaps are real). |
| `NoSuchFileException` / `AccessDeniedException` on a `%TEMP%` path | Temp dir missing or unwritable | F1 fallback ladder (user-profile directory) fixes it. |
| `UnsatisfiedLinkError … Access is denied` / "blocked" / CodeIntegrity or AppLocker events naming `sqlite-3.47.1.0-*-sqlitejdbc.dll` | Security software or policy blocks DLLs loaded from `%TEMP%` | F1 ladder first; **F1b** (ship the DLL inside the install directory) is the durable fix; document an allow-list entry. |
| An architecture message ("Can't load AMD 64-bit .dll on a IA 32-bit platform") | JVM/DLL bitness mismatch | Check the bundled runtime's architecture. Unlikely with jpackage; **[UNV]**. |
| A cause that is **neither** `NativeLibraryNotFoundException` nor `UnsatisfiedLinkError` | My mechanism does not explain Trace A | **Stop and report (R10).** Trace B alone still proves the native library was not linked in that JVM. |

### 3.5 Why the application made an environment problem much worse

| Gap | Task |
|---|---|
| Native bootstrap is delegated to a library default ("extract to temp and hope"), with no fallback and no diagnostics | F1 |
| `catch (Exception)` at the only two places that matter; Retry re-enters a poisoned JVM and crashes it | F2 |
| The real reason is shown nowhere: the gate screen prints only `exception-class: message` for `Failed` (`Main.kt:373-374`) while `ServiceFailure` prints the full stack (`:375-378`); telemetry keeps the *head* of the stack trace (1200/1500 chars) and drops the `Caused by:` tail | F3 |
| Telemetry is sent when consent is `null` **or unreadable** — exactly the situation during a DB failure | F4 |
| `ensureRegistered()` rewrites the uninstall command to `"<exe>" --uninstall` on first launch, *before* the DB opens (`Main.kt:73-83`); the wizard then refuses to run if the DB cannot be opened (`UninstallWizard.kt:30-37`, `UninstallProtectionService.kt:44-59, 81-92`) | F5 |
| A crash is followed by an automatic relaunch within 2 minutes (`FocusFlowWatchdog` scheduled task, `WatchdogInstaller.kt:49-66`, installed by `StartupBootstrap.kt:63`). **[INF]** each relaunch repeats the first-failure report | F4 / F18 |

---

## 4. Findings register

Severity: **P0** = must fix first (it is the reported failure or makes it unrecoverable); **P1** = real data/security/correctness defect; **P2/P3** = hardening or cleanup.

| ID | Sev | Finding | Evidence | Phase |
|---|---|---|---|---|
| **F1** | P0 | Native library bootstrap is uncontrolled; failure is latched and hidden | V-repro, V-src | 1 |
| **F2** | P0 | `Error`s escape `catch (Exception)`; Retry on a poisoned JVM crashes the app | V-repro | 1 |
| **F3** | P0 | Root cause invisible in UI, local report and telemetry | V-repro, V-code | 1 |
| **F4** | P0 | Telemetry sent without consent (fail-open; consent stored in the DB that failed); PII in reports; webhook secrets ship in the binary | V-repro, V-code | 1 |
| **F5** | P0 | Uninstall wizard refuses to run whenever the DB cannot be opened | V-code (impact INF) | 1 |
| **F6** | P1 | Recurring tasks multiply every day (2ⁿ) | V-repro (verbatim logic copy) | 2 |
| **F7** | P1 | JDBC `Statement` leak on the 2-second enforcement-cache refresh | V-repro | 2 |
| **F8** | P1 | PIN/guard checks **fail open** when the settings read fails; PIN writes are swallowed | V-repro | 2 |
| **F9** | P1 | Orphaned "unfinished focus session" row = permanent "active session" for the quit/uninstall gate | V-repro, V-code | 2 |
| **F10** | P1 | Transaction wrappers roll back only on `Exception`; an `Error` commits a half-applied transaction | V-repro | 2 |
| **F11** | P1 | The `--uninstall` JVM runs a full `Database.init` (sidecar snapshots, WAL pragma, migrations) while the main app is using the DB | V-repro (stress), V-code | 2 |
| **F12** | P1 | `getSetting()` returns `null` for both "absent" and "unreadable"; ~all consumers conflate them | V-code | 2 |
| **F13** | P1 | Focus-session start/end writes are un-ordered, un-checked, and swallowed | V-code | 2 |
| **F14** | P2 | Swallow-all `ALTER TABLE` in migrations hides real failures | V-code | 2 |
| **F15** | P1 | Backups: "verification" is cosmetic, no integrity check, same disk, failures silent; `restoreBackup` is dead and unsafe | V-code | 2 |
| **F16** | P2 | One global connection + one global monitor; which threads call it is not audited | INF (not measured) | 2 |
| **F17** | P1 | `VpnBlocker` caches "disabled / no custom processes" forever after one failed read | V-code | 2 |
| **F18** | P1 | No enforcement of any kind while DB init is failed (policy = **OWNER DECISION**) | V-code | 2 |
| **F19** | P2 | Mid-run DB loss has no defined behaviour (stale caches, fail-open PIN reads) | INF | 2 |
| **F20** | P3 | `DATE(col)` defeats indexes; per-day N+1 queries (17 ms → 0.007 ms per query at 200k rows) | V-code, V-repro | 3 |
| **F21** | P3 | `completeTask` is not idempotent (double count) | V-repro | 3 |
| **F22** | P3 | "Clear all data" deletes less than its dialog says, is non-atomic, takes no backup, is not PIN-gated; dead duplicate in `BackupService` | V-code | 3 |
| **F23** | P3 | Three CSV code paths: silent caps (1,000 / 9,999), inconsistent and lossy quoting, errors indistinguishable from "cancel" | V-code | 3 |
| **F24** | P3 | Crash/startup logs go to the Desktop first, unbounded | V-code | 3 |
| **F25** | P3 | The DB path is computed in 4 places | V-code | 3 |
| **F26** | P3 | `include("recovery")` in `settings.gradle.kts:3` but no `recovery/` directory exists in the archive | V-code | 3 |
| **F27** | P3 | JVM flag `-Djava.nio.channels.spi.SelectorProvider=sun.nio.ch.PollSelectorProvider` (`build.gradle.kts:55`) names a class that exists only in the JDK **unix** tree | INF (not run on Windows) | 3 |
| **F28** | P3 (**do first**) | `CrashReporter.APP_VERSION` throws if the JVM property is missing (`CrashReporter.kt:55-56`); tests that use the default DB path would hit it | V-code | 1 (prerequisite) |
| **F29** | P3 | Test gaps (native-load failure, PIN fail-open, backup/restore, recurring tasks, statement leaks) | V-code | 3 |
| **F30** | P3 | "Current streak" is capped at 90 by a `LIMIT 90` (may be intentional — ask) | V-repro | 3 |

---
## 5. Phase 0 — Baseline and tooling (do this first, change no production code)

### P0-1 Baseline
- Run `./gradlew test` on the untouched tree. Record: pass/fail counts, duration, and the JDK actually used (`jvmToolchain` is 19, `build.gradle.kts:90-94`; `gradle.properties:5` pins a Nix GraalVM path that will not exist on Windows/CI).
- Existing DB tests live in `src/test/.../DatabaseTest.kt` and cover busy/locked/corrupt/recovery/v9 migration only. **Do not modify them in Phase 0.**
- If the build cannot run, write down why in `PHASE-0-REPORT.md` and stop; do not "fix" the toolchain.

### P0-2 Test helpers (new files under `src/test/kotlin/com/focusflow/testutil/`)
| Helper | Contract |
|---|---|
| `TempHome` | Creates a temp dir, sets the JVM property `user.home` to it for the duration of a block, restores it after. Also sets `focusflow.telemetry=off` and `focusflow.app.version=test` (see F4.0, F28). Calls `Database.resetForTest()` (`Database.kt:179-180`, `internal`) before and after. **Never** touches the real home. |
| `ForkedJvm.run(mainClass, jvmArgs, args)` | Spawns `java` with the test runtime classpath (`System.getProperty("java.class.path")`), a **fresh JVM**, always passing `-Dfocusflow.telemetry=off -Dfocusflow.app.version=test`, and returns `exitCode`, `stdout`, `stderr`. Needed because `NativeDB`'s load latch is process-global (R8). Timeout 60 s. |
| `StatementLeakAssert.assertStable { … }` | Reflection helper (test-only): reads `Database.conn` → `org.sqlite.SQLiteConnection.getDatabase()` → `org.sqlite.core.DB.stmts` (`DB.java:58`, a strong `ConcurrentHashMap.newKeySet()`), runs the block N times, asserts the set size did not grow. Reference implementation: `stmtCount()` in the audit harness `Repro3.kt` (Appendix A). |
| `FakeTelemetrySink` | Implements the `TelemetrySink` seam introduced in **F4.0**; records payloads, performs **no I/O**. (Write it in Phase 1, right after F4.0 — Phase 0 changes no production code.) |
| Clock seam | `fun today(): LocalDate` injectable where F6 needs it (add as a default parameter, not a global). |

### P0-3 Collect the missing facts (owner action — not code)
Ask Himanshu for the three items in §3.4 (which machine; the local `FocusFlow-crash-*.log`; `%TEMP%`/security-software context). Put the answers in `AUDIT-ADDENDUM.md`. **Do not block Phase 1 on the answers**: F1–F3 are correct regardless of the trigger.

**Phase 0 exit:** baseline recorded; helpers compile and have their own smoke tests; `PHASE-0-REPORT.md` written.

---

## 6. Phase 1 — P0: the reported failure and its blast radius (F1–F5)

**Order inside Phase 1: F28 → F4.0 → F2 → F1 → F3 → F4 (remaining steps) → F5.**
Why: (1) any test that makes `Database.init` fail on the default path reaches `CrashReporter.reportCritical` (`Database.kt:463-470`), whose static initialiser throws without the version property (F28) and which, before F4, **posts to the production Discord webhook** when consent is unset or unreadable; (2) F2 is the smallest change and makes every later test safe from escaping `Error`s. F28 and F4.0 are small and mechanical; do them first and commit them separately.

### F1 — Controlled native bootstrap `[V-repro][V-src]`

**Problem.** The app relies on sqlite-jdbc's default "extract DLL to `java.io.tmpdir` and `System.load` it" with no fallback, no pre-flight and no captured reason; one failure poisons the JVM (§3.2 steps 2-6).

**Root cause.** `Database.kt:341` is the first and only place the native library is touched, and nothing runs before it that could detect, diagnose or work around a load failure.

**Required change** *(new file `data/NativeSqlite.kt`; small edit in `Database.kt`)*

1. `object NativeSqlite { fun ensureLoaded(): NativeSqliteStatus }` with `Loaded(path, how)` / `Unavailable(NativeSqliteUnavailableException)`. Only a **successful** result is memoized; a failure is recomputed on each explicit Retry (the ladder below does not poison sqlite-jdbc state, so Retry is meaningful).
2. **Where it runs:** in `Database.init`, immediately after the `isReady` check and the database-directory creation (`Database.kt:126-132`) and **before** `captureSidecarSnapshots` (`:134`). On `Unavailable` → `return finishFailed(cause, dbFile)`. No database file is touched on this path.
3. **Candidate ladder** (each attempt records: directory, existed/created, writable, extraction result, and the **exact `Throwable` class + message** from `System.load`):
   1. `%LOCALAPPDATA%\FocusFlow\native\sqlite-3.47.1.0\<os-arch>\` (fall back to `<user.home>\AppData\Local\...` if the env var is absent).
   2. `<user.home>\.focusflow\native\sqlite-3.47.1.0\<os-arch>\`.
   3. *(F1b, optional, owner-gated)* the application resources directory next to the installed app (see F1b).
   4. Last resort: let sqlite-jdbc's default loader run (`java.io.tmpdir`). Because this can poison the one-shot latch, if it fails the result must carry `requiresRestart = true` and the UI must not offer Retry (see UI below).
4. **Extraction:** read the resource with `LibraryLoaderUtil.getNativeLibResourcePath()` + `LibraryLoaderUtil.getNativeLibName()` (both `public`, `org.sqlite.util`, 3.47.1.0). Write to a **content-addressed** file name `sqlitejdbc-<first 12 hex of SHA-256>.dll` via a temp file + `Files.move(ATOMIC_MOVE)`; reuse the file when its SHA-256 already matches; if it exists but differs or cannot be replaced, use a new suffix. (Content-addressed names mean no per-launch UUID files and no `.lck`/cleanup race.)
5. **Load:** `System.load(absolutePath)` inside `try/catch (Throwable)`. On success set `System.setProperty("org.sqlite.lib.path", dir)` and `System.setProperty("org.sqlite.lib.name", fileName)` so sqlite-jdbc's own loader (step 1 of its order, `SQLiteJDBCLoader.java:307-318`) finds the already-loaded library (a second `System.load` of the same path in the same class loader is a no-op **[INF from JDK behaviour — assert it in the fresh-JVM test]**). Then call `SQLiteJDBCLoader.initialize()` once inside `try/catch (Throwable)` to confirm the whole path works.
6. **Failure object:** `NativeSqliteUnavailableException(message, attempts, requiresRestart)` (an `Exception`, not an `Error`). `message` is one line: `SQLite native library could not be loaded (os=<name> <arch>, java=<ver>): [1] <dir>: <Throwable class>: <message>; [2] …`. Each attempt's `Throwable` is added with `addSuppressed`. A multi-line `diagnostics` string (os.name/os.arch/java.version/java.vendor/java.io.tmpdir/user.home-redacted, resource present?, per-attempt details) is kept for the log and UI.
7. **UI** (`StartupGateWindow`, wired at `Main.kt:381-405`): for `NativeSqliteUnavailableException` show title "FocusFlow could not load its database engine", the one-line message, the log path, and — if `requiresRestart` — a **Restart/Quit** button instead of Retry. Retry stays for the non-poisoned case. (Keep the existing visual style; this is a functional change only.)

**Must NOT**
- Call `NativeDB.load()` or `SQLiteJDBCLoader.initialize()` before a ladder step has loaded a library successfully (except ladder step 4, which is the explicit last resort). Doing so re-creates Trace B.
- Delete or overwrite anything in `java.io.tmpdir` or in the user's profile other than your own content-addressed file.
- Add a dependency, change the sqlite-jdbc version, or touch the DB file on this path.

**Tests** (R8: fresh JVM for anything that reaches sqlite-jdbc)
- `F1-a` *(in-process, injected loader):* three candidate dirs, loader throws `UnsatisfiedLinkError("Access is denied")` for #1 and #2, succeeds for #3 → `Loaded`, and the two earlier failures are recorded with their exact messages.
- `F1-b` *(fresh JVM)*: `-Dorg.sqlite.tmpdir=<nonexistent>` plus a writable candidate dir → `Database.init` returns `Ready`; a `SELECT 1` works.
- `F1-c` *(fresh JVM)*: all candidates unusable (test hook `NativeSqlite.candidateDirsForTest`) → `init` returns `Failed` whose `cause` is `NativeSqliteUnavailableException` with ≥ 3 attempts; **no `Error` is thrown**; calling `init` a second time returns `Failed` again, still without throwing; `org.sqlite.core.NativeDB`'s private `isLoaded` is still `false` (reflection) — proves the latch was not poisoned.
- `F1-d` *(fresh JVM, Linux CI only, skip elsewhere)*: reproduce the audit's fault 2 if a `noexec` mount is available; otherwise skip with a clear message.

**Acceptance**
- [ ] `F1-a…c` pass and fail on the pre-fix tree.
- [ ] With every candidate unusable, no `UnsatisfiedLinkError` can be produced by calling `Database.init` any number of times.
- [ ] The failure text contains, per attempt, the real `Throwable` message.
- [ ] **WIN-VERIFY:** clean Windows VM, `%TEMP%` pointed at a nonexistent path → the app starts and the DB opens.
- [ ] **WIN-VERIFY:** a DLL-block rule on `%TEMP%` (AppLocker/WDAC audit rule or a test AV) → app still starts via ladder step 1.

#### F1b — Ship the DLL inside the installation *(OWNER DECISION: touches packaging; R12 exception)*
Extract `org/sqlite/native/Windows/x86_64/sqlitejdbc.dll` from the resolved sqlite-jdbc jar at build time (a small Gradle task writing into `build/…`; **do not commit binaries**) and expose it through Compose's `appResourcesRootDir` so it lands next to the installed app; at runtime read `System.getProperty("compose.application.resources.dir")` as ladder step 3. **[UNV]** — confirm the exact `appResourcesRootDir` layout (`windows-x64/` vs `windows/`) and the property name against the Compose Multiplatform 1.6.1 documentation before implementing. **WIN-VERIFY** both the EXE and MSI installers. Do not start F1b until the owner approves.

---

### F2 — `Throwable`-safe init and startup `[V-repro]`

**Problem.** `UnsatisfiedLinkError` (and any `LinkageError`) escapes the two `catch (Exception)` blocks and crashes the app; a failed init can also leave `startupState` stuck at `Opening`.

**Root cause.** `Database.kt:377` and `Main.kt:221` catch `Exception` only; `init` (`Database.kt:120-177`) has no outer guard.

**Required change**
1. `Database.tryOpenAndMigrate` (`:377`): `catch (failure: Throwable)`; rethrow only `VirtualMachineError`; otherwise return the throwable as the failure value (`OpenResult.Failure` already takes `Throwable`; `:289-290` classifies non-`SQLException` as `OTHER`).
2. `Database.init`: wrap the body so it **cannot throw** (except `VirtualMachineError`): convert anything else into `finishFailed(...)`, which also moves `_startupState` to `Failed`.
3. `Main.kt:216-224`: extract the call into a small testable function, e.g. `suspend fun initDatabaseSafely(): Pair<DbInitResult, Boolean /*threw*/>`, that rethrows `CancellationException` (as at `:218-220`) and `VirtualMachineError`, and maps any other `Throwable` to `DbInitResult.Failed` with `initThrew = true` (as at `:221-224`).
4. Add the test seam `internal var dataSourceFactoryForTest` next to `recoveryFileOpsForTest` (`Database.kt:117-118`) so tests can inject a data source that throws.
5. **Audit pass (read-only first):** list every `catch (e: Exception)` / `catch (_: Exception)` that wraps a `Database.*` call inside a *loop or background job* (`ProcessMonitor.kt:378-387` cache loop, `AutoBackupService.kt:125` backup job, `RecurringTaskService.kt:85` task generator, …). For each, decide in the PR whether an `Error` there would silently kill the loop, and change to the R6 boundary pattern where it would. Do not change catches inside plain request/response helpers.

**Must NOT:** swallow `VirtualMachineError`; swallow `CancellationException`; change `Database.init`'s public signature.

**Tests**
- `F2-a`: factory throws `UnsatisfiedLinkError("x")` → `Database.init()` returns `Failed`, does not throw; `startupState` is `Failed`; a second `init()` also returns `Failed`.
- `F2-b`: factory throws `OutOfMemoryError` → rethrown.
- `F2-c`: `initDatabaseSafely` — cancellation rethrown; arbitrary `Throwable` → `Failed(initThrew=true)`.

**Acceptance**
- [ ] `F2-a…c` pass and fail on the pre-fix tree.
- [ ] No code path leaves `startupState` at `Opening` after `init` returns or throws.
- [ ] The audit list from step 5 is in the phase report with one decision per entry.

---

### F3 — Make the root cause visible `[V-repro][V-code]`

**Problem.** The real reason lives only in JUL SEVERE lines on stderr (§3.2 step 6); the gate screen shows only `class: message` for `Failed` (`Main.kt:373-374`); telemetry keeps the first 1200/1500 characters of the stack trace (`CrashReporter.kt:582`, `:775`) and loses the `Caused by:` section.

**Required change**
1. **Capture sqlite-jdbc's log.** Before the first database use, attach to the JUL logger `org.sqlite` (a) a `FileHandler` writing `~/.focusflow/sqlite-jdbc-%g.log` (e.g. 256 KB × 2, append) and (b) an in-memory handler whose records are appended to `NativeSqliteUnavailableException.diagnostics`. If creating the file handler fails, continue silently (there is nowhere to report it).
2. **Root-cause-first summary.** Add `CrashReporter.rootCauseSummary(t: Throwable, maxChars: Int): String`: for each element of the cause chain (cycle-safe, at most 8): `"<class>: <message, max 160 chars>"`; then the top 3 frames of the **deepest** cause; then a one-line `Suppressed:` list; truncated to `maxChars`. Use it in **both** `reportCritical` (cap stays 1200, replaces `:580-584`) and `sendToDiscord` (cap stays 1500, replaces the `:775` truncation). Keep the full chain only in the local log.
3. **Environment line in every DB-failure report:** `os.name`/`os.arch`/`java.version`/`java.vendor`, native status (`Loaded(how)` or `Unavailable(n attempts)`), free disk space on the DB drive, DB/`-wal`/`-shm` existence and sizes. **Redact** (R9, F4) the username and home path before it goes into the Discord payload; the local log may keep full paths.
4. **Gate screen:** for `Failed`, show the full cause chain (the same rendering `ServiceFailure` already gets at `Main.kt:375-378`) and the path of the local report (`startupReportPath` is already tracked at `Main.kt:113`).

**Tests**
- `F3-a`: chain A→B→C with 60 frames → summary length ≤ cap and contains C's class and message.
- `F3-b`: self-referential cause cycle does not loop.
- `F3-c`: payload JSON is valid for messages containing quotes, backslashes, newlines and non-ASCII.
- `F3-d` *(fresh JVM)*: a native failure produces a non-empty `sqlite-jdbc-*.log` under the temp home containing the loader's SEVERE text.

**Acceptance**
- [ ] For the audit's fault 1 and fault 2, the **Discord-bound text** (captured through `FakeTelemetrySink`) contains the deepest cause class and message within the existing caps.
- [ ] The gate screen shows the deepest cause.
- [ ] No username or home path appears in the sink payload (see F4 test).

---

### F4 — Consent, PII and secrets `[V-repro][V-code]`

**Problem.**
- Consent is read as `Database.getSetting("crash_reports_enabled") != "false"` at `CrashReporter.kt:567` (`reportCritical`) and `:770` (`sendToDiscord`). `getSetting` returns `null` when the DB is not ready or the read fails (`Database.kt:1302-1310`), and `null != "false"` is `true` → **reports are sent exactly when consent cannot be read, and also before the user has ever answered** (the dialog is only offered when the value is `null`, `App.kt:193-194`, and never before onboarding finishes). Reproduced (Repro2 §D).
- The dialog's KDoc says the user makes an opt-in/out choice "before any crash report is ever sent" (`ui/components/TelemetryConsentDialog.kt:24-25`); the code does not do that. The displayed text says "No personal information is ever collected" (`:83`) and lists "Your name, email, or IP address" as not collected (`:99`).
- Report bodies can carry `C:\Users\<name>\…` (e.g. sqlite-jdbc's own message `opening db: '<path>'`, `SQLiteConnection.java:271`, flows into `**Cause:** ${cause.message}` at `Database.kt:467`) **[INF]**.
- Two Discord webhooks are embedded in the shipped code as Base64 (`CrashReporter.kt:60-66`, `ReviewPromptService.kt:57-63`); anyone with the installer can recover them **[V-code]** (Base64 is encoding). They can be used to spam the channel or forge reports.

**Required change**
1. **DB-independent consent store:** `TelemetryConsent` reading/writing a tiny file `~/.focusflow/telemetry-consent` (`granted` / `denied`; absent = `UNKNOWN`). One-time migration: if the file is absent and the DB is readable, copy the existing `crash_reports_enabled` value (`"true"` → granted, `"false"` → denied, absent → UNKNOWN).
2. **Fail closed:** a report is sent only when the state is `GRANTED`. `UNKNOWN`, `DENIED` and *unreadable* all mean "do not send". Apply at `CrashReporter.kt:567` and `:770` and in `ReviewPromptService` if it sends anything automatically (read it first; if it only sends user-written feedback on an explicit click, leave it).
3. **Settings screen and consent dialog:** `SettingsScreen.kt:108` (`!= "false"` default-on) and `:807`, and `App.kt:500/506`, must read/write through `TelemetryConsent`. **Dialog wording is an OWNER DECISION** (see below).
4. **F4.0 — `TelemetrySink` seam (do this FIRST in Phase 1; it changes no behaviour):** interface `post(payloadJson: String)`; production implementation wraps `DiscordWebhookClient`; `NoopTelemetrySink` is selected when the JVM property `focusflow.telemetry=off` is set (used by `TempHome`, `ForkedJvm`, `tasks.test` and local developer runs); tests inject `FakeTelemetrySink`. Route `reportCritical` and `sendToDiscord` through it. No test may hit the network (R9).
5. **Redaction:** before building any payload, replace `System.getProperty("user.home")`, `System.getProperty("user.name")` and `System.getenv("COMPUTERNAME")` (case-insensitive, both slash styles) with `~`, `<user>`, `<host>`. Unit-test it.
6. **Secrets (R4):** do not read the constants into any output. After the owner supplies a replacement endpoint (ideally a small server-side relay that holds the secret and rate-limits), replace the two constants with a `TelemetryEndpoint` abstraction that reads the relay URL from a build-time property that is **not committed**. Until then **do not touch** those lines. **Rotation of both existing webhooks is an owner action** — put it at the top of the phase report as an open item.

**OWNER DECISIONS in F4**
- *Default for an unanswered question:* **opt-in (recommended — the app is marketed as privacy-first)** vs. the current opt-out. If the owner chooses opt-out, the dialog and the Store privacy text must say so plainly.
- *Dialog copy:* "No personal information is ever collected" and "Your name, email, or IP address" (`TelemetryConsentDialog.kt:83, 99`) are at risk: paths in a report can contain the Windows username (**[INF]**, fixed by redaction), and any webhook POST necessarily reveals the sender's IP address to the receiving service. Whether that counts as "collected" is a wording/legal question; the owner approves the final copy.

**Tests**
- `F4-a`: state UNKNOWN / DENIED → `FakeTelemetrySink` receives **zero** payloads from `reportCritical` and `sendToDiscord`, with the DB ready, not ready, and throwing.
- `F4-b`: state GRANTED + DB unavailable → payload is sent (consent no longer depends on the DB).
- `F4-c`: migration from DB value `"true"` / `"false"` / absent.
- `F4-d`: redaction removes username, home path and host name from message, stack trace and `Source`.

**Acceptance**
- [ ] `F4-a…d` pass and fail on the pre-fix tree.
- [ ] `grep -rn "crash_reports_enabled"` shows no consent decision outside `TelemetryConsent` (the migration may read it once).
- [ ] The phase report lists the webhook rotation as an **open owner item** and contains no webhook text.

---

### F5 — Uninstall wizard must not be a trap `[V-code]; impact [INF]`

**Problem.** On direct (EXE/MSI) installs `WindowsUninstallRegistration.ensureRegistered()` replaces both `UninstallString` and `QuietUninstallString` with `"<exe>" --uninstall` (`WindowsUninstallRegistration.kt:87-102`) on the **first launch, before the DB opens** (`Main.kt:73-83`). `UninstallWizard.run` then calls `prepareForUninstallWizard()` (`UninstallWizard.kt:30-37`), which returns `false` unless `Database.init(allowRecovery = false)` reaches `Ready` (`UninstallProtectionService.kt:44-59`), and shows "could not read its protection state safely. The application was not removed." The wizard JVM needs the same native library, so **a machine that cannot load sqlite-jdbc also cannot uninstall FocusFlow from Windows Settings.** The original command is preserved in the registry value `FocusFlowOriginalUninstallString` (`WindowsUninstallRegistration.kt:26-28, 64-80`).

**Why this is a policy question, not a bug fix.** Protection state lives in a user-writable SQLite file, so the gate is not tamper-proof (the PIN hashes are unsalted SHA-256 — F8). A determined user can already run the original command or `msiexec /x`. Meanwhile, if the app cannot start, **no** enforcement is running (F18). The wizard therefore stops honest users and almost nobody else **[INF/opinion]**.

**OWNER DECISION — choose one; do not implement before the answer**
- **A (recommended):** if the wizard cannot read the DB for a reason other than BUSY/LOCKED (native failure, corrupt, I/O error), show an explicit warning ("FocusFlow cannot read its protection settings, so it cannot verify any PIN or block") and allow uninstall after the user types a confirmation phrase.
- **B:** keep fail-closed but make the failure dialog print the supported manual removal path (the original uninstall command) so the user is never stranded.
- **C:** mirror a minimal "protection active" flag outside the DB. Not recommended (over-engineering for a user-controlled machine).

**Required change after the decision:** implement the chosen option in `UninstallWizard.kt` / `UninstallProtectionService.kt`; for BUSY/LOCKED keep waiting/retrying as today. Whatever the option, do **F11** (read-only open, no snapshots, no migrations in the wizard JVM).

**Tests (option A or B)**
- `F5-a`: wizard decision function with a stub `DbInitResult.Failed(NativeSqliteUnavailableException)` → the chosen outcome (allow-with-phrase or show-manual-path), not the generic dead end.
- `F5-b`: `Busy` still blocks and does not offer "uninstall anyway".
- `F5-c`: with DB `Ready` and an active Nuclear/Standalone/Always-On requirement, behaviour is unchanged from today (regression).

**Acceptance**
- [ ] The chosen policy is implemented and documented in the phase report.
- [ ] **WIN-VERIFY:** on a machine where sqlite-jdbc cannot load, Apps → Uninstall no longer dead-ends.

---

**Phase 1 exit criteria:** F1–F5 acceptance lines true (WIN-VERIFY items listed as outstanding, not ticked); `./gradlew test` green; `PHASE-1-REPORT.md` written (including the open owner items: webhook rotation, consent default, dialog copy, F5 policy, F1b approval).

---
## 7. Phase 2 — P1/P2: reproduced data and security defects (F6–F19)

Do F10 and F12 **first** (they are shared infrastructure for the rest), then F7, F8, F6, F9, F13, F11, F17, F15, F14, then F16/F18/F19 (which are measure-first or owner-gated).

### F10 — One transaction helper that is correct `[V-repro]`

**Problem.** Six places use the JDBC pattern `autoCommit = false; try { …; commit() } catch (e: Exception) { rollback(); throw e } finally { autoCommit = true }`: `migrate` (`Database.kt:517-538`), `completeTask` (`:941-953`), `insertSession` (`:1023-1051`), `deleteHabit` (`:1542-1555`), `saveFocusLauncherSession` (`:1793-1840`), `clearFocusLauncherSession` (`:1847-1860`). If an `Error` (e.g. `StackOverflowError`, `LinkageError`) is thrown inside, `catch (e: Exception)` does not run, `finally` calls `setAutoCommit(true)` — which **commits** the open transaction — so a half-applied transaction is persisted. Reproduced: `tasks.completed=1` committed while the second statement never ran (Repro4 `txerror`). Also, a deferred `BEGIN` that later needs the write lock can fail with `SQLITE_BUSY` without the busy handler helping.

**Required change**
1. Add `internal inline fun <T> inTransaction(block: () -> T): T` to `Database`: `BEGIN IMMEDIATE` (executed as SQL on an auto-commit connection — **do not toggle `autoCommit`**), run `block`, `COMMIT`; on **any `Throwable`** run `ROLLBACK` (in its own `runCatching`, adding the rollback failure as suppressed) and rethrow.
2. Convert the six sites. Keep each function's public signature and behaviour otherwise.
3. For `migrate()` keep the single all-or-nothing transaction and the "bump `user_version` last" rule (`:514-516, :529-532`).

**Must NOT:** change any SQL statement text inside the converted functions; nest `inTransaction` calls.

**Tests**
- `F10-a`: `block` throws `StackOverflowError` after the first write → after the call the first write is **not** visible; `autoCommit` state unchanged (still true).
- `F10-b`: `block` throws `SQLException` → rolled back.
- `F10-c`: second connection holds the write lock → `inTransaction` fails fast per busy timeout with `SQLITE_BUSY`, no partial state.
- `F10-d`: migration from a v9 fixture and from empty reaches the same schema (compare `PRAGMA table_info` for every table) and `user_version`.

**Acceptance:** F10-a…d fail before / pass after; `grep -n "autoCommit = false" data/Database.kt` returns nothing; `DatabaseTest` still passes.

---

### F12 — Distinguish "absent" from "unreadable" `[V-code]`

**Problem.** `getSetting` returns `null` when the DB is not ready **and** on any read exception (`Database.kt:1302-1310`); `setSetting` returns nothing and swallows every failure (`:1335-1342`). Callers cannot tell "never set" from "could not read", so read failures silently become defaults:

| Consumer | What a failed read turns into |
|---|---|
| PIN checks (`GlobalPin.kt:21,43-47`, `SessionPin.kt:18,38-55`, `NuclearPin.kt:26,43-46`) | "no PIN set" → pass-through (F8) |
| `VpnBlocker.kt:99-100,106-116` | `false` / empty list, **cached permanently** (F17) |
| `KillSwitchService.loadFromDb` (`:37-48`) | full 300 s daily budget refilled, then written back by `saveToDb()` (`:47`) |
| `NuclearMode.loadFromDb` (`:395-397`) | not re-enabled after restart; and `setSetting("nuclear_mode","true")` (`:287`) can fail silently |
| `StandaloneBlockService` (`:51-53`, `:112-114`, `:163-165`; `loadFromDb :119-133`) | three separate swallowed writes per state change; partial writes leave inconsistent state |
| `WeeklyReportService.checkAndGenerate` (`:66-92`) | `null` ⇒ "never generated" ⇒ report regenerated and announced again |
| `TaskAlarmService` (`:51-68`, `:74-84`) | read-modify-write of `alarm_fired_ids` overwrites the list with a single id after a failed read; "new day" reset after a failed read |
| `App.kt:147-150` | `app_open_count` restarts from 1; promo/consent gating (`:193-194`) re-evaluates |
| `UninstallProtectionService.activeRequirements` (`:142-227`) | an unreadable flag ⇒ requirement absent (fail-open for Quit) |

**Required change**
1. Add (do **not** remove `getSetting`/`setSetting` yet):
   ```kotlin
   sealed interface SettingRead { data class Present(val value: String) : SettingRead; data object Absent : SettingRead; data class Unreadable(val cause: Throwable) : SettingRead }
   sealed interface SettingWrite { data object Ok : SettingWrite; data class Failed(val cause: Throwable) : SettingWrite }
   fun Database.readSetting(key: String): SettingRead
   fun Database.writeSetting(key: String, value: String): SettingWrite
   fun Database.writeSettings(pairs: Map<String, String>): SettingWrite   // one inTransaction, all-or-nothing
   ```
   `readSetting` returns `Unreadable` when the DB is not ready, on `SQLException`, and on `DatabaseUnavailableException`; never `null`.
2. Migrate consumers **in this order**, one commit each, each with a test: (a) PIN classes (F8) → (b) `VpnBlocker` (F17) → (c) `StandaloneBlockService` (use `writeSettings`) → (d) `NuclearMode`, `KillSwitchService` → (e) `UninstallProtectionService.activeRequirements` → (f) `WeeklyReportService`, `TaskAlarmService`, `App.kt` → (g) everything else is optional and may stay on `getSetting`.
3. **In-repo precedent:** the allowance subsystem already models this — `UsageStore.isAvailable()` (`services/allowance/AllowancePorts.kt:63-69`), wired to `Database.isReady` (`services/DailyAllowanceTracker.kt:89-96`) — and its tests (`unavailableUsageStoreDoesNotStartTracking`, `failedReloadReturnsFailureAndKeepsTheLastKnownAllowances`) show the expected style.
4. Rules for migrated code: `Unreadable` on a **security** read ⇒ deny/closed; on a **persistence-restore** read ⇒ keep current in-memory state and retry later, never "reset to default and write it back"; on a **write** `Failed` ⇒ surface to the caller (UI toast or log), never pretend success.

**Tests:** one test per migrated consumer that injects an unreadable store (DB not ready / closed) and asserts the table's "what it must do instead" column.

**Acceptance:** `Unreadable` is handled explicitly in every migrated consumer; the migration list is recorded in the phase report; no `?: ""`/`?: default` fallback remains on a migrated read.

---

### F7 — JDBC `Statement` leak on the 2-second hot path `[V-repro]`

**Problem.** 25 of the 39 `createStatement()` calls in `Database.kt` are never closed (all 60 `prepareStatement(...)` calls are correctly `.use`d). They are of two kinds, and only one kind leaks native memory:
- **18 `executeQuery` sites — real leak.** `Statement.executeQuery(String)` prepares a native statement and registers it in `DB.stmts` — a strong `ConcurrentHashMap.newKeySet()` (`DB.java:58`, `:265`) — until `Statement.close()` removes it (`DB.java:285`). `ResultSet.close()` only *resets* the statement; it finalizes it only when `closeStmt` is set, which it is not for `Statement.executeQuery(String)` (`JDBC3Statement.java:81-98`, `CoreResultSet.java:126-154`) **[V-src]**. `.executeQuery(...).use { rs -> … }` therefore frees nothing.
- **7 `executeUpdate` sites — hygiene only.** `executeUpdate(String)` runs through `executeLargeUpdate`, which calls `db._exec(sql)` and `internalClose()` in a `finally` (`JDBC3Statement.java:112-114, 122-152`) **[V-src]**; no native statement is retained. They are still wrapped so the hygiene test (F7-b) can be strict.

`ProcessMonitor.refreshCaches` runs every 2 s (`ProcessMonitor.kt:275-297`, loop `:378-387`) and calls five reads, **three of which leak**: `getEnabledBlockProcesses` (`Database.kt:1158-1163`), `getEnabledNetworkCutoffRules` (`:1628-1635`), `getBlockRules` (`:1147-1155`).
Measured **[V-repro]**, three saved runs of the dedicated test (`Repro3` `leak`/`control`; raw output in the evidence zip): 5,000 refreshes (15,000 leaked statements) → `DB.stmts` = 15,003, 15,003 and 15,005, RSS growth +73, +74 and +74 MB (for example 57→130 MB), Java heap flat (2→3 MB); control with 15,000 properly closed reads → `DB.stmts` = 3, 3 and 5, RSS growth +6, +9 and +5 MB. **Extrapolation [INF, 0.5]:** ~43,200 refreshes/day × 3 ≈ 130k statements/day ≈ several hundred MB/day of native memory if the app runs continuously; I did not run it for days or on Windows.

**Sites (all in `Database.kt`; each is the line of the `createStatement()` call):**
- *`executeQuery` — leak (18):* 346, 353, 508 (startup, once per `init`), 776, 812, 1092, 1098, 1148, 1159, 1186, 1229, 1361, 1376, 1513, 1619, 1629, 1669, 1702.
- *`executeUpdate` — hygiene (7):* 530 (startup), 1003, 1141, 1142, 1398, 1421, 1450.

**Required change:** wrap each as `connection.createStatement().use { st -> st.executeQuery(sql).use { rs -> … } }` (reads) or `.use { it.executeUpdate(sql) }` (writes). Mechanical; no SQL or behaviour change. Do the 18 leaking sites first, ~6 per commit so a regression is easy to bisect, then the 7 hygiene sites.

**Tests**
- `F7-a` `StatementLeakAssert`: call each of the five `refreshCaches` reads 1,000× → `DB.stmts` does not grow.
- `F7-b` **source-hygiene test** (`StatementHygieneTest`): scan `Database.kt` text and fail if any `createStatement()` is not immediately followed by `.use`. This keeps new code from re-introducing the leak. (Use the balanced-parenthesis scan from Appendix A; a naive regex mis-handles the multi-line SQL.)
- `F7-c`: public read methods used by the Dashboard/Stats screens, run once each → `DB.stmts` returns to its baseline.

**Acceptance:** F7-a/b/c fail before and pass after; the scanner reports 0 unwrapped `createStatement()`.

---

### F8 — PIN checks must not fail open `[V-repro]`

**Problem.** `GlobalPin.verify` (`GlobalPin.kt:43-47`), `SessionPin.verify` (`SessionPin.kt:38-55`) and `NuclearPin.verify` (`NuclearPin.kt:43-46`) return `true` when the stored hash is `null` **or blank**; `isSet()` is `getSetting(KEY)?.isNotBlank() == true`. Because `getSetting` returns `null` on any read failure, a failed read means "no PIN" → everything passes. `SessionPin.kt:40-53` even documents the hazard ("a transient DB read error would return null … Both must correctly deny access") and then relies on a caller-side `isSet()` guard — but `isSet()` reads through the same lossy path, so the guard fails in exactly the case the comment describes. Reproduced: with a PIN stored and the DB handle lost, `verify("wrong")` returned `true`.
Writes are swallowed too: `GlobalPin.set/autoGenerate` (`:25-40`) call `setSetting`, which discards `SQLITE_BUSY`/not-ready failures (reproduced under a second writer). `autoGenerate()` then **returns the PIN to the UI to display**, so the user can be shown a PIN that was never saved and is "protected" by nothing.
Hash: unsalted SHA-256 (`GlobalPin.kt:65-68`). Note that all protection state sits in a user-writable SQLite file, so PINs are a **friction mechanism, not a security boundary** — keep that wording out of any "secure" claims (R2).

**Required change** (builds on F12)
1. `enum PinState { NOT_SET, SET, UNKNOWN }` from `readSetting`; `isSet()` callers that gate an action treat `UNKNOWN` as `SET`.
2. `verify(raw)`: `NOT_SET` → `true` (unchanged semantics); `SET` → hash compare; `UNKNOWN` → **`false`** (deny), and the UI says "Couldn't read PIN settings — try again" rather than "wrong PIN".
3. `set/autoGenerate/clear/resetWithoutPin` return a result (or throw) based on `writeSetting`; `autoGenerate` must **read the hash back** and compare before returning the PIN; on failure return no PIN and surface an error.
4. Replace the misleading comment block at `SessionPin.kt:40-53` with an accurate one.
5. **Out of scope / OWNER DECISION:** hashing strength (salted KDF) and the "forgot PIN" recovery design (see `ChangelogScreen.kt:54`).

**Tests:** `F8-a` stored hash + DB closed → `verify(any)` is `false`; `F8-b` no hash + DB healthy → `true`; `F8-c` busy writer → `set()` reports failure and `isSet()` is still `false`; `F8-d` `autoGenerate` read-back mismatch → no PIN returned; `F8-e` each of Global/Session/Nuclear.

**Acceptance:** no PIN API returns `true` for an unreadable store; all F8 tests fail before / pass after.

---

### F6 — Recurring tasks multiply every day `[V-repro on a verbatim logic copy]`

**Problem.** `RecurringTaskService.generateForToday` (`RecurringTaskService.kt:60-88`) loads **all** rows with `recurring = 1` as "templates" (`Database.getRecurringTemplates`, `Database.kt:1006-1016`), but the copies it creates inherit `recurring = true` (`template.copy(...)`, `RecurringTaskService.kt:75-82`). The `existing` set is computed **once** before the loop (`RecurringTaskService.kt:66-68`) and never updated. Day 1: 1 template → 1 copy. Day 2: 2 rows are "templates", `existing` is empty for the new date, so **both** insert a copy → 4 rows with `recurring=1`. The count doubles daily. Measured on the real DB with the function body copied verbatim (the real coroutine service was not run): day +12 → 2,048 tasks shown for that date and 4,096 rows with `recurring=1`. The `@Synchronized` comment (`RecurringTaskService.kt:50-60`) guards a different race (two callers), not this one.

**Required change**
1. **Schema (new migration v10, R14):** `ALTER TABLE tasks ADD COLUMN recurring_parent_id TEXT` (guard with `PRAGMA table_info`, F14). `Task` gets `recurringParentId: String? = null`; update `upsertTask` (`Database.kt:900-925`) and `rowToTask` (`:1875-1876` area). Bump `TARGET_VERSION` to 10 (`:505`).
2. `getRecurringTemplates`: `WHERE recurring = 1 AND recurring_parent_id IS NULL`.
3. `generateForToday(today: LocalDate = LocalDate.now())`: copies get `recurringParentId = template.id`; **add each created key to `existing`** inside the loop (belt and braces).
4. UI badge (`ui/components/TaskCard.kt:127`) unchanged — copies keep `recurring = true` so the badge still shows.
5. **Repair existing polluted databases** (users who already ran ≥ 2 days): a one-time routine, run only after a verified backup (R3), **dry-run first**: group `recurring = 1` rows by `lower(trim(title))`, `scheduled_time`, `lower(recurring_type)`; in each group the row with the earliest `created_at` stays the template, every other row gets `recurring_parent_id = <template id>`. **Mark, never delete.** Whether to offer deleting the duplicate rows for today/future is an **OWNER DECISION**.

**Tests**
- `F6-a`: 3 templates (daily, weekdays, weekly), simulate 30 days via the `today` parameter → exactly one task per eligible template per day; `COUNT(*) WHERE recurring=1 AND recurring_parent_id IS NULL` stays 3.
- `F6-b`: calling `generateForToday` twice for the same date adds nothing.
- `F6-c`: pre-fix pollution fixture (generate 10 days with the old algorithm) → dry-run reports the groups and changes nothing; real run marks parents and creates a backup first; then further generation does not grow the table.
- `F6-d`: v9 → v10 migration keeps all rows, `recurring_parent_id` NULL.

**Acceptance:** F6-a…d fail before / pass after; the repair report lists counts only (no user text).

---

### F9 — Orphaned "unfinished focus session" rows `[V-repro][V-code]`

**Problem.** `hasUnfinishedFocusSession()` (`Database.kt:1317-1333`) is true for any `focus_sessions` row with `end_time IS NULL AND completed = 0`, with **no age bound**. `FocusSessionService.start()` writes such a row (`FocusSessionService.kt:98-116`); `end()` rewrites it later (`FocusSessionService.kt:169-188`). If the process is killed, loses power, or the end-write is swallowed or lost, the row stays forever. Two paths that *do* call `end()` still depend on that asynchronous write: the crash cleanup (`CrashReporter.kt:542`) and the normal quit path (`Main.kt:162-163` calls `end()` and then `dispose()`, which does `scope.cancel()`, `FocusSessionService.kt:253-260`). **[INF]** both leave a window in which the write can be lost or cancelled; I did not reproduce that window. `UninstallProtectionService.activeRequirements` ORs the row into "session active" (`UninstallProtectionService.kt:178-206`): Quit then demands the Session PIN, and Uninstall is **blocked** ("cannot be uninstalled during an active focus session") unless a Global PIN exists. Reproduced with a 3-day-old orphan (Repro4 `orphan`). No code restores a session after restart (searched `FocusSessionService.kt`, `StartupBootstrap.kt`; `resume()` is pause/resume).

**Required change**
1. `reconcileOrphanFocusSessions(dryRun: Boolean): Int`, run once in `StartupBootstrap.startServices` **before** services start (no session can be active yet): inside `inTransaction`, `UPDATE focus_sessions SET end_time = start_time, interrupted = 1 WHERE end_time IS NULL AND completed = 0`. **Backup first** (R3, F15); if the backup fails, skip the repair and log. *(End-time rule `= start_time` avoids inflating focus statistics; the owner may prefer another rule — OWNER DECISION.)*
2. Bound the gate: `hasUnfinishedFocusSession()` also requires `start_time >= now − 12 h` (named constant; parameter, not SQL `now`, so it is testable). This protects the wizard JVM, which never runs the reconciliation.

**Tests:** `F9-a` orphan older than the bound → gate false; `F9-b` dry-run changes nothing and returns the count; `F9-c` real run creates a backup first and is idempotent; `F9-d` a genuinely active (recent) row still counts.

**Acceptance:** F9-a…d pass; the uninstall/quit gate never counts a row older than the bound.

---

### F13 — Session start/end writes are unordered, unchecked, swallowed `[V-code]`

**Problem.** `start()` and `end()` each launch an independent `scope.launch(Dispatchers.IO)` that calls `Database.insertSession` (an `INSERT OR REPLACE`) inside `try { … } catch (_: Exception) {}` (`FocusSessionService.kt:98-116` and `FocusSessionService.kt:169-188`). Nothing orders the two jobs, so a fast start→end can persist the end row first and then be **replaced** by the start row (`end_time = NULL` again → F9). Failures are invisible.

**Required change:** keep a reference to the start-write `Job` and make the end-write `startWriteJob?.join()` first; log failures via `EnforcementLog.warn` (as the cache loop does) instead of swallowing; add a small `sessionWriter` seam for tests. `dispose()` (`FocusSessionService.kt:253-260`) should wait (bounded, e.g. 2 s) for the pending end-write before `scope.cancel()`, because the quit path calls `end()` and `dispose()` back-to-back (`Main.kt:162-163`) **[INF]**.
**Tests:** `F13-a` slow start-write + immediate end → final row has `end_time`; `F13-b` a failing write is logged (captured logger), not swallowed; `F13-c` `end()` immediately followed by `dispose()` still persists the end row.
**Acceptance:** F13-a/b fail before / pass after. F13-c must pass after; I did not reproduce the dispose race, so record whether it already passes before the change.

---

### F11 — The `--uninstall` JVM must not run the full `Database.init` `[V-repro stress][V-code]`

**Problem.** `UninstallProtectionService.prepareForUninstallWizard` calls `Database.init(allowRecovery = false)` (`:51`) in a **second process** while the main app may be running. That executes the whole startup: it **copies and SHA-256s the live `-wal`/`-shm` files** (`Database.kt:216-239`), runs `quick_check`, `PRAGMA journal_mode=WAL`, `wal_checkpoint(TRUNCATE)` and migrations (`:332-385`), and — on any non-`Ready` first result **including BUSY** — runs `restoreSidecarSnapshots` (`:143`, `:241-267`), which moves live sidecars aside and copies older snapshots back if they changed in the meantime. Measured while another process wrote every N ms and the wizard-style init ran 150–300 times: in four saved runs (the numbers vary with timing) a **1000 ms writer → 0/150 failures (all four runs); 20 ms → 2, 1, 1 and 0 failures per 300; 1 ms → 75, 28, 33 and 64 failures per 300**, all `IOException: Could not verify startup snapshot for focusflow.db-wal|-shm`, which the wizard reports as "could not read its protection state safely". Real-app write rates are far lower (light-load row), so the **likelihood is low**; the design is still wrong, and the destructive `restoreSidecarSnapshots` path against a live peer is **[INF]**: on Windows it should fail on a sharing violation (SQLite opens files with `FILE_SHARE_READ|WRITE`, not DELETE), on POSIX it would replace the live WAL.

**Required change:** add `Database.initReadOnlyForWizard(dbFile): DbInitResult` — opens `SQLiteConfig().setReadOnly(true)` with a short busy timeout, **no** snapshots, **no** `quick_check`, **no** pragmas that write, **no** checkpoint, **no** migration, **no** recovery; a missing DB file ⇒ "no protection state" (allow). `prepareForUninstallWizard` uses it. The wizard never writes.

**Tests:** `F11-a` the wizard open leaves the DB, `-wal`, `-shm` byte-identical with unchanged mtimes; `F11-b` (tag `stress`) writer at 1 ms + 300 wizard opens ⇒ 0 failures; `F11-c` reads `standalone_block_*`, `always_on_enforcement`, `nuclear_mode` and PIN hashes from a v9 fixture; `F11-d` missing file ⇒ no protections.

**Acceptance:** F11-a…d pass; `UninstallProtectionService` no longer calls `Database.init`. **WIN-VERIFY:** uninstall wizard while the app is running and writing.

---

### F17 — `VpnBlocker` caches a failed read forever `[V-code]`

**Problem.** `isEnabled` caches `(getSetting("vpn_block_enabled") == "true")` on first read (`VpnBlocker.kt:99-100`); `getCustomProcesses` caches an empty list when the read returns `null` (`:106-116`, the comment at `:109-110` makes that deliberate). One transient failure therefore disables VPN blocking until the user toggles it. The setter sets the cache **before** the swallowed write (`:101-104`), so the UI state and the persisted state can diverge silently.

**Required change** (after F12): the hot path (`isVpnProcess`) reads only volatile fields. Load/refresh them from `ProcessMonitor.refreshCaches()` (already runs every 2 s) via `readSetting`; on `Unreadable` keep the last good value and do not cache a default; setters use `writeSetting` and revert/notify on failure.
**Tests:** `F17-a` unreadable first read does not stick (next successful read updates it); `F17-b` failed write is reported and cache not changed; `F17-c` the hot path performs no DB read (stub `Database` throws on access).
**Acceptance:** F17-a…c fail before / pass after.

---

### F15 — Backups cannot be used, and "verification" is cosmetic `[V-code]`

**Problem** (`AutoBackupService.kt`):
- `runBackupNow` "verifies" by hashing the backup file twice (`:110, :114`); that proves the copy is stable, not that it is a **valid SQLite database**. No `quick_check`/`integrity_check` is run on the backup.
- `verifyBackup` returns `true` when no `.sha256` exists (`:159-161`), so legacy/hash-less files count as verified.
- `runBackupIfNeeded` ignores the `BackupResult` (`:138, :147`) — failures are silent; there is no log or status.
- `todayBackup.path.replace(".db", ".sha256")` (`:145-146`) replaces every `.db` substring in the whole path; `verifyBackup` correctly uses `nameWithoutExtension` (`:160`).
- `restoreBackup` (`:186-219`) has **no callers** (dead). It copies over the live database while the app holds it open and ignores stale `-wal`/`-shm`; its safety snapshot uses `VACUUM INTO`, which **fails when the destination exists and is non-empty** (reproduced), so the second restore would roll back to a stale snapshot.
- All backups live in the same directory as the DB (`:49-54`): they do not survive loss of that folder or disk.
- When corruption triggers automatic recovery, the corrupt set is preserved as `.broken_*` and a **fresh empty DB is created** (`Database.kt:161-176`, `:387-433`); the only trace is a log line (`:418`) — no UI references `.broken`/recovery (searched), and nothing offers to restore a backup. So the user silently "loses" their data while backups sit unused **[V-code]**.

**Required change**
1. After `VACUUM INTO`, open the backup **read-only** and run `PRAGMA quick_check`; only then write the `.sha256`/`.meta.json`; otherwise delete and return `VerificationFailed`.
2. A backup without a hash is `Unverified`, not `true`.
3. Log every non-success `BackupResult` (EnforcementLog) and persist `last_backup_status` for a future UI.
4. Fix the `replace(".db", …)` calls to use `nameWithoutExtension`.
5. Mark `restoreBackup` `@Deprecated(level = DeprecationLevel.ERROR, message = "unsafe: copies over a live WAL database")`. A real restore (close DB → swap file → delete stale `-wal`/`-shm` → reopen) is **new work for the owner to approve**, as is a second backup location (e.g. a user-chosen folder) and a "your data was reset, restore from backup?" notice after automatic recovery.
**Tests:** `F15-a` backup of a valid DB passes; a truncated/garbage copy fails verification and is deleted; `F15-b` hash-less file ⇒ `Unverified`; `F15-c` failure is logged; `F15-d` same-second double backup does not leave a half file.
**Acceptance:** F15-a…d pass; `restoreBackup` cannot be called without a compile error.

---

### F14 — Swallow-all `ALTER TABLE` in migrations `[V-code]` (P2)

`migrateV1` (`Database.kt:666-670`), `migrateV2` (`:686`), `migrateV3` (`:694-695`) wrap `ALTER TABLE … ADD COLUMN` in `try { } catch (_: Exception) {}`. The expected failure is "duplicate column name"; but `SQLITE_BUSY`/`IOERR`/read-only failures are swallowed too, and because the whole migration runs in one transaction (`:517-538`) the version bump can then **commit with a column missing** — later queries fail with "no such column" in unrelated places **[INF; not reproduced]**.
**Rule R14 forbids editing existing `migrateVn()`**, so: (1) leave v1–v9 untouched; (2) add `verifySchema()` after `migrate()` that checks the expected columns via `PRAGMA table_info` (`tasks.skipped, focus_mode, focus_intensity, focus_blocked_apps, focus_require_pin`; `daily_completions.total_count, focus_minutes`; `block_rules.source`) and fails init with a typed `SchemaMismatchException` that names the missing column; (3) new migrations (v10+) use a guarded `addColumnIfMissing` helper built on `PRAGMA table_info`, never `catch (_: Exception)`.
**Tests:** `F14-a` a DB lacking `tasks.skipped` at `user_version = 9` → init fails with `SchemaMismatchException("tasks.skipped")`; `F14-b` healthy v9 passes.

---

### F18 — No enforcement at all while DB init is failed `[V-code]` — **OWNER DECISION**

`StartupBootstrap.startServices` runs only after `Ready` (`Main.kt:227-253`), so while the DB is `Failed` no blocking, schedules, Nuclear Mode or Always-On run; the user sees the gate window (`Main.kt:343-380`) and nothing says that blocking is off. On a persistent native-load failure the watchdog relaunches the process every ≤ 2 minutes (§3.5), so this is a steady state, not a blip.
**Options for the owner:** (A) keep as is but show an explicit "Blocking is OFF until the database opens" line on the gate screen and tray (small, no policy change — the agent may do this one); (B) persist an enforcement snapshot (block list only) in a plain file written after each change and run a *degraded* blocker from it when the DB is down (significant design, needs its own spec); (C) status quo. **Do not implement B without a written owner decision and a separate design note.**

### F16 — Global monitor and caller threads `[INF]` (P2, measure first)

Every public `Database` method is `@Synchronized` on the one `Database` object around one `Connection`, and `init` holds the monitor for up to the 35 s retry budget (`Database.kt:120-177`, `InitPolicy`). Which callers run on the Compose/AWT thread was **not** audited line by line (a syntactic scan of all non-Database files found 146 DB-call sites not lexically inside `Dispatchers.IO/Default`/a thread, but that count only means "the call site has no dispatcher in its own lexical scope" — many are service helpers whose callers I did not trace; it is not a measure of UI-thread calls). **Measure before changing anything:** add a debug-only wrapper that records (a) monitor wait time per call and (b) whether the caller is the AWT event thread, writing the top offenders (with a one-line call-site) to the debug log. Run a 10-minute manual session and report the top 10 in the phase report. Any restructuring afterwards is a separate owner-approved task.

### F19 — Mid-run DB loss `[INF]` (P2, design note only)

After `Ready`, a lost/closed connection makes `connection` throw `DatabaseUnavailableException` (`Database.kt:102-108`); `getSetting`/`setSetting` swallow it; `ProcessMonitor.refreshCaches` logs and keeps stale caches (`ProcessMonitor.kt:378-387`). With F8/F12 the security reads become fail-closed. Write a one-page design note proposing a `Database.health` StateFlow (`Healthy/Degraded/Lost`), an automatic reopen using the same `init` machinery, and a banner; **do not implement without the owner's approval.**

---

**Phase 2 exit criteria:** every non-owner-gated task above has passing tests; `PHASE-2-REPORT.md` lists owner-gated items (F6 repair deletion policy, F9 end-time rule, F15 restore/second location, F18 option, F19 note).

---
## 8. Phase 3 — P3: hardening and cleanup (F20–F30)

Small, independent tasks. Take them in any order **after Phase 2**, except **F28, which must be pulled forward** (see its entry). Each has the same shape as before, compressed.

### F20 — `DATE(column)` defeats the indexes; per-day N+1 queries `[V-code][V-repro on Python SQLite]`

**Problem.** Eight queries wrap the timestamp column in `DATE(...)`: `Database.kt:980` (`updateDailyFocusMinutes`), `:1070` (`getSessionsInDateRange`), `:1084` (`getTotalFocusMinutesToday`), `:1110` (`getFocusMinutesByDay`), `:1457` (`getSessionsInRange`), `:1470` (`getCompletedTasksInRange`), `:1479` (`getTemptationsInRange`), `:1494` (`getTemptationBreakdownInRange`). Indexes exist on `focus_sessions(start_time)` and `temptation_log(timestamp)` (`Database.kt:674-675`) but a function on the column cannot use them. `getFocusMinutesByDay(days)` runs one such query per day (`:1103-1119`) and `getRecentDayCompletions(84)` runs 84 lookups (`:1121-1138`), all under the global monitor.
**Measured** (Python's SQLite 3.45.1, **not** the app's engine; script `f20_dateindex.py`): 200,004 rows spanning 27 years → plan `SCAN focus_sessions` vs `SEARCH … USING INDEX idx_sessions_start`; **17.4 ms vs 0.007 ms** per day-query; results identical on 4,951 days incl. boundary rows (`T00:00:00`, `T23:59:59.999999999`). The cost is linear in row count, so at a realistic ≈ 12k rows expect ≈ 1 ms per query **[INF]**: this is P3 hardening, not an emergency.
`getTotalFocusMinutesToday` (`:1081-1089`) also reads `getInt(1)` **without** `rs.next()`. It works only because sqlite-jdbc pre-loads the first row (`JDBC3ResultSet.java:61-65`, `:330-332`) **[V-src]**; JDBC requires `next()`.

**Required change.** Timestamps are stored as `DateTimeFormatter.ISO_LOCAL_DATE_TIME` text (`Database.kt:95, 1034`), which sorts lexicographically. Replace `DATE(col) = ?` with `col >= ? AND col < ?` (day start inclusive, next day start exclusive, both as `yyyy-MM-dd` strings) and `BETWEEN a AND b` with `col >= a AND col < (b + 1 day)`. Replace the per-day loops with one `GROUP BY substr(col, 1, 10)` query over the window. Add `rs.next()` to `getTotalFocusMinutesToday`.
**Must NOT:** change any returned value, the stored format, or the schema.
**Tests:** `F20-a` randomized equivalence (old SQL vs new SQL, same fixture, every day in range, boundary timestamps); `F20-b` `EXPLAIN QUERY PLAN` of the new queries mentions `idx_sessions_start` / `idx_temptation_ts`; `F20-c` `getFocusMinutesByDay(7)` equals the pre-change result on a fixture; `F20-d` `getTotalFocusMinutesToday` on an empty table is 0.
**Acceptance:** F20-a…d pass; no `DATE(` remains on an indexed column.

---

### F21 — `completeTask` is not idempotent `[V-repro]`

**Problem.** `completeTask` (`Database.kt:936-953`) always runs `UPDATE tasks SET completed = 1, completed_at = ? WHERE id = ?` and then `recordDailyCompletion` (`:962-969`: `completed_count = completed_count + 1`), whether or not the task was already completed or even exists. Four UI call sites launch one IO job per click (`TasksScreen.kt:281, 304, 337`, `DashboardScreen.kt:504`). Reproduced on the real `Database` (`Repro5`): complete the same task twice → `completed_count` 1 → 2; complete a non-existent id → 3; `completed_at` is overwritten by the later call.
Impact is limited: the streak queries only test `completed_count > 0` (`Database.kt:1360-1387`), so streaks are unaffected; any per-day count that is displayed is inflated **[INF: I did not audit every screen that reads `daily_completions`]**.

**Required change** (after F10): inside `inTransaction`: `UPDATE tasks SET completed = 1, completed_at = ? WHERE id = ? AND completed = 0`; call `recordDailyCompletion` only if exactly one row changed; return that boolean. Public behaviour on first completion is unchanged.
**Tests:** `F21-a` second call does not change `completed_count` or `completed_at`; `F21-b` unknown id changes nothing; `F21-c` first completion still increments by exactly 1.
**Acceptance:** F21-a…c fail before / pass after.

---

### F30 — "Current streak" can never exceed 90 `[V-repro]`

**Problem.** `getCurrentStreak` (`Database.kt:1360-1373`) reads `… ORDER BY date DESC LIMIT 90`, so the result is capped at 90. Reproduced (`Repro6`): 120 consecutive qualifying days → `getCurrentStreak() = 90`, `getBestStreak() = 120` (no `LIMIT`, `Database.kt:1375-1387`). Users with a streak longer than 90 days see a "current" streak shorter than their own "best". It feeds the Dashboard, Stats, Profile, Active screen and the weekly report (`DashboardScreen.kt:113`, `StatsScreen.kt:393, 687`, `ProfileScreen.kt:87`, `ActiveScreen.kt:84`, `WeeklyReportService.kt:106`).
**This may be a deliberate display cap — the code does not say.** Ask the owner before changing it (§10).
**Required change (if the owner confirms it is a bug):** drop the `LIMIT` (the table has one row per active day) or page backwards until the first gap; keep the "yesterday still counts if today has no completion yet" rule (`:1367-1369`).
**Tests:** `F30-a` 120 days → 120; `F30-b` gap yesterday → 0 (or 1 if today completed); `F30-c` today empty, yesterday completed → counts from yesterday.

---

### F22 — "Clear all data" does not do what it says, and is not atomic `[V-code]`

**Problem.**
- The dialog says it will "permanently delete all sessions, tasks, notes, and settings" (`ProfileScreen.kt:408`), but the confirm handler calls only `clearAllSessions`, `clearAllTasks`, `clearNotes`, `clearTemptationLog` (`ProfileScreen.kt:417-420`) — i.e. `focus_sessions` + `daily_completions`, `tasks`, `daily_notes`, `temptation_log` (`Database.kt:1140-1143, 1002-1004, 1449-1451, 1420-1422`). **Settings (PINs, consent, protection flags), habits, block rules/schedules/allowances/usage and presets are not touched.** Whether "all data" should include them is an **OWNER DECISION** — wiping PINs or protection flags from an unguarded button would itself be a protection bypass, so the *text* is probably what is wrong.
- Four separate calls = four separate implicit transactions; `clearAllSessions` is itself two statements (`:1140-1143`). A failure in the middle leaves a partial wipe (e.g. sessions gone, `daily_completions` — the streak source — still there). No backup is taken before an irreversible delete, and the button is **not PIN-gated** (`ProfileScreen.kt:387` opens the dialog directly).
- `BackupService.clearAllData()` (`BackupService.kt:69-74`) is an unused duplicate of the same four calls (no callers; searched `src/main`).

**Required change**
1. `Database.clearUserActivity()`: the four clears inside one `inTransaction` (F10). `ProfileScreen` calls it; delete `BackupService.clearAllData`.
2. Before it runs, take a verified backup with the fixed `AutoBackupService.runBackupNow` (F15); if the backup fails, show the error and delete nothing.
3. Make the dialog text match what is deleted (e.g. "sessions, tasks, notes and the activity log"). **Do not extend the deletion to settings, rules, habits or PINs on your own** — OWNER DECISION. Whether the button should require the Global PIN is also an OWNER DECISION (policy).
**Tests:** `F22-a` an injected failure on the last clear leaves **all four** tables unchanged; `F22-b` success empties exactly those tables and nothing else (settings, rules, habits intact); `F22-c` backup failure ⇒ nothing deleted.

---

### F23 — CSV exports: three code paths, silent caps, lossy and inconsistent quoting `[V-code]`

**Problem.**
- Two session exporters behave differently. `BackupService.exportToCsv` (`BackupService.kt:12-40`) exports at most **1,000** sessions (`getRecentSessions(1000)`, `:26`), replaces `"` with `'` and strips CR/LF (`:33`). `StatsScreen.kt:436-460` exports at most **9,999** (`getRecentSessions(9999)`, `:441`) to a fixed path (`~/.focusflow/sessions_<date>.csv`, overwritten on a second export the same day), replaces `"` with `'` but does **not** strip newlines in `task_name`/`notes` — the very defect `BackupService.kt:30-32` says it fixed.
- Neither tells the user that the export was truncated (`ProfileScreen.kt:355-357`: "Sessions saved to <path>").
- Both exporters in `BackupService` swallow every exception into `null` (`:39, :66`), and the UI prints "Export cancelled" for both cancel and failure (`ProfileScreen.kt:355-357, 364-366`) — a full disk looks like the user pressing Cancel.
- Free text starting with `=`, `+`, `-` or `@` is evaluated by spreadsheet apps (CSV/formula injection) **[INF; self-export, low risk]**.

**Required change.** One `CsvExport` helper used by all three paths: RFC-4180 quoting (double the quote character instead of replacing it; keep the existing CR/LF→space rule), all rows exported (page through the table) or the exact `N of M` reported, and a result type `Saved(path, rows) | Cancelled | Failed(message)` that the UI renders distinctly. The formula-prefix guard and a UTF-8 BOM (Excel on Windows may mis-read BOM-less UTF-8 **[UNV]**) are **OWNER DECISIONS** — they change the file's content.
**Tests:** `F23-a` round-trip through a minimal RFC-4180 parser for titles with quotes, commas, newlines and non-ASCII; `F23-b` 1,500 sessions ⇒ 1,500 rows; `F23-c` unwritable target ⇒ `Failed`, not `Cancelled`.

---

### F24 — Crash/startup reports pile up on the Desktop with no limit `[V-code]`

**Problem.** `CrashReporter.writeLog` (`CrashReporter.kt:477-515`) writes `FocusFlow-crash-<timestamp>.log` to the **Desktop first**, then `~/.focusflow`, then the temp directory; it is called for fatal crashes (`:175`) and startup-failure reports (`:638`). Nothing ever deletes or rotates these files (searched the file for `delete`/prune logic: none). A persistent startup failure combined with the 2-minute watchdog relaunch (§3.5) would write a new file each cycle — up to 720 per day **[INF]**. Under OneDrive "known folder backup" the real Desktop is not `<user.home>\Desktop` **[INF]**, so the destination varies by machine.
**Required change (no deletion on the Desktop):** (1) de-duplicate: do not write a second report for the same root-cause signature within 60 minutes (log one line instead); (2) cap `~/.focusflow` and temp copies to the newest 20 files whose names match exactly `FocusFlow-crash-*.log`; (3) leave the Desktop-first policy as is until the owner decides (§10) — it is a UX choice (users can find the file), not a bug.
**Tests:** `F24-a` 30 reports with one signature within an hour ⇒ 1 file; `F24-b` retention keeps the newest 20 and never touches files that do not match the pattern.

---

### F25 — One place for application paths `[V-code]`

The database path is computed in four places: `Database.kt:201-202` (`defaultDbFile`), `Main.kt:124-126`, `AutoBackupService.kt:49-54` (`dbPath`, `backupDir`) and `CrashReporter.kt:402-405`. Other paths are built ad hoc as well (`EnforcementLog.kt:21`, `SingleInstanceGuard.kt:168`, `StatsScreen.kt:444`, `CrashReporter.kt:492, 659`; the UI shows a hard-coded string at `SettingsScreen.kt:831`). If one of them ever diverges, backups and crash reports would describe a different database than the one in use **[INF]**.
**Required change:** `object AppPaths { val dataDir; val dbFile; val backupDir; val logDir; val consentFile; val nativeDir }` where every property is a **getter** that reads `user.home` each time (so tests can redirect it). Replace the four DB-path computations first; the rest only if trivial. Behaviour must be byte-for-byte identical.
**Tests:** `F25-a` `AppPaths.dbFile == File(File(System.getProperty("user.home"), ".focusflow"), "focusflow.db")` for two different `user.home` values; `F25-b` no remaining `".focusflow"` literal outside `AppPaths` except the display string.

---

### F26 — `include("recovery")` points at a missing directory `[V-code]; Gradle behaviour [UNV]`

`settings.gradle.kts:3` includes a project `recovery`, but the audited archive contains no `recovery/` directory and nothing in `build.gradle.kts` depends on it. **Do not delete the line blindly:** the directory may simply have been left out of the zip. Run `./gradlew help` in P0-1 and record whether Gradle warns about the missing project directory (newer Gradle versions deprecate configuring a project without a directory — **I did not verify this for 8.6**). Then ask the owner (§10). If it is dead, remove the `include` line in its own commit.

### F27 — A Unix-only JVM flag in the packaged runtime `[V-src JDK 21][INF impact]`

`build.gradle.kts:55` passes `-Djava.nio.channels.spi.SelectorProvider=sun.nio.ch.PollSelectorProvider` to every packaged launch, with a comment that it is needed under MSIX. In the JDK source tree (tag `jdk-21+35`; I did **not** check JDK 19) that class exists only under `src/java.base/unix/classes/sun/nio/ch/`; the Windows tree has `WindowsSelectorProvider` and `WEPollSelectorProvider`. `SelectorProvider.provider()` honours the property by `Class.forName(...)` and turns a missing class into `ServiceConfigurationError` (`java/nio/channels/spi/SelectorProvider.java:111-126`). So on Windows **any** code that opens a selector (`Selector.open`, `SocketChannel.open`, `Pipe.open`, `java.net.http.HttpClient`) would throw an `Error` **[INF]**.
Nothing in the app's own code does so today: telemetry uses `HttpURLConnection` (`DiscordWebhookClient.kt:3-4, 28-30`) and `SingleInstanceGuard` uses `ServerSocket`/`FileChannel` **[V-code]** — which is why nobody has noticed. The module `java.net.http` is bundled (`build.gradle.kts:67-76`), so a future or transitive use is one import away.
**Original plan recommendation:** make no change until **W9** (§9) has been run on Windows (with and without the flag, including under MSIX). If W9 shows the flag is harmful or unnecessary, remove it in its own commit; if MSIX needs a fallback, use a Windows-valid class. **Never remove it on the strength of this audit alone.**

**Current owner decision (2026-10-15):** Keep the current flag unchanged and accept that Windows behavior is unverified. W9 is waived; do not remove or replace the flag unless the owner reopens D17.

### F28 — `CrashReporter` cannot initialise without a JVM property `[V-code]` — **pull this forward**

`CrashReporter.APP_VERSION` is `System.getProperty("focusflow.app.version") ?: error(...)` (`CrashReporter.kt:55-56`). The property is set only for the packaged app/Gradle `run` (`build.gradle.kts:49`); `tasks.test` sets nothing (`build.gradle.kts:34-36`). If it is missing, the object's static initialiser throws `ExceptionInInitializerError` and every later use throws `NoClassDefFoundError` — an **`Error` thrown by the component that is supposed to report errors**.
It matters for this plan: `Database.finishFailed` calls `CrashReporter.reportCritical` whenever the failing database is the **default** path (`Database.kt:463-470`). Any F2/F3/F4 test that redirects `user.home` and uses the default path will hit `CrashReporter`'s initialiser inside `Database.init` and fail for a reason unrelated to the fix. (The existing tests use temp-file paths, so they never get there. My audit harness replaced `CrashReporter` with a recording stub, which hid this.)
**Required change (do this first in Phase 1, together with F4.0):** `?: "unknown"` instead of `error(...)`; in `tasks.test` add `systemProperty("focusflow.app.version", project.version.toString())` and `systemProperty("focusflow.telemetry", "off")` (the F4.0 kill switch).
**Tests:** `F28-a` in a JVM without the property, `CrashReporter.APP_VERSION == "unknown"` and `reportCritical` does not throw.

---

### F29 — Test gaps `[V-code]`

Existing tests cover `Database` init/busy/corrupt/recovery/v9 migration and the allowance subsystem (`DatabaseTest.kt`, `AllowanceEngineTest.kt`, …) and nothing else in the database-adjacent code. The allowance tests are the in-repo model for the style requested here (fake ports; `failedReloadReturnsFailureAndKeepsTheLastKnownAllowances`, `unavailableUsageStoreDoesNotStartTracking`). Required new test classes (each finding above lists its cases):

| New test file | Covers |
|---|---|
| `data/NativeSqliteTest.kt` (+ fresh-JVM cases) | F1, F2, F3-d |
| `data/DatabaseInitSafetyTest.kt` | F2, F10, F14, F11 |
| `data/SettingsReadWriteTest.kt` | F12 |
| `data/StatementHygieneTest.kt` | F7 |
| `data/DayQueriesTest.kt` | F20, F21, F30 |
| `services/PinTest.kt` | F8 |
| `services/RecurringTaskServiceTest.kt` | F6 |
| `services/FocusSessionPersistenceTest.kt` | F9, F13 |
| `services/AutoBackupServiceTest.kt` | F15 |
| `services/TelemetryConsentTest.kt`, `services/CrashReporterSummaryTest.kt` | F3, F4, F24, F28 |
| `services/CsvExportTest.kt` | F23 |
| `services/UninstallProtectionServiceTest.kt` | F5, F11 |
| `enforcement/VpnBlockerTest.kt` | F17 |

---

**Phase 3 exit criteria:** every non-owner-gated task above has passing tests; `./gradlew test` is green; `PHASE-3-REPORT.md` lists the owner-gated items and D17's accepted unverified W9 status (F22 scope/PIN, F23 BOM/formula guard, F24 Desktop policy, F26 `recovery`, F30 intent).

---
## 9. Windows verification checklist (human only)

Nothing in this plan was run on Windows. The agent prepares scripts and notes; **Himanshu (or a tester) runs these and ticks them. The agent must never tick a box here (R2, R11).** Use a throw-away VM or a spare user account — several steps deliberately break the machine's temp folder or install a DLL-blocking policy.

| ID | Scenario | How | Pass criterion | Finding |
|---|---|---|---|---|
| **W1** | Clean install, nothing exotic | Per-user EXE install on a fresh Windows 10/11 VM; launch | DB opens; `%LOCALAPPDATA%\FocusFlow\native\sqlite-3.47.1.0\<os-arch>\sqlitejdbc-<hash>.dll` exists; no new `sqlite-3.47.1.0-*-sqlitejdbc.dll` appears in `%TEMP%` | F1 |
| **W2** | **Broken `%TEMP%` — also the hypothesis test for the reported error** | Set the *user* variables `TEMP` and `TMP` to a folder that does not exist; sign out and in. First run it on the **currently released build**, then on the fixed build | Released build: if the gate screen shows "Error opening connection" and Retry then crashes, the root-cause class in §3 is confirmed on Windows. Fixed build: starts, DB Ready | F1, F2, F3 |
| **W3** | DLL load blocked from user-writable temp | Use whatever your Windows edition can enforce (AppLocker DLL rule, WDAC, Software Restriction Policy) or a third-party AV/EDR that blocks DLLs loaded from `%TEMP%`; **audit mode first** | Released build fails as in the traces. Fixed build: starts via the `%LOCALAPPDATA%` ladder step. If the policy also blocks that location: gate screen shows the full cause chain and **does not crash on Retry**; F1b is then needed | F1, F1b, F3 |
| **W4** | Odd profile path | Local user with a space and non-ASCII characters (e.g. `Zoë Müller`); install per-user; launch | DB opens, native library loads, crash reports and backups land under that profile | F1, F25 |
| **W5** | Install matrix | EXE (per-user), MSI, MSIX/Store: for each — start; where the native library ended up; uninstall from Settings → Apps | Same behaviour in all three; MSIX path documented | F1b, F5 |
| **W6** | Uninstall wizard | (a) app running and writing, DB healthy; (b) native library unavailable (reuse W2/W3 setup); (c) Nuclear/Standalone requirement active | (a) wizard opens quickly and `focusflow.db`, `-wal`, `-shm` are byte-identical before/after (F11); (b) the owner-chosen F5 outcome — not a dead end; (c) unchanged from today | F5, F11 |
| **W7** | Watchdog relaunch loop | In the persistent-failure setup of W2/W3: kill the process, watch relaunches for 30 min, use a **test** webhook endpoint (never production) | Relaunch ≤ 2 min; number of crash files and telemetry posts stays bounded (F24 de-duplication, F4 consent) | F4, F18, F24 |
| **W8** | Backup/restore against an open database | Copy/move `focusflow.db-wal`/`-shm` while the app runs; `VACUUM INTO` onto an existing file; run the fixed `restoreBackup` replacement if one is built | Record the real Windows errors (sharing violation expected **[INF]**); the app never ends up with a mismatched db/wal pair | F11, F15 |
| **W9** | The `SelectorProvider` flag | With a full JDK of the **same major version as the bundled runtime**, run a one-class program that calls `java.nio.channels.Selector.open()` once with and once without `-Djava.nio.channels.spi.SelectorProvider=sun.nio.ch.PollSelectorProvider`; repeat the MSIX scenario the flag was added for | Would record whether the flag throws `ServiceConfigurationError` on Windows and whether MSIX fails without it. **Waived by owner 2026-10-15:** keep the current flag and accept behavior as unverified; do not claim W9 passed | F27 |
| **W10** | First-run consent | Fresh profile, packet capture or firewall prompt, no internet exceptions | **No** outbound telemetry before the user answers; the answer survives a DB failure; opt-out stops all posts | F4 |
| **W11** | 24-hour soak | Run the released build and the F7-fixed build for 24 h; sample Private Bytes at 0/6/12/24 h | Released build grows; fixed build stays flat. (My Linux numbers are a linear extrapolation — this replaces them with a real figure.) | F7 |
| **W12** | Recurring tasks | After F6: create one daily recurring task; advance the clock 3 days (or wait); run the repair **dry run on a copy** of a real polluted DB | Exactly one task per day; dry run changes nothing | F6 |
| **W13** | OneDrive Known-Folder Backup | Account with Desktop redirected into OneDrive; trigger a test crash report | Note where the log really lands | F24 |
| **W14** | Packaged layout | Look at the installed app folder; read `System.getProperty("compose.application.resources.dir")` from a debug build | Settles the **[UNV]** details of F1b | F1b |
| **W15** | Large data | DB with > 1,000 sessions: export CSV from Profile and from Stats; "Clear all data" | Row counts match; clear is all-or-nothing | F22, F23 |

---

## 10. Owner decisions (Himanshu)

The agent must **stop and ask** at each of these; none is the agent's to decide.

| # | Decision | Options | My recommendation | Blocks |
|---|---|---|---|---|
| D1 | **Rotate both Discord webhooks** (owner action, do it now) | Rotate and stop shipping the secret; send telemetry through a small server-side relay that holds the secret and rate-limits | Rotate immediately — Base64 is not protection and the strings are in every installer **[V-code]** | F4 step 6 |
| D2 | Crash-report consent default | **Opt-in** (nothing is sent until the user says yes) vs the current opt-out | Opt-in: the app is marketed as privacy-first | F4 |
| D3 | Wording of the consent dialog / Store privacy text | The claims "No personal information is ever collected" and "Your name, email, or IP address" (`TelemetryConsentDialog.kt:83, 99`) vs what a webhook POST reveals (sender IP) and what paths may contain | Reword; this is also a legal/wording question I cannot settle | F4 |
| D4 | Uninstall when the DB cannot be read | A: allow after an explicit confirmation phrase; B: stay locked but print the manual removal path; C: mirror a flag outside the DB | A | F5 |
| D5 | Ship the SQLite DLL inside the install folder | Approve / defer | Approve after W1–W3 results | F1b |
| D6 | Upgrade sqlite-jdbc | Now / later / never | Later, as its own change; **I did not check what newer releases change** | — |
| D7 | Duplicate recurring-task rows already in users' databases | Mark only (default) / offer deletion of future duplicates | Mark only | F6 |
| D8 | End time for orphaned focus sessions | `= start_time` (proposed, does not inflate stats) / planned end / delete | `= start_time` | F9 |
| D9 | Backups | Second location (user-chosen folder)? Restore UI? "Your data was reset — restore?" notice after automatic recovery? | Yes to all three, as a separate design | F15 |
| D10 | Behaviour while the DB is down | A: show "Blocking is OFF" (agent may do); B: degraded blocker from a plain file (needs a design note); C: status quo | A now, B only if failures prove common | F18 |
| D11 | Mid-run DB loss design | Approve the design note | — | F19 |
| D12 | PIN design | Salted KDF hashing; "forgot PIN" flow; wording — protection state lives in a user-writable file, so say "friction", not "secure" | Separate project | F8 |
| D13 | "Clear all data" | What "all" means (settings? rules? habits? PINs?); require the Global PIN? | **Approved 2026-10-15:** clear only sessions/completion stats, tasks, daily notes, and temptation logs; preserve settings, rules, habits, allowances, PINs, and presets; fix wording; require the Global PIN | F22 |
| D14 | CSV details | Formula-injection guard; UTF-8 BOM for Excel | Guard on, BOM on **after** testing in Excel | F23 |
| D15 | Crash-log placement | Keep Desktop-first / move to `~/.focusflow/logs` | Move, with a "show me the log" button | F24 |
| D16 | `recovery` Gradle module | Still needed? | Ask before touching | F26 |
| D17 | `SelectorProvider` flag | **Resolved 2026-10-15:** keep current flag; waive W9 and accept unverified Windows behavior | — | F27 |
| D18 | Streak cap of 90 | Intentional? | If not intentional, fix | F30 |
| D19 | Threading restructure | Approve only after the F16 measurements | — | F16 |

---

## 11. Out of scope — do not touch

- **Enforcement semantics:** Nuclear Mode, Standalone Block, block lists/schedules, allowances, the kill switch, overlays, keyword blocker. Only the way they *read/write the database* changes (F12, F17).
- **UI design.** Functional text changes only where a task says so (F1, F3, F5, F22, F23). Match the localisation approach of the screen you are editing (check whether it goes through `LocalizationManager.strings`); do not add translation keys on a screen that does not use them.
- **Packaging:** installer, MSIX, Store listing, signing — except the approved F1b.
- **Dependencies:** no version bumps (D6).
- **The Android app**, the website, anything outside this repository.
- **Telemetry content and wording** beyond F3/F4.
- **User data** of any kind (R3).

---

## Appendix A — Reproduction evidence

**Environment.** Linux 6.18, OpenJDK **21.0.12** (the app's toolchain is **19**, `build.gradle.kts:90-94`), Python 3.13, Kotlin compiler 1.9.22. sqlite-jdbc **built from the GitHub tag `3.47.1.0`** because Maven Central was not reachable from my sandbox — so the DLLs I inspected are the tag's resources, not the exact jar your build resolves. The app's own `Database.kt`, `GlobalPin.kt`/`SessionPin.kt`/`NuclearPin.kt`, `Task`/`FocusSession` models etc. were compiled **unmodified**; the only substitutions were a recording stub for `CrashReporter` (so **nothing was ever sent over the network**) and compile-only stand-ins for `StateFlow`. `Repro1`–`Repro6` always ran with `-Duser.home=<scratch dir>`; no real user data was involved.

> **Do not run `Repro1` or `Repro2` against the real `CrashReporter`.** The first failed `init` on the default path would POST to the production webhook (that is F4). Run them after F4.0 with `-Dfocusflow.telemetry=off`, or keep the stub.

Re-run shape: `java -Duser.home=<SCRATCH> [-Dorg.sqlite.tmpdir=<dir>] -cp <HARNESS>:<APP-CLASSES>:<kotlin-stdlib.jar>:<sqlite-jdbc.jar> Repro1Kt` (`Repro3Kt` takes `prep|race <n>|control|leak|sum`, `Repro4Kt` takes `orphan|txerror`, `Repro6Kt` takes a day count). The sources, stubs and raw outputs ship alongside this file in `focusflow-audit-evidence.zip`.

| Finding | Script | Observed (my sandbox) |
|---|---|---|
| F1/F2/F3 — control | `Repro1` | `init` #1 `Ready`, `init` #2 `Ready` |
| F1/F2/F3 — **fault 1** `-Dorg.sqlite.tmpdir=/nonexistent/focusflow-audit` | `Repro1` | #1 → `Failed`, cause `java.sql.SQLException: Error opening connection` ← `org.sqlite.NativeLibraryNotFoundException: No native library found for os.name=Linux, os.arch=x86_64, paths=[…]`. The real reason appears only in JUL `SEVERE` lines on stderr (`Failed to open directory … NoSuchFileException`, `Unexpected IOException … .lck`, `no sqlitejdbc in java.library.path`). #2 → **throws** `java.lang.UnsatisfiedLinkError: 'void org.sqlite.core.NativeDB._open_utf8(byte[], int)'` (`is Exception=false`, `is Error=true`), frames `NativeDB.java:82 ← DB.java:216 ← SQLiteConnection.java:287` — identical to your Trace B. The stub recorded exactly one `reportCritical(source=Database.open, SQLException: Error opening connection)` — your Trace A. |
| F1/F2/F3 — **fault 2**: temp dir on a `noexec` tmpfs (private mount namespace) | `Repro1` | Extraction works; the JUL log shows `UnsatisfiedLinkError: …libsqlitejdbc.so: failed to map segment from shared object`, then the `java.library.path` miss; then the same two outcomes as fault 1. Closest Linux analogue of AV/AppLocker/WDAC blocking a DLL in `%TEMP%`. |
| F8 PIN fail-open | `Repro2` §A–C | DB not open: `GlobalPin.isSet()=false`, `verify("anything")=true` for Global/Session/Nuclear. PIN set, then DB handle lost: `isSet=false`, `verify(wrong)=true`, `clear(wrong)=true` while the hash is still on disk. A second writer holding the lock: `autoGenerate()` returned a 24-character PIN after 308 ms with no exception; afterwards `verify(newPin)=false`, `verify(oldPin)=true`. |
| F4 consent fail-open | `Repro2` §D | DB open, opted out: `getSetting="false"` → `optedIn=false`. DB closed: `getSetting=null` → `optedIn=true` (report **sent**). |
| F7 statement leak | `Repro2` §E; `Repro3` `leak`/`control` | 3 leaked statements per `refreshCaches()`. `Repro2`: `DB.stmts` 303 → 15,303, RSS 67 → 146 MB after 5,000 refreshes. `Repro3` (three saved runs): 15,003 / 15,003 / 15,005 statements, RSS growth +73 / +74 / +74 MB, heap flat; control 3 / 3 / 5 statements, RSS +6 / +9 / +5 MB. |
| F6 recurring tasks | `Repro2` §F (verbatim copy of `generateForToday`, `today` injected) | Day +1: 1 task shown / 2 rows with `recurring=1`; day +12: **2,048 shown / 4,096 rows**. Doubles every day. |
| F11 wizard-style `init` while another process writes | `Repro3` `race` (+ `writer.py`) | Four saved runs: writer every 1000 ms → 0/150 failures in all four; 20 ms → 2, 1, 1, 0 per 300; 1 ms → 75, 28, 33, 64 per 300 — all `IOException: Could not verify startup snapshot for focusflow.db-wal` (or `-shm`). Real-app write rates are far lower than 1 ms. |
| F9 orphan session | `Repro4` `orphan` | `hasUnfinishedFocusSession()` is `false` on a fresh DB and **`true`** for a row started 3 days earlier after a restart. |
| F10 `Error` inside a transaction | `Repro4` `txerror` | A `StackOverflowError` between two statements → `tasks.completed=1` is **committed**, second statement never ran. (Pattern copied from `completeTask`/`insertSession`; plain JDBC.) |
| F15 `VACUUM INTO` onto an existing file | `vacuum_into.py` (SQLite 3.45.1) | First run succeeds; second run to the same destination fails with `output file already exists`. SQLite's documented rule; the app's engine is 3.47.1. |
| F20 `DATE(col)` | `f20_dateindex.py` (SQLite 3.45.1, not the app's engine) | 200,004 rows over 27 years: plan `SCAN` vs `SEARCH … idx_sessions_start`; 17.4 ms vs 0.007 ms per day-query; 4,951 days compared, 0 differences. |
| F21 `completeTask` | `Repro5` | `completed_count` 0 → 1 → **2** (same task twice) → **3** (task id that does not exist). `getTotalFocusMinutesToday()` returns 0 on an empty table although it never calls `next()`. |
| F30 streak cap | `Repro6` | 120 consecutive days: `getCurrentStreak() = 90`, `getBestStreak() = 120`. |

Every row above was produced or re-run between 2026-10-11 and 2026-10-13 (sandbox clock). The F7 `Repro3` rows and the F11 rows were run again in the final pass so that each quoted figure has a saved raw output in the evidence zip. The Python-based rows (F15, F20) used SQLite 3.45.1 — see Appendix C, item 11.

---

## Appendix B — Upstream facts used (sqlite-jdbc **3.47.1.0**, tag read from GitHub; JDK source tag `jdk-21+35`)

| Claim | Location | Label |
|---|---|---|
| `NativeDB.load()` sets `isLoaded = true` in a `finally`, so a failed load is latched and later calls return the stale `false` without throwing | `NativeDB.java:42-43, 66-74` | V-src |
| `SQLiteConnection.open` wraps any `Exception` from `NativeDB.load()`/`new NativeDB(...)` as `SQLException("Error opening connection")`, **ignores** `load()`'s return value, then calls `db.open` | `SQLiteConnection.java:278-288` | V-src |
| `db.open` → `_open_utf8` (native) — an `UnsatisfiedLinkError` if the library never linked | `DB.java:216`, `NativeDB.java:82` | V-src |
| Loader order: `org.sqlite.lib.path`/`lib.name` → extract jar resource to `org.sqlite.tmpdir` (else `java.io.tmpdir`) and `System.load` → every `java.library.path` entry → `System.loadLibrary("sqlitejdbc")` → `NativeLibraryNotFoundException` | `SQLiteJDBCLoader.java:300-369`; extraction `:179-229`; `loadNativeLibrary :262-283`; `loadNativeLibraryJdk :285-293` | V-src |
| `initialize()` runs `cleanup()` (lists the temp dir) before the first extraction and returns `extracted`, which is `true` after **any** successful route | `SQLiteJDBCLoader.java:71-78` (`initialize`), `:88` (`cleanup`) | V-src |
| Intermediate failures go only to the loader's logger; it uses SLF4J if present, else `java.util.logging` `SEVERE` | `SQLiteJDBCLoader.java:226, 271, 290`; `util/LoggerFactory.java:10-72` | V-src |
| `LibraryLoaderUtil.getNativeLibResourcePath()`, `getNativeLibName()` are `public` | `util/LibraryLoaderUtil.java:13, 20` | V-src |
| `Statement.executeQuery` prepares and registers the statement in `DB.stmts` (strong set); `ResultSet.close()` only resets it unless `closeStmt`; `executeUpdate(String)` uses `_exec` and closes in `finally` | `DB.java:58, 265, 285`; `JDBC3Statement.java:81-98, 112-114, 122-152`; `CoreResultSet.java:126-154` | V-src |
| The first row is loaded by `execute()`, so `getInt` before `next()` works | `JDBC3ResultSet.java:55-65, 330-332` | V-src |
| The error text `opening db: '<path>'` can contain the full database path | `SQLiteConnection.java:271` | V-src |
| `SelectorProvider.provider()` honours the system property by `Class.forName`; a missing class becomes `ServiceConfigurationError`. `PollSelectorProvider` is only in the `unix` source tree; Windows has `WindowsSelectorProvider`/`WEPollSelectorProvider` | `java/nio/channels/spi/SelectorProvider.java:111-126`; `src/java.base/unix/classes/sun/nio/ch/PollSelectorProvider.java`; `src/java.base/windows/classes/sun/nio/ch/` (JDK 21, **not 19**) | V-src |

---

## Appendix C — What is unverified (read before trusting any "Windows" sentence)

1. **The trigger on the affected machine, and even its OS.** Unknown (§3.4).
2. **Exact jar contents.** I read the tag's resources, not the jar Gradle downloads.
3. **JDK 19 vs 21.** My runs and JDK source reading are JDK 21; the app builds with 19. I do not expect a difference in the cited behaviour, but I did not check.
4. **Everything Windows-specific:** file-sharing semantics when replacing an open DB or its `-wal`/`-shm`; `System.load` twice on the same path being a no-op; how a non-existent `%TEMP%` is reported; the uninstall-wizard dead end in practice; MSIX file virtualisation; the effect of the `SelectorProvider` flag; AV/AppLocker/WDAC blocking behaviour; the untested PowerShell helper in §3.4.
5. **UI-level behaviour.** Screens were read only at the cited lines; the Compose crash-handler path in step 5 of §3.2 is inferred from the trace shape.
6. **F6** was reproduced on a verbatim copy of `generateForToday`, not by running the real coroutine service. **F9**: "nothing restores a session after restart" was established by searching `FocusSessionService.kt` and `StartupBootstrap.kt` only.
7. **Extrapolations:** the per-day leak size (F7), the `DATE()` cost at realistic row counts (F20), the number of crash files per day (F24).
8. **Compose Multiplatform 1.6.1 packaging details** (`appResourcesRootDir`, `compose.application.resources.dir`) for F1b — not checked.
9. **Gradle 8.6** behaviour for an `include` without a directory (F26) — not checked.
10. **Excel and UTF-8 without BOM** (F23) — common knowledge, not tested here.
11. **Query-plan evidence** for F20 and the `VACUUM INTO` rule used Python's SQLite 3.45.1, not the app's 3.47.1 engine.
12. **The PIN threat model:** I assume the SQLite file is writable by the logged-in user (default profile ACLs) **[INF]**; I did not test tampering.
13. **`ReviewPromptService`:** I did not establish what it sends or when (F4 step 2 tells the agent to read it first).
14. I did **not** run `./gradlew test` or build the app (Maven Central and Compose artefacts were unreachable). Phase 0 does.

---

## Appendix D — Coverage and method

- **Tree:** 115 main Kotlin files, 43,550 lines by `wc -l` over `src/main/kotlin` (`ui/screens` 16,373; `i18n` 6,691; `ui/components` 5,554; `ui/launcher` + `ui/startup` + `ui/theme` + `ui` root 932; `services` 5,400 + `services/allowance` 887; `enforcement` 4,385; `data` 1,947 + `data/models` 236; package root 1,145). This plan gives `File.ext:lines` citations into 37 project files (34 Kotlin files plus `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`; 13,588 lines in total). `tools/cite_check.py` confirmed that every one of those citations points at a line that exists, and it flags ranges where a nearby identifier does not appear (66 flags in the final run; I read each one against the sentence it supports, fixed the real mismatches, and the rest are false positives of the heuristic, for example an identifier that appears in the sentence but not on the cited line). Short-form citations in the F12 table (rows that name a class without a file extension, e.g. `KillSwitchService.loadFromDb (:37-48)`) are **not** covered by the script; I checked each of them against the source by hand.
- **Read line-by-line or in the cited ranges:** `Database.kt`, `Main.kt`, `CrashReporter.kt`, `BackupService.kt`, `AutoBackupService.kt` and the cited ranges of the other files in the findings.
- **Scanned by pattern only:** the UI screens (database call sites, `catch` patterns, the CSV/clear-all handlers). A script classified 364 database call sites outside `Database.kt` as 218 lexically inside a background dispatcher and 146 not; that is a **syntactic** count and says nothing about which run on the UI thread (F16).
- **Not examined:** `i18n/*`, most UI layout code, `src/main/resources`, the Gradle wrapper, anything outside the zip (installer scripts, MSIX manifest, CI).
- **Method:** static reading → hypothesis → reproduction against the unmodified classes where possible → upstream-source check for the third-party parts. Findings without a **[V-repro]** label were not executed.
- **Your agent's rule R1 still applies:** the coverage above is not a substitute for reading each file in full before editing it.

*End of plan.*
