package app.sanctum.machina.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.sanctum.machina.core.data.Model
import app.sanctum.machina.core.data.ModelDownloadStatus
import app.sanctum.machina.core.embedder.EmbedderEngine
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.core.registry.ModelEntry
import app.sanctum.machina.core.registry.ModelRegistry
import app.sanctum.machina.core.registry.ResetReason
import app.sanctum.machina.data.dao.EmbeddingRow
import app.sanctum.machina.data.dao.ProjectEmbeddingDao
import app.sanctum.machina.data.model.ProjectEmbeddingEntity
import app.sanctum.machina.engine.EmbedderNotReadyException
import app.sanctum.machina.engine.EmbedderRegistry
import app.sanctum.machina.engine.EmbedderState
import app.sanctum.machina.rag.EmbedderEncodeException
import app.sanctum.machina.rag.EmbeddingBlob
import app.sanctum.machina.rag.EmptyCorpusException
import app.sanctum.machina.rag.RagInjector
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RagInjectorTest {

  private lateinit var context: Context
  private lateinit var errorLog: ErrorLog
  private lateinit var errorLogFile: File
  private lateinit var embeddingDao: FakeEmbeddingDao

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    errorLog = ErrorLog(context)
    errorLogFile = File(context.filesDir, "logs/errors.log")
    errorLogFile.parentFile?.deleteRecursively()
    embeddingDao = FakeEmbeddingDao()
  }

  @After
  fun tearDown() {
    errorLogFile.parentFile?.deleteRecursively()
  }

  @Test
  fun retrieve_throwsEmptyCorpusWhenNoRows() = runTest {
    val registry = FakeEmbedderRegistry(state = EmbedderState.Ready)
    val injector = RagInjector(registry, embeddingDao, errorLog)

    val thrown = runCatching { injector.retrieve(projectId = 42L, query = "q", topK = 4) }
      .exceptionOrNull()
    assertTrue("EmptyCorpusException expected, got $thrown", thrown is EmptyCorpusException)
    assertEquals(42L, (thrown as EmptyCorpusException).projectId)
    assertEquals("encodeQuery must not be called for empty corpus", 0, registry.encodeCalls)
    val log = errorLogFile.readLines()
    assertTrue("rag-retrieve diagnostic logged, lines: $log",
      log.any { it.contains("[rag-retrieve]") && it.contains("empty corpus") })
  }

  @Test
  fun retrieve_throwsEncodeExceptionOnEmbedderFailure() = runTest {
    embeddingDao.rows.add(rowWith(fileId = 1L, vec = floatArrayOf(1f, 0f)))
    val boom = RuntimeException("interpreter aborted")
    val registry = FakeEmbedderRegistry(state = EmbedderState.Ready, encodeException = boom)
    val injector = RagInjector(registry, embeddingDao, errorLog)

    val thrown = runCatching { injector.retrieve(projectId = 1L, query = "q", topK = 4) }
      .exceptionOrNull()
    assertTrue("EmbedderEncodeException expected, got $thrown", thrown is EmbedderEncodeException)
    assertSame("cause preserved", boom, thrown!!.cause)
    val log = errorLogFile.readLines()
    assertTrue("encodeQuery failure logged under rag-retrieve, lines: $log",
      log.any { it.contains("[rag-retrieve]") && it.contains("encodeQuery failed") })
  }

  @Test
  fun retrieve_throwsNotReadyWhenStateNotReady() = runTest {
    val registry = FakeEmbedderRegistry(state = EmbedderState.Initializing)
    val injector = RagInjector(registry, embeddingDao, errorLog)

    val thrown = runCatching { injector.retrieve(1L, "q", 4) }.exceptionOrNull()
    assertTrue("EmbedderNotReadyException expected, got $thrown",
      thrown is EmbedderNotReadyException)
    assertEquals(EmbedderState.Initializing,
      (thrown as EmbedderNotReadyException).currentState)
    assertEquals("dao must not be queried for not-ready", 0, embeddingDao.queryCalls)
  }

  @Test
  fun retrieve_returnsRetrieverOutputOrder() = runTest {
    // Three rows; query vector identical to row B → B must rank first.
    embeddingDao.rows.add(rowWith(fileId = 1L, vec = floatArrayOf(1f, 0f, 0f), page = 1, name = "a.pdf"))
    embeddingDao.rows.add(rowWith(fileId = 2L, vec = floatArrayOf(0f, 1f, 0f), page = 2, name = "b.pdf"))
    embeddingDao.rows.add(rowWith(fileId = 3L, vec = floatArrayOf(0f, 0f, 1f), page = null, name = "c.pdf"))
    val query = floatArrayOf(0f, 1f, 0f)
    val registry = FakeEmbedderRegistry(state = EmbedderState.Ready, encodeResult = query)
    val injector = RagInjector(registry, embeddingDao, errorLog)

    val out = injector.retrieve(1L, "anything", topK = 3)

    assertEquals(3, out.size)
    assertEquals("top hit is exact-match fileId=2", 2L, out[0].fileId)
    assertEquals("b.pdf", out[0].fileName)
    assertEquals(2, out[0].page)
    assertTrue("cosine threaded through", out[0].cosine > 0.99f)
    // Nullable page surfaces verbatim on the page=null source row.
    val cRow = out.single { it.fileId == 3L }
    assertNull("page-null row keeps null on RetrievedChunk", cRow.page)
  }

  @Test
  fun retrieve_passesQueryVerbatim() = runTest {
    embeddingDao.rows.add(rowWith(fileId = 1L, vec = floatArrayOf(1f, 0f)))
    val registry = FakeEmbedderRegistry(state = EmbedderState.Ready, encodeResult = floatArrayOf(1f, 0f))
    val injector = RagInjector(registry, embeddingDao, errorLog)

    val rawQuery = "  has  Trailing   whitespace AND MIXED case  "
    injector.retrieve(1L, rawQuery, topK = 1)

    assertEquals("query forwarded verbatim", rawQuery, registry.lastEncodeQuery)
  }

  @Test
  fun retrieve_emptyQuery_passesThroughToEmbedder() = runTest {
    embeddingDao.rows.add(rowWith(fileId = 1L, vec = floatArrayOf(1f, 0f)))
    val registry = FakeEmbedderRegistry(state = EmbedderState.Ready, encodeResult = floatArrayOf(0.5f, 0.5f))
    val injector = RagInjector(registry, embeddingDao, errorLog)

    injector.retrieve(1L, query = "", topK = 1) // must not pre-validate empty
    assertEquals("", registry.lastEncodeQuery)
  }

  @Test
  fun retrieve_failedState_carriesStateInException() = runTest {
    val failed = EmbedderState.Failed("native abort", RuntimeException("x"))
    val registry = FakeEmbedderRegistry(state = failed)
    val injector = RagInjector(registry, embeddingDao, errorLog)

    val thrown = runCatching { injector.retrieve(1L, "q", 4) }.exceptionOrNull()
        as EmbedderNotReadyException?
    assertNotNull(thrown)
    assertSame(failed, thrown!!.currentState)
  }
}

