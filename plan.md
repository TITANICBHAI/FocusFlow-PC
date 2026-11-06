# FocusFlow — Phone Usage Analytics (Stats · Extra · Insights)

Implementation plan for the coding agent. Written 2026-11-06 against the repository in `FocusFlow-main.zip`
(native Android, Kotlin, Jetpack Compose, Room schema v10, minSdk 29).

> **How to read this file.** §0 is the working agreement. §1–§6 are reference (what, why, facts, definitions, file map,
> type contracts). §7 is the storage spec. §8 is the task order. §9 is the task list — this is where you work.
> §10–§13 are device checks, risks, appendices and the progress tracker.
> Every existing class name and path in this plan was verified in the repository at planning time. Re-verify with `grep`
> before you call anything. If the repository disagrees with this plan, **stop, write the difference in the Progress log,
> and do not improvise.**

---

## 0. Working agreement (read before every session)

**Goal.** Make FocusFlow answer "how, when and why do I use my phone?" from the phone's own usage data — independent of
tasks — and show the answer in three places inside the existing Stats tab:

| Destination | What it holds |
|---|---|
| **Stats** (existing main screen) | Screen time, app usage with % share, unlocks and wake-ups, daily/weekly and hourly charts |
| **Extra** (existing screen, gains four new tabs) | **Apps** · **Phone** · **Distraction** · **Focus** — detailed analysis |
| **Insights** (new screen) | Automatically detected patterns, unusual changes, personal records, weekly and monthly summaries |

**In scope — six areas:** screen time · app usage · phone behaviour · distraction · focus & blocking · usage insights.
**Out of scope:** task completion, procrastination, scheduling, productivity scores, cloud/sync, AI-written text,
new Android permissions, new Gradle dependencies, changes to enforcement/blocking code, changes to the detectors.

### Rules

1. **One task at a time, in the order of §8.** Do not start a task before its "Needs" are `DONE`.
2. **Before each task** read §3 (facts), §4 (definitions), §6 (contracts) and the whole task section.
3. **Verify, don't assume.** `grep -rn` every existing class/function before using it; copy exact signatures. Never invent an API.
4. **New code goes in new files** (§5). Edit existing files only at the edit points named in the task, with the smallest
   possible diff. Never reformat, reorder or "clean up" existing code.
5. **Do not touch:** `build.gradle.kts` / `gradle/*` / `settings.gradle.kts`, `.github/**`, `enforcement/**`,
   `analytics/detection/**`, the `.focusflow` backup format (`data/backup/**`), the Windows/PC app, existing migrations.
   (Exception list: only the files named in the edit points E1–E6.)
6. **No new dependencies, no new permissions, no network calls.** All analytics stay on the device.
7. **Environment first.** The repo's own scripts bootstrap JDK + Android SDK. Missing Java/SDK = record
   `BLOCKED — environment`, not a code failure. Do not build APKs on Replit; do not push to GitHub.
8. **Commands (exact):**
   - Compile check:
     `FOCUSFLOW_SKIP_APK_BUILD=1 bash scripts/build-apk-with-java.sh :app:compileProductionDebugKotlin`
   - Focused unit tests:
     `FOCUSFLOW_SKIP_APK_BUILD=1 bash scripts/build-apk-with-java.sh :app:testProductionDebugUnitTest --tests "com.tbtechs.focusflow.analytics.phone.*"`
   - Full production unit suite (run at every Gate and before closing the plan):
     `FOCUSFLOW_SKIP_APK_BUILD=1 bash scripts/build-apk-with-java.sh :app:testProductionDebugUnitTest`
   - Android-test compile (migration test only compiles here; it is **run by the owner on a device**):
     `FOCUSFLOW_SKIP_APK_BUILD=1 bash scripts/build-apk-with-java.sh :app:compileProductionDebugAndroidTestKotlin`
   - Test only the `production` flavor. A full run takes about 5–6 minutes; use the focused command while working.
9. **Prove test runs.** After any test command, open `app/build/test-results/testProductionDebugUnitTest/TEST-*.xml`
   and confirm every test class you expect is present and the counts match. A green Gradle message without the XML
   check does not count. A filter that matched nothing is a failure.
10. **Failed patch / failed multi-edit:** re-read the file before retrying — earlier hunks may have applied.
11. **Kotlin gotchas.** In raw strings write `${'$'}` for a literal `$`. Use `File.readText(Charsets.UTF_8)`, not
    `Files.readString`. `org.json` works in JVM tests (it is a `testImplementation`). Never use `Dispatchers.IO` directly
    in code under test — inject a `CoroutineDispatcher`.
12. **Coroutine tests.** Use `runTest` and pass the same test scheduler to every dispatcher you create
    (`StandardTestDispatcher(testScheduler)`); reset `Dispatchers.Main` in `@After` if you set it.
13. **Progress.** After each task update §13: status, files changed, the exact command run and the observed result.
    Never mark something `DONE` that you did not run. A failed check stays unchecked; append the failure and the fix.
14. **Stop and record** (do not push through) when: the same test fails twice for the same reason after fixes; Room
    reports a schema/identity-hash error you cannot fix with Appendix A; a requirement here contradicts the repository.
15. **Task size.** If a task feels too big, finish the part that compiles and passes tests, mark the remainder `TODO` in
    §13, and continue. Never leave the build broken at the end of a session.

### Stage gates (safe stopping points — build compiles, full suite green)

| Gate | After | What is usable |
|---|---|---|
| **A** | T12 | All data + read model done, no UI |
| **B** | T16 | **Stats** destination complete (screen time, unlocks, apps %, charts) |
| **C** | T19 | **Extra** tabs complete (Apps, Phone, Distraction, Focus) |
| **D** | T21 | **Insights** + hardening complete |

---

## 1. What was asked → where it lands (traceability)

"Don't add a statistic just because Android can collect it." Every row answers a question a person would actually ask.

### Six areas

| # | Requested analysis | Question it answers | Destination · card | Task |
|---|---|---|---|---|
| 1.1 | Total screen time | How long was my screen on? | Stats · Screen time | T9, T16 |
| 1.2 | Daily and weekly averages | What's my normal day? | Stats · Screen time; Insights · weekly summary | T9, T13, T16 |
| 1.3 | Screen-on time by hour | When is my phone on? | Stats · Hourly | T9, T16 |
| 1.4 | Weekday vs weekend | Are weekends different? | Stats · Screen time | T9, T16 |
| 1.5 | Highest / lowest days | What were my extremes? | Stats · Screen time; Insights · records | T9, T13, T16 |
| 2.1 | Most-used apps | What eats my time? | Stats · Top apps; Extra · Apps | T10, T16, T17 |
| 2.2 | Duration per app | How long per app? | Stats · Top apps; Extra · Apps | T10, T16, T17 |
| 2.3 | Number of app launches | How often do I open it? | Extra · Apps ("Opens") | T10, T17 |
| 2.4 | Average time per app session | How long is one visit? | Extra · Apps | T10, T17 |
| 2.5 | Most frequently opened apps | Which apps do I reach for? | Extra · Apps (sort by opens); Distraction | T10, T17, T18 |
| 2.6 | App usage trends | Is this app growing? | Extra · Apps (trend, daily bars); Insights | T10, T13, T17 |
| 3.1 | Number of unlocks | How often do I unlock? | Stats · Unlocks | T3, T9, T16 |
| 3.2 | Number of screen wake-ups | How often does the screen turn on? | Stats · Unlocks | T3, T9, T16 |
| 3.3 | Average time between unlocks | How often do I pick it up? | Stats · Unlocks | T3, T9, T16 |
| 3.4 | Short vs long phone sessions | Quick checks or long sessions? | Extra · Phone | T3, T9, T18 |
| 3.5 | Frequent phone-checking patterns | Do I check compulsively? | Extra · Phone; Insights | T9, T13, T18 |
| 3.6 | Time distribution through the day | When do I use it? | Stats · Hourly; Extra · Phone; Extra · Apps | T9, T10, T16–T18 |
| 4.1 | Most distracting apps | Which apps pull me away? | Extra · Distraction | T10, T18 |
| 4.2 | Most frequent distraction hours | When does it happen? | Extra · Distraction | T10, T18 |
| 4.3 | Repeated app-opening patterns | Which apps do I open again and again? | Extra · Distraction | T10, T18 |
| 4.4 | Apps using unexpectedly high time | What's taking more than usual? | Extra · Distraction; Insights | T10, T13, T18 |
| 4.5 | Changes in distraction behaviour | Is it getting better or worse? | Extra · Distraction; Insights | T10, T13, T18 |
| 5.1 | Total time in focus mode | How much did I focus? | Extra · Focus | T11, T19 |
| 5.2 | Total blocked-app attempts | How often did I try? | Extra · Focus; Stats (fixes a hard-coded 0) | T7, T11, T16, T19 |
| 5.3 | Most attempted blocked apps | Which blocked apps tempt me? | Extra · Focus | T11, T19 |
| 5.4 | Successful vs interrupted sessions | Do my focus sessions hold? | Extra · Focus | T11, T19 |
| 5.5 | Session duration distribution | How long are my sessions? | Extra · Focus | T11, T19 |
| 5.6 | Blocking effectiveness over time | Is blocking working? | Extra · Focus; Insights | T11, T13, T19 |
| 6.1 | Unusual screen-time increases | Was a day unusual? | Insights · Changes | T13, T20 |
| 6.2 | Significant changes in app usage | Which app changed? | Insights · Changes | T13, T20 |
| 6.3 | Emerging usage patterns | What habit is forming? | Insights · Patterns | T13, T20 |
| 6.4 | Personal usage records | What are my bests/worsts? | Insights · Records | T13, T20 |
| 6.5 | Weekly and monthly summaries | How did my habits change? | Insights · Summary | T13, T20 |

### The 12-item wish-list

| Wish | Where | Task |
|---|---|---|
| 1 Unlock count | Stats · Unlocks | T16 |
| 2 App opened after unlock | Extra · Phone ("What you open first") | T18 |
| 3 Unlock-to-app patterns ("unlock just to open Instagram") | Extra · Phone (share of unlocks + per day) | T18 |
| 4 Usage session breakdown | Extra · Phone (phone sessions); Extra · Apps (per-app mix) | T17, T18 |
| 5 App usage percentage | Stats · Top apps | T16 |
| 6 App categories | Extra · Apps · Categories | T17 |
| 7 App opening frequency | Extra · Apps (sort by opens) | T17 |
| 8 Usage changes | Extra · Apps (trend); Insights · Changes | T17, T20 |
| 9 Personal records | Insights · Records | T20 |
| 10 Usage consistency | Insights · Patterns ("Consistency") | T20 |
| 11 Weekly insights | Insights · Summary | T20 |
| 12 Time-based app habits | Extra · Apps · Time of day; Insights · Patterns | T17, T20 |

---

## 2. Principles (non-negotiable)

1. **Measured, or say it's unavailable.** Never turn missing data into zero. Every card knows its coverage and says so
   ("based on 5 of 7 days"). A device that doesn't report unlocks shows wake-ups and says why.
2. **Real history only.** Hourly and daily numbers come from stored event history, never reconstructed from daily totals.
3. **Own baseline, own history.** "Unusual" always means unusual for this person, never against a universal norm.
4. **Minimum samples.** No insight is produced below its minimum sample size (§4.20). No "trend" from two data points.
5. **Observations, not diagnoses; correlations, not causes.** No words like "addicted", "disorder", "caused".
   Wording is factual and neutral (templates in Appendix C).
6. **Explain every insight:** what was detected, how it was calculated, which period, how many days of evidence.
7. **Local-first.** No uploads, no network, no remote logging of package names or usage.
8. **Deterministic and stateless.** The same data always yields the same insights; the engine stores nothing.
9. **Understandable.** If a card cannot be explained in one sentence to a non-technical person, do not build it.

---

## 3. Verified facts about the existing code (reuse these — do not rewrite them)

### 3.1 Usage events and sessions

| Fact | Where |
|---|---|
| `fun interface UsageEventsSource { suspend fun readForegroundEvents(startMs: Long, endMs: Long): UsageEventRead }`, implemented by `UsageStatsRepository` (instance: `AppModule.usageStatsRepository`) | `analytics/ForegroundUsageModels.kt`, `data/repository/UsageStatsRepository.kt` |
| `UsageEventRead` is `Available(events: List<ForegroundUsageEvent>, earliestEventAtMs: Long?)` or `Unknown(reason)` with reasons `PERMISSION_MISSING`, `EVENTS_UNAVAILABLE`, `ACCESS_REVOKED` | same |
| `ForegroundEventType` already contains `SCREEN_INTERACTIVE`, `SCREEN_NON_INTERACTIVE`, `KEYGUARD_SHOWN`, `KEYGUARD_HIDDEN`, `DEVICE_SHUTDOWN`, `DEVICE_STARTUP`, `ACTIVITY_RESUMED/PAUSED/STOPPED`. **So unlocks, wake-ups and screen-on time need no new permission and no new OS API.** | same |
| `ForegroundUsageEvent(type, timestampMs, packageName?, activityClassName?)`; `ForegroundSession(packageName, startedAtMs, endedAtMs, activityClassName, isNewOpen, isInferredTail)` | same |
| `ForegroundSpanTracker(..., excludedPackages = DEFAULT_EXCLUDED_PACKAGES).sessions(events, windowStartMs, windowEndMs, nowMs)` → `List<ForegroundSession>`; closes spans on screen-off / keyguard-shown / shutdown; excludes launchers and System UI. The rollup writer additionally excludes FocusFlow's own package | `analytics/ForegroundSpanTracker.kt`, `analytics/UsageRollupWriter.kt` |
| `UsageCalendarAggregator.aggregate(sessions, rangeStartMs, rangeEndMs, zoneId)` → `List<AppUsageDay>` (per app per local day, `hourlyMs` list of 24). Its hour-splitting `while` loop is the pattern to copy for `HourSplitter` | `analytics/UsageCalendarAggregator.kt` |
| `launchCount` counts only a switch to a *different* package than the previous foreground package, so "app → lock → same app" is not a launch. **For "opens" use sessions (§4.9), not `launchCount`.** | `UsageCalendarAggregator.kt` |
| The OS keeps only a limited event history (roughly a week; varies by device). FocusFlow must store its own history — the existing rollups already do for apps; this plan adds the same for phone behaviour | platform behaviour |

### 3.2 Existing rollups and maintenance

| Fact | Where |
|---|---|
| Tables `usage_rollup_day`, `usage_rollup_app_day` (`foreground_ms`, `hourly_ms` CSV of 24, `launch_count`, `session_count`, `first_start_at_ms`, `last_used_at_ms`, `category`), `usage_rollup_session` (`package_name`, `started_at_ms`, `ended_at_ms`, `duration_ms`, `local_date`) | `data/local/entity/UsageRollup*Entity.kt` |
| `UsageRollupDao`: `getDays(start, end)`, `getAppDays(start, end)`, `getSessions(start, end)`, `getDay(date)` (dates are `YYYY-MM-DD` strings) | `data/local/dao/UsageRollupDao.kt` |
| `UsageRollupWriter.writeRecentPastDays(nowMs)` writes the last 3 **past** days (`ROLLUP_LOOKBACK_DAYS = 3`); never writes today; `writeDaysFromEvents(startDate, endDate, sessions, earliestEventAtMs, nowMs)` writes given past days and skips days already COMPLETE | `analytics/UsageRollupWriter.kt` |
| `UsageRollupCoverage.status(earliestEventAtMs, dayStartMs, unresolvedSessionStartedOnDate)` → `UsageRollupDayEntity.COMPLETE` or `PARTIAL` | `analytics/UsageRollupCoverage.kt` |
| `UsageMaintenanceWorker` runs about every 15 minutes: `writeRecentRollups` → `AppModule.usageRollupWriter.writeRecentPastDays(nowMs)`; daily detection; prune of **90 days** (`UsageMaintenanceJob.PRUNE_RETENTION_DAYS`) | `background/UsageMaintenanceWorker.kt`, `background/UsageMaintenanceJob.kt` |
| The detectors' "pickups" are **estimated** from 2-minute gaps ("there is no separate unlock event stored"). Leave them alone. The new unlock data is *measured* and separate | `analytics/detection/PickupDetectors.kt` |

