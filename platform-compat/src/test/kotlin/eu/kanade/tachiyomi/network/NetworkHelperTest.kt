package eu.kanade.tachiyomi.network

import android.app.Application
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NetworkHelperTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun networkHelper(
        dohEnabled: Boolean = false,
        dohProvider: String = DohDns.PROVIDER_GOOGLE,
    ) = NetworkHelper(Application(tempFolder.newFolder()), dohEnabled, dohProvider)

    @Test
    fun `doh disabled uses system dns`() {
        val helper = networkHelper(dohEnabled = false)

        assertSame(Dns.SYSTEM, helper.client.dns)
    }

    @Test
    fun `doh enabled uses fallback-wrapped doh dns`() {
        val helper = networkHelper(dohEnabled = true, dohProvider = DohDns.PROVIDER_CLOUDFLARE)

        assertTrue(helper.client.dns is FallbackDns)
    }

    @Test
    fun `interceptors are wired in upstream mihon order`() {
        // Extensions defensively assert UncaughtExceptionInterceptor /
        // UserAgentInterceptor / CloudflareInterceptor are on the client;
        // upstream Mihon registers them in exactly this order.
        val interceptors = networkHelper().client.interceptors

        assertEquals(3, interceptors.size)
        assertTrue(interceptors[0] is UncaughtExceptionInterceptor)
        assertTrue(interceptors[1] is UserAgentInterceptor)
        assertTrue(interceptors[2] is CloudflareInterceptor)
    }
}
