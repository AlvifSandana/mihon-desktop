package mihon.desktop.loader.catalog

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * HTTPS enforcement for every catalog-derived URL: URLs coming off the wire
 * (repo.json, index_v2, apkUrl/jarUrl, GitHub API assets, icons) must be
 * rejected before they reach OkHttp. Plain-http fetches would leak the
 * request (and the downloaded jar bytes) in the clear and enable MITM swaps.
 */
class HttpsEnforcementTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── requireHttps ─────────────────────────────────────────────────────

    @Test
    fun `requireHttps passes https URLs through unchanged`() {
        val url = "https://example.com/releases/extension-v1.0.jar"
        assertEquals(url, requireHttps(url))
    }

    @Test
    fun `requireHttps rejects plain http URLs`() {
        try {
            requireHttps("http://example.com/releases/extension-v1.0.jar")
            fail("expected http URL to be refused")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("Refusing non-https extension URL"))
        }
    }

    @Test
    fun `requireHttps rejects garbage that is not a URL`() {
        try {
            requireHttps("definitely not a url")
            fail("expected non-URL to be refused")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("Refusing non-https extension URL"))
        }
    }

    // ── OkHttpFetcher ────────────────────────────────────────────────────

    @Test
    fun `OkHttpFetcher refuses to download over http and writes nothing`() {
        val fetcher = OkHttpFetcher(OkHttpClient())
        val destination = File(tmp.newFolder(), "out.jar")

        try {
            fetcher.fetch("http://127.0.0.1:1/evil.jar", destination) { _, _ -> }
            fail("expected the http download to be refused")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("Refusing non-https extension URL"))
        }

        // Refused before any request was made: no partial file on disk.
        assertFalse(destination.exists())
    }

    @Test
    fun `OkHttpFetcher refuses http HEAD probes`() {
        val fetcher = OkHttpFetcher(OkHttpClient())
        try {
            fetcher.exists("http://127.0.0.1:1/evil.jar")
            fail("expected the http HEAD probe to be refused")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("Refusing non-https extension URL"))
        }
    }

    // ── CatalogExtension apkUrl/jarUrl ───────────────────────────────────

    @Test
    fun `CatalogExtension refuses non-https apk URLs when deriving the jar URL`() {
        val extension = CatalogExtension(
            name = "Ext",
            packageName = "eu.kanade.a",
            versionName = "1.0.0",
            versionCode = 1L,
            extensionLibVersion = "1.4",
            isNsfw = false,
            apkUrl = "http://example.com/releases/ext-v1.0.0.apk",
            iconUrl = "",
            sources = emptyList(),
        )
        try {
            extension.jarUrl
            fail("expected the non-https apkUrl to be refused")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("Refusing non-https extension URL"))
        }
    }

    @Test
    fun `CatalogExtension requires apk URLs to end in apk`() {
        val extension = CatalogExtension(
            name = "Ext",
            packageName = "eu.kanade.a",
            versionName = "1.0.0",
            versionCode = 1L,
            extensionLibVersion = "1.4",
            isNsfw = false,
            apkUrl = "https://example.com/releases/ext-v1.0.0.exe",
            iconUrl = "",
            sources = emptyList(),
        )
        try {
            extension.jarUrl
            fail("expected the non-apk apkUrl to be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("must end in .apk"))
        }
    }

    @Test
    fun `CatalogExtension derives the jar URL from a well-formed apk URL`() {
        val extension = CatalogExtension(
            name = "Ext",
            packageName = "eu.kanade.a",
            versionName = "1.0.0",
            versionCode = 1L,
            extensionLibVersion = "1.4",
            isNsfw = false,
            apkUrl = "https://example.com/releases/ext-v1.0.0.apk",
            iconUrl = "",
            sources = emptyList(),
        )
        assertEquals("https://example.com/releases/ext-v1.0.0.jar", extension.jarUrl)
        assertEquals("ext-v1.0.0.jar", extension.jarFileName)
    }

    // ── CatalogClient ────────────────────────────────────────────────────

    @Test
    fun `CatalogClient refuses a non-https repo URL before any request`() = runBlocking {
        val client = CatalogClient(OkHttpClient())
        try {
            client.fetchCatalog("http://127.0.0.1:1/repo.json")
            fail("expected the non-https repo URL to be refused")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("Refusing non-https extension URL"))
        }
    }
}
