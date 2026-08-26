# FocusFlow — Alternative Distribution Guide

> Version 1.0.6 · Windows x64 · TBTechs
> Use alongside `MICROSOFT_STORE_PUBLISH.md` — these channels complement the Store, not replace it.

---

## Quick Reference — App Identity

| Field | Value |
|---|---|
| App Name | `FocusFlow — Deep Focus & App Blocker` |
| Short Name | `FocusFlow` |
| Version | `1.0.6` |
| Developer / Publisher | `TBTechs` |
| Website | `https://tbtechs.app` *(update if different)* |
| GitHub Releases | `https://github.com/TITANICBHAI/FocusFlow-PC/releases` |
| License | See `LICENSE` in repo |
| Platform | Windows 10 / 11 (x64) |
| Binary | `.exe` installer or `.msi` — built via `gradle packageExe` / `gradle packageMsi` |
| Category | `Productivity / Utilities` |

---

## 1. GitHub Releases *(do this first — everything else links here)*

This is your canonical download source. Every other platform will point here.

### Steps
1. Build the installer locally:
   ```bash
   gradle packageExe
   # Output: build/compose/binaries/main/exe/FocusFlow-1.0.6.exe
   ```
2. Go to `https://github.com/TITANICBHAI/FocusFlow-PC/releases/new`
3. Tag: `v1.0.6`
4. Release title: `FocusFlow v1.0.6 — Deep Focus & App Blocker`
5. Upload: `FocusFlow-1.0.6.exe` and `FocusFlow-1.0.6.msi`
6. Paste the release notes below:

### Release Notes (copy-paste)
```
## FocusFlow v1.0.6

Real enforcement. No soft timers. No workarounds.

### What's new
- Daily Allowance Tracker: race condition fix — no more crashes when a tracked process exits mid-check
- SQLite stability: upgraded native driver to eliminate UnsatisfiedLinkError on cold launch
- Minor stability improvements

### System requirements
- Windows 10 / 11 (64-bit)
- ~80 MB disk space
- Run as Administrator recommended for full enforcement (hosts file + firewall rules)

### Install
Download FocusFlow-1.0.6.exe and run it. No Java installation required — runtime is bundled.
```

---

## 2. Winget (Windows Package Manager)

Microsoft's official CLI package manager — `winget install FocusFlow`. High-trust, built into Windows 11.

### Submit
1. Fork: `https://github.com/microsoft/winget-pkgs`
2. Create the folder path:
   ```
   manifests/t/TBTechs/FocusFlow/1.0.6/
   ```
3. Create three files inside that folder:

**`TBTechs.FocusFlow.yaml`** (version manifest)
```yaml
PackageIdentifier: TBTechs.FocusFlow
PackageVersion: 1.0.6
DefaultLocale: en-US
ManifestType: version
ManifestVersion: 1.6.0
```

**`TBTechs.FocusFlow.installer.yaml`** (installer manifest)
```yaml
PackageIdentifier: TBTechs.FocusFlow
PackageVersion: 1.0.6
InstallerLocale: en-US
Platform:
  - Windows.Desktop
MinimumOSVersion: 10.0.17763.0
InstallerType: exe
Scope: user
InstallModes:
  - interactive
  - silent
Installers:
  - Architecture: x64
    InstallerUrl: https://github.com/TITANICBHAI/FocusFlow-PC/releases/download/v1.0.6/FocusFlow-1.0.6.exe
    InstallerSha256: <SHA256_OF_EXE>   # run: certutil -hashfile FocusFlow-1.0.6.exe SHA256
    InstallerSwitches:
      Silent: /S
      SilentWithProgress: /S
ManifestType: installer
ManifestVersion: 1.6.0
```

**`TBTechs.FocusFlow.locale.en-US.yaml`** (locale manifest)
```yaml
PackageIdentifier: TBTechs.FocusFlow
PackageVersion: 1.0.6
PackageLocale: en-US
Publisher: TBTechs
PublisherUrl: https://tbtechs.app
PackageName: FocusFlow — Deep Focus & App Blocker
PackageUrl: https://github.com/TITANICBHAI/FocusFlow-PC
License: See LICENSE
ShortDescription: Block distracting apps and lock your desktop with real Windows enforcement. No soft timers, no workarounds.
Description: >
  FocusFlow is a hard-enforcement productivity app for Windows. It uses low-level Win32 APIs
  to instantly kill blocked apps, modify the hosts file to block websites, and optionally
  enter Focus Launcher (kiosk mode) that hides your taskbar and suppresses system shortcuts.
  Includes Pomodoro timer, habit tracker, daily allowances, and weekly focus reports.
Tags:
  - productivity
  - focus
  - app-blocker
  - pomodoro
  - distraction
ManifestType: defaultLocale
ManifestVersion: 1.6.0
```

