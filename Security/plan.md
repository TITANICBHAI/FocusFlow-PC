# FocusFlow PC — Anti-Bypass Plan (weak-agent edition)

> **Read this whole file once before touching anything.** Every step is mechanical on purpose. If anything does not match what this file says, **stop and write it in the tracker — do not improvise, and do not "fix" it a different way.**

| | |
|---|---|
| Owner | Himanshu (TBTechs) — FocusFlow, a free, privacy-first Windows focus / app blocker |
| Repo | `FocusFlow-PC`: Kotlin 1.9.22, Compose Desktop 1.6.1, SQLite (sqlite-jdbc 3.47.1.0), JDK 19, package `com.focusflow` |
| Snapshot | app version 2.0.6 (its changelog entry is still marked `PLANNED`), analysed 2026-11-01 |
| Goal | Make it harder for the PC's owner to lift FocusFlow's restrictions in a weak moment — **without ever locking anyone out of their own PC**. FocusFlow is a *friction layer*, not a security product: never write text that claims otherwise. |

Tags used in this file: **[code]** = checked by reading the source · **[verify]** = Windows behaviour nobody has run yet · **[idea]** = a design thought, not checked.

---

## 0. What was checked before you got this file

**Checked (Linux, 2026-11-01)**
- Every `old_string` in the cards below occurs **exactly once** in the original sources, and the edits apply cleanly (each card alone, and all cards together).
- The new Kotlin files compile with the Kotlin compiler (2.0.21, language level 1.9) and **10 new tests pass against the real sqlite-jdbc 3.47.1.0**: `BackupActionPolicyTest` (3) and `RestoreProtectionMergeTest` (7).
- The two source-scan tests were run against an unpatched and a patched copy of the sources: **red before** the edits, **green after**.
- The merge SQL was first tried on plain SQLite 3.45.1 (Python) before being wrapped in Kotlin; the Kotlin tests include a "garbage / missing source file" case (the restored database stays untouched).

**NOT checked — you will be the first to run these**
- That the edited files (`ContactScreen.kt`, `BackupRecoveryService.kt`, `UninstallProtectionService.kt`, `PinGateDialog.kt`, `ChangelogScreen.kt`) compile inside the full Gradle build (Compose UI code cannot be built without Gradle).
- `RestoreKeepsProtectionsTest` and `UninstallProtectionQuitGateTest` (they need the whole app on the class path).
- The repo's existing 69 test files — their baseline is unknown (Card A0 measures it).
- **Anything on Windows.** The owner runs the sheet in §9.

---

## 1. Rules (read all, every time)

1. **Work alone.** Do not create or call sub-agents.
2. **Only the cards in §4–§6, in this order:** A0 → A1 → (B1, B2, B3 — only if their gate in §2 is open) → Z. Anything else you notice goes in the tracker under "Out-of-scope observations". **Do not fix it.**
3. **Copy code exactly.** Do not rename, reformat, "improve", merge cards, or add extra changes.
4. **One card at a time.** Mark it *in progress* in the tracker before you start; tick it only after its checks passed; record the exact commands and results.
5. **The real user-data folder is off limits.** Never open, change, move or delete `%USERPROFILE%\.focusflow\` (database, backups, logs). The new tests use temp folders / the repo's `TempHome` helper.
6. **Secrets.** Two Discord webhook strings live in `CrashReporter.kt` and `ReviewPromptService.kt`. Do not open those files and never print the strings.
7. **Git.** Do not `git push`. Do not start, poll or watch GitHub Actions.
8. **Windows.** You cannot run Windows. Never write "verified on Windows". Windows checks belong to the owner (§9).
9. **Gradle.** Run from the folder that contains `build.gradle.kts`. Use the system `gradle` with `--no-daemon`, **not** `./gradlew` (its wrapper jar is missing in this repo). A run can take several minutes — do not cancel it. If `grep` is not installed on your machine, use your Grep tool with the same text and count the matching lines instead.
10. **Never weaken anything.** Do not touch PIN hashing/verification, enforcement code, or any file a card does not list.
11. **A gate is open only if its `ANSWER:` line in §2 contains exactly the required letter.** Never guess an answer.
12. **Undo.** If the repo has git (`git rev-parse --is-inside-work-tree` prints `true`): undo a card with `git checkout -- <files the card edited>` and delete the new files the card created. No git: before the *first* edit of a file run `cp <file> <file>.orig`; undo with `cp <file>.orig <file>`; at the end `find src -name "*.orig"` must print nothing (delete them).

### STOP rules — stop the *current card*, undo it, write it in the tracker, move on or finish
- An `old_string` does not occur exactly once (the file changed since this plan was made).
- A compile error that points at code or a test you pasted from this file. Copy the exact error into the tracker. **Do not edit the pasted code or the test to make it pass.**
- Any test fails (new or existing) after your edit. Record the test names and the first assertion message. **Never edit an existing test.**
- A "RED" check that is supposed to fail passes instead (it means the guard test is not testing what it should).
- You are about to edit a file the card does not list.
- **Line endings.** The `old_string` blocks use LF line endings. If a multi-line `old_string` is not found but its first line *is* found, the file probably uses Windows line endings (CRLF). Do not guess: record "CRLF suspected in <file>" in the tracker and STOP that card. (The owner can fix it with `git config core.autocrlf false` and a fresh checkout, or tell you how to go on.)

---

## 2. Owner answers (the owner fills these in; the agent only reads them)

The owner can edit the lines below, or tell the agent the letters in chat — the agent then copies them here. **Recommended answers are mine; they are not answers until the owner writes them.**

**O1 — What should "Forgot PIN?" do?**
- **A** = remove the link everywhere (the app then matches its own notice in `ContactScreen.kt`: a forgotten PIN "cannot be recovered"; last resorts stay: uninstall, Store removal).
- **C** = leave it as it is.
- (A 72-hour cooling-off variant exists but is **not** for this agent — see §7, N10.)
- Recommended: **A**. Today, after 2 wrong PINs, typing `RESET` clears the Global PIN — a PIN with a built-in reset is no protection.

ANSWER O1: `(not answered)` → Card B3 runs only if this says `A`.

**O3 — What should restoring a backup do to protections?**
- **B** = restore the user's data from the backup, but **never weaken protections** (PIN, Always-On, Nuclear, block rules, schedules, limits, network rules): the strictest of "now" and "backup" wins. (This is the "merge instead of replace" idea.)
- **C** = leave restore as it is (Card A1 still adds a PIN in front of it).
- Recommended: **B**.

ANSWER O3: `(not answered)` → Card B1 runs only if this says `B`.

**O4 — Quit on the Microsoft Store (MSIX) build?**
- **B** = tray → Quit asks for the same PINs as the direct build when a protection is active. (Removing the package from Windows Settings stays Windows-owned and is **not** touched.)
- **A** = keep the Store build exempt.
- Recommended: **B**. The Store build is the main channel and today has no quit gate. The owner should confirm it is fine with Store policy.

ANSWER O4: `(not answered)` → Card B2 runs only if this says `B`.

(Other owner decisions — O2 DB-loss behaviour, O5 Emergency Break, O6/O7 — belong to §7 and are not for this agent.)

---

## 3. What this plan fixes — and what it does not

| Hole | What a user can do today | Card | After the card |
|---|---|---|---|
| H2 · restore/backup management is ungated | In Settings → Backup & Recovery: restore an older, weaker backup; delete backups or recovery archives; change or remove the secondary folder — no PIN | **A1** | needs the Global PIN when one is set (fail-closed: an unreadable PIN state counts as "set") |
| H2 · restore swaps the whole database | The PIN, Always-On, rules etc. of the old backup replace the current ones | **B1** (O3=B) | a restore can only *add* protections, never remove them |
| H4 · Store build quits freely | On the Store build, tray → Quit needs no PIN | **B2** (O4=B) | same quit PINs as the direct build |
| H1 · "Forgot PIN?" | After 2 wrong PINs, typing `RESET` clears the Global PIN | **B3** (O1=A) | the link is gone at every call site |

**Not fixed by this plan.** After these cards a user can no longer get to a weaker database *through FocusFlow's own screens*. Someone who works outside the app can still do it: close the app and replace or delete `focusflow.db` in Explorer (H3), kill the app (H5), run one-shot command-line tools while Nuclear Mode is on (H6), change the time zone (H8), edit the database while the app runs (H9). Those need a stronger model and owner decisions — §7 explains each, so nobody believes they are closed.

---

## 4. Cards that need no owner answer

### Card A0 — Baseline and tracker (no code changes)
1. `gradle --version` and `java -version`. If `gradle` is not found: **STOP** — write "no gradle available" in the tracker and tell the owner.
2. If the repo has git: `git status --short` — paste the output into the tracker as the "starting state".
3. Create `Security/tracker.md` from the template in §10.
4. Run `gradle :test --no-daemon`. Record the last ~15 output lines and the totals from this one-liner:
```bash
grep -h "<testsuite " build/test-results/test/*.xml | sed -E 's/.*tests="([0-9]+)".*failures="([0-9]+)".*errors="([0-9]+)".*/\1 \2 \3/' | awk '{t+=$1;f+=$2;e+=$3} END {print "tests="t" failures="f" errors="e}'
```
   If the build does not compile at baseline: **STOP** (the owner must fix the build first). Failing tests at baseline are *not yours* — list their names; later you only have to show **no new failures**.
5. **Done when:** the tracker contains the line `Baseline: tests=N failures=F errors=E` plus the names of any failing tests.

### Card A1 — The Global PIN guards restore and backup management (fixes H2, first half)
**Files:** NEW `BackupActionPolicy.kt`, NEW `BackupActionPolicyTest.kt`, NEW `BackupUiPinGateSourceTest.kt`; EDIT `ContactScreen.kt` and `ChangelogScreen.kt`.

1. Tracker: A1 in progress. (Git: `git status --short`.)
2. Create the three new files:

**Create** `src/main/kotlin/com/focusflow/services/BackupActionPolicy.kt` with exactly this content:

```kotlin
package com.focusflow.services

