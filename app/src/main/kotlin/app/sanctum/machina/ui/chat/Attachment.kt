package app.sanctum.machina.ui.chat

import android.graphics.Bitmap

/**
 * In-memory attachment referenced by a user message (tech-spec D6).
 *
 * Two shapes:
 *  - [Image] — a `Bitmap` obtained from Photo Picker (downscaled via
 *    `decodeSampledBitmapFromUri`) or CameraX capture.
 *  - [Audio] — raw little-endian PCM 16-bit mono 16 kHz produced by
 *    `AudioRecord`, alongside its duration in milliseconds.
 *
 * [Audio] overrides `equals`/`hashCode` on [pcm] content — the default
 * `ByteArray` reference semantics break recomposition keys and diff
 * comparisons in test/state code.
 */
sealed class Attachment {

    class Image(val bitmap: Bitmap) : Attachment()

    class Audio(val pcm: ByteArray, val durationMs: Long) : Attachment() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Audio) return false
            return durationMs == other.durationMs && pcm.contentEquals(other.pcm)
        }

        override fun hashCode(): Int {
            var result = pcm.contentHashCode()
            result = 31 * result + durationMs.hashCode()
            return result
        }
    }
}
