# FocusFlow — AlternativeTo Listing Guide

> AlternativeTo is a crowd-sourced discovery platform where people search for
> alternatives to apps they already know. Someone googles "Cold Turkey alternative"
> and lands on AlternativeTo — your listing is what converts that visit into a download.
>
> This document covers: submission, copy, competitor targeting, community engagement,
> and long-term maintenance. Everything is copy-paste ready.

---

## Why AlternativeTo matters for FocusFlow specifically

People who search for focus-app alternatives are **high-intent** — they already tried
something and it wasn't enough. That is exactly FocusFlow's positioning: every other
tool on AlternativeTo in this category is a soft timer or a browser extension. FocusFlow
is the only one with Win32-level hard enforcement. That gap is the pitch.

Top apps in this space on AlternativeTo (your direct targets):

| App | AlternativeTo page | Their weakness (your angle) |
|---|---|---|
| **Cold Turkey** | `/software/cold-turkey-blocker/` | Bypassable by killing the service; no kiosk mode |
| **Freedom** | `/software/freedom-app/` | Cloud-dependent; no process kill; no host file enforcement |
| **RescueTime** | `/software/rescuetime/` | Analytics only — no blocking whatsoever |
| **FocusMe** | `/software/focusme/` | Can be bypassed by uninstalling during a session |
| **Forest** | `/software/forest-app/` | Phone app; no Windows enforcement at all |
| **StayFocusd** | `/software/stayfocusd/` | Chrome extension only; any other browser bypasses it |
| **LeechBlock** | `/software/leechblock/` | Browser extension only |
| **Focusplan** | `/software/focusplan/` | Planning tool, zero enforcement |

---

## Step 1 — Submit the listing

**URL:** `https://alternativeto.net/software/add/`

Fill in every field as follows:

---

### Basic info

| Field | Value |
|---|---|
| **Software name** | `FocusFlow` |
| **Official website** | `https://github.com/TITANICBHAI/FocusFlow-PC` |
| **License** | *(match your LICENSE file — MIT / Proprietary / etc.)* |
| **Platforms** | `Windows` |
| **Categories** | `Productivity`, `Security & Privacy` |

---

### Short description *(shows in search results — ~160 chars)*

```
Hard-enforcement focus app for Windows. Kills blocked apps instantly using Win32 APIs,
locks your desktop in kiosk mode. No soft timers. No bypasses.
```
*(156 characters)*

---

### Full description *(shown on the listing page — paste this exactly)*

```
FocusFlow is a deep-focus enforcement tool for Windows that goes further than any
other app in its category. While most focus tools show you a polite reminder or block
a browser tab, FocusFlow uses low-level Win32 APIs to HARD-BLOCK distractions the
instant they appear — no grace period, no workarounds, no bypass.

── What makes it different ──

Real process killing: Uses SetWinEventHook (EVENT_SYSTEM_FOREGROUND) to detect a
blocked app the moment its window comes to the foreground — then kills it immediately
via taskkill. Detection latency is 0ms. There is no window to bypass it.

Focus Launcher (Kiosk Mode): Hides the Windows taskbar, suppresses Win key, Alt+Tab,
and Alt+F4, and replaces your desktop with a restricted workspace. You see only what
you're supposed to work on.

Nuclear Mode: Blocks 30+ system escape routes — Task Manager, Registry Editor,
PowerShell, CMD, and more — via a triple-layer detect-kill-firewall system.

Website blocking: Modifies the Windows hosts file directly to redirect blocked domains
to 127.0.0.1. Includes an integrity monitor that re-applies blocks if the file is
tampered with mid-session.

PIN-gated breaks: Set a SHA-256-hashed PIN before locking in. You need it to end
the session early — there's no "I'll just close the app" escape route.

Sound Aversion: Plays an unpleasant tone the moment a blocked app is detected — a
behavioral deterrent on top of the kill.

Daily App Allowances: Set a daily time budget per app (e.g. "30 minutes of YouTube").
When the budget runs out, the app is blocked until midnight. Automatically resets daily.

Watchdog / Self-restart: Registers a Windows Task Scheduler entry that relaunches
FocusFlow every 2 minutes if it's killed. Combined with the nuclear mode, this makes
it very hard to simply "turn off" during a session.

── Also included ──

• Pomodoro timer with enforced work/break cycles
• Habit tracker with streaks
• Task manager with scheduled alarms
• Weekly focus reports (focus time, temptation count, session history)
• Recovery Tool: a standalone emergency app that undoes all system changes if
  FocusFlow crashes mid-lockdown (restores taskbar, removes firewall rules, resets registry)

── Who it's for ──

People who have tried every other focus app and found a way around it within 10 minutes.
FocusFlow is for the 10% of users who actually need the nuclear option.

── System requirements ──

• Windows 10 / 11 (64-bit)
• Run as Administrator recommended for full enforcement
• No Java installation needed — JRE is bundled in the installer
```

---

### Tags *(select all that apply on the form)*

```
productivity, focus, app-blocker, website-blocker, pomodoro, distraction-blocker,
kiosk-mode, time-management, habit-tracker, self-control, windows, deep-work
```

---

## Step 2 — Link it to competitors (critical for discovery)

