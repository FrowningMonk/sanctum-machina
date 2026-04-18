package app.sanctum.machina.core.settings

import app.sanctum.machina.core.settings.proto.PerModelSettings
import kotlinx.coroutines.flow.Flow

interface AppSettingsRepository {
  fun observePerModelSettings(modelId: String): Flow<PerModelSettings?>
  suspend fun savePerModelSettings(modelId: String, settings: PerModelSettings)
  suspend fun resetPerModelSettings(modelId: String)
}
