package app.sanctum.machina.ui.chat

import app.sanctum.machina.data.Citation
import app.sanctum.machina.rag.RetrievedChunk

/**
 * Pure helpers backing the citation chip-strip and modal (Phase 4 Task 12).
 *
 * Format strings live in `strings.xml` so locale / translator can swap the
 * bracket and separator glyphs without touching Kotlin. These functions
 * shape the resolved templates so unit tests can assert label / a11y output
 * without standing up a Composable tree.
 *
 * Untrusted-content note: the `filename` placeholder is interpolated through
 * `String.format` — it is treated as a literal here and rendered through
 * plain `Text` in the modal (no markdown / link parsers anywhere on this
 * path). See `CitationModal.kt` and tech-spec § Security for the broader
 * argument why citation payloads bypass `SafeMarkdown`.
 */

/** Builds the chip label from already-resolved templates (with-page / no-page). */
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

/** Same shape for the modal title. Kept separate so locales can diverge bracket/sep glyphs. */
internal fun formatModalTitle(
    filename: String,
    page: Int?,
    withPageTemplate: String,
    noPageTemplate: String,
): String = if (page != null) {
    withPageTemplate.format(filename, page)
} else {
    noPageTemplate.format(filename)
}

/** Builds the chip TalkBack description. Mirrors visible label structure. */
internal fun formatChipContentDescription(
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
 * Mid-stream chunks are by definition fresh from the current ranked retrieve,
 * so `stale` is hard-coded to `false`. Stale-marking happens on the Room
 * side via `MessageDao.markCitationsStale` after `ProjectRepository.deleteFile`
 * cascades — `RetrievedChunk` is never observed in a stale state.
 */
internal fun RetrievedChunk.toCitation(): Citation = Citation(
    fileId = fileId,
    fileName = fileName,
    page = page,
    chunkText = chunkText,
    stale = false,
)