### 3.3 Blocking and focus data

| Fact | Where |
|---|---|
| `TemptationLogManager.log(context, pkg, appName)` is called from `AppBlockerAccessibilityService.kt` (~line 2704) and appends `{pkg, appName, timestamp}` to a JSON array in SharedPreferences, **capped at 500 entries** (`MAX_ENTRIES`). Read with `GreyoutRepository.getTemptationLog()` (raw JSON string). `parseTemptationLog` in `AnalyticsProcessor.kt` is `private` | `enforcement/TemptationLogManager.kt`, `data/repository/GreyoutRepository.kt` |
| `data class TemptationEntry(pkg: String, appName: String?, timestamp: Long)` is public | `analytics/AnalyticsProcessor.kt` |
| `FocusSessionDao.getSessionsWithOverrideCount(startISO, endISO)` → `List<SessionOverrideCountRow(sessionId, taskId, startedAt, endedAt, overrideCount)>`; timestamps are UTC ISO-8601 strings. "Clean session" = `overrideCount == 0` | `data/local/dao/FocusSessionDao.kt` |

### 3.4 Windows, ViewModel, UI

| Fact | Where |
|---|---|
| `getAnalyticsRange(window, now: ZonedDateTime, weekStartDay, weekMode): AnalyticsRange(start, end: ZonedDateTime)`; constants `ANALYTICS_TODAY`, `ANALYTICS_YESTERDAY`, `ANALYTICS_WEEK`, `ANALYTICS_THREE_MONTHS`, `ANALYTICS_ALL_TIME`; `WEEK_MODE_DYNAMIC`, `WEEK_MODE_FIXED`; `AnalyticsWindow` is a `String` alias. **`ANALYTICS_ALL_TIME` starts at 1970 — always clamp it.** `FIXED` week mode can end in the future — clamp to today | `analytics/AnalyticsProcessor.kt` |
| `StatsScreen` (`ui/stats/StatsScreen.kt`) holds `showExtra: Boolean` and switches between `ArchivedStatsScreen` (the visible **Stats**, window pills Today / Yesterday / Week / All Time) and `StatsInsightsExperience` (titled **Extra**, already holds task cards, findings, day rating). One `StatsViewModel` (`StatsViewModel.Factory`) owns `activeWindow`, `weekViewMode`, `weekStartDay`, `analyticsSnapshot` | `ui/stats/*` |
| `ArchivedStatsScreen` hides `DeviceUsageCard` for All Time (`takeUnless { window == ANALYTICS_ALL_TIME }`) and hard-codes `UsageMetric("0", "Blocked attempts", ...)` (~line 691) — **a defect** this plan fixes | `ui/stats/ArchivedStatsScreen.kt` |
| UI primitives in package `com.tbtechs.focusflow.ui.stats`: `StatsCard`, `StatsHeroCard` (internal), `minutesLabel(Double)` (internal), `hourLabel(Int?)` (**public**, in `PhoneUsageSummary.kt`; another `hourLabel` exists in package `analytics` — import the `ui.stats` one). Theme colours in `ui.theme`: `DarkBackground`, `DarkCard`, `DarkBorder`, `DarkTextPrimary`, `DarkTextSecondary`, `DarkTextMuted`, `BrandPrimary`, `SunAmber`. Stats strings are hard-coded Kotlin literals | `ui/stats/StatsUi.kt`, `ui/stats/PhoneTimeCard.kt` |
| `TrendChart.kt` draws with `Canvas`/`Box` — no chart library exists or is needed | `ui/stats/TrendChart.kt` |

### 3.5 DI, database, tests

| Fact | Where |
|---|---|
| Manual DI: `object AppModule` with `lateinit var … private set` properties, all assigned inside one init function (`usageRollupWriter ≈ line 318`; `analyticsProcessor` just after). Context variable in that function is `app`; coroutine scope is the private `applicationScope` | `di/AppModule.kt` |
| `FocusFlowDatabase` is **version 10**, `exportSchema = true`, schemas in `app/schemas/com.tbtechs.focusflow.data.local.FocusFlowDatabase/` (6–10.json). Manual migrations `MIGRATION_0_1 … MIGRATION_9_10` are registered in `AppModule` via `.addMigrations(...)`. Policy (`app/PERSISTENCE_CONTRACT.md`): AutoMigration is allowed for additive Room-only changes; destructive migration is forbidden | `data/local/FocusFlowDatabase.kt` |
| `minSdk = 29` → SQLite 3.22: **no UPSERT** (`ON CONFLICT … DO UPDATE`). Use `@Insert(onConflict = REPLACE)` and read-modify-write inside `db.withTransaction` | platform |
| Unit tests: JUnit4 + `kotlinx-coroutines-test` + `org.json`; tests live in `app/src/test/java/com/tbtechs/focusflow/...` (existing examples: `analytics/ForegroundSpanTrackerTest.kt`, `analytics/UsageHistoryRepositoryOnDemandTest.kt`). Room migration tests are instrumentation tests (`app/src/androidTest/.../FocusFlowDatabaseMigrationTest.kt`) and are owner-run. Flavors `production` and `tbtechsdev`; test only `production` | `app/build.gradle.kts` |
| The usage rollup tables are not referenced by the backup code. The new tables are likewise **derived data — do not add them to backup** | `data/backup/**` |


---

## 4. Definitions — the contract for every number

Every number shown anywhere must follow these definitions exactly. Put the constants in one place (`PhoneConstants.kt`, §6).

**4.1 Time and dates.** Zone = `ZoneId.systemDefault()` (passed in as a parameter, never cached in a singleton). A *day* is a local
calendar date stored and compared as `YYYY-MM-DD` (`LocalDate.toString()`). Day bounds:
`date.atStartOfDay(zone).toInstant().toEpochMilli()` and the same for `date.plusDays(1)`. **Never add 24 h of milliseconds** —
DST days have 23 or 25 hours. Hour index 0–23 = local clock hour.

**4.2 Events used.** For phone behaviour only these types matter: `SCREEN_INTERACTIVE`, `SCREEN_NON_INTERACTIVE`,
`KEYGUARD_HIDDEN`, `KEYGUARD_SHOWN`, `DEVICE_SHUTDOWN`, `DEVICE_STARTUP`. Activity events are used only through the
`ForegroundSession` list produced by `ForegroundSpanTracker`.

**4.3 Screen-on span.** From `SCREEN_INTERACTIVE` to the next `SCREEN_NON_INTERACTIVE` or `DEVICE_SHUTDOWN`. *Screen-on time*
is the sum of spans clipped to the day. A `KEYGUARD_HIDDEN` while the screen is believed off implies the screen is on: start a
span at that event.

**4.4 Wake-up.** A `SCREEN_INTERACTIVE` that changes the state off → on (a repeated event while the screen is already on is
ignored). It includes wakes caused by notifications — the UI says so.

**4.5 Unlock.** A `KEYGUARD_HIDDEN` that changes the state locked → unlocked (a repeated event while already unlocked is
ignored). It belongs to the day the event happened.

**4.6 Phone session.** One continuous period of using the phone.
- Basis `UNLOCK`: from an unlock to the next screen-off, `KEYGUARD_SHOWN` or shutdown.
- Basis `SCREEN` (fallback when a day has **no** unlock events): each screen-on span.
- A session belongs to the day it **started**; its length is its full length even when it crosses midnight.
- A session still open "now" counts with its length so far, capped at 6 h (`OPEN_SESSION_CAP_MS`).

**4.7 Unlock signal.** A day has the unlock signal iff it has ≥ 1 unlock. A *window* has it if any non-missing day has it.
If a window has ≥ 5 wake-ups and no unlock signal, show wake-ups and the note "This phone doesn't report unlocks (is a screen
lock set?), so unlock counts aren't shown." **Never show "0 unlocks" in that case.**

**4.8 First app after unlock.** For an unlock at time `U`, look at the foreground sessions (launchers, System UI and FocusFlow
are already excluded by the tracker) and take the earliest-starting one with `endedAtMs > U − 2 000` and
`startedAtMs ≤ U + 15 000`. Its package is the *first app*. If none exists the unlock counts as **no app** (checked the time,
the notification shade, the home screen).

**4.9 App open.** One `ForegroundSession` whose length is ≥ 1 000 ms (`MIN_OPEN_MS`). *Opens* = number of such sessions in the
range. **Not** `launchCount`. *Average session* = foreground ms of those sessions ÷ opens. If session rows are unavailable fall
back to `session_count` from `usage_rollup_app_day`.

**4.10 Session length buckets** (the same five for phone sessions and app sessions; lower bound inclusive):

| Index | Label | Range |
|---|---|---|
| 0 | `Under 30s` | < 30 000 ms |
| 1 | `30s–1m` | 30 000 – 59 999 |
| 2 | `1–5m` | 60 000 – 299 999 |
| 3 | `5–20m` | 300 000 – 1 199 999 |
| 4 | `20m+` | ≥ 1 200 000 |

**4.11 Quick check.** A phone session shorter than 1 minute (bucket 0 or 1). *Check share* = quick checks ÷ phone sessions.

**4.12 Gap between unlocks.** Difference between consecutive unlocks on the same day. Gaps longer than 3 h
(`UNLOCK_GAP_CAP_MS`) are ignored (sleep, long idle). Average = Σ gaps ÷ number of gaps; `null` when there are no gaps.

**4.13 Day coverage and averages.** Every date in a range has a coverage:
`COMPLETE` (stored row, status COMPLETE) · `PARTIAL` (stored row, the phone was not observed the whole day) ·
`LIVE` (today, computed from events right now, never stored) · `MISSING` (no row).
- Totals include COMPLETE + PARTIAL + LIVE (all measured data).
- "Per day" averages use **COMPLETE days only**. If there is no COMPLETE day but there is a LIVE day, the average is that day and
  is flagged `TODAY_SO_FAR`. With neither, there is no average.
- Whenever PARTIAL or MISSING days exist, the card shows: "N day(s) with partial or no data aren't averaged."

**4.14 Highest / lowest day.** COMPLETE days only, needs ≥ 3 COMPLETE days; ties → the later date.

**4.15 Periods of the day and weekends.** Morning = hours 5–11, Afternoon = 12–16, Evening = 17–21, Late night = 22–23 and 0–4.
Weekend = Saturday and Sunday (not locale-dependent).

**4.16 Distraction set.** A package is in the distraction set for a range if its category is a distraction category
(Social media, Entertainment, Games) **or** it had ≥ 1 blocked attempt in that range. *Distraction time* = total foreground
time of the set. The UI must show **why** each app is listed (category, "blocked N×", "N opens a day"). The previous-period
comparison uses the category-only set on both sides.

**4.17 Reflex apps.** `activeDays ≥ 3` and `opens ÷ activeDays ≥ 8` and ≥ 50 % of the app's sessions are bucket 0 (under 30 s).
Top 5 by opens per active day.

**4.18 Rising apps ("taking more time than usual").** Average per day in the window ≥ 1.5× the previous period's **and** ≥ 15 min/day
higher; needs `activeDays ≥ 3` in the window and ≥ 4 days with app data in the previous period; previous average ≥ 5 min/day.
Top 3 by absolute increase. (Insights use their own stricter fixed windows — T13.)

**4.19 Focus and blocking metrics.**
- *Focus time* = Σ overlap of every focus session `[startedAt, endedAt ?: now]` with the range.
- *Sessions* = sessions overlapping the range. **Held** = ended session with `overrideCount == 0`. **Interrupted** = ended session
  with `overrideCount > 0`. A still-running session counts toward focus time only (not held / interrupted / buckets).
- Duration buckets (ended sessions, full length): `<15m`, `15–30m`, `30–60m`, `1–2h`, `2h+` (bounds 15, 30, 60, 120 min).
- Blocked attempts come from the durable `blocked_attempt_day` table (T7), **not** from the 500-entry log.
- *Attempts per focus hour* = attempts ÷ focus hours, only when focus time ≥ 30 min.
- *Blocking over time* = eight rolling 7-day blocks ending today (block 0 = today−6…today, block 1 = the 7 days before, …). The trend
  compares the latest two blocks with the two before, only if each side has ≥ 1 h of focus time.
- These numbers describe what happened. Never claim the blocker "caused" anything.

**4.20 Minimum sample sizes** (below these, produce nothing and show the "building history" or "not enough data" text):

| Analysis | Minimum |
|---|---|
| Highest / lowest day (Stats) | 3 COMPLETE days |
| Weekday vs weekend row | ≥ 2 COMPLETE weekdays **and** ≥ 1 COMPLETE weekend day |
| Per-app trend % (Extra) | ≥ 4 days with app data in the window and in the previous period |
| Weekly summary | ≥ 4 COMPLETE days in each of the two weeks |
| Monthly summary | ≥ 20 COMPLETE days in the last 30 and ≥ 10 in the 30 before |
| Unusual day | ≥ 7 COMPLETE baseline days (of the previous 14) |
| App surge / drop, late-night change | ≥ 5 COMPLETE days of the last 7 and ≥ 14 COMPLETE days of the 21 before |
| Unlocks change | unlock signal on ≥ 5 of the last 7 days and ≥ 14 of the 21 before |
| First-app pattern | ≥ 30 unlocks in the last 14 days |
| Checking habit | ≥ 50 phone sessions in the last 14 days, basis `UNLOCK` |
| Time-of-day habit | the app has ≥ 60 min in the last 14 days |
| Consistency | ≥ 10 COMPLETE days in the last 14 |
| Records "highest/lowest/busiest hour" | ≥ 7 COMPLETE days |
| Focus trend | ≥ 1 h of focus time in each compared side |
| Insights screen at all | ≥ 3 COMPLETE days of phone data, otherwise the "building history" state |

**4.21 Wording.** Neutral and factual (templates in Appendix C). "usual", not "normal". No moralizing, no diagnosis, no causal
claims ("coincided with" at most). Every message ≤ 2 sentences (summary cards: ≤ 4 short lines). All durations, percentages, dates and hours go through `PhoneText` (T1).

---

## 5. File map (new files and the only edit points)

```
app/src/main/java/com/tbtechs/focusflow/
├─ analytics/phone/                       (NEW package)
│   ├─ PhoneConstants.kt                  buckets, thresholds, PhonePeriod, UsageCategory            (T1)
│   ├─ PhoneCodec.kt                      CSV encode/decode for hourly arrays                         (T1)
│   ├─ PhoneText.kt                       duration / percent / date / hour text helpers (pure)        (T1)
│   ├─ PhoneModels.kt                     PhoneDay, rows, ranges, enums                               (T1)
│   ├─ PhoneReportModels.kt               report sections + PhoneReport + results                     (T1)
│   ├─ HourSplitter.kt                    DST-safe split of a time span into (date, hour, ms)         (T2)
│   ├─ PhoneEventReducer.kt               events + sessions → PhoneDay (pure)                         (T3)
│   ├─ AppCategories.kt                   AppCategoryMap (pure) + AppCategoryResolver (Android)       (T4)
│   ├─ PhoneBehaviourStore.kt             store interface + RoomPhoneBehaviourStore                   (T6)
│   ├─ PhoneBehaviourRollupWriter.kt      persists past days, backfill                                (T6)
│   ├─ BlockedAttemptSync.kt              TemptationLogParser + log → blocked_attempt_day             (T7)
│   ├─ ScreenTimeBuilder.kt               ScreenTimeSection                                           (T9)
│   ├─ PhoneBehaviourBuilder.kt           PhoneBehaviourSection                                       (T9)
│   ├─ AppsBuilder.kt                     AppsSection                                                 (T10)
│   ├─ DistractionBuilder.kt              DistractionSection                                          (T10)
│   ├─ FocusBlockingBuilder.kt            FocusBlockingSection                                        (T11)
│   ├─ PhoneBehaviourRepository.kt        loads, merges live today, assembles PhoneReport             (T12)
│   └─ insights/
│       ├─ PhoneInsightModels.kt          PhoneInsight, PhoneInsightInput, results                    (T1)
│       ├─ InsightThresholds.kt           every insight constant                                      (T1)
│       ├─ InsightStats.kt                median, mean, stdev, percentChange                          (T13)
│       └─ PhoneInsightEngine.kt          generate(input) → List<PhoneInsight>                        (T13)
├─ data/local/entity/  PhoneDayRollupEntity.kt, PhoneFirstAppDayEntity.kt, BlockedAttemptDayEntity.kt (T5)
├─ data/local/dao/     PhoneBehaviourDao.kt                                                           (T5)
└─ ui/stats/phone/                        (NEW package)
    ├─ StatsDestination.kt                enum StatsDestination, ExtraTab + tab rows                  (T14)
    ├─ PhoneCharts.kt                     HourBars, DayBars, ShareRow, BucketBars                     (T15)
    ├─ PhoneStatsViewModel.kt             UI state + loading                                          (T15)
    ├─ StatsPhoneSection.kt               the new cards on Stats                                      (T16)
    ├─ ExtraAppsTab.kt                                                                                (T17)
    ├─ ExtraPhoneTab.kt, ExtraDistractionTab.kt                                                       (T18)
    ├─ ExtraFocusTab.kt                                                                               (T19)
    └─ PhoneInsightsScreen.kt                                                                         (T20)

app/src/test/java/com/tbtechs/focusflow/analytics/phone/   (tests, one file per class)
app/src/test/java/com/tbtechs/focusflow/ui/stats/phone/    (formatter + ViewModel tests)
app/src/androidTest/java/com/tbtechs/focusflow/data/local/ (one added migration test, owner-run)
```

