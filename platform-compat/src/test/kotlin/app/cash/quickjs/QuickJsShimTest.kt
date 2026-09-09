package app.cash.quickjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tests the `app.cash.quickjs.QuickJs` compatibility shim against the exact
 * usage patterns found in real keiyoushi extension jars (source-scanned):
 * `create()`, `evaluate(String)`, `compile(String, String)`,
 * `execute(ByteArray)`, `close()` via `use`, and `as String` result casts.
 *
 * Skipped (via [assumeTrue]) on platforms where the QuickJS native library
 * cannot load.
 */
class QuickJsShimTest {

    private fun assumeEngineAvailable() {
        assumeTrue(
            "QuickJS native library not available on this platform",
            runCatching { QuickJs.create().use { it.evaluate("1") } }.isSuccess,
        )
    }

    @Test
    fun `evaluate returns cashapp-compatible value types`() {
        assumeEngineAvailable()
        QuickJs.create().use { js ->
            assertEquals("mihon", js.evaluate("'mi' + 'hon'"))
            assertEquals(2, js.evaluate("1 + 1")) // Int, like the cashapp binding
            assertEquals(true, js.evaluate("1 == 1"))
            assertNull(js.evaluate("undefined"))
            assertEquals(2.5, js.evaluate("5 / 2")) // doubles stay doubles
            assertEquals(2.147483648E9, js.evaluate("2147483648")) // beyond int32 is a JS double
        }
    }

    @Test
    fun `evaluate with filename reports syntax errors`() {
        assumeEngineAvailable()
        QuickJs.create().use { js ->
            try {
                js.evaluate("function broken( {", "broken.js")
                fail("Expected QuickJsException for syntax error")
            } catch (e: QuickJsException) {
                // Must be the app.cash.quickjs type, not the backend's.
                assertEquals(QuickJsException::class.java, e.javaClass)
                assertTrue("expected SyntaxError in: ${e.message}", e.message!!.contains("SyntaxError"))
            }
        }
    }

    @Test
    fun `runtime errors surface as app cash quickjs QuickJsException`() {
        assumeEngineAvailable()
        QuickJs.create().use { js ->
            try {
                js.evaluate("nope.data('boom')", "rt.js")
                fail("Expected QuickJsException for runtime error")
            } catch (e: QuickJsException) {
                assertEquals(QuickJsException::class.java, e.javaClass)
                assertTrue(e.message!!.contains("nope"))
            }
        }
    }

    /**
     * The Mangago extension compiles a script on one instance and executes the
     * bytecode on another — bytecode must be portable across instances.
     */
    @Test
    fun `compiled bytecode executes on a different instance`() {
        assumeEngineAvailable()
        val bytecode = QuickJs.create().use { js ->
            js.compile(
                """
                    function replacePos(str, pos, replacement) {
                        return str.substring(0, pos) + replacement + str.substring(pos + 1);
                    }
                """.trimIndent(),
                "?",
            )
        }
        assertTrue("compiled bytecode should not be empty", bytecode.isNotEmpty())

        QuickJs.create().use { js ->
            js.execute(bytecode)
            val result = js.evaluate("replacePos('mihonXdesktop', 5, '-')") as String
            assertEquals("mihon-desktop", result)
        }
    }

    /** The dominant extension pattern: QuickJs.create().use { it.evaluate(...) as String }. */
    @Test
    fun `extension use pattern works`() {
        assumeEngineAvailable()
        val deobfuscated = QuickJs.create().use { js ->
            js.evaluate("JSON.stringify({a: 1, b: [2, 3]})") as String
        }
        assertEquals("""{"a":1,"b":[2,3]}""", deobfuscated)
    }

    @Test
    fun `es2020 features available for cf-bypass scripts`() {
        assumeEngineAvailable()
        QuickJs.create().use { js ->
            // Optional chaining, nullish coalescing, spread, arrow fns —
            // synchrony (the Cloudflare deobfuscator keiyoushi bundles) needs these.
            val out = js.evaluate(
                """
                    const o = { a: { b: { c: 41 } } };
                    (o?.a?.b?.c ?? 0) + 1
                """.trimIndent(),
            )
            assertEquals(42, out)
        }
    }

    @Test
    fun `set is documented as unsupported and fails loudly`() {
        assumeEngineAvailable()
        QuickJs.create().use { js ->
            try {
                js.set("obj", Any::class.java, Any())
                fail("Expected QuickJsException from set()")
            } catch (e: QuickJsException) {
                assertTrue(e.message!!.contains("not supported"))
            }
        }
    }

    @Test
    fun `concurrent instances on separate threads are safe`() {
        assumeEngineAvailable()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 8).map { i ->
                pool.submit(
                    Callable {
                        QuickJs.create().use { js ->
                            val r = js.evaluate("$i * 2") as Int
                            assertEquals(i * 2, r)
                        }
                    },
                )
            }
            // get() rethrows any assertion failure raised on the pool thread.
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
