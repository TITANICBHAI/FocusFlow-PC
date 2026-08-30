---
name: Windows network enforcement boundaries
description: Durable limitations and reliability requirements for FocusFlow's Windows hosts and firewall enforcement.
---

Windows hosts-file blocking and per-process outbound firewall rules are useful enforcement layers, but neither is a browser-level URL blocker. Secure DNS/DoH, VPN routing, DNS/browser caches, and already-established connections can bypass or delay a domain cutoff. A browser extension is required for reliable active-tab URL, title, or page-description matching.

**Why:** The native desktop process can see foreground window titles and Windows process state, but it cannot inspect the browser's active document or force every browser DNS/connection path to honor a hosts entry.

**How to apply:** Require administrator elevation for configuration, verify firewall rule creation instead of assuming the PowerShell command succeeded, and describe the browser-level limitations in the UI. Treat VPN process blocking, hosts domain blocking, firewall keyword cutoffs, and regular process keyword blocking as separate layers.