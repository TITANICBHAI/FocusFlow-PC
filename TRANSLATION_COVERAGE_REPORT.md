# Translation Coverage Report

**Audit date:** 2026-10-15
**Scope:** Public website pages and the Kotlin/Compose app's localization catalog and UI call sites.

## Summary

- The website has home, features, and blog pages in all eight supported locales, but legal and data-help pages are available in fewer languages, and some homepage content is omitted.
- The app's translation catalog has all **652 fields populated in all eight locales**. That does not mean every rendered screen is translated: the UI mixes localized fields with hardcoded English text.
- English and Brazilian Portuguese Terms pages now describe the current consent, report contents, redaction limits, Discord/IP visibility, and separate user-submitted feedback behavior.

## Website coverage

| Locale | Home / features / blog | Privacy policy | Terms | Data & backups |
|---|---:|---:|---:|---:|
| English (`en`) | 3/3 | Yes | Yes | Yes |
| Spanish (`es`) | 3/3 | No — footer links to English | No — footer links to English | No |
| French (`fr`) | 3/3 | No — footer links to English | No — footer links to English | No |
| German (`de`) | 3/3 | No — footer links to English | No — footer links to English | No |
| Japanese (`ja`) | 3/3 | No — footer links to English | No — footer links to English | No |
| Simplified Chinese (`zh`) | 3/3 | No — footer links to English | No — footer links to English | No |
| Korean (`ko`) | 3/3 | No — footer links to English | No — footer links to English | No |
| Brazilian Portuguese (`pt-BR`) | 3/3 | Yes | Yes | No |

All eight locales have localized titles and main headings on the three core marketing pages. Page availability is not the same as complete content parity:

- The English homepage has a technology/packaging section about Kotlin, Compose Desktop, and the standalone Windows installer. It is present in Brazilian Portuguese but absent from the Spanish, French, German, Japanese, Chinese, and Korean homepages.
- The Brazilian Portuguese homepage uses a different heading structure from English. Its lower heading count alone does not establish whether text is missing, so it needs a content-level comparison before being called complete.
- Chinese has inconsistent HTML language tags: the homepage uses `zh-Hans`, while its features and blog pages use `zh`.
- The English data-and-backups guide has no localized counterpart.

## Kotlin app coverage

The app supports English (`en`), Spanish (`es`), Simplified Chinese (`zh`), Japanese (`ja`), Korean (`ko`), German (`de`), French (`fr`), and Brazilian Portuguese (`pt-BR`).

| Locale | Populated fields | Exact text matches to English | Raw text-difference ratio |
|---|---:|---:|---:|
| Spanish (`es`) | 652/652 | 5 | 99.2% |
| Simplified Chinese (`zh`) | 652/652 | 0 | 100.0% |
| Japanese (`ja`) | 652/652 | 0 | 100.0% |
| Korean (`ko`) | 652/652 | 2 | 99.7% |
| German (`de`) | 652/652 | 8 | 98.8% |
| French (`fr`) | 652/652 | 24 | 96.3% |
| Brazilian Portuguese (`pt-BR`) | 652/652 | 12 | 98.2% |
| **All non-English locales** | **4,564/4,564** | **51** | **98.9%** |

Every locale has the same 652 keys as English, with no missing or blank values. The match count is only an exact-text comparison: names, abbreviations, units, and borrowed terms can correctly remain in English; a different string can still be mistranslated.

### Rendered screen caveat

The complete catalog does not translate all visible UI. A static scan found **430 direct string-literal `Text(...)` arguments across 35 UI files**; many are English labels or explanatory copy, while some are units, formats, or dynamic values. English literals remain in major screens and dialogs such as Settings, Contact & Bug Reports, Focus Launcher, App Blocker, Windows Setup, VPN & Network, onboarding, and the crash-consent dialog. The same literals appear regardless of the selected locale. The scan is not a screen-by-screen percentage, but no main screen can be certified fully localized end to end from this source audit.

There is also a consent-copy mismatch across all app locales: the localized privacy strings still call reports “anonymous” and say file paths are not included. The current consent explanation says Discord receives the sender's IP address and that diagnostic text may still contain paths or other context after redaction. These strings need a separate localization/content update.

## Terms and related privacy copy

The English and Brazilian Portuguese Terms pages were updated on 2026-10-15. They now say that reports require granted consent; explain the bounded exception/cause details and up to three stack-frame entries; distinguish the remote summary from the full local crash report; describe the limits of redaction; disclose Discord's visibility of the sender IP; and separate automatic diagnostics from a user-submitted issue message.

The Brazilian Portuguese Privacy Policy still contains older crash-report wording, including the outdated anonymous/default-on description. It was not changed in this Terms-only update and should be reconciled separately.

## Method and limitations

- Website coverage means the page exists and has localized page-level headings; it does not certify every paragraph's fluency or accuracy.
- The app catalog was compared by locale and key, including blank/missing checks. Exact English equality is a review signal, not an error by itself.
- The UI scan looks for direct string arguments in Compose `Text(...)` calls. It does not cover every UI API, inspect screenshots at every interaction state, or produce a semantic translation score.
- This is a static source audit, not native-speaker proofreading.
