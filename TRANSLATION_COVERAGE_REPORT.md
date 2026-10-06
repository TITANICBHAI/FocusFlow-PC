# Translation Coverage Report

**Date:** 2026-10-06  
**English source:** `src/main/kotlin/com/focusflow/i18n/Translations.kt`

## Summary

The translation catalog contains **652 string fields** in English and in each of the six non-English locales. Every non-English locale has the same set of fields as English: **no missing or extra keys were found**.

Coverage here is measured by comparing each locale's displayed string with the English string for the same key. A string that differs from English is counted as translated; an identical string is counted as unchanged and listed below for review.

| Language | Locale | Different from English | Identical to English | Text-difference coverage |
|---|---:|---:|---:|---:|
| Spanish | `es` | 647 / 652 | 5 | **99.2%** |
| Chinese (Simplified) | `zh` | 652 / 652 | 0 | **100.0%** |
| Japanese | `ja` | 652 / 652 | 0 | **100.0%** |
| Korean | `ko` | 650 / 652 | 2 | **99.7%** |
| German | `de` | 644 / 652 | 8 | **98.8%** |
| French | `fr` | 628 / 652 | 24 | **96.3%** |
| **All non-English locales** | — | **3,873 / 3,912** | **39** | **99.0%** |

## Strings identical to English

These entries are exact text matches to their English source. They are review candidates, not confirmed errors: product names, abbreviations, app names, time placeholders, and borrowed terms may appropriately remain unchanged.

### Spanish (`es`) — 5

| Key | English text |
|---|---|
| `focusPomodoroLabel` | Pomodoro |
| `focusNuclearLabel` | Nuclear |
| `statsTotal` | Total |
| `blockerHour` | HH |
| `blockerMinute` | MM |

### Chinese (Simplified) (`zh`) — 0

No exact English matches remain.

### Japanese (`ja`) — 0

No exact English matches remain.

### Korean (`ko`) — 2

| Key | English text |
|---|---|
| `goalGamingSub` | Steam, Twitch, Netflix… |
| `goalWebSub` | Chrome, Firefox, Edge… |

### German (`de`) — 8

| Key | English text |
|---|---|
| `sectionLive` | LIVE |
| `goalSocialSub` | Discord, Instagram, WhatsApp… |
| `goalGamingSub` | Steam, Twitch, Netflix… |
| `goalWebSub` | Chrome, Firefox, Edge… |
| `focusPomodoroLabel` | Pomodoro |
| `focusStandardLabel` | Standard |
| `blockerStart` | Start |
| `blockerMonth` | Mo |

### French (`fr`) — 24

| Key | English text |
|---|---|
| `navFocus` | Focus |
| `goalSocialSub` | Discord, Instagram, WhatsApp… |
| `goalGamingSub` | Steam, Twitch, Netflix… |
| `goalWebSub` | Chrome, Firefox, Edge… |
| `duration25Label` | 25 minutes |
| `duration45Label` | 45 minutes |
| `duration60Label` | 60 minutes |
| `duration90Label` | 90 minutes |
| `focusTitle` | Focus |
| `focusPomodoroLabel` | Pomodoro |
| `focusStandardLabel` | Standard |
| `focusPause` | Pause |
| `statsTotal` | Total |
| `statsSessions` | Sessions |
| `reportsSessions` | Sessions |
| `reportsFocusLabel` | Focus |
| `activeSessions` | Sessions |
| `notesSection` | Notes |
| `profileWeeklySessions` | Sessions |
| `blockerHour` | HH |
| `blockerMinute` | MM |
| `tasksFieldDesc` | Description |
| `tasksFieldDateShort` | Date |
| `focusCycleLabel` | Cycle |

## Method and limitations

- Each of the 652 string fields in a locale is compared with the English value for the same key.
- Coverage is the number of values that differ from English divided by 652. The overall figure is weighted by the same 652 fields in each of six locales.
- This is a source-text comparison, not a human review. It does not detect partially English strings, assess translation accuracy or fluency, or decide whether identical terms should be localized.
- Exact English matches can be valid in context. Review the unchanged-string lists before treating any entry as missing a translation.
