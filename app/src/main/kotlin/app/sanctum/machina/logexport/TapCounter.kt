package app.sanctum.machina.logexport

/**
 * Pure-JVM N-tap gesture detector. No Android imports.
 *
 * A "trigger" is [threshold] consecutive [tap] calls where every pair of
 * adjacent calls is separated by no more than [maxGapNanos] nanoseconds
 * (inclusive boundary). Any gap strictly greater than [maxGapNanos] resets
 * the counter to 1. On the [threshold]th in-window tap, [tap] returns `true`
 * exactly once and the internal counter resets to 0 — an immediate follow-up
 * tap starts a new cycle from 1 and does not re-trigger.
 *
 * Not thread-safe: the contract is single-threaded (UI thread). Time is
 * threaded through the [nowNanos] lambda so tests can feed virtual time
 * without platform clock mocking.
 */
class TapCounter(
    private val nowNanos: () -> Long,
    private val maxGapNanos: Long = 2_000_000_000L,
    private val threshold: Int = 7,
) {
    private var count: Int = 0
    private var lastTapNanos: Long = 0L

    fun tap(): Boolean {
        val now = nowNanos()
        count = if (count == 0 || now - lastTapNanos > maxGapNanos) 1 else count + 1
        lastTapNanos = now
        return if (count >= threshold) {
            count = 0
            true
        } else {
            false
        }
    }
}
