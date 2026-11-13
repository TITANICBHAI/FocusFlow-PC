import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.compose") version "2.2.21"
    id("org.jetbrains.compose") version "1.9.3"
}

group = "com.focusflow"
version = file("../gradle.properties").useLines { lines ->
    lines.first { it.startsWith("appVersion=") }.substringAfter("=").trim()
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
}

compose.desktop {
    application {
        mainClass = "com.focusflow.recovery.MainKt"

        jvmArgs += listOf(
            "-Xms32m",
            "-Xmx128m",
            "-XX:+UseG1GC",
            "-Dfocusflow.recovery.version=${project.version}",
            "-Dfile.encoding=UTF-8",
            "-Djava.awt.headless=false",
            "-Dskiko.renderApi=SOFTWARE"
        )

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)

            packageName        = "FocusFlow-Recovery"
            packageVersion     = project.version.toString()
            description        = "FocusFlow Emergency Recovery Tool — restores taskbar and clears all enforcement flags"
            vendor             = "TBTechs"
            copyright          = "© 2025 TBTechs"

            modules(
                "java.base",
                "java.desktop",
                "java.logging",
                "java.management",
                "java.naming",
                "java.net.http",
                "java.sql",
                "jdk.crypto.ec",
                "jdk.unsupported"
            )

            windows {
                iconFile.set(project.file("../src/main/resources/focusflow.ico"))
                menuGroup      = "FocusFlow"
                shortcut       = false
                dirChooser     = false
                perUserInstall = false
                upgradeUuid    = "A1B2C3D4-5E6F-7890-ABCD-EF1234567890"
            }
        }
    }
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// ── dist task — copy the packaged EXE + README into /dist/recovery-tool/ ─────
//
// Usage:  ./gradlew :recovery:dist
//
// Depends on packageExe so the EXE is always rebuilt before copying.
// Output structure:
//   dist/recovery-tool/
//     FocusFlow-Recovery-<version>.exe   ← self-contained, no install needed
//     README.txt                         ← plain-text instructions
//
val distDir = rootProject.layout.projectDirectory.dir("dist/recovery-tool")

tasks.register<Copy>("dist") {
    dependsOn("packageExe")

    description = "Copies the recovery EXE and README into dist/recovery-tool/ for USB distribution."
    group       = "distribution"

    // Grab the EXE produced by jpackage
    from(layout.buildDirectory.dir("compose/binaries/main/exe")) {
        include("*.exe")
    }

    // Copy the plain-text README alongside it
    from(layout.projectDirectory.file("README.txt"))

    into(distDir)

    doLast {
        println("")
        println("✔  Recovery tool ready at: ${distDir.asFile.absolutePath}")
        println("   Drop the entire recovery-tool/ folder onto a USB drive.")
        println("")
    }
}
