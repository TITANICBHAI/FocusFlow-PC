# PIN policy — strengthening is easy, loosening is hard

**Read this before you touch any PIN gate, toggle, "add" button, "remove" button, or restore/backup path.**

FocusFlow is a *friction layer* that helps its owner resist a weak moment. It is not a security
product, and it must never lock the owner out of their own PC. The whole design follows one
asymmetry:

> **Anything that adds or strengthens protection is easy (no PIN).**
> **Anything that removes, disables, shortens, pauses or otherwise weakens protection is hard
> (Global PIN, and where configured the cool-off).**

The reasoning: a person in a weak moment wants to *stop* being blocked. They never want to add more
blocking. So a pinless "add" costs nothing, while a pinless "remove" defeats the whole app.

## The rule, applied

| Action | Gate |
|---|---|
| Add a keyword / preset / VPN process / network cut-off rule / block rule | **No PIN** |
| Turn a protection **ON** (keyword blocker, VPN Shield, re-enable a paused network rule) | **No PIN** |
| Merely *viewing* or expanding a list (e.g. the built-in VPN list) | **No PIN** |
| Remove a keyword / preset, Clear All, remove a VPN process, delete a network rule | **Global PIN** |
| Turn a protection **OFF** or pause a rule | **Global PIN** (+ cool-off if the owner enabled it) |
| Shorten / end a standalone block or session early, loosen an allowance | Existing gate — do not relax |
| Restore from backup | Never weakens protections: the strictest of "now" vs "backup" wins |

Current gate sites (keep in sync if you move them): `KeywordBlockerScreen.kt` and
`VpnNetworkScreen.kt` (`withPin` is used **only** for weakening actions),
`BlockDefenseScreen.kt`, `AppBlockerScreen.kt`, `AllowanceEditPolicy.kt`, `SettingsScreen.kt`,
`NuclearModeScreen.kt`.

## Rules for any agent working in this repo

1. **Never add a PIN to a strengthening action, and never remove or soften one from a weakening
   action.** If you are unsure which a change is, treat it as weakening and keep the PIN.
2. **Judge by effect, not by the button's label.** An "add" that can end up loosening something
   (for example one that overwrites or replaces an existing stricter rule, shortens a duration, or
   lowers a limit) is a *weakening* action and needs the PIN. Today's pinless adds are safe because
   each creates a new entry (new UUID) and cannot overwrite another.
3. **Do not add a back door.** No "Forgot PIN" reset inside a PIN dialog (`allowReset` stays
   false), no reset or loosening through restore/import, no bypass for sessions or the Focus
   Launcher.
4. **Never lock the owner out.** The PIN holder must still be able to loosen or end every
   protection through the UI; sessions and standalone blocks must still end by time; crash/shutdown
   must still restore OS state (see R6 in `design-notes-strong-model.md`).
5. **Gating is UI-layer today.** `VpnBlocker`, `Database`, etc. do not check the PIN themselves. A
   new code path that weakens protection (CLI, tray, import, scheduled job) must apply the same
   gate; do not assume the UI will.
6. **Do not claim more than it is.** Never write text saying FocusFlow is secure, unbreakable or
   tamper-proof.
7. **Tests guard this.** `PinOnlyForWeakeningSourceTest` fails if a PIN is added to an "add" action
   or removed from a "remove" action. Do not edit or delete it to make a change pass. If the policy
   must change, that is an owner decision: stop and ask.

## Related
- `Security/plan.md` rule 10 ("Never weaken anything") and the "do not fix" list.
- `Security/design-notes-strong-model.md` (R6 no lock-outs, `strictest` / `weakenings`, O7 cool-off).
- `src/main/kotlin/com/focusflow/services/LooseningCoolOff.kt`
