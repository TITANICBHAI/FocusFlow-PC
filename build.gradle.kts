import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "1.9.22"
    id("org.jetbrains.compose") version "1.6.1"
}

group = "com.focusflow"
version = "1.1.8"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")
}

compose.desktop {
    application {
        mainClass = "com.focusflow.MainKt"

        jvmArgs += listOf(
            "-Xms128m",
            "-Xmx1g",
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=50",
            "-XX:SoftRefLRUPolicyMSPerMB=50",
            "-Dfile.encoding=UTF-8",
            "-Djava.awt.headless=false",
            "-Dskiko.renderApi=SOFTWARE",
            // Required when running inside MSIX AppContainer: Java NIO Selectors using
            // epoll/kqueue fail in the sandboxed environment. PollSelectorProvider is the
            // correct fallback and avoids "No such file or directory" selector errors.
            "-Djava.nio.channels.spi.SelectorProvider=sun.nio.ch.PollSelectorProvider"
        )

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)

            packageName        = "FocusFlow"
            packageVersion     = "1.1.8"
            description        = "Focus & productivity app with real app blocking"
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
                iconFile.set(project.file("src/main/resources/focusflow.ico"))
                menuGroup     = "FocusFlow"
                shortcut      = true
                dirChooser    = true
                perUserInstall = true
                upgradeUuid   = "B4C3F3A2-8E41-4D9A-B7C6-D1E0F2A34B56"
            }
        }
    }
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(19))
    }
}
