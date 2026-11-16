package com.focusflow.enforcement

/**
 * Starts a helper process and records its identity in an [OwnedProcessRegistry] so enforcement can
 * recognise it as FocusFlow's own (see the registry for what "recognise" means and why).
 */
internal class OwnedProcessLauncher(val registry: OwnedProcessRegistry) {

    /**
     * @param expectedImage the exact executable the caller asked for (for a system tool, its full
     *   path in the Windows folder). The helper is recognised only while it runs that file.
     */
    fun start(builder: ProcessBuilder, expectedImage: String): Process {
        val token = registry.beginSpawn(expectedImage)
        val process = try {
            builder.start()
        } catch (failure: Throwable) {
            registry.abortSpawn(token)
            throw failure
        }
        val pid = process.pid()
        val startMs = try {
            process.toHandle().info().startInstant().map { it.toEpochMilli() }.orElse(null)
        } catch (_: Throwable) {
            null
        }
        registry.completeSpawn(token, pid, startMs)
        // Registered before this callback is attached, so a helper that ends at once is still released.
        process.onExit().whenComplete { _, _ -> registry.release(pid) }
        return process
    }
}

/** The process-wide launcher. Every helper FocusFlow starts for enforcement or setup work goes through it. */
internal object OwnedProcesses {
    val registry = OwnedProcessRegistry(ownPid = ProcessHandle.current().pid())
    private val launcher = OwnedProcessLauncher(registry)

    fun start(builder: ProcessBuilder, expectedImage: String): Process = launcher.start(builder, expectedImage)
}
