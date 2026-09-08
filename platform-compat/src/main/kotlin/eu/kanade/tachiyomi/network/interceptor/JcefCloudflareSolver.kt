package eu.kanade.tachiyomi.network.interceptor

import eu.kanade.tachiyomi.network.AndroidCookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Solves Cloudflare JS challenges using JCEF (Java Chromium Embedded Framework).
 *
 * This class uses reflection to load JCEF classes, so it only works when
 * `me.friwi:jcefmaven` is on the runtime classpath.
 *
 * Usage:
 * ```
 * val solver = JcefCloudflareSolver(cookieJar) { userAgent }
 * val solved = solver.solve("https://example.com", 30)
 * ```
 */
class JcefCloudflareSolver(
    private val cookieJar: AndroidCookieJar,
    private val userAgentProvider: () -> String,
) {
    private var cefApp: Any? = null
    private var cefClient: Any? = null

    /**
     * Solve a Cloudflare challenge for the given URL.
     * Returns true if the challenge was solved (cookies set).
     */
    fun solve(url: String, timeoutSeconds: Long): Boolean {
        return try {
            solveInternal(url, timeoutSeconds)
        } catch (e: Exception) {
            System.err.println("JCEF Cloudflare solver failed: ${e.message}")
            false
        }
    }

    private fun solveInternal(url: String, timeoutSeconds: Long): Boolean {
        // Initialize JCEF if not already done
        if (cefApp == null) {
            initJcef()
        }

        val app = cefApp ?: return false
        val client = cefClient ?: return false

        val latch = CountDownLatch(1)
        var solved = false

        // Create a browser to solve the challenge
        val browserClass = Class.forName("org.cef.CefClient")
        val createBrowserMethod = browserClass.getMethod("createBrowser", String::class.java, Boolean::class.java, Boolean::class.java, Any::class.java)
        val browser = createBrowserMethod.invoke(client, url, false, false, null)

        // Add a display handler to detect when the challenge is solved
        val displayHandlerClass = Class.forName("org.cef.handler.CefDisplayHandlerAdapter")
        val displayHandler = java.lang.reflect.Proxy.newProxyInstance(
            displayHandlerClass.classLoader,
            arrayOf(displayHandlerClass),
        ) { _, method, args ->
            when (method.name) {
                "onTitleChange" -> {
                    val title = args?.getOrNull(1) as? String ?: ""
                    // Cloudflare redirects to the original page after solving
                    if (!title.contains("Just a moment", ignoreCase = true) &&
                        !title.contains("Checking your browser", ignoreCase = true)
                    ) {
                        solved = true
                        latch.countDown()
                    }
                }
                "onLoadStart" -> {
                    // Page started loading, might be the redirect after challenge
                }
                else -> null
            }
        }

        // Register the display handler
        val addDisplayHandlerMethod = browserClass.getMethod("addDisplayHandler", Class.forName("org.cef.handler.CefDisplayHandler"))
        addDisplayHandlerMethod.invoke(browser, displayHandler)

        // Wait for the challenge to be solved
        val completed = latch.await(timeoutSeconds, TimeUnit.SECONDS)

        // Clean up
        val closeMethod = browserClass.getMethod("close", Boolean::class.java)
        closeMethod.invoke(browser, true)

        return completed && solved
    }

    private fun initJcef() {
        try {
            val builderClass = Class.forName("me.friwi.jcefmaven.CefAppBuilder")
            val builder = builderClass.getDeclaredConstructor().newInstance()

            // Set install directory
            val installDir = File(System.getProperty("user.home"), ".mihon-desktop/jcef-bundle")
            installDir.mkdirs()
            val setInstallDirMethod = builderClass.getMethod("setInstallDir", File::class.java)
            setInstallDirMethod.invoke(builder, installDir)

            // Configure JCEF settings
            val settingsClass = Class.forName("org.cef.CefSettings")
            val settings = settingsClass.getDeclaredConstructor().newInstance()
            val setMethod = settingsClass.getMethod("setLogSeverity", settingsClass)
            setMethod.invoke(settings, settingsClass.getField("LOGSEVERITY_DISABLE").get(null))

            // Build the CefApp
            val buildMethod = builderClass.getMethod("build")
            val app = buildMethod.invoke(builder)

            cefApp = app

            // Create a client
            val getClientMethod = app.javaClass.getMethod("createClient")
            cefClient = getClientMethod.invoke(app)

        } catch (e: Exception) {
            System.err.println("Failed to initialize JCEF: ${e.message}")
            cefApp = null
            cefClient = null
        }
    }

    /**
     * Shutdown JCEF when done.
     */
    fun shutdown() {
        try {
            cefClient?.let { client ->
                val disposeMethod = client.javaClass.getMethod("dispose")
                disposeMethod.invoke(client)
            }
            cefApp?.let { app ->
                val disposeMethod = app.javaClass.getMethod("dispose")
                disposeMethod.invoke(app)
            }
        } catch (_: Exception) {
            // Ignore shutdown errors
        }
        cefClient = null
        cefApp = null
    }
}
