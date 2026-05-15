package app.sanctum.machina.data

/**
 * Citation snapshot stored in `messages.citations` as a JSON array via Gson reflection
 * (Decision 7). Survives deletion of the source PDF — see Decision 8 stale-mark mechanism:
 * `DefaultProjectRepository.deleteFile` flips [stale] to `true` on entries that point at the
 * deleted file id, leaving the rest of the snapshot intact.
 *
 * Field order and names are part of the on-disk JSON contract — see
 * `CitationsRoundtripTest` for the literal expected JSON shape. Reordering or renaming
 * silently invalidates older message rows on read-back.
 */
data class Citation(
  val fileId: Long,
  val fileName: String,
  val page: Int?,
  val chunkText: String,
  val stale: Boolean = false,
)
