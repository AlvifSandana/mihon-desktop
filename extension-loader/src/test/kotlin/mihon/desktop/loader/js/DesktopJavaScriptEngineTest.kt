package mihon.desktop.loader.js

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Real-JS evaluation tests for [DesktopJavaScriptEngine].
 *
 * The QuickJS native library (libquickjs.so / .dylib / .dll) ships for Linux
 * x64/aarch64, macOS x64/aarch64, and Windows x64. If it cannot load (exotic
 * arch, headless CI container with a broken toolchain), these tests are skipped
 * via [assumeTrue] rather than failing — on supported platforms they run for real.
 */
class DesktopJavaScriptEngineTest {

    private fun assumeEngineAvailable() {
        assumeTrue(
            "QuickJS engine not available on this platform: skipping real-JS tests",
            DesktopJavaScriptEngine.isAvailable(),
        )
    }

    @Test
    fun `simple expression evaluates`() {
        assumeEngineAvailable()
        assertEquals("2", DesktopJavaScriptEngine.evaluate("1 + 1"))
    }

    @Test
    fun `define function and call it`() {
        assumeEngineAvailable()
        val script = """
            function add(a, b) {
                return a + b;
            }
            add(20, 22)
        """.trimIndent()
        assertEquals("42", DesktopJavaScriptEngine.evaluate(script, "add.js"))
    }

    @Test
    fun `es6 arrow function template literal and destructuring work`() {
        assumeEngineAvailable()
        // ES6+ features that obfuscated sources and CF-bypass scripts rely on.
        val script = """
            const { x, y } = { x: 1, y: 2 };
            const join = (tag) => `${'$'}{tag}-${'$'}{x}-${'$'}{y}`;
            join("mihon")
        """.trimIndent()
        assertEquals("mihon-1-2", DesktopJavaScriptEngine.evaluate(script, "es6.js"))
    }

    @Test
    fun `es2020 optional chaining and nullish coalescing work`() {
        assumeEngineAvailable()
        val script = """
            const obj = { a: { b: "deep" } };
            const v = obj?.a?.b ?? "fallback";
            v + (obj?.missing?.c ?? "-ok")
        """.trimIndent()
        assertEquals("deep-ok", DesktopJavaScriptEngine.evaluate(script, "es2020.js"))
    }

    @Test
    fun `syntax error propagates as exception with message`() {
        assumeEngineAvailable()
        try {
            DesktopJavaScriptEngine.evaluate("function broken( {", "broken.js")
            throw AssertionError("Expected a JS exception for a syntax error")
        } catch (e: Exception) {
            // QuickJS reports syntax errors as QuickJsException containing "SyntaxError"
            assertTrue(
                "expected SyntaxError in message, got: ${e.message}",
                e.message?.contains("SyntaxError") == true,
            )
        }
    }

    @Test
    fun `runtime error propagates as exception with message`() {
        assumeEngineAvailable()
        try {
            DesktopJavaScriptEngine.evaluate("throw new Error('boom')", "boom.js")
            throw AssertionError("Expected a JS exception for a thrown error")
        } catch (e: Exception) {
            assertTrue(
                "expected 'boom' in message, got: ${e.message}",
                e.message?.contains("boom") == true,
            )
        }
    }

    @Test
    fun `undefined result coerces to empty string`() {
        assumeEngineAvailable()
        assertEquals("", DesktopJavaScriptEngine.evaluate("undefined"))
    }

    @Test
    fun `string result round-trips`() {
        assumeEngineAvailable()
        assertEquals("hello world", DesktopJavaScriptEngine.evaluate("'hello' + ' ' + 'world'"))
    }

    @Test
    fun `evaluateFile reads and evaluates a file`() {
        assumeEngineAvailable()
        val file = File.createTempFile("mihon-js-test", ".js").apply {
            deleteOnExit()
            writeText("6 * 7")
        }
        assertEquals("42", DesktopJavaScriptEngine.evaluateFile(file))
    }

    @Test
    fun `missing script file fails with clear message`() {
        val missing = File("does-not-exist-${System.nanoTime()}.js")
        try {
            DesktopJavaScriptEngine.evaluateFile(missing)
            throw AssertionError("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Script file not found"))
        }
    }

    @Test
    fun `engine reports availability and name`() {
        assumeEngineAvailable()
        assertTrue(DesktopJavaScriptEngine.isAvailable())
        assertNotNull(DesktopJavaScriptEngine.engineName())
        // On a stock JDK 21 with the bundled quickjs-jvm, QuickJS wins over javax.script.
        assertEquals("QuickJS", DesktopJavaScriptEngine.engineName())
    }

    @Test
    fun `repeated evaluations stay isolated`() {
        assumeEngineAvailable()
        // Each evaluate() gets a fresh QuickJS context; no state must leak between calls.
        DesktopJavaScriptEngine.evaluate("var leaked = 123", "leak.js")
        assertEquals("", DesktopJavaScriptEngine.evaluate("typeof leaked === 'undefined' ? '' : leaked"))
    }
}
