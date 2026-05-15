package app.sanctum.machina.rag

import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CosineRetrieverTest {

  private fun row(
    id: Long = 1L,
    fileName: String = "f.pdf",
    page: Int = 1,
    text: String = "t",
    embedding: FloatArray,
  ) = RetrieverRow(id, fileName, page, text, embedding)

  @Test
  fun emptyRows_returnsEmpty() {
    val result = CosineRetriever.topK(floatArrayOf(1f, 0f, 0f), emptyList(), k = 5)
    assertEquals(emptyList<Scored>(), result)
  }

  @Test
  fun kZeroOrNegative_throws() {
    val q = floatArrayOf(1f, 0f, 0f)
    val rows = listOf(row(embedding = floatArrayOf(1f, 0f, 0f)))
    assertThrows(IllegalArgumentException::class.java) { CosineRetriever.topK(q, rows, k = 0) }
    assertThrows(IllegalArgumentException::class.java) { CosineRetriever.topK(q, rows, k = -1) }
  }

  @Test
  fun kGreaterThanRows_returnsAllSorted() {
    val q = floatArrayOf(1f, 0f)
    val r1 = row(id = 1L, embedding = floatArrayOf(1f, 0f))       // cos = 1
    val r2 = row(id = 2L, embedding = floatArrayOf(0f, 1f))       // cos = 0
    val r3 = row(id = 3L, embedding = floatArrayOf(-1f, 0f))      // cos = -1
    val result = CosineRetriever.topK(q, listOf(r2, r3, r1), k = 10)
    assertEquals(3, result.size)
    assertEquals(1L, result[0].row.fileId)
    assertEquals(2L, result[1].row.fileId)
    assertEquals(3L, result[2].row.fileId)
    // Pin actual cosine values too — id-only ordering would still pass an
    // accidental abs() or sign-strip in the scorer.
    assertEquals(1f, result[0].score, 1e-6f)
    assertEquals(0f, result[1].score, 1e-6f)
    assertEquals(-1f, result[2].score, 1e-6f)
  }

  @Test
  fun topK_ordersByScoreDescending() {
    val q = floatArrayOf(1f, 0f)
    // Construct three rows with cosines 0.9, 0.5, 0.1 against q=(1,0).
    fun v(cos: Double): FloatArray {
      val s = sqrt(1.0 - cos * cos)
      return floatArrayOf(cos.toFloat(), s.toFloat())
    }
    val r09 = row(id = 9L, embedding = v(0.9))
    val r05 = row(id = 5L, embedding = v(0.5))
    val r01 = row(id = 1L, embedding = v(0.1))
    val result = CosineRetriever.topK(q, listOf(r01, r05, r09), k = 2)
    assertEquals(2, result.size)
    assertEquals(9L, result[0].row.fileId)
    assertEquals(5L, result[1].row.fileId)
    assertEquals(0.9f, result[0].score, 1e-5f)
    assertEquals(0.5f, result[1].score, 1e-5f)
  }

  @Test
  fun zeroNormQuery_returnsZeroScoresNotNaN() {
    val q = FloatArray(384) // all zeros
    val rows = listOf(
      row(id = 1L, embedding = FloatArray(384) { 1f }),
      row(id = 2L, embedding = FloatArray(384) { (it % 7).toFloat() }),
    )
    val result = CosineRetriever.topK(q, rows, k = 2)
    assertEquals(2, result.size)
    for (s in result) {
      assertFalse("score should not be NaN", s.score.isNaN())
      assertEquals(0f, s.score, 0f)
    }
  }

  @Test
  fun zeroNormRow_returnsZeroScore() {
    val q = floatArrayOf(1f, 2f, 3f)
    val rows = listOf(row(id = 1L, embedding = FloatArray(3)))
    val result = CosineRetriever.topK(q, rows, k = 1)
    assertEquals(1, result.size)
    assertFalse(result[0].score.isNaN())
    assertEquals(0f, result[0].score, 0f)
  }

  @Test
  fun nanOrInfInRowEmbedding_coercedToZero_doesNotPoisonRanking() {
    val q = floatArrayOf(1f, 0f, 0f)
    val good1 = row(id = 1L, embedding = floatArrayOf(1f, 0f, 0f))      // cos = 1
    val good2 = row(id = 2L, embedding = floatArrayOf(0.5f, 0.5f, 0f))  // cos > 0
    val poisonedNaN = row(id = 99L, embedding = floatArrayOf(Float.NaN, 0f, 0f))
    val poisonedInf = row(id = 100L, embedding = floatArrayOf(Float.POSITIVE_INFINITY, 0f, 0f))
    val result = CosineRetriever.topK(q, listOf(poisonedNaN, poisonedInf, good2, good1), k = 4)
    assertEquals(4, result.size)
    // Legitimate rows must rank ahead of poisoned ones; poisoned ones land
    // at the bottom with score 0f (not NaN, which would sort to the top).
    assertEquals(1L, result[0].row.fileId)
    assertEquals(2L, result[1].row.fileId)
    for (s in result) {
      assertFalse("score is NaN for row ${s.row.fileId}", s.score.isNaN())
      assertFalse("score is infinite for row ${s.row.fileId}", s.score.isInfinite())
    }
    // The two poisoned rows both got coerced to 0f.
    val poisonedScores = result.filter { it.row.fileId == 99L || it.row.fileId == 100L }
    assertEquals(2, poisonedScores.size)
    for (s in poisonedScores) assertEquals(0f, s.score, 0f)
  }

  @Test
  fun dimensionMismatch_throws_IllegalArgumentException() {
    val q = floatArrayOf(1f, 2f, 3f)
    val rows = listOf(row(id = 1L, embedding = floatArrayOf(1f, 2f)))
    assertThrows(IllegalArgumentException::class.java) {
      CosineRetriever.topK(q, rows, k = 1)
    }
  }

  @Test
  fun largeNumberOfRows_partialHeapMatchesBruteForce() {
    val rnd = Random(seed = 42)
    val dim = 64
    val q = FloatArray(dim) { rnd.nextFloat() * 2f - 1f }
    val rows = List(10_000) { i ->
      row(id = i.toLong(), embedding = FloatArray(dim) { rnd.nextFloat() * 2f - 1f })
    }
    val k = 5
    val startNs = System.nanoTime()
    val heapResult = CosineRetriever.topK(q, rows, k)
    val heapMs = (System.nanoTime() - startNs) / 1_000_000

    // Brute-force reference: score everything, sort all, take k.
    val qNorm = sqrt(q.sumOf { it.toDouble() * it.toDouble() }).toFloat()
    val bruteResult = rows.map { r ->
      val rNorm = sqrt(r.embedding.sumOf { it.toDouble() * it.toDouble() }).toFloat()
      val dot = q.indices.sumOf { q[it].toDouble() * r.embedding[it].toDouble() }.toFloat()
      val s = if (qNorm == 0f || rNorm == 0f) 0f else dot / (qNorm * rNorm)
      Scored(r, s)
    }.sortedByDescending { it.score }.take(k)

    assertEquals(k, heapResult.size)
    for (i in 0 until k) {
      assertEquals(
        "tier $i row mismatch: heap=${heapResult[i].row.fileId} brute=${bruteResult[i].row.fileId}",
        bruteResult[i].row.fileId,
        heapResult[i].row.fileId,
      )
      assertEquals(bruteResult[i].score, heapResult[i].score, 1e-6f)
    }
    // Soft perf guard — 10k×64 should be well under a second on any dev box.
    assertTrue("heap topK too slow: ${heapMs}ms", heapMs < 2_000)
  }
}
