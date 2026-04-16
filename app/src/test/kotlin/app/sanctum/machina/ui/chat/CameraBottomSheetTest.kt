package app.sanctum.machina.ui.chat

import android.app.Activity
import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for `CameraBottomSheet` helpers — [rotateBitmapByDegrees]
 * (applies `ImageProxy.imageInfo.rotationDegrees` to the captured bitmap)
 * and [isCameraDenialPermanent] (permission-denied classification).
 *
 * The rest of the camera flow (permission launcher, CameraX provider bind,
 * `ModalBottomSheet` dismiss) is covered by manual smoke on Honor 200 —
 * CameraX requires a physical camera and `ModalBottomSheet` interactions
 * require `compose-ui-test`, which the Phase-2 unit test strategy explicitly
 * excludes (see tech-spec § Testing Strategy and precedent from Task 3
 * pixel-rotation tests). TODO: revisit when/if Phase 3 adds androidTest.
 *
 * Dimension-swap assertions are robust under Robolectric; pixel-level
 * rotation correctness is not — Robolectric's shadow doesn't rasterise
 * transformed pixels (same finding as Task 3's `rotateBitmap` tests). A
 * hypothetical identity-matrix regression in [rotateBitmapByDegrees] would
 * still be caught by `assertNotSame`, which is why the 180° test survives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CameraBottomSheetTest {

    @Test
    fun rotateBitmap_zeroDegrees_returnsSameBitmap() {
        val src = Bitmap.createBitmap(10, 20, Bitmap.Config.ARGB_8888)

        val out = rotateBitmapByDegrees(src, 0)

        assertSame("0° must short-circuit without allocating a new bitmap", src, out)
    }

    @Test
    fun rotateBitmap_720Degrees_normalizesToZeroAndShortCircuits() {
        val src = Bitmap.createBitmap(10, 20, Bitmap.Config.ARGB_8888)

        // 720° normalizes to 0. Without the `((degrees % 360) + 360) % 360`
        // normalization, `Matrix.postRotate(720f)` would allocate a new
        // bitmap — `assertSame` proves the short-circuit fires.
        val out = rotateBitmapByDegrees(src, 720)

        assertSame("720° normalizes to 0 and must short-circuit", src, out)
    }

    @Test
    fun rotateBitmap_negative360Degrees_normalizesToZeroAndShortCircuits() {
        val src = Bitmap.createBitmap(10, 20, Bitmap.Config.ARGB_8888)

        val out = rotateBitmapByDegrees(src, -360)

        assertSame("-360° normalizes to 0 and must short-circuit", src, out)
    }

    @Test
    fun rotateBitmap_90Degrees_swapsDimensions() {
        val src = Bitmap.createBitmap(10, 20, Bitmap.Config.ARGB_8888)

        val out = rotateBitmapByDegrees(src, 90)

        assertNotSame(src, out)
        assertEquals(20, out.width)
        assertEquals(10, out.height)
    }

    @Test
    fun rotateBitmap_180Degrees_preservesDimensions() {
        val src = Bitmap.createBitmap(10, 20, Bitmap.Config.ARGB_8888)

        val out = rotateBitmapByDegrees(src, 180)

        // `assertNotSame` is the meaningful guard — an identity-matrix bug
        // would return `src` itself. Dimension equality is cosmetic under
        // Robolectric (pixel rasterisation not implemented in shadow).
        assertNotSame(src, out)
        assertEquals(10, out.width)
        assertEquals(20, out.height)
    }

    @Test
    fun rotateBitmap_270Degrees_swapsDimensions() {
        val src = Bitmap.createBitmap(10, 20, Bitmap.Config.ARGB_8888)

        val out = rotateBitmapByDegrees(src, 270)

        assertNotSame(src, out)
        assertEquals(20, out.width)
        assertEquals(10, out.height)
    }

    @Test
    fun isCameraDenialPermanent_nullActivity_returnsTrue() {
        // No resolvable Activity → we can't re-prompt anyway, so classify
        // as permanent so the caller routes to the "Open settings" action.
        assertTrue(isCameraDenialPermanent(activity = null, rationaleVisible = true))
    }

    @Test
    fun isCameraDenialPermanent_rationaleVisible_returnsFalse() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()

        assertFalse(isCameraDenialPermanent(activity, rationaleVisible = true))
    }

    @Test
    fun isCameraDenialPermanent_rationaleHidden_returnsTrue() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()

        assertTrue(isCameraDenialPermanent(activity, rationaleVisible = false))
    }
}
