package mihon.desktop.loader.js

import java.io.File
import java.util.concurrent.TimeUnit
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager

/**
 * JavaScript engine for Mihon extensions.
 *
 * Tries, in order:
 * 1. **QuickJS JVM** (`app.cash.quickjs:quickjs-jvm`) — real QuickJS, sandboxed, lightweight.
 * 2. **javax.script** (Nashorn on JDK 8-14, GraalJS if on classpath) — fallback.
 * 3. Throws [UnsupportedOperationException] if neither is available.
 *
 * Extensions that execute JS (e.g. for parsing page lists or image URLs) call into
 * `eu.kanade.tachiyomi.network.interceptor.quickjs.QuickJSInterceptor` which
 * delegates to this engine.
 *
 * QuickJS is `compileOnly` — add `app.cash.quickjs:quickjs-jvm:0.9.2` to your
 * runtime classpath to enable it.
 */
object DesktopJavaScriptEngine {
    private enum class EngineType { QUICKJS, JAVAX_SCRIPT, NONE }

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
     */
    fun evaluate(script: String, filename: String = "script.js"): String {
        return when (engineType) {
            EngineType.QUICKJS -> evaluateWithQuickJs(script, filename)
            EngineType.JAVAX_SCRIPT -> evaluateWithJavaxScript(script, filename)
            EngineType.NONE -> throw UnsupportedOperationException(
                "No JavaScript engine available. " +
                    "Add app.cash.quickjs:quickjs-jvm to enable QuickJS, " +
                    "or use JDK 8-14 for Nashorn. Extension: $filename"
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
        return try {
            Class.forName("app.cash.quickjs.QuickJs")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    private fun evaluateWithQuickJs(script: String, filename: String): String {
        // Use reflection to avoid compile-time dependency
        val quickJsClass = Class.forName("app.cash.quickjs.QuickJs")
        val createMethod = quickJsClass.getMethod("create")
        val engine = createMethod.invoke(null)

        return try {
            val evalMethod = quickJsClass.getMethod("evaluate", String::class.java)
            val result = evalMethod.invoke(engine, script)
            result?.toString() ?: ""
        } finally {
            val closeMethod = quickJsClass.getMethod("close")
            closeMethod.invoke(engine)
        }
    }

    // --- javax.script fallback ---

    private fun isJavaxScriptAvailable(): Boolean {
        return javaxEngine != null
    }

    private fun evaluateWithJavaxScript(script: String, filename: String): String {
        val eng = javaxEngine ?: throw UnsupportedOperationException("No javax.script engine available")
        return eng.eval(script)?.toString() ?: ""
    }
}
