package mihon.desktop.loader.tracker

import java.security.MessageDigest
import java.security.SecureRandom

/** Randomness seam so PKCE verifier generation is deterministic in tests. */
fun interface RandomSource {
    fun nextInt(bound: Int): Int
}

/** One shared instance (self-seeding, thread-safe); never re-instantiated per draw. */
private val secureRandom = SecureRandom()

val secureRandomSource: RandomSource = RandomSource { bound -> synchronized(secureRandom) { secureRandom.nextInt(bound) } }

/**
 * RFC 7636 PKCE helpers (plain BASE64URL, no padding).
 * MAL only supports the S256 challenge method.
 */
object Pkce {
    /** RFC 7636 recommends 43-128 chars from the unreserved set. */
    const val VERIFIER_LENGTH = 128
    private const val CHARS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    fun generateVerifier(random: RandomSource = secureRandomSource): String =
        buildString {
            repeat(VERIFIER_LENGTH) { append(CHARS[random.nextInt(CHARS.length)]) }
        }

    fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
