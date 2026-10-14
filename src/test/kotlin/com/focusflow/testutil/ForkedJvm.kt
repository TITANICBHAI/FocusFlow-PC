package com.focusflow.testutil

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object ForkedJvm {
    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )

    fun run(
        mainClass: String,
        jvmArgs: List<String> = emptyList(),
        args: List<String> = emptyList(),
        appVersion: String? = "test",
        timeout: Duration = Duration.ofSeconds(60)
    ): Result {
        require(mainClass.isNotBlank()) { "mainClass must not be blank" }
        require(!timeout.isNegative && !timeout.isZero) { "timeout must be positive" }

        val home = Files.createTempDirectory("focusflow-forked-home-").toAbsolutePath()
        var child: Process? = null

        try {
            val javaName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                "java.exe"
            } else {
                "java"
            }
            val javaExecutable = Path.of(System.getProperty("java.home"), "bin", javaName)
            check(Files.isExecutable(javaExecutable)) { "Java executable is unavailable in java.home" }

            val classpath = System.getProperty("java.class.path")
                ?.takeIf(String::isNotBlank)
                ?: error("java.class.path is empty in the test worker")

            val command = buildList {
                add(javaExecutable.toString())
                addAll(jvmArgs)
                add("-Duser.home=${home}")
                appVersion?.let { add("-Dfocusflow.app.version=$it") }
                add("-Dfocusflow.telemetry=off")
                add("-cp")
                add(classpath)
                add(mainClass)
                addAll(args)
            }

            val process = ProcessBuilder(command).start()
            child = process

            val stdout = CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            }
            val stderr = CompletableFuture.supplyAsync {
                process.errorStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            }

            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor()
                stdout.get(5, TimeUnit.SECONDS)
                stderr.get(5, TimeUnit.SECONDS)
                throw TimeoutException("Forked JVM exceeded ${timeout.seconds} seconds")
            }

            return Result(
                exitCode = process.exitValue(),
                stdout = stdout.get(5, TimeUnit.SECONDS),
                stderr = stderr.get(5, TimeUnit.SECONDS)
            )
        } finally {
            child?.let { process ->
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor()
                }
                process.inputStream.close()
                process.errorStream.close()
                process.outputStream.close()
            }
            deleteScratchTree(home)
        }
    }
}