/**
 * Decides whether a Backup & Recovery action needs the Global PIN first.
 *
 * Restoring an older backup, deleting backups or recovery archives, and changing the secondary
 * backup folder can all be used to get back to a weaker set of protections. They follow the same
 * fail-closed rule as the other PIN gates: only a confirmed "no PIN" lets the action run without
 * one, and an unreadable PIN state ([PinState.UNKNOWN]) counts as "set".
 */
object BackupActionPolicy {
    fun requiresPin(pinState: PinState): Boolean = pinState != PinState.NOT_SET
}
```

**Create** `src/test/kotlin/com/focusflow/services/BackupActionPolicyTest.kt` with exactly this content:

```kotlin
package com.focusflow.services

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupActionPolicyTest {
    @Test
    fun noPinSetAllowsTheActionWithoutAPrompt() {
        assertFalse(BackupActionPolicy.requiresPin(PinState.NOT_SET))
    }

    @Test
    fun aSetPinIsRequired() {
        assertTrue(BackupActionPolicy.requiresPin(PinState.SET))
    }

    @Test
    fun anUnreadablePinStateIsTreatedAsSetSoTheActionFailsClosed() {
        assertTrue(BackupActionPolicy.requiresPin(PinState.UNKNOWN))
    }
}
```

**Create** `src/test/kotlin/com/focusflow/services/BackupUiPinGateSourceTest.kt` with exactly this content:

```kotlin
package com.focusflow.services

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cheap structural guard. It reads source files; it does not run the UI.
 * The Backup & Recovery screen must keep sending every restore / delete / folder action through
 * withBackupPin, and no other file may start calling those functions without a review.
 */
class BackupUiPinGateSourceTest {
    private val sourceRoot: Path = Path.of("src/main/kotlin")
    private val contactScreen: Path = sourceRoot.resolve("com/focusflow/ui/screens/ContactScreen.kt")

    private fun kotlinFilesContaining(vararg needles: String): Set<String> =
        Files.walk(sourceRoot).use { stream ->
            stream.iterator().asSequence()
                .filter { it.toString().endsWith(".kt") }
                .filter { path ->
                    val text = Files.readString(path)
                    needles.any { text.contains(it) }
                }
                .map { it.fileName.toString() }
                .toSet()
        }

    @Test
    fun everyBackupActionInTheScreenGoesThroughTheBackupPinHelper() {
        val text = Files.readString(contactScreen)
        assertEquals(
            5,
            Regex("withBackupPin \\{").findAll(text).count(),
            "Expected exactly 5 'withBackupPin {' call sites: choose folder, remove folder, restore, delete backup, delete archive"
        )
        assertTrue(text.contains("BackupActionPolicy.requiresPin("), "The helper must ask BackupActionPolicy")
        assertTrue(text.contains("allowReset = false"), "The backup PIN dialog must not offer a reset link")
    }

    @Test
    fun noNewCallersOfTheRestoreAndBackupManagementFunctions() {
        val callers = kotlinFilesContaining(
            "scheduleRestore(",
            "configureSecondaryBackupDirectory(",
            "deleteSecondaryBackup(",
            "deleteSafetyBackup(",
            "deleteRestoreArchive("
        )
        assertEquals(
            setOf("BackupRecoveryService.kt", "ContactScreen.kt"),
            callers,
            "A new file calls a restore / backup-management function. Gate it with BackupActionPolicy and ask the owner."
        )
    }
}
```

3. **RED check** (proves the guard test is real). Run:
```bash
gradle :test --no-daemon --tests "com.focusflow.services.BackupActionPolicyTest" --tests "com.focusflow.services.BackupUiPinGateSourceTest"
```
   Expected: the 3 `BackupActionPolicyTest` tests pass; in `BackupUiPinGateSourceTest`, `everyBackupActionInTheScreenGoesThroughTheBackupPinHelper` **fails** and `noNewCallersOfTheRestoreAndBackupManagementFunctions` passes. If the first one passes now: **STOP**.
4. Apply these edits to `ContactScreen.kt` **in this order**, one at a time, running each "Check" afterwards:

**Edit A1-1** — file `src/main/kotlin/com/focusflow/ui/screens/ContactScreen.kt`  
Adds two imports.  
Use the Edit tool. `old_string` (must occur **exactly once** — if it does not, STOP):
```kotlin
import com.focusflow.services.CrashReporter
import com.focusflow.services.ReviewPromptService
import com.focusflow.services.AppLifecycle
import com.focusflow.services.AutoBackupService
```
`new_string`:
```kotlin
import com.focusflow.services.CrashReporter
import com.focusflow.services.ReviewPromptService
import com.focusflow.services.AppLifecycle
import com.focusflow.services.AutoBackupService
import com.focusflow.services.BackupActionPolicy
import com.focusflow.services.GlobalPin
```
Check (right after this edit): `grep -c -F 'import com.focusflow.services.BackupActionPolicy' src/main/kotlin/com/focusflow/ui/screens/ContactScreen.kt` prints **1**.

**Edit A1-2** — file `src/main/kotlin/com/focusflow/ui/screens/ContactScreen.kt`  
Adds the PIN dialog import.  
Use the Edit tool. `old_string` (must occur **exactly once** — if it does not, STOP):
```kotlin
import com.focusflow.ui.components.openUrl
```
`new_string`:
```kotlin
import com.focusflow.ui.components.PinGateDialog
import com.focusflow.ui.components.openUrl
```
Check (right after this edit): `grep -c -F 'import com.focusflow.ui.components.PinGateDialog' src/main/kotlin/com/focusflow/ui/screens/ContactScreen.kt` prints **1**.

**Edit A1-3** — file `src/main/kotlin/com/focusflow/ui/screens/ContactScreen.kt`  
Adds the state that remembers which action is waiting for the PIN.  
Use the Edit tool. `old_string` (must occur **exactly once** — if it does not, STOP):
```kotlin
    var deleteArchive by remember { mutableStateOf<RestoreArchiveEntry?>(null) }
```
`new_string`:
```kotlin
    var deleteArchive by remember { mutableStateOf<RestoreArchiveEntry?>(null) }
    var pinGateAction by remember { mutableStateOf<(() -> Unit)?>(null) }
