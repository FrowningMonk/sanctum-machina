package app.sanctum.machina.core.settings

import app.sanctum.machina.core.settings.proto.PerModelSettings
import kotlinx.coroutines.flow.Flow

interface AppSettingsRepository {
  fun observePerModelSettings(modelId: String): Flow<PerModelSettings?>
  suspend fun savePerModelSettings(modelId: String, settings: PerModelSettings)
  suspend fun resetPerModelSettings(modelId: String)

  // Default model — persisted via `AppSettings.default_model_id` (proto3 string,
  // default `""`). `observe*` is non-suspend so Compose collectors can start
  // immediately; consumers treat `""` as "no default selected".
  suspend fun getDefaultModelId(): String
  suspend fun setDefaultModelId(id: String)
  fun observeDefaultModelId(): Flow<String>

  // Last successfully warmed-up model — used as fallback when default is unset.
  suspend fun getLastUsedModelId(): String
  suspend fun setLastUsedModelId(id: String)
  fun observeLastUsedModelId(): Flow<String>

  // One-shot Phase 3 rekey sentinel (AC-R8). Checked by SettingsMigrationHelper.
  suspend fun isSettingsMigrated(): Boolean
  suspend fun markSettingsMigrated()
}
