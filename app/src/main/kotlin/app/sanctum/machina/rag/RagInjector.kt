package app.sanctum.machina.rag

import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.data.dao.EmbeddingRow
import app.sanctum.machina.data.dao.ProjectEmbeddingDao
import app.sanctum.machina.engine.EmbedderNotReadyException
import app.sanctum.machina.engine.EmbedderRegistry
import app.sanctum.machina.engine.EmbedderState
import java.util.IdentityHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val LOG_RETRIEVE = "rag-retrieve"

/**
 * Pure read-side orchestrator over [EmbedderRegistry] + [ProjectEmbeddingDao] +
 * [CosineRetriever]. Given `projectId` + `query` + `topK`, returns the top-K matching chunks
 * for the project corpus. No persistence, no prefix-building, no chat-state mutation — those
 * concerns live in `ChatViewModel` (Task 11) where the result is consumed.
 *
 * **Fail-loud contract (Task 6 AC line 107).** Three structural error cases each surface as
 * a distinct typed exception so the consumer can pick the user-facing message and decide
 * whether to retain the USER row:
 *
 *  - [EmbedderNotReadyException] (re-used from [EmbedderRegistry]) — embedder is not in
 *    `Ready` state at the moment of the call. ChatViewModel renders «Загрузите модель
 *    эмбеддингов» or a re-warmup spinner depending on the carried state.
 *  - [EmptyCorpusException] — the project has zero ready embeddings. Surfaces as the
 *    «В корпусе нет проиндексированных документов» bubble; USER row retained.
 *  - [EmbedderEncodeException] — `encodeQuery` threw. Wraps the original cause so logs and
 *    UI both see the leaf class.
 *
 * No silent fallback to query-only inference — Decision 8 / US-AC8 require the user to be
 * notified that retrieval did not run, rather than silently degrading.
 *
 * Exposed as an interface so `ChatViewModel` (Task 11) can be unit-tested with a hand-rolled
 * fake — the production binding is [DefaultRagInjector] via `@Binds` in `AppModule`.
 */
interface RagInjector {
  suspend fun retrieve(projectId: Long, query: String, topK: Int): List<RetrievedChunk>
}

@Singleton
class DefaultRagInjector @Inject constructor(
  private val embedderRegistry: EmbedderRegistry,
  private val embeddingDao: ProjectEmbeddingDao,
  private val errorLog: ErrorLog,
) : RagInjector {

  override suspend fun retrieve(
    projectId: Long,
    query: String,
    topK: Int,
  ): List<RetrievedChunk> {
    // 1) State gate — pre-check so we can carry the *current* state in the exception. The
    //    registry's own encodeQuery() also throws EmbedderNotReadyException, but only after
    //    we have already paid the DAO read for nothing.
    val state = embedderRegistry.state.value
    if (state !is EmbedderState.Ready) {
      throw EmbedderNotReadyException(state)
    }

    // 2) Corpus check before encode — empty corpus is the most common structural case and
    //    we never want to spend an embedder call on a fail-loud branch that would always
    //    throw downstream anyway. The pre-encode log is observable in field bug reports
    //    where users complain "RAG doesn't work" but the corpus is empty.
    val rows: List<EmbeddingRow> = embeddingDao.allByProjectAndReadyFiles(projectId)
    if (rows.isEmpty()) {
      errorLog.i(LOG_RETRIEVE, "empty corpus, projectId=$projectId")
      throw EmptyCorpusException(projectId)
    }

    // 3) Encode the query verbatim — query normalization is ChatViewModel's responsibility
    //    (Task 11 hook). The injector treats the input string as opaque so the test for
    //    normalization stays at the call-site layer where it belongs.
    val queryVec: FloatArray = try {
      embedderRegistry.encodeQuery(query)
    } catch (notReady: EmbedderNotReadyException) {
      // Race with idle-teardown — state was Ready at line above, then the loop took the
      // mutex and flipped to Idle before encodeQuery acquired it. Propagate verbatim so
      // ChatViewModel can re-warm or surface the same snackbar the pre-check would have.
      throw notReady
    } catch (t: Throwable) {
      errorLog.e(LOG_RETRIEVE, "encodeQuery failed, projectId=$projectId", t)
      throw EmbedderEncodeException(cause = t)
    }

    // 4) Decode each blob exactly once and hand the decoded rows to CosineRetriever. The
    //    DAO returns the raw `EmbeddingRow` (BLOB form); RetrieverRow is the decoded
    //    counterpart that lives in this package on purpose (cf. CosineRetriever.kt header).
    //    RetrieverRow.page is non-nullable per Task 5, so we keep an identity-keyed
    //    side-table from the decoded RetrieverRow back to the original EmbeddingRow — that
    //    lets us re-thread the original nullable `page` into the result without re-scanning
    //    the corpus on every hit. IdentityHashMap because RetrieverRow.equals is content-
    //    based and two structurally identical chunks would otherwise collide.
    val rrToOriginal = IdentityHashMap<RetrieverRow, EmbeddingRow>(rows.size)
    val decoded = rows.map { row ->
      val rr = RetrieverRow(
        fileId = row.fileId,
        fileName = row.fileName,
        page = row.page ?: 0,
        chunkText = row.chunkText,
        embedding = EmbeddingBlob.decode(row.embeddingBlob),
      )
      rrToOriginal[rr] = row
      rr
    }

    val scored = CosineRetriever.topK(queryVec, decoded, topK)
    return scored.map { hit ->
      val original = rrToOriginal[hit.row]
      RetrievedChunk(
        fileId = hit.row.fileId,
        fileName = hit.row.fileName,
        page = original?.page,
        chunkText = hit.row.chunkText,
        cosine = hit.score,
      )
    }
  }
}

/**
 * Domain model from tech-spec § Domain models (line 345). Carries the cosine score so
 * downstream consumers can render rank or filter on threshold without re-running the
 * retriever.
 */
data class RetrievedChunk(
  val fileId: Long,
  val fileName: String,
  val page: Int?,
  val chunkText: String,
  val cosine: Float,
)

/**
 * Project has zero ready embeddings — typically the user hasn't ingested any documents yet
 * or every ingest is `failed`. Caller surfaces the «load a document» CTA.
 */
class EmptyCorpusException(val projectId: Long) :
  RuntimeException("RAG corpus is empty for projectId=$projectId")

/**
 * Wraps an arbitrary [encodeQuery] failure (out-of-memory, native interpreter abort, etc.)
 * with a typed envelope. Callers should not pattern-match on the underlying cause; the
 * envelope alone is the structural signal.
 */
class EmbedderEncodeException(cause: Throwable) :
  RuntimeException("embedder encodeQuery failed", cause)