```
Check (right after this edit): `grep -c -F 'var pinGateAction by remember { mutableStateOf<(() -> Unit)?>(null) }' src/main/kotlin/com/focusflow/ui/screens/ContactScreen.kt` prints **1**.

**Edit A1-4** — file `src/main/kotlin/com/focusflow/ui/screens/ContactScreen.kt`  
Adds the PIN helper and moves the 'remove folder' body into its own function (same code as before).  
Use the Edit tool. `old_string` (must occur **exactly once** — if it does not, STOP):
```kotlin
    LaunchedEffect(Unit) { refresh() }

    fun chooseSecondaryFolder() {
```
`new_string`:
```kotlin
    LaunchedEffect(Unit) { refresh() }

    // Restoring, deleting backups and changing the backup folder can all be used to get back to a
    // weaker set of protections, so they need the Global PIN when one is set (fail-closed).
    fun withBackupPin(action: () -> Unit) {
        scope.launch {
            val pinState = withContext(Dispatchers.IO) { GlobalPin.state() }
            if (BackupActionPolicy.requiresPin(pinState)) {
                pinGateAction = action
            } else {
                action()
            }
        }
    }

    fun removeSecondaryFolder() {
        scope.launch {
            working = true
            val result = withContext(Dispatchers.IO) {
                BackupRecoveryService.configureSecondaryBackupDirectory(null)
            }
            when (result) {
                SecondaryLocationUpdate.Removed -> {
                    secondaryDirectory = null
                    statusMessage = "Secondary folder removed. Existing backups were left untouched."
                }
                is SecondaryLocationUpdate.Failed -> statusMessage = result.reason
                is SecondaryLocationUpdate.Saved -> Unit
            }
            refresh()
            working = false
        }
    }

    fun chooseSecondaryFolder() {
```
Check (right after this edit): `grep -c -F 'fun withBackupPin(action: () -> Unit) {' src/main/kotlin/com/focusflow/ui/screens/ContactScreen.kt` prints **1**.

**Edit A1-5** — file `src/main/kotlin/com/focusflow/ui/screens/ContactScreen.kt`  
Gates 'Choose/Change folder'.  
Use the Edit tool. `old_string` (must occur **exactly once** — if it does not, STOP):
```kotlin
                OutlinedButton(onClick = ::chooseSecondaryFolder, enabled = !working) {
```
`new_string`:
```kotlin
                OutlinedButton(onClick = { withBackupPin { chooseSecondaryFolder() } }, enabled = !working) {
```
Check (right after this edit): `grep -c -F 'OutlinedButton(onClick = { withBackupPin { chooseSecondaryFolder() } }, enabled = !working) {' src/main/kotlin/com/focusflow/ui/screens/ContactScreen.kt` prints **1**.

**Edit A1-6** — file `src/main/kotlin/com/focusflow/ui/screens/ContactScreen.kt`  
Gates 'Remove folder' (its body now lives in removeSecondaryFolder, added in A1-4).  
Use the Edit tool. `old_string` (must occur **exactly once** — if it does not, STOP):
```kotlin
                    TextButton(
                        onClick = {
                            scope.launch {
                                working = true
                                val result = withContext(Dispatchers.IO) {
                                    BackupRecoveryService.configureSecondaryBackupDirectory(null)
                                }
                                when (result) {
                                    SecondaryLocationUpdate.Removed -> {
                                        secondaryDirectory = null
                                        statusMessage = "Secondary folder removed. Existing backups were left untouched."
                                    }
                                    is SecondaryLocationUpdate.Failed -> statusMessage = result.reason
                                    is SecondaryLocationUpdate.Saved -> Unit
                                }
                                refresh()
                                working = false
                            }
                        },
                        enabled = !working
                    ) { Text("Remove folder") }
```
`new_string`:
```kotlin
                    TextButton(
                        onClick = { withBackupPin { removeSecondaryFolder() } },
                        enabled = !working
                    ) { Text("Remove folder") }
```
Check (right after this edit): `grep -c -F 'onClick = { withBackupPin { removeSecondaryFolder() } },' src/main/kotlin/com/focusflow/ui/screens/ContactScreen.kt` prints **1**.

**Edit A1-7** — file `src/main/kotlin/com/focusflow/ui/screens/ContactScreen.kt`  
Gates 'Stage restore'.  
Use the Edit tool. `old_string` (must occur **exactly once** — if it does not, STOP):
```kotlin
                        if (selected != null) stageRestore(selected)
```
`new_string`:
```kotlin
                        if (selected != null) withBackupPin { stageRestore(selected) }
```
Check (right after this edit): `grep -c -F 'if (selected != null) withBackupPin { stageRestore(selected) }' src/main/kotlin/com/focusflow/ui/screens/ContactScreen.kt` prints **1**.

**Edit A1-8** — file `src/main/kotlin/com/focusflow/ui/screens/ContactScreen.kt`  
Gates 'Delete copy'.  
Use the Edit tool. `old_string` (must occur **exactly once** — if it does not, STOP):
```kotlin
                    onClick = {
                        if (entry.location == BackupLocation.SECONDARY) {
                            deleteSecondaryBackup(entry)
                        } else {
                            deleteSafetyBackup(entry)
                        }
                    },
```
`new_string`:
```kotlin
                    onClick = {
                        withBackupPin {
                            if (entry.location == BackupLocation.SECONDARY) {
                                deleteSecondaryBackup(entry)
                            } else {
                                deleteSafetyBackup(entry)
                            }
                        }
                    },
```
Check (right after this edit): `grep -c -F 'withBackupPin {' src/main/kotlin/com/focusflow/ui/screens/ContactScreen.kt` prints **4**.

**Edit A1-9** — file `src/main/kotlin/com/focusflow/ui/screens/ContactScreen.kt`  
Gates 'Delete archive'.  
Use the Edit tool. `old_string` (must occur **exactly once** — if it does not, STOP):
```kotlin
                    onClick = { deleteRestoreArchive(archive) },
```
`new_string`:
```kotlin
                    onClick = { withBackupPin { deleteRestoreArchive(archive) } },
```
Check (right after this edit): `grep -c -F 'onClick = { withBackupPin { deleteRestoreArchive(archive) } },' src/main/kotlin/com/focusflow/ui/screens/ContactScreen.kt` prints **1**.

**Edit A1-10** — file `src/main/kotlin/com/focusflow/ui/screens/ContactScreen.kt`  
Shows the PIN dialog (no 'Forgot PIN?' link) when an action is waiting.  
Use the Edit tool. `old_string` (must occur **exactly once** — if it does not, STOP):
```kotlin
                TextButton(onClick = { deleteArchive = null }, enabled = !working) { Text("Keep archive") }
            }
        )
    }
}
```
`new_string`:
```kotlin
                TextButton(onClick = { deleteArchive = null }, enabled = !working) { Text("Keep archive") }
            }
        )
    }

    pinGateAction?.let { pending ->
        PinGateDialog(
            title = "Enter your PIN",
            subtitle = "Restoring or deleting backups, and changing the backup folder, need your Global PIN.",
            allowReset = false,
            requireConfiguredPin = true,
            onSuccess = {
                pinGateAction = null
                pending()
            },
            onDismiss = { pinGateAction = null }
        )
    }
}
```
Check (right after this edit): `grep -c -F 'pinGateAction?.let { pending ->' src/main/kotlin/com/focusflow/ui/screens/ContactScreen.kt` prints **1**.


5. `grep -c "withBackupPin {" src/main/kotlin/com/focusflow/ui/screens/ContactScreen.kt` must print **5**.
6. **GREEN check:** run the same `gradle ... --tests` command as step 3. Expected: all 5 tests pass.
7. Compile the whole UI: `gradle :compileKotlin --no-daemon` → `BUILD SUCCESSFUL`. (If Gradle says the task does not exist, skip this step — `:test` compiles too.) A failure here is a STOP: undo the card.
8. Changelog line (user-facing text — do not change the wording):

**Edit CL-A1** — file `src/main/kotlin/com/focusflow/ui/screens/ChangelogScreen.kt`  
Adds one line to the 2.0.6 changelog list (the new line goes just above the last line).  
Use the Edit tool. `old_string` (must occur **exactly once** — if it does not, STOP):
```kotlin
            "UPD" to "Windows-specific behavior still needs hands-on verification"
```
`new_string`:
```kotlin
            "SEC" to "Restoring or deleting backups and changing the backup folder now asks for your PIN when you have one",
            "UPD" to "Windows-specific behavior still needs hands-on verification"
```
Check (right after this edit): `grep -c -F '"SEC" to "Restoring or deleting backups and changing the backup folder now asks for your PIN when you have one",' src/main/kotlin/com/focusflow/ui/screens/ChangelogScreen.kt` prints **1**.


9. Run the full `gradle :test --no-daemon` and compare with the baseline: **no new failing tests**.
10. Tracker: tick A1 only now. Evidence = files changed, the commands above, their results.

**Done when:** steps 5–9 all passed. **Owner's Windows check:** W-1.
**Do not** gate "Back up now" (it only adds a backup) and do not touch `BackupRecoveryService.kt` in this card.

---

## 5. Gated cards — run one only if its `ANSWER:` line in §2 has the required letter

### Card B1 — Restore keeps current protections (needs `ANSWER O3: B`)
**Fixes:** H2, second half — your "merge instead of replace" idea. **Files:** NEW `RestoreProtectionMerge.kt`, NEW `RestoreProtectionMergeTest.kt`, NEW `RestoreKeepsProtectionsTest.kt`; EDIT `BackupRecoveryService.kt`, `ChangelogScreen.kt`.

**How it works (so you understand it, you do not need to change it).** At the next start, `BackupRecoveryService.initializeDatabaseForStartup` already (1) makes a verified *safety backup* of the current database, (2) archives the current files, (3) installs the staged backup, (4) opens it. The new code runs after (4): it closes the database, copies the safety backup to a temporary file (the safety backup itself is never touched), attaches that copy to the restored database and, in **one transaction**, copies back every protection that is stricter than the restored one — PIN hashes, switches that were on, standalone blocks that end later, block rules, schedules, network rules, the smaller allowance limit, the union of keyword and custom-VPN lists. If anything fails, the existing `rollbackAndReopen` puts the previous database back. Known gaps (do not try to fix them): the Emergency-Break budget, per-task focus settings, and edits to the *times* of an existing schedule are not merged. A rule the user deleted *on purpose* before the restore can come back if the backup has it — they can delete it again with their PIN.

1. Check the gate. Tracker: B1 in progress.
2. Create the merge code and its unit test:

**Create** `src/main/kotlin/com/focusflow/services/RestoreProtectionMerge.kt` with exactly this content:

```kotlin
package com.focusflow.services

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.SQLException
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource

/**
 * Makes sure a restore can never leave protections weaker than they were just before it.
 *
 * The restored database keeps everything from the backup. Then every protection the user had
 * BEFORE the restore is copied back in whenever it is stricter, so a restore can only ADD
 * protections, never remove them. Loosening something on purpose still happens in the
 * PIN-gated screens, never through a restore.
 *
 * Known gaps (listed in Security/plan.md): the emergency-break budget, per-task focus settings
 * and edits to the times of an existing schedule are not merged.
 * PIN hashes are copied as opaque strings and are never logged.
 */
internal object RestoreProtectionMerge {

    sealed interface MergeOutcome {
        object Merged : MergeOutcome
        data class Failed(val reason: String) : MergeOutcome
    }

    /** In these statements `pre` is the database as it was just before the restore, `main` is the restored one. */
    private val mergeStatements = listOf(
        // Switches that were ON stay ON.
        """INSERT OR REPLACE INTO main.settings(key, value)
           SELECT key, 'true' FROM pre.settings
           WHERE key IN ('always_on_enforcement','nuclear_mode','keyword_blocker_enabled','vpn_block_enabled')
             AND value = 'true'""",
        // A PIN that was set stays set (the current hash wins over the backup's).
        """INSERT OR REPLACE INTO main.settings(key, value)
           SELECT key, value FROM pre.settings
           WHERE key IN ('global_pin_hash','nuclear_pin_hash') AND value <> ''""",
        // A standalone block that ends later than the restored one replaces it.
        """INSERT OR REPLACE INTO main.settings(key, value)
           SELECT key, value FROM pre.settings
           WHERE key IN ('standalone_block_processes','standalone_block_until','standalone_block_start')
             AND CAST((SELECT value FROM pre.settings WHERE key = 'standalone_block_until') AS INTEGER) >
                 CAST(COALESCE((SELECT value FROM main.settings WHERE key = 'standalone_block_until'), '0') AS INTEGER)""",
        // Block rules: add the ones the backup lacks, switch the ones that were on back on.
        """INSERT OR IGNORE INTO main.block_rules(id, process_name, display_name, enabled, block_network)
           SELECT id, process_name, display_name, enabled, block_network FROM pre.block_rules""",
        """UPDATE main.block_rules SET enabled = 1
           WHERE enabled = 0 AND process_name IN (SELECT process_name FROM pre.block_rules WHERE enabled = 1)""",
        """UPDATE main.block_rules SET block_network = 1
           WHERE block_network = 0 AND process_name IN (SELECT process_name FROM pre.block_rules WHERE block_network = 1)""",
        // Schedules: add the missing ones, switch the ones that were on back on.
        """INSERT OR IGNORE INTO main.block_schedules(id, name, days_of_week, start_hour, start_minute, end_hour, end_minute, enabled, process_names)
           SELECT id, name, days_of_week, start_hour, start_minute, end_hour, end_minute, enabled, process_names FROM pre.block_schedules""",
        """UPDATE main.block_schedules SET enabled = 1
           WHERE enabled = 0 AND id IN (SELECT id FROM pre.block_schedules WHERE enabled = 1)""",
        // Daily allowances: add the missing ones, and the smaller limit always wins.
        """INSERT OR IGNORE INTO main.daily_allowances(process_name, display_name, allowance_minutes)
           SELECT process_name, display_name, allowance_minutes FROM pre.daily_allowances""",
        """UPDATE main.daily_allowances AS a
           SET allowance_minutes = MIN(a.allowance_minutes,
               (SELECT p.allowance_minutes FROM pre.daily_allowances AS p WHERE p.process_name = a.process_name))
           WHERE a.process_name IN (SELECT process_name FROM pre.daily_allowances)""",
        // Network cut-off rules: add the missing ones, switch the ones that were on back on.
        """INSERT OR IGNORE INTO main.network_cutoff_rules(id, pattern, mode, target_process, target_display_name, enabled)
           SELECT id, pattern, mode, target_process, target_display_name, enabled FROM pre.network_cutoff_rules""",
        """UPDATE main.network_cutoff_rules SET enabled = 1
           WHERE enabled = 0 AND id IN (SELECT id FROM pre.network_cutoff_rules WHERE enabled = 1)"""
    )