**The only existing files you may edit** (everything else is read-only):

| Edit point | File | Change | Task |
|---|---|---|---|
| E1 | `data/local/FocusFlowDatabase.kt` | +3 entities, `version = 11`, `autoMigrations`, DAO accessor | T5 |
| E2 | `di/AppModule.kt` | new `lateinit var`s + their construction | T8, T12 |
| E3 | `background/UsageMaintenanceWorker.kt` | two guarded calls inside `writeRecentRollups` | T8 |
| E4 | `ui/stats/StatsScreen.kt` | destination switching + tabs | T14 |
| E5 | `ui/stats/ArchivedStatsScreen.kt` | blocked-attempts fix, one new parameter, one new `item` | T16 |
| E6 | `app/PERSISTENCE_CONTRACT.md`, `.agents/memory/` | documentation | T22 |

---

## 6. Type contracts (copy these exactly; later tasks depend on the names)

All types below are in package `com.tbtechs.focusflow.analytics.phone` unless the file says `.insights`. T1 creates them all so
every later task compiles against the same definitions. Do not rename fields.

### 6.1 `PhoneConstants.kt`

```kotlin
package com.tbtechs.focusflow.analytics.phone

object PhoneBuckets {
    const val COUNT = 5
    /** Exclusive upper bounds of buckets 0..3, in ms. Bucket 4 is open-ended. */
    val BOUNDS_MS: LongArray = longArrayOf(30_000L, 60_000L, 300_000L, 1_200_000L)
    val LABELS: List<String> = listOf("Under 30s", "30s–1m", "1–5m", "5–20m", "20m+")

    fun indexFor(durationMs: Long): Int {
        val d = durationMs.coerceAtLeast(0L)
        for (i in BOUNDS_MS.indices) if (d < BOUNDS_MS[i]) return i
        return COUNT - 1
    }
}

object PhoneThresholds {
    const val BEHAVIOUR_VERSION = 1
    const val FIRST_APP_BEFORE_MS = 2_000L
    const val FIRST_APP_AFTER_MS = 15_000L
    const val UNLOCK_GAP_CAP_MS = 3L * 60 * 60 * 1000
    const val OPEN_SESSION_CAP_MS = 6L * 60 * 60 * 1000
    const val EVENT_LOOKBACK_MS = 6L * 60 * 60 * 1000
    const val MIN_OPEN_MS = 1_000L
    const val QUICK_CHECK_MAX_MS = 60_000L
    const val BACKFILL_DAYS = 14
    const val DAY_ROW_RETENTION_DAYS = 365
    const val REFLEX_MIN_ACTIVE_DAYS = 3
    const val REFLEX_MIN_OPENS_PER_DAY = 8.0
    const val REFLEX_MIN_SHORT_SHARE = 0.5
    const val RISING_MIN_RATIO = 1.5
    const val RISING_MIN_DELTA_MS = 15L * 60 * 1000
    const val RISING_MIN_PREVIOUS_MS = 5L * 60 * 1000
}

enum class PhonePeriod(val label: String) {
    MORNING("Morning"), AFTERNOON("Afternoon"), EVENING("Evening"), LATE_NIGHT("Late night");

    companion object {
        fun of(hour: Int): PhonePeriod = when (hour) {
            in 5..11 -> MORNING
            in 12..16 -> AFTERNOON
            in 17..21 -> EVENING
            else -> LATE_NIGHT
        }
    }
}

enum class UsageCategory(val label: String, val isDistraction: Boolean) {
    SOCIAL("Social media", true),
    COMMUNICATION("Messaging", false),
    ENTERTAINMENT("Entertainment", true),
    GAMES("Games", true),
    EDUCATION("Education", false),
    PRODUCTIVITY("Productivity", false),
    NEWS_READING("News & reading", false),
    TOOLS("Tools & utilities", false),
    OTHER("Other", false),
}
```

### 6.2 `PhoneModels.kt`

```kotlin
package com.tbtechs.focusflow.analytics.phone

import java.time.LocalDate

enum class PhoneDayStatus { COMPLETE, PARTIAL, LIVE }
enum class DayCoverage { COMPLETE, PARTIAL, LIVE, MISSING }
enum class SessionBasis { UNLOCK, SCREEN }
enum class TrendDirection { UP, DOWN, FLAT }

/** One local day of phone behaviour. All hourly lists have exactly 24 entries; bucket lists exactly 5. */
data class PhoneDay(
    val date: String,
    val status: PhoneDayStatus,
    val screenOnMs: Long,
    val screenOnHourlyMs: List<Long>,
    val wakeCount: Int,
    val wakeHourly: List<Int>,
    val unlockCount: Int,
    val unlockHourly: List<Int>,
    val unlockSignal: Boolean,
    val sessionBasis: SessionBasis,
    val sessionCount: Int,
    val sessionBucketCounts: List<Int>,
    val sessionBucketMs: List<Long>,
    val longestSessionMs: Long,
    val firstUnlockAtMs: Long?,
    val unlockGapSumMs: Long,
    val unlockGapCount: Int,
    val firstApps: Map<String, Int>,
    val unlocksWithoutApp: Int,
) {
    companion object {
        fun empty(date: String, status: PhoneDayStatus) = PhoneDay(
            date = date, status = status, screenOnMs = 0L,
            screenOnHourlyMs = List(24) { 0L }, wakeCount = 0, wakeHourly = List(24) { 0 },
            unlockCount = 0, unlockHourly = List(24) { 0 }, unlockSignal = false,
            sessionBasis = SessionBasis.SCREEN, sessionCount = 0,
            sessionBucketCounts = List(PhoneBuckets.COUNT) { 0 },
            sessionBucketMs = List(PhoneBuckets.COUNT) { 0L },
            longestSessionMs = 0L, firstUnlockAtMs = null, unlockGapSumMs = 0L, unlockGapCount = 0,
            firstApps = emptyMap(), unlocksWithoutApp = 0,
        )
    }
}

/** Inclusive date range. */
data class PhoneDateRange(val start: LocalDate, val end: LocalDate) {
    init { require(!end.isBefore(start)) { "end before start" } }
    val dayCount: Int get() = (end.toEpochDay() - start.toEpochDay() + 1).toInt()
    fun dates(): List<LocalDate> = (0 until dayCount).map { start.plusDays(it.toLong()) }
}

data class AppDayRow(
    val date: String, val packageName: String, val appName: String,
    val foregroundMs: Long, val hourlyMs: List<Long>, val sessionCount: Int,
)
data class AppSessionRow(val packageName: String, val date: String, val startedAtMs: Long, val durationMs: Long)
data class FocusSessionRow(val date: String, val startedAtMs: Long, val endedAtMs: Long?, val overrideCount: Int)
data class BlockedDayRow(
    val date: String, val packageName: String, val appName: String,
    val attempts: Int, val hourly: List<Int>,
)
```

### 6.3 `PhoneReportModels.kt`

```kotlin
package com.tbtechs.focusflow.analytics.phone

import java.time.LocalDate

data class DayPoint(
    val date: LocalDate, val coverage: DayCoverage,
    val screenOnMs: Long, val appMs: Long, val unlocks: Int, val wakes: Int,
)
enum class AverageBasis { COMPLETE_DAYS, TODAY_SO_FAR, NONE }
data class DayGroupAverage(val avgMs: Long, val days: Int)

data class ScreenTimeSection(
    val totalScreenOnMs: Long,
    val totalAppMs: Long,
    val averageBasis: AverageBasis,
    val averageDays: Int,
    val avgScreenOnPerDayMs: Long?,
    val avgAppPerDayMs: Long?,
    val weekday: DayGroupAverage?,
    val weekend: DayGroupAverage?,
    val highest: DayPoint?,
    val lowest: DayPoint?,
    val screenOnByHourAvgMs: List<Long>,     // 24
    val peakHour: Int?,
    val days: List<DayPoint>,                // one per date in range, oldest first
)

data class AppUsageRow(
    val packageName: String, val appName: String, val category: UsageCategory,
    val totalMs: Long, val sharePercent: Double,
    val opens: Int, val avgSessionMs: Long, val longestSessionMs: Long,
    val sessionBucketCounts: List<Int>,      // 5
    val periodMs: List<Long>,                // 4, PhonePeriod.entries order
    val dailyMs: List<Long>,                 // aligned with ScreenTimeSection.days
    val activeDays: Int,
    val avgPerDayMs: Long,
    val previousAvgPerDayMs: Long?,
    val changePercent: Int?,
    val firstAfterUnlock: Int,
)
data class CategoryShare(val category: UsageCategory, val totalMs: Long, val sharePercent: Double)
data class AppShare(val packageName: String, val appName: String, val totalMs: Long)
data class PeriodShare(val period: PhonePeriod, val totalMs: Long, val sharePercent: Double, val topApps: List<AppShare>)
data class AppsSection(
    val totalMs: Long,
    val appDataDays: Int,
    val apps: List<AppUsageRow>,             // by totalMs desc, max 30
    val othersMs: Long,                      // time in apps beyond the 30 listed
    val categories: List<CategoryShare>,     // by totalMs desc
    val periods: List<PeriodShare>,          // 4 entries, PhonePeriod order
)

data class FirstAppRow(
    val packageName: String, val appName: String,
    val unlocks: Int, val shareOfUnlocksPercent: Double, val perDay: Double,
)
data class PhoneBehaviourSection(
    val daysWithData: Int,
    val unlockSignalAvailable: Boolean,
    val unlocks: Int, val unlocksPerDay: Double?,
    val wakes: Int, val wakesPerDay: Double?,
    val avgGapBetweenUnlocksMs: Long?,
    val sessionBasis: SessionBasis,
    val sessions: Int,
    val sessionBucketCounts: List<Int>, val sessionBucketMs: List<Long>,
    val quickChecks: Int, val quickCheckSharePercent: Int?,
    val longestSessionMs: Long,
    val unlocksByHour: List<Int>, val wakesByHour: List<Int>,    // sums over non-missing days
    val firstApps: List<FirstAppRow>, val unlocksWithoutApp: Int,
    val firstUnlockMedianMinuteOfDay: Int?,
)

data class DistractionApp(
    val packageName: String, val appName: String, val category: UsageCategory,
    val totalMs: Long, val sharePercent: Double,
    val blockedAttempts: Int, val opens: Int, val reasons: List<String>,
)
data class ReflexApp(val packageName: String, val appName: String, val opensPerActiveDay: Double, val shortSessionSharePercent: Int)
data class RisingApp(val packageName: String, val appName: String, val currentAvgMs: Long, val previousAvgMs: Long, val changePercent: Int)
data class DistractionSection(
    val distractionMs: Long, val sharePercent: Int?,
    val apps: List<DistractionApp>,          // max 8, by totalMs desc
    val byHourMs: List<Long>,                // 24
    val peakHour: Int?,
    val reflexApps: List<ReflexApp>,         // max 5
    val risingApps: List<RisingApp>,         // max 3
    val changePercent: Int?,                 // category-only distraction avg/day vs previous period
)

data class BlockedAppRow(val packageName: String, val appName: String, val attempts: Int, val sharePercent: Double)
data class FocusWeekPoint(val blockStart: LocalDate, val focusMs: Long, val endedSessions: Int, val heldSessions: Int, val attempts: Int)
data class FocusTrend(
    val direction: TrendDirection,
    val attemptsPerFocusHourNow: Double, val attemptsPerFocusHourBefore: Double, val changePercent: Int,
)
data class FocusBlockingSection(
    val focusMs: Long, val sessions: Int, val heldSessions: Int, val interruptedSessions: Int,
    val durationBucketCounts: List<Int>,     // 5: <15m, 15–30, 30–60, 1–2h, 2h+
    val blockedAttempts: Int, val blockedApps: List<BlockedAppRow>,   // top 8
    val blockedByHour: List<Int>, val blockedPeakHour: Int?,
    val attemptsPerFocusHour: Double?,
    val weeks: List<FocusWeekPoint>,         // up to 8, oldest first
    val trend: FocusTrend?,
)

data class PhoneCoverage(
    val completeDays: Int, val partialDays: Int, val liveDays: Int, val missingDays: Int,
    val phoneDataSince: LocalDate?, val appDataSince: LocalDate?,
)
data class PhoneReport(
    val window: String, val range: PhoneDateRange,
    val screenTime: ScreenTimeSection, val apps: AppsSection, val behaviour: PhoneBehaviourSection,
    val distraction: DistractionSection, val focus: FocusBlockingSection, val coverage: PhoneCoverage,
)
sealed interface PhoneReportResult {
    object PermissionNeeded : PhoneReportResult
    object NoData : PhoneReportResult
    data class Ready(val report: PhoneReport) : PhoneReportResult
    data class Failed(val message: String) : PhoneReportResult
}
```

### 6.4 `insights/PhoneInsightModels.kt` (package `…analytics.phone.insights`)

```kotlin
package com.tbtechs.focusflow.analytics.phone.insights

import com.tbtechs.focusflow.analytics.phone.*
import java.time.LocalDate
import java.time.ZoneId

enum class InsightKind {
    WEEKLY_SUMMARY, MONTHLY_SUMMARY, UNUSUAL_DAY, APP_SURGE, APP_DROP, UNLOCKS_CHANGE, LATE_NIGHT_SHIFT,
    FIRST_APP_PATTERN, CHECKING_HABIT, TIME_OF_DAY_HABIT, CONSISTENCY, RECORD, FOCUS_TREND, DATA_NOTE,
}
enum class InsightGroup(val title: String) {
    SUMMARY("Summary"), CHANGE("Unusual changes"), PATTERN("Patterns"), RECORD("Personal records"), NOTE("About this data"),
}
enum class InsightConfidence { MEDIUM, HIGH }

data class PhoneInsight(
    val id: String,                       // stable, e.g. "weekly:2026-11-05", "surge:com.instagram.android"
    val kind: InsightKind, val group: InsightGroup,
    val title: String, val message: String,
    val evidence: List<String>,           // one fact per line: "What: …", "How: …", "Period: …", "Evidence: …"
    val direction: TrendDirection?,
    val confidence: InsightConfidence,
    val priority: Int,
    val isNew: Boolean = false,
)

data class PhoneInsightInput(
    val today: LocalDate,
    val days: List<PhoneDay>,             // up to 90 days, any order, LIVE today included
    val appDays: List<AppDayRow>,
    val appSessions: List<AppSessionRow>,
    val blockedDays: List<BlockedDayRow>,
    val focusSessions: List<FocusSessionRow>,
    val appNames: Map<String, String>,
    val categoryOf: (String) -> UsageCategory,
    val zone: ZoneId,
)

/** What the repository returns for the Insights screen (the engine has not run yet). */
sealed interface PhoneInsightInputResult {
    object PermissionNeeded : PhoneInsightInputResult
    data class Building(val completeDays: Int) : PhoneInsightInputResult
    data class Ready(val input: PhoneInsightInput) : PhoneInsightInputResult
    data class Failed(val message: String) : PhoneInsightInputResult
}

/** What the ViewModel exposes to the Insights screen (the engine has run). */
sealed interface PhoneInsightsResult {
    object PermissionNeeded : PhoneInsightsResult
    data class Building(val completeDays: Int) : PhoneInsightsResult
    data class Ready(val insights: List<PhoneInsight>, val completeDays: Int) : PhoneInsightsResult
    data class Failed(val message: String) : PhoneInsightsResult
}
```

