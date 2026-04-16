package app.sanctum.machina.core.common

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

  private fun writeJpegTempFile(width: Int, height: Int): File {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val file = File.createTempFile("mediautils-test-", ".jpg", context.cacheDir)
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    tempFiles.add(file)
    return file
  }

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
    // FileInputStream throws; openStream returns InputStream that fails on use — returns null.
    // Guard via null-safe branch in decodeSampledBitmapFromUri.
    assertEquals(null, result)
  }

  @Test
  fun rotateBitmap_rotate90_swapsDimensions() {
    val src = Bitmap.createBitmap(4, 8, Bitmap.Config.ARGB_8888)
    val rotated = rotateBitmap(src, ExifInterface.ORIENTATION_ROTATE_90)
    assertEquals("rotated width = original height", 8, rotated.width)
    assertEquals("rotated height = original width", 4, rotated.height)
    assertNotSame("rotation must return a new bitmap", src, rotated)
  }

  @Test
  fun rotateBitmap_rotate180_preservesDimensions() {
    val src = Bitmap.createBitmap(4, 8, Bitmap.Config.ARGB_8888)
    val rotated = rotateBitmap(src, ExifInterface.ORIENTATION_ROTATE_180)
    assertEquals(4, rotated.width)
    assertEquals(8, rotated.height)
  }

  @Test
  fun rotateBitmap_rotate270_swapsDimensions() {
    val src = Bitmap.createBitmap(4, 8, Bitmap.Config.ARGB_8888)
    val rotated = rotateBitmap(src, ExifInterface.ORIENTATION_ROTATE_270)
    assertEquals(8, rotated.width)
    assertEquals(4, rotated.height)
  }

  @Test
  fun rotateBitmap_flipHorizontal_preservesDimensions() {
    val src = Bitmap.createBitmap(4, 8, Bitmap.Config.ARGB_8888)
    val rotated = rotateBitmap(src, ExifInterface.ORIENTATION_FLIP_HORIZONTAL)
    assertEquals(4, rotated.width)
    assertEquals(8, rotated.height)
  }

  @Test
  fun rotateBitmap_normal_returnsSameInstance() {
    val src = Bitmap.createBitmap(4, 8, Bitmap.Config.ARGB_8888)
    val result = rotateBitmap(src, ExifInterface.ORIENTATION_NORMAL)
    assertSame("ORIENTATION_NORMAL is a no-op", src, result)
  }

  @Test
  fun rotateBitmap_undefined_returnsSameInstance() {
    val src = Bitmap.createBitmap(4, 8, Bitmap.Config.ARGB_8888)
    val result = rotateBitmap(src, ExifInterface.ORIENTATION_UNDEFINED)
    assertSame("unknown orientation is a no-op", src, result)
  }

  @Test
  fun calculateInSampleSize_exactFit_2x_4x_8x() {
    assertEquals(2, calculateInSampleSize(rawWidth = 2048, rawHeight = 2048, reqWidth = 1024, reqHeight = 1024))
    assertEquals(4, calculateInSampleSize(rawWidth = 2048, rawHeight = 2048, reqWidth = 512, reqHeight = 512))
    assertEquals(8, calculateInSampleSize(rawWidth = 2048, rawHeight = 2048, reqWidth = 256, reqHeight = 256))
  }

  @Test
  fun calculateInSampleSize_smallerThanRequested_returnsOne() {
    assertEquals(1, calculateInSampleSize(rawWidth = 512, rawHeight = 512, reqWidth = 1024, reqHeight = 1024))
  }

  @Test
  fun calculateInSampleSize_asymmetric_takesLargerRatio() {
    // 2048 wide × 256 tall; request 1024 × 1024 → width ratio = 2, height ratio = 0 → max = 2
    assertEquals(2, calculateInSampleSize(rawWidth = 2048, rawHeight = 256, reqWidth = 1024, reqHeight = 1024))
  }

  @Test
  fun calculatePeakAmplitude_emptyBuffer_returnsZero() {
    assertEquals(0f, calculatePeakAmplitude(ByteArray(0)), 0f)
  }

  @Test
  fun calculatePeakAmplitude_bytesReadZero_returnsZero() {
    assertEquals(0f, calculatePeakAmplitude(ByteArray(4), bytesRead = 0), 0f)
  }

  @Test
  fun calculatePeakAmplitude_mixedValues_returnsMax() {
    // LE 16-bit shorts: 1000, -2000, 500 → max abs = 2000
    val buffer = ByteArray(6)
    ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().apply {
      put(1000.toShort())
      put((-2000).toShort())
      put(500.toShort())
    }
    assertEquals(2000f, calculatePeakAmplitude(buffer), 0f)
  }

  @Test
  fun calculatePeakAmplitude_boundary_shortMaxValue() {
    val buffer = ByteArray(2)
    ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(Short.MAX_VALUE)
    assertEquals(Short.MAX_VALUE.toFloat(), calculatePeakAmplitude(buffer), 0f)
  }

  @Test
  fun calculatePeakAmplitude_oddSize_dropsTrailingByte() {
    // 4 bytes = 2 shorts (100, 200), plus one stray byte → trailing byte ignored
    val buffer = ByteArray(5)
    ByteBuffer.wrap(buffer, 0, 4).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().apply {
      put(100.toShort())
      put(200.toShort())
    }
    buffer[4] = 0x7F
    assertEquals(200f, calculatePeakAmplitude(buffer), 0f)
  }

  @Test
  fun calculatePeakAmplitude_respectsBytesRead() {
    // Full buffer has a louder sample after bytesRead — must be ignored.
    val buffer = ByteArray(6)
    ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().apply {
      put(100.toShort())
      put(200.toShort())
      put(Short.MAX_VALUE) // past bytesRead=4
    }
    assertEquals(200f, calculatePeakAmplitude(buffer, bytesRead = 4), 0f)
  }
}
