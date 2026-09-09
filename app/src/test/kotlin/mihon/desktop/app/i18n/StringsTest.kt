package mihon.desktop.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Properties

/**
 * Strings table tests: fallback chain, placeholder formatting, live locale
 * switching, and bundle key parity between English and Indonesian.
 */
class StringsTest {

    @Before
    fun setUp() {
        // Tests must not depend on the host machine's locale.
        Strings.setLocale("en")
    }

    // ── Bundle loading / parsing ───────────────────────────────────────

    @Test
    fun `english bundle loads and is non-empty`() {
        assertTrue("English bundle failed to load", Strings.get("library_title").isNotBlank())
    }

    @Test
    fun `both bundles parse and have identical key sets`() {
        val english = loadBundle("strings.properties")
        val indonesian = loadBundle("strings_id.properties")

        assertTrue("English bundle missing", english.isNotEmpty())
        assertTrue("Indonesian bundle missing", indonesian.isNotEmpty())

        // No orphan keys: every Indonesian key must exist in English.
        val orphans = indonesian.keys - english.keys
        assertTrue("Keys only in strings_id.properties: $orphans", orphans.isEmpty())

        // Full parity: every English key has an Indonesian translation.
        val missing = english.keys - indonesian.keys
        assertTrue("Keys missing from strings_id.properties: $missing", missing.isEmpty())

        // No blank values on either side.
        english.forEach { (key, value) ->
            assertTrue("Blank value for '$key' in strings.properties", value.isNotBlank())
        }
        indonesian.forEach { (key, value) ->
            assertTrue("Blank value for '$key' in strings_id.properties", value.isNotBlank())
        }
    }

    // ── Fallback chain ─────────────────────────────────────────────────

    @Test
    fun `resolve prefers locale bundle`() {
        val locale = mapOf("k" to "locale-value")
        val english = mapOf("k" to "english-value")
        assertEquals("locale-value", Strings.resolve(locale, english, "k"))
    }

    @Test
    fun `resolve falls back to english when key missing from locale bundle`() {
        val english = mapOf("k" to "english-value")
        assertEquals("english-value", Strings.resolve(emptyMap(), english, "k"))
    }

    @Test
    fun `resolve returns raw key when missing everywhere`() {
        assertEquals("missing_key", Strings.resolve(emptyMap(), emptyMap(), "missing_key"))
    }

    @Test
    fun `get returns raw key for unknown key`() {
        assertEquals("definitely_not_a_real_key_42", Strings.get("definitely_not_a_real_key_42"))
    }

    // ── Placeholder formatting ─────────────────────────────────────────

    @Test
    fun `format substitutes positional placeholders`() {
        assertEquals("Ch. 3/12", Strings.format("Ch. {0}/{1}", arrayOf(3, 12)))
        assertEquals("5 of 20 chapters", Strings.format("{0} of {1} chapters", arrayOf(5, 20)))
    }

    @Test
    fun `format with no placeholders returns template unchanged`() {
        assertEquals("plain", Strings.format("plain", arrayOf<Any>()))
    }

    @Test
    fun `format with args but no placeholders in template ignores args`() {
        assertEquals("plain", Strings.format("plain", arrayOf("unused")))
    }

    @Test
    fun `format replaces repeated placeholders and renders null as empty`() {
        assertEquals("a, a and", Strings.format("{0}, {0} and{1}", arrayOf("a", null)))
    }

    @Test
    fun `format does not rescan substituted args containing placeholders`() {
        // Single pass: the literal "{1}" inside the first arg must survive
        // verbatim instead of being replaced by the second arg.
        assertEquals("A{1}B and C", Strings.format("{0} and {1}", arrayOf("A{1}B", "C")))
        assertEquals("{2}-{2}", Strings.format("{0}-{0}", arrayOf("{2}")))
    }

    @Test
    fun `format keeps placeholders for missing args`() {
        assertEquals("only then {1}", Strings.format("{0} then {1}", arrayOf("only")))
        assertEquals("{0}-{1}", Strings.format("{0}-{1}", arrayOf<Any>()))
    }

    @Test
    fun `format keeps non-numeric placeholders`() {
        assertEquals("x{a}y", Strings.format("x{a}y", arrayOf("v")))
    }

    @Test
    fun `get interpolates args from bundles`() {
        Strings.setLocale("en")
        assertEquals("5 manga in library", Strings.get("migration_manga_count", 5))
    }

    // ── Locale switching (live) ────────────────────────────────────────

    @Test
    fun `setLocale switches resolved strings without recreating the object`() {
        Strings.setLocale("id")
        assertEquals("Pustaka", Strings.get("tab_library"))

        Strings.setLocale("en")
        assertEquals("Library", Strings.get("tab_library"))
    }

    @Test
    fun `setLocale accepts the java legacy code in for indonesian`() {
        Strings.setLocale("in")
        assertEquals("Pustaka", Strings.get("tab_library"))
    }

    @Test
    fun `unknown locale tag falls back to english`() {
        Strings.setLocale("xx")
        assertEquals("Library", Strings.get("tab_library"))
    }

    @Test
    fun `languageTick bumps on every locale switch`() {
        val before = Strings.languageTick.intValue
        Strings.setLocale("id")
        val afterFirst = Strings.languageTick.intValue
        Strings.setLocale("en")

        assertTrue("tick must increase on setLocale", afterFirst > before)
        assertTrue("tick must increase again", Strings.languageTick.intValue > afterFirst)
    }

    @Test
    fun `indonesian bundle actually translates a known key`() {
        Strings.setLocale("id")
        assertEquals("Pengaturan", Strings.get("settings_title"))
        assertEquals("Kembali", Strings.get("common_back"))
    }

    /** Loads a bundle the same way Strings does (UTF-8 properties from the classpath). */
    private fun loadBundle(resource: String): Map<String, String> {
        val stream = javaClass.classLoader!!.getResourceAsStream(resource)
            ?: error("$resource not on test classpath")
        val props = Properties()
        stream.use { props.load(it.reader(Charsets.UTF_8)) }
        return props.stringPropertyNames().associate { it to props.getProperty(it) }
    }
}
