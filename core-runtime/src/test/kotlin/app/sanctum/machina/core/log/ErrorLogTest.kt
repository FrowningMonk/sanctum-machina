package app.sanctum.machina.core.log

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ErrorLogTest {

  private lateinit var context: Context
  private lateinit var errorLog: ErrorLog
  private lateinit var logFile: File

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    errorLog = ErrorLog(context)
    logFile = File(context.filesDir, "logs/errors.log")
    logFile.parentFile?.deleteRecursively()
  }

  @After
  fun tearDown() {
    // Symmetric cleanup — Robolectric filesDir persists across test classes within
    // a single run; leaving state behind risks polluting other Robolectric suites.
    logFile.parentFile?.deleteRecursively()
  }

  @Test
  fun knownComponents_accepted() = runTest {
    val allowed = listOf(
      // Phase 1
      "download",
      "inference-init",
      "inference",
      "inference-cleanup",
      // Phase 2 (D27)
      "settings-io",
      "camera",
      "audio",
      "attachment-decode",
      // Phase 3
      "model",
      "engine-warmup",
      "history-read",
      "history-write",
      "attachment-save",
      "attachment-read",
      // Phase 3.6
      "inference-reset",
      // Phase 4 Task 4 (forward-ported from Task 6)
      "embed-init",
    )
    assertEquals("expected 16 allowed components", 16, allowed.size)
    for (component in allowed) {
      errorLog.e(component, "ok")
    }
    assertTrue("log file must exist", logFile.exists())
    assertEquals(allowed.size, logFile.readLines().size)
  }

  @Test
  fun whitelistCount_is16() {
    assertEquals(16, ALLOWED_COMPONENTS.size)
    assertTrue("inference-reset must be whitelisted", "inference-reset" in ALLOWED_COMPONENTS)
    assertTrue("embed-init must be whitelisted", "embed-init" in ALLOWED_COMPONENTS)
  }

  @Test
  fun inferenceResetComponent_acceptedByAllLevels() = runTest {
    errorLog.e("inference-reset", "x")
    errorLog.i("inference-reset", "x")
    errorLog.w("inference-reset", "x")

    assertTrue("log file must exist", logFile.exists())
    val lines = logFile.readLines()
    assertEquals(3, lines.size)
    assertTrue("first line must start with ERROR prefix, got: ${lines[0]}", lines[0].startsWith("ERROR [inference-reset] "))
    assertTrue("second line must start with INFO prefix, got: ${lines[1]}", lines[1].startsWith("INFO [inference-reset] "))
    assertTrue("third line must start with WARN prefix, got: ${lines[2]}", lines[2].startsWith("WARN [inference-reset] "))
  }

  @Test
  fun unknownComponent_stillRejected_negative(): Unit = runTest {
    var eThrew = false
    try {
      errorLog.e("inference-reset-x", "x")
    } catch (_: IllegalArgumentException) {
      eThrew = true
    }
    assertTrue("e() must reject unknown component", eThrew)

    var iThrew = false
    try {
      errorLog.i("inference-reset-x", "x")
    } catch (_: IllegalArgumentException) {
      iThrew = true
    }
    assertTrue("i() must reject unknown component", iThrew)

    var wThrew = false
    try {
      errorLog.w("inference-reset-x", "x")
    } catch (_: IllegalArgumentException) {
      wThrew = true
    }
    assertTrue("w() must reject unknown component", wThrew)

    assertFalse("whitelist rejection must not create log file", logFile.exists())
  }

  @Test
  fun iAndW_lengthBoundingMatchesE() = runTest {
    val longDesc = "a".repeat(600)
    val longCauseMsg = "x".repeat(300)

    errorLog.i("inference-reset", longDesc, RuntimeException(longCauseMsg))
    errorLog.w("inference-reset", longDesc, RuntimeException(longCauseMsg))

    val lines = logFile.readLines()
    assertEquals(2, lines.size)

    val infoPrefix = "INFO [inference-reset] "
    val warnPrefix = "WARN [inference-reset] "
    assertTrue("INFO line must start with prefix, got: ${lines[0]}", lines[0].startsWith(infoPrefix))
    assertTrue("WARN line must start with prefix, got: ${lines[1]}", lines[1].startsWith(warnPrefix))

    val infoDesc = lines[0].substringAfter(infoPrefix).substringBefore(" :: ")
    val infoCause = lines[0].substringAfter(":: RuntimeException: ")
    assertEquals(500, infoDesc.length)
    assertTrue("INFO truncated description must be all 'a'", infoDesc.all { it == 'a' })
    assertEquals(200, infoCause.length)
    assertTrue("INFO truncated cause must be all 'x'", infoCause.all { it == 'x' })

    val warnDesc = lines[1].substringAfter(warnPrefix).substringBefore(" :: ")
    val warnCause = lines[1].substringAfter(":: RuntimeException: ")
    assertEquals(500, warnDesc.length)
    assertTrue("WARN truncated description must be all 'a'", warnDesc.all { it == 'a' })
    assertEquals(200, warnCause.length)
    assertTrue("WARN truncated cause must be all 'x'", warnCause.all { it == 'x' })
  }

  @Test
  fun newComponents_accepted() = runTest {
    val phase3 = listOf(
      "model",
      "engine-warmup",
      "history-read",
      "history-write",
      "attachment-save",
      "attachment-read",
    )
    for (component in phase3) {
      errorLog.e(component, "ok")
    }
    assertTrue("log file must exist", logFile.exists())
    assertEquals(phase3.size, logFile.readLines().size)
  }

  @Test(expected = IllegalArgumentException::class)
  fun unknownComponent_throwsIAE(): Unit = runTest {
    errorLog.e("not-a-real-component", "should fail")
  }

  @Test
  fun unknownComponent_doesNotWriteFile(): Unit = runTest {
    try {
      errorLog.e("mystery", "fails before open")
    } catch (_: IllegalArgumentException) {
      // expected
    }
    assertFalse("whitelist rejection must not create log file", logFile.exists())
  }

  @Test
  fun causeChainBounding_truncatesAt200() = runTest {
    val longMessage = "x".repeat(500)
    val cause = RuntimeException(longMessage)
    errorLog.e("inference", "desc", cause)

    val content = logFile.readText()
    val linePrefix = "ERROR [inference] desc :: RuntimeException: "
    assertTrue("line must start with expected prefix, got:\n$content", content.startsWith(linePrefix))

    val causePart = content.substringAfter(linePrefix).trimEnd('\n')
    assertEquals("cause message must be truncated to 200 chars", 200, causePart.length)
    assertTrue("truncated cause must be all 'x'", causePart.all { it == 'x' })
  }

  @Test
  fun causeChainBounding_shortMessage_notPadded() = runTest {
    val cause = IllegalStateException("short")
    errorLog.e("inference", "desc", cause)
    val content = logFile.readText().trimEnd('\n')
    assertEquals("ERROR [inference] desc :: IllegalStateException: short", content)
  }

  @Test
  fun nullCauseMessage_emitsEmptyTail() = runTest {
    val cause = RuntimeException() // null message
    errorLog.e("inference", "desc", cause)
    val content = logFile.readText().trimEnd('\n')
    assertEquals("ERROR [inference] desc :: RuntimeException: ", content)
  }

  @Test
  fun descriptionSanitization_controlWhitespaceReplaced() = runTest {
    errorLog.e("inference", "line1\nline2\tcol2\rtrail")
    val content = logFile.readText().trimEnd('\n')
    assertEquals("ERROR [inference] line1 line2 col2 trail", content)
  }

  @Test
  fun descriptionTruncation_cappedAt500() = runTest {
    errorLog.e("inference", "a".repeat(1000))
    val desc = logFile.readText().trimEnd('\n').substringAfter("ERROR [inference] ")
    assertEquals(500, desc.length)
    assertTrue("truncated description must be all 'a'", desc.all { it == 'a' })
  }
}
