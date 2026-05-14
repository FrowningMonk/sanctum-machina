package app.sanctum.machina.core.registry

import android.content.Context
import androidx.annotation.VisibleForTesting
import app.sanctum.machina.core.data.AllowedModel
import app.sanctum.machina.core.data.Model
import app.sanctum.machina.core.data.ModelAllowlist
import app.sanctum.machina.core.log.ErrorLog
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ASSET_NAME = "model_allowlist.json"
private const val MAX_SIZE_BYTES: Long = 10_737_418_240L // 10 GB
private const val URL_PREFIX = "https://huggingface.co/"
private const val MIN_DEVICE_MEMORY_GB_RANGE_LO = 1
private const val MIN_DEVICE_MEMORY_GB_RANGE_HI = 64
// Phase 3.7 Task 1 (Decision 11): defence-in-depth bounds on AllowedModelConfig.maxContextLength
// so a future allowlist edit cannot propagate an out-of-range value to LiteRT-LM `max_num_tokens`.
private const val MAX_CONTEXT_LENGTH_RANGE_LO = 1024
private const val MAX_CONTEXT_LENGTH_RANGE_HI = 131072

// Each segment: one or more of [A-Za-z0-9._-], but never contains ".." — prevents
// post-normalization escape from the org pin (e.g. `..`, `foo/..`).
// Phase 4 Task 1 (Decision 4): widened to an org-whitelist `litert-community|google` to
// accept EmbeddingGemma checkpoints hosted under `google/*`. The pin still keeps the source
// bounded — no open allowlist of HF orgs. (Our ship-row uses `litert-community/`, but the
// regex stays widened per AC-9; any future Google-hosted checkpoint is parsable without code
// change.)
private val MODEL_ID_REGEX = Regex("^(litert-community|google)/[A-Za-z0-9._-]+$")
private val MODEL_FILE_REGEX = Regex("^[A-Za-z0-9._-]+$")
private val COMMIT_HASH_REGEX = Regex("^[a-f0-9]{40}$")
private const val PATH_TRAVERSAL = ".."

// Phase 4 Task 1 (Decision 11): Matryoshka truncation enum from EmbeddingGemma model card.
// Any other value is rejected at parse time — vector store is fixed-dim per project.
private val MATRYOSHKA_EMBEDDING_DIMS = setOf(128, 256, 512, 768)

