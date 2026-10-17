package com.focusflow.ui.screens

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FocusLauncherSetupLoaderTest {

    @Test
    fun eachIndependentLoaderFailureIsLoggedAndCompleted() = runTest {
        val loaderNames = listOf(
            "Installed app scan",
            "Saved app selection",
            "Launcher chain settings",
            "Saved launcher presets",
            "Launcher notice preferences"
        )

        loaderNames.forEach { loaderName ->
            var loggedName: String? = null
            var loggedFailure: Throwable? = null
            var shownFailure: Throwable? = null
            var completed = false

            runLauncherSetupLoader(
                loaderName = loaderName,
                load = { throw IllegalStateException("$loaderName unavailable") },
                onSuccess = { error("A failed loader must not report success") },
                onFailure = { shownFailure = it },
                onComplete = { completed = true },
                logFailure = { name, failure ->
                    loggedName = name
                    loggedFailure = failure
                }
            )

            assertEquals(loaderName, loggedName)
            val actualLoggedFailure = assertNotNull(loggedFailure)
            assertEquals("$loaderName unavailable", actualLoggedFailure.message)
            assertEquals(actualLoggedFailure, shownFailure)
            assertTrue(completed, "$loaderName must always finish its loading state")
        }
    }

    @Test
    fun appScanFailureClearsLoadingAndRetryCanSucceed() = runTest {
        var attempt = 0
        var isLoading = false
        var errorMessage: String? = null
        var loadedApps = emptyList<String>()

        suspend fun scan() {
            isLoading = true
            runLauncherSetupLoader(
                loaderName = "Installed app scan",
                load = {
                    attempt++
                    if (attempt == 1) error("Registry scan unavailable")
                    listOf("editor.exe")
                },
                onSuccess = {
                    loadedApps = it
                    errorMessage = null
                },
                onFailure = { errorMessage = "Couldn't load installed apps. Retry the scan." },
                onComplete = { isLoading = false },
                logFailure = { _, _ -> }
            )
        }

        scan()
        assertFalse(isLoading)
        assertNotNull(errorMessage)
        assertTrue(loadedApps.isEmpty())

        scan()
        assertFalse(isLoading)
        assertNull(errorMessage)
        assertEquals(listOf("editor.exe"), loadedApps)
    }

    @Test
    fun slowPresetLoaderDoesNotKeepTheAppScanLoading() = runTest {
        val presetStarted = CompletableDeferred<Unit>()
        val releasePreset = CompletableDeferred<Unit>()
        var isLoading = true
        var appListVisible = false

        val presetJob = launch {
            runLauncherSetupLoader(
                loaderName = "Saved launcher presets",
                load = {
                    presetStarted.complete(Unit)
                    releasePreset.await()
                },
                onSuccess = {},
                onFailure = {},
                logFailure = { _, _ -> }
            )
        }
        val appJob = launch {
            runLauncherSetupLoader(
                loaderName = "Installed app scan",
                load = { listOf("editor.exe") },
                onSuccess = { appListVisible = true },
                onFailure = {},
                onComplete = { isLoading = false },
                logFailure = { _, _ -> }
            )
        }

        runCurrent()
        assertTrue(presetStarted.isCompleted)
        assertTrue(appListVisible)
        assertFalse(isLoading)

        releasePreset.complete(Unit)
        runCurrent()
        presetJob.join()
        appJob.join()
    }

    @Test
    fun cancellationPropagatesAndStillRunsCompletion() = runTest {
        var failureHandled = false
        var completed = false
        var cancellation: CancellationException? = null

        try {
            runLauncherSetupLoader(
                loaderName = "Installed app scan",
                load = { throw CancellationException("screen left") },
                onSuccess = { error("A cancelled loader must not report success") },
                onFailure = { failureHandled = true },
                onComplete = { completed = true },
                logFailure = { _, _ -> }
            )
        } catch (caught: CancellationException) {
            cancellation = caught
        }

        assertNotNull(cancellation)
        assertFalse(failureHandled, "Cancellation is not a loader failure")
        assertTrue(completed)
    }
}
