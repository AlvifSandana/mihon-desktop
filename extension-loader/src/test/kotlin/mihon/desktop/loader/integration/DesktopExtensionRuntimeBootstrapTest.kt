package mihon.desktop.loader.integration

import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import mihon.desktop.loader.DesktopExtensionRuntime
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * [DesktopExtensionRuntime.bootstrap] integration: the Injekt singletons that
 * `:source-api` / `:platform-compat` classes (and every extension jar's
 * bytecode) resolve at class-load time must be registered and usable.
 *
 * Hermeticity notes:
 * - bootstrap's only input is AppPreferences (the DoH flags). AppPreferences
 *   resolves its properties file from `user.home` at object-init time with no
 *   seam, and bootstrap only ever READS it — so pointing it at a temp home
 *   would neither be reliable (object may already be class-loaded by earlier
 *   tests in this JVM) nor necessary. One side effect to be honest about:
 *   AppPreferences's object-init does `mkdirs()` on the parent, so it may
 *   create an empty `~/.mihon-desktop/` directory; no properties file is
 *   ever written and existing preferences are only read. The NetworkHelper
 *   is asserted to be buildable either way, never to hit the network
 *   (building the client performs no I/O).
 * - bootstrap is process-global and guarded by its `bootstrapped` flag, so
 *   these assertions hold regardless of whether another test booted the
 *   runtime first — the idempotency check pins that second call as a no-op.
 */
class DesktopExtensionRuntimeBootstrapTest {

    @Test
    fun `bootstrap registers the Injekt singletons extension code expects`() {
        DesktopExtensionRuntime.bootstrap()

        // Context and Application are the same stub instance.
        val context = Injekt.get<Context>()
        val app = Injekt.get<android.app.Application>()
        assertSame("Context and Application should be one shared instance", app, context)
        assertNotNull(context)

        // NetworkHelper builds its OkHttpClient eagerly at bootstrap time --
        // no network calls involved in building it.
        val network = Injekt.get<NetworkHelper>()
        val client: OkHttpClient = network.client
        assertNotNull(client)
        // Building a request from the client exercises its interceptor chain
        // construction without issuing anything.
        assertNotNull(client.newBuilder().build())

        // Json instance with the source-api-friendly config.
        val json = Injekt.get<Json>()
        assertNotNull(json)
    }

    @Test
    fun `second bootstrap is a no-op`() {
        DesktopExtensionRuntime.bootstrap()
        val context = Injekt.get<Context>()
        val network = Injekt.get<NetworkHelper>()
        val json = Injekt.get<Json>()

        DesktopExtensionRuntime.bootstrap()

        // The bootstrapped flag short-circuits: no singleton is re-registered,
        // so every lookup still yields the same instances. (A broken flag
        // would mint fresh Application/NetworkHelper objects here.)
        assertSame(context, Injekt.get<Context>())
        assertSame(network, Injekt.get<NetworkHelper>())
        assertSame(json, Injekt.get<Json>())
        assertTrue("second bootstrap must not replace Context", Injekt.get<Context>() === context)
    }
}
