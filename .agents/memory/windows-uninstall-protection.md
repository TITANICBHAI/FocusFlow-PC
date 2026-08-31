---
name: Windows uninstall protection and Store packaging
description: MSIX package removal is controlled by Windows; direct installers can guard normal uninstall flows but cannot defeat an administrator.
---

Microsoft Store/MSIX distribution must preserve the Windows-managed package lifecycle; an app should not promise to veto package removal during an active session. A direct EXE/MSI channel can add a session-aware custom-uninstaller guard, but this remains a normal-path friction layer rather than absolute protection against administrators, offline removal, or recovery tools. The direct channel's uninstall entry can be wrapped by the installed app and hand the original Windows Installer command back after authorization.

**Why:** Public FocusMe documentation and Reddit reports indicate that protected focus sessions can block ordinary uninstall/settings paths, while Windows and third-party administrative tools still provide escape routes. Microsoft Store policy also requires products not to compromise device security or functionality.

**How to apply:** Keep Store/MSIX and direct EXE/MSI as separate distribution channels. Runtime package detection must exempt MSIX from quit gates, uninstaller-process blocking, and installer firewall rules. Direct-channel protection belongs on normal quit plus Nuclear Mode's common uninstaller process path; keep it optional, explicit, reversible, and fail-open during crashes. An active focus session may use the Session PIN for tray Quit, but permanent uninstall must use the Global PIN or wait for the session to end.