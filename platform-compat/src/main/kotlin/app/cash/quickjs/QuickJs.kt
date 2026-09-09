@file:Suppress("unused")

package app.cash.quickjs

import com.dokar.quickjs.QuickJs as DokarQuickJs
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Compatibility shim for Cash App's `app.cash.quickjs:quickjs-jvm` binding.
 *
 * Extension jars are compiled against Mihon Android, which links
 * `app.cash.quickjs.QuickJs` by exact name (verified against keiyoushi's
 * extension sources: `QuickJs.create()`, `evaluate(String)`,
 * `compile(String, String)`, `execute(ByteArray)`, `close()` — no `set`/`get`
 * marshaling in the wild). This class reproduces that exact FQCN and API
 * surface so extension bytecode loads and links without recompilation.
 *
 * Why not use the real `app.cash.quickjs:quickjs-jvm` artifact? Its Linux
 * native (`libquickjs.so`) links against `libc++.so.1`/`libc++abi.so.1`,
 * which stock Debian/Ubuntu/Fedora systems do not ship — every user would
 * need a manual `apt install libc++1`. dokar's `quickjs-kt` bundles
 * self-contained natives (plain libc/libm only) for Linux x64/aarch64,
 * macOS x64/aarch64 and Windows x64, which is what a desktop app needs.
 *
 * Semantics matched to the cashapp binding:
 *  - `evaluate` returns the plain JS value: `String` for strings,
 *    `Boolean` for booleans, a number type for numbers, `null` for
 *    `undefined`/`null`.
 *  - JS syntax/runtime errors surface as [QuickJsException]
 *    (`RuntimeException`) — the same exception type extension code catches.
 *  - Instances are single-threaded; create one per unit of work (extensions
 *    already follow the `QuickJs.create().use { ... }` pattern).
 *  - Bytecode returned by [compile] can be executed on a *different*
 *    instance via [execute] (the Mangago extension relies on this).
 */
class QuickJs private constructor(
    private val engine: DokarQuickJs,
) : Closeable {

    companion object {
        @JvmStatic
        fun create(): QuickJs = try {
            QuickJs(DokarQuickJs.create(Dispatchers.IO))
        } catch (t: Throwable) {
            // Native library load failures (missing .so/.dll for the current
            // OS/arch, UnsatisfiedLinkError, ...) must not escape as raw
            // Errors — callers expect QuickJsException like the cashapp
            // binding throws for engine failures. Keep the original throwable
            // as the cause so probe failure messages stay diagnosable.
            throw QuickJsException("Failed to create QuickJS instance: ${t.message}").apply {
                initCause(t)
            }
        }
    }

    /** Evaluates [script] and returns the resulting JS value, or null for `undefined`. */
    fun evaluate(script: String): Any? = evaluate(script, "script.js")

    /** Evaluates [script], reporting [filename] in error messages. */
    fun evaluate(script: String, filename: String): Any? {
        val result: Any? = bridge {
            runBlocking { engine.evaluate(script, filename, false) }
        }
        return normalizeNumber(result)
    }

    /** Compiles [script] to QuickJS bytecode that [execute] can run. */
    fun compile(script: String, filename: String): ByteArray = bridge {
        engine.compile(script, filename, false)
    }

    /** Executes QuickJS bytecode produced by [compile] (on any instance). */
    fun execute(bytecode: ByteArray): Any? {
        val result: Any? = bridge {
            runBlocking { engine.evaluate(bytecode) }
        }
        return normalizeNumber(result)
    }

    override fun close() {
        engine.close()
    }

    /**
     * Not supported by the desktop shim: no extension in keiyoushi's repo
     * marshals Java objects into JS (verified by source scan), and the dokar
     * binding models interop differently. Present for binary compatibility;
     * fails loudly if ever reached.
     */
    fun <T : Any> set(name: String, type: Class<T>, value: T): Unit =
        throw QuickJsException("QuickJs.set() Java-object marshaling is not supported on desktop")

    /** See [set]. */
    fun <T : Any> get(name: String, type: Class<T>): T =
        throw QuickJsException("QuickJs.get() Java-object marshaling is not supported on desktop")

    private inline fun <R> bridge(block: () -> R): R = try {
        block()
    } catch (e: com.dokar.quickjs.QuickJsException) {
        // Re-type dokar's exception into the cashapp exception class extension
        // bytecode expects, preserving message and cause.
        val converted = QuickJsException(e.message ?: "QuickJS error")
        converted.initCause(e.cause ?: e)
        throw converted
    }

    /**
     * The backend maps integral JS numbers to Long; the cashapp binding returned
     * Integer for JS int32 values, and extension code may store results in
     * `int` fields. Narrow Longs that fit to keep that contract.
     */
    private fun normalizeNumber(result: Any?): Any? = when (result) {
        is Long -> if (result in Int.MIN_VALUE..Int.MAX_VALUE) result.toInt() else result
        else -> result
    }
}

/** Mirrors `app.cash.quickjs.QuickJsException` (extends RuntimeException). */
class QuickJsException : RuntimeException {
    constructor(message: String) : super(message)
    constructor(message: String, fileName: String) : super("$fileName: $message")

    companion object {
        private const val serialVersionUID = 0L
    }
}
