package app.sanctum.machina.rag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PdfTextExtractorTest {

  private lateinit var ctx: Context
  private lateinit var fixturesDir: File
  private lateinit var onePage: File
  private lateinit var fiftyPages: File
  private lateinit var encrypted: File
  private lateinit var scannedImage: File
  private lateinit var malformedHeader: File

  // Robolectric isolates each test in its own ClassLoader sandbox — Companion-
  // level statics are reset per test, so fixtures are rebuilt in @Before
  // instead of @BeforeClass. The build is a few hundred ms total; acceptable.
  @Before
  fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    fixturesDir = File(ctx.cacheDir, "pdf-fixtures").apply {
      deleteRecursively()
      mkdirs()
    }
    PdfTextExtractor.ensureInitialized(ctx)

    onePage = newPdf("one_page.pdf") { doc ->
      addTextPage(doc, "Hello, PhoneWrap.")
    }
    fiftyPages = newPdf("fifty_pages.pdf") { doc ->
      for (i in 1..50) addTextPage(doc, "Page $i body text.")
    }
    encrypted = newPdf("encrypted.pdf") { doc ->
      addTextPage(doc, "Secret text.")
      val policy = StandardProtectionPolicy(
        "owner-secret",
        "user-secret",
        AccessPermission(),
      ).apply { encryptionKeyLength = 128 }
      doc.protect(policy)
    }
    // scanned_image fixture is a single page with no text-drawing content —
    // PDFTextStripper yields an empty string for it. That is functionally
    // equivalent to a true image-only page for this contract.
    scannedImage = newPdf("scanned_image.pdf") { doc -> doc.addPage(PDPage()) }
    malformedHeader = File(fixturesDir, "malformed_header.pdf").also {
      it.writeBytes("This is not a PDF file.\n".toByteArray(Charsets.UTF_8))
    }
  }

  @After
  fun tearDown() {
    if (::fixturesDir.isInitialized) fixturesDir.deleteRecursively()
  }

  private fun newPdf(name: String, build: (PDDocument) -> Unit): File =
    File(fixturesDir, name).also { f ->
      PDDocument().use { doc ->
        build(doc)
        doc.save(f)
      }
    }

  private fun addTextPage(doc: PDDocument, text: String) {
    val page = PDPage()
    doc.addPage(page)
    PDPageContentStream(doc, page).use { cs ->
      cs.setFont(PDType1Font.HELVETICA, 12f)
      cs.beginText()
      cs.newLineAtOffset(50f, 750f)
      cs.showText(text)
      cs.endText()
    }
  }

  private fun newExtractor(
    pageReader: PdfTextExtractor.PageReader? = null,
  ): Pair<PdfTextExtractor, RecordingLogger> {
    val logger = RecordingLogger()
    val extractor = if (pageReader == null) {
      PdfTextExtractor(ctx, logger)
    } else {
      PdfTextExtractor(ctx, logger, pageReader)
    }
    return extractor to logger
  }

  @Test
  fun extractsOnePagePdf_yieldsSinglePageWithText() = runTest {
    val (extractor, logger) = newExtractor()
    val pages = extractor.extract(onePage).toList()
    assertEquals(1, pages.size)
    assertEquals(1, pages[0].page)
    assertTrue("expected non-blank text, got '${pages[0].text}'", pages[0].text.isNotBlank())
    assertTrue("expected no errors, got ${logger.events}", logger.events.isEmpty())
  }

  @Test
  fun extractsFiftyPagePdf_yieldsFiftyPages() = runTest {
    val (extractor, logger) = newExtractor()
    val pages = extractor.extract(fiftyPages).toList()
    assertEquals(50, pages.size)
    pages.forEachIndexed { idx, p ->
      assertEquals(idx + 1, p.page)
      assertTrue("page ${p.page} has empty text", p.text.isNotBlank())
    }
    assertTrue("expected no errors, got ${logger.events}", logger.events.isEmpty())
  }

  @Test
  fun encryptedPdf_yieldsNothingAndLogsError() = runTest {
    val (extractor, logger) = newExtractor()
    val pages = extractor.extract(encrypted).toList()
    assertTrue("expected no pages, got ${pages.size}", pages.isEmpty())
    assertNotNull(
      "expected an 'encrypted' log entry, got ${logger.events}",
      logger.events.firstOrNull { it.first.startsWith("encrypted ") },
    )
  }

  @Test
  fun scannedImagePdf_yieldsEmptyTextForEachPage() = runTest {
    val (extractor, logger) = newExtractor()
    val pages = extractor.extract(scannedImage).toList()
    assertEquals(1, pages.size)
    assertTrue("expected blank text, got '${pages[0].text}'", pages[0].text.isBlank())
    assertTrue("expected no errors, got ${logger.events}", logger.events.isEmpty())
  }

  @Test
  fun malformedPdf_yieldsNothingAndLogsError_doesNotCrash() = runTest {
    val (extractor, logger) = newExtractor()
    val pages = extractor.extract(malformedHeader).toList()
    assertTrue("expected no pages, got ${pages.size}", pages.isEmpty())
    assertTrue("expected at least one logged event", logger.events.isNotEmpty())
    assertTrue(
      "expected a logged Throwable, got ${logger.events}",
      logger.events.any { it.second != null },
    )
  }

  @Test
  fun perPageTimeout_skipsSlowPageAndContinues() = runTest {
    val slowPage = 3
    val timeoutMs = 80L
    val pageReader = PdfTextExtractor.PageReader { _, pageNumber ->
      if (pageNumber == slowPage) {
        kotlinx.coroutines.delay(timeoutMs * 5)
        "should-not-arrive"
      } else {
        "page-$pageNumber-text"
      }
    }
    val (extractor, logger) = newExtractor(pageReader)

    val fivePages = newPdf("five_pages.pdf") { doc -> repeat(5) { doc.addPage(PDPage()) } }

    val pages = extractor.extract(fivePages, perPageTimeoutMs = timeoutMs).toList()
    assertEquals(4, pages.size)
    assertEquals(listOf(1, 2, 4, 5), pages.map { it.page })
    assertNotNull(
      "expected a timeout log for page=$slowPage, got ${logger.events}",
      logger.events.firstOrNull { it.first.startsWith("timeout page=$slowPage ") },
    )
  }

  private class RecordingLogger : PdfParseLogger {
    val events: MutableList<Pair<String, Throwable?>> = mutableListOf()
    override suspend fun log(message: String, cause: Throwable?) {
      events += message to cause
    }
  }
}