4. Open a PR to `microsoft/winget-pkgs` — title: `New package: TBTechs.FocusFlow version 1.0.6`
5. The winget bot validates automatically. Usually approved within 1–3 days.

> **SHA256 tip:** Generate with `certutil -hashfile FocusFlow-1.0.6.exe SHA256` on Windows or `sha256sum` on Linux.

---

## 3. Chocolatey

Popular Windows package manager used by developers and IT admins — `choco install focusflow`.

### Steps
1. Create a free account at `https://community.chocolatey.org`
2. Install Chocolatey CLI locally: `Set-ExecutionPolicy Bypass -Scope Process -Force; [...]`
3. Create the package folder:
   ```
   focusflow/
   ├── focusflow.nuspec
   └── tools/
       └── chocolateyInstall.ps1
   ```

**`focusflow.nuspec`**
```xml
<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://schemas.microsoft.com/packaging/2015/06/nuspec.xsd">
  <metadata>
    <id>focusflow</id>
    <version>1.0.6</version>
    <title>FocusFlow — Deep Focus &amp; App Blocker</title>
    <authors>TBTechs</authors>
    <projectUrl>https://github.com/TITANICBHAI/FocusFlow-PC</projectUrl>
    <licenseUrl>https://github.com/TITANICBHAI/FocusFlow-PC/blob/main/LICENSE</licenseUrl>
    <requireLicenseAcceptance>false</requireLicenseAcceptance>
    <tags>productivity focus blocker pomodoro windows</tags>
    <summary>Hard-enforcement focus app for Windows. Real Win32 blocking — no soft timers.</summary>
    <description>
FocusFlow is a deep-focus enforcement tool for Windows. Unlike simple timers, it uses
Win32 APIs to forcibly kill blocked apps, block websites via hosts file modification,
and optionally enter Focus Launcher (kiosk mode). Features: Pomodoro, habits, daily
app allowances, weekly reports, PIN-gated breaks, and Sound Aversion mode.
    </description>
  </metadata>
</package>
```

**`tools/chocolateyInstall.ps1`**
```powershell
$ErrorActionPreference = 'Stop'
$packageName = 'focusflow'
$installerType = 'exe'
$url64 = 'https://github.com/TITANICBHAI/FocusFlow-PC/releases/download/v1.0.6/FocusFlow-1.0.6.exe'
$checksum64 = '<SHA256_OF_EXE>'
$checksumType64 = 'sha256'
$silentArgs = '/S'

Install-ChocolateyPackage $packageName $installerType $silentArgs $url64 `
  -Checksum64 $checksum64 -ChecksumType64 $checksumType64
```

4. Pack and push:
   ```powershell
   choco pack focusflow.nuspec
   choco push focusflow.1.0.6.nupkg --source https://push.chocolatey.org --api-key <YOUR_API_KEY>
   ```
5. Moderation review: typically 1–5 business days.

---

## 4. Scoop

Lightweight, developer-friendly package manager — `scoop install focusflow`. No moderation, instant.

### Option A — Host your own bucket (recommended, immediate)
1. Create a new GitHub repo: `scoop-tbtechs` (or `scoop-focusflow`)
2. Add a `bucket/` folder with `focusflow.json`:

```json
{
  "version": "1.0.6",
  "description": "Hard-enforcement focus & app blocker for Windows. Real Win32 blocking.",
  "homepage": "https://github.com/TITANICBHAI/FocusFlow-PC",
  "license": "See LICENSE",
  "architecture": {
    "64bit": {
      "url": "https://github.com/TITANICBHAI/FocusFlow-PC/releases/download/v1.0.6/FocusFlow-1.0.6.exe",
      "hash": "<SHA256_OF_EXE>"
    }
  },
  "installer": {
    "script": "Start-Process -FilePath \"$dir\\FocusFlow-1.0.6.exe\" -ArgumentList '/S' -Wait"
  },
  "checkver": {
    "github": "https://github.com/TITANICBHAI/FocusFlow-PC"
  },
  "autoupdate": {
    "architecture": {
      "64bit": {
        "url": "https://github.com/TITANICBHAI/FocusFlow-PC/releases/download/v$version/FocusFlow-$version.exe"
      }
    }
  }
}
```

3. Users install with:
   ```powershell
   scoop bucket add tbtechs https://github.com/TITANICBHAI/scoop-tbtechs
   scoop install focusflow
   ```

### Option B — Submit to `scoop-extras` (broader reach, takes time)
- PR to `https://github.com/ScoopInstaller/Extras` with the same JSON above.

