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

@Singleton
class AllowlistLoader
@Inject
constructor(@ApplicationContext private val context: Context, private val gson: Gson = Gson()) {

  suspend fun load(): Result<List<Model>> =
    withContext(Dispatchers.IO) {
      runCatching { context.assets.open(ASSET_NAME) }
        .fold(
          onSuccess = { stream -> stream.use { parse(it, gson).map { list -> list.map(AllowedModel::toModel) } } },
          onFailure = { Result.failure(it) },
        )
    }

  companion object {
    /**
     * Core parse + schema-guard. Exposed so unit tests can drive it against a classpath fixture
     * without an Android [Context].
     */
    internal fun parse(stream: InputStream, gson: Gson): Result<List<AllowedModel>> = runCatching {
      val json = stream.bufferedReader().use { it.readText() }
      val allowlist = gson.fromJson(json, ModelAllowlist::class.java)
        ?: error("Allowlist JSON parsed to null")
      @Suppress("SENSELESS_COMPARISON")
      val models = allowlist.models ?: error("Allowlist has no models array")
      require(models.isNotEmpty()) { "Empty allowlist" }
      for (m in models) {
        require(m.modelId.startsWith(MODEL_ID_PREFIX)) {
          "modelId must start with '$MODEL_ID_PREFIX': ${m.modelId}"
        }
        require(m.sizeInBytes in 1..MAX_SIZE_BYTES) {
          "sizeInBytes out of range (1..$MAX_SIZE_BYTES): ${m.modelId} has ${m.sizeInBytes}"
        }
        val url = "$URL_PREFIX${m.modelId}/resolve/${m.commitHash}/${m.modelFile}"
        require(url.startsWith(URL_PREFIX)) {
          "Download URL must start with '$URL_PREFIX': $url"
        }
      }
      models
    }
  }
}
