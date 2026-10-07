# FocusFlow Language Tracker

**Last updated:** 2026-10-07

This tracker records locale support in the app and the current Brazilian Portuguese work. The English catalog has 652 string fields.

## Locale status

| Locale | Status | Text-difference coverage | Notes |
|---|---|---:|---|
| English (`en`) | Source | — | Reference catalog |
| Spanish (`es`) | Complete | 99.2% | Remaining exact matches are shared terms or time placeholders |
| Chinese (Simplified, `zh`) | Complete | 100.0% | |
| Japanese (`ja`) | Complete | 100.0% | |
| Korean (`ko`) | Complete | 99.7% | Remaining exact matches are app and product names |
| German (`de`) | Complete | 98.8% | Remaining exact matches are shared terms and abbreviations |
| French (`fr`) | Complete | 96.3% | Remaining exact matches are shared terms, labels, and time placeholders |
| Brazilian Portuguese (`pt-BR`) | Complete | 98.2% | All 652 fields reviewed; 12 exact English matches are product names, abbreviations, or shared terms |

Coverage is a comparison with English source text, not a human assessment of translation quality.

## Brazilian Portuguese checklist

- [x] Create this tracker before starting locale implementation
- [x] Add Brazilian Portuguese to the supported-language selector
- [x] Translate all 652 English source fields into Brazilian Portuguese
- [x] Keep placeholders and formatting tokens intact
- [x] Update the coverage report
- [x] Record the addition in the v2.0.3 changelog
- [x] Review Brazilian Portuguese fluency and terminology (editorial pass; native-speaker verification remains pending)
