package app.sanctum.machina.logexport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for [TapCounter]. No Robolectric — the class must be
 * Android-independent (Acceptance Criteria: "TapCounter.kt не содержит ни одного
 * Android-импорта"). Virtual time is fed through a mutable `var t: Long` closure
 * read by `nowNanos = { t }`.
 */
class TapCounterTest {

    private val oneSecond = 1_000_000_000L

    @Test
    fun sevenTaps_withinWindow_triggersOnSeventh() {
        var t = 0L
        val counter = TapCounter(nowNanos = { t })

        val results = mutableListOf<Boolean>()
        for (i in 0 until 7) {
            t = i * oneSecond
            results += counter.tap()
        }

        for (i in 0 until 6) {
            assertFalse("tap ${i + 1} must not trigger, got ${results[i]}", results[i])
        }
        assertTrue("7th tap must trigger", results[6])
    }

    @Test
    fun gapOverTwoSeconds_resetsCounter() {
        var t = 0L
        val counter = TapCounter(nowNanos = { t })

        counter.tap()
        t = 3 * oneSecond
        val second = counter.tap()

        assertFalse("second tap after >2 s gap must reset to 1, not trigger", second)
    }

    @Test
    fun gapExactlyTwoSeconds_inclusive() {
        var t = 0L
        val counter = TapCounter(nowNanos = { t })

        counter.tap()
        t = 2 * oneSecond
        val secondTap = counter.tap()

        assertFalse("two-tap boundary case must not prematurely trigger", secondTap)

        for (i in 3..6) {
            t = i * oneSecond
            assertFalse("tap $i must not trigger", counter.tap())
        }
        t = 7 * oneSecond
        assertTrue("7th tap reached through inclusive 2s gaps must trigger", counter.tap())
    }

    @Test
    fun gapTwoSecondsPlusOneNs_exclusive() {
        var t = 0L
        val counter = TapCounter(nowNanos = { t })

        counter.tap()
        t = 2 * oneSecond + 1
        val second = counter.tap()

        assertFalse("gap 2s+1ns must reset counter (exclusive boundary)", second)

        for (i in 0 until 5) {
            t += oneSecond
            assertFalse(counter.tap())
        }
        t += oneSecond
        assertTrue("7th tap of the new sequence must trigger", counter.tap())
    }

    @Test
    fun fewerThanSeven_noTrigger() {
        var t = 0L
        val counter = TapCounter(nowNanos = { t })

        for (i in 0 until 6) {
            t = i * oneSecond
            assertFalse("tap ${i + 1} / 6 must not trigger", counter.tap())
        }
    }

    @Test
    fun eighthTapDoesNotRetrigger() {
        var t = 0L
        val counter = TapCounter(nowNanos = { t })

        for (i in 0 until 6) {
            t = i * oneSecond
            counter.tap()
        }
        t = 6 * oneSecond
        assertTrue("7th tap must trigger", counter.tap())

        t = 7 * oneSecond
        assertFalse("8th tap must not re-trigger — cycle resets after trigger", counter.tap())
    }
}