---

## 5. Softpedia

One of the largest Windows software directories. Good for SEO and organic discovery.

### Submit at
`https://www.softpedia.com/submitsoft/`

### Fields (copy-paste)

| Field | Value |
|---|---|
| Program Name | `FocusFlow` |
| Version | `1.0.6` |
| Category | `Desktop Enhancements > Other Desktop Enhancements` |
| OS | `Windows 10, Windows 11` |
| License | *(match your LICENSE file)* |
| Download URL | GitHub Releases URL |
| Developer | `TBTechs` |
| Homepage | `https://tbtechs.app` |

**Description (500 chars)**
```
FocusFlow is a hard-enforcement productivity app for Windows that uses Win32 APIs to 
forcibly block distracting apps and websites the moment they open. Includes Focus Launcher 
(kiosk mode), Pomodoro timer, daily app allowances, habit tracker, PIN-gated breaks, 
Sound Aversion mode, and weekly focus reports. No soft timers — real enforcement.
```

---

## 6. SourceForge

Classic hosting platform with strong search presence and a built-in download mirror network.

### Steps
1. Create project at `https://sourceforge.net/projects/`
2. Project name: `focusflow`
3. Upload your `.exe` and `.msi` to Files section
4. Set a default download (the `.exe`)

**Project description (copy-paste)**
```
FocusFlow is a deep-focus enforcement tool for Windows. Unlike timer apps that just 
suggest breaks, FocusFlow uses low-level Win32 APIs to HARD-BLOCK distraction apps 
the instant they open — no bypass possible during a session.

Features:
• App Blocker — instant process kill via WinEventHook (0ms detection)
• Focus Launcher — kiosk mode: hides taskbar, suppresses Win key / Alt+Tab
• Nuclear Mode — blocks Task Manager, PowerShell, and 30+ escape routes
• Website Blocking — modifies the Windows hosts file
• Pomodoro Timer — enforced work/break cycles with PIN-gated early exit
• Daily App Allowances — set time budgets per app, auto-blocked at midnight
• Habit Tracker — streaks + daily completion
• Weekly Reports — focus time, temptation count, session history
• Sound Aversion — plays an unpleasant tone when a blocked app is opened
```

---

## 7. AlternativeTo

Discoverable by people searching for alternatives to Cold Turkey, Freedom, RescueTime, etc.

### Submit at
`https://alternativeto.net/software/add/`

| Field | Value |
|---|---|
| Name | `FocusFlow` |
| URL | `https://github.com/TITANICBHAI/FocusFlow-PC` |
| License | *(your license)* |
| Platform | `Windows` |
| Category | `Productivity` |

**Tagline**
```
Hard-enforcement Windows focus app — kills blocked apps instantly, no bypass possible.
```

**Listed as alternative to:** Cold Turkey, Freedom, RescueTime, FocusMe, Forest

---

## 8. Itch.io *(optional — unusual but growing for productivity tools)*

### Steps
1. Create account at `https://itch.io`
2. New project → kind: `Downloadable`
3. Upload `.exe` installer
4. Pricing: Free or Pay-what-you-want
5. Tags: `productivity`, `windows`, `focus`, `utility`

**Short description**
```
A hard-enforcement focus tool for Windows. Kills distraction apps the instant they open 
using Win32 APIs. Kiosk mode, Pomodoro, daily allowances, habit tracking.
```

---

## Maintenance Checklist (on every new version)

- [ ] Build new `.exe` and `.msi` with `gradle packageExe` + `gradle packageMsi`
- [ ] Generate SHA256 checksums for both files
- [ ] Create GitHub Release and upload both binaries + release notes
- [ ] Update winget manifest PR (bump `PackageVersion` + `InstallerUrl` + `InstallerSha256`)
- [ ] Update Chocolatey package (bump version + hash, `choco push`)
- [ ] Update Scoop bucket JSON (bump version + hash, commit to bucket repo)
- [ ] Update Softpedia / SourceForge listings with new version number
- [ ] Update `version` in `build.gradle.kts` and `replit.md`

---

## SHA256 Checksum Commands

```bash
# Windows (PowerShell)
Get-FileHash FocusFlow-1.0.6.exe -Algorithm SHA256

# Windows (CMD)
certutil -hashfile FocusFlow-1.0.6.exe SHA256

# Linux / macOS
sha256sum FocusFlow-1.0.6.exe
```

---

*Last updated: June 2025 · v1.0.6*