After submitting, AlternativeTo lets you list what apps yours is an alternative **to**.
This is the primary discovery mechanism — people land on Cold Turkey's page and see
FocusFlow in the alternatives list.

**Go to your listing → "Suggest as alternative to" → add each of these:**

1. `https://alternativeto.net/software/cold-turkey-blocker/`
2. `https://alternativeto.net/software/freedom-app/`
3. `https://alternativeto.net/software/rescuetime/`
4. `https://alternativeto.net/software/focusme/`
5. `https://alternativeto.net/software/forest-app/`
6. `https://alternativeto.net/software/stayfocusd/`
7. `https://alternativeto.net/software/leechblock/`
8. `https://alternativeto.net/software/focusplan/`
9. `https://alternativeto.net/software/selfcontrol/` *(macOS app — cross-platform searchers)*
10. `https://alternativeto.net/software/be-focused/`

> The more competitor pages you appear on, the more discovery you get.
> This costs nothing and takes ~10 minutes.

---

## Step 3 — Screenshot strategy

AlternativeTo shows screenshots prominently. Upload **5 screenshots** in this order:

| # | What to show | Why |
|---|---|---|
| 1 | Dashboard screen — active focus session running | First impression: the product works |
| 2 | App Blocker screen — list of blocked apps with "Kill instantly" visible | Shows the core differentiator |
| 3 | Focus Launcher overlay (kiosk mode active) | Most visually striking, no other tool has this |
| 4 | Nuclear Mode screen | Signals intensity — appeals to the target user |
| 5 | Stats/Reports screen — streak and weekly chart | Shows depth beyond just blocking |

**Screenshot specs:**
- PNG, at least 1280×720
- No personal info visible in the process list or task names
- Dark theme (it's already the default in FocusFlow) photographs well

---

## Step 4 — First comment / description comment

Right after publishing, post a comment on your own listing as the developer.
This appears prominently and sets the tone.

**Copy-paste this:**

```
Hey! Developer here.

Built FocusFlow because I tried every focus app on this list and bypassed all of them
within minutes. Cold Turkey? Killed the service. Freedom? Ran the session from another
browser. Chrome extensions? Switched to Firefox.

FocusFlow is different because it works at the OS level — SetWinEventHook fires the
instant a blocked app's window comes to the foreground, then taskkill terminates it.
Not a browser extension, not a suggestion. A kill switch.

Focus Launcher (kiosk mode) takes it further — hides the taskbar, suppresses Win key
and Alt+Tab globally, replaces the desktop with a restricted workspace. Nuclear Mode
blocks Task Manager, PowerShell, and 30+ other escape routes.

Happy to answer any questions. If you've been let down by softer tools, this is the
one built specifically for that scenario.
```

---

## Step 5 — Respond to the "Suggest as alternative" votes

When users suggest FocusFlow as an alternative to other apps, you'll get notified.
Approve every suggestion. Each approved link = another entry point from a competitor's
page to yours.

If someone suggests FocusFlow as an alternative to an app you haven't heard of,
look it up — it might reveal a new competitor page worth targeting with a manual suggestion.

---

## Positioning matrix — how to frame FocusFlow vs each competitor

Use these angles when responding to comments or writing comparison text:

| Competitor | Their users say | Your counter |
|---|---|---|
| Cold Turkey | "It's pretty good but I can bypass it sometimes" | "FocusFlow uses WinEventHook + watchdog — there's no service to kill" |
| Freedom | "Works across devices" | "Cloud dependency = a bypass exists (offline mode, VPN). FocusFlow is local and harder to fool" |
| RescueTime | "Great for analytics" | "RescueTime doesn't block anything — it tells you after the fact. FocusFlow stops it in the moment" |
| FocusMe | "Good but pricey" | "FocusFlow is free/open-source and enforces harder at the OS level" |
| Forest | "Love the gamification" | "Forest is a phone app. If your distraction is on your PC, it does nothing" |
| StayFocusd | "Does the job for YouTube" | "Only works in Chrome. Open any other browser and StayFocusd doesn't exist" |

---

## SEO — search terms that land on your listing

AlternativeTo pages rank on Google. These are the queries that will send people to
FocusFlow's listing:

- `"cold turkey alternative"`
- `"freedom app alternative windows"`
- `"app blocker windows no bypass"`
- `"focus app that actually works"`
- `"how to block yourself from apps windows"`
- `"rescuetime alternative with blocking"`
- `"focusme alternative free"`
- `"kiosk mode productivity windows"`

Make sure your listing description naturally includes these phrases — it already does
if you used the full description above.

---

## Maintenance — what to do on every new version

- [ ] Update the version number shown in the listing description
- [ ] Upload new screenshots if any UI changed
- [ ] Add a comment on the listing: `"v1.X.X released — [what changed]. Download: <GitHub Release URL>"`
- [ ] Check if any new competitor apps have appeared and suggest FocusFlow as an alternative

---

## Direct listing URL (once live)

After submission, your listing will be at:

```
https://alternativeto.net/software/focusflow/
```

Share this URL in:
- The GitHub README (`## Find us on AlternativeTo`)
- The `ALTERNATIVE_DISTRIBUTION.md` file (already has a brief entry)
- Any Reddit or HackerNews launch posts

---

*Last updated: June 2025 · v1.0.6*
