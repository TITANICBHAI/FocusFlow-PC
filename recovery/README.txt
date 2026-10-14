================================================================================
  FocusFlow Emergency Recovery Tool  v2.0.5
  by TBTechs  |  Last-resort escape hatch for locked-down machines
================================================================================

WHAT THIS TOOL DOES
-------------------
If FocusFlow crashed or left your PC in a locked state (hidden taskbar, blocked
websites, firewall rules still active), this tool fixes all of it in one click:

  1. Restores the Windows Taskbar (primary + secondary)
  2. Clears all FocusFlow enforcement flags in the database
     (crash guard, hard-lock, Nuclear Mode, Kill Switch state)
  3. Removes all FocusFlow_Block_* Windows Firewall rules
  4. Removes all FocusFlow entries from the hosts file
  5. Flushes the DNS cache so unblocked sites resolve immediately

A recovery log is written to your Desktop after each run
(FocusFlow-Recovery-<timestamp>.log) — useful for support requests.


HOW TO RUN
----------
Option A — Normal Windows (recommended):
  1. Double-click FocusFlow-Recovery-2.0.5.exe
  2. The app checks automatically whether it is running as Administrator.
     If not, click the amber "Relaunch as Administrator" button — this
     re-opens the tool with elevated rights in one click (no manual
     right-click needed).
  3. Click "Run Recovery Now".
  4. Click "Restart Windows Now" when the recovery completes.

Option B — From a USB Drive:
  Copy this entire folder (FocusFlow-Recovery-2.0.5.exe + README.txt) to
  a USB drive. Plug it into the affected machine and double-click the EXE.
  No installation required — the runtime is fully bundled.

Option C — Windows Safe Mode:
  1. Boot into Safe Mode (hold Shift → click Restart →
     Troubleshoot > Advanced options > Startup Settings > Restart,
     press F4 for Safe Mode).
  2. Plug in your USB drive.
  3. Open File Explorer, navigate to the USB drive, and double-click
     FocusFlow-Recovery-2.0.5.exe.
  4. Use "Relaunch as Administrator" if prompted, then click
     "Run Recovery Now".
  5. Restart your PC normally.


ADMIN RIGHTS
------------
Steps 3 (firewall rules) and 4 (hosts file) require Administrator rights.
The tool detects this automatically at startup and shows a warning if you
are not elevated. Click the amber "Relaunch as Administrator" button to
fix this in one click — no manual right-clicking needed.

If you see FAILED on the firewall or hosts steps, it means the tool was
not running as Administrator. Close it, use the button on the next run.


BEFORE YOU RUN — CHECKLIST
---------------------------
  ☐  Run as Administrator (use the in-app button if needed)
  ☐  Close FocusFlow if it is currently running
      (otherwise it may re-apply enforcement locks immediately after recovery)


WHAT GETS CHANGED
-----------------
  Database (~/.focusflow/focusflow.db):
    launcher_crash_guard       -> "false"
    launcher_hard_locked       -> "false"
    nuclear_mode               -> "false"
    killswitch_remaining_today -> "300"  (daily budget reset)
    killswitch_reset_date      -> ""

  Windows Firewall:
    All rules whose display name starts with "FocusFlow_Block_" are deleted.

  Hosts file (C:\Windows\System32\drivers\etc\hosts):
    All lines ending with "# FocusFlow" are removed.

  Taskbar:
    ShowWindow() is called on Shell_TrayWnd and Shell_SecondaryTrayWnd
    with SW_SHOW. This is a no-op if the taskbar was already visible.

  Recovery log (Desktop\FocusFlow-Recovery-<timestamp>.log):
    A plain-text log is created with timestamps for each step result,
    your OS version, and whether admin rights were active.

  Nothing else is touched. Your FocusFlow data, tasks, habits, and
  focus history are not affected.


AFTER RUNNING
-------------
You do not need to uninstall or reinstall FocusFlow. Once recovery is
complete, restart your PC (use the in-app "Restart Windows Now" button
or do it manually), then relaunch FocusFlow normally. All your data is
intact.

If an issue persists after running this tool, attach the recovery log
from your Desktop when contacting support — it contains the exact error
detail for any failed step.


SYSTEM REQUIREMENTS
-------------------
  - Windows 10 or Windows 11 (x64)
  - No Java installation needed (runtime is bundled)
  - ~150 MB free space to run (no install)


================================================================================
  FocusFlow  |  https://github.com/TBTechs/focusflow
================================================================================
