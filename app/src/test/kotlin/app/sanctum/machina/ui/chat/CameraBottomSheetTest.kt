package app.sanctum.machina.ui.chat

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [rotateBitmapByDegrees] — the rotation helper used by
 * `CameraBottomSheet` to apply `ImageProxy.imageInfo.rotationDegrees` to
 * the captured Bitmap.
 *
 * The rest of the camera flow (permission launcher, CameraX provider bind,
 * `ModalBottomSheet` dismiss) is covered by manual smoke on Honor 200 —
 * CameraX requires a physical camera and `ModalBottomSheet` interactions
 * require `compose-ui-test`, which the Phase-2 unit test strategy explicitly
 * excludes (see tech-spec § Testing Strategy and precedent from Task 3
 * pixel-rotation tests).
 *
 * Dimension-swap assertions are robust under Robolectric; pixel-level
 * rotation correctness is not (Robolectric's shadow doesn't rasterise
 * transformed pixels — same finding as Task 3's `rotateBitmap` tests).
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
    fun rotateBitmap_360Degrees_normalizesToZero() {
        val src = Bitmap.createBitmap(10, 20, Bitmap.Config.ARGB_8888)

        val out = rotateBitmapByDegrees(src, 360)

        assertSame("360° normalizes to 0 and must short-circuit", src, out)
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
    fun rotateBitmap_negativeDegrees_normalizeAndRotate() {
        val src = Bitmap.createBitmap(10, 20, Bitmap.Config.ARGB_8888)

        // -90° normalizes to 270°, which swaps dimensions.
        val out = rotateBitmapByDegrees(src, -90)

        assertNotSame(src, out)
        assertEquals(20, out.width)
        assertEquals(10, out.height)
    }
}
