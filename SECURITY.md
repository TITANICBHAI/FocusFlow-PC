# Security Policy

FocusFlow runs on your PC and can close apps, edit the hosts file, add firewall rules and change a few Windows policies. Security reports are taken seriously.

## Report a vulnerability

Please **don't open a public issue** for security problems. Instead:

- use GitHub's private reporting: [Report a vulnerability](https://github.com/TITANICBHAI/FocusFlow-PC/security/advisories/new), or
- email **support@tbtechs.dev** with "SECURITY" in the subject.

Please include the FocusFlow version, how you installed it (Microsoft Store, EXE or MSI), what you found, and the steps to reproduce it. Every report gets read and answered as soon as possible. FocusFlow is a small independent project, so there is no formal response-time guarantee. Credit is given when the fix ships, if you want it.

## Supported versions

Security fixes go into the latest release, published through the Microsoft Store and [GitHub Releases](https://github.com/TITANICBHAI/FocusFlow-PC/releases). Please update before you report.

## What counts

**In scope**, for example:

- leaking or weakening PIN protection
- privilege escalation through FocusFlow's elevated actions
- tampering with FocusFlow's hosts-file, firewall, registry or scheduled-task changes
- unsafe handling of data in crash reports or feedback
- installer or uninstaller flaws

**Out of scope**, for example:

- a local Windows administrator removing or disabling FocusFlow. It adds friction against impulsive workarounds; it is not a security boundary.
- Ctrl+Alt+Del and Win+L, which Windows doesn't let any program intercept
- social engineering
