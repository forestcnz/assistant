package com.forest.android.assistant.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class UpdateTransferTest {
    @get:Rule val folder = TemporaryFolder()

    private val download = UpdateDownload("test.apk", UpdateManifest(
        versionCode = 2, versionName = "0.1.2", minSdk = 29, sizeBytes = 8,
        sha256 = "a".repeat(64), apkUrl = "https://github.com/update.apk",
    ))
    private val etag = "\"release\""
    private val partial get() = File(folder.root, download.partialName)
    private val target get() = File(folder.root, download.payloadName)

    @Test
    fun interruptedResponseIsResumedThroughRedirect() {
        val first = response(200, "abcd", "ETag" to etag, "Content-Length" to "8")
        assertThrows(IOException::class.java) { run(first) }
        assertEquals("abcd", partial.readText())
        assertFalse(target.exists())

        val redirect = response(302, "", "Location" to "https://release-assets.githubusercontent.com/update.apk")
        val resumed = response(206, "efgh", "ETag" to etag, "Content-Range" to "bytes 4-7/8")
        run(redirect, resumed)

        for (request in listOf(redirect, resumed)) {
            assertEquals("bytes=4-", request.getRequestProperty("Range"))
            assertEquals(etag, request.getRequestProperty("If-Range"))
        }
        assertEquals("abcdefgh", target.readText())
        assertFalse(partial.exists())
    }

    @Test
    fun fullResponseReplacesExistingBytes() {
        seedPartial()
        val replacement = response(200, "12345678", "ETag" to "\"new\"")
        run(replacement)
        assertEquals("bytes=4-", replacement.getRequestProperty("Range"))
        assertEquals("12345678", target.readText())
    }

    @Test
    fun invalidRangeIsRetriedWithoutAppending() {
        seedPartial()
        val invalid = response(206, "defgh", "ETag" to etag, "Content-Range" to "bytes 3-7/8")
        val replacement = response(200, "12345678")
        run(invalid, replacement)
        assertNull(replacement.getRequestProperty("Range"))
        assertEquals("12345678", target.readText())
    }

    private fun seedPartial() {
        partial.writeText("abcd")
        File(folder.root, "${download.partialName}.etag").writeText(etag)
    }

    private fun run(vararg responses: HttpsURLConnection) {
        val remaining = responses.iterator()
        UpdateTransfer(folder.root, download, reservedSpace = 0, openConnection = { remaining.next() }).run {}
        assertFalse(remaining.hasNext())
    }

    private fun response(status: Int, body: String, vararg headers: Pair<String, String>): HttpsURLConnection {
        val fields = headers.toMap()
        return object : HttpsURLConnection(URL("https://example.com/update.apk")) {
            override fun getResponseCode() = status
            override fun getHeaderField(name: String?) = fields[name]
            override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
            override fun getCipherSuite() = ""
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate>? = null
            override fun usingProxy() = false
            override fun connect() = Unit
            override fun disconnect() = Unit
        }
    }
}
