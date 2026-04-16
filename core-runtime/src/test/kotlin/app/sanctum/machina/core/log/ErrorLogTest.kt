package app.sanctum.machina.core.log

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
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

  @Test
  fun knownComponents_accepted() = runBlocking {
    val allowed = listOf(
      "download",
      "inference-init",
      "inference",
      "inference-cleanup",
      "settings-io",
      "camera",
      "audio",
      "attachment-decode",
    )
    for (component in allowed) {
      errorLog.e(component, "ok")
    }
    assertTrue("log file must exist", logFile.exists())
    assertEquals(allowed.size, logFile.readLines().size)
  }

  @Test(expected = IllegalArgumentException::class)
  fun unknownComponent_throwsIAE(): Unit = runBlocking {
    errorLog.e("not-a-real-component", "should fail")
  }

  @Test
  fun unknownComponent_doesNotWriteFile(): Unit = runBlocking {
    try {
      errorLog.e("mystery", "fails before open")
    } catch (_: IllegalArgumentException) {
      // expected
    }
    assertFalse("whitelist rejection must not create log file", logFile.exists())
  }

  @Test
  fun causeChainBounding_truncatesAt200() = runBlocking {
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
  fun causeChainBounding_shortMessage_notPadded() = runBlocking {
    val cause = IllegalStateException("short")
    errorLog.e("inference", "desc", cause)
    val content = logFile.readText().trimEnd('\n')
    assertEquals("ERROR [inference] desc :: IllegalStateException: short", content)
  }

  @Test
  fun nullCauseMessage_emitsEmptyTail() = runBlocking {
    val cause = RuntimeException() // null message
    errorLog.e("inference", "desc", cause)
    val content = logFile.readText().trimEnd('\n')
    assertEquals("ERROR [inference] desc :: RuntimeException: ", content)
  }

  @Test
  fun descriptionSanitization_controlWhitespaceReplaced() = runBlocking {
    errorLog.e("inference", "line1\nline2\tcol2\rtrail")
    val content = logFile.readText().trimEnd('\n')
    assertEquals("ERROR [inference] line1 line2 col2 trail", content)
  }
}