`InsightThresholds.kt` holds an `object InsightThresholds` with one `const val` per number in §4.20 and §9-T13 (names such as
`UNUSUAL_DAY_MIN_BASELINE_DAYS = 7`, `UNUSUAL_DAY_RATIO = 1.4`, `UNUSUAL_DAY_MIN_DELTA_MS = 45L * 60 * 1000`). **Define each constant
the first time a rule in T13 mentions a number; do not scatter literals in the engine.**

---

## 7. Storage spec (tables, database bump, converters)

Three **new derived tables**. They are not part of the backup and are rebuilt from OS events / the temptation log, exactly like
the existing `usage_rollup_*` tables. No existing table changes.

### 7.1 `phone_day_rollup` — one row per past local day (`PhoneDayRollupEntity`)

| Column | Kotlin type | SQL | Meaning |
|---|---|---|---|
| `date` (PK) | `String` | `TEXT NOT NULL` | `YYYY-MM-DD` |
| `status` | `String` | `TEXT NOT NULL` | `"COMPLETE"` or `"PARTIAL"` (companion constants) |
| `behaviour_version` | `Int` | `INTEGER NOT NULL` | `PhoneThresholds.BEHAVIOUR_VERSION` |
| `computed_at_ms` | `Long` | `INTEGER NOT NULL` | when the row was written |
| `coverage_start_ms` | `Long?` | `INTEGER` | earliest event time seen when computing |
| `screen_on_ms` | `Long` | `INTEGER NOT NULL` | |
| `screen_on_hourly_ms` | `String` | `TEXT NOT NULL` | 24 CSV longs |
| `wake_count` | `Int` | `INTEGER NOT NULL` | |
| `wake_hourly` | `String` | `TEXT NOT NULL` | 24 CSV ints |
| `unlock_count` | `Int` | `INTEGER NOT NULL` | |
| `unlock_hourly` | `String` | `TEXT NOT NULL` | 24 CSV ints |
| `unlock_signal` | `Boolean` | `INTEGER NOT NULL` | `unlock_count > 0` |
| `session_count` | `Int` | `INTEGER NOT NULL` | phone sessions (basis = `UNLOCK` iff `unlock_signal`) |
| `session_bucket_counts` | `String` | `TEXT NOT NULL` | 5 CSV ints |
| `session_bucket_ms` | `String` | `TEXT NOT NULL` | 5 CSV longs |
| `longest_session_ms` | `Long` | `INTEGER NOT NULL` | |
| `first_unlock_at_ms` | `Long?` | `INTEGER` | first unlock, else first screen-on, of the day |
| `unlock_gap_sum_ms` | `Long` | `INTEGER NOT NULL` | |
| `unlock_gap_count` | `Int` | `INTEGER NOT NULL` | |
| `unlocks_without_app` | `Int` | `INTEGER NOT NULL` | |

### 7.2 `phone_first_app_day` (`PhoneFirstAppDayEntity`)

`date TEXT NOT NULL`, `package_name TEXT NOT NULL`, `app_name TEXT NOT NULL`, `unlock_count INTEGER NOT NULL`,
`PRIMARY KEY(date, package_name)`. One row per package that was the first app after ≥ 1 unlock that day.

### 7.3 `blocked_attempt_day` (`BlockedAttemptDayEntity`)

`date TEXT NOT NULL`, `package_name TEXT NOT NULL`, `app_name TEXT NOT NULL`, `attempts INTEGER NOT NULL`,
`hourly_attempts TEXT NOT NULL` (24 CSV ints), `PRIMARY KEY(date, package_name)`. Written for past days **and today**
(it is a counter, not a coverage-based rollup).

### 7.4 Rules for all three entities

- Use `@Entity(tableName = …, primaryKeys = […])` and `@ColumnInfo(name = …)` on every field, exactly like
  `UsageRollupSessionEntity`.
- **No `defaultValue`, no SQL defaults, no extra indices** (the composite primary keys already serve `date BETWEEN` queries).
- Every value is supplied by code; hourly/bucket lists are encoded with `PhoneCodec` (T1).
- `PhoneDayRollupEntity` has `companion object { const val COMPLETE = "COMPLETE"; const val PARTIAL = "PARTIAL" }`.
- Converters live next to the store (T6): `PhoneDayRollupEntity.toPhoneDay(firstApps: Map<String, Int>): PhoneDay` and
  `PhoneDay.toEntity(computedAtMs, coverageStartMs): PhoneDayRollupEntity`.

### 7.5 Database change (edit point E1)

