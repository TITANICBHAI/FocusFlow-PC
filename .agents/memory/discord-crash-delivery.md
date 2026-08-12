---
name: Discord crash delivery
description: Fatal crash telemetry must complete its bounded webhook request before JVM exit.
---

Crash reports must post synchronously with a short timeout from the uncaught-exception handler; daemon workers can be terminated as soon as the handler returns.

**Why:** A fatal JVM exits before a daemon telemetry thread is guaranteed to open its connection, causing local crash logs to exist while Discord receives nothing.

**How to apply:** Keep non-fatal telemetry asynchronous, but route fatal reports through the shared bounded HTTP client and never let delivery errors escape the crash handler.