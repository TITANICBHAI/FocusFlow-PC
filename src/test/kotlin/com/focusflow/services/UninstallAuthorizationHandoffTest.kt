package com.focusflow.services

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

class UninstallAuthorizationHandoffTest {
    private var now = 1_000_000L
    private lateinit var dir: java.io.File

    @BeforeEach
    fun setUp() {
        dir = Files.createTempDirectory("ff-handoff").toFile()
        UninstallAuthorizationHandoff.fileForTest = java.io.File(dir, "uninstall-authorization")
        UninstallAuthorizationHandoff.nowForTest = { now }
    }

    @AfterEach
    fun tearDown() {
        UninstallAuthorizationHandoff.resetForTest()
        dir.deleteRecursively()
    }

    @Test
    fun noGrantMeansNotAuthorized() {
        assertFalse(UninstallAuthorizationHandoff.isGranted())
    }

    @Test
    fun aGrantIsValidUntilItExpires() {
        assertTrue(UninstallAuthorizationHandoff.grant())
        assertTrue(UninstallAuthorizationHandoff.isGranted())

        now += UninstallAuthorizationHandoff.WINDOW_MS - 1
        assertTrue(UninstallAuthorizationHandoff.isGranted())

        now += 2
        assertFalse(UninstallAuthorizationHandoff.isGranted())
    }

    @Test
    fun aHandMadeFileCannotGrantALongLivedBypass() {
        UninstallAuthorizationHandoff.fileForTest!!.writeText("expiresAtMs=${now + 24L * 60 * 60 * 1000}\n")
        assertFalse(UninstallAuthorizationHandoff.isGranted())
    }

    @Test
    fun aMalformedFileGrantsNothing() {
        UninstallAuthorizationHandoff.fileForTest!!.writeText("garbage")
        assertFalse(UninstallAuthorizationHandoff.isGranted())
    }

    @Test
    fun clearRemovesTheGrant() {
        UninstallAuthorizationHandoff.grant()
        UninstallAuthorizationHandoff.clear()
        assertFalse(UninstallAuthorizationHandoff.isGranted())
    }
}
