package app.sanctum.machina.ui.chat

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory attachment referenced by a user message (tech-spec D6).
 *
 * Two shapes:
 *  - [Image] — a `Bitmap` obtained from Photo Picker (downscaled via
 *    `decodeSampledBitmapFromUri`) or CameraX capture.
 *  - [Audio] — raw little-endian PCM 16-bit mono 16 kHz produced by
 *    `AudioRecord`, alongside its duration in milliseconds.
 *
 * Each instance gets a process-unique [id] used as a stable `LazyRow` key
 * in `ThumbnailStrip` — keying by list index would invalidate every
 * trailing thumbnail on removal.
 *
 * [Audio] overrides `equals`/`hashCode` on `(pcm content, durationMs)` —
 * [id] is excluded so round-trip comparisons (tests, DataStore-backed
 * diffing in later phases) stay value-based.
 */
sealed class Attachment {
    abstract val id: Long

    class Image(
        val bitmap: Bitmap,
        override val id: Long = nextId(),
    ) : Attachment()

    class Audio(
        val pcm: ByteArray,
        val durationMs: Long,
        override val id: Long = nextId(),
    ) : Attachment() {
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

    companion object {
        private val idCounter = AtomicLong(0L)
        private fun nextId(): Long = idCounter.incrementAndGet()
    }
}
