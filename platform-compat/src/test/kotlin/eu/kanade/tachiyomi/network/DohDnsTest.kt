package eu.kanade.tachiyomi.network

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class DohDnsTest {

    private class FakeDns(
        private val behavior: (String) -> List<InetAddress>,
    ) : Dns {
        var calls = 0
            private set

        override fun lookup(hostname: String): List<InetAddress> {
            calls++
            return behavior(hostname)
        }
    }

    private val address = listOf(InetAddress.getByAddress(byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34.toByte())))

    @Test
    fun `falls back to system dns when primary throws UnknownHostException`() {
        val throwing = FakeDns { throw UnknownHostException("doh unreachable") }
        val fallback = FakeDns { address }

        val result = FallbackDns(throwing, fallback).lookup("example.com")

        assertEquals(address, result)
        assertEquals(1, throwing.calls)
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `falls back to system dns on every failure but keeps trying primary`() {
        val throwing = FakeDns { throw UnknownHostException("still down") }
        val fallback = FakeDns { address }
        val dns = FallbackDns(throwing, fallback)

        dns.lookup("a.example.com")
        dns.lookup("b.example.com")

        // Primary is retried (it may recover), fallback answers each time.
        assertEquals(2, throwing.calls)
        assertEquals(2, fallback.calls)
    }

    @Test
    fun `falls back to system dns when primary returns empty list`() {
        val empty = FakeDns { emptyList() }
        val fallback = FakeDns { address }

        val result = FallbackDns(empty, fallback).lookup("example.com")

        assertEquals(address, result)
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `fallback not consulted when primary succeeds`() {
        val primary = FakeDns { address }
        val fallback = FakeDns { throw UnknownHostException("must not be called") }

        val result = FallbackDns(primary, fallback).lookup("example.com")

        assertEquals(address, result) // no exception thrown — fallback never used
        assertEquals(1, primary.calls)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun `failure of both primary and fallback propagates`() {
        val dns = FallbackDns(
            FakeDns { throw UnknownHostException("primary down") },
            FakeDns { throw UnknownHostException("system down") },
        )

        try {
            dns.lookup("example.com")
            throw AssertionError("expected UnknownHostException")
        } catch (expected: UnknownHostException) {
            assertEquals("system down", expected.message)
        }
    }

    @Test
    fun `known providers map to their endpoints`() {
        assertEquals("dns.google", DohDns.PROVIDER_URLS[DohDns.PROVIDER_GOOGLE]?.host)
        assertEquals("cloudflare-dns.com", DohDns.PROVIDER_URLS[DohDns.PROVIDER_CLOUDFLARE]?.host)
        assertTrue(DohDns.isKnownProvider("google"))
        assertTrue(DohDns.isKnownProvider("cloudflare"))
        assertFalse(DohDns.isKnownProvider("unknown"))
    }

    @Test
    fun `create returns fallback-wrapped dns for known providers without network access`() {
        val google = DohDns.create(DohDns.PROVIDER_GOOGLE, FakeDns { address })
        val cloudflare = DohDns.create(DohDns.PROVIDER_CLOUDFLARE, FakeDns { address })

        assertNotNull(google)
        assertNotNull(cloudflare)
        assertTrue(google is FallbackDns)
        assertTrue(cloudflare is FallbackDns)
    }

    @Test
    fun `create returns null for unknown provider`() {
        assertNull(DohDns.create("opendns"))
    }
}