In `FocusFlowDatabase.kt`: add the three entity classes to `entities = [...]`; set `version = 11`; add
`autoMigrations = [AutoMigration(from = 10, to = 11)]` to the `@Database` annotation (import `androidx.room.AutoMigration`);
add `abstract fun phoneBehaviourDao(): PhoneBehaviourDao`. Schema 10.json already exists. After a successful compile,
`app/schemas/com.tbtechs.focusflow.data.local.FocusFlowDatabase/11.json` is generated — it **must be kept** (commit it) and must contain the
three tables. Never hand-write it. If AutoMigration generation fails, use the manual fallback in **Appendix A** instead
(register `FocusFlowDatabase.MIGRATION_10_11` in `AppModule`'s `.addMigrations(...)` and drop the `autoMigrations` argument).

### 7.6 Retention

`phone_day_rollup`, `phone_first_app_day`, `blocked_attempt_day`: keep 365 days (`DAY_ROW_RETENTION_DAYS`); the writer prunes older
rows at the end of each pass. (The existing 90-day prune of `usage_rollup_*` is unchanged — app-level history stays 90 days.)

---

## 8. Task order and dependencies

| Task | Title | Needs | Gate |
|---|---|---|---|
| T0 | Preflight: environment, baseline, verify facts | — | |
| T1 | Constants, codec, all model files | T0 | |
| T2 | `HourSplitter` | T1 | |
| T3 | `PhoneEventReducer` | T1, T2 | |
| T4 | App categories | T1 | |
| T5 | Entities, DAO, database v11 | T1 | |
| T6 | Store + rollup writer (+ backfill) | T3, T5 | |
| T7 | Blocked-attempt sync | T5, T6 | |
| T8 | Wiring: AppModule + maintenance worker | T4, T6, T7 | |
| T9 | Screen-time + phone-behaviour builders | T1 | |
| T10 | Apps + distraction builders | T1, T4 | |
| T11 | Focus & blocking builder | T1 | |
| T12 | `PhoneBehaviourRepository` | T6–T11 | **A** |
| T13 | Insight engine | T1, T4 | |
| T14 | Destinations + `StatsScreen` wiring | T0 | |
| T15 | ViewModel, formatting, chart primitives | T12 | |
| T16 | Stats cards (+ blocked-attempts fix) | T14, T15 | **B** |
| T17 | Extra · Apps tab | T14, T15 | |
| T18 | Extra · Phone + Distraction tabs | T14, T15 | |
| T19 | Extra · Focus tab | T14, T15 | **C** |
| T20 | Insights screen | T12, T13, T14, T15 | |
| T21 | Hardening, edge cases, accessibility | T16–T20 | **D** |
| T22 | Documentation + persistence contract | T21 | |
| T23 | Owner device checks | T22 | owner-run |

Priorities: T0–T12 and T14–T16 are **must-have** (they deliver Stats). T17–T20 deliver Extra and Insights. T21–T22 close the plan.
If you run short of time, stop at a Gate — the app is always shippable at A, B, C and D.

---

## 9. Tasks

Every task has: **Needs** · **Goal** · **Files** · **Spec** · **Steps** · **Tests** · **Done when** · **Do NOT**.
After every task: compile check (§0 rule 8) → focused tests → update §13.

---

### T0 — Preflight: environment, baseline, verify facts

**Needs:** nothing. **Goal:** prove the toolchain works, record the baseline, confirm §3 is still true.

**Steps**
1. Read these repo notes (they hold hard-won rules): `.agents/memory/MEMORY.md`, then `android-build-environment.md`,
   `coroutine-test-dispatchers.md`, `kotlin-compose-scope-audits.md`, `gradle-test-filter-verification.md`,
   `patch-application-failures.md`, `rolling-stats-dates.md`.
2. The repo's root `plan.md` may be an older finished plan. Do not edit it; this file is the current plan.
3. Run the full production unit suite once (§0 rule 8). From `app/build/test-results/testProductionDebugUnitTest/TEST-*.xml`
   record in §13: duration, suites, tests, failures, errors, skipped. This is the **baseline**; every later full run must keep
   failures = 0 and tests ≥ baseline.
4. Run these checks and record `OK` or the exact discrepancy for each:
```
grep -rn "fun interface UsageEventsSource" app/src/main/java
grep -rn "KEYGUARD_HIDDEN" app/src/main/java | head -5
grep -rn "ROLLUP_LOOKBACK_DAYS" app/src/main/java | head -3
grep -rn "fun writeDaysFromEvents" app/src/main/java
grep -rn "object UsageRollupCoverage" app/src/main/java
grep -rn "fun getAnalyticsRange" app/src/main/java
grep -n  "MAX_ENTRIES" app/src/main/java/com/tbtechs/focusflow/enforcement/TemptationLogManager.kt
grep -n  "version = 10" app/src/main/java/com/tbtechs/focusflow/data/local/FocusFlowDatabase.kt
ls app/schemas/com.tbtechs.focusflow.data.local.FocusFlowDatabase/
grep -n  "Blocked attempts" app/src/main/java/com/tbtechs/focusflow/ui/stats/ArchivedStatsScreen.kt
grep -n  "greyoutRepository =\|usageRollupWriter = \|usageRollupCoordinator = " app/src/main/java/com/tbtechs/focusflow/di/AppModule.kt
grep -rn "abstract fun focusSessionDao\|abstract fun usageRollupDao" app/src/main/java
grep -rn "import androidx.room.withTransaction" app/src/main/java | head -2
```
5. In the `AppModule.kt` result, note whether `greyoutRepository =` is assigned on a line **above** `usageRollupCoordinator =`.
   If not, T8 must insert its code after both lines — record this.

**Done when:** baseline and the 13 check results are in §13. **Do NOT** change any code in T0.

---

### T1 — Constants, codec and all model files

**Needs:** T0. **Goal:** every shared type exists and compiles, so later tasks cannot drift.

**Files (new):** `analytics/phone/PhoneConstants.kt`, `PhoneCodec.kt`, `PhoneText.kt`, `PhoneModels.kt`, `PhoneReportModels.kt`,
`analytics/phone/insights/PhoneInsightModels.kt`, `analytics/phone/insights/InsightThresholds.kt`.
Tests: `analytics/phone/PhoneCodecTest.kt`, `PhoneBucketsTest.kt`, `PhonePeriodTest.kt`, `PhoneTextTest.kt`.

**Spec**
- Copy §6.1–§6.4 **verbatim**. (If the repository's Kotlin version rejects something, fix minimally and note it in §13.)
- `InsightThresholds.kt`: `object InsightThresholds` with a KDoc line "Constants are added in T13." and no members yet.
- `PhoneCodec`:
```kotlin
object PhoneCodec {
    fun encodeLongs(values: List<Long>): String = values.joinToString(",")
    fun encodeInts(values: List<Int>): String = values.joinToString(",")
    /** Splits on ','; a blank/null text or a bad token yields 0; the result is padded/truncated to exactly [size]. */
    fun decodeLongs(text: String?, size: Int): List<Long>
    fun decodeInts(text: String?, size: Int): List<Int>
}
```

- `PhoneText` (pure, English, `Locale.ENGLISH`; the single place that formats numbers for people):
```kotlin
object PhoneText {
    /** <60 s → "45s"; <1 h → "37m" (rounded to the nearest minute); else "4h 12m", or "4h" when the minutes are 0. */
    fun duration(ms: Long): String
    fun percent(value: Int): String                 // "35%"
    fun signedPercent(value: Int): String           // "+35%", "−12%" (U+2212), "0%"
    fun weekdayDate(date: java.time.LocalDate): String   // "Sat, Oct 18"
    fun hour(hour: Int): String                     // 0 → "12 AM", 9 → "9 AM", 12 → "12 PM", 21 → "9 PM"
    fun clock(minuteOfDay: Int): String             // 495 → "8:15 AM"
    fun count(n: Int, singular: String, plural: String = singular + "s"): String   // "1 unlock", "63 unlocks"
}
```

**Tests**
- `PhoneTextTest`: `duration` at 0, 999, 59_999 ("59s"), 60_000 ("1m"), 3_599_999 ("1h"), 3_600_000 ("1h"), 4_320_000 ("1h 12m"), 15_120_000 ("4h 12m");
  `signedPercent(35) == "+35%"`, `(-12) == "−12%"`, `(0) == "0%"`; `hour(0) == "12 AM"`, `hour(12) == "12 PM"`, `hour(23) == "11 PM"`; `clock(495) == "8:15 AM"`, `clock(0) == "12:00 AM"`; `count(1, "unlock") == "1 unlock"`.
- `PhoneCodecTest`: round-trip of 24 longs; `null` and `""` → all zeros; short input padded with zeros; long input truncated;
  a garbage token (`"1,x,3"`) → `[1,0,3,…]`; negative numbers survive.
- `PhoneBucketsTest`: `indexFor` at 0, 29_999 → 0; 30_000, 59_999 → 1; 60_000, 299_999 → 2; 300_000, 1_199_999 → 3;
  1_200_000, 9_999_999 → 4; negative → 0. `LABELS.size == COUNT`.
- `PhonePeriodTest`: hours 0–23 map exactly per §4.15 (5–11 morning, 12–16 afternoon, 17–21 evening, 22–23 and 0–4 late night).

**Done when:** compile OK; the three test classes appear in the XML and pass. **Do NOT** add logic beyond the above.

---

### T2 — `HourSplitter`

**Needs:** T1. **Goal:** split a time span into per-(date, hour) milliseconds, correctly across hour boundaries, midnight, DST and half-hour time zones.

**File (new):** `analytics/phone/HourSplitter.kt`. Test: `HourSplitterTest.kt`.

```kotlin
data class HourSlice(val date: java.time.LocalDate, val hour: Int, val ms: Long)

object HourSplitter {
    fun split(startMs: Long, endMs: Long, zone: java.time.ZoneId): List<HourSlice>
}
```

**Spec** — same loop as `UsageCalendarAggregator`:
```
if (endMs <= startMs) return emptyList()
cursor = startMs
while (cursor < endMs):
    zoned   = Instant.ofEpochMilli(cursor).atZone(zone)
    nextHr  = zoned.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
    segEnd  = min(endMs, nextHr)
    if (segEnd <= cursor) break
    add HourSlice(zoned.toLocalDate(), zoned.hour, segEnd - cursor)
    cursor = segEnd
```
**Tests** (use fixed epoch values built with `ZonedDateTime.of(...)`):
single hour; crosses one hour boundary (two slices, sums equal); crosses midnight (dates differ); zero and negative length → empty;
`Asia/Kolkata` (UTC+05:30): a span 10:15–11:15 local splits 45 min (hour 10) + 15 min (hour 11) at the *local* boundary;
`America/New_York` spring-forward 2026-03-08: span 00:00–06:00 local → total ms = 5 h and **no slice has hour 2**;
fall-back 2026-11-01: span 00:00–06:00 local → total 7 h and the two `01:xx` hours both land in `hour == 1` (sum 2 h).
For every test assert `slices.sumOf { it.ms } == endMs - startMs`.

**Done when:** tests pass. **Do NOT** use `plusDays(1)` of epoch millis or any fixed 3 600 000 stepping from `startMs`.

---

### T3 — `PhoneEventReducer` (the core algorithm)

**Needs:** T1, T2. **Goal:** turn OS events + foreground sessions into one `PhoneDay`. Pure Kotlin, no Android, no clock.

**File (new):** `analytics/phone/PhoneEventReducer.kt`. Test: `PhoneEventReducerTest.kt`.

```kotlin
class PhoneEventReducer(private val zone: ZoneId) {
    fun reduceDay(
        date: LocalDate,
        events: List<ForegroundUsageEvent>,          // may cover several days; need not be sorted
        foregroundSessions: List<ForegroundSession>, // from ForegroundSpanTracker for the same events
        nowMs: Long,
        status: PhoneDayStatus,
    ): PhoneDay
}
```

**Algorithm — implement exactly, in this order**

*Setup*
1. `dayStart`, `dayEnd` per §4.1; `limit = min(dayEnd, nowMs)`. If `limit <= dayStart` return `PhoneDay.empty(date.toString(), status)`.
2. `ev` = events whose type is one of the six in §4.2 and `timestampMs <= nowMs`, **sorted by `timestampMs`** (stable sort).
3. State variables: `screenOn=false`, `screenOnSince=0`, `screenStartedBeforeDay=false`, `unlocked=false`, `unlockedSince=0`,
   `unlockCounted=false`. Accumulators: `screenOnMs`, `screenOnHourly[24]`, `wakeCount`, `wakeHourly[24]`, `unlockCount`,
   `unlockHourly[24]`, `unlockTimes`, `closedUnlockedSessions` (list of start,end), `screenSpans` (list of start,end for spans that
   **started** inside the day).

*Initial state* — replay events with `timestampMs < dayStart` **without counting anything**:
`SCREEN_INTERACTIVE` → `screenOn=true`; `SCREEN_NON_INTERACTIVE`, `DEVICE_SHUTDOWN`, `DEVICE_STARTUP` → `screenOn=false, unlocked=false`;
`KEYGUARD_HIDDEN` → `unlocked=true, screenOn=true`; `KEYGUARD_SHOWN` → `unlocked=false`.
Afterwards: if `!screenOn` then `unlocked=false`. If `screenOn` → `screenOnSince=dayStart`, `screenStartedBeforeDay=true`.
If `unlocked` → `unlockedSince=dayStart`, `unlockCounted=false` (it began on an earlier day, so it is not counted here).

*Main loop* — events with `dayStart <= ts < limit`, in order:
- `SCREEN_INTERACTIVE`: if `!screenOn` → `screenOn=true; screenOnSince=ts; screenStartedBeforeDay=false; wakeCount++; wakeHourly[hour(ts)]++`.
- `SCREEN_NON_INTERACTIVE` or `DEVICE_SHUTDOWN`: `closeScreen(ts)`; `closeUnlocked(ts)`.
- `KEYGUARD_HIDDEN`: if `!screenOn` → `screenOn=true; screenOnSince=ts; screenStartedBeforeDay=false` (a hidden keyguard implies the screen is on).
  If `!unlocked` → `unlocked=true; unlockedSince=ts; unlockCounted=true; unlockCount++; unlockHourly[hour(ts)]++; unlockTimes += ts`.
- `KEYGUARD_SHOWN`: `closeUnlocked(ts)`.
- `DEVICE_STARTUP`: `screenOn=false; unlocked=false`.

*Helpers*
- `closeScreen(end)`: if `screenOn` → `addSpan(screenOnSince, end, startedInDay = !screenStartedBeforeDay)`; `screenOn=false`.
- `closeUnlocked(end)`: if `unlocked` → if `unlockCounted` add `(unlockedSince, end)` to `closedUnlockedSessions`; `unlocked=false; unlockCounted=false`.
- `addSpan(start, end, startedInDay)`: `s=max(start,dayStart)`, `e=min(end,limit)`; if `e>s`: `screenOnMs += e-s` and for every
  `HourSlice` of `HourSplitter.split(s, e, zone)` with `slice.date == date` add `slice.ms` to `screenOnHourly[slice.hour]`.
  If `startedInDay` add `(start, end)` (full, unclipped end) to `screenSpans`.

*After the loop (state at `limit`)*
4. If `screenOn` is still true: account its time up to `limit` (`addSpan(screenOnSince, limit, …)` for time only — do **not** add it to
   `screenSpans` yet) and remember it as the *open screen span*.
5. Resolve ends of open things: scan `ev` for events with `limit <= ts <= nowMs`:
   - `openUnlockedEnd` = ts of the first `SCREEN_NON_INTERACTIVE`, `DEVICE_SHUTDOWN` or `KEYGUARD_SHOWN`; if none, `min(nowMs, unlockedSince + OPEN_SESSION_CAP_MS)`.
   - `openScreenEnd` = ts of the first `SCREEN_NON_INTERACTIVE` or `DEVICE_SHUTDOWN`; if none, `min(nowMs, screenOnSince + OPEN_SESSION_CAP_MS)`.
   If an open unlocked session exists and `unlockCounted`, add `(unlockedSince, openUnlockedEnd)` to `closedUnlockedSessions`.
   If an open screen span exists and it started in the day, add `(screenOnSince, openScreenEnd)` to `screenSpans`.
6. **Sessions:** if `unlockCount > 0` → basis `UNLOCK`, sessions = `closedUnlockedSessions`; else basis `SCREEN`, sessions = `screenSpans`.
   For each session `len = max(0, end - start)`: `sessionCount++`, `bucketCounts[PhoneBuckets.indexFor(len)]++`,
   `bucketMs[…] += len`, `longest = max(longest, len)`.
7. **Gaps:** sort `unlockTimes`; for consecutive pairs `gap = t[i]-t[i-1]`; if `gap <= UNLOCK_GAP_CAP_MS` add to `unlockGapSumMs` and `unlockGapCount++`.
8. **First apps:** for each unlock time `U` choose, among `foregroundSessions`, those with `endedAtMs > U - FIRST_APP_BEFORE_MS` **and**
   `startedAtMs <= U + FIRST_APP_AFTER_MS`; take the one with the smallest `startedAtMs`. If found `firstApps[pkg]++`, else `unlocksWithoutApp++`.
9. `firstUnlockAtMs` = first unlock time, else the start of the first span in `screenSpans` (earliest), else `null`.
10. Return `PhoneDay(date.toString(), status, screenOnMs, hourly…, unlockSignal = unlockCount > 0, sessionBasis, …)`.

**Tests** (`ZoneId.of("UTC")` unless stated; build events with a small helper `ev(type, hour, minute, second)`; every test asserts the fields it names):
1. Simple day: interactive 08:00:00 → keyguard hidden 08:00:03 → non-interactive 08:05:03 ⇒ wake 1, unlock 1, screenOn 303 000 ms, `screenOnHourly[8] == 303000`, 1 session of 300 000 ms in bucket 3, `unlockSignal`, basis `UNLOCK`.
2. A second `SCREEN_INTERACTIVE` while on, and a second `KEYGUARD_HIDDEN` while unlocked, change nothing.
3. Span 10:55–11:10 splits hourly (5 min hour 10, 10 min hour 11).
4. Screen on across midnight: events 23:50 on, 00:20 off. `reduceDay(day1)` → 10 min in hour 23; `reduceDay(day2)` → 20 min in hour 0 and `wakeCount == 0`.
5. Unlock 23:50, off 00:20: day 1 has 1 session of 30 min (bucket 4), day 2 has `sessionCount == 0` and `unlockCount == 0`.
6. Live: unlock 10:00, `nowMs` 10:07, status `LIVE` ⇒ 1 session of 7 min; screenOn 7 min; no events after now are used.
7. No keyguard events at all: `unlockSignal=false`, basis `SCREEN`, `sessionCount` = number of screen spans, `unlockCount == 0`.
8. `KEYGUARD_SHOWN` after 40 s, screen off at 100 s ⇒ session 40 s (bucket 1), screenOn 100 s.
9. Re-lock then screen wakes without an unlock ⇒ wake counted, unlock not counted.
10. Unlocks 08:00, 09:00, 14:00 ⇒ `unlockGapSumMs == 3 600 000`, `unlockGapCount == 1` (the 5 h gap is ignored).
11. First app: sessions starting U+3 s and U+8 s ⇒ the earlier wins; `firstApps == {pkgA:1}`.
12. Only a session starting U+20 s ⇒ `unlocksWithoutApp == 1`, `firstApps` empty.
13. A session that started U−1 s and is still running at U counts as the first app.
14. `DEVICE_SHUTDOWN` closes the screen span and the unlocked session.
15. `KEYGUARD_HIDDEN` while the screen was off starts a screen span at that moment.
16. DST (`America/New_York`, 2026-03-08): screen on 00:00–06:00 local ⇒ `sum(screenOnHourly) == screenOnMs == 5 h` and `screenOnHourly[2] == 0`.
17. Unsorted input gives the same result as sorted input. 18. Events after `nowMs` are ignored. 19. Empty events ⇒ all-zero day.
20. No unlocks: `firstUnlockAtMs` equals the first screen-on time. 21. Lists always have sizes 24/24/24/5/5.

**Done when:** all tests pass. **Do NOT** read the clock, touch Android classes, or log.

---

### T4 — App categories

**Needs:** T1. **Goal:** map a package to a `UsageCategory` (the user's six categories plus messaging, news, tools).

**File (new):** `analytics/phone/AppCategories.kt`. Test: `AppCategoryMapTest.kt`. **Leave `UsageAppMetadata` unchanged.**

```kotlin
object AppCategoryMap {
    fun categorize(packageName: String, apiCategory: Int?, isGameFlag: Boolean): UsageCategory
}

class AppCategoryResolver(private val context: Context) {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, UsageCategory>()
    fun resolve(packageName: String): UsageCategory   // PackageManager lookup, cached per process
}
```
**Spec — precedence (first match wins):**
1. Package override table (Appendix B: exact names and prefixes).
2. `ApplicationInfo.category` (`apiCategory`): `CATEGORY_SOCIAL`→SOCIAL; `CATEGORY_VIDEO`, `CATEGORY_AUDIO`→ENTERTAINMENT; `CATEGORY_GAME`→GAMES;
   `CATEGORY_PRODUCTIVITY`→PRODUCTIVITY; `CATEGORY_NEWS`→NEWS_READING; `CATEGORY_MAPS`, `CATEGORY_IMAGE`→TOOLS; anything else falls through.
3. `isGameFlag` (`FLAG_IS_GAME`, deprecated but present — add `@Suppress("DEPRECATION")`) → GAMES.
4. Keyword fallback on the lower-cased package split by `.` and `_` into segments: a segment equal to (keyword shorter than 5 letters) or
   starting with (keyword of 5+ letters) one of: GAMES `game, games, casino, ludo, puzzle, racing`; EDUCATION `learn, edu, study, course, school, tutor`;
   SOCIAL `social`; COMMUNICATION `messenger, chat, sms`; ENTERTAINMENT `music, video, player, tv, stream, movie`;
   NEWS_READING `news, reader, magazine`; TOOLS `calculator, clock, camera, gallery, files, browser, vpn, wallet`. Evaluate the groups in the order listed.
5. Otherwise `OTHER`.
`resolve`: `getApplicationInfo(pkg, 0)`; on `NameNotFoundException` use `categorize(pkg, null, false)`. `info.category` is API 26+ (minSdk 29: no version check needed).

**Tests:** override beats API category (`com.instagram.android` with a `CATEGORY_VIDEO` input → SOCIAL); each API category maps as above; `isGameFlag` → GAMES;
keyword fallback hits (`com.example.mathgame` → GAMES, `com.foo.learnenglish` → EDUCATION) and a near-miss does not (`com.stvision.app` must not match `tv`);
unknown package → OTHER; `UsageCategory.isDistraction` is true only for SOCIAL, ENTERTAINMENT, GAMES.

**Done when:** tests pass. **Do NOT** call `PackageManager` from `AppCategoryMap` (it must stay pure).

---

### T5 — Entities, DAO, database v11

**Needs:** T1. **Goal:** the three tables exist in Room; schema 11 is exported; existing data survives.

**Files (new):** `data/local/entity/PhoneDayRollupEntity.kt`, `PhoneFirstAppDayEntity.kt`, `BlockedAttemptDayEntity.kt`, `data/local/dao/PhoneBehaviourDao.kt`.
**Edit:** E1 (`FocusFlowDatabase.kt`). **Android test (new method):** in `FocusFlowDatabaseMigrationTest.kt`.

**Before you start:** read `app/PERSISTENCE_CONTRACT.md` (37 lines) and §7 of this plan.

```kotlin
// PhoneDayRollupEntity.kt — package com.tbtechs.focusflow.data.local.entity
@Entity(tableName = "phone_day_rollup", primaryKeys = ["date"])
data class PhoneDayRollupEntity(
    @ColumnInfo(name = "date") val date: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "behaviour_version") val behaviourVersion: Int,
    @ColumnInfo(name = "computed_at_ms") val computedAtMs: Long,
    @ColumnInfo(name = "coverage_start_ms") val coverageStartMs: Long?,
    @ColumnInfo(name = "screen_on_ms") val screenOnMs: Long,
    @ColumnInfo(name = "screen_on_hourly_ms") val screenOnHourlyMs: String,
    @ColumnInfo(name = "wake_count") val wakeCount: Int,
    @ColumnInfo(name = "wake_hourly") val wakeHourly: String,
    @ColumnInfo(name = "unlock_count") val unlockCount: Int,
    @ColumnInfo(name = "unlock_hourly") val unlockHourly: String,
    @ColumnInfo(name = "unlock_signal") val unlockSignal: Boolean,
    @ColumnInfo(name = "session_count") val sessionCount: Int,
    @ColumnInfo(name = "session_bucket_counts") val sessionBucketCounts: String,
    @ColumnInfo(name = "session_bucket_ms") val sessionBucketMs: String,
    @ColumnInfo(name = "longest_session_ms") val longestSessionMs: Long,
    @ColumnInfo(name = "first_unlock_at_ms") val firstUnlockAtMs: Long?,
    @ColumnInfo(name = "unlock_gap_sum_ms") val unlockGapSumMs: Long,
    @ColumnInfo(name = "unlock_gap_count") val unlockGapCount: Int,
    @ColumnInfo(name = "unlocks_without_app") val unlocksWithoutApp: Int,
) {
    companion object { const val COMPLETE = "COMPLETE"; const val PARTIAL = "PARTIAL" }
}

// PhoneFirstAppDayEntity.kt
@Entity(tableName = "phone_first_app_day", primaryKeys = ["date", "package_name"])
data class PhoneFirstAppDayEntity(
    @ColumnInfo(name = "date") val date: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "app_name") val appName: String,
    @ColumnInfo(name = "unlock_count") val unlockCount: Int,
)

// BlockedAttemptDayEntity.kt
@Entity(tableName = "blocked_attempt_day", primaryKeys = ["date", "package_name"])
data class BlockedAttemptDayEntity(
    @ColumnInfo(name = "date") val date: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "app_name") val appName: String,
    @ColumnInfo(name = "attempts") val attempts: Int,
    @ColumnInfo(name = "hourly_attempts") val hourlyAttempts: String,
)
```
```kotlin
// PhoneBehaviourDao.kt
@Dao
interface PhoneBehaviourDao {
    @Query("SELECT * FROM phone_day_rollup WHERE date BETWEEN :startDate AND :endDate ORDER BY date")
    suspend fun getDays(startDate: String, endDate: String): List<PhoneDayRollupEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertDay(row: PhoneDayRollupEntity)
    @Query("SELECT MIN(date) FROM phone_day_rollup") suspend fun earliestPhoneDate(): String?
    @Query("DELETE FROM phone_day_rollup WHERE date < :cutoff") suspend fun deleteDaysBefore(cutoff: String)

    @Query("SELECT * FROM phone_first_app_day WHERE date BETWEEN :startDate AND :endDate")
    suspend fun getFirstApps(startDate: String, endDate: String): List<PhoneFirstAppDayEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertFirstApps(rows: List<PhoneFirstAppDayEntity>)
    @Query("DELETE FROM phone_first_app_day WHERE date = :date") suspend fun deleteFirstApps(date: String)
    @Query("DELETE FROM phone_first_app_day WHERE date < :cutoff") suspend fun deleteFirstAppsBefore(cutoff: String)

    @Query("SELECT * FROM blocked_attempt_day WHERE date BETWEEN :startDate AND :endDate")
    suspend fun getBlocked(startDate: String, endDate: String): List<BlockedAttemptDayEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertBlocked(rows: List<BlockedAttemptDayEntity>)
    @Query("DELETE FROM blocked_attempt_day WHERE date < :cutoff") suspend fun deleteBlockedBefore(cutoff: String)
}
```
**Steps**
1. Create the four files (imports: `androidx.room.*`).
2. E1: apply §7.5. Do not touch any other line of `FocusFlowDatabase.kt`.
3. Compile. Confirm `app/schemas/.../11.json` was generated and contains `phone_day_rollup`, `phone_first_app_day`, `blocked_attempt_day`
   (`grep -c phone_day_rollup 11.json`). Keep the file.
4. If Room reports a schema/auto-migration error, switch to **Appendix A** (manual migration) and note it in §13.
5. Add this instrumentation test to `FocusFlowDatabaseMigrationTest.kt` (same class, same `helper` rule), then compile it with
   `:app:compileProductionDebugAndroidTestKotlin`:
```kotlin
@Test
fun migrateV10To11AddsPhoneTablesAndKeepsExistingRows() {
    helper.createDatabase(PHONE_MIGRATION_DB_NAME, 10).apply {
        execSQL("INSERT INTO report_notes (ref_date, type, note, updated_at) VALUES ('2026-11-01', 'day', 'kept', '2026-11-01T00:00:00Z')")
        close()
    }
    val migrated = helper.runMigrationsAndValidate(PHONE_MIGRATION_DB_NAME, 11, true)
    migrated.query("SELECT note FROM report_notes WHERE ref_date = '2026-11-01' AND type = 'day'").use {
        assertTrue(it.moveToFirst()); assertEquals("kept", it.getString(0))
    }
    listOf("phone_day_rollup", "phone_first_app_day", "blocked_attempt_day").forEach { table ->
        migrated.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
    }
    migrated.close()
}
```
   Add `private const val PHONE_MIGRATION_DB_NAME = "phone-migration-test"` next to the file's existing constants (where
   `SUBTASK_MIGRATION_DB_NAME` is declared). If the manual fallback is used, pass `FocusFlowDatabase.MIGRATION_10_11` as the 4th
   argument of `runMigrationsAndValidate`.
6. Mark the test `OWNER-RUN PENDING` in §13 (instrumentation tests cannot run in the agent environment).

**Done when:** production compile passes, `11.json` exists with all three tables, the Android-test Kotlin compiles. **Do NOT** edit existing migrations or `10.json`.

---

### T6 — Store and rollup writer (with backfill)

**Needs:** T3, T5. **Goal:** persist one `PhoneDay` per past day, automatically backfilling whatever history the OS still has.

**Files (new):** `analytics/phone/PhoneBehaviourStore.kt`, `PhoneBehaviourRollupWriter.kt`. Tests: `FakePhoneBehaviourStore.kt` (test helper),
`PhoneBehaviourRollupWriterTest.kt`, `PhoneDayEntityMappingTest.kt`.

**6.1 The store (so everything above the database is testable without Room)**
```kotlin
interface PhoneBehaviourStore {
    suspend fun phoneDays(startDate: String, endDate: String): List<PhoneDayRollupEntity>
    suspend fun firstApps(startDate: String, endDate: String): List<PhoneFirstAppDayEntity>
    suspend fun replacePhoneDay(day: PhoneDayRollupEntity, firstApps: List<PhoneFirstAppDayEntity>)
    suspend fun appDays(startDate: String, endDate: String): List<UsageRollupAppDayEntity>       // existing table
    suspend fun appSessions(startDate: String, endDate: String): List<UsageRollupSessionEntity>   // existing table
    suspend fun blockedDays(startDate: String, endDate: String): List<BlockedAttemptDayEntity>
    suspend fun mergeBlockedMax(rows: List<BlockedAttemptDayEntity>)
    suspend fun focusSessions(startIso: String, endIso: String): List<SessionOverrideCountRow>   // existing DAO
    suspend fun earliestDate(): String?          // min over phone_day_rollup and usage_rollup_day
    suspend fun prune(cutoffDate: String)
}

class RoomPhoneBehaviourStore(private val db: FocusFlowDatabase) : PhoneBehaviourStore { … }
```
Room implementation notes (use the DAO accessor names you recorded in T0):
- `replacePhoneDay`: `db.withTransaction { dao.deleteFirstApps(day.date); dao.upsertDay(day); if (firstApps.isNotEmpty()) dao.upsertFirstApps(firstApps) }`.
- `appDays/appSessions` → `db.usageRollupDao().getAppDays(start, end)` / `.getSessions(start, end)`.
- `focusSessions` → `db.focusSessionDao().getSessionsWithOverrideCount(startIso, endIso)`.
- `mergeBlockedMax`: inside `db.withTransaction`, load existing rows for the min..max date of `rows`, keep only the incoming rows whose `attempts` is
  **greater** than the existing row's (or that have no existing row), `upsertBlocked(thoseRows)`. (No SQL UPSERT — SQLite 3.22.)
- `earliestDate`: smallest of `dao.earliestPhoneDate()` and `db.usageRollupDao().getDays("0000-01-01", "9999-12-31").firstOrNull()?.date`.
- `prune(cutoff)`: the three `delete…Before(cutoff)` calls.
Also write the two mappers (top-level functions in the same file, pure, unit-tested):
`PhoneDayRollupEntity.toPhoneDay(firstApps: Map<String, Int>): PhoneDay` and `PhoneDay.toEntity(computedAtMs: Long, coverageStartMs: Long?): PhoneDayRollupEntity`
(`status` strings via the entity companion; lists via `PhoneCodec`; `sessionBasis = if (unlockSignal) UNLOCK else SCREEN`).

**6.2 The writer**
```kotlin
sealed interface PhoneRollupPass {
    data class Written(val dates: List<String>) : PhoneRollupPass
    data class Unknown(val reason: UsageEventRead.Unknown.Reason) : PhoneRollupPass
}

class PhoneBehaviourRollupWriter(
    private val store: PhoneBehaviourStore,
    private val eventSource: UsageEventsSource,
    private val excludedPackages: Set<String>,
    private val appNameResolver: (String) -> String,
    /** Keeps the existing per-app rollups in step; AppModule passes usageRollupWriter::writeDaysFromEvents. Null in tests. */
    private val appRollupHook: (suspend (String, String, List<ForegroundSession>, Long?, Long) -> Unit)? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun writeMissingPastDays(nowMs: Long = clock.millis()): PhoneRollupPass
}
```
**Algorithm of `writeMissingPastDays`** (guard the whole body with a `Mutex`):
1. `today` = local date of `nowMs`. Candidate dates = the 14 dates `today-14 … today-1` (`BACKFILL_DAYS`).
2. `existing = store.phoneDays(first, last).associateBy { it.date }`.
3. A date is a **candidate** if **both**: (a) `knownLowerBoundMs == null || dayEndMs(date) > knownLowerBoundMs` (an in-memory field, see step 6); and
   (b) it needs work: no existing row, **or** `behaviourVersion != BEHAVIOUR_VERSION`, **or** the row is `PARTIAL` and (`date >= today-2` **or**
   `nowMs - computedAtMs > 24 h`). A `COMPLETE` row of the current version is never recomputed.
4. If there are no candidates: `store.prune(today - 365 days)` and return `Written(emptyList())`. **No event read happens on this fast path.**
5. `readStartMs = dayStartMs(firstCandidate) - EVENT_LOOKBACK_MS`; `read = eventSource.readForegroundEvents(readStartMs, nowMs)`.
   If `read` is `Unknown` → return `PhoneRollupPass.Unknown(reason)` (write nothing).
6. `knownLowerBoundMs = read.earliestEventAtMs ?: nowMs`.
7. `sessions = ForegroundSpanTracker(excludedPackages = excludedPackages).sessions(read.events, readStartMs, nowMs, nowMs)`.
8. For each candidate date (oldest first):
   - Skip it if `read.earliestEventAtMs == null || read.earliestEventAtMs >= dayEndMs(date)` (the day was never observed — no row is written; it stays `MISSING`).
   - `daySessions = sessions.filter { it.startedAtMs < dayEndMs && it.endedAtMs > dayStartMs }`;
     `unresolved = daySessions.any { it.isInferredTail && localDate(it.startedAtMs) == date && nowMs < it.startedAtMs + ForegroundSpanTracker.DEFAULT_INFERRED_TAIL_CAP_MS }`
     (this is exactly the expression `UsageRollupWriter` uses).
   - `coverage = UsageRollupCoverage.status(read.earliestEventAtMs, dayStartMs, unresolved)`; `status = if (coverage == UsageRollupDayEntity.COMPLETE) COMPLETE else PARTIAL`.
   - `day = PhoneEventReducer(zoneId).reduceDay(date, read.events, sessions, nowMs, status)`.
   - `store.replacePhoneDay(day.toEntity(nowMs, read.earliestEventAtMs), day.firstApps.map { (pkg, n) -> PhoneFirstAppDayEntity(date.toString(), pkg, appNameResolver(pkg), n) })`.
9. If `appRollupHook != null`: call it **once** in a try/catch (rethrow `CancellationException`, swallow other exceptions) with
   `(firstCandidate.toString(), (today-1).toString(), sessions, read.earliestEventAtMs, nowMs)`. This makes the existing app rollups exist for the same days.
10. `store.prune((today - DAY_ROW_RETENTION_DAYS).toString())`; return `Written(writtenDates)`.

**Tests** (`FakePhoneBehaviourStore` = in-memory lists implementing the whole interface; the event source is a lambda `UsageEventsSource { s, e -> … }` that counts its calls; `ZoneId.of("UTC")`, fixed `nowMs`):
1. Events over 3 past days → rows written for those days, **none for today**; COMPLETE vs PARTIAL as expected.
2. Second call with the rows now COMPLETE → **zero** event-source calls.
3. `earliestEventAtMs` in the middle of day D-5 → D-6 and older get no row; D-5 is PARTIAL.
4. A PARTIAL row for yesterday is recomputed on the next pass; a PARTIAL row 10 days old computed 1 h ago is not.
5. `Unknown(PERMISSION_MISSING)` → returns `Unknown`, store untouched.
6. First-app rows are stored with names from `appNameResolver`.
7. Rows older than 365 days are pruned.
8. The hook is called once with the right `startDate/endDate`, and a hook that throws does not fail the pass.
9. `PhoneDayEntityMappingTest`: round trip `PhoneDay → entity → PhoneDay` keeps every field (incl. `firstApps` passed separately).

**Done when:** tests pass; compile OK. **Do NOT** write a row for today; do not read events when nothing is missing.

---

### T7 — Blocked-attempt sync (durable counts, no enforcement changes)

**Needs:** T5, T6. **Goal:** keep a durable per-day count of blocked attempts by reading the existing 500-entry log. **Do not edit `enforcement/**`.**

**Files (new):** `analytics/phone/BlockedAttemptSync.kt`. Tests: `TemptationLogParserTest.kt`, `BlockedAttemptSyncTest.kt`.

```kotlin
object TemptationLogParser {
    /** Same JSON shape as AnalyticsProcessor's private parser: [{"pkg":"…","appName":"…","timestamp":123}, …]. Bad input → emptyList(). */
    fun parse(raw: String): List<TemptationEntry>
}

class BlockedAttemptSync(
    private val temptationLogProvider: suspend () -> String,
    private val store: PhoneBehaviourStore,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    /** Returns the number of rows handed to the store. */
    suspend fun sync(): Int
}
```
**Spec**
- `TemptationEntry` is the public data class in `analytics/AnalyticsProcessor.kt` (`pkg`, `appName`, `timestamp`). Parse with `org.json.JSONArray`. Skip items without a non-blank `pkg`.
  Wrap parsing in try/catch → on any exception return `emptyList()`.
- `sync()`: parse; drop entries with `timestamp <= 0`; group by `(localDate(timestamp), pkg)`; `attempts = group size`;
  `hourly` = 24 counts by local hour; `appName` = the last non-blank name in the group, else the package.
  Build `BlockedAttemptDayEntity` rows (`hourlyAttempts = PhoneCodec.encodeInts(hourly)`) and call `store.mergeBlockedMax(rows)`.
  Because the store keeps `max(existing, incoming)`, a day truncated by the 500-entry cap never lowers a stored count.
- If the log is empty/invalid return 0 and do not call the store.

**Tests:** parser handles valid, empty array, malformed JSON, missing `pkg`, missing `appName`; grouping by date and package; hourly array correct for a non-UTC zone;
a second sync with fewer entries does not lower stored counts (use the fake store, which must implement the max rule like the real one); an entry with `timestamp = 0` is ignored.

**Done when:** tests pass. **Do NOT** call `TemptationLogManager` directly (the provider lambda does that in T8).

---

### T8 — Wiring (AppModule + maintenance worker)

**Needs:** T4, T6, T7. **Goal:** the writer and the sync run automatically (every ~15 minutes with the existing worker). **Edit points E2, E3.**

**E2 — `di/AppModule.kt`**
1. Add four properties, following the file's existing style (KDoc + `lateinit var … private set`), next to the other usage properties:
```kotlin
lateinit var appCategoryResolver: AppCategoryResolver
    private set
lateinit var phoneBehaviourStore: PhoneBehaviourStore
    private set
lateinit var phoneBehaviourRollupWriter: PhoneBehaviourRollupWriter
    private set
lateinit var blockedAttemptSync: BlockedAttemptSync
    private set
```
2. In the init function, **after** the `usageRollupCoordinator = … .also { … }` statement (and after `greyoutRepository` is assigned — see your T0 note), add:
```kotlin
appCategoryResolver = AppCategoryResolver(app)
phoneBehaviourStore = RoomPhoneBehaviourStore(database)
phoneBehaviourRollupWriter = PhoneBehaviourRollupWriter(
    store = phoneBehaviourStore,
    eventSource = usageStatsRepository,
    excludedPackages = ForegroundSpanTracker.DEFAULT_EXCLUDED_PACKAGES + app.packageName,
    appNameResolver = { pkg -> UsageAppMetadata.resolveAppName(app, pkg) },
    appRollupHook = { startDate, endDate, sessions, earliest, nowMs ->
        usageRollupWriter.writeDaysFromEvents(startDate, endDate, sessions, earliest, nowMs)
    },
    clock = Clock.systemDefaultZone(),
    zoneId = ZoneId.systemDefault(),
)
blockedAttemptSync = BlockedAttemptSync(
    temptationLogProvider = { greyoutRepository.getTemptationLog() },
    store = phoneBehaviourStore,
    zoneId = ZoneId.systemDefault(),
)
```
(Adjust import lines only; the `appRollupHook` lambda's return value must be `Unit` — end it with `Unit` if the compiler complains about the `Set<String>` result.)

**E3 — `background/UsageMaintenanceWorker.kt`:** inside `writeRecentRollups`, directly after `val pass = AppModule.usageRollupWriter.writeRecentPastDays(nowMs)` and **before** the `return when (pass)`, add:
```kotlin
try {
    AppModule.phoneBehaviourRollupWriter.writeMissingPastDays(nowMs)
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(TAG, "Phone behaviour rollup failed", e)
}
try {
    AppModule.blockedAttemptSync.sync()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(TAG, "Blocked attempt sync failed", e)
}
```
Nothing else in the worker changes. (`Log` and `TAG` are already available in that class.)

**Tests:** none new (wiring). Run the **full** suite; the existing `UsageMaintenanceJobTest` etc. must still pass unchanged.

**Done when:** compile OK; full suite green with tests ≥ baseline. **Do NOT** change `UsageMaintenanceJob`, its interface, or any test double of it.

---

### T9 — Screen-time and phone-behaviour builders (pure)

**Needs:** T1 (T3 for realistic fixtures). **Goal:** turn stored/live `PhoneDay`s into `ScreenTimeSection` and `PhoneBehaviourSection`.

**Files (new):** `analytics/phone/ScreenTimeBuilder.kt`, `PhoneBehaviourBuilder.kt`. Tests: `ScreenTimeBuilderTest.kt`, `PhoneBehaviourBuilderTest.kt` (+ a test helper `PhoneTestFixtures.kt` with `day(date, status, screenOnMin, unlocks, …)`).

```kotlin
object ScreenTimeBuilder {
    fun build(range: PhoneDateRange, phoneDays: List<PhoneDay>, appDays: List<AppDayRow>): ScreenTimeSection
}
object PhoneBehaviourBuilder {
    fun build(range: PhoneDateRange, phoneDays: List<PhoneDay>, appNames: Map<String, String>, zone: ZoneId): PhoneBehaviourSection
}
```

**`ScreenTimeBuilder.build` — rules (all numbers per §4.13–§4.15)**
1. `byDate = phoneDays.associateBy { it.date }`; `appMsByDate` = sum of `AppDayRow.foregroundMs` per date.
2. `days` = one `DayPoint` per date of `range.dates()`: coverage from `PhoneDay.status` (`COMPLETE/PARTIAL/LIVE`) or `MISSING` when there is no `PhoneDay`;
   `screenOnMs/unlocks/wakes` from the day (0 when missing); `appMs` from `appMsByDate` (kept even when the phone day is missing).
3. `totalScreenOnMs` = sum over days whose coverage ≠ MISSING. `totalAppMs` = sum of `appMs` over **all** days.
4. Baseline days: the COMPLETE days → `averageBasis = COMPLETE_DAYS`; if none, the LIVE day → `TODAY_SO_FAR`; otherwise `NONE` (no averages).
   `averageDays` = number of baseline days. `avgScreenOnPerDayMs` = Σ baseline `screenOnMs` ÷ `averageDays` (integer division).
   `avgAppPerDayMs` = Σ baseline `appMs` ÷ `averageDays`, but `null` if every baseline day has `appMs == 0`.
5. `weekday`/`weekend` (Mon–Fri / Sat–Sun) from **COMPLETE days only**: `DayGroupAverage(avgMs, days)`. Both are returned only if ≥ 2 weekday and ≥ 1 weekend COMPLETE days exist; otherwise both `null`.
6. `highest`/`lowest`: only if there are ≥ 3 COMPLETE days. Highest = largest `screenOnMs`, ties → later date. Lowest = smallest `screenOnMs`, ties → later date.
7. `screenOnByHourAvgMs` = per hour Σ baseline `screenOnHourlyMs[h]` ÷ `averageDays` (24 longs; all zero when `NONE`). `peakHour` = hour with the largest value (ties → earliest hour), `null` if the maximum is 0.

**`PhoneBehaviourBuilder.build` — rules**
1. `measured` = `phoneDays` inside the range (any status). `daysWithData = measured.size`. Baseline days for per-day averages = COMPLETE days, else LIVE, else none.
2. `unlockSignalAvailable = measured.any { it.unlockSignal }`; `sessionBasis = UNLOCK` if available else `SCREEN`.
3. `unlocks` = Σ `unlockCount`; `unlocksPerDay` = Σ baseline `unlockCount` ÷ baseline days (`Double`) — `null` if no signal or no baseline days.
   `wakes`/`wakesPerDay` the same way (wakes do not need the unlock signal).
4. `avgGapBetweenUnlocksMs` = Σ `unlockGapSumMs` ÷ Σ `unlockGapCount` (`null` if the count is 0).
5. `sessions` = Σ `sessionCount`; `sessionBucketCounts`/`sessionBucketMs` = element-wise sums; `quickChecks` = counts[0] + counts[1];
   `quickCheckSharePercent` = round(quickChecks × 100 ÷ sessions), `null` if `sessions == 0`; `longestSessionMs` = max.
6. `unlocksByHour`/`wakesByHour` = element-wise sums over `measured`.
7. `firstApps`: sum each day's `firstApps` map; sort by unlocks desc then name; keep the top 8. `shareOfUnlocksPercent` = unlocks × 100.0 ÷ `unlocks` (0.0 if `unlocks == 0`);
   `perDay` = unlocks ÷ max(1, number of measured days with `unlockSignal`). Names from `appNames` (fall back to the package). `unlocksWithoutApp` = Σ.
8. `firstUnlockMedianMinuteOfDay`: from COMPLETE days with `firstUnlockAtMs != null`, convert each to minute-of-day in `zone`; the median (average of the two middle values, rounded, for an even count);
   `null` with fewer than 5 samples.

**Tests**
- `ScreenTimeBuilderTest`: MISSING days are excluded from totals but keep `appMs`; PARTIAL counts in the total but not in the average; only-LIVE → `TODAY_SO_FAR`; only PARTIAL/MISSING → `NONE` with null averages;
  weekday/weekend gating (1 weekend day OK, 0 weekend days → both null); highest/lowest absent with 2 COMPLETE days, correct with 3, tie → later date; hourly average and peak (incl. all-zero → `peakHour == null`);
  `avgAppPerDayMs == null` when no app rows exist.
- `PhoneBehaviourBuilderTest`: sums; `unlockSignalAvailable` false → basis `SCREEN`, `unlocksPerDay == null` but `wakesPerDay` present; gap average; quick-check share rounding; first-app shares and per-day;
  median minute with odd/even counts and with 4 samples (→ `null`); empty input gives zero-filled lists of sizes 24/24/5/5.

**Done when:** tests pass. **Do NOT** read the clock or call Android.

---

### T10 — Apps and distraction builders (pure)

**Needs:** T1, T4. **Goal:** per-app analysis, categories, time-of-day habits, and the distraction section.

**Files (new):** `analytics/phone/AppsBuilder.kt`, `DistractionBuilder.kt`. Tests: `AppsBuilderTest.kt`, `DistractionBuilderTest.kt`.

```kotlin
data class PreviousAppPeriod(val daysWithData: Int, val appMsByPackage: Map<String, Long>)
data class AppsInput(
    val range: PhoneDateRange,
    val appDays: List<AppDayRow>,
    val appSessions: List<AppSessionRow>,
    val firstAppUnlocks: Map<String, Int>,        // package -> unlocks in the range
    val previous: PreviousAppPeriod?,
    val categoryOf: (String) -> UsageCategory,
)
object AppsBuilder { fun build(input: AppsInput): AppsSection }
object DistractionBuilder {
    fun build(
        apps: AppsSection, appDays: List<AppDayRow>, blockedByPackage: Map<String, Int>,
        categoryOf: (String) -> UsageCategory, previous: PreviousAppPeriod?,
    ): DistractionSection
}
```

**`AppsBuilder.build` — rules**
1. `dates = range.dates()`; `dateIndex` maps `date.toString()` → position.
2. Per package from `appDays`: `totalMs`; `dailyMs` (aligned with `dates`); `activeDays` = dates with ms > 0; `periodMs[PhonePeriod.of(h).ordinal] += hourlyMs[h]` for each hour `h`; `appName` = the latest non-blank name seen.
3. Sessions per package (only those with `durationMs >= MIN_OPEN_MS`): `opens` = count; `bucketCounts[PhoneBuckets.indexFor(duration)]++`; `longestSessionMs` = max; `avgSessionMs` = Σ duration ÷ opens.
   **Fallback** when a package has no qualifying session rows but its `appDays` have `sessionCount > 0`: `opens = Σ sessionCount`, buckets all 0, `longestSessionMs = 0`, `avgSessionMs = totalMs ÷ opens`.
4. `appDataDays` = distinct dates with any app row whose ms > 0. `avgPerDayMs = totalMs ÷ max(1, appDataDays)`.
5. Previous period: only if `previous != null && previous.daysWithData >= 4 && appDataDays >= 4`:
   `previousAvgPerDayMs = previous.appMsByPackage[pkg] ÷ previous.daysWithData` (0 if absent). `changePercent` = round((avgPerDayMs − previousAvgPerDayMs) × 100 ÷ previousAvgPerDayMs) **only if** `previousAvgPerDayMs >= 5 min`; else `null`. Otherwise both `null`.
6. `sharePercent = totalMs × 100.0 ÷ totalAllApps` (0.0 if the total is 0). `firstAfterUnlock = firstAppUnlocks[pkg] ?: 0`. `category = categoryOf(pkg)`.
7. Sort by `totalMs` desc, ties by `appName`. Keep 30 rows; `othersMs` = time of the rest. `totalMs` is the sum of **all** apps.
8. `categories`: group **all** apps (not just the 30) by `categoryOf`, with `totalMs` and `sharePercent`, sorted desc; omit zero rows.
9. `periods`: always 4 entries in `PhonePeriod` order. `totalMs` = Σ over all apps of `periodMs[ordinal]`; `sharePercent`; `topApps` = the 3 packages with the largest ms in that period (ms > 0).

**`DistractionBuilder.build` — rules (§4.16–§4.18)**
1. From `appDays`: per package `totalMs` and 24-hour sums.
2. Distraction set = packages where `categoryOf(pkg).isDistraction || blockedByPackage[pkg] > 0`.
3. `distractionMs` = Σ `totalMs` of the set; `sharePercent` = round(distractionMs × 100 ÷ `apps.totalMs`), `null` if `apps.totalMs == 0`.
4. `byHourMs` = Σ hourly of the set; `peakHour` = argmax (ties → earliest), `null` if all zero.
5. `apps` (max 8): members of the set that appear in `apps.apps`, by `totalMs` desc. `reasons` (in this order): the category label if `category.isDistraction`; `"Blocked N×"` if blocked > 0;
   `"N opens a day"` (N rounded) if `opens ÷ activeDays >= 5` and `activeDays > 0`. `blockedAttempts` from the map; `opens` from the row.
6. `reflexApps` (max 5): from `apps.apps` with `activeDays >= REFLEX_MIN_ACTIVE_DAYS`, `opens ÷ activeDays >= REFLEX_MIN_OPENS_PER_DAY` and `bucketCounts[0] ÷ opens >= REFLEX_MIN_SHORT_SHARE`; sort by opens-per-active-day desc; `shortSessionSharePercent` = round(bucket0 × 100 ÷ opens).
7. `risingApps` (max 3): from `apps.apps` where `changePercent != null`, `avgPerDayMs >= RISING_MIN_RATIO × previousAvgPerDayMs`, `avgPerDayMs − previousAvgPerDayMs >= RISING_MIN_DELTA_MS`, `previousAvgPerDayMs >= RISING_MIN_PREVIOUS_MS`, `activeDays >= 3`; sort by absolute increase desc.
8. `changePercent` (category-only): current = Σ `totalMs` of packages with `categoryOf(pkg).isDistraction` ÷ `apps.appDataDays`; previous = Σ `previous.appMsByPackage` of such packages ÷ `previous.daysWithData`.
   Only if `previous != null`, `previous.daysWithData >= 4`, `apps.appDataDays >= 4` and previous ≥ 5 min/day; else `null`.

**Tests**
- `AppsBuilderTest`: totals and shares add up to ~100; opens ignore sessions < 1 s; fallback to `sessionCount`; bucket counts; hour 23 → LATE_NIGHT and hour 6 → MORNING; trend gating (3 previous days → null, 4 → computed; previous avg < 5 min → null);
  more than 30 apps → `othersMs`, categories still include them; tie ordering by name; `dailyMs` aligned with `range.dates()`; empty input → empty lists, 4 periods with zeros.
- `DistractionBuilderTest`: set membership by category and by blocked attempts; reasons list order; hourly sums and peak; reflex thresholds (7.9 opens/day excluded, 8.0 included; 49% short excluded);
  rising thresholds (1.49× excluded, 1.5× and ≥ 15 min included); category-only change% gating; empty input.

**Done when:** tests pass. **Do NOT** call Android APIs; categories arrive through `categoryOf`.

---

### T11 — Focus and blocking builder (pure)

**Needs:** T1. **Goal:** `FocusBlockingSection` from focus sessions and the durable blocked-attempt rows.

**File (new):** `analytics/phone/FocusBlockingBuilder.kt`. Test: `FocusBlockingBuilderTest.kt`.

```kotlin
object FocusBlockingBuilder {
    fun build(
        range: PhoneDateRange, today: LocalDate, nowMs: Long, zone: ZoneId,
        sessions: List<FocusSessionRow>, blocked: List<BlockedDayRow>,
        trendSessions: List<FocusSessionRow>,        // sessions overlapping the last 56 days
        trendBlocked: List<BlockedDayRow>,           // rows in the last 56 days
    ): FocusBlockingSection
}
```
**Rules (§4.19)**
1. Range bounds: `rangeStartMs = range.start.atStartOfDay(zone)`, `rangeEndMs = min(range.end.plusDays(1).atStartOfDay(zone), nowMs)` (as epoch ms).
2. `focusMs` = Σ over sessions of `overlap([startedAtMs, endedAtMs ?: nowMs], [rangeStartMs, rangeEndMs])`. A session counts in `sessions` if its overlap is > 0.
3. Ended sessions (`endedAtMs != null`) overlapping the range: `heldSessions` = `overrideCount == 0`; `interruptedSessions` = `overrideCount > 0`. Running sessions are excluded from both and from the duration buckets.
   Duration buckets (full length `endedAtMs − startedAtMs`, 5 entries): `< 15 min`, `< 30`, `< 60`, `< 120`, else.
4. `blockedAttempts` = Σ attempts of `blocked` rows; `blockedApps` = per package sum, top 8 by attempts, `sharePercent` = attempts × 100.0 ÷ total; `blockedByHour` = element-wise sum of the hourly lists; `blockedPeakHour` = argmax (ties → earliest), `null` if all zero.
5. `attemptsPerFocusHour` = `blockedAttempts ÷ (focusMs ÷ 3 600 000.0)` only if `focusMs >= 30 min`, else `null`.
6. `weeks`: for `k` in 0..7 the block `[today − 7k − 6, today − 7k]` (inclusive dates). Per block: `focusMs` (overlap with the block's day bounds), `endedSessions`/`heldSessions` for sessions whose **start date** is in the block, `attempts` = Σ trend rows in the block. Return oldest first (k = 7 first).
7. `trend`: now = blocks 0+1, before = blocks 2+3. Needs `focusMs >= 1 h` on both sides, else `null`. `rate = attempts ÷ focusHours` for each side.
   If `rateBefore == 0.0`: both rates 0 → `FocusTrend(FLAT, 0.0, 0.0, 0)`; `rateBefore == 0.0 && rateNow > 0` → `null`.
   Else `changePercent = round((rateNow − rateBefore) × 100 ÷ rateBefore)`; direction `UP` if ≥ +20, `DOWN` if ≤ −20, else `FLAT`.

**Tests:** overlap clipping at both range edges; a running session counts toward `focusMs` only; held vs interrupted; each bucket boundary (14:59 → 0, 15:00 → 1, …, 120:00 → 4); top-8 cut and shares; hour array and peak;
`attemptsPerFocusHour` null below 30 min; weeks (8 entries, oldest first, block boundaries); trend: null below 1 h, FLAT within ±19%, UP at +20%, DOWN at −20%, `rateBefore == 0` cases; empty input.

**Done when:** tests pass. **Do NOT** read blocked data from `TemptationLogManager` here (rows arrive from the store).

---

### T12 — `PhoneBehaviourRepository` (assemble everything) — **Gate A**

**Needs:** T6–T11, T8. **Goal:** one suspend call returns a ready `PhoneReport` (or why not); another returns the input for the Insights engine.

**File (new):** `analytics/phone/PhoneBehaviourRepository.kt`. Test: `PhoneBehaviourRepositoryTest.kt` (uses `FakePhoneBehaviourStore`, a counting event source, a real `PhoneBehaviourRollupWriter` over the fake store, `Clock.fixed`, `ZoneId.of("UTC")`).
**Edit:** E2 again (add the repository to `AppModule`).

```kotlin
class PhoneBehaviourRepository(
    private val store: PhoneBehaviourStore,
    private val eventSource: UsageEventsSource,
    private val writer: PhoneBehaviourRollupWriter,
    private val blockedSync: BlockedAttemptSync,
    private val hasPermission: suspend () -> Boolean,
    private val categoryOf: (String) -> UsageCategory,
    private val appNameResolver: (String) -> String,
    private val excludedPackages: Set<String>,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun loadReport(window: String, weekMode: String, weekStartDay: Int): PhoneReportResult
    suspend fun loadInsightInput(): PhoneInsightInputResult
}
```
Run the body inside `withContext(dispatcher)`. Catch `Exception` → `Failed(message)`, **but rethrow `CancellationException`**.

**`loadReport` algorithm**
1. `if (!hasPermission()) return PermissionNeeded`.
2. `nowMs = clock.millis()`; `today` = local date. Persist first: `writer.writeMissingPastDays(nowMs)` — if it returns `Unknown` with `PERMISSION_MISSING`/`ACCESS_REVOKED` return `PermissionNeeded`;
   then `blockedSync.sync()`. Failures in these two calls are swallowed (log nothing); the report is still built from what is stored.
3. `range` via this helper (keep it `internal` for tests):
```kotlin
internal fun resolveRange(window: String, weekMode: String, weekStartDay: Int, now: ZonedDateTime, earliest: LocalDate?): PhoneDateRange {
    val r = getAnalyticsRange(window, now, weekStartDay, weekMode)
    val today = now.toLocalDate()
    val end = minOf(r.end.toLocalDate(), today)
    var start = if (window == ANALYTICS_ALL_TIME) (earliest ?: today) else r.start.toLocalDate()
    if (start.isAfter(end)) start = end
    return PhoneDateRange(start, end)
}
```
   `earliest` = `store.earliestDate()?.let(LocalDate::parse)`.
4. Past part: `pastEnd = minOf(range.end, today.minusDays(1))`. If `range.start <= pastEnd` load for `[range.start, pastEnd]`: `store.phoneDays`, `store.firstApps`, `store.appDays`, `store.appSessions`. Convert entities:
   `PhoneDayRollupEntity.toPhoneDay(firstAppsByDate[date])`; `UsageRollupAppDayEntity → AppDayRow(date, packageName, appName, foregroundMs, PhoneCodec.decodeLongs(hourlyMs, 24), sessionCount)`;
   `UsageRollupSessionEntity → AppSessionRow(packageName, localDate, startedAtMs, durationMs)`.
5. **Live today** when `range.end == today`:
   `dayStart = start of today`; `read = eventSource.readForegroundEvents(dayStart − EVENT_LOOKBACK_MS, nowMs)`; if it is not `Available`, skip live data (not an error).
   `sessions = ForegroundSpanTracker(excludedPackages = excludedPackages).sessions(read.events, dayStart − EVENT_LOOKBACK_MS, nowMs, nowMs)`;
   `phoneDay = PhoneEventReducer(zoneId).reduceDay(today, read.events, sessions, nowMs, LIVE)`;
   live app rows = `UsageCalendarAggregator.aggregate(sessions, dayStart, nowMs, zoneId)` mapped to `AppDayRow` (name via `appNameResolver`);
   live app sessions = sessions with `startedAtMs >= dayStart`, `durationMs = min(endedAtMs, nowMs) − startedAtMs`.
   Add them to the lists from step 4.
6. Blocked rows: `store.blockedDays(range.start, range.end)` → `BlockedDayRow(date, pkg, name, attempts, PhoneCodec.decodeInts(hourlyAttempts, 24))`.
7. Focus rows: `store.focusSessions(startIso, endIso)` with `startIso = range.start.atStartOfDay(zone).toInstant().toString()` and `endIso = range.end.plusDays(1).atStartOfDay(zone).toInstant().toString()`;
   map `SessionOverrideCountRow → FocusSessionRow` (`Instant.parse`, local date of the start; skip a row whose timestamps do not parse).
   Trend inputs: the same two queries for `[today − 55, today]`.
8. Previous period (app trends) only for `ANALYTICS_WEEK` and `ANALYTICS_THREE_MONTHS` and `range.dayCount >= 7`:
   `prevEnd = range.start − 1 day`, `prevStart = prevEnd − (dayCount − 1)`; load `store.appDays` for it →
   `PreviousAppPeriod(daysWithData = distinct dates with ms > 0, appMsByPackage = Σ foregroundMs per package)`; else `null`.
9. `appNames` = names from all app rows, first-app rows and blocked rows (package as fallback).
10. If there are no phone days, no app rows, no blocked rows and no focus rows → `NoData`.
11. Build: `ScreenTimeBuilder`, `PhoneBehaviourBuilder`, `AppsBuilder` (with `firstAppUnlocks` = Σ of `phoneDay.firstApps` over the range), `DistractionBuilder` (`blockedByPackage` = Σ attempts per package),
    `FocusBlockingBuilder`; `PhoneCoverage` from the `ScreenTimeSection.days` coverages and the earliest dates of phone/app rows. Return `Ready(PhoneReport(window, range, …))`.

**`loadInsightInput` algorithm:** same permission/persist steps; range `[today − 89, today]`; load phone days (+ first apps), app days, app sessions, blocked rows, focus sessions, live today (same code as step 5, shared private function);
`completeDays = phoneDays.count { it.status == COMPLETE }`; if `< 3` → `Building(completeDays)`; else `Ready(PhoneInsightInput(today, days, appDays, appSessions, blockedDays, focusSessions, appNames, categoryOf, zoneId))`.

**E2 (again):** add `lateinit var phoneBehaviourRepository: PhoneBehaviourRepository` (private set) and, right after the T8 block:
```kotlin
phoneBehaviourRepository = PhoneBehaviourRepository(
    store = phoneBehaviourStore,
    eventSource = usageStatsRepository,
    writer = phoneBehaviourRollupWriter,
    blockedSync = blockedAttemptSync,
    hasPermission = { usageStatsRepository.hasPermission() },
    categoryOf = { pkg -> appCategoryResolver.resolve(pkg) },
    appNameResolver = { pkg -> UsageAppMetadata.resolveAppName(app, pkg) },
    excludedPackages = ForegroundSpanTracker.DEFAULT_EXCLUDED_PACKAGES + app.packageName,
)
```

**Tests**
1. No permission → `PermissionNeeded`; the store and event source are never touched.
2. No data anywhere → `NoData`. 3. `ANALYTICS_YESTERDAY` → one-date range, values from the stored row.
4. `ANALYTICS_WEEK` (dynamic) with 6 stored days + events for today → 7 `DayPoint`s, today is `LIVE`, totals include it.
5. `ANALYTICS_ALL_TIME` starts at `store.earliestDate()`. 6. `WEEK_MODE_FIXED` range ending in the future clamps to today (use `resolveRange` directly).
7. The writer runs before reads: days that exist only as events (not yet stored) appear in the report.
8. Previous-period gating: 3 previous days → `changePercent == null`; 4 → computed.
9. A temptation-log JSON given to the sync shows up in `focus.blockedAttempts`.
10. A store that throws → `Failed`, not an exception; a cancelled coroutine rethrows `CancellationException`.
11. `loadInsightInput`: 2 complete days → `Building(2)`; 5 complete days → `Ready` whose `days` contains the live day.

**Done when (GATE A):** compile OK; focused tests pass; **full** production suite green with tests ≥ baseline; record the counts in §13.
**Do NOT** touch UI in this task.

---

### T13 — Insight engine (pure)

**Needs:** T1, T4. **Goal:** `PhoneInsightEngine.generate(input)` returns evidence-backed insights. Deterministic, stateless, no Android, no clock (`input.today` is the clock).

**Files (new):** `analytics/phone/insights/InsightStats.kt`, `PhoneInsightEngine.kt`; fill `InsightThresholds.kt`. Tests: `InsightStatsTest.kt`, `PhoneInsightEngineTest.kt` (+ `InsightFixtures.kt`).

```kotlin
object InsightStats {
    fun median(values: List<Long>): Long            // average of the two middle values (integer division) for an even count; 0 for empty
    fun mean(values: List<Long>): Double
    fun stdev(values: List<Double>): Double          // population standard deviation
    fun percentChange(now: Double, before: Double): Int?   // round half up; null when before == 0.0
}
class PhoneInsightEngine { fun generate(input: PhoneInsightInput): List<PhoneInsight> }
```
**Windows** (all relative to `today = input.today`; "complete" = `PhoneDay.status == COMPLETE`):
`recent` = `[today−7 … today−1]` · `prior` = `[today−28 … today−8]` · `last14` = `[today−14 … today−1]` · `prior14` = `[today−28 … today−15]` ·
`weekLast` = `[today−14 … today−8]` · `month` = `[today−30 … today−1]` · `monthBefore` = `[today−60 … today−31]`.
Per-app minutes per date come from `appDays`; a date with no row counts as 0 **only if that date is a COMPLETE phone day** (otherwise the date is ignored).
All text uses `PhoneText` (T1). Put **every** number below into `InsightThresholds` as named constants before using it. Every insight also carries
`evidence` lines `What:`, `How:`, `Period:`, `Evidence:` (e.g. `Evidence: 12 complete days`). `confidence` = `HIGH` when the sample is ≥ 2× the minimum, else `MEDIUM`. Message templates: **Appendix C**.

**Modules** (each returns zero or more insights; below-minimum input returns none):
1. **WEEKLY_SUMMARY** — `recent` vs `weekLast`; needs ≥ 4 complete days in each. Lines (only when data exists): average screen-on per day with % change; average unlocks per day if ≥ 4 days with signal in both weeks;
   most-used app this week (with minutes/day) and whether it changed; late-night minutes per day; focus time and blocked attempts if either week has any. Direction: `UP` if screen-time change ≥ +5%, `DOWN` if ≤ −5%, else `FLAT`.
   `id = "weekly:" + (today−1)`, priority 80.
2. **MONTHLY_SUMMARY** — `month` vs `monthBefore`; needs ≥ 20 and ≥ 10 complete days. Lines: average screen-on per day with % change; unlocks per day (signal); top 3 apps of the month. `id = "monthly:" + (today−1)`, priority 45.
3. **UNUSUAL_DAY** — yesterday must be complete; baseline = complete days in `[today−15 … today−2]`, needs ≥ 7. `M` = median baseline screen-on, `y` = yesterday.
   `UP` if `y ≥ 1.4·M` **and** `y − M ≥ 45 min`; `DOWN` if `y ≤ 0.6·M` **and** `M − y ≥ 45 min`. `id = "unusual:" + yesterday`, priority 90 (`UP`) / 55 (`DOWN`).
4. **APP_SURGE / APP_DROP** — needs ≥ 5 complete days in `recent` and ≥ 14 in `prior`. For every package: `recentAvg` and `priorAvg` = mean minutes over the complete dates of each window; `recentActive` = complete recent dates with minutes > 0.
   Surge: `recentActive ≥ 3` and `recentAvg ≥ 1.5·priorAvg` and `recentAvg − priorAvg ≥ 15 min`; if `priorAvg == 0` it needs `recentAvg ≥ 20 min` and uses the "new" wording. Top 2 by increase; `id = "surge:" + pkg`, priority 85.
   Drop: `priorAvg ≥ 20 min`, `recentAvg ≤ 0.5·priorAvg`, `priorAvg − recentAvg ≥ 15 min`. Top 1 by decrease; `id = "drop:" + pkg`, priority 60.
5. **UNLOCKS_CHANGE** — complete days **with unlock signal**: needs ≥ 5 in `recent`, ≥ 14 in `prior`. `UP` if `recentAvg ≥ 1.25·priorAvg` and difference ≥ 10 unlocks/day; `DOWN` if `≤ 0.75·priorAvg` and difference ≥ 10. `id = "unlocks:" + (today−1)`, priority 70.
6. **LATE_NIGHT_SHIFT** — late-night screen-on per day = Σ `screenOnHourlyMs` at hours 22, 23, 0, 1, 2, 3, 4. Same eligibility as surge. `UP` if increase ≥ 30 min/day and `recentAvg ≥ 1.5·priorAvg` (or `priorAvg < 5 min` and `recentAvg ≥ 30 min`);
   `DOWN` if decrease ≥ 30 min/day and `recentAvg ≤ 0.5·priorAvg`. `id = "latenight:" + (today−1)`, priority 75 (`UP`) / 50 (`DOWN`).
7. **FIRST_APP_PATTERN** — over complete days in `last14`: total unlocks `U ≥ 30`; the most common first app has share `≥ 25%` → insight (mention a second app only if its share ≥ 15%). `id = "firstapp:" + pkg`, priority 65.
8. **CHECKING_HABIT** — complete days in `last14` with unlock signal: phone sessions `N ≥ 50`; quick checks (buckets 0+1) share `≥ 50%` and `≥ 10` quick checks per day → insight. `id = "checking:" + (today−1)`, priority 64.
9. **TIME_OF_DAY_HABIT** — the top 3 apps by total minutes in `last14` that have ≥ 60 min; for each, the share of its time in each `PhonePeriod` (from `hourlyMs`); if the largest share is `≥ 55%` → insight (max 2). `id = "todhabit:" + pkg`, priority 55.
10. **CONSISTENCY** — ≥ 10 complete days in `last14`: `cv = stdev ÷ mean` of daily screen-on; label `steady` (< 0.25), `variable` (< 0.5), `irregular`. If `prior14` also has ≥ 10 complete days and `|cvNow − cvBefore| ≥ 0.1`, add the trend line
   (`direction = UP` when less steady, `DOWN` when steadier). If ≥ 10 complete days have a `firstUnlockAtMs`, add the first-pickup line (stdev of minute-of-day: `≤ 45` regular, `≥ 120` varies a lot, otherwise omit). `id = "consistency:" + (today−1)`, priority 50.
11. **RECORD** — one insight per record (max 7), `group = RECORD`, priority 40, `+5` and `isNew = true` when the record's date is within the last 7 days:
    `highest_day`, `lowest_day` (both need ≥ 7 complete days), `most_unlocks_day` (≥ 7 days with signal), `longest_phone_session` (max `longestSessionMs` over complete days, needs ≥ 3), `longest_app_session` (max `durationMs` in `appSessions`),
    `most_used_app` (Σ minutes over all given days), `busiest_hour` (hour with the largest mean `screenOnHourlyMs` over complete days, needs ≥ 7). `id = "record:" + key`.
12. **FOCUS_TREND** — attempts per focus hour for `last14` vs `prior14` (focus time by overlap of `focusSessions`, attempts from `blockedDays`); needs ≥ 1 h focus in each; emit if `|change| ≥ 20%`. Add the held-rate line (held ÷ ended sessions) when each side has ≥ 3 ended sessions and the difference ≥ 15 points.
    `id = "focus:" + (today−1)`, priority 58.
13. **DATA_NOTE** — `history`: when complete days < 14 (priority 10, `id = "note:history"`); `unlocks`: when the window has wakes ≥ 5/day on average but **no** unlock signal on any complete day (priority 12, `id = "note:unlocks"`).

**Assembly:** concatenate; keep at most 12 non-note insights (highest priority first, ties by `id`), then append the notes (max 2). Sort order within the result: priority desc, `id` asc. Drop duplicates by `id`.

**Tests** (fixtures build 60 days of `PhoneDay`/`AppDayRow` with a helper; use `LocalDate.of(2026, 11, 6)` as `today`):
for **each** module: a positive case (checks title/message numbers, direction, priority, `id`), a below-minimum case (no insight), and a just-under-threshold case (no insight);
UNUSUAL_DAY at exactly 1.4× and +45 min (fires) and 1.39× (does not); surge "new app" wording; drop; UNLOCKS_CHANGE not firing without signal; records with `isNew`; CONSISTENCY labels at 0.24/0.25/0.49/0.5;
determinism (two runs equal); assembly cap (13 candidate insights → 12 + notes); empty input → only notes or empty, never an exception.
`InsightStatsTest`: median (odd/even/empty), mean, stdev of a known set, `percentChange` rounding and the null case.

**Done when:** tests pass. **Do NOT** invent additional insights or loosen thresholds; do not store anything.

