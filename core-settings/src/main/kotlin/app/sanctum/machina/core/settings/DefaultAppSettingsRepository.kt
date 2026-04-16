package app.sanctum.machina.core.settings

import androidx.datastore.core.DataStore
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.core.settings.proto.AppSettings
import app.sanctum.machina.core.settings.proto.PerModelSettings
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private const val ERROR_COMPONENT = "settings-io"

@Singleton
class DefaultAppSettingsRepository @Inject constructor(
  private val dataStore: DataStore<AppSettings>,
  private val errorLog: ErrorLog,
) : AppSettingsRepository {

  override fun observePerModelSettings(modelId: String): Flow<PerModelSettings?> =
    dataStore.data
      .catch { cause ->
        if (cause is IOException) {
          errorLog.e(ERROR_COMPONENT, "observe failed for modelId=$modelId", cause)
          emit(AppSettings.getDefaultInstance())
        } else {
          throw cause
        }
      }
      .map { settings ->
        if (settings.perModelOverridesMap.containsKey(modelId))
          settings.perModelOverridesMap[modelId]
        else
          null
      }

  override suspend fun savePerModelSettings(modelId: String, settings: PerModelSettings) {
    try {
      dataStore.updateData { current ->
        current.toBuilder().putPerModelOverrides(modelId, settings).build()
      }
    } catch (e: IOException) {
      errorLog.e(ERROR_COMPONENT, "save failed for modelId=$modelId", e)
    }
  }

  override suspend fun resetPerModelSettings(modelId: String) {
    try {
      dataStore.updateData { current ->
        current.toBuilder().removePerModelOverrides(modelId).build()
      }
    } catch (e: IOException) {
      errorLog.e(ERROR_COMPONENT, "reset failed for modelId=$modelId", e)
    }
  }
}
