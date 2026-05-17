package app.sanctum.machina.rag

import java.util.PriorityQueue
import kotlin.math.sqrt

/**
 * Retriever-side row. Decoded form, i.e. [embedding] is the FloatArray vector
 * (not the on-disk `ByteArray` BLOB).
 *
 * `data.dao.EmbeddingRow` is the raw (BLOB) DAO projection; `RagInjector`
 * decodes that to [RetrieverRow] via [EmbeddingBlob.decode] before handing it
 * to [CosineRetriever]. The two types live in different packages on purpose.
 */
class RetrieverRow(
  val fileId: Long,
  val fileName: String,
  val page: Int,
  val chunkText: String,
  val embedding: FloatArray,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is RetrieverRow) return false
    return fileId == other.fileId &&
      fileName == other.fileName &&
      page == other.page &&
      chunkText == other.chunkText &&
      embedding.contentEquals(other.embedding)
  }

  override fun hashCode(): Int {
    var result = fileId.hashCode()
    result = 31 * result + fileName.hashCode()
    result = 31 * result + page
    result = 31 * result + chunkText.hashCode()
    result = 31 * result + embedding.contentHashCode()
    return result
  }
}

/** A scored row returned by [CosineRetriever.topK]. */
data class Scored(val row: RetrieverRow, val score: Float)

/**
 * In-process cosine-similarity top-K retriever (Decision 10).
 *
 * O(n) scan with a size-k min-heap; never sorts all rows. Pure logic — no IO,
 * no Android, no coroutines.
 *
 * Cosine is `dot(q, r) / (||q|| * ||r||)`, with `0f` (not `NaN`) when either
 * vector has zero norm. That keeps the result usable upstream (sortable, no
 * NaN propagation into UI) at the cost of conflating "zero vector" with
 * "orthogonal" — both are equally useless for retrieval, so the conflation
 * is intentional.
 */
object CosineRetriever {

  fun topK(query: FloatArray, rows: List<RetrieverRow>, k: Int): List<Scored> {
    require(k > 0) { "k must be > 0, was $k" }
    if (rows.isEmpty()) return emptyList()

    val qNorm = norm(query)
    val heap = PriorityQueue<Scored>(k, compareBy { it.score })
    for (row in rows) {
      require(row.embedding.size == query.size) {
        "dimension mismatch: query=${query.size} row=${row.embedding.size}"
      }
      val rNorm = norm(row.embedding)
      val raw = if (qNorm == 0f || rNorm == 0f) {
        0f
      } else {
        dot(query, row.embedding) / (qNorm * rNorm)
      }
      // Coerce NaN / ±Infinity → 0f. `EmbeddingBlob.decode` faithfully restores
      // whatever bits the encoder wrote, including NaN/Inf if a row got
      // corrupted in transit — without this guard, one poisoned row sorts to
      // the top of the heap (NaN > anything under `Float.compare`) and shadows
      // every legitimate hit. Defence-in-depth alongside the dim check above.
      val score = if (raw.isFinite()) raw else 0f
      val candidate = Scored(row, score)
      if (heap.size < k) {
        heap.offer(candidate)
      } else {
        // size == k here, so peek() is non-null; use !! to silence the
        // kotlinc nullable-receiver warning without an extra branch.
        val worst = heap.peek()!!
        if (score > worst.score) {
          heap.poll()
          heap.offer(candidate)
        }
      }
    }
    return heap.sortedByDescending { it.score }
  }

  private fun dot(a: FloatArray, b: FloatArray): Float {
    // Accumulate in Double to keep large-dim sums (e.g. 768) from drifting on
    // adversarial inputs; the final cast back to Float matches model output.
    var acc = 0.0
    for (i in a.indices) acc += a[i].toDouble() * b[i].toDouble()
    return acc.toFloat()
  }

  private fun norm(v: FloatArray): Float {
    var acc = 0.0
    for (x in v) acc += x.toDouble() * x.toDouble()
    return sqrt(acc).toFloat()
  }
}
