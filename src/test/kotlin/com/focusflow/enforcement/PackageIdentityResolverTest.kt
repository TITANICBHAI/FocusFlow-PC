package com.focusflow.enforcement

import com.sun.jna.ptr.IntByReference
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import org.junit.jupiter.api.io.TempDir

class PackageIdentityResolverTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun readsPackageStringsUsingWindowsLengthThenBufferCalls() {
        val api = FakePackageApi(
            fullName = "FocusFlow_1.0.0.0_x64__abcd",
            familyName = "FocusFlow_abcd",
            aumid = "FocusFlow_abcd!App"
        )

        val identity = assertNotNull(PackageIdentityResolver(api).read())

        assertEquals("FocusFlow_1.0.0.0_x64__abcd", identity.packageFullName)
        assertEquals("FocusFlow_abcd", identity.packageFamilyName)
        assertEquals("FocusFlow_abcd!App", identity.aumid)
        assertEquals(2, api.fullNameCalls)
        assertEquals(2, api.familyNameCalls)
        assertEquals(2, api.aumidCalls)
    }

    @Test
    fun resolvesAumidFromPackagedManifestWhenNativeAumidIsUnavailable() {
        val packagePath = tempDir.toFile()
        File(packagePath, "AppxManifest.xml").writeText(
            """<Package><Applications><Application Id="FocusFlowApp" /></Applications></Package>"""
        )
        val api = FakePackageApi(
            familyName = "FocusFlow_abcd",
            packagePath = packagePath.absolutePath
        )

        assertEquals(
            "FocusFlow_abcd!FocusFlowApp",
            PackageIdentityResolver(api).read()?.aumid
        )
    }

    @Test
    fun noPackageIdentityReturnsNull() {
        assertNull(PackageIdentityResolver(FakePackageApi()).read())
    }

    private class FakePackageApi(
        private val fullName: String? = null,
        private val familyName: String? = null,
        private val aumid: String? = null,
        private val packagePath: String? = null
    ) : PackageIdentityApi {
        var fullNameCalls = 0
        var familyNameCalls = 0
        var aumidCalls = 0

        override fun packageFullName(length: IntByReference, value: CharArray?): Int {
            fullNameCalls++
            return supply(fullName, length, value)
        }

        override fun packageFamilyName(length: IntByReference, value: CharArray?): Int {
            familyNameCalls++
            return supply(familyName, length, value)
        }

        override fun applicationUserModelId(length: IntByReference, value: CharArray?): Int {
            aumidCalls++
            return supply(aumid, length, value)
        }

        override fun packagePath(length: IntByReference, value: CharArray?): Int =
            supply(packagePath, length, value)

        private fun supply(text: String?, length: IntByReference, value: CharArray?): Int {
            if (text == null) return APPMODEL_ERROR_NO_PACKAGE
            val required = text.length + 1
            if (value == null || value.size < required) {
                length.value = required
                return ERROR_INSUFFICIENT_BUFFER
            }
            text.toCharArray().copyInto(value)
            value[text.length] = '\u0000'
            length.value = required
            return ERROR_SUCCESS
        }
    }

    private companion object {
        const val ERROR_SUCCESS = 0
        const val ERROR_INSUFFICIENT_BUFFER = 122
        const val APPMODEL_ERROR_NO_PACKAGE = 15700
    }
}
