package app.sanctum.machina.core.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decodes a sampled bitmap from a content or file URI, downscaling to fit within
 * [reqWidth] × [reqHeight]. Returns `null` if the stream cannot be opened or
 * the image cannot be decoded. Ports Gallery `common/Utils.kt`.
 */
fun decodeSampledBitmapFromUri(
  context: Context,
  uri: Uri,
  reqWidth: Int,
  reqHeight: Int,
): Bitmap? {
  val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  openStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    ?: return null

  if (options.outWidth <= 0 || options.outHeight <= 0) return null

  options.inSampleSize = calculateInSampleSize(
    rawWidth = options.outWidth,
    rawHeight = options.outHeight,
    reqWidth = reqWidth,
    reqHeight = reqHeight,
  )
  options.inJustDecodeBounds = false

  return openStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}

/**
 * Applies an EXIF orientation transform to [bitmap]. Returns the original bitmap
 * for [ExifInterface.ORIENTATION_NORMAL] and unknown values; a new rotated or
 * flipped bitmap otherwise. Ports Gallery `common/Utils.kt`.
 */
fun rotateBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
  val matrix = Matrix()
  when (orientation) {
    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
    ExifInterface.ORIENTATION_TRANSPOSE -> {
      matrix.postRotate(90f)
      matrix.preScale(-1f, 1f)
    }
    ExifInterface.ORIENTATION_TRANSVERSE -> {
      matrix.postRotate(270f)
      matrix.preScale(-1f, 1f)
    }
    else -> return bitmap
  }
  return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/**
 * Pure function — no Android dependency, JVM-testable without Robolectric.
 * Computes `BitmapFactory.Options.inSampleSize` from raw image dimensions and
 * requested dimensions. Redesigned from Gallery's private overload that took
 * `BitmapFactory.Options` directly (see tech-spec D20 seam rationale).
 */
fun calculateInSampleSize(rawWidth: Int, rawHeight: Int, reqWidth: Int, reqHeight: Int): Int {
  if (rawHeight <= reqHeight && rawWidth <= reqWidth) return 1
  val heightRatio = (rawHeight.toFloat() / reqHeight.toFloat()).roundToInt()
  val widthRatio = (rawWidth.toFloat() / reqWidth.toFloat()).roundToInt()
  return max(heightRatio, widthRatio)
}

/**
 * Maximum absolute amplitude of little-endian 16-bit PCM samples in [buffer].
 * Only the first [bytesRead] bytes are inspected — matches AudioRecord's
 * pre-allocated-buffer contract. An odd [bytesRead] truncates the unpaired
 * trailing byte (consistent with Gallery). Empty buffer or `bytesRead <= 0`
 * returns `0f`.
 *
 * Diverges from Gallery: return type is `Float` (not `Int`) and [bytesRead]
 * defaults to `buffer.size` (Gallery required both positional args). Documented
 * in `work/phase-2-ui/decisions.md` Task 3.
 */
fun calculatePeakAmplitude(buffer: ByteArray, bytesRead: Int = buffer.size): Float {
  val validLen = bytesRead.coerceIn(0, buffer.size)
  if (validLen <= 1) return 0f
  val shortBuffer = ByteBuffer.wrap(buffer, 0, validLen)
    .order(ByteOrder.LITTLE_ENDIAN)
    .asShortBuffer()
  var maxAmplitude = 0
  while (shortBuffer.hasRemaining()) {
    val sample = abs(shortBuffer.get().toInt())
    if (sample > maxAmplitude) maxAmplitude = sample
  }
  return maxAmplitude.toFloat()
}

/**
 * Opens a read stream for [uri] or returns null on any I/O or permission failure.
 * Callers treat null as "image unavailable" — see `decodeSampledBitmapFromUri`
 * edge case in task 3 (URI pointing to a missing file).
 */
private fun openStream(context: Context, uri: Uri): InputStream? =
  try {
    if (uri.scheme == null || uri.scheme == "file") {
      uri.path?.let { FileInputStream(it) }
    } else {
      context.contentResolver.openInputStream(uri)
    }
  } catch (_: IOException) {
    null
  } catch (_: SecurityException) {
    null
  }
