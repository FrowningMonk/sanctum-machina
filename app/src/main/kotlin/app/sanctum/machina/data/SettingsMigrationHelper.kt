package app.sanctum.machina.data

import androidx.datastore.core.DataStore
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.core.registry.ModelRegistry
import app.sanctum.machina.core.settings.AppSettingsRepository
import app.sanctum.machina.core.settings.proto.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

private const val ERROR_COMPONENT = "settings-io"

/**
 * One-shot Phase 3 rekey of `AppSettings.per_model_overrides` from `Model.name`
 * (Phase 1/2 key) to the stable HuggingFace repo id `Model.modelId` (Phase 3+).
 *
 * The migration runs at most once per install: [AppSettings.settings_keys_migrated]
 * is checked before any work and set to `true` after a successful rewrite.
 *
 * Every rekey happens inside a single [DataStore.updateData] block — DataStore's
 * atomicity guarantees that a crash mid-migration leaves either the old map or
 * the new map, never a partial blend (Decision 8, AC-R8).
 *
 * Orphan keys — overrides whose name does not match any current allowlist entry
 * — are logged via [ErrorLog.e] (`settings-io`) and dropped; there is no model
 * they could migrate to.
 */
@Singleton
// `open` so `SanctumApplicationTest` can substitute a recording fake to prove
// `migrateIfNeeded()` is launched inside the `packageName` guard (AC / tech-spec Cold start
// sequence step 3).
open class SettingsMigrationHelper @Inject constructor(
  private val appSettings: AppSettingsRepository,
  private val registry: ModelRegistry,
  private val dataStore: DataStore<AppSettings>,
  private val errorLog: ErrorLog,
) {

  open suspend fun migrateIfNeeded() {
    if (appSettings.isSettingsMigrated()) return

    // Snapshot the allowlist once. Using `name` as the lookup key matches the
    // Phase 1/2 override-key convention.
    val nameToModelId: Map<String, String> =
      registry.models.value.associate { it.model.name to it.model.modelId }

    // Rekey and sentinel flip happen in the SAME updateData transform. A separate
    // markSettingsMigrated() write would expose a kill-window: process death
    // between the two writes would leave rekeyed data without the sentinel, and
    // the next boot would re-run the migration against modelId-keyed data, see
    // every key as "orphan", and clear all overrides. Folding the flag in makes
    // the migration a single atomic DataStore transition (Decision 8, AC-R8).
    dataStore.updateData { current ->
      val old = current.perModelOverridesMap
      val remapped = LinkedHashMap<String, app.sanctum.machina.core.settings.proto.PerModelSettings>(old.size)
      for ((oldKey, value) in old) {
        val newKey = nameToModelId[oldKey]
        if (newKey.isNullOrEmpty()) {
          errorLog.e(
            ERROR_COMPONENT,
            "orphan per-model-settings key dropped during Phase 3 rekey: $oldKey",
          )
          continue
        }
        remapped[newKey] = value
      }
      current.toBuilder()
        .clearPerModelOverrides()
        .putAllPerModelOverrides(remapped)
        .setSettingsKeysMigrated(true)
        .build()
    }
  }
}
