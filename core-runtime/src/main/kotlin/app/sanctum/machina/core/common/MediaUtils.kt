package app.sanctum.machina.core.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
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
 *
 * Two-pass decode: first pass with `inJustDecodeBounds = true` populates
 * `options.outWidth/outHeight` and returns `null` **by API contract** — we
 * must not treat that null as a failure signal. The stream-open null check
 * and the bounds-validity check are kept separate for that reason.
 */
fun decodeSampledBitmapFromUri(
  context: Context,
  uri: Uri,
  reqWidth: Int,
  reqHeight: Int,
): Bitmap? {
  val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  val boundsStream = openStream(context, uri) ?: return null
  boundsStream.use { BitmapFactory.decodeStream(it, null, options) }

  if (options.outWidth <= 0 || options.outHeight <= 0) return null

  options.inSampleSize = calculateInSampleSize(
    rawWidth = options.outWidth,
    rawHeight = options.outHeight,
    reqWidth = reqWidth,
    reqHeight = reqHeight,
  )
  options.inJustDecodeBounds = false

  val decodeStream = openStream(context, uri) ?: return null
  return decodeStream.use { BitmapFactory.decodeStream(it, null, options) }
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
 * Wraps raw little-endian 16-bit PCM mono audio in a canonical 44-byte
 * RIFF/WAVE header. Ports Gallery `ChatMessageAudioClip.genByteArrayForWav`
 * — confirmed by smoke on Honor 200 that litertlm 0.10.0's
 * `Content.AudioBytes` rejects headerless PCM and invokes the error
 * listener (user-spec claim "litertlm ест PCM" was factually wrong).
 *
 * Assumes 1 channel, 16 bits/sample. Not a stream — builds the full byte
 * array in memory. Safe at Phase-2 sizes: 30 s × 16 kHz × 2 B = ~960 KB.
 */
fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
  val channels = 1
  val bitsPerSample = 16
  val byteRate = sampleRate * channels * bitsPerSample / 8
  val blockAlign = channels * bitsPerSample / 8
  val dataSize = pcm.size
  val riffChunkSize = dataSize + 36 // total file size minus the leading 8 "RIFF<size>" bytes

  val header = ByteArray(44)
  // "RIFF" + chunk size + "WAVE"
  header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
  header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
  writeLeInt(header, 4, riffChunkSize)
  header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
  header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
  // "fmt " sub-chunk
  header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
  header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
  writeLeInt(header, 16, 16)                    // PCM fmt chunk size
  writeLeShort(header, 20, 1)                   // PCM format tag
  writeLeShort(header, 22, channels.toShort())
  writeLeInt(header, 24, sampleRate)
  writeLeInt(header, 28, byteRate)
  writeLeShort(header, 32, blockAlign.toShort())
  writeLeShort(header, 34, bitsPerSample.toShort())
  // "data" sub-chunk
  header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
  header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
  writeLeInt(header, 40, dataSize)

  return header + pcm
}

/**
 * Decoded audio payload: raw little-endian 16-bit PCM mono samples plus the
 * duration derived from the WAV header's data-chunk size.
 */
data class DecodedWav(val pcm: ByteArray, val durationMs: Long) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is DecodedWav) return false
    return durationMs == other.durationMs && pcm.contentEquals(other.pcm)
  }

  override fun hashCode(): Int = 31 * pcm.contentHashCode() + durationMs.hashCode()
}

/**
 * Inverse of [pcmToWav]: strip the 44-byte RIFF/WAVE header and return the
 * little-endian 16-bit PCM mono payload together with its duration in
 * milliseconds. Validates the RIFF/WAVE magic and "fmt "/"data" sub-chunk
 * sizes so a truncated or non-canonical file is rejected before the native
 * decoder sees it.
 *
 * Throws [IOException] for: a file shorter than the 44-byte canonical header,
 * missing RIFF/WAVE magic, or a declared `data` chunk larger than the file
 * body. `bytesPerSample` is derived from the header so audio not written by
 * [pcmToWav] (e.g. 8-bit payloads) still reports a truthful [DecodedWav.durationMs].
 */
@Throws(IOException::class)
fun wavToPcm(file: File): DecodedWav {
  val bytes = file.readBytes()
  return wavBytesToPcm(bytes, file.absolutePath)
}

/**
 * Byte-array overload of [wavToPcm] — kept internal to this file because the
 * File-overload is the only production caller; tests parse in-memory WAV
 * buffers through the public [wavToPcm] by writing to a temp file.
 */
@Throws(IOException::class)
internal fun wavBytesToPcm(bytes: ByteArray, source: String = "<bytes>"): DecodedWav {
  if (bytes.size < 44) {
    throw IOException("WAV too short (${bytes.size} bytes): $source")
  }
  if (bytes[0] != 'R'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
    bytes[2] != 'F'.code.toByte() || bytes[3] != 'F'.code.toByte()
  ) {
    throw IOException("missing RIFF magic: $source")
  }
  if (bytes[8] != 'W'.code.toByte() || bytes[9] != 'A'.code.toByte() ||
    bytes[10] != 'V'.code.toByte() || bytes[11] != 'E'.code.toByte()
  ) {
    throw IOException("missing WAVE magic: $source")
  }
  val sampleRate = readLeInt(bytes, 24)
  val bitsPerSample = readLeShort(bytes, 34).toInt() and 0xffff
  val channels = readLeShort(bytes, 22).toInt() and 0xffff
  val dataSize = readLeInt(bytes, 40)
  if (dataSize < 0 || dataSize > bytes.size - 44) {
    throw IOException("data chunk size $dataSize exceeds file body: $source")
  }
  if (sampleRate <= 0 || bitsPerSample <= 0 || channels <= 0) {
    throw IOException(
      "invalid WAV params: sampleRate=$sampleRate bits=$bitsPerSample ch=$channels: $source",
    )
  }
  val pcm = bytes.copyOfRange(44, 44 + dataSize)
  val bytesPerSample = bitsPerSample / 8
  val denom = sampleRate.toLong() * bytesPerSample * channels
  val durationMs = if (denom > 0) pcm.size.toLong() * 1000L / denom else 0L
  return DecodedWav(pcm = pcm, durationMs = durationMs)
}

private fun readLeInt(buf: ByteArray, offset: Int): Int =
  (buf[offset].toInt() and 0xff) or
    ((buf[offset + 1].toInt() and 0xff) shl 8) or
    ((buf[offset + 2].toInt() and 0xff) shl 16) or
    ((buf[offset + 3].toInt() and 0xff) shl 24)

private fun readLeShort(buf: ByteArray, offset: Int): Short =
  (((buf[offset].toInt() and 0xff)) or
    ((buf[offset + 1].toInt() and 0xff) shl 8)).toShort()

private fun writeLeInt(buf: ByteArray, offset: Int, value: Int) {
  buf[offset] = (value and 0xff).toByte()
  buf[offset + 1] = ((value shr 8) and 0xff).toByte()
  buf[offset + 2] = ((value shr 16) and 0xff).toByte()
  buf[offset + 3] = ((value shr 24) and 0xff).toByte()
}

private fun writeLeShort(buf: ByteArray, offset: Int, value: Short) {
  val v = value.toInt()
  buf[offset] = (v and 0xff).toByte()
  buf[offset + 1] = ((v shr 8) and 0xff).toByte()
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
