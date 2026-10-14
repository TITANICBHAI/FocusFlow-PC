# FocusFlow audit: evidence kit

These are the programs and raw outputs behind the **[V-repro]** labels in `PLAN.md`.
They compile a *subset* of your **unmodified** `src/main/kotlin` files. Nothing in your project was changed.

## Read this before running anything

1. **Never build the harness against the real `CrashReporter.kt`.** On the default DB path, a failed
   `Database.init(...)` calls `CrashReporter.reportCritical`, which would POST to your production Discord webhook
   (that is finding F4 in the plan). `stubs/com/focusflow/services/CrashReporter.kt` records the call in a list instead and sends nothing.
2. **Always pass `-Duser.home=<an empty scratch directory>`.** The harness creates `~/.focusflow/focusflow.db` there. Never point it at a real profile.
3. Do not paste any webhook string from `CrashReporter.kt` / `ReviewPromptService.kt` anywhere. PLAN.md rule R4 says to rotate them.

## What was measured, and where

| Setting | Value |
|---|---|
| OS | Linux 6.18 sandbox. **Not Windows.** |
| JDK | OpenJDK 21.0.12 (your Gradle toolchain is 19) |
| sqlite-jdbc | **Built from the GitHub tag `3.47.1.0`** (Maven Central was unreachable), not the Maven jar |
| Kotlin | 1.9.22 compiler |
| Python scripts | Python 3.13 with SQLite **3.45.1** (not the app's 3.47.1 engine) |

## Build

```bash
SRC=<your project>/src/main/kotlin/com/focusflow
SQLJ=<path to sqlite-jdbc-3.47.1.0 jar>

# 1. the app subset (unmodified files) + the two stubs
kotlinc -nowarn -cp $SQLJ -d out/app \
  $SRC/data/*.kt $SRC/data/models/*.kt \
  $SRC/enforcement/EnforcementLog.kt \
  $SRC/services/GlobalPin.kt $SRC/services/NuclearPin.kt $SRC/services/PinPolicy.kt $SRC/services/SessionPin.kt \
  stubs/com/focusflow/services/CrashReporter.kt \
  stubs/kotlinx/coroutines/flow/Flow.kt

# 2. one harness (repeat for Repro1..Repro6)
kotlinc -nowarn -cp out/app:$SQLJ -d out/h3 harness/Repro3.kt
```

`stubs/kotlinx/coroutines/flow/Flow.kt` only exists so the build works without kotlinx-coroutines.
If you have the real library on the classpath, leave that stub out.

## Run

```bash
export JAVA_TOOL_OPTIONS=          # keep the JVM quiet
SCRATCH=$(mktemp -d)
java -Duser.home=$SCRATCH -cp out/h3:out/app:<kotlin-stdlib.jar>:$SQLJ Repro3Kt prep
java -Duser.home=$SCRATCH -cp out/h3:out/app:<kotlin-stdlib.jar>:$SQLJ Repro3Kt leak
```

Arguments: `Repro3Kt prep|race <n>|control|leak|sum`, `Repro4Kt orphan|txerror`, `Repro6Kt <days>`.
`Repro1Kt` takes `-Dorg.sqlite.tmpdir=<dir>` to inject the native-library fault.
`run_race.sh <label>` runs the wizard-versus-writer experiment (edit the paths at its top first).
The Python scripts (`writer.py`, `f20_dateindex.py`, `vacuum_into.py`) need only Python 3 and its built-in `sqlite3`.

## Which raw file shows what

| File in `raw/` | Produced by | Shows | Plan |
|---|---|---|---|
| `repro1_final.txt` | `Repro1`: a control run, then a run with `-Dorg.sqlite.tmpdir=/nonexistent/focusflow-audit` | Control: `Ready`, `Ready`. Fault: first `init` returns `Failed` (`SQLException: Error opening connection`), the second call **throws** `UnsatisfiedLinkError … _open_utf8`, an `Error` that `catch (e: Exception)` does not catch | §3, F1–F3 |
| `repro1_fault2_final.txt` | `Repro1` with the temp dir on a `noexec` tmpfs | Same two-stage failure; the log shows "failed to map segment from shared object" | §3 |
| `repro2_final.txt` | `Repro2`, sections A–F | A–C PIN fail-open and swallowed writes (F8); D telemetry consent fail-open (F4); E statement leak (F7); F recurring-task doubling (F6, verbatim copy of the function body) | F4, F6–F8 |
| `repro3_final.txt`, `repro3_leak_run1.txt`, `repro3_leak_run2.txt` | `Repro3` `control` / `leak` | 15,003 / 15,003 / 15,005 leaked statements after 5,000 refreshes; control 3 / 3 / 5 | F7 |
| `repro3_race_final.txt`, `repro3_race_run1.txt` … `run3.txt` | `Repro3 race` with `writer.py` | Wizard-style `init` fails with `Could not verify startup snapshot` while another process writes; the rate varies a lot between runs | F11 |
| `repro4_final.txt` | `Repro4` | `orphan`: unfinished-session row still counts 3 days later; `txerror`: an `Error` inside a transaction ends in a committed half-update | F9, F10 |
| `repro5_f21.txt` | `Repro5` | `completeTask` counts the same task twice and counts a task id that does not exist | F21 |
| `repro6_f30.txt` | `Repro6` | 120-day streak is reported as 90 | F30 |
| `f20_dateindex.txt` | `f20_dateindex.py` | `DATE(col)` scans the table; a range on the column uses the index | F20 |
| `vacuum_into.txt` | `vacuum_into.py` | `VACUUM INTO` fails when the destination file already exists and is not empty | F15 |

## Limits (also in PLAN.md, Appendix C)

- Not run on Windows or on JDK 19. Windows-specific behaviour is a checklist in PLAN.md §9, not a result.
- `Repro2` section F tests a verbatim copy of `generateForToday`, not the real coroutine service.
- The F20 timings come from Python's SQLite, not the app's engine, and scale with row count.
- Leak and race figures vary from run to run. The saved runs show the range.
- `tools/cite_check.py` is the script that checked every `File.ext:line` citation in PLAN.md. It has sandbox-specific paths at the top; edit them before use.
