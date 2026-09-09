package mihon.desktop.loader.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** PKCE (RFC 7636, S256) verifier/challenge generation. */
class PkceTest {

    @Test
    fun `codeChallenge matches the RFC 7636 appendix B test vector`() {
        // https://datatracker.ietf.org/doc/html/rfc7636#appendix-B
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `challenge is unpadded base64url of sha256`() {
        val challenge = Pkce.codeChallenge("abc")
        // 32-byte SHA-256 -> 43 chars, no '=' padding, url-safe alphabet.
        assertEquals(43, challenge.length)
        assertTrue(!challenge.contains('='))
        assertTrue(!challenge.contains('+'))
        assertTrue(!challenge.contains('/'))
    }

    @Test
    fun `verifier is deterministic under a fixed random source`() {
        val seeded: RandomSource = RandomSource { bound -> Random(42).nextInt(bound) }
        val a = Pkce.generateVerifier(seeded)
        val b = Pkce.generateVerifier(RandomSource { bound -> Random(42).nextInt(bound) })
        assertEquals(a, b)
    }

    @Test
    fun `verifier has the documented length and unreserved charset`() {
        val verifier = Pkce.generateVerifier(RandomSource { bound -> Random(7).nextInt(bound) })
        assertEquals(Pkce.VERIFIER_LENGTH, verifier.length)
        assertTrue(verifier.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~" })
    }

    @Test
    fun `secure verifier differs between calls`() {
        assertNotEquals(Pkce.generateVerifier(), Pkce.generateVerifier())
    }

    @Test
    fun `challenge depends on the verifier`() {
        assertNotEquals(Pkce.codeChallenge("v1"), Pkce.codeChallenge("v2"))
    }
}
