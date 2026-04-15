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
private const val MODEL_ID_PREFIX = "litert-community/"
private val MODEL_ID_REGEX = Regex("^litert-community/[A-Za-z0-9._-]+$")

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
        require(MODEL_ID_REGEX.matches(m.modelId)) {
          "modelId must match $MODEL_ID_REGEX: ${m.modelId}"
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
