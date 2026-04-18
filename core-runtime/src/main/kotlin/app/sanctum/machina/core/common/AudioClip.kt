package app.sanctum.machina.core.common

/**
 * Raw PCM audio clip for multimodal inference.
 *
 * Matches Gallery `common/Types.kt` — plain class, not `data class`, to sidestep
 * `equals`/`hashCode` semantics on [audioData] (ByteArray identity vs content
 * would mislead callers). Ports D5 (user-spec) — AudioRecord writes raw PCM
 * directly, litertlm consumes it without conversion.
 */
class AudioClip(val audioData: ByteArray, val sampleRate: Int)
