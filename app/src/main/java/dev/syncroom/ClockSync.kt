// Vendored from syncroom@6b4705e (sdk/kotlin) by scripts/sync-syncroom.sh. Do not edit here.
package dev.syncroom

/**
 * Server clock estimate. NTP-style: offset = serverTime + rtt/2 − t1, keeping
 * the sample with the smallest RTT among the last [size] (least queuing noise).
 * Residual error ≈ half the up/down path asymmetry.
 */
class ClockSync(private val size: Int = 8, private val localNow: () -> Double) {
    private class Sample(val rtt: Double, val offset: Double)

    private val samples = ArrayDeque<Sample>()
    private var best: Sample? = null

    /** t0 = local send time, t1 = local receive time (same clock as localNow). */
    fun add(t0: Double, serverTime: Double, t1: Double) {
        val rtt = maxOf(0.0, t1 - t0)
        samples.addLast(Sample(rtt, serverTime + rtt / 2 - t1))
        if (samples.size > size) samples.removeFirst()
        best = samples.minBy { it.rtt }
    }

    /** Rough offset from a one-way server timestamp; any real sample replaces it. */
    fun seed(serverTime: Double) {
        if (best != null) return
        best = Sample(Double.MAX_VALUE, serverTime - localNow())
    }

    val ready get() = best != null
    val offset get() = best?.offset ?: 0.0
    val rtt get() = best?.rtt?.takeIf { it != Double.MAX_VALUE } ?: 0.0

    /** Estimated server time (ms). */
    fun now() = localNow() + offset
    fun local() = localNow()
}
