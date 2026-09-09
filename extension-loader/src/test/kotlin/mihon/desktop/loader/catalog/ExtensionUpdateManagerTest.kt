package mihon.desktop.loader.catalog

import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import mihon.desktop.loader.ExtensionLoader
import mihon.desktop.loader.library.LibraryDatabase
import mihon.desktop.loader.library.LibraryRepository
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [ExtensionUpdateManager]: update detection (pure), installed-jar discovery
 * (manifest parse), and the jar swap (download safety + atomicity) via the
 * [HttpFetcher] constructor seam -- no network, no MockWebServer.
 */
class ExtensionUpdateManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        cacheDir = tmp.newFolder("extension-cache")
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun manifestXml(pkg: String, versionCode: Long?, versionName: String?) = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n")
        append("    package=\"$pkg\"")
        if (versionCode != null) append("\n    android:versionCode=\"$versionCode\"")
        if (versionName != null) append("\n    android:versionName=\"$versionName\"")
        append(">\n")
        append("    <meta-data android:name=\"tachiyomi.extension.class\" android:value=\"$pkg.E Sources\" />\n")
        append("</manifest>\n")
    }

    /** Builds a minimal but structurally valid extension jar carrying a manifest. */
    private fun buildJar(target: File, pkg: String, versionCode: Long?, versionName: String?) {
        ZipOutputStream(target.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(manifestXml(pkg, versionCode, versionName).toByteArray())
            zip.closeEntry()
        }
    }

    private fun catalogExtension(pkg: String, versionCode: Long, versionName: String) =
        CatalogExtension(
            name = "Ext $pkg",
            packageName = pkg,
            versionName = versionName,
            versionCode = versionCode,
            extensionLibVersion = "1.4",
            isNsfw = false,
            apkUrl = "https://example.com/releases/$pkg-v$versionName.apk",
            iconUrl = "",
            sources = emptyList(),
        )

    /**
     * Serves the bytes of the file [resolve] maps a URL to; records jar
     * invocations (the release-assets manifest also travels through the fetcher
     * and is deliberately not counted -- [calls] is "jar bytes served").
     */
    private class FakeFetcher(private val resolve: (url: String) -> File) : HttpFetcher {
        var calls = 0
        override fun fetch(url: String, destination: File, onProgress: (Long, Long?) -> Unit) {
            if (!url.endsWith(".json")) calls++
            val source = resolve(url)
            source.inputStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            onProgress(source.length(), source.length())
        }

        override fun exists(url: String) = true
    }

    /** Writes half the source bytes, then fails -- simulates an interrupted download. */
    private class InterruptedFetcher(private val resolve: (url: String) -> File) : HttpFetcher {
        override fun fetch(url: String, destination: File, onProgress: (Long, Long?) -> Unit) {
            val bytes = resolve(url).readBytes()
            destination.outputStream().use { output ->
                output.write(bytes, 0, bytes.size / 2)
                output.flush()
            }
            throw IOException("connection reset mid-download")
        }

        override fun exists(url: String) = true
    }

    /**
     * Manager wired for hermetic tests: a fake fetcher, a release-assets URL that
     * resolves to nothing (so sha256-manifest verification is skipped through the
     * documented "manifest unreachable" path), and a throwaway library database
     * so the DB-rebind side of applyUpdate never touches the developer's library.
     */
    private fun newManager(
        fetcher: HttpFetcher,
        libraryRepository: LibraryRepository = LibraryRepository(LibraryDatabase.openDatabase(tmp.newFile())),
    ): ExtensionUpdateManager {
        val unusedClient = OkHttpClient()
        return ExtensionUpdateManager(
            catalogClient = CatalogClient(unusedClient),
            downloader = ExtensionDownloader(
                client = unusedClient,
                cacheDir = cacheDir,
                releaseAssetsUrl = "http://127.0.0.1:1/release-assets.json",
                fetcher = fetcher,
            ),
            libraryRepository = libraryRepository,
            cacheDir = cacheDir,
        )
    }

    private fun sidecarFor(jar: File) = File(jar.parentFile, jar.name + ".sha256")

    // ── discovery ────────────────────────────────────────────────────────

    @Test
    fun `discoverInstalled reads package and versions from the manifest`() = runBlocking {
        val jar = File(cacheDir, "tachiyomi-en.bunmanga-v1.6.54.jar")
        buildJar(jar, "eu.kanade.tachiyomi.extension.en.bunmanga", 106054L, "1.6.54")

        val installed = newManager(FakeFetcher { jar }).discoverInstalled()

        assertEquals(1, installed.size)
        val ext = installed.single()
        assertEquals("eu.kanade.tachiyomi.extension.en.bunmanga", ext.packageName)
        assertEquals(106054L, ext.versionCode)
        assertEquals("1.6.54", ext.versionName)
        assertEquals(jar, ext.jarFile)
    }

    @Test
    fun `discoverInstalled ignores non-jar files and dedupes same package keeping newest`() = runBlocking {
        File(cacheDir, "notes.txt").writeText("not a jar")
        buildJar(File(cacheDir, "old-v1.0.0.jar"), "eu.kanade.test", 100L, "1.0.0")
        buildJar(File(cacheDir, "new-v2.0.0.jar"), "eu.kanade.test", 200L, "2.0.0")
        buildJar(File(cacheDir, "other-v1.0.0.jar"), "eu.kanade.other", 100L, "1.0.0")

        val installed = newManager(FakeFetcher { cacheDir }).discoverInstalled()

        assertEquals(2, installed.size)
        val test = installed.single { it.packageName == "eu.kanade.test" }
        assertEquals(200L, test.versionCode)
        assertEquals("new-v2.0.0.jar", test.jarFile.name)
    }

    // ── update detection (pure) ──────────────────────────────────────────

    @Test
    fun `findUpdates reports newer catalog versions`() = runBlocking {
        val manager = newManager(FakeFetcher { cacheDir })
        val installed = listOf(
            InstalledExtension(
                jarFile = File(cacheDir, "a-v1.0.0.jar"),
                packageName = "eu.kanade.a",
                name = "A",
                versionCode = 100L,
                versionName = "1.0.0",
            ),
        )
        val catalog = listOf(catalogExtension("eu.kanade.a", 200L, "1.1.0"))

        val updates = manager.findUpdates(installed, catalog)

        assertEquals(1, updates.size)
        val update = updates.single()
        assertEquals(200L, update.newVersionCode)
        assertEquals("1.1.0", update.newVersionName)
        assertEquals("eu.kanade.a", update.packageName)
    }

    @Test
    fun `findUpdates reports nothing for same version`() = runBlocking {
        val manager = newManager(FakeFetcher { cacheDir })
        val installed = listOf(
            InstalledExtension(File(cacheDir, "a.jar"), "eu.kanade.a", "A", 200L, "1.1.0"),
        )
        val updates = manager.findUpdates(installed, listOf(catalogExtension("eu.kanade.a", 200L, "1.1.0")))
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `findUpdates reports nothing when package is absent from catalog`() = runBlocking {
        val manager = newManager(FakeFetcher { cacheDir })
        val installed = listOf(
            InstalledExtension(File(cacheDir, "a.jar"), "eu.kanade.local", "Local", 100L, "1.0.0"),
        )
        val updates = manager.findUpdates(installed, listOf(catalogExtension("eu.kanade.a", 900L, "9.0.0")))
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `findUpdates reports nothing when installed is newer than catalog`() = runBlocking {
        val manager = newManager(FakeFetcher { cacheDir })
        val installed = listOf(
            InstalledExtension(File(cacheDir, "a.jar"), "eu.kanade.a", "A", 300L, "1.3.0"),
        )
        val updates = manager.findUpdates(installed, listOf(catalogExtension("eu.kanade.a", 200L, "1.2.0")))
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `findUpdates falls back to version names when installed has no versionCode`() = runBlocking {
        val manager = newManager(FakeFetcher { cacheDir })
        val installed = listOf(
            InstalledExtension(File(cacheDir, "a.jar"), "eu.kanade.a", "A", null, "1.2"),
        )
        // 1.2 -> 1.10 is an update numerically (and would not be lexicographically).
        val updates = manager.findUpdates(installed, listOf(catalogExtension("eu.kanade.a", 0L, "1.10")))
        assertEquals(1, updates.size)
        assertEquals("1.10", updates.single().newVersionName)
    }

    @Test
    fun `checkForUpdates with injected catalog populates pendingUpdates`() = runBlocking {
        val newJar = File(tmp.newFolder("served"), "eu.kanade.a-v1.1.0.jar")
        buildJar(newJar, "eu.kanade.a", 200L, "1.1.0")
        val manager = newManager(FakeFetcher { newJar })

        buildJar(File(cacheDir, "eu.kanade.a-v1.0.0.jar"), "eu.kanade.a", 100L, "1.0.0")

        val updates = manager.checkForUpdates(catalog = listOf(catalogExtension("eu.kanade.a", 200L, "1.1.0")))

        assertEquals(1, updates.size)
        assertEquals(1, manager.pendingUpdates.value.size)
        assertEquals("eu.kanade.a", manager.pendingUpdates.value.single().packageName)
    }

    // ── jar swap: download safety + atomicity ────────────────────────────

    @Test
    fun `applyUpdate swaps in the new jar and removes the old one`() = runBlocking {
        val oldJar = File(cacheDir, "eu.kanade.a-v1.0.0.jar")
        buildJar(oldJar, "eu.kanade.a", 100L, "1.0.0")
        val newJar = File(tmp.newFolder("served"), "eu.kanade.a-v1.1.0.jar")
        buildJar(newJar, "eu.kanade.a", 200L, "1.1.0")
        val fetcher = FakeFetcher { newJar }
        val manager = newManager(fetcher)
        manager.checkForUpdates(catalog = listOf(catalogExtension("eu.kanade.a", 200L, "1.1.0")))
        val update = manager.pendingUpdates.value.single()

        var lastProgress: Pair<Long, Long?>? = null
        val result = manager.applyUpdate(update) { bytes, total -> lastProgress = bytes to total }

        // New jar present + integrity sidecar recorded; old jar and its sidecar gone.
        assertEquals(File(cacheDir, "eu.kanade.a-v1.1.0.jar"), result)
        assertTrue(result.exists())
        assertTrue(sidecarFor(result).exists())
        assertFalse(oldJar.exists())
        assertFalse(sidecarFor(oldJar).exists())
        // No .part leftovers.
        assertNull(cacheDir.listFiles { f -> f.name.endsWith(".part") }?.firstOrNull())
        // Progress was reported.
        assertNotNull(lastProgress)
        assertEquals(newJar.length(), lastProgress!!.first)
        // Pending updates drained -- the badge refreshes reactively.
        assertTrue(manager.pendingUpdates.value.isEmpty())
    }

    @Test
    fun `applyUpdate with invalid non-zip download leaves old jar intact`() = runBlocking {
        val oldJar = File(cacheDir, "eu.kanade.a-v1.0.0.jar")
        buildJar(oldJar, "eu.kanade.a", 100L, "1.0.0")
        val oldBytes = oldJar.readBytes()
        val garbage = File(tmp.newFolder("garbage"), "eu.kanade.a-v1.1.0.jar")
        garbage.writeText("this is definitely not a zip file")
        val manager = newManager(FakeFetcher { garbage })
        manager.checkForUpdates(catalog = listOf(catalogExtension("eu.kanade.a", 200L, "1.1.0")))
        val update = manager.pendingUpdates.value.single()

        try {
            manager.applyUpdate(update)
            fail("expected the invalid download to be rejected")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not a valid zip"))
        }

        // Old jar untouched, byte for byte; no new jar, no .part leftovers.
        assertTrue(oldJar.exists())
        assertTrue(oldBytes.contentEquals(oldJar.readBytes()))
        assertFalse(File(cacheDir, "eu.kanade.a-v1.1.0.jar").exists())
        assertNull(cacheDir.listFiles { f -> f.name.endsWith(".part") }?.firstOrNull())
        // The update is still pending.
        assertEquals(1, manager.pendingUpdates.value.size)
    }

    @Test
    fun `applyUpdate with interrupted download leaves old jar intact`() = runBlocking {
        val oldJar = File(cacheDir, "eu.kanade.a-v1.0.0.jar")
        buildJar(oldJar, "eu.kanade.a", 100L, "1.0.0")
        val oldBytes = oldJar.readBytes()
        val halfJar = File(tmp.newFolder("served"), "eu.kanade.a-v1.1.0.jar")
        buildJar(halfJar, "eu.kanade.a", 200L, "1.1.0")
        val manager = newManager(InterruptedFetcher { halfJar })
        manager.checkForUpdates(catalog = listOf(catalogExtension("eu.kanade.a", 200L, "1.1.0")))
        val update = manager.pendingUpdates.value.single()

        try {
            manager.applyUpdate(update)
            fail("expected the interrupted download to propagate")
        } catch (expected: IOException) {
            assertEquals("connection reset mid-download", expected.message)
        }

        // Old jar intact, no partial new file committed, temp cleaned up.
        assertTrue(oldJar.exists())
        assertTrue(oldBytes.contentEquals(oldJar.readBytes()))
        assertFalse(File(cacheDir, "eu.kanade.a-v1.1.0.jar").exists())
        assertNull(cacheDir.listFiles { f -> f.name.endsWith(".part") }?.firstOrNull())
    }

    @Test
    fun `concurrent applyUpdate for same package downloads once and stays consistent`() = runBlocking {
        val oldJar = File(cacheDir, "eu.kanade.a-v1.0.0.jar")
        buildJar(oldJar, "eu.kanade.a", 100L, "1.0.0")
        val newJar = File(tmp.newFolder("served"), "eu.kanade.a-v1.1.0.jar")
        buildJar(newJar, "eu.kanade.a", 200L, "1.1.0")
        val fetcher = FakeFetcher { newJar }
        val manager = newManager(fetcher)
        val installed = manager.discoverInstalled()
        manager.checkForUpdates(catalog = listOf(catalogExtension("eu.kanade.a", 200L, "1.1.0")))
        val update = manager.pendingUpdates.value.single()

        val first = async { manager.applyUpdate(update) }
        val second = async { manager.applyUpdate(update) }
        val results = listOf(first.await(), second.await())

        // Both calls succeed; the second sees the already-verified target and
        // never re-downloads (target exists, sha manifest unreachable).
        assertEquals(2, results.size)
        assertEquals(1, fetcher.calls)
        // Final state: exactly the new jar, old one gone, pending drained.
        val jars = cacheDir.listFiles { f -> f.extension == "jar" }!!.map { it.name }
        assertEquals(listOf("eu.kanade.a-v1.1.0.jar"), jars)
        assertTrue(manager.pendingUpdates.value.isEmpty())
    }

    @Test
    fun `applyAllPending applies every update and reports per-update results`() = runBlocking {
        buildJar(File(cacheDir, "eu.kanade.a-v1.0.0.jar"), "eu.kanade.a", 100L, "1.0.0")
        buildJar(File(cacheDir, "eu.kanade.b-v1.0.0.jar"), "eu.kanade.b", 100L, "1.0.0")
        val served = tmp.newFolder("served2")
        buildJar(File(served, "eu.kanade.a-v1.1.0.jar"), "eu.kanade.a", 200L, "1.1.0")
        buildJar(File(served, "eu.kanade.b-v1.1.0.jar"), "eu.kanade.b", 200L, "1.1.0")
        val manager = newManager(FakeFetcher { url -> File(served, url.substringAfterLast('/')) })
        manager.checkForUpdates(
            catalog = listOf(
                catalogExtension("eu.kanade.a", 200L, "1.1.0"),
                catalogExtension("eu.kanade.b", 200L, "1.1.0"),
            ),
        )
        assertEquals(2, manager.pendingUpdates.value.size)

        val results = manager.applyAllPending()

        assertEquals(2, results.size)
        results.forEach { (_, result) -> assertTrue(result.isSuccess) }
        assertTrue(manager.pendingUpdates.value.isEmpty())
        val jars = cacheDir.listFiles { f -> f.extension == "jar" }!!.map { it.name }.sorted()
        assertEquals(listOf("eu.kanade.a-v1.1.0.jar", "eu.kanade.b-v1.1.0.jar"), jars)
    }

    // ── DB rebind: library rows follow the new jar ──────────────────────

    @Test
    fun `applyUpdate rebinds library and update-history rows to the new jar name`() = runBlocking {
        val db = LibraryDatabase.openDatabase(tmp.newFile("rebind.db"))
        val repo = LibraryRepository(db)
        val oldJar = File(cacheDir, "eu.kanade.rebindcheck-v1.0.0.jar")
        buildJar(oldJar, "eu.kanade.rebindcheck", 100L, "1.0.0")
        val servedJar = File(tmp.newFolder("served-rebind"), "eu.kanade.rebindcheck-v1.1.0.jar")
        buildJar(servedJar, "eu.kanade.rebindcheck", 200L, "1.1.0")
        val manager = newManager(FakeFetcher { servedJar }, repo)

        // Library + update-history rows referencing the OLD jar name.
        repo.add(1L, "eu.kanade.rebindcheck", oldJar.name, "Ext", "m1", "Manga 1", null, null)
        val chapter = SChapter.create().apply {
            url = "c1"
            name = "Chapter 1"
        }
        repo.recordNewChapters(1L, "m1", "Manga 1", null, "eu.kanade.rebindcheck", oldJar.name, listOf(chapter))

        manager.checkForUpdates(catalog = listOf(catalogExtension("eu.kanade.rebindcheck", 200L, "1.1.0")))
        manager.applyUpdate(manager.pendingUpdates.value.single())

        // Both tables now point at the new jar file name.
        val libraryRow = db.libraryMangaQueries.selectOne(1L, "m1").executeAsOneOrNull()
        assertEquals("eu.kanade.rebindcheck-v1.1.0.jar", libraryRow?.jarFileName)
        val historyRow = db.updateHistoryQueries.selectAll().executeAsList().single()
        assertEquals("eu.kanade.rebindcheck-v1.1.0.jar", historyRow.jarFileName)

        // Old jar retired; the persisted OLD name no longer resolves to a
        // loadable jar (nothing with that name remains anywhere).
        assertFalse(oldJar.exists())
        assertTrue(File(cacheDir, "eu.kanade.rebindcheck-v1.1.0.jar").exists())
        assertTrue(runCatching { ExtensionLoader.loadCached(oldJar.name) }.isFailure)
    }

    // ── sha256 verification via the fetcher seam ────────────────────────

    @Test
    fun `sha256 mismatch rejects the download and leaves the old jar intact`() = runBlocking {
        val oldJar = File(cacheDir, "eu.kanade.a-v1.0.0.jar")
        buildJar(oldJar, "eu.kanade.a", 100L, "1.0.0")
        val oldBytes = oldJar.readBytes()
        val served = File(tmp.newFolder("served-sha"), "eu.kanade.a-v1.1.0.jar")
        buildJar(served, "eu.kanade.a", 200L, "1.1.0")
        // Manifest reachable via the fetcher, but advertising a wrong hash.
        val manifest = File(tmp.newFolder("manifest"), "release-assets.json")
        manifest.writeText(
            """{"eu.kanade.a": {"jar": {"name": "eu.kanade.a-v1.1.0.jar", "sha256": "${"0".repeat(64)}"}}}""",
        )
        val manager = newManager(FakeFetcher { url -> if (url.endsWith(".json")) manifest else served })
        manager.checkForUpdates(catalog = listOf(catalogExtension("eu.kanade.a", 200L, "1.1.0")))
        val update = manager.pendingUpdates.value.single()

        try {
            manager.applyUpdate(update)
            fail("expected sha256 verification to reject the download")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("failed sha256 verification"))
        }

        // Old jar intact byte for byte; no new jar, no .part leftovers; update still pending.
        assertTrue(oldJar.exists())
        assertTrue(oldBytes.contentEquals(oldJar.readBytes()))
        assertFalse(File(cacheDir, "eu.kanade.a-v1.1.0.jar").exists())
        assertNull(cacheDir.listFiles { f -> f.name.endsWith(".part") }?.firstOrNull())
        assertEquals(1, manager.pendingUpdates.value.size)
    }

    // ── jar file name safety ─────────────────────────────────────────────

    @Test
    fun `download refuses jar file names that could escape the cache dir`() = runBlocking {
        val downloader = ExtensionDownloader(
            client = OkHttpClient(),
            cacheDir = cacheDir,
            releaseAssetsUrl = "http://127.0.0.1:1/release-assets.json",
            fetcher = FakeFetcher { cacheDir }, // must never be reached
        )
        // Windows drive-relative name: parses as a URL but fails the allowlist.
        val driveRelative = catalogExtension("eu.kanade.a", 200L, "1.1.0").copy(
            apkUrl = "https://example.com/releases/C:evil.apk",
        )
        try {
            downloader.download(driveRelative)
            fail("expected the unsafe jar file name to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("unsafe file name"))
        }

        // `..\evil.jar` traversal via a backslash in the wire apkUrl.
        val traversal = catalogExtension("eu.kanade.a", 200L, "1.1.0").copy(
            apkUrl = "https://example.com/releases/..\\evil.apk",
        )
        val outcome = runCatching { downloader.download(traversal) }
        assertTrue(outcome.isFailure)

        // Nothing was written inside (or, via traversal, outside) the cache dir.
        assertNull(cacheDir.listFiles { f -> f.extension == "jar" }?.firstOrNull())
        assertNull(cacheDir.parentFile?.listFiles { f -> f.name == "evil.jar" }?.firstOrNull())
    }

    // ── GitHub fallback resolution is cached per session ────────────────

    @Test
    fun `fallback asset URLs are resolved once per jar per session`() = runBlocking {
        val served = File(tmp.newFolder("served-cached"), "eu.kanade.a-v1.1.0.jar")
        buildJar(served, "eu.kanade.a", 200L, "1.1.0")
        val resolver = object : GitHubReleaseAssetResolver(OkHttpClient()) {
            var calls = 0
            override fun findAssetUrl(fileName: String, maxReleases: Int): String? {
                calls++
                return "https://example.com/assets/$fileName"
            }
        }
        val fetcher = object : HttpFetcher {
            override fun fetch(url: String, destination: File, onProgress: (Long, Long?) -> Unit) {
                served.inputStream().use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
                onProgress(served.length(), served.length())
            }

            // Primary jar URL "does not exist": force the fallback resolver.
            override fun exists(url: String) = false
        }
        val downloader = ExtensionDownloader(
            client = OkHttpClient(),
            cacheDir = cacheDir,
            fallbackResolver = resolver,
            releaseAssetsUrl = "http://127.0.0.1:1/release-assets.json",
            fetcher = fetcher,
        )

        downloader.download(catalogExtension("eu.kanade.a", 200L, "1.1.0"))
        assertEquals(1, resolver.calls)

        // Second download of the same jar: the resolved URL comes from the
        // session cache, no second GitHub API round.
        File(cacheDir, "eu.kanade.a-v1.1.0.jar").delete()
        downloader.download(catalogExtension("eu.kanade.a", 200L, "1.1.0"))
        assertEquals(1, resolver.calls)
        assertTrue(File(cacheDir, "eu.kanade.a-v1.1.0.jar").exists())
    }
}
