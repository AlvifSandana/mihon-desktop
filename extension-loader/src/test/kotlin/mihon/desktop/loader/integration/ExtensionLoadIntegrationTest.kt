package mihon.desktop.loader.integration

import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.runBlocking
import mihon.desktop.loader.ExtensionLoader
import mihon.desktop.loader.JarIntegrity
import mihon.desktop.loader.catalog.CatalogClient
import mihon.desktop.loader.catalog.ExtensionDownloader
import mihon.desktop.loader.catalog.ExtensionUpdateManager
import mihon.desktop.loader.library.LibraryDatabase
import mihon.desktop.loader.library.LibraryRepository
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URLClassLoader
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.DiagnosticCollector
import javax.tools.FileObject
import javax.tools.ForwardingJavaFileManager
import javax.tools.JavaFileManager
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.StandardJavaFileManager
import javax.tools.ToolProvider

/**
 * Integration harness for the REAL extension-jar loading pipeline, which
 * previously could only be exercised by hand (drop a keiyoushi jar into
 * `~/.mihon-desktop/extension-cache` and click around the app). No real
 * extension is shipped (legal + hermetic + zero network): a synthetic
 * extension jar is built in-test and driven through the full path —
 * discovery -> integrity sidecar -> manifest metadata -> URLClassLoader ->
 * class resolution -> Source instantiation -> loader cache -> loadCached.
 *
 * ## Fixture construction (documented contract)
 *
 * - **Classes**: two tiny Java sources (`SyntheticSource implements Source`,
 *   `SyntheticFactory implements SourceFactory`) compiled IN-MEMORY by the
 *   system javac (`ToolProvider.getSystemJavaCompiler`, present on any full
 *   JDK 21 — the same environment assumption the QuickJS tests make). They
 *   live in package `mihon.fixture`, which exists nowhere on the test
 *   classpath, so the classes provably load THROUGH the jar's
 *   [URLClassLoader] and not from the parent loader. Suspend methods return
 *   their result directly (no suspension points), so Kotlin callers can
 *   invoke them from `runBlocking`.
 * - **Manifest**: plain-text `AndroidManifest.xml` — keiyoushi `.jar`
 *   manifests are not compiled AXML, which is exactly why
 *   `ExtensionMetadataReader` can parse them with a stock XML parser. Fields
 *   the reader requires: `package`, `android:versionCode`,
 *   `android:versionName`, and the `tachiyomi.extension.class` meta-data
 *   (plus `tachiyomix.name` and `tachiyomi.extension.factory` for the
 *   factory variant).
 * - **Jar**: manifest + `.class` entries zipped into a temp dir.
 *   `ExtensionLoader.cacheDirOverride` (test seam) redirects the loader's
 *   cache dir away from the developer's real
 *   `~/.mihon-desktop/extension-cache` for the `loadCached` path; extension
 *   discovery takes its cache dir as a constructor parameter already.
 */
class ExtensionLoadIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var cacheDir: File

    /** Jars registered with the process-wide loader cache; cleaned in @After. */
    private val loadedJars = mutableListOf<File>()

    @Before
    fun setUp() {
        cacheDir = tmp.newFolder("extension-cache")
        ExtensionLoader.cacheDirOverride = cacheDir
    }

    @After
    fun tearDown() {
        // Drop cache entries + close their classloaders so the process-wide
        // loader map doesn't accumulate temp-dir entries across tests.
        // TemporaryFolder may already have deleted the files; that's fine.
        loadedJars.forEach { jar -> runCatching { ExtensionLoader.removeCachedJar(jar) } }
        loadedJars.clear()
        ExtensionLoader.cacheDirOverride = null
    }

    // ── jar assembly ────────────────────────────────────────────────────

    private fun manifestXml(pkg: String, sourceClass: String, factory: Boolean = false): String =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n")
            append("    package=\"$pkg\"\n")
            append("    android:versionCode=\"42\"\n")
            append("    android:versionName=\"1.2.3\">\n")
            append("    <meta-data android:name=\"tachiyomi.extension.class\" android:value=\"$sourceClass\" />\n")
            if (factory) {
                append("    <meta-data android:name=\"tachiyomi.extension.factory\" android:value=\"1\" />\n")
            }
            append("    <meta-data android:name=\"tachiyomix.name\" android:value=\"Synthetic Fixture\" />\n")
            append("</manifest>\n")
        }

    /** Writes manifest + class bytes into [target] as an extension jar. */
    private fun buildJar(target: File, manifest: String, classes: Map<String, ByteArray>): File =
        target.apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write(manifest.toByteArray())
                zip.closeEntry()
                classes.forEach { (className, bytes) ->
                    zip.putNextEntry(ZipEntry(className.replace('.', '/') + ".class"))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            loadedJars += this
        }

    private fun sourceJar(name: String, pkg: String = "mihon.fixture", sourceClass: String = "mihon.fixture.SyntheticSource"): File =
        buildJar(File(cacheDir, name), manifestXml(pkg, sourceClass), fixtureClasses)

    // ── tests ───────────────────────────────────────────────────────────

    @Test
    fun `load returns a working Source whose bytecode is served by the jar classloader`() {
        val jar = sourceJar("tachiyomi-synthetic-v1.2.3.jar")

        val extension = ExtensionLoader.load(jar)

        // Metadata parsed out of the synthetic manifest.
        with(extension.metadata) {
            assertEquals("mihon.fixture", packageName)
            assertEquals(42L, versionCode)
            assertEquals("1.2.3", versionName)
            assertEquals("Synthetic Fixture", name)
            assertFalse(isNsfw)
        }

        // Source instantiated from the jar's classes.
        assertEquals(1, extension.sources.size)
        val source = extension.sources.single()
        assertEquals(123456789L, source.id)
        assertEquals("Synthetic Fixture Source", source.name)
        assertEquals("en", source.lang)

        // The fixture package exists nowhere on the test classpath, so the
        // class must have been served by the URLClassLoader built over the
        // jar -- not by the parent (test) classloader.
        val loader = source.javaClass.classLoader
        assertTrue("expected the jar's URLClassLoader, got $loader", loader is URLClassLoader)
        val jarUrls = (loader as URLClassLoader).urLs
        assertTrue(
            "classloader should point at the fixture jar, got $jarUrls",
            jarUrls.any { it.file.endsWith("tachiyomi-synthetic-v1.2.3.jar") },
        )

        // Real bytecode executing: suspend calls resolve to empty results.
        runBlocking {
            val page = source.getPopularManga(1)
            assertEquals(0, page.mangas.size)
            assertFalse(page.hasNextPage)
            assertEquals(0, source.getPageList(SChapter.create().apply { url = "ch1" }).size)
        }
    }

    @Test
    fun `discoverInstalled describes the synthetic jar from its manifest`() {
        sourceJar("tachiyomi-synthetic-v1.2.3.jar")
        // A corrupt neighbor jar must be skipped without failing discovery.
        File(cacheDir, "broken-v9.jar").writeText("this is not a zip file")

        val manager = ExtensionUpdateManager(
            catalogClient = CatalogClient(OkHttpClient()),
            downloader = ExtensionDownloader(OkHttpClient(), cacheDir),
            libraryRepository = LibraryRepository(LibraryDatabase.openDatabase(tmp.newFile())),
            cacheDir = cacheDir,
        )

        val installed = runBlocking { manager.discoverInstalled() }

        assertEquals(1, installed.size)
        with(installed.single()) {
            assertEquals("mihon.fixture", packageName)
            assertEquals("Synthetic Fixture", name)
            assertEquals(42L, versionCode)
            assertEquals("1.2.3", versionName)
            assertTrue(jarFile.name.endsWith("tachiyomi-synthetic-v1.2.3.jar"))
        }
    }

    @Test
    fun `factory jars expand to their sources via createSources`() {
        val jar = buildJar(
            File(cacheDir, "tachiyomi-synthetic-factory-v1.2.3.jar"),
            manifestXml(pkg = "mihon.fixture", sourceClass = "mihon.fixture.SyntheticFactory", factory = true),
            fixtureClasses,
        )

        val extension = ExtensionLoader.load(jar)

        // The class named by tachiyomi.extension.class was a SourceFactory:
        // the loader expanded it via createSources().
        assertEquals(1, extension.sources.size)
        assertEquals("Synthetic Fixture Source", extension.sources.single().name)
    }

    @Test
    fun `leading-dot source class resolves against the manifest package`() {
        // Real Mihon manifests often use relative (".Sources") class names;
        // the loader must resolve them as package + name.
        val jar = buildJar(
            File(cacheDir, "tachiyomi-synthetic-relative-v1.2.3.jar"),
            manifestXml(pkg = "mihon.fixture", sourceClass = ".SyntheticSource"),
            fixtureClasses,
        )

        val extension = ExtensionLoader.load(jar)

        assertEquals(1, extension.sources.size)
        assertEquals("Synthetic Fixture Source", extension.sources.single().name)
    }

    @Test
    fun `loadCached loads through the cache dir and rejects hostile file names`() {
        sourceJar("tachiyomi-synthetic-v1.2.3.jar")

        // Positive: the DB-row entry point resolves inside the (redirected)
        // cache dir and returns the loaded extension.
        val extension = ExtensionLoader.loadCached("tachiyomi-synthetic-v1.2.3.jar")
        assertEquals("mihon.fixture", extension.metadata.packageName)
        assertEquals(1, extension.sources.size)

        // Hostile names never resolve to a file at all.
        listOf("../evil.jar", "/etc/evil.jar", ".hidden.jar", "sub/dir/evil.jar").forEach { name ->
            try {
                ExtensionLoader.loadCached(name)
                fail("expected loadCached('$name') to be rejected")
            } catch (expected: IllegalArgumentException) {
                // required() rejected the name before any file access
            }
        }
    }

    @Test
    fun `reloading an unchanged jar is a cache hit and a changed jar reloads fresh`() {
        val jar = sourceJar("tachiyomi-synthetic-v1.2.3.jar")

        val first = ExtensionLoader.load(jar)
        val second = ExtensionLoader.load(jar)
        assertSame(
            "unchanged jar must reuse the cached Source instances",
            first.sources.single(),
            second.sources.single(),
        )

        // Replace the jar on disk (an extension update): new bytes + bumped
        // mtime make the cache entry stale. Fresh sidecar so integrity passes.
        jar.delete()
        buildJar(
            jar,
            manifestXml(pkg = "mihon.fixture.updated", sourceClass = "mihon.fixture.SyntheticSource"),
            fixtureClasses,
        )
        // Ensure lastModified differs even on coarse-mtime filesystems.
        jar.setLastModified(jar.lastModified() + 60_000)
        JarIntegrity.writeSidecar(jar)

        val third = ExtensionLoader.load(jar)
        assertFalse(
            "changed jar must not reuse stale instances",
            third.sources.single() === first.sources.single(),
        )
        assertEquals("mihon.fixture.updated", third.metadata.packageName)
    }

    @Test
    fun `sidecar integrity verifies the jar before load and rejects tampering`() {
        val verified = sourceJar("tachiyomi-synthetic-verified-v1.2.3.jar")
        // "Download-time" hash: the loader must re-check against it before
        // executing any of the jar's code -- and pass.
        JarIntegrity.writeSidecar(verified)
        val loaded = ExtensionLoader.load(verified)
        assertEquals("mihon.fixture", loaded.metadata.packageName)

        // Legacy install (no sidecar): loads unverified, as documented.
        val legacy = sourceJar("tachiyomi-synthetic-legacy-v1.2.3.jar")
        assertEquals("mihon.fixture", ExtensionLoader.load(legacy).metadata.packageName)

        // Tamper: replace the verified jar with different bytes after the
        // sidecar was written. Cache misses (new mtime/length), integrity
        // fails -- and the rejection must come from verification, before any
        // class loading.
        verified.delete()
        buildJar(
            verified,
            manifestXml(pkg = "mihon.evil.tampered", sourceClass = "mihon.fixture.SyntheticSource"),
            fixtureClasses,
        )
        try {
            ExtensionLoader.load(verified)
            fail("expected the tampered jar to be rejected by sidecar verification")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "expected a sidecar verification failure, got: ${expected.message}",
                expected.message?.contains("failed sidecar sha256 verification") == true,
            )
        }
    }

    // ── fixture sources + per-JVM compilation cache ──────────────────

    companion object {
        private val syntheticSourceJava = """
            package mihon.fixture;

            import eu.kanade.tachiyomi.source.Source;
            import eu.kanade.tachiyomi.source.model.FilterList;
            import eu.kanade.tachiyomi.source.model.MangasPage;
            import eu.kanade.tachiyomi.source.model.Page;
            import eu.kanade.tachiyomi.source.model.SChapter;
            import eu.kanade.tachiyomi.source.model.SManga;
            import eu.kanade.tachiyomi.source.model.SMangaUpdate;
            import java.util.Collections;
            import java.util.List;
            import kotlin.coroutines.Continuation;

            public class SyntheticSource implements Source {
                @Override public long getId() { return 123456789L; }
                @Override public String getName() { return "Synthetic Fixture Source"; }
                @Override public String getLang() { return "en"; }
                @Override public boolean getSupportsLatest() { return false; }

                @Override
                public Object getPopularManga(int page, Continuation<? super MangasPage> c) {
                    return new MangasPage(Collections.<SManga>emptyList(), false);
                }

                @Override
                public Object getLatestUpdates(int page, Continuation<? super MangasPage> c) {
                    return new MangasPage(Collections.<SManga>emptyList(), false);
                }

                @Override
                public Object getSearchManga(
                    int page,
                    String query,
                    FilterList filters,
                    Continuation<? super MangasPage> c
                ) {
                    return new MangasPage(Collections.<SManga>emptyList(), false);
                }

                @Override
                public Object getMangaUpdate(
                    SManga manga,
                    List<? extends SChapter> chapters,
                    boolean fetchDetails,
                    boolean fetchChapters,
                    Continuation<? super SMangaUpdate> c
                ) {
                    return new SMangaUpdate(manga, chapters);
                }

                @Override
                public Object getPageList(SChapter chapter, Continuation<? super List<? extends Page>> c) {
                    return Collections.<Page>emptyList();
                }
            }
        """.trimIndent()

        private val syntheticFactoryJava = """
            package mihon.fixture;

            import eu.kanade.tachiyomi.source.Source;
            import eu.kanade.tachiyomi.source.SourceFactory;
            import java.util.ArrayList;
            import java.util.List;

            public class SyntheticFactory implements SourceFactory {
                @Override
                public List<Source> createSources() {
                    List<Source> sources = new ArrayList<>();
                    sources.add(new SyntheticSource());
                    return sources;
                }
            }
        """.trimIndent()

        /**
         * The system compiler, or null on a JRE-only runtime. Tests guard with
         * `assumeTrue` — on CI (full JDK) and dev machines it is always present,
         * mirroring the QuickJS tests' environment assumption.
         */
        private val javac = ToolProvider.getSystemJavaCompiler()

        /** Compiles named [sources] (simple class name -> Java source) in-memory; returns className -> bytecode. */
        private fun compile(vararg sources: Pair<String, String>): Map<String, ByteArray> {
            assumeTrue("system javac (jdk.compiler) not available", javac != null)
            val compiler = requireNotNull(javac)
            val diagnostics = DiagnosticCollector<JavaFileObject>()
            val bytecode = mutableMapOf<String, ByteArray>()

            fun sinkFor(className: String): JavaFileObject =
                object : SimpleJavaFileObject(URI.create("mem:///$className.class"), JavaFileObject.Kind.CLASS) {
                    // javac closes the stream when the class is fully written:
                    // capture the bytes then.
                    val buffer = object : ByteArrayOutputStream() {
                        override fun close() {
                            synchronized(bytecode) { bytecode[className] = toByteArray() }
                        }
                    }

                    override fun openOutputStream() = buffer
                }

            val stdManager: StandardJavaFileManager = compiler.getStandardFileManager(diagnostics, null, null)
            val fileManager = object : ForwardingJavaFileManager<StandardJavaFileManager>(stdManager) {
                override fun getJavaFileForOutput(
                    location: JavaFileManager.Location,
                    className: String,
                    kind: JavaFileObject.Kind,
                    sibling: FileObject?,
                ): JavaFileObject = sinkFor(className)
            }

            val sourceObjects = sources.map { (className, source) ->
                object : SimpleJavaFileObject(URI.create("mem:///$className.java"), JavaFileObject.Kind.SOURCE) {
                    override fun getCharContent(ignoreEncodingErrors: Boolean): String = source
                }
            }

            val ok = compiler.getTask(
                null,
                fileManager,
                diagnostics,
                // The fixture implements source-api interfaces, which sit on the
                // test runtime classpath (Gradle runs tests in classic classpath
                // mode, so java.class.path is the real, expanded classpath).
                listOf("-classpath", System.getProperty("java.class.path")),
                null,
                sourceObjects,
            ).call()

            if (!ok) {
                fail(
                    "fixture compilation failed:\n" +
                        diagnostics.diagnostics.joinToString("\n") { it.toString() },
                )
            }
            assertTrue("compiled ${sources.size} sources but no class bytes were emitted", bytecode.isNotEmpty())
            return bytecode
        }

        /**
         * Fixture classes compiled at most once per JVM (javac is not free):
         * a companion-object lazy is shared by the fresh test instance JUnit
         * creates per test method, where an instance-level `by lazy` would
         * rerun javac for every single test. Compiled output is identical
         * every time, and tests run sequentially within the module's single
         * test executor.
         */
        private val fixtureClasses: Map<String, ByteArray> by lazy {
            compile(
                "SyntheticSource" to syntheticSourceJava,
                "SyntheticFactory" to syntheticFactoryJava,
            )
        }
    }

    private val URLClassLoader.urLs: List<java.net.URL>
        get() = getURLs().toList()
}
