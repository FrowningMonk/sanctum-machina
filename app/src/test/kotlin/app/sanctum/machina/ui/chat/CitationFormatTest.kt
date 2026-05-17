package app.sanctum.machina.ui.chat

import app.sanctum.machina.rag.RetrievedChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * TDD anchors for the pure helpers under `CitationFormat.kt` (Phase 4 Task 12).
 *
 * The visible-string format is owned by `strings.xml` and resolved through
 * `stringResource(...)` in the Composables; tests feed the same templates
 * verbatim so the format expectations stay coupled to the production
 * resource string. If a translator changes punctuation or spacing,
 * `values/strings.xml` and these templates move together.
 *
 * No Compose UI tests by project convention (tech-spec § Test pyramid).
 */
class CitationFormatTest {

    // Templates kept literal — match `values/strings.xml` rows for
    // `citation_chip_label_with_page` / `citation_chip_label_no_page`
    // (without xliff:g wrappers, which Android resolves away).
    private val withPage = "[%1\$s · p. %2\$d]"
    private val noPage = "[%1\$s]"

    @Test
    fun formatChipLabel_withPage() {
        val label = formatChipLabel(
            filename = "1c-manual.pdf",
            page = 12,
            withPageTemplate = withPage,
            noPageTemplate = noPage,
        )
        assertEquals("[1c-manual.pdf · p. 12]", label)
    }

    @Test
    fun formatChipLabel_pageNull() {
        val label = formatChipLabel(
            filename = "notes.pdf",
            page = null,
            withPageTemplate = withPage,
            noPageTemplate = noPage,
        )
        assertEquals("[notes.pdf]", label)
    }

    @Test
    fun formatChipLabel_filenameWithSpecialChars() {
        // Filename comes from user-supplied PDF metadata. The format helper
        // must treat it as a literal — no markdown escaping, no URL parsing,
        // no bracket double-encoding. The bracketed `[ ... ]` chars in the
        // filename should appear verbatim inside the chip label brackets.
        val tricky = "[ВАЖНО] manual (v2) · raw.pdf"
        val label = formatChipLabel(
            filename = tricky,
            page = 7,
            withPageTemplate = withPage,
            noPageTemplate = noPage,
        )
        assertEquals("[[ВАЖНО] manual (v2) · raw.pdf · p. 7]", label)
    }

    @Test
    fun retrievedChunkToCitation_mapping() {
        val chunk = RetrievedChunk(
            fileId = 42L,
            fileName = "doc.pdf",
            page = 3,
            chunkText = "hello world",
            cosine = 0.87f,
        )
        val citation = chunk.toCitation()

        assertEquals(42L, citation.fileId)
        assertEquals("doc.pdf", citation.fileName)
        assertEquals(3, citation.page)
        assertEquals("hello world", citation.chunkText)
        // Mid-stream conversion: never stale by construction — cascade-stale
        // is a Room-side concern.
        assertFalse(citation.stale)
    }

    @Test
    fun formatChipLabel_pageZeroTakesWithPageBranch() {
        // Page is `Int?`, not `Int >= 1`. `0` is a legal page number for PDFs
        // with zero-based numbering metadata — must follow the with-page branch
        // (not the noPage fallback).
        val label = formatChipLabel(
            filename = "zero-indexed.pdf",
            page = 0,
            withPageTemplate = withPage,
            noPageTemplate = noPage,
        )
        assertEquals("[zero-indexed.pdf · p. 0]", label)
    }

    @Test
    fun retrievedChunkToCitation_nullPagePreserved() {
        val chunk = RetrievedChunk(
            fileId = 1L,
            fileName = "no-pages.pdf",
            page = null,
            chunkText = "body",
            cosine = 0.5f,
        )
        val citation = chunk.toCitation()
        assertEquals(null, citation.page)
    }
}