    /**
     * Copies [preRestoreDatabase] to a temporary file (so the verified safety backup itself is
     * never opened for writing or changed), merges from the copy into [restoredDatabase], and
     * deletes the copy. The merge is one transaction: on any failure nothing is changed.
     * Call it only while FocusFlow's own connection to [restoredDatabase] is closed.
     */
    fun apply(restoredDatabase: File, preRestoreDatabase: File): MergeOutcome {
        if (!restoredDatabase.isFile) return MergeOutcome.Failed("the restored database file is missing")
        if (!preRestoreDatabase.isFile) return MergeOutcome.Failed("the pre-restore copy is missing")
        val copy = try {
            Files.createTempFile("focusflow-merge-", ".db").toFile()
        } catch (failure: Exception) {
            return MergeOutcome.Failed("could not create a temporary file")
        }
        return try {
            Files.copy(preRestoreDatabase.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val config = SQLiteConfig().apply { setBusyTimeout(5_000) }
            val dataSource = SQLiteDataSource(config).apply {
                url = "jdbc:sqlite:${restoredDatabase.absolutePath}"
            }
            dataSource.connection.use { connection -> mergeInto(connection, copy) }
            MergeOutcome.Merged
        } catch (failure: Exception) {
            MergeOutcome.Failed(describe(failure))
        } finally {
            deleteQuietly(copy)
        }
    }

    /** Runs the whole merge on an open connection to the RESTORED database. */
    internal fun mergeInto(connection: Connection, preRestoreCopy: File) {
        val quotedPath = preRestoreCopy.absolutePath.replace("'", "''")
        connection.createStatement().use { statement ->
            statement.execute("ATTACH DATABASE '$quotedPath' AS pre")
            try {
                statement.execute("BEGIN IMMEDIATE")
                try {
                    mergeStatements.forEach { statement.executeUpdate(it) }
                    mergeCommaList(connection, "blocked_keywords")
                    mergeCommaList(connection, "vpn_custom_processes")
                    statement.execute("COMMIT")
                } catch (failure: Throwable) {
                    runCatching { statement.execute("ROLLBACK") }
                    throw failure
                }
            } finally {
                runCatching { statement.execute("DETACH DATABASE pre") }
            }
        }
    }

    /** Comma-separated lists (blocked keywords, custom VPN processes): keep the union, restored order first. */
    private fun mergeCommaList(connection: Connection, key: String) {
        fun read(schema: String): List<String> =
            connection.prepareStatement("SELECT value FROM $schema.settings WHERE key = ?").use { statement ->
                statement.setString(1, key)
                statement.executeQuery().use { rows ->
                    if (rows.next()) {
                        rows.getString(1).orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    } else {
                        emptyList()
                    }
                }
            }

        val before = read("pre")
        if (before.isEmpty()) return
        val restored = read("main")
        val seen = restored.map { it.lowercase() }.toMutableSet()
        val merged = restored.toMutableList()
        for (item in before) {
            if (seen.add(item.lowercase())) merged += item
        }
        if (merged.size == restored.size) return
        connection.prepareStatement("INSERT OR REPLACE INTO main.settings(key, value) VALUES (?, ?)").use { statement ->
            statement.setString(1, key)
            statement.setString(2, merged.joinToString(","))
            statement.executeUpdate()
        }
    }

    /** Short, path-free description that is safe to show in the restore notice. */
    private fun describe(failure: Exception): String {
        val detail = if (failure is SQLException) failure.message else null
        return listOfNotNull(failure.javaClass.simpleName, detail).joinToString(": ")
    }

    private fun deleteQuietly(file: File) {
        listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal"))
            .forEach { runCatching { it.delete() } }
    }
}
```

**Create** `src/test/kotlin/com/focusflow/services/RestoreProtectionMergeTest.kt` with exactly this content:

```kotlin
package com.focusflow.services

import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.sqlite.SQLiteDataSource

class RestoreProtectionMergeTest {
    private val dir: File = createTempDirectory("focusflow-merge-test-").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    // Same columns as the real tables in Database.kt (migrations v1-v10).
    private val schema = listOf(
        "CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        "CREATE TABLE block_rules (id TEXT PRIMARY KEY, process_name TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL, enabled INTEGER DEFAULT 1, block_network INTEGER DEFAULT 0, source TEXT DEFAULT 'manual')",
        "CREATE TABLE block_schedules (id TEXT PRIMARY KEY, name TEXT NOT NULL, days_of_week TEXT NOT NULL, start_hour INTEGER NOT NULL, start_minute INTEGER NOT NULL, end_hour INTEGER NOT NULL, end_minute INTEGER NOT NULL, enabled INTEGER DEFAULT 1, process_names TEXT DEFAULT '')",
        "CREATE TABLE daily_allowances (process_name TEXT PRIMARY KEY, display_name TEXT NOT NULL, allowance_minutes INTEGER NOT NULL)",
        "CREATE TABLE network_cutoff_rules (id TEXT PRIMARY KEY, pattern TEXT NOT NULL, mode TEXT NOT NULL, target_process TEXT, target_display_name TEXT, enabled INTEGER DEFAULT 1)"
    )

    private fun connect(file: File): Connection =
        SQLiteDataSource().apply { url = "jdbc:sqlite:${file.absolutePath}" }.connection

    private fun newDb(name: String, vararg statements: String): File {
        val file = File(dir, name)
        connect(file).use { connection ->
            connection.createStatement().use { statement ->
                (schema + statements.toList()).forEach { statement.executeUpdate(it) }
            }
        }
        return file
    }

