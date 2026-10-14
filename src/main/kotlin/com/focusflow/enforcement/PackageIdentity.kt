package com.focusflow.enforcement

import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

data class PackageIdentity(
    val packageFullName: String?,
    val packageFamilyName: String?,
    val aumid: String?
)

internal interface PackageIdentityApi {
    fun packageFullName(length: IntByReference, value: CharArray?): Int
    fun packageFamilyName(length: IntByReference, value: CharArray?): Int
    fun applicationUserModelId(length: IntByReference, value: CharArray?): Int
    fun packagePath(length: IntByReference, value: CharArray?): Int
}

internal class PackageIdentityResolver(private val api: PackageIdentityApi) {
    fun read(): PackageIdentity? {
        val fullName = readString(api::packageFullName)
        val familyName = readString(api::packageFamilyName)
        val nativeAumid = readString(api::applicationUserModelId)
        val aumid = nativeAumid ?: resolveAumidFromManifest(familyName)
        return if (fullName == null && familyName == null && aumid == null) {
            null
        } else {
            PackageIdentity(fullName, familyName, aumid)
        }
    }

    private fun resolveAumidFromManifest(familyName: String?): String? {
        if (familyName.isNullOrBlank()) return null
        val packagePath = readString(api::packagePath) ?: return null
        val manifest = File(packagePath, "AppxManifest.xml")
        val text = runCatching { manifest.readText() }.getOrNull() ?: return null
        return parseManifestAumid(familyName, text)
    }

    private fun readString(read: (IntByReference, CharArray?) -> Int): String? {
        val length = IntByReference(0)
        val firstResult = runCatching { read(length, null) }.getOrNull() ?: return null
        if (firstResult == APPMODEL_ERROR_NO_PACKAGE) return null
        if (firstResult == ERROR_SUCCESS && length.value == 0) return null
        if (firstResult != ERROR_INSUFFICIENT_BUFFER || length.value <= 0) return null

        val buffer = CharArray(length.value)
        val result = runCatching { read(length, buffer) }.getOrNull() ?: return null
        if (result != ERROR_SUCCESS) return null
        return String(buffer).substringBefore('\u0000').takeIf(String::isNotBlank)
    }

    companion object {
        private const val ERROR_SUCCESS = 0
        private const val ERROR_INSUFFICIENT_BUFFER = 122
        private const val APPMODEL_ERROR_NO_PACKAGE = 15700

        internal fun parseManifestAumid(familyName: String, manifestText: String): String? {
            if (familyName.isBlank()) return null
            return runCatching {
                val factory = DocumentBuilderFactory.newInstance().apply {
                    isXIncludeAware = false
                    isExpandEntityReferences = false
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                }
                val document = factory.newDocumentBuilder().parse(InputSource(StringReader(manifestText)))
                val applicationId = document.getElementsByTagName("Application")
                    .item(0)?.attributes?.getNamedItem("Id")?.nodeValue
                    ?.takeIf(String::isNotBlank)
                applicationId?.let { "$familyName!$it" }
            }.getOrNull()
        }
    }
}

private interface WindowsPackageApi : StdCallLibrary {
    fun GetCurrentPackageFullName(length: IntByReference, value: CharArray?): Int
    fun GetCurrentPackageFamilyName(length: IntByReference, value: CharArray?): Int
    fun GetCurrentApplicationUserModelId(length: IntByReference, value: CharArray?): Int
    fun GetCurrentPackagePath(length: IntByReference, value: CharArray?): Int

    companion object {
        val INSTANCE: WindowsPackageApi =
            Native.load("kernel32", WindowsPackageApi::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

internal object CurrentPackageIdentity {
    val value: PackageIdentity? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (!isWindows) {
            null
        } else {
            runCatching {
                val native = WindowsPackageApi.INSTANCE
                PackageIdentityResolver(object : PackageIdentityApi {
                    override fun packageFullName(length: IntByReference, value: CharArray?) =
                        native.GetCurrentPackageFullName(length, value)

                    override fun packageFamilyName(length: IntByReference, value: CharArray?) =
                        native.GetCurrentPackageFamilyName(length, value)

                    override fun applicationUserModelId(length: IntByReference, value: CharArray?) =
                        native.GetCurrentApplicationUserModelId(length, value)

                    override fun packagePath(length: IntByReference, value: CharArray?) =
                        native.GetCurrentPackagePath(length, value)
                }).read()
            }.getOrNull()
        }
    }
}
