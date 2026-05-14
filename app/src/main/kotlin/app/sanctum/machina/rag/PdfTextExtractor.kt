package app.sanctum.machina.rag

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull

/** One page of extracted PDF text. [page] is 1-based to match `PDFTextStripper`. */
data class PageText(val page: Int, val text: String)

/**
 * Receives per-document and per-page errors from [PdfTextExtractor]. Production
 * wiring binds this to `ErrorLog.e("pdf-parse", ...)`; tests pass a recording
 * stub so they do not depend on the `pdf-parse` component being whitelisted yet.
 *
 * No default no-op binding is supplied on purpose — silent extraction failures
 * would mask hostile-PDF / corrupt-file telemetry that R-8 relies on.
 */
fun interface PdfParseLogger {
  suspend fun log(message: String, cause: Throwable?)
}

/**
 * Page-tagged PDF text extractor over `pdfbox-android` (Decision 9).
 *
 * Defensive against hostile or malformed PDFs:
 *   * each page is wrapped in [withTimeoutOrNull] (default 5 s). The timeout is
 *     **cooperative**: pdfbox's `PDFTextStripper.getText` does not check
 *     `isActive`, so a hostile page can keep the underlying IO thread parked
 *     for the full duration of its native loop even after `withTimeoutOrNull`
 *     returns. R-8 accepts this — caller still sees the page as "skipped" and
 *     remaining pages continue;
 *   * every per-page failure is caught as `Throwable` (the native pdfbox /
 *     BouncyCastle paths can surface `Error` subclasses such as
 *     `NoClassDefFoundError`) — the offending page is logged + skipped;
 *   * encrypted documents are reported once and yield nothing (no empty-
 *     password retry, per Decision 9 / R-8);
 *   * page count is hard-capped at [MAX_PAGES] to bound the per-document
 *     worst case if a hostile `/Count` and a slow native loop combine.
 *
 * `PDFBoxResourceLoader.init` runs exactly once per process — guarded by a
 * synchronized block (not just CAS — pdfbox's loader does internal mutable
 * work that must complete before any other caller observes the initialized
 * state). Passed `applicationContext` so the loader does not retain an
 * Activity. R-T4 (~600 ms cold init) is paid here on the worker thread,
 * never on UI.
 */
class PdfTextExtractor internal constructor(
  private val context: Context,
  private val logger: PdfParseLogger,
  private val pageReader: PageReader,
  private val maxPages: Int = MAX_PAGES,
) {

  constructor(context: Context, logger: PdfParseLogger) :
    this(context, logger, DefaultPageReader)

  /**
   * Strategy for reading one page's text. Default delegates to
   * `PDFTextStripper`; tests inject deterministic readers (e.g. a slow one
   * to exercise the per-page timeout) to avoid mocking pdfbox internals.
   */
  fun interface PageReader {
    suspend fun read(doc: PDDocument, page: Int): String
  }

  /**
   * Emits one [PageText] per page in document order (1-based). Runs on
   * [Dispatchers.IO]. Cancellation of the collecting coroutine is honoured
   * between pages via `ensureActive`.
   *
   * Errors do not throw: malformed header / encrypted document / per-page
   * failure / per-page timeout / page-count exceeded all route to [logger]
   * and surface as "missing" pages — empty flow for whole-document failures,
   * gap in page numbers for individual page failures.
   */
  fun extract(
    file: File,
    perPageTimeoutMs: Long = DEFAULT_PER_PAGE_TIMEOUT_MS,
  ): Flow<PageText> = flow {
    ensureInitialized(context)

    val safeName = sanitizeName(file.name)
    if (!file.canRead()) {
      logger.log("cannot read file=$safeName", null)
      return@flow
    }

    val doc: PDDocument = try {
      PDDocument.load(file)
    } catch (ce: CancellationException) {
      throw ce
    } catch (e: InvalidPasswordException) {
      // pdfbox 2.x auto-attempts an empty password during load; a non-empty
      // user password surfaces here as a typed exception before isEncrypted
      // can be observed. Treat both routes uniformly.
      logger.log("encrypted file=$safeName", null)
      return@flow
    } catch (t: Throwable) {
      logger.log("open failed file=$safeName", t)
      return@flow
    }

    try {
      if (doc.isEncrypted) {
        logger.log("encrypted file=$safeName", null)
        return@flow
      }
      val rawPageCount = doc.numberOfPages
      val pageCount = rawPageCount.coerceAtMost(maxPages)
      if (rawPageCount > maxPages) {
        logger.log("page-cap file=$safeName pages=$rawPageCount cap=$maxPages", null)
      }
      for (i in 1..pageCount) {
        currentCoroutineContext().ensureActive()
        val text: String? = try {
          val result = withTimeoutOrNull(perPageTimeoutMs) { pageReader.read(doc, i) }
          if (result == null) logger.log("timeout page=$i file=$safeName", null)
          result
        } catch (ce: CancellationException) {
          throw ce
        } catch (t: Throwable) {
          logger.log("page=$i file=$safeName", t)
          null
        }
        if (text != null) emit(PageText(i, text))
      }
    } finally {
      try {
        doc.close()
      } catch (ce: CancellationException) {
        throw ce
      } catch (t: Throwable) {
        logger.log("close failed file=$safeName", t)
      }
    }
  }.flowOn(Dispatchers.IO)

  private fun sanitizeName(name: String): String =
    name.replace(CONTROL_WS, " ").take(MAX_LOG_NAME_LEN)

  private object DefaultPageReader : PageReader {
    override suspend fun read(doc: PDDocument, page: Int): String =
      PDFTextStripper().apply {
        startPage = page
        endPage = page
      }.getText(doc)
  }

  companion object {
    const val DEFAULT_PER_PAGE_TIMEOUT_MS: Long = 5_000L

    /**
     * Hard cap on pages processed per document. Above this, the rest is
     * dropped + logged once. Picked at 2000 — comfortably covers realistic
     * project corpora while bounding the worst case if a hostile `/Count`
     * combines with a slow native loop. See R-8.
     */
    const val MAX_PAGES: Int = 2_000

    private const val MAX_LOG_NAME_LEN: Int = 120
    // All C0 controls (U+0000..U+001F) and DEL (U+007F). Aligns the substitution
    // with the function name `sanitizeName` and blocks log-injection vectors
    // beyond the obvious \n\r\t.
    private val CONTROL_WS = Regex("[\\x00-\\x1F\\x7F]")

    private val initLock = Any()

    @Volatile
    private var initialized: Boolean = false

    /**
     * Idempotent one-shot init of `PDFBoxResourceLoader`. Public so tests
     * and pre-warm callers (`IngestWorker.doWork`, R-T4) can drive it
     * explicitly; otherwise [extract] calls it lazily on first use.
     */
    fun ensureInitialized(context: Context) {
      if (initialized) return
      synchronized(initLock) {
        if (initialized) return
        PDFBoxResourceLoader.init(context.applicationContext)
        initialized = true
      }
    }
  }
}
