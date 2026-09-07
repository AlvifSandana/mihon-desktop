package android.os

/**
 * Extensions commonly use this for monotonic timing in rate-limit interceptors.
 * System.nanoTime()-based elapsedRealtime is a faithful desktop substitute.
 */
object SystemClock {
    @JvmStatic
    fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000L
}
