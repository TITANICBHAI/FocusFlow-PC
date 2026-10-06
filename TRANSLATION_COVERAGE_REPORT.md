# Translation Coverage Report

**Date:** 2026-10-06  
**English source:** `src/main/kotlin/com/focusflow/i18n/Translations.kt`

## Summary

The translation catalog contains **652 string fields** in English and in each of the six non-English locales. Every non-English locale has the same set of fields as English: **no missing or extra keys were found**.

Coverage here is measured by comparing each locale's displayed string with the English string for the same key. A string that differs from English is counted as translated; an identical string is counted as unchanged and listed below for review.

| Language | Locale | Different from English | Identical to English | Text-difference coverage |
|---|---:|---:|---:|---:|
| Spanish | `es` | 638 / 652 | 14 | **97.9%** |
| Chinese (Simplified) | `zh` | 650 / 652 | 2 | **99.7%** |
| Japanese | `ja` | 649 / 652 | 3 | **99.5%** |
| Korean | `ko` | 648 / 652 | 4 | **99.4%** |
| German | `de` | 639 / 652 | 13 | **98.0%** |
| French | `fr` | 623 / 652 | 29 | **95.6%** |
| **All non-English locales** | — | **3,847 / 3,912** | **65** | **98.3%** |

## Strings identical to English

These entries are exact text matches to their English source. They are review candidates, not confirmed errors: product names, abbreviations, app names, time placeholders, and borrowed terms may appropriately remain unchanged.

### Spanish (`es`) — 14

| Key | English text |
|---|---|
| `goalSocialSub` | Discord, Instagram, WhatsApp… |
| `goalGamingSub` | Steam, Twitch, Netflix… |
| `goalWebSub` | Chrome, Firefox, Edge… |
| `focusPomodoroLabel` | Pomodoro |
| `focusNuclearLabel` | Nuclear |
| `statsTotal` | Total |
| `settingsPinLabel` | PIN |
| `defPinLabel` | PIN |
| `blockerHour` | HH |
| `blockerMinute` | MM |
| `blockerAppsLabel` | Apps |
| `blockerApp` | app |
| `blockerApps` | apps |
| `settingsAppsCount` | app(s) |

### Chinese (Simplified) (`zh`) — 2

| Key | English text |
|---|---|
| `settingsPinLabel` | PIN |
| `defPinLabel` | PIN |

### Japanese (`ja`) — 3

| Key | English text |
|---|---|
| `dashNow` | NOW |
| `settingsPinLabel` | PIN |
| `defPinLabel` | PIN |

### Korean (`ko`) — 4

| Key | English text |
|---|---|
| `goalGamingSub` | Steam, Twitch, Netflix… |
| `goalWebSub` | Chrome, Firefox, Edge… |
| `settingsPinLabel` | PIN |
| `defPinLabel` | PIN |

### German (`de`) — 13

| Key | English text |
|---|---|
| `sectionLive` | LIVE |
| `navDashboard` | Dashboard |
| `goalSocialSub` | Discord, Instagram, WhatsApp… |
| `goalGamingSub` | Steam, Twitch, Netflix… |
| `goalWebSub` | Chrome, Firefox, Edge… |
| `focusPomodoroLabel` | Pomodoro |
| `focusStandardLabel` | Standard |
| `settingsPinLabel` | PIN |
| `defPinLabel` | PIN |
| `blockerLimit` | Limit: |
| `blockerStart` | Start |
| `blockerMonth` | Mo |
| `blockerAppsLabel` | Apps |

### French (`fr`) — 29

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
| `settingsPinLabel` | PIN |
| `defPinLabel` | PIN |
| `blockerHour` | HH |
| `blockerMinute` | MM |
| `blockerAppsLabel` | Apps |
| `blockerApp` | app |
| `blockerApps` | apps |
| `tasksFieldDesc` | Description |
| `tasksFieldDateShort` | Date |
| `focusCycleLabel` | Cycle |

## Method and limitations

- Each of the 652 string fields in a locale is compared with the English value for the same key.
- Coverage is the number of values that differ from English divided by 652. The overall figure is weighted by the same 652 fields in each of six locales.
- This is a source-text comparison, not a human review. It does not detect partially English strings, assess translation accuracy or fluency, or decide whether identical terms should be localized.
- Exact English matches can be valid in context. Review the unchanged-string lists before treating any entry as missing a translation.
