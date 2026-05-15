package app.sanctum.machina.data

/**
 * Per-project RAG knobs (Decision 11). Serialised as JSON via Gson and stored in
 * `projects.rag_overrides_json`. Allowlist defaults are merged in by callers that resolve
 * the *effective* config (Task 7 IngestWorker, Task 9 ProjectSettingsScreen) — this type
 * stays a plain value class so it round-trips through Gson without reflection surprises.
 */
data class RagConfig(
  val chunkSize: Int,
  val chunkOverlap: Int,
  val topK: Int,
  val embeddingDim: Int,
)
