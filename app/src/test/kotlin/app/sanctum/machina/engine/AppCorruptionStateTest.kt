package app.sanctum.machina.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD harness for [AppCorruptionState] — the in-memory boolean flag raised by `AppModule`
 * when the Room DB fails to open on cold start (Decision 6 / AC-R6). The class has no
 * persistence and no coroutine machinery; these tests simply pin down the construction
 * contract so `HomeScreen` (Task 10) can trust `false` as the "no corruption" signal.
 */
class AppCorruptionStateTest {

    @Test
    fun initiallyFalse() {
        val state = AppCorruptionState()
        assertFalse(
            "corruptionOccurred must start false so HomeScreen never shows a stale banner",
            state.corruptionOccurred,
        )
    }

    @Test
    fun canBeSetTrue() {
        val state = AppCorruptionState()
        state.corruptionOccurred = true
        assertTrue(
            "setting corruptionOccurred = true must persist within the same instance",
            state.corruptionOccurred,
        )
    }
}