    private fun rows(file: File, sql: String): List<List<String?>> =
        connect(file).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { results ->
                    val columns = results.metaData.columnCount
                    val all = mutableListOf<List<String?>>()
                    while (results.next()) all += (1..columns).map { results.getString(it) }
                    all
                }
            }
        }

    private fun settings(file: File): Map<String, String> =
        rows(file, "SELECT key, value FROM settings").associate { it[0]!! to it[1]!! }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun strongerCurrentProtectionsSurviveARestoreOfAWeakerBackup() {
        val current = newDb(
            "current.db",
            "INSERT INTO settings VALUES ('always_on_enforcement','true'), ('nuclear_mode','true'), ('keyword_blocker_enabled','false'), " +
                "('global_pin_hash','abc'), ('nuclear_pin_hash',''), ('standalone_block_until','9999999999999'), " +
                "('standalone_block_processes','x.exe'), ('standalone_block_start','0')",
            "INSERT INTO block_rules(id, process_name, display_name, enabled, block_network) VALUES ('r1','chrome.exe','Chrome',1,1), ('r2','discord.exe','Discord',1,0)",
            "INSERT INTO daily_allowances VALUES ('youtube.exe','YouTube',30), ('tiktok.exe','TikTok',10)",
            "INSERT INTO block_schedules VALUES ('s1','Work','1,2,3',9,0,17,0,1,'a.exe')",
            "INSERT INTO network_cutoff_rules(id, pattern, mode, enabled) VALUES ('n1','x.com','DOMAIN',1)"
        )
        val restored = newDb(
            "restored.db",
            "INSERT INTO settings VALUES ('always_on_enforcement','false'), ('global_pin_hash','')",
            "INSERT INTO block_rules(id, process_name, display_name, enabled, block_network) VALUES ('r9','chrome.exe','Chrome',0,0)",
            "INSERT INTO daily_allowances VALUES ('youtube.exe','YouTube',120)"
        )

        assertIs<RestoreProtectionMerge.MergeOutcome.Merged>(RestoreProtectionMerge.apply(restored, current))

        val merged = settings(restored)
        assertEquals("true", merged["always_on_enforcement"])
        assertEquals("true", merged["nuclear_mode"])
        assertEquals(null, merged["keyword_blocker_enabled"])
        assertEquals("abc", merged["global_pin_hash"])
        assertEquals(null, merged["nuclear_pin_hash"])
        assertEquals("9999999999999", merged["standalone_block_until"])
        assertEquals("x.exe", merged["standalone_block_processes"])
        assertEquals(
            listOf(listOf<String?>("chrome.exe", "1", "1"), listOf<String?>("discord.exe", "1", "0")),
            rows(restored, "SELECT process_name, enabled, block_network FROM block_rules ORDER BY process_name")
        )
        assertEquals(
            listOf(listOf<String?>("tiktok.exe", "10"), listOf<String?>("youtube.exe", "30")),
            rows(restored, "SELECT process_name, allowance_minutes FROM daily_allowances ORDER BY process_name")
        )
        assertEquals(listOf(listOf<String?>("1")), rows(restored, "SELECT enabled FROM block_schedules WHERE id = 's1'"))
        assertEquals(listOf(listOf<String?>("1")), rows(restored, "SELECT enabled FROM network_cutoff_rules WHERE id = 'n1'"))
    }

    @Test
    fun aStrongerBackupIsNeverLoosened() {
        val current = newDb(
            "current.db",
            "INSERT INTO settings VALUES ('global_pin_hash',''), ('standalone_block_until','100')",
            "INSERT INTO daily_allowances VALUES ('youtube.exe','YouTube',90)"
        )
        val restored = newDb(
            "restored.db",
            "INSERT INTO settings VALUES ('global_pin_hash','backuppin'), ('always_on_enforcement','true'), ('standalone_block_until','5000')",
            "INSERT INTO daily_allowances VALUES ('youtube.exe','YouTube',20)"
        )

        assertIs<RestoreProtectionMerge.MergeOutcome.Merged>(RestoreProtectionMerge.apply(restored, current))

        val merged = settings(restored)
        assertEquals("backuppin", merged["global_pin_hash"])
        assertEquals("true", merged["always_on_enforcement"])
        assertEquals("5000", merged["standalone_block_until"])
        assertEquals(listOf(listOf<String?>("20")), rows(restored, "SELECT allowance_minutes FROM daily_allowances"))
    }

    @Test
    fun anUnprotectedCurrentDatabaseLeavesTheRestoreExactlyAsItWas() {
        val current = newDb("current.db")
        val restored = newDb("restored.db", "INSERT INTO settings VALUES ('always_on_enforcement','false')")

        assertIs<RestoreProtectionMerge.MergeOutcome.Merged>(RestoreProtectionMerge.apply(restored, current))

        assertEquals(mapOf("always_on_enforcement" to "false"), settings(restored))
    }

    @Test
    fun keywordAndVpnListsAreUnionedWithoutDuplicates() {
        val current = newDb(
            "current.db",
            "INSERT INTO settings VALUES ('blocked_keywords','alpha, Beta'), ('vpn_custom_processes','v1.exe')"
        )
        val restored = newDb(
            "restored.db",
            "INSERT INTO settings VALUES ('blocked_keywords','beta,gamma')"
        )

        assertIs<RestoreProtectionMerge.MergeOutcome.Merged>(RestoreProtectionMerge.apply(restored, current))

        val merged = settings(restored)
        assertEquals("beta,gamma,alpha", merged["blocked_keywords"])
        assertEquals("v1.exe", merged["vpn_custom_processes"])
    }

    @Test
    fun theSafetyBackupFileIsNeverChanged() {
        val current = newDb(
            "current.db",
            "INSERT INTO settings VALUES ('always_on_enforcement','true')"
        )
        val restored = newDb("restored.db")
        val before = sha256(current)

        assertIs<RestoreProtectionMerge.MergeOutcome.Merged>(RestoreProtectionMerge.apply(restored, current))

        assertEquals(before, sha256(current))
        assertEquals(listOf("current.db", "restored.db"), dir.list().orEmpty().sorted())
    }

    @Test
    fun aMissingOrUnreadableSourceFailsAndLeavesTheRestoredDatabaseUntouched() {
        val restored = newDb("restored.db", "INSERT INTO settings VALUES ('always_on_enforcement','false')")
        val garbage = File(dir, "garbage.db").apply { writeText("this is not a database ".repeat(40)) }

        assertIs<RestoreProtectionMerge.MergeOutcome.Failed>(
            RestoreProtectionMerge.apply(restored, File(dir, "missing.db"))
        )
        assertIs<RestoreProtectionMerge.MergeOutcome.Failed>(RestoreProtectionMerge.apply(restored, garbage))
        assertEquals(mapOf("always_on_enforcement" to "false"), settings(restored))
    }

    @Test
    fun aMissingRestoredDatabaseIsNotCreatedByTheMerge() {
        val current = newDb("current.db", "INSERT INTO settings VALUES ('always_on_enforcement','true')")
        val restored = File(dir, "restored.db")

        assertIs<RestoreProtectionMerge.MergeOutcome.Failed>(RestoreProtectionMerge.apply(restored, current))
        assertEquals(false, restored.exists())
    }
}
```

3. Run `gradle :test --no-daemon --tests "com.focusflow.services.RestoreProtectionMergeTest"`. Expected: **7 tests pass** (these 7 passed here against sqlite-jdbc 3.47.1.0). If one fails: STOP.
4. Create the integration test:

**Create** `src/test/kotlin/com/focusflow/services/RestoreKeepsProtectionsTest.kt` with exactly this content:

```kotlin
package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.DbInitResult
import com.focusflow.testutil.TempHome
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RestoreKeepsProtectionsTest {
    @AfterTest
    fun resetServices() {
        AutoBackupService.resetForTest()
    }

    @Test
    fun restoringAnOlderBackupKeepsTheProtectionsYouHaveNow() =
        TempHome.withHome {
            assertEquals(DbInitResult.Ready, Database.init())
            // The backup is taken while there is no PIN and Always-On is off.
            assertEquals(Database.SettingWrite.Ok, Database.writeSetting("restore_test_value", "from-backup"))
            val backup = assertIs<AutoBackupService.BackupResult.Success>(AutoBackupService.runBackupNow())

            // Later the user turns protections on.
            assertEquals(Database.SettingWrite.Ok, Database.writeSetting("restore_test_value", "newer"))
            assertEquals(Database.SettingWrite.Ok, Database.writeSetting("global_pin_hash", "kept-hash"))
            assertEquals(Database.SettingWrite.Ok, Database.writeSetting("always_on_enforcement", "true"))

            assertIs<RestoreScheduleResult.Scheduled>(BackupRecoveryService.scheduleRestore(backup.file))
            assertEquals(DbInitResult.Ready, BackupRecoveryService.initializeDatabaseForStartup())

            // The data comes from the backup ...
            assertEquals(Database.SettingRead.Present("from-backup"), Database.readSetting("restore_test_value"))
            // ... but the protections are NOT weaker than they were before the restore.
            assertEquals(Database.SettingRead.Present("kept-hash"), Database.readSetting("global_pin_hash"))
            assertEquals(Database.SettingRead.Present("true"), Database.readSetting("always_on_enforcement"))
            assertEquals("Backup restored", RecoveryNoticeService.pendingNotice()?.title)
        }
}
```

5. **RED check:** `gradle :test --no-daemon --tests "com.focusflow.services.RestoreKeepsProtectionsTest"`. Expected: it **fails** on an `assertEquals` about `global_pin_hash` or `always_on_enforcement` (today a restore wipes them). If it passes: STOP.
6. Apply these edits to `BackupRecoveryService.kt` in order:

**Edit B1-1** — file `src/main/kotlin/com/focusflow/services/BackupRecoveryService.kt`  
Runs the merge after the restored database opened; on failure uses the existing rollback.  
Use the Edit tool. `old_string` (must occur **exactly once** — if it does not, STOP):
```kotlin
        if (restoredResult == DbInitResult.Ready && !newlyBroken) {
            clearPendingRestoreFiles()
            RecoveryNoticeService.writeRestoreNotice(
                "Backup restored",
                "FocusFlow restored ${journalSourceName(journal)}. A verified safety backup and the previous database files were preserved in the backup folder."
            )
            return DbInitResult.Ready
        }
```
`new_string`:
```kotlin
        if (restoredResult == DbInitResult.Ready && !newlyBroken) {
            val mergeFailure = mergeProtectionsIntoRestoredDatabase(
                safetyBackup.file
            )
            if (mergeFailure != null) {
                // The database is closed at this point, which is what rollbackAndReopen expects.
                return rollbackAndReopen(
                    applyingJournal,
                    "FocusFlow could not keep your current protections while restoring ($mergeFailure), so the restore was undone and your previous database was put back."
                )
            }
            clearPendingRestoreFiles()
            RecoveryNoticeService.writeRestoreNotice(
                "Backup restored",
                "FocusFlow restored ${journalSourceName(journal)}. Protections you already had (PIN, always-on, block rules, schedules, limits) were kept, so a restore never weakens them. A verified safety backup and the previous database files were preserved in the backup folder."
            )
            return DbInitResult.Ready
        }
