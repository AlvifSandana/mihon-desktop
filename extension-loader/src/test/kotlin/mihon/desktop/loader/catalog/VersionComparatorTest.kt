package mihon.desktop.loader.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VersionComparator]: versionCode Long-compare path + version-name semver-ish path.
 */
class VersionComparatorTest {

    // ── versionCode path (installed jar has a code) ─────────────────────

    @Test
    fun `higher versionCode is newer`() {
        assertTrue(VersionComparator.isNewerVersion(100L, "1.0.0", 200L, "1.1.0"))
    }

    @Test
    fun `equal versionCode is not newer`() {
        assertFalse(VersionComparator.isNewerVersion(200L, "1.0.0", 200L, "1.0.0"))
    }

    @Test
    fun `lower versionCode is not newer`() {
        assertFalse(VersionComparator.isNewerVersion(300L, "1.0.0", 200L, "2.0.0"))
    }

    // ── version-name path (installed jar has no versionCode) ────────────

    @Test
    fun `numeric compare not lexicographic - 1_2 is older than 1_10`() {
        assertTrue(VersionComparator.isNewerVersion(null, "1.2", 0L, "1.10"))
        assertEquals(-1, sign(VersionComparator.compareVersionNames("1.2", "1.10")))
    }

    @Test
    fun `1_2_3 is older than 1_2_4`() {
        assertTrue(VersionComparator.isNewerVersion(null, "1.2.3", 0L, "1.2.4"))
    }

    @Test
    fun `equal version names are equal`() {
        assertEquals(0, VersionComparator.compareVersionNames("1.2.3", "1.2.3"))
        assertFalse(VersionComparator.isNewerVersion(null, "1.2.3", 0L, "1.2.3"))
    }

    @Test
    fun `missing segment counts as zero`() {
        assertEquals(0, VersionComparator.compareVersionNames("1.2", "1.2.0"))
        assertEquals(0, VersionComparator.compareVersionNames("1.2.0.0", "1.2"))
    }

    @Test
    fun `null version name is oldest`() {
        assertTrue(VersionComparator.compareVersionNames(null, "0.0.1") < 0)
        assertTrue(VersionComparator.compareVersionNames("0.0.1", null) > 0)
        assertEquals(0, VersionComparator.compareVersionNames(null, null))
        // An installed extension without any version info is updatable.
        assertTrue(VersionComparator.isNewerVersion(null, null, 1L, "0.0.1"))
    }

    @Test
    fun `suffix makes a segment different - beta sorts after plain per suffix rule`() {
        // Rule (documented in VersionComparator): equal numeric part -> plain string
        // compare of the suffix, empty suffix lowest.
        assertTrue(VersionComparator.compareVersionNames("1.2", "1.2-beta") < 0)
        assertTrue(VersionComparator.compareVersionNames("1.2-alpha", "1.2-beta") < 0)
        assertTrue(VersionComparator.compareVersionNames("1.2-beta", "1.2") > 0)
    }

    @Test
    fun `numeric part of a suffixed segment still compares numerically`() {
        // 9 < 10 even with suffixes hanging off the segments.
        assertTrue(VersionComparator.compareVersionNames("1.9-x", "1.10-x") < 0)
        assertTrue(VersionComparator.compareVersionNames("2.1", "10.0") < 0)
    }

    @Test
    fun `installed newer than catalog is not an update`() {
        assertFalse(VersionComparator.isNewerVersion(null, "1.10", 0L, "1.2"))
    }

    private fun sign(value: Int): Int = when {
        value < 0 -> -1
        value > 0 -> 1
        else -> 0
    }
}
