package app.sanctum.machina.ui.chat

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for `AudioRecorderBottomSheet` pure helpers — [formatTimer]
 * (elapsed-ms → `MM:SS`) and [isAudioDenialPermanent] (permission-denied
 * classification).
 *
 * The recording flow itself (`AudioRecord` lifecycle, `DisposableEffect`
 * release, `LifecycleEventEffect(ON_PAUSE)` dismiss, `ModalBottomSheet`
 * permission launcher) is covered by manual smoke on Honor 200 —
 * `AudioRecord` requires a physical microphone and `ModalBottomSheet`
 * interaction requires `compose-ui-test`, which the Phase-2 unit-test
 * strategy explicitly excludes (tech-spec § Testing Strategy; precedent:
 * Task 3 pixel-rotation and Task 8 permission-launcher tests).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AudioRecorderBottomSheetTest {

    @Test
    fun formatTimer_zero_returnsZeroZero() {
        assertEquals("00:00", formatTimer(0L))
    }

    @Test
    fun formatTimer_oneSecond_padsSeconds() {
        assertEquals("00:01", formatTimer(1_000L))
    }

    @Test
    fun formatTimer_fractionalSecondTruncatesDown() {
        // 1999 ms → 1 second (integer truncation), not 2. Avoids the timer
        // ticking to "00:30" a tick before the 30-sec auto-stop fires.
        assertEquals("00:01", formatTimer(1_999L))
    }

    @Test
    fun formatTimer_thirtySeconds_rendersThirty() {
        assertEquals("00:30", formatTimer(30_000L))
    }

    @Test
    fun formatTimer_oneMinute_rendersMinutesField() {
        // `MAX_AUDIO_CLIP_DURATION_SEC` is 30 today, but the formatter is
        // expected to keep working if the cap is relaxed in later phases.
        assertEquals("01:05", formatTimer(65_000L))
    }

    @Test
    fun formatTimer_negativeMillis_clampsToZero() {
        // Guard against `System.currentTimeMillis` going backwards on NTP
        // re-sync mid-recording. Negative durations render "00:00" rather
        // than a spurious negative readout.
        assertEquals("00:00", formatTimer(-42L))
    }

    @Test
    fun isAudioDenialPermanent_nullActivity_returnsTrue() {
        // No hosting Activity resolvable → can't re-prompt → route to the
        // "Open settings" snackbar branch.
        assertTrue(isAudioDenialPermanent(activity = null, rationaleVisible = true))
    }

    @Test
    fun isAudioDenialPermanent_rationaleVisible_returnsFalse() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()

        assertFalse(isAudioDenialPermanent(activity, rationaleVisible = true))
    }

    @Test
    fun isAudioDenialPermanent_rationaleHidden_returnsTrue() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()

        assertTrue(isAudioDenialPermanent(activity, rationaleVisible = false))
    }
}
