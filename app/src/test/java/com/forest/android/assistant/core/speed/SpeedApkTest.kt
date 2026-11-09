package com.forest.android.assistant.core.speed

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

class SpeedApkTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun dexNumberingHandlesGapsAndExistingMultidex() {
        assertEquals("classes.dex", ApkRewriter.nextDexName(emptyList()))
        assertEquals("classes2.dex", ApkRewriter.nextDexName(listOf("classes.dex")))
        assertEquals("classes13.dex", ApkRewriter.nextDexName(listOf("classes.dex", "classes3.dex", "classes12.dex", "assets/classes99.dex")))
    }
    @Test fun signatureRemovalPreservesServiceAndKotlinMetadata() {
        assertTrue(ApkRewriter.isSignature("META-INF/CERT.RSA"))
        assertTrue(ApkRewriter.isSignature("META-INF/cert.sf"))
        assertTrue(ApkRewriter.isSignature("META-INF/MANIFEST.MF"))
        assertFalse(ApkRewriter.isSignature("META-INF/services/example.Service"))
        assertFalse(ApkRewriter.isSignature("META-INF/app.kotlin_module"))
    }
    @Test fun malformedBinaryManifestsAreRejected() {
        for (input in listOf(byteArrayOf(), ByteArray(8), "<manifest/>".toByteArray())) {
            assertThrows(IllegalArgumentException::class.java) { BinaryManifest.patch(input, "test.app", "test.host") }
        }
    }
    @Test fun patchedRealApkRetainsResourcesAlignmentAndVerifiableSignature() {
        val fixture = File(System.getProperty("speed.fixture.apk", "").orEmpty())
        assumeTrue("Payload APK is built by Gradle", fixture.isFile)
        val staged = temporary.newFile("staged.apk")
        ApkRewriter.rewrite(fixture, staged, mapOf("lib/arm64-v8a/libalignment.so" to { ByteArray(257) { 42 }.inputStream() }))
        val patched = temporary.newFile("patched.apk")
        ApkRewriter.rewrite(staged, patched, mapOf("assets/forest-speed/test" to { "payload".byteInputStream() })) {
            BinaryManifest.patch(it, "com.forest.android.speed.payload", "com.forest.android.assistant")
        }
        ZipFile(fixture).use { before -> ZipFile(patched).use { after ->
            before.getEntry("resources.arsc")?.let { entry ->
                assertArrayEquals(before.getInputStream(entry).readBytes(), after.getInputStream(after.getEntry("resources.arsc")).readBytes())
            }
            assertEquals("payload", after.getInputStream(after.getEntry("assets/forest-speed/test")).reader().readText())
        } }
        assertAlignment(patched)
        val keyFile = File(System.getProperty("user.home"), ".android/debug.keystore")
        assumeTrue(keyFile.isFile)
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { keyFile.inputStream().use { load(it, "android".toCharArray()) } }
        val signer = ApkSigner.SignerConfig.Builder("test", store.getKey("androiddebugkey", "android".toCharArray()) as PrivateKey,
            listOf(store.getCertificate("androiddebugkey") as X509Certificate)).build()
        val signed = temporary.newFile("signed.apk")
        ApkSigner.Builder(listOf(signer)).setInputApk(patched).setOutputApk(signed).setMinSdkVersion(29)
            .setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(true).setV4SigningEnabled(false).build().sign()
        val verification = ApkVerifier.Builder(signed).setMinCheckedPlatformVersion(29).build().verify()
        assertTrue(verification.errors.toString(), verification.isVerified)
        assertAlignment(signed)
        val sdk = File(System.getProperty("speed.android.sdk", "").orEmpty())
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
        val aapt = File(sdk, "build-tools/37.0.0/aapt2${if (windows) ".exe" else ""}")
        assumeTrue(aapt.isFile)
        val process = ProcessBuilder(aapt.path, "dump", "xmltree", signed.path, "--file", "AndroidManifest.xml").redirectErrorStream(true).start()
        val xml = process.inputStream.reader().readText()
        assertEquals(xml, 0, process.waitFor())
        assertTrue(xml, xml.contains(BinaryManifest.PROVIDER))
        assertTrue(xml, xml.contains("com.forest.android.speed.payload.forest.speed"))
        assertTrue(xml, xml.contains(BinaryManifest.PATCH_MARKER))
        assertTrue(xml, xml.contains("com.forest.android.assistant"))
        assertTrue(xml, xml.contains("2147483646") || xml.contains("0x7ffffffe"))
    }
    @Test fun existingQueriesPermissionsAndCodeDisabledManifestsRemainValid() {
        val sdk = File(System.getProperty("speed.android.sdk", "").orEmpty())
        val suffix = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) ".exe" else ""
        val aapt = File(sdk, "build-tools/37.0.0/aapt2$suffix")
        val platform = File(sdk, "platforms/android-37.0/android.jar")
        assumeTrue(aapt.isFile && platform.isFile)
        val manifest = temporary.newFile("AndroidManifest.xml").apply { writeText("""
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="fixture.app">
              <queries><package android:name="original.visible" /></queries>
              <application android:hasCode="false" android:permission="fixture.private" android:label="原来的应用">
                <provider android:name="original.Provider" android:authorities="fixture.original" android:exported="false" />
              </application>
            </manifest>
        """.trimIndent()) }
        fun run(vararg args: String): String {
            val process = ProcessBuilder(aapt.path, *args).redirectErrorStream(true).start()
            val output = process.inputStream.reader().readText()
            assertEquals(output, 0, process.waitFor()); return output
        }
        val original = File(temporary.root, "queries-original.apk")
        run("link", "--manifest", manifest.path, "-I", platform.path, "--min-sdk-version", "29", "--target-sdk-version", "37", "-o", original.path)
        val patched = File(temporary.root, "queries-patched.apk")
        val fixture = File(System.getProperty("speed.fixture.apk", "").orEmpty())
        val dex = ZipFile(fixture).use { zip -> zip.getInputStream(zip.getEntry("classes.dex")).readBytes() }
        ApkRewriter.rewrite(original, patched, mapOf("classes.dex" to { dex.inputStream() })) { BinaryManifest.patch(it, "fixture.app", "fixture.host") }
        val xml = run("dump", "xmltree", patched.path, "--file", "AndroidManifest.xml")
        assertTrue(xml, xml.contains("original.visible") && xml.contains("fixture.host"))
        assertTrue(xml, xml.contains("original.Provider") && xml.contains("fixture.original"))
        assertTrue(xml, xml.contains("fixture.private") && xml.contains("原来的应用"))
        assertTrue(xml, Regex("hasCode.*(?:true|0xffffffff)").containsMatchIn(xml))
        ZipFile(patched).use { zip -> assertEquals(java.util.zip.ZipEntry.STORED, zip.getEntry("classes.dex").method) }
        assertAlignment(patched)
    }
    private fun assertAlignment(apk: File) {
        val bytes = apk.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var position = 0
        while (position + 30 < bytes.size && buffer.getInt(position) == 0x04034b50) {
            val nameLength = buffer.getShort(position + 26).toInt() and 65535
            val extraLength = buffer.getShort(position + 28).toInt() and 65535
            val name = String(bytes, position + 30, nameLength, Charsets.UTF_8)
            val data = position + 30 + nameLength + extraLength
            if (name == "resources.arsc" || name.startsWith("lib/") || name.endsWith(".dex") && buffer.getShort(position + 8).toInt() == 0) {
                assertEquals(name, 0, buffer.getShort(position + 8).toInt())
                assertEquals(name, 0, data % if (name.startsWith("lib/")) 16384 else 4)
            }
            position = data + 1
            while (position + 4 < bytes.size && buffer.getInt(position) != 0x04034b50) position++
        }
    }
}
