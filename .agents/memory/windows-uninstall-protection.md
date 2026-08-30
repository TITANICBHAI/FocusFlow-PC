---
name: Windows uninstall protection and Store packaging
description: MSIX package removal is controlled by Windows; direct installers can guard normal uninstall flows but cannot defeat an administrator.
---

Microsoft Store/MSIX distribution must preserve the Windows-managed package lifecycle; an app should not promise to veto package removal during an active session. A direct EXE/MSI channel can add a session-aware custom-uninstaller guard, but this remains a normal-path friction layer rather than absolute protection against administrators, offline removal, or recovery tools.

**Why:** Public FocusMe documentation and Reddit reports indicate that protected focus sessions can block ordinary uninstall/settings paths, while Windows and third-party administrative tools still provide escape routes. Microsoft Store policy also requires products not to compromise device security or functionality.

**How to apply:** Keep Store/MSIX and direct EXE/MSI as separate distribution channels. Treat any direct-channel uninstall guard as optional, explicit, reversible, and fail-open during crashes; do not use it as a reason to claim MSIX itself is uninstall-proof.