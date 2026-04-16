package app.sanctum.machina.core.common

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric-backed tests for `MediaUtils` functions that touch Android APIs
 * (`BitmapFactory`, `Matrix`, `ExifInterface`). Pure-JVM functions
 * (`calculateInSampleSize`, `calculatePeakAmplitude`) live in
 * [MediaUtilsPureTest] — D20 seam realised as separate files.
 *
 * Rotation litmus strategy (Robolectric limitation):
 * Robolectric's `Bitmap.createBitmap(src, x, y, w, h, matrix, filter)` copies
 * dimensions correctly but does not rasterise the transformed pixels into the
 * shadow-backed bitmap — `getPixel` on the output returns 0 regardless of the
 * matrix. So pixel-motion assertions (test-reviewer T-1 ideal) cannot run here.
 * What we do instead:
 *   - For orientations that swap dimensions (90/270/transpose/transverse) —
 *     dimension swap IS a strong litmus: an identity-matrix substitution would
 *     preserve dimensions, so the test fails on the regression. Also assert
 *     `assertNotSame(src, out)` (createBitmap's full-region identity-matrix
 *     short-circuit would return `src` itself).
 *   - For orientations that preserve dimensions (180/flipH/flipV) — we can only
 *     assert `assertNotSame(src, out)`. Pixel-level correctness of these three
 *     is verified by the device smoke test (tech-spec AC-13, AC-18) — not here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaUtilsTest {

  private lateinit var context: Context
  private val tempFiles = mutableListOf<File>()

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
  }

  @After
  fun tearDown() {
    tempFiles.forEach { it.delete() }
    tempFiles.clear()
  }

  /**
   * JPEG (not PNG) — mirrors the wire format the Android Photo Picker hands
   * back for most gallery photos (AC-10). Robolectric 4.x backs
   * `Bitmap.compress` with real Skia, so the byte stream is a genuine JPEG
   * decodable by `BitmapFactory`.
   */
  private fun writeJpegTempFile(width: Int, height: Int): File {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val file = File.createTempFile("mediautils-test-", ".jpg", context.cacheDir)
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    tempFiles.add(file)
    return file
  }

  private fun srcBitmap(): Bitmap =
    Bitmap.createBitmap(4, 8, Bitmap.Config.ARGB_8888)

  @Test
  fun decodeSampledBitmapFromUri_2048x2048_downscaledTo1024() {
    val file = writeJpegTempFile(2048, 2048)
    val uri = Uri.fromFile(file)

    val result = decodeSampledBitmapFromUri(context, uri, reqWidth = 1024, reqHeight = 1024)

    assertNotNull("decoded bitmap must not be null", result)
    assertTrue("width ≤ 1024, got ${result!!.width}", result.width <= 1024)
    assertTrue("height ≤ 1024, got ${result.height}", result.height <= 1024)
  }

  @Test
  fun decodeSampledBitmapFromUri_missingFile_returnsNull() {
    val uri = Uri.fromFile(File(context.cacheDir, "does-not-exist.jpg"))
    val result = decodeSampledBitmapFromUri(context, uri, 512, 512)
    assertEquals("missing file → null (no exception propagated)", null, result)
  }

  @Test
  fun rotateBitmap_rotate90_swapsDimensions() {
    val src = srcBitmap()
    val out = rotateBitmap(src, ExifInterface.ORIENTATION_ROTATE_90)
    assertEquals("rotated width = original height", 8, out.width)
    assertEquals("rotated height = original width", 4, out.height)
    assertNotSame("rotation must return a new bitmap", src, out)
  }

  @Test
  fun rotateBitmap_rotate180_producesNewBitmap() {
    val src = srcBitmap()
    val out = rotateBitmap(src, ExifInterface.ORIENTATION_ROTATE_180)
    assertEquals(4, out.width)
    assertEquals(8, out.height)
    // Litmus: identity-matrix regression would short-circuit Bitmap.createBitmap
    // (full-region, identity → returns source) and this assertion would catch it.
    assertNotSame("rotation must return a new bitmap, not the source", src, out)
  }

  @Test
  fun rotateBitmap_rotate270_swapsDimensions() {
    val src = srcBitmap()
    val out = rotateBitmap(src, ExifInterface.ORIENTATION_ROTATE_270)
    assertEquals(8, out.width)
    assertEquals(4, out.height)
    assertNotSame(src, out)
  }

  @Test
  fun rotateBitmap_flipHorizontal_producesNewBitmap() {
    val src = srcBitmap()
    val out = rotateBitmap(src, ExifInterface.ORIENTATION_FLIP_HORIZONTAL)
    assertEquals(4, out.width)
    assertEquals(8, out.height)
    assertNotSame("flip must return a new bitmap, not the source", src, out)
  }

  @Test
  fun rotateBitmap_flipVertical_producesNewBitmap() {
    val src = srcBitmap()
    val out = rotateBitmap(src, ExifInterface.ORIENTATION_FLIP_VERTICAL)
    assertEquals(4, out.width)
    assertEquals(8, out.height)
    assertNotSame("flip must return a new bitmap, not the source", src, out)
  }

  @Test
  fun rotateBitmap_transpose_swapsDimensions() {
    val src = srcBitmap()
    val out = rotateBitmap(src, ExifInterface.ORIENTATION_TRANSPOSE)
    assertEquals("TRANSPOSE swaps width and height", 8, out.width)
    assertEquals(4, out.height)
    assertNotSame(src, out)
  }

  @Test
  fun rotateBitmap_transverse_swapsDimensions() {
    val src = srcBitmap()
    val out = rotateBitmap(src, ExifInterface.ORIENTATION_TRANSVERSE)
    assertEquals("TRANSVERSE swaps width and height", 8, out.width)
    assertEquals(4, out.height)
    assertNotSame(src, out)
  }

  @Test
  fun rotateBitmap_normal_returnsSameInstance() {
    val src = srcBitmap()
    val result = rotateBitmap(src, ExifInterface.ORIENTATION_NORMAL)
    assertSame("ORIENTATION_NORMAL is a no-op", src, result)
  }

  @Test
  fun rotateBitmap_undefined_returnsSameInstance() {
    val src = srcBitmap()
    val result = rotateBitmap(src, ExifInterface.ORIENTATION_UNDEFINED)
    assertSame("unknown orientation is a no-op", src, result)
  }
}
