read this md and  after renaming it, put it into   folder

for tracker-
At the start of a batch, mark it in progress in its notes. After each meaningful change, update the relevant notes rather than waiting until the whole project is finished.
Tick each checkbox as soon as its work is complete and verified. Never mark unverified, partially completed, or merely attempted work as complete.
For every completed batch, record evidence: changed files, the exact verification performed, its result. Record decisions, failures, blockers, and deferred work as well.
If a check fails, keep the affected completion item unchecked, record the observed failure, and document the fix and successful rerun before ticking it.
Do not erase failed attempts or blockers from the record; add the outcome so another agent can follow the history.
Before finishing, reconcile the tracker with the actual code and test evidence. Leave incomplete items unchecked and say why
and make a agent pre prompt so the new agent can get context by reading these and have some rules too-
Work alone. Do not create, invoke, or delegate to sub-agents.
Follow the plan's implementation order and the tracker. Do not silently widen scope or change the plan's contract.
Before editing, inspect relevant current code, project instructions, and the working tree. Preserve unrelated user changes and user data.
Respect the plan's no-touch list unless the user explicitly changes the scope.
Treat task replacement and any other deletion of user data as destructive. Keep replacement disabled by default, preserve explicit confirmation and required safety guards, and do not perform destructive actions without the user's authorization.
run Gradle tests, or Android tests on Replit by using current workflow or scripts under scripts folder. If verification is needed, use GitHub Actions only when the user explicitly requests apk building verification. Otherwise state clearly that those checks were not run.
Do not push to GitHub or start, poll, or monitor GitHub Actions unless the user explicitly asks.
Do not expose, copy into files, or print secrets or credentials.
