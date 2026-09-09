package mihon.desktop.app.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import java.util.Locale
import java.util.Properties

/**
 * Minimal, dependency-free string table for the desktop UI.
 *
 * Bundles live on the classpath as UTF-8 properties files:
 * `strings.properties` (English, always the fallback) and per-language
 * variants such as `strings_id.properties` (Indonesian).
 *
 * Lookup order per key: active-locale bundle → English bundle → the raw
 * key (so a missing translation degrades to English, and a missing key
 * is immediately visible in the UI).
 *
 * Placeholders are positional `{0}`, `{1}`, … — see [format].
 */
object Strings {
    private const val ENGLISH_BUNDLE = "strings.properties"

    // Indonesian. ISO 639-1 modern code is "id"; Java's legacy code is "in"
    // (ResourceBundle on a Locale("id") looks for *_in.properties). We load
    // by explicit file name instead of ResourceBundle, so "id" works — but
    // the legacy "in" is accepted too, in case it leaks in from a
    // system-locale lookup.
    private const val ID_BUNDLE = "strings_id.properties"

    /**
     * Bumped on every locale switch. Composables read it (via [t]) so the
     * whole UI recomposes and re-reads every string — a live language swap
     * without restarting the app.
     */
    val languageTick = mutableIntStateOf(0)

    private val english: Map<String, String> = loadBundle(ENGLISH_BUNDLE)

    // @Volatile: setLocale can run on any thread (startup, pref listeners,
    // tests) while get() reads from the UI thread — the swap must be visible
    // without a lock.
    @Volatile
    private var localeBundle: Map<String, String> = emptyMap()

    /** Applies a persisted preference ("system" or an explicit tag) at startup. */
    fun init(preference: String) = setLocale(preference)

    /**
     * Live-swaps the active locale. "system" resolves through
     * [Locale.getDefault]; unknown tags fall back to English.
     */
    fun setLocale(tag: String) {
        val resolved = if (tag == "system") Locale.getDefault().language else tag
        localeBundle = when (resolved) {
            "id", "in" -> loadBundle(ID_BUNDLE)
            else -> emptyMap() // English is the built-in fallback
        }
        languageTick.intValue++
    }

    /** Resolves [key] against the current locale, then English, then the key itself. */
    fun get(key: String, vararg args: Any?): String {
        val template = resolve(localeBundle, english, key)
        return if (args.isEmpty()) template else format(template, args)
    }

    /** Pure lookup used by [get]; internal so tests can exercise each fallback tier. */
    internal fun resolve(locale: Map<String, String>, english: Map<String, String>, key: String): String =
        locale[key] ?: english[key] ?: key

    /** `{0}`, `{1}`, … — one or more digits in braces. */
    private val PLACEHOLDER = Regex("""\{(\d+)\}""")

    /**
     * Replaces `{0}`, `{1}`, … with the given args (dependency-free
     * MessageFormat-lite). Single pass over the template: substituted values
     * are never re-scanned, so an arg containing a literal `{1}` can't
     * corrupt the result. A placeholder with no corresponding arg (index out
     * of range) or a non-numeric `{…}` is kept verbatim.
     */
    internal fun format(template: String, args: Array<out Any?>): String =
        PLACEHOLDER.replace(template) { match ->
            val index = match.groupValues[1].toIntOrNull()
            if (index != null && index < args.size) args[index]?.toString() ?: "" else match.value
        }

    private fun loadBundle(resource: String): Map<String, String> {
        val stream = Strings::class.java.classLoader?.getResourceAsStream(resource) ?: return emptyMap()
        val props = Properties()
        stream.use { props.load(it.reader(Charsets.UTF_8)) }
        return props.stringPropertyNames().associate { it to props.getProperty(it) }
    }
}

/**
 * Composable string lookup. Reading [Strings.languageTick] subscribes the
 * calling composable to locale changes, so every `t(...)` call site
 * recomposes when the language switches.
 */
@Composable
fun t(key: String, vararg args: Any?): String {
    Strings.languageTick.intValue
    return Strings.get(key, *args)
}
