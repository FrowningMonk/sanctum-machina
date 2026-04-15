package app.sanctum.machina.core.registry

import android.content.Context
import app.sanctum.machina.core.data.AllowedModel
import app.sanctum.machina.core.data.Model
import app.sanctum.machina.core.data.ModelAllowlist
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

// Each segment: one or more of [A-Za-z0-9._-], but never contains ".." — prevents
// post-normalization escape from the litert-community org pin (e.g. `..`, `foo/..`).
private val MODEL_ID_REGEX = Regex("^litert-community/[A-Za-z0-9._-]+$")
private val MODEL_FILE_REGEX = Regex("^[A-Za-z0-9._-]+$")
private val COMMIT_HASH_REGEX = Regex("^[a-f0-9]{40}$")
private const val PATH_TRAVERSAL = ".."

@Singleton
class AllowlistLoader @Inject constructor(@ApplicationContext private val context: Context) {

  suspend fun load(): Result<List<Model>> =
    withContext(Dispatchers.IO) {
      runCatching {
        context.assets.open(ASSET_NAME).use { stream -> parse(stream).getOrThrow() }.map(AllowedModel::toModel)
      }
    }

  companion object {
    /**
     * Core parse + schema-guard. Exposed so unit tests can drive it against a classpath fixture
     * without an Android [Context].
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
        require(m.toModel().url.startsWith(URL_PREFIX)) {
          "Download URL must start with '$URL_PREFIX' for ${m.modelId}"
        }
      }
      models
    }
  }
}
