package mihon.desktop.loader.js

import app.cash.quickjs.QuickJs
import java.io.File
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager

/**
 * JavaScript engine for Mihon extensions.
 *
 * Tries, in order:
 * 1. **QuickJS** — real QuickJS via JNI, sandboxed, ES2020-capable (the level of
 *    JS support obfuscated sources and Cloudflare-bypass scripts need). Provided
 *    by the `app.cash.quickjs` compatibility shim in :platform-compat, backed by
 *    `io.github.dokar3:quickjs-kt-jvm` with bundled natives for Linux x64/aarch64,
 *    macOS x64/aarch64 and Windows x64.
 * 2. **javax.script** (GraalJS if on classpath; Nashorn existed only on JDK 8-14) — fallback.
 * 3. Throws [UnsupportedOperationException] with the exact probe failure if neither works.
 *
 * Extensions that execute JS (e.g. for parsing page lists or image URLs) call the
 * `app.cash.quickjs.QuickJs` shim in :platform-compat directly — that is the API
 * surface they were compiled against. A stand-in for Mihon's
 * `QuickJSInterceptor` that routes through this engine is future work.
 *
 * FQCN compatibility: extension jars reference `app.cash.quickjs.QuickJs` by exact
 * name (same binding Mihon Android uses); the shim in :platform-compat provides it.
 * [QuickJsBridge] isolates those imports so that even if the shim or its native
 * library fails to load, this class still loads and reports a clear error instead
 * of crashing with `NoClassDefFoundError`.
 *
 * [QuickJs] instances are not thread-safe, so a fresh instance is created (and
 * closed) per evaluation — QuickJS context creation is cheap and this keeps the
 * engine safe for concurrent use from download threads.
 */
object DesktopJavaScriptEngine {
    private enum class EngineType { QUICKJS, JAVAX_SCRIPT, NONE }

    /** Why the QuickJS probe failed, if it did — surfaced in the NONE error message. */
    private var quickJsProbeFailure: String? = null

    private val engineType: EngineType by lazy {
        when {
            isQuickJsAvailable() -> EngineType.QUICKJS
            isJavaxScriptAvailable() -> EngineType.JAVAX_SCRIPT
            else -> EngineType.NONE
        }
    }

    private val javaxEngine: ScriptEngine? by lazy {
        ScriptEngineManager().getEngineByName("nashorn")
            ?: ScriptEngineManager().getEngineByName("js")
            ?: ScriptEngineManager().getEngineByName("graal.js")
    }

    /**
     * Evaluate JavaScript source code and return the result as a string.
     *
     * @param script the JavaScript source to execute
     * @param filename optional filename for error reporting
     * @return the string representation of the result
     * @throws UnsupportedOperationException if no JS engine is available
     * @throws app.cash.quickjs.QuickJsException on JS syntax/runtime errors (QuickJS path)
     */
    fun evaluate(script: String, filename: String = "script.js"): String {
        return when (engineType) {
            EngineType.QUICKJS -> QuickJsBridge.evaluate(script, filename)
            EngineType.JAVAX_SCRIPT -> evaluateWithJavaxScript(script)
            EngineType.NONE -> throw UnsupportedOperationException(
                "No JavaScript engine available (script: $filename). " +
                    "QuickJS probe failed: ${quickJsProbeFailure ?: "unknown reason"}. " +
                    "quickjs-kt bundles natives for Linux x64/aarch64, macOS x64/aarch64 " +
                    "and Windows x64; on other platforms add a javax.script engine " +
                    "(e.g. GraalJS) to the classpath."
            )
        }
    }

    /**
     * Evaluate a JavaScript file and return the result.
     */
    fun evaluateFile(file: File): String {
        if (!file.exists()) throw IllegalArgumentException("Script file not found: ${file.absolutePath}")
        return evaluate(file.readText(), file.name)
    }

    /**
     * Check if a JavaScript engine is available.
     */
    fun isAvailable(): Boolean = engineType != EngineType.NONE

    /**
     * Get the name of the available JS engine, or null.
     */
    fun engineName(): String? = when (engineType) {
        EngineType.QUICKJS -> "QuickJS"
        EngineType.JAVAX_SCRIPT -> javaxEngine?.javaClass?.simpleName
        EngineType.NONE -> null
    }

    // --- QuickJS via app.cash.quickjs ---

    private fun isQuickJsAvailable(): Boolean {
        // Loading QuickJsBridge (and from it app.cash.quickjs.QuickJs) can fail
        // with ClassNotFoundException / NoClassDefFoundError (jar absent) or
        // UnsatisfiedLinkError / IllegalStateException (native lib unsupported
        // for the OS/arch) — all Throwable, none should crash the app.
        return try {
            quickJsProbeFailure = null
            QuickJsBridge.probe()
            true
        } catch (t: Throwable) {
            quickJsProbeFailure = "${t.javaClass.simpleName}: ${t.message}"
            false
        }
    }

    // --- javax.script fallback ---

    private fun isJavaxScriptAvailable(): Boolean = javaxEngine != null

    private fun evaluateWithJavaxScript(script: String): String {
        val eng = javaxEngine ?: throw UnsupportedOperationException("No javax.script engine available")
        return eng.eval(script)?.toString() ?: ""
    }

    /**
     * Isolation boundary for `app.cash.quickjs` imports. Referenced only after
     * [isQuickJsAvailable] succeeds (or inside its try/catch), so a missing jar
     * degrades to a reported failure instead of a hard class-load crash of
     * [DesktopJavaScriptEngine] itself.
     */
    private object QuickJsBridge {
        /** Round-trip a trivial eval to prove both class-loading and native lib work. */
        fun probe() {
            QuickJs.create().use { engine ->
                engine.evaluate("1")
            }
        }

        fun evaluate(script: String, filename: String): String {
            QuickJs.create().use { engine ->
                // evaluate() returns null for JS `undefined`
                return engine.evaluate(script, filename)?.toString() ?: ""
            }
        }
    }
}
