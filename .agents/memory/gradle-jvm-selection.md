---
name: Gradle JVM selection
description: The Gradle runtime JVM can differ from JAVA_HOME in this workspace.
---

When Phase 0 checked the workflow's GraalVM 19 `JAVA_HOME`, `java -version` reported GraalVM CE 19.0.2, while Gradle 8.14.2 reported launcher JVM 21.0.7 and daemon JVM 21.0.7+6. The root test worker was separately observed running GraalVM CE 22.3.1 / OpenJDK 19.0.2, matching the configured Kotlin toolchain.

**Why:** Relying on `JAVA_HOME` or the Kotlin toolchain declaration alone could misstate which JVM actually ran a Gradle task.

**How to apply:** For a baseline, record `java -version` and `gradle --version` separately, and only claim the test-worker JVM after observing the test process or its report.
