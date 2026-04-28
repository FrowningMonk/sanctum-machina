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

// Each segment: one or more of [A-Za-z0-9._-], but never contains ".." — prevents
// post-normalization escape from the litert-community org pin (e.g. `..`, `foo/..`).
private val MODEL_ID_REGEX = Regex("^litert-community/[A-Za-z0-9._-]+$")
private val MODEL_FILE_REGEX = Regex("^[A-Za-z0-9._-]+$")
private val COMMIT_HASH_REGEX = Regex("^[a-f0-9]{40}$")
private const val PATH_TRAVERSAL = ".."

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
          "model ${m.modelId} rejected: minDeviceMemoryInGb is null/missing"
        }
        require(minMemory in MIN_DEVICE_MEMORY_GB_RANGE_LO..MIN_DEVICE_MEMORY_GB_RANGE_HI) {
          "model ${m.modelId} rejected: minDeviceMemoryInGb=$minMemory not in " +
            "$MIN_DEVICE_MEMORY_GB_RANGE_LO..$MIN_DEVICE_MEMORY_GB_RANGE_HI"
        }
        require(m.toModel().url.startsWith(URL_PREFIX)) {
          "Download URL must start with '$URL_PREFIX' for ${m.modelId}"
        }
      }
      models
    }
  }
}
