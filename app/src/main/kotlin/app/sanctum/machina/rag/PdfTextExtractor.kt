package app.sanctum.machina.rag

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One page of extracted PDF text. [page] is 1-based to match `PDFTextStripper`. */
data class PageText(val page: Int, val text: String)

/**
 * Receives per-document and per-page errors from [PdfTextExtractor]. Production
 * wiring binds this to `ErrorLog.e("pdf-parse", ...)`; tests pass a recording
 * stub so they do not depend on the `pdf-parse` component being whitelisted yet.
 */
fun interface PdfParseLogger {
  suspend fun log(message: String, cause: Throwable?)
}

/**
 * Page-tagged PDF text extractor over `pdfbox-android` (Decision 9).
 *
 * Defensive against hostile or malformed PDFs:
 *   * each page is wrapped in [withTimeoutOrNull] (default 5 s) — slow pages
 *     are skipped, not blocking;
 *   * every per-page failure is caught as `Throwable` (the native pdfbox /
 *     BouncyCastle paths can surface `Error` subclasses such as
 *     `NoClassDefFoundError`) — the offending page is logged + skipped,
 *     remaining pages continue;
 *   * encrypted documents are reported once and yield nothing (no empty-
 *     password retry, per Decision 9 / R-8).
 *
 * `PDFBoxResourceLoader.init` runs exactly once per process — driven by an
 * [AtomicBoolean], passed `applicationContext` so the loader does not
 * retain an Activity. R-T4 (~600 ms cold init) is paid here on the worker
 * thread, never on UI.
 */
class PdfTextExtractor internal constructor(
  private val context: Context,
  private val logger: PdfParseLogger,
  private val pageReader: PageReader,
) {

  constructor(
    context: Context,
    logger: PdfParseLogger = PdfParseLogger { _, _ -> },
  ) : this(context, logger, DefaultPageReader)

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
   * between pages via [ensureActive].
   *
   * Errors do not throw: malformed header / encrypted document / per-page
   * failure / per-page timeout all route to [logger] and surface as
   * "missing" pages — empty flow for whole-document failures, gap in page
   * numbers for individual page failures.
   */
  fun extract(
    file: File,
    perPageTimeoutMs: Long = DEFAULT_PER_PAGE_TIMEOUT_MS,
  ): Flow<PageText> = flow {
    ensureInitialized(context)

    if (!file.canRead()) {
      logger.log("cannot read file=${file.name}", null)
      return@flow
    }

    val doc: PDDocument = try {
      withContext(Dispatchers.IO) { PDDocument.load(file) }
    } catch (ce: CancellationException) {
      throw ce
    } catch (t: Throwable) {
      logger.log("open failed file=${file.name}", t)
      return@flow
    }

    try {
      if (doc.isEncrypted) {
        logger.log("encrypted file=${file.name}", null)
        return@flow
      }
      val pageCount = doc.numberOfPages
      for (i in 1..pageCount) {
        currentCoroutineContext().ensureActive()
        val text: String? = try {
          val result = withTimeoutOrNull(perPageTimeoutMs) { pageReader.read(doc, i) }
          if (result == null) logger.log("timeout page=$i file=${file.name}", null)
          result
        } catch (ce: CancellationException) {
          throw ce
        } catch (t: Throwable) {
          logger.log("page=$i file=${file.name}", t)
          null
        }
        if (text != null) emit(PageText(i, text))
      }
    } finally {
      runCatching { doc.close() }
    }
  }.flowOn(Dispatchers.IO)

  private object DefaultPageReader : PageReader {
    override suspend fun read(doc: PDDocument, page: Int): String =
      PDFTextStripper().apply {
        startPage = page
        endPage = page
      }.getText(doc)
  }

  companion object {
    const val DEFAULT_PER_PAGE_TIMEOUT_MS: Long = 5_000L

    private val initialized = AtomicBoolean(false)

    /**
     * Idempotent one-shot init of `PDFBoxResourceLoader`. Public so tests
     * and pre-warm callers (`IngestWorker.doWork`, R-T4) can drive it
     * explicitly; otherwise [extract] calls it lazily on first use.
     */
    fun ensureInitialized(context: Context) {
      if (initialized.compareAndSet(false, true)) {
        PDFBoxResourceLoader.init(context.applicationContext)
      }
    }
  }
}
