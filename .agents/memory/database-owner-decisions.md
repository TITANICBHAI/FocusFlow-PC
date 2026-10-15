---
name: Database owner decisions
description: Owner-confirmed scope choices for FocusFlow's database reliability work.
---

- D1: Keep direct Discord delivery; the delivery-route decision is settled.
- D5/F1b: Leave SQLite DLL packaging as it is; do not add the optional install-directory packaging path.
- D7: Leave recurring-task duplicates as they are; do not reopen a delete option.
- D9: The owner says the approved backup/recovery work was already underway; verify current code rather than trusting stale tracker text.
- D12: Do not pursue PIN hashing/recovery redesign; the owner says it will not be done.
- D19: No database-threading restructure is needed; the decision is closed.

**Why:** The owner clarified these choices on 2026-10-15 because the tracker still showed some as open or deferred. A source review confirmed that D9's main runtime components are present even though older reports describe only the design.

**How to apply:** Treat these as settled when summarizing remaining work. Do not implement F1b or gate it on W1–W3; those Windows checks remain relevant to F1 itself. For D9, check implementation, automated tests, documentation, and Windows validation separately. If the tracker conflicts, rely on the owner's clarification and inspect the current source.
