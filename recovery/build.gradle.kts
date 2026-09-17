import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "1.9.22"
    id("org.jetbrains.compose") version "1.6.1"
}

group = "com.focusflow"
version = "2.0.1"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")
}

compose.desktop {
    application {
        mainClass = "com.focusflow.recovery.MainKt"

        jvmArgs += listOf(
            "-Xms32m",
            "-Xmx128m",
            "-XX:+UseG1GC",
            "-Dfile.encoding=UTF-8",
            "-Djava.awt.headless=false",
            "-Dskiko.renderApi=SOFTWARE"
        )

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)

            packageName        = "FocusFlow-Recovery"
            packageVersion     = "2.0.1"
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
        languageVersion.set(JavaLanguageVersion.of(19))
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
