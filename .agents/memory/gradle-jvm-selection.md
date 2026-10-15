---
name: Gradle JVM selection
description: The Gradle runtime JVM can differ from JAVA_HOME in this workspace.
---

In this workspace, `JAVA_HOME`, the Gradle launcher/daemon, the test worker, and a `JavaExec` application process can select different JVMs. A test worker and the launched desktop app have each differed from the shell's configured `JAVA_HOME`.

**Why:** Relying on `JAVA_HOME` or the Kotlin toolchain declaration alone could misstate which JVM actually ran a test or application capture.

**How to apply:** Record `java -version` and `gradle --version` separately; for task-specific claims, inspect the test worker or application process itself rather than assuming which JVM Gradle selected.
