package app.sanctum.machina.core.registry

import app.sanctum.machina.core.data.Model
import app.sanctum.machina.core.data.ModelDownloadStatus

/**
 * Phase 1 view-type over Gallery's [Model]. Bundles per-model reactive state surfaced by
 * [ModelRegistry]: the underlying [Model], latest [downloadStatus], and engine [initStatus].
 *
 * Phase-1 keeps the original [Model] class unmodified (user-spec D6 deferred to Phase 2);
 * [ModelEntry] is a wrapper, not a refactor.
 */
data class ModelEntry(
  val model: Model,
  val downloadStatus: ModelDownloadStatus,
  val initStatus: ModelInitStatus,
)
