package app.sanctum.machina.ui.chat

import app.sanctum.machina.data.Citation
import app.sanctum.machina.rag.RetrievedChunk

/**
 * Pure helpers backing the citation chip-strip (Phase 4 Task 12).
 *
 * Format strings live in `strings.xml` so locales can swap bracket / separator
 * glyphs without touching Kotlin. [formatChipLabel] applies the resolved
 * template — `MessageBubble.kt` calls it from inside the chip Composable so the
 * branch on `page == null` stays unit-testable without standing up a Compose
 * tree. Modal title and content-description currently share the with-page /
 * no-page structure but call `stringResource(...)` directly because they live
 * in different keys and the branching is trivial; if those keys ever diverge
 * structurally, mirror this helper.
 *
 * Untrusted-content note: the `filename` placeholder is interpolated via
 * `String.format`. `java.util.Formatter` treats arguments as data — a filename
 * like `"%s%n"` renders literally, not as a format specifier. The chip body and
 * modal `chunk_text` go through plain `Text` (no markdown / link parser); see
 * `CitationModal.kt` and tech-spec § Security for the broader rule.
 */

internal fun formatChipLabel(
    filename: String,
    page: Int?,
    withPageTemplate: String,
    noPageTemplate: String,
): String = if (page != null) {
    withPageTemplate.format(filename, page)
} else {
    noPageTemplate.format(filename)
}

/**
 * Mid-stream adapter: the streaming bubble carries `RetrievedChunk` from the
 * RAG pipeline while the persisted bubble carries `Citation` from Room. The
 * chip-strip composable only knows `Citation`, so this is the one-way bridge.
 *
 * `cosine` is intentionally dropped — `Citation` is the long-lived snapshot
 * stored in `messages.citations` JSON (Decision 7) and the per-retrieval score
 * has no audience on the UI side. Ranking has already happened upstream in
 * `RagInjector`; the chip only surfaces filename / page / chunkText / stale.
 *
 * Mid-stream chunks are by definition fresh from the current ranked retrieve,
 * so `stale` is hard-coded to `false`. Stale-marking happens on the Room side
 * via `MessageDao.markCitationsStale` after `ProjectRepository.deleteFile`
 * cascades — `RetrievedChunk` is never observed in a stale state.
 */
internal fun RetrievedChunk.toCitation(): Citation = Citation(
    fileId = fileId,
    fileName = fileName,
    page = page,
    chunkText = chunkText,
    stale = false,
)