// ---- Fakes ----

private class FakeEmbedderRegistry(
  state: EmbedderState,
  private val encodeResult: FloatArray = FloatArray(2),
  private val encodeException: Throwable? = null,
) : EmbedderRegistry(
  context = ApplicationProvider.getApplicationContext<Context>(),
  modelRegistry = stubModelRegistry(),
  engine = StubEmbedderEngine(),
  errorLog = ErrorLog(ApplicationProvider.getApplicationContext<Context>()),
  scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
  engineDispatcher = Dispatchers.Unconfined,
  clock = { 0L },
  idleTimeoutMillis = Long.MAX_VALUE,
  // Suppress the idle-teardown loop so the fake's `state` is the sole source of truth and
  // no spurious transitions happen mid-test.
  idleCheckIntervalMillis = Long.MAX_VALUE,
) {
  var encodeCalls: Int = 0; private set
  var lastEncodeQuery: String? = null; private set

  private val _state = MutableStateFlow(state)
  override val state: StateFlow<EmbedderState> get() = _state

  override suspend fun encodeQuery(text: String): FloatArray {
    encodeCalls++
    lastEncodeQuery = text
    encodeException?.let { throw it }
    return encodeResult
  }
}

/**
 * No-op `ModelRegistry`. EmbedderRegistry's @VisibleForTesting constructor stores the
 * reference but only consults `models.value` from `warmupLocked()` — which the tests in
 * this file never invoke (state is driven manually through `FakeEmbedderRegistry`).
 */
private fun stubModelRegistry(): ModelRegistry = object : ModelRegistry {
  override val models: StateFlow<List<ModelEntry>> = MutableStateFlow(emptyList())
  override val activeModelName: StateFlow<String?> = MutableStateFlow(null)
  override suspend fun refreshAllowlist(): Result<Unit> = Result.success(Unit)
  override fun download(model: Model): Flow<ModelDownloadStatus> = emptyFlow()
  override fun cancelDownload(modelName: String) {}
  override suspend fun delete(modelName: String) {}
  override suspend fun initialize(modelName: String): Result<Unit> = Result.success(Unit)
  override suspend fun cleanup(modelName: String) {}
  override suspend fun resetConversation(
    modelName: String,
    systemPrompt: String?,
    reason: ResetReason,
    initialMessages: List<com.google.ai.edge.litertlm.Message>,
  ) {}
  override fun getModel(modelName: String): Model? = null
}

private class StubEmbedderEngine : EmbedderEngine {
  override suspend fun initialize(
    context: Context, modelFile: File, tokenizerFile: File,
  ): Result<Unit> = Result.success(Unit)
  override fun encode(text: String, taskType: String): FloatArray = FloatArray(0)
  override fun releaseEngine() { /* no-op */ }
}

private class FakeEmbeddingDao : ProjectEmbeddingDao {
  val rows: MutableList<EmbeddingRow> = mutableListOf()
  var queryCalls: Int = 0; private set

  override suspend fun insertAll(rows: List<ProjectEmbeddingEntity>): List<Long> = emptyList()
  override suspend fun deleteByFileId(fileId: Long) {}
  override suspend fun getById(id: Long): ProjectEmbeddingEntity? = null
  override suspend fun allByProjectAndReadyFiles(projectId: Long): List<EmbeddingRow> {
    queryCalls++
    return rows.toList()
  }
}

private fun rowWith(
  fileId: Long,
  vec: FloatArray,
  page: Int? = 1,
  name: String = "f$fileId.pdf",
): EmbeddingRow = EmbeddingRow(
  fileId = fileId,
  fileName = name,
  page = page,
  chunkText = "chunk-$fileId",
  embeddingBlob = EmbeddingBlob.encode(vec),
)
