package app.sanctum.machina.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Pure-JVM contract tests for [DiagnosticsState]. Covers the six single-thread
 * branches plus a concurrent reader/writer atomicity test (≥10 000 iterations,
 * raw threads — no Robolectric, no MockK, no jcstress).
 */
class DiagnosticsStateTest {

    @Test
    fun initialStateIsNull() {
        val state = DiagnosticsState()
        assertNull(
            "fresh DiagnosticsState must report null — readers depend on null = «none yet»",
            state.lastInitSnapshot(),
        )
    }

    @Test
    fun onInitStartProducesInProgressSnapshot() {
        val state = DiagnosticsState()
        state.onInitStart("model-x", FREE_RAM_A, AT_A)
        val snapshot = state.lastInitSnapshot()
        assertEquals(InitSnapshot("model-x", FREE_RAM_A, AT_A, Outcome.InProgress), snapshot)
    }

    @Test
    fun onInitEndTrueProducesOkAndPreservesFields() {
        val state = DiagnosticsState()
        state.onInitStart("model-x", FREE_RAM_A, AT_A)
        state.onInitEnd(true)
        assertEquals(InitSnapshot("model-x", FREE_RAM_A, AT_A, Outcome.Ok), state.lastInitSnapshot())
    }

    @Test
    fun onInitEndFalseProducesFailedAndPreservesFields() {
        val state = DiagnosticsState()
        state.onInitStart("model-x", FREE_RAM_A, AT_A)
        state.onInitEnd(false)
        assertEquals(
            InitSnapshot("model-x", FREE_RAM_A, AT_A, Outcome.Failed),
            state.lastInitSnapshot(),
        )
    }

    @Test
    fun onInitEndWithoutStartIsNoop() {
        val state = DiagnosticsState()
        state.onInitEnd(true)
        assertNull(
            "updateAndGet on null must remain null — defence-in-depth contract for «end without start»",
            state.lastInitSnapshot(),
        )
    }

    @Test
    fun secondOnInitStartReplacesPreviousAttempt() {
        val state = DiagnosticsState()
        state.onInitStart("modelA", FREE_RAM_A, AT_A)
        state.onInitStart("modelB", FREE_RAM_B, AT_B)
        assertEquals(
            InitSnapshot("modelB", FREE_RAM_B, AT_B, Outcome.InProgress),
            state.lastInitSnapshot(),
        )
    }

    /**
     * Guards visibility + immutable-snapshot atomicity: the reader must never see a
     * snapshot whose fields are blended from two different attempts. Because every
     * mutation goes through `data class copy` (a fresh object), this test does not
     * exercise CAS lost-update — that property is structural to `AtomicReference`
     * itself and is the reason Decision 7 picked it over `@Volatile var`. The
     * `observedNonNull` counter ensures the race window was actually exercised
     * (i.e. the reader did not miss every write under unfortunate scheduling).
     */
    @Test
    fun concurrentWriterReaderNeverSeesMixedState() {
        val state = DiagnosticsState()
        val iterations = 10_000
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val readerFailure = AtomicReference<AssertionError?>(null)
        val observedNonNull = AtomicInteger(0)

        val writer = Thread {
            start.await()
            try {
                repeat(iterations) { i ->
                    if (i % 2 == 0) {
                        state.onInitStart("modelA", FREE_RAM_A, AT_A)
                        state.onInitEnd(true)
                    } else {
                        state.onInitStart("modelB", FREE_RAM_B, AT_B)
                        state.onInitEnd(false)
                    }
                }
            } finally {
                done.countDown()
            }
        }

        val reader = Thread {
            start.await()
            try {
                repeat(iterations) {
                    val snap = state.lastInitSnapshot() ?: return@repeat
                    observedNonNull.incrementAndGet()
                    val valid = when (snap.modelName) {
                        "modelA" -> snap.freeRamBytes == FREE_RAM_A &&
                            snap.atEpochMs == AT_A &&
                            (snap.outcome == Outcome.InProgress || snap.outcome == Outcome.Ok)
                        "modelB" -> snap.freeRamBytes == FREE_RAM_B &&
                            snap.atEpochMs == AT_B &&
                            (snap.outcome == Outcome.InProgress || snap.outcome == Outcome.Failed)
                        else -> false
                    }
                    if (!valid) {
                        readerFailure.set(
                            AssertionError("mixed-state snapshot observed: $snap"),
                        )
                        return@Thread
                    }
                }
            } finally {
                done.countDown()
            }
        }

        writer.start()
        reader.start()
        start.countDown()
        if (!done.await(30, TimeUnit.SECONDS)) {
            writer.interrupt()
            reader.interrupt()
            fail("concurrent test timed out before $iterations iterations completed")
        }

        readerFailure.get()?.let { throw it }
        if (observedNonNull.get() == 0) {
            fail("reader observed only null — race window was not exercised, test is vacuous")
        }
    }

    private companion object {
        const val FREE_RAM_A = 4_000_000_000L
        const val AT_A = 1_700_000_000_000L
        const val FREE_RAM_B = 2_500_000_000L
        const val AT_B = 1_700_000_001_000L
    }
}