@Singleton
class AllowlistLoader @Inject constructor(
  @ApplicationContext private val context: Context,
  private val errorLog: ErrorLog,
) {

  suspend fun load(): Result<List<Model>> =
    withContext(Dispatchers.IO) {
      context.assets.open(ASSET_NAME).use { stream -> loadFromStream(stream) }
    }

  /**
   * Test seam: drives the same parse → toModel → log-on-rejection path as [load], but reads
   * from a caller-provided stream instead of the bundled asset. Visible to unit tests in
   * `:core-runtime` so the rejection-logging contract (Phase 3.5 Task 1, Decision 5) can be
   * exercised without overriding bundled assets.
   */
  @VisibleForTesting
  internal suspend fun loadFromStream(stream: InputStream): Result<List<Model>> =
    runCatching { parse(stream).getOrThrow().map(AllowedModel::toModel) }
      .onFailure { cause ->
        if (cause is IllegalArgumentException) {
          errorLog.e(LOG_TAG_DOWNLOAD, "model rejected: ${cause.message}")
        }
      }

  companion object {
    private const val LOG_TAG_DOWNLOAD = "download"

    /**
     * Core parse + schema-guard. Aggregate fail-fast: on the first invalid record the whole
     * call returns [Result.failure] so the caller ([load]) can record one rejection event and
     * keep the registry empty rather than load a partially-trusted allowlist. Exposed so unit
     * tests can drive it against a classpath fixture without an Android [Context].
     */
    internal fun parse(stream: InputStream): Result<List<AllowedModel>> = runCatching {
      val json = stream.bufferedReader().use { it.readText() }
      val allowlist = Gson().fromJson(json, ModelAllowlist::class.java)
        ?: error("Allowlist JSON parsed to null")
      // Gson bypasses Kotlin nullability via reflection; `models` is typed non-null but may be null at runtime.
      val models: List<AllowedModel>? = allowlist.models
      require(!models.isNullOrEmpty()) { "Empty allowlist" }
      for (m in models) {
        require(MODEL_ID_REGEX.matches(m.modelId) && !m.modelId.contains(PATH_TRAVERSAL)) {
          "modelId must match $MODEL_ID_REGEX without '$PATH_TRAVERSAL': ${m.modelId}"
        }
        require(MODEL_FILE_REGEX.matches(m.modelFile) && !m.modelFile.contains(PATH_TRAVERSAL)) {
          "modelFile must match $MODEL_FILE_REGEX without '$PATH_TRAVERSAL': ${m.modelFile}"
        }
        require(COMMIT_HASH_REGEX.matches(m.commitHash)) {
          "commitHash must match $COMMIT_HASH_REGEX: ${m.commitHash}"
        }
        require(m.sizeInBytes in 1..MAX_SIZE_BYTES) {
          "sizeInBytes out of range (1..$MAX_SIZE_BYTES): ${m.modelId} has ${m.sizeInBytes}"
        }
        val minMemory = m.minDeviceMemoryInGb
        require(minMemory != null) {
          "minDeviceMemoryInGb is null/missing: ${m.modelId}"
        }
        require(minMemory in MIN_DEVICE_MEMORY_GB_RANGE_LO..MIN_DEVICE_MEMORY_GB_RANGE_HI) {
          "minDeviceMemoryInGb=$minMemory not in " +
            "$MIN_DEVICE_MEMORY_GB_RANGE_LO..$MIN_DEVICE_MEMORY_GB_RANGE_HI: ${m.modelId}"
        }
        val maxContext = m.defaultConfig?.maxContextLength
        require(maxContext == null || maxContext in MAX_CONTEXT_LENGTH_RANGE_LO..MAX_CONTEXT_LENGTH_RANGE_HI) {
          "maxContextLength=$maxContext not in " +
            "$MAX_CONTEXT_LENGTH_RANGE_LO..$MAX_CONTEXT_LENGTH_RANGE_HI: ${m.modelId}"
        }
        // Phase 4 Task 1: taskTypes must be non-empty — it now drives RuntimeType derivation
        // in AllowedModel.toModel(). An empty list would have produced ambiguous runtimeType,
        // so fail fast here rather than silently default to LITERT_LM.
        require(m.taskTypes.isNotEmpty()) {
          "taskTypes must be non-empty: ${m.modelId}"
        }
        // Phase 4 Task 1 (Decision 11): when defaultRagConfig is present, validate against
        // EmbeddingGemma's documented bounds. Absent → null is fine (chat rows leave it null).
        val ragCfg = m.defaultRagConfig
        if (ragCfg != null) {
          require(ragCfg.chunkSize > 0) {
            "defaultRagConfig.chunkSize must be > 0: ${m.modelId} has ${ragCfg.chunkSize}"
          }
          require(ragCfg.chunkOverlap >= 0 && ragCfg.chunkOverlap < ragCfg.chunkSize) {
            "defaultRagConfig.chunkOverlap=${ragCfg.chunkOverlap} must be in " +
              "[0, chunkSize=${ragCfg.chunkSize}): ${m.modelId}"
          }
          require(ragCfg.topK > 0) {
            "defaultRagConfig.topK must be > 0: ${m.modelId} has ${ragCfg.topK}"
          }
          require(ragCfg.embeddingDim in MATRYOSHKA_EMBEDDING_DIMS) {
            "defaultRagConfig.embeddingDim=${ragCfg.embeddingDim} must be one of " +
              "$MATRYOSHKA_EMBEDDING_DIMS: ${m.modelId}"
          }
        }
        require(m.toModel().url.startsWith(URL_PREFIX)) {
          "Download URL must start with '$URL_PREFIX' for ${m.modelId}"
        }
      }
      models
    }
  }
}