```
Check (right after this edit): `grep -c -F 'val mergeFailure = mergeProtectionsIntoRestoredDatabase(' src/main/kotlin/com/focusflow/services/BackupRecoveryService.kt` prints **1**.

**Edit B1-2** — file `src/main/kotlin/com/focusflow/services/BackupRecoveryService.kt`  
Adds the helper just above rollbackAndReopen.  
Use the Edit tool. `old_string` (must occur **exactly once** — if it does not, STOP):
```kotlin
    private fun rollbackAndReopen(
```
`new_string`:
```kotlin
    /**
     * A restore may bring back data, but it must never leave protections weaker than they were
     * just before it. Returns null on success (the database is open again), or a short reason on
     * failure (the database is closed, so the caller can roll back).
     */
    private fun mergeProtectionsIntoRestoredDatabase(safetyBackupFile: File): String? {
        Database.close()
        val merged = RestoreProtectionMerge.apply(AppPaths.dbFile, safetyBackupFile)
        if (merged is RestoreProtectionMerge.MergeOutcome.Failed) return merged.reason
        if (Database.init() != DbInitResult.Ready) {
            Database.close()
            return "the database did not reopen after the merge"
        }
        return null
    }

    private fun rollbackAndReopen(
```
Check (right after this edit): `grep -c -F 'private fun mergeProtectionsIntoRestoredDatabase(safetyBackupFile: File): String? {' src/main/kotlin/com/focusflow/services/BackupRecoveryService.kt` prints **1**.


7. **GREEN check:**
```bash
gradle :test --no-daemon --tests "com.focusflow.services.RestoreKeepsProtectionsTest" --tests "com.focusflow.services.RestoreProtectionMergeTest" --tests "com.focusflow.services.BackupRecoveryServiceTest"
```
   Expected: everything passes, **including all existing `BackupRecoveryServiceTest` tests**. If an existing test fails: STOP, undo the two edits, record the test name and message. **Do not edit that test.**
8. Changelog line:

**Edit CL-B1** — file `src/main/kotlin/com/focusflow/ui/screens/ChangelogScreen.kt`  
Adds one line to the 2.0.6 changelog list (the new line goes just above the last line).  
Use the Edit tool. `old_string` (must occur **exactly once** — if it does not, STOP):
```kotlin
            "UPD" to "Windows-specific behavior still needs hands-on verification"
```
`new_string`:
```kotlin
            "SEC" to "Restoring a backup now keeps the protections you already had (PIN, always-on, block rules, schedules, limits)",
            "UPD" to "Windows-specific behavior still needs hands-on verification"
```
Check (right after this edit): `grep -c -F '"SEC" to "Restoring a backup now keeps the protections you already had (PIN, always-on, block rules, schedules, limits)",' src/main/kotlin/com/focusflow/ui/screens/ChangelogScreen.kt` prints **1**.


9. Full `gradle :test --no-daemon`, compare with the baseline. Tracker: tick B1 with evidence.

**Done when:** steps 3, 5, 7, 9 behaved exactly as described. **Owner's Windows check:** W-2.

### Card B2 — Quit on the Store (MSIX) build asks for the PIN (needs `ANSWER O4: B`)
**Fixes:** H4, first part. **Files:** NEW `UninstallProtectionQuitGateTest.kt`; EDIT `UninstallProtectionService.kt`, `ChangelogScreen.kt`. **Do not touch `Main.kt`.**

1. Check the gate. Tracker: B2 in progress.
2. Create the test:

**Create** `src/test/kotlin/com/focusflow/services/UninstallProtectionQuitGateTest.kt` with exactly this content:

```kotlin
package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.SettingsStore
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/** Owner decision O4-B: the Store/MSIX build asks for the same PINs as the direct build when quitting. */
class UninstallProtectionQuitGateTest {
    private lateinit var store: StubSettingsStore
    private val messageTitles = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        Database.resetForTest()
        UninstallProtectionService.resetForTest()
        store = StubSettingsStore()
        Database.settingsStoreForTest = store
        // Pretend to be the Store/MSIX build.
        UninstallProtectionService.isDirectInstallForTest = false
        UninstallProtectionService.messageDialogForTest = { title, _ -> messageTitles += title }
    }

    @AfterEach
    fun tearDown() {
        UninstallProtectionService.resetForTest()
        Database.resetForTest()
    }

    @Test
    fun quitIsStillAllowedWhenNothingIsProtected() {
        assertTrue(UninstallProtectionService.authorizeQuit())
        assertEquals(emptyList<String>(), messageTitles)
    }

    @Test
    fun quitIsRefusedWhileNuclearModeIsOnWithoutANuclearPin() {
        store.reads["nuclear_mode"] = Database.SettingRead.Present("true")

        assertFalse(UninstallProtectionService.authorizeQuit())
        assertEquals(listOf("Action blocked"), messageTitles)
    }

    @Test
    fun quitNeedsTheGlobalPinWhileAlwaysOnIsOn() {
        store.reads["always_on_enforcement"] = Database.SettingRead.Present("true")
        store.reads["global_pin_hash"] = Database.SettingRead.Present(sha256("correct-pin-1234"))

        UninstallProtectionService.pinPromptForTest = { "wrong-pin" }
        assertFalse(UninstallProtectionService.authorizeQuit())
        assertEquals(listOf("Incorrect PIN"), messageTitles)

        UninstallProtectionService.pinPromptForTest = { "correct-pin-1234" }
        assertTrue(UninstallProtectionService.authorizeQuit())
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private class StubSettingsStore : SettingsStore {
        val reads = mutableMapOf<String, Database.SettingRead>()
        override fun readSetting(key: String): Database.SettingRead =
            reads[key] ?: Database.SettingRead.Absent

        override fun writeSetting(key: String, value: String) = Database.SettingWrite.Ok
        override fun writeSettings(pairs: Map<String, String>) = Database.SettingWrite.Ok
    }
}
```

3. **RED check:** `gradle :test --no-daemon --tests "com.focusflow.services.UninstallProtectionQuitGateTest"`. Expected: `quitIsRefusedWhileNuclearModeIsOnWithoutANuclearPin` and `quitNeedsTheGlobalPinWhileAlwaysOnIsOn` **fail** (today the Store variant returns `true` at once); `quitIsStillAllowedWhenNothingIsProtected` passes. If they pass now: STOP.
4. Apply the edit to `UninstallProtectionService.kt`:

**Edit B2-1** — file `src/main/kotlin/com/focusflow/services/UninstallProtectionService.kt`  
Removes the early 'return true' for non-direct installs.  
Use the Edit tool. `old_string` (must occur **exactly once** — if it does not, STOP):
```kotlin
    fun authorizeQuit(): Boolean {
        if (!isDirectInstall) return true

        return authorizeRequirements(activeRequirements(Action.QUIT))
    }
```
`new_string`:
```kotlin
    fun authorizeQuit(): Boolean {
        // Store/MSIX builds are gated too (owner decision O4-B). Only package REMOVAL is owned
        // by Windows and is never intercepted here (see prepareForUninstallWizard).
        return authorizeRequirements(activeRequirements(Action.QUIT))
    }
```
Check (right after this edit): `grep -c -F 'if (!isDirectInstall) return true' src/main/kotlin/com/focusflow/services/UninstallProtectionService.kt` prints **3**. (it was **4** before this edit; the other 3 belong to other functions and must stay exactly as they are)


5. **GREEN check:** `gradle :test --no-daemon --tests "com.focusflow.services.UninstallProtectionQuitGateTest" --tests "com.focusflow.services.UninstallProtectionSettingsTest"` → all pass.
6. Changelog line:

**Edit CL-B2** — file `src/main/kotlin/com/focusflow/ui/screens/ChangelogScreen.kt`  
Adds one line to the 2.0.6 changelog list (the new line goes just above the last line).  
Use the Edit tool. `old_string` (must occur **exactly once** — if it does not, STOP):
```kotlin
            "UPD" to "Windows-specific behavior still needs hands-on verification"
```
`new_string`:
```kotlin
            "SEC" to "The Microsoft Store version now asks for your PIN to quit while a protection is active",
            "UPD" to "Windows-specific behavior still needs hands-on verification"
```
Check (right after this edit): `grep -c -F '"SEC" to "The Microsoft Store version now asks for your PIN to quit while a protection is active",' src/main/kotlin/com/focusflow/ui/screens/ChangelogScreen.kt` prints **1**.


7. Full `gradle :test --no-daemon`, compare with the baseline. Tracker: tick B2.

**Done when:** steps 3, 5, 7 behaved as described. **Owner's Windows check:** W-3. (Why this should be safe: `grep -rn "authorizeQuit(" src` shows only the definition (`UninstallProtectionService.kt:156`) and one caller (`Main.kt:304`, inside `doShutdown`, which skips the check when `skipQuitAuthorization` is true); no existing test calls it. Which code paths pass `skipQuitAuthorization = true`, and whether Windows sign-out / shutdown goes through `doShutdown`, was **not** re-checked — W-3 covers that on a real PC.)

### Card B3 — Remove the "Forgot PIN?" path (needs `ANSWER O1: A`)
**Fixes:** H1. **Files:** NEW `PinResetPathSourceTest.kt`; EDIT `PinGateDialog.kt`, `ChangelogScreen.kt`.

1. Check the gate. Tracker: B3 in progress.
2. Create the test:

**Create** `src/test/kotlin/com/focusflow/services/PinResetPathSourceTest.kt` with exactly this content:

```kotlin
package com.focusflow.services

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Owner decision O1-A: a PIN that can be reset from its own lock dialog is no protection.
 * This is a structural guard (it reads source files; it does not run the UI).
 */
class PinResetPathSourceTest {
    private val sourceRoot: Path = Path.of("src/main/kotlin")

    private fun kotlinFilesContaining(needle: String): Set<String> =
        Files.walk(sourceRoot).use { stream ->
            stream.iterator().asSequence()
                .filter { it.toString().endsWith(".kt") }
                .filter { Files.readString(it).contains(needle) }
                .map { it.fileName.toString() }
                .toSet()
        }

    @Test
    fun theForgotPinLinkIsOffByDefault() {
        val dialog = Files.readString(sourceRoot.resolve("com/focusflow/ui/components/PinGateDialog.kt"))
        assertTrue(dialog.contains("allowReset: Boolean = false"))
    }

    @Test
    fun noCallerTurnsTheForgotPinLinkBackOn() {
        assertEquals(
            emptySet<String>(),
            kotlinFilesContaining("allowReset = true"),
            "A caller passes allowReset = true. That re-opens the PIN reset path; the owner must approve it first."
        )
    }

    @Test
    fun onlyTheKnownFlowsCallResetWithoutPin() {
        assertEquals(
            setOf("GlobalPin.kt", "PinGateDialog.kt", "SettingsScreen.kt"),
            kotlinFilesContaining("resetWithoutPin("),
            "A new file calls GlobalPin.resetWithoutPin(). Use the verified GlobalPin.clearResult(rawPin) instead."
        )
    }
}
```

3. **RED check:** `gradle :test --no-daemon --tests "com.focusflow.services.PinResetPathSourceTest"`. Expected: `theForgotPinLinkIsOffByDefault` **fails**; the other two pass. If `theForgotPinLinkIsOffByDefault` passes: STOP.
4. Apply the two edits to `PinGateDialog.kt` in order:

**Edit B3-1** — file `src/main/kotlin/com/focusflow/ui/components/PinGateDialog.kt`  
Updates the doc comment (the next doc-comment lines stay as they are).  
Use the Edit tool. `old_string` (must occur **exactly once** — if it does not, STOP):
```kotlin
 * @param allowReset When true (default), the "Forgot PIN?" reset link is shown
```
`new_string`:
```kotlin
 * @param allowReset OFF by default (owner decision O1-A: a PIN that can be reset from its own lock
 *   dialog is no protection; PinResetPathSourceTest fails if a caller passes true). When true, the
 *   "Forgot PIN?" reset link is shown
```
Check (right after this edit): `grep -c -F '@param allowReset OFF by default' src/main/kotlin/com/focusflow/ui/components/PinGateDialog.kt` prints **1**.

**Edit B3-2** — file `src/main/kotlin/com/focusflow/ui/components/PinGateDialog.kt`  
The one-word change that hides the link at every call site.  
Use the Edit tool. `old_string` (must occur **exactly once** — if it does not, STOP):
```kotlin
    allowReset: Boolean = true,
```
`new_string`:
```kotlin
    allowReset: Boolean = false,
```
Check (right after this edit): `grep -c -F 'allowReset: Boolean = false,' src/main/kotlin/com/focusflow/ui/components/PinGateDialog.kt` prints **1**.


5. **GREEN check:** the same command as step 3 → all 3 pass. Then `gradle :compileKotlin --no-daemon` → `BUILD SUCCESSFUL`.
6. Changelog line:

**Edit CL-B3** — file `src/main/kotlin/com/focusflow/ui/screens/ChangelogScreen.kt`  
Adds one line to the 2.0.6 changelog list (the new line goes just above the last line).  
Use the Edit tool. `old_string` (must occur **exactly once** — if it does not, STOP):
```kotlin
            "UPD" to "Windows-specific behavior still needs hands-on verification"
```
`new_string`:
```kotlin
            "SEC" to "The Forgot PIN? reset link is gone from PIN prompts, so a PIN can only be changed by someone who knows it",
            "UPD" to "Windows-specific behavior still needs hands-on verification"
```
Check (right after this edit): `grep -c -F '"SEC" to "The Forgot PIN? reset link is gone from PIN prompts, so a PIN can only be changed by someone who knows it",' src/main/kotlin/com/focusflow/ui/screens/ChangelogScreen.kt` prints **1**.


7. Full `gradle :test --no-daemon`, compare with the baseline. Tracker: tick B3.

**Notes.** All five places that used the default (`FocusScreen`, `ProfileScreen`, `KeywordBlockerScreen`, `VpnNetworkScreen`, `SettingsScreen` ~line 1250) lose the link automatically. The now-unreachable "forgot" steps stay inside `PinGateDialog.kt` — **leave them** (a stronger model can delete them later). The Settings "remove PIN" flow (correct PIN → `GlobalPin.resetWithoutPin`) is a *verified* flow and keeps working — that is why `SettingsScreen.kt` is allowed in the test. **Owner's Windows check:** W-4.

---

## 6. Card Z — Finish
1. `find src -name "*.orig"` prints nothing (delete any you made).
2. Full `gradle :test --no-daemon`; record totals (use the §4/A0 one-liner) next to the baseline and say which JVM ran the tests.
3. Final report, in this order: **(1)** table: card · done / skipped (gate) / stopped · files changed; **(2)** baseline vs final test totals; **(3)** gated cards you skipped and why; **(4)** Windows checks **not run** (list the W-ids from §9); **(5)** deviations and STOP events; **(6)** out-of-scope observations; **(7)** any `old_string` that no longer matched.
4. Do not claim anything that is not in the tracker with evidence.

---

## 7. NOT FOR THIS AGENT — real holes that need a stronger model and/or an owner decision

These are listed so nobody thinks they are closed. **Do not start any of them.** Each one can lock users out or silently drop protection if done badly, and none can be fully tested without Windows.

**N1 — The database can be deleted, replaced, edited or locked (H3, H9, H10) · owner decision O2.** *[code]* A missing/fresh database has no PIN and no rules (`PinPolicy.kt:50,64`: an absent row means "no PIN"); a deleted file gives no recovery notice (`RecoveryNoticeService.kt:33-77`); the block-rule cache re-reads SQLite every ~2 s, so a SQLite tool can disable a rule with no PIN while the app runs (`ProcessMonitor.kt:60,332-357`); holding the DB file or `instance.lock` stops enforcement from starting (`Main.kt:362-378,430-452`, `SingleInstanceGuard.kt:81-130`). *[idea]* A tamper-evident protection snapshot (HMAC, key protected by Windows DPAPI via the `jna-platform` already in the build) written after every in-app protection change; at startup and in the 2 s cycle, restore stronger values and re-seed a fresh DB from it; when the DB is busy/unreadable, enforce read-only from the snapshot. It stops SQLite tools, Explorer deletes and weaker restores; a *program* running as the same Windows user can still re-seal it. Reopens decisions D10/D11.

**N2 — Killing the app, and the weak watchdog (H5).** *[code]* The watchdog is a scheduled task every 2 minutes that checks `Get-Process -Name 'FocusFlow'` — by name only (`WatchdogInstaller.kt:23-24,49-60`), so a decoy process with that name satisfies it; task and Run-key are removable without admin *[verify]*; `schtasks /tr` has a ~261-character limit, which is why a naive "check the path" script change is risky. No process or file ACL hardening exists (a grep for `SetKernelObjectSecurity|SetSecurityInfo|DACL` over `src/main/kotlin` finds nothing). *[idea]* Deny `PROCESS_TERMINATE` to ordinary same-user processes on FocusFlow's own process (so `taskkill`/Task Manager fail with "access denied") — needs careful Windows testing (graceful close, installer and Store updates must still work); heartbeat file + path-aware relaunch; clean-exit marker; re-create missing tasks and log it.

**N3 — Nuclear Mode's escape list (H6, H6b).** *[code]* `taskkill.exe`, `tskill.exe`, `schtasks.exe`, `reg.exe` etc. are **not** in `escapeProcesses` (`NuclearMode.kt:50-73`), and detection is image name **or** canonical System32 path (`:142-196`), so a *renamed copy* of a listed tool in a user folder is not detected (the comment at `:75-81` suggests otherwise; it only holds for copies that stay in System32). *[idea, important]* **Do not just add the names to the list.** The sweep runs every 500 ms (`NuclearMode.kt:274-279`); one-shot command-line tools usually finish in far less than that, so a polling list would catch only some attempts (not measured — W-7 measures it) and could kill FocusFlow's *own* helper `taskkill`/`schtasks`/`reg` children unless an "own direct child" exemption is added (`LauncherKioskPolicy.isFocusFlowOwnedUtility` exists for that). The reliable fixes are N2's process hardening, or an Explorer-level block (HKCU "DisallowRun" policy — not used anywhere in the repo today), plus PE `OriginalFilename` checks for renamed copies.

**N4 — Emergency Break (H7) · owner decision O5.** *[code]* No PIN, 5 min/day, pauses everything including Nuclear Mode and the kiosk (`KillSwitchService.kt:62-85,126-170`, `NuclearMode.kt:283`, `ProcessMonitor.kt:450-451`); the budget is a date-keyed value in the database. *[idea]* Monotonic budget; PIN or typed delay to start; keep the self-protection tools blocked during a break.

**N5 — Clock and time-zone changes (H8).** *[code]* Schedules, daily allowance, standalone blocks and the break budget use `LocalDateTime.now()` / `LocalDate.now()` / `System.currentTimeMillis()`; no tamper detection exists. A standard user can usually change the time zone *[verify]*. *[idea]* A `TrustedClock` that anchors zone + monotonic time and flags rollback / forward jump / zone change.

**N6 — Name-only process identity (H11).** *[code]* User block rules and the kiosk safe list match the process image name (`ProcessMonitor.kt:802`, `LauncherKioskPolicy.kt:72-73`), so a renamed copy of an allowed program is allowed in kiosk mode. *[idea]* Require the resolved path to match the owner of the name (Windows names only under `%SystemRoot%`, etc.) and add PE `OriginalFilename` matching; if the path cannot be resolved keep today's behaviour (never kill critical processes).

**N7 — Nuclear Mode feels slow on an old PC (H13) · measure first.** *[code]* Each 500 ms sweep calls `ProcessHandle.info()`/`parent()` for every process (`NuclearMode.kt:280-352`, `ProcessSnapshot.kt:64,133-160`); a shared snapshot with a 250 ms cache exists; the allowance engine does its own full scan every 10 s (`DailyAllowanceTracker.kt:71-84`); with an escape tool present a `taskkill` is spawned each tick. *[idea]* One Toolhelp snapshot instead of per-process `info()`; resolve full paths lazily; terminate by PID through JNA instead of spawning `taskkill`; keep the 500 ms cadence unless the owner agrees. Do not claim a speed-up without the owner's before/after numbers (W-6).

**N8 — The UI can overstate protection when not elevated (H14).** *[code]* Nuclear's firewall layer returns early without admin (`NuclearMode.kt:258`); `HostsBlocker.blockDomain` returns `NoAdmin` and the 30 s monitor just returns (`HostsBlocker.kt:70,231`). Whether every screen says so is **[verify]**.

**N9 — Privilege separation (tier T4).** The only structural fix for "same user can undo it" is a service that owns the protection state while the PC owner runs a standard account (or someone else holds the admin/PIN). Design note only; no code.

**N10 — Other owner-gated variants.** "Forgot PIN" with a 72-hour visible cooling-off instead of removal (O1-B); a cancellable 10-minute cool-off for *loosening* a protection when a PIN is set (O7); opt-in "Strict" extras in Nuclear Mode such as `python.exe`/`node.exe`/SQLite tools (O6, default off); the missing-database branch of the uninstall wizard (O8: `UninstallProtectionService.kt:93-98` lets the wizard through when the DB is missing — code behaviour, not a recorded decision).

---

## 8. Findings register (reference)

Tiers: **T0** FocusFlow's own UI only · **T1** Windows built-ins (Explorer, Win+R, Settings, Task Scheduler) · **T2** one-liners and GUI SQLite tools · **T3** a small program/script · **T4** administrator, Safe Mode, offline edits (out of scope). Re-grep any line number before relying on it.

| ID | Finding | Tier | Evidence | Handled by |
|---|---|---|---|---|
| H1 | "Forgot PIN?" (link after 2 wrong tries, type `RESET`) clears the Global PIN. Default call sites: `FocusScreen.kt:294`, `ProfileScreen.kt:591`, `KeywordBlockerScreen.kt:673`, `VpnNetworkScreen.kt:640`, `SettingsScreen.kt:1250`. `allowReset=false` at `AppBlockerScreen.kt:816,1321`, `SettingsScreen.kt:1237`. The UI notice (`ContactScreen.kt:201-202`) says the option "is not currently a usable recovery path" — the code disagrees. | T0 | `PinGateDialog.kt:34,46,152,251-266`; `GlobalPin.kt:98` **[verify]** the owner reports it unusable in practice | B3 |
| H2 | Restore, backup/archive delete and secondary-folder change need no PIN; a restore replaces the whole database. | T0 | `ContactScreen.kt:508,564,585,605,624,678`; `BackupRecoveryService.kt:359,434` | A1, B1 |
| H3 | A missing or fresh DB has no PIN and no rules; no recovery notice for a deleted file. | T1 | `PinPolicy.kt:50,64`; `RecoveryNoticeService.kt:33-77` | N1 |
| H4 | `authorizeQuit()` returns true for non-direct (Store/MSIX) installs; in the DB-unavailable window `quitGate` is ungated for every build. The repo note `windows-uninstall-protection.md` only says package *removal* is Windows-owned — it does not mention tray Quit. | T0/T1 | `UninstallProtectionService.kt:156-160`; `Main.kt:247,301-304` | B2 (first part); rest N1 |
| H5 | Killing the app gives a free window up to the watchdog interval; watchdog is name-only; persistence removable. | T1/T2 | `WatchdogInstaller.kt:23-24,49-60`; `WindowsStartupManager.kt:39,53` | N2 |
| H6 | Nuclear list lacks `taskkill`/`tskill`/`schtasks`/`reg`…; and a 500 ms sweep is racy for one-shot tools. | T1 | `NuclearMode.kt:50-73,274-280` | N3 |
| H6b | Renamed copies of listed tools are not detected. | T1/T2 | `NuclearMode.kt:75-102,142-196` **[verify]** | N3 |
| H7 | Emergency Break: no PIN, pauses everything. | T0/T2 | `KillSwitchService.kt:62-85,126-170`; `NuclearMode.kt:283` | N4 |
| H8 | No clock / time-zone tamper detection. | T2 | `BlockScheduleService.tick()`; `DailyAllowanceTracker.kt:56-58`; `StandaloneBlockService.kt`; `UninstallProtectionService.kt:210,286` | N5 |
| H9 | DB edits take effect within ~2 s while the app runs. | T2 | `ProcessMonitor.kt:60,332-357`; `Database.kt:554` | N1 |
| H10 | Holding the DB or `instance.lock` stops enforcement from starting. | T3 | `Main.kt:362-378,430-452`; `SingleInstanceGuard.kt:81-130` | N1 |
| H11 | Name-only identity for block rules and the kiosk safe list. | T2 | `ProcessMonitor.kt:802`; `LauncherKioskPolicy.kt:72-73` | N6 |
| H12 | Uninstall is a normal-path guard only (decided: D4). Missing-DB branch: O8. | T1/T2 | `UninstallProtectionService.kt:93-98,168-198` | settled / N10 |
| H13 | Slow Nuclear sweep on an old PC. | perf | `NuclearMode.kt:280-352`; `DailyAllowanceTracker.kt:71-84` | N7 |
| H14 | UI can overstate protection when not elevated. | UX | `NuclearMode.kt:258`; `HostsBlocker.kt:70,231` | N8 |

**Checked and found fine — do not "fix":** disabling/deleting block rules, schedules, VPN/network rules and the keyword blocker, and loosening allowances, are Global-PIN-gated in the UI (`AppBlockerScreen`, `BlockDefenseScreen`, `VpnNetworkScreen`, `AllowanceEditPolicy.kt`); Always-On off is gated; the tray toggle refuses when a PIN is set. Ending a focus session early needs the Session PIN; Nuclear PIN dialogs verify before `clearForced`. `ClearUserActivityService` clears only sessions/tasks/notes/temptation log (D13). The hosts monitor re-applies missing entries every 30 s when elevated. Network limits that cannot be fixed in-app: DoH/VPN/caches (`windows-network-enforcement.md`).

**Settled by the owner — do not reopen:** D4 (uninstall with an unreadable DB after a typed phrase; MSIX removal is OS-owned), D9 (backup/recovery design — `VACUUM INTO`, SHA-256 sidecars, restart-only restore, safety backups never auto-deleted), D10-A (DB down ⇒ "Blocking is OFF"), D11 (design only), D12 (no PIN hashing / recovery redesign — O1 above is the only exception, and only by the owner's letter), D1/D5/D6/D17/D19.

---

## 9. Windows checks for the owner (the agent never claims these)

Use a test profile or a copy, and take a manual backup first. Each line: action → expected.

| ID | Check |
|---|---|
| **W-1** (A1) | Global PIN set. Settings → Backup & Recovery: *Stage restore*, *Delete copy*, *Delete archive*, *Change folder*, *Remove folder* → each shows a PIN dialog titled "Enter your PIN" and **no** "Forgot PIN?" link, even after 3 wrong tries; wrong PIN refused; correct PIN runs the action; *Cancel* does nothing. With **no** PIN set → the actions run with no prompt. *Back up now* never asks. |
| **W-2** (B1) | Take a backup **before** turning on a PIN, Always-On and a block rule. Turn them on, stage that backup, quit (with the PIN) and reopen → PIN, Always-On and the rule are **still there**; the backup's tasks/notes are back; the "Backup restored" notice says protections were kept. Also try a stronger backup (PIN set in the backup, none now) → the PIN from the backup arrives. |
| **W-3** (B2) | Store build, Always-On + Global PIN: tray → Quit asks for the PIN; wrong PIN → app stays; no protections → quits without a prompt. **Regression:** signing out of Windows with FocusFlow running still works. |
| **W-4** (B3) | Open each PIN prompt (VPN/Network, Keyword Blocker, Profile → clear activity, Focus break-skip, Settings) and enter a wrong PIN 5 times → no "Forgot PIN?" link. Settings → remove PIN with the **correct** PIN still works. |
| **W-5** (all) | **No lock-out:** Windows sign-in, Explorer, keyboard and mouse normal; FocusFlow starts and blocking still works after every card. |
| **W-6** (N7) | Old PC, Nuclear Mode on for 10 minutes: find the `nuclearSweep durationMs=` lines (they are written to FocusFlow's enforcement log under `%USERPROFILE%\.focusflow\` **[verify the file name]**) and note how often they appear and how large they are. |
| **W-7** (N3) | Nuclear Mode on: press Win+R, run `taskkill /?` 20 times and count how often the window is killed before it prints. (This measures the polling race that N3 is about.) |

---

## 10. Tracker template — create `Security/tracker.md`

```markdown
# Security tracker
Started: <date> · Agent: <name> · Git: <yes/no>

## Owner answers (copied from plan.md §2)
O1: <letter or "not answered"> · O3: <...> · O4: <...>

## Baseline (Card A0)
- gradle / java versions: ...
- `gradle :test --no-daemon`: tests=.. failures=.. errors=.. (JVM: ..)
- failing tests at baseline: ...

## Cards
- [ ] A0 baseline
- [ ] A1 backup actions need the PIN
- [ ] B1 merge on restore (only if O3 = B)
- [ ] B2 Store quit gate (only if O4 = B)
- [ ] B3 remove "Forgot PIN?" (only if O1 = A)
- [ ] Z finish

## Evidence per card
(files changed · exact commands · result lines · failures and STOP events — never erase them)

## Out-of-scope observations

## Windows checks NOT run
W-1 … W-7 (owner)
```

---

## 11. Appendix — how much was actually read

Read in full or in the relevant regions: `PinGateDialog.kt`, `PinPolicy.kt`, `GlobalPin.kt`, `SessionPin.kt`, `ContactScreen.kt` (screen header + backup section), `BackupRecoveryService.kt` (restore flow), `AutoBackupService.kt` (verification code), `UninstallProtectionService.kt` (quit/uninstall gates, test seams), `NuclearMode.kt` (lists, sweep, kill helpers), `LauncherKioskPolicy.kt`, `WinApiBindings.kt` (kill helpers), `WatchdogInstaller.kt`, `RecoveryNoticeService.kt`, `Main.kt` (startup/quit region), `Database.kt` (schema and settings helpers), `ChangelogScreen.kt` (entry format), the call-site regions of the other PIN screens, the allowance / standalone-block / clear-activity services, `.agents` / `fixes` notes, and the heads of the existing tests that the new tests copy from. Other files were only searched (grep), not read end to end — which is why §7 marks several items **[verify]** or **[idea]**.

Not read at all: `NetworkBlocker`, `VpnBlocker` internals, `FocusSessionService`, `FocusLauncherService` (beyond call sites), `ElevationManager`/`RelaunchHandoff`, `WinEventHook`, `GlobalKeyboardHook`, telemetry/Discord code, and most of `ui/`.
