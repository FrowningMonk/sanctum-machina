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
import kotlinx.coroutines.flow.first
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

  override suspend fun getDefaultModelId(): String =
    try {
      dataStore.data.first().defaultModelId
    } catch (e: IOException) {
      errorLog.e(ERROR_COMPONENT, "getDefaultModelId failed", e)
      ""
    }

  override suspend fun setDefaultModelId(id: String) {
    try {
      dataStore.updateData { it.toBuilder().setDefaultModelId(id).build() }
    } catch (e: IOException) {
      errorLog.e(ERROR_COMPONENT, "setDefaultModelId failed", e)
    }
  }

  override fun observeDefaultModelId(): Flow<String> =
    dataStore.data
      .catch { cause ->
        if (cause is IOException) {
          errorLog.e(ERROR_COMPONENT, "observeDefaultModelId failed", cause)
          emit(AppSettings.getDefaultInstance())
        } else {
          throw cause
        }
      }
      .map { it.defaultModelId }

  override suspend fun getLastUsedModelId(): String =
    try {
      dataStore.data.first().lastUsedModelId
    } catch (e: IOException) {
      errorLog.e(ERROR_COMPONENT, "getLastUsedModelId failed", e)
      ""
    }

  override suspend fun setLastUsedModelId(id: String) {
    try {
      dataStore.updateData { it.toBuilder().setLastUsedModelId(id).build() }
    } catch (e: IOException) {
      errorLog.e(ERROR_COMPONENT, "setLastUsedModelId failed", e)
    }
  }

  override fun observeLastUsedModelId(): Flow<String> =
    dataStore.data
      .catch { cause ->
        if (cause is IOException) {
          errorLog.e(ERROR_COMPONENT, "observeLastUsedModelId failed", cause)
          emit(AppSettings.getDefaultInstance())
        } else {
          throw cause
        }
      }
      .map { it.lastUsedModelId }

  override suspend fun isSettingsMigrated(): Boolean =
    try {
      dataStore.data.first().settingsKeysMigrated
    } catch (e: IOException) {
      errorLog.e(ERROR_COMPONENT, "isSettingsMigrated failed", e)
      false
    }

  override suspend fun markSettingsMigrated() {
    try {
      dataStore.updateData { it.toBuilder().setSettingsKeysMigrated(true).build() }
    } catch (e: IOException) {
      errorLog.e(ERROR_COMPONENT, "markSettingsMigrated failed", e)
    }
  }
}
