package mihon.desktop.loader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the strict validation of DB-sourced jar file names in
 * [ExtensionLoader.loadCached]: a tampered `jarFileName` must never resolve
 * outside the extension cache directory. Tests the validator directly so they
 * never touch the developer's real `~/.mihon-desktop/extension-cache`.
 */
class LoadCachedValidationTest {

    @Test
    fun `plain cache file names are accepted`() {
        assertTrue(ExtensionLoader.isValidCachedJarName("eu.kanade.tachiyomi.extension-en-example-v1.4.jar"))
        assertTrue(ExtensionLoader.isValidCachedJarName("extension-v12.jar"))
        assertTrue(ExtensionLoader.isValidCachedJarName("_private-1.0.jar"))
        assertTrue(ExtensionLoader.isValidCachedJarName("a.jar"))
    }

    @Test
    fun `traversal names are rejected`() {
        assertFalse(ExtensionLoader.isValidCachedJarName("../evil.jar"))
        assertFalse(ExtensionLoader.isValidCachedJarName("..\\evil.jar"))
        assertFalse(ExtensionLoader.isValidCachedJarName("foo/../../evil.jar"))
        assertFalse(ExtensionLoader.isValidCachedJarName("sub/dir/evil.jar"))
    }

    @Test
    fun `absolute and drive-relative names are rejected`() {
        assertFalse(ExtensionLoader.isValidCachedJarName("/etc/evil.jar"))
        assertFalse(ExtensionLoader.isValidCachedJarName("C:evil.jar"))
        assertFalse(ExtensionLoader.isValidCachedJarName("C:\\evil.jar"))
    }

    @Test
    fun `hidden and malformed names are rejected`() {
        assertFalse(ExtensionLoader.isValidCachedJarName(".hidden.jar"))
        assertFalse(ExtensionLoader.isValidCachedJarName(".."))
        assertFalse(ExtensionLoader.isValidCachedJarName(""))
        assertFalse(ExtensionLoader.isValidCachedJarName("no-extension"))
        assertFalse(ExtensionLoader.isValidCachedJarName("spaces in name.jar"))
        assertFalse(ExtensionLoader.isValidCachedJarName("semi;colon.jar"))
        assertFalse(ExtensionLoader.isValidCachedJarName("evil.JAR"))
        assertFalse(ExtensionLoader.isValidCachedJarName("evil.jar "))
    }
}
