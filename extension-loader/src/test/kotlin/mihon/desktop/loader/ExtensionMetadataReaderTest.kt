package mihon.desktop.loader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [ExtensionMetadataReader]: plain manifest parsing still works, and a hostile
 * manifest carrying a DOCTYPE/ENTITY payload is rejected instead of being
 * resolved (XXE -- the manifest comes from a downloaded jar, so external
 * entities must never be followed).
 */
class ExtensionMetadataReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun buildJarWithManifest(target: File, manifest: String) {
        ZipOutputStream(target.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
        }
    }

    @Test
    fun `reads package name and versions from a plain manifest`() {
        val jar = tmp.newFile("ext.jar")
        buildJarWithManifest(
            jar,
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="eu.kanade.tachiyomi.extension.en.example"
                android:versionCode="100"
                android:versionName="1.0.0">
                <meta-data android:name="tachiyomi.extension.class"
                    android:value="eu.kanade.tachiyomi.extension.en.example.ExampleSource" />
            </manifest>
            """.trimIndent(),
        )

        val metadata = ExtensionMetadataReader.read(jar)

        assertEquals("eu.kanade.tachiyomi.extension.en.example", metadata.packageName)
        assertEquals(100L, metadata.versionCode)
        assertEquals("1.0.0", metadata.versionName)
        assertEquals(
            "eu.kanade.tachiyomi.extension.en.example.ExampleSource",
            metadata.sourceClass,
        )
    }

    @Test
    fun `manifest with a DOCTYPE is rejected`() {
        val jar = tmp.newFile("evil-dtd.jar")
        buildJarWithManifest(
            jar,
            """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE manifest [
                <!ENTITY xxe SYSTEM "file:///etc/passwd">
            ]>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="&xxe;">
                <meta-data android:name="tachiyomi.extension.class"
                    android:value="evil.Source" />
            </manifest>
            """.trimIndent(),
        )

        try {
            ExtensionMetadataReader.read(jar)
            fail("expected the DOCTYPE-bearing manifest to be rejected")
        } catch (expected: Exception) {
            // disallow-doctype-decl rejects the DTD outright; the external
            // entity must never be resolved.
            assertTrue(
                "unexpected rejection: ${expected.message}",
                expected.message?.contains("DOCTYPE", ignoreCase = true) == true ||
                    expected.message?.contains("disallow-doctype", ignoreCase = true) == true,
            )
        }
    }

    @Test
    fun `manifest referencing an undefined entity is rejected`() {
        val jar = tmp.newFile("evil-entity.jar")
        buildJarWithManifest(
            jar,
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="&steal;">
                <meta-data android:name="tachiyomi.extension.class"
                    android:value="evil.Source" />
            </manifest>
            """.trimIndent(),
        )

        // Either the entity is rejected outright or it resolves to nothing --
        // what must NOT happen is a successful read of attacker-controlled
        // external content.
        assertTrue(runCatching { ExtensionMetadataReader.read(jar) }.isFailure)
    }
}
