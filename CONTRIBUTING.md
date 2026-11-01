# Contributing to FocusFlow

Thanks for wanting to help! FocusFlow is a free, open-source app blocker and focus timer for Windows, written in Kotlin with Compose Multiplatform Desktop. Bug reports, translations, docs and code are all welcome.

## Quick ways to help

- **Report a bug or suggest a feature.** [Open an issue](https://github.com/TITANICBHAI/FocusFlow-PC/issues/new/choose). For bugs, include your Windows version, your FocusFlow version, and whether you installed from the Microsoft Store or the EXE / MSI.
- **Improve a translation.** UI text lives in [`AppStrings.kt`](src/main/kotlin/com/focusflow/i18n/AppStrings.kt) and [`Translations.kt`](src/main/kotlin/com/focusflow/i18n/Translations.kt). [`TRANSLATION_COVERAGE_REPORT.md`](TRANSLATION_COVERAGE_REPORT.md) and [`language/TRANSLATION_TRACKER.md`](language/TRANSLATION_TRACKER.md) show what is still missing.
- **Fix the docs.** The README, the in-app *How to Use* page, and the website source in [`docs/`](docs/).

## Set up

You need **JDK 19** (Temurin recommended). The repo includes the Gradle wrapper.

```bash
./gradlew run     # start the app
./gradlew test    # run the automated tests
```

The window opens on any operating system, but the enforcement features (closing apps, hosts file, firewall, kiosk mode) only work on **Windows 10 and 11**. Test those on a real Windows machine or a VM. Installers (`packageExe`, `packageMsi`) must be built on Windows — GitHub Actions does this on every push to `main`.

## Pull requests

1. Fork the repo and branch from `main`. Keep each pull request small and focused.
2. Add or update tests when you change logic. The tests in `src/test/` show the patterns used here (temporary home folders, separate JVMs for process tests, and so on).
3. Run `./gradlew test` before you push.
4. Describe **what** changed, **why**, and what you tested it on.
5. If you touch enforcement (processes, hosts file, firewall, registry, taskbar, kiosk mode), also explain **how the change is undone**. A bug here can leave a PC locked, so recovery paths matter. Say whether administrator rights are needed.
6. Don't commit secrets, webhook URLs, database files, installers, or AI-assistant prompt and scratch files.

## Code style

Follow the surrounding Kotlin style. Prefer small, testable functions, and keep Windows-only calls inside `enforcement/` where you can, so the rest of the code stays testable on any OS.

## Code of conduct

Be kind. See [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
