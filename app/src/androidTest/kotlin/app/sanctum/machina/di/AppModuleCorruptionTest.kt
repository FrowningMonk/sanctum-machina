package app.sanctum.machina.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.data.SanctumDatabase
import app.sanctum.machina.engine.AppCorruptionState
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented coverage for AC-R6: `AppModule.provideSanctumDatabase` must recover from a
 * corrupt on-disk Room database by renaming the bad file, opening a fresh empty DB, and
 * flipping the in-memory `AppCorruptionState` flag. We run on-device because intercepting
 * `Room.databaseBuilder.build()` requires the real Android SQLite path (AGSL's `NativeDB`
 * validates magic bytes before returning from `Room.databaseBuilder.build()`), and the
 * project forbids static mocking libraries (`patterns.md` — hand-rolled fakes only).
 *
 * Each test deletes `sanctum.db*` in `setUp` / `tearDown` so reruns start from a known
 * filesystem state on whichever device or emulator the suite lands on.
 */
@RunWith(AndroidJUnit4::class)
class AppModuleCorruptionTest {

    private lateinit var context: Context
    private lateinit var dbDir: File
    private lateinit var dbFile: File
    private lateinit var errorLog: ErrorLog
    private lateinit var errorLogFile: File
    private var openedDb: SanctumDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath(SanctumDatabase.DATABASE_NAME)
        dbDir = dbFile.parentFile!!
        errorLog = ErrorLog(context)
        errorLogFile = File(context.filesDir, "logs/errors.log")
        cleanArtifacts()
    }

    @After
    fun tearDown() {
        openedDb?.close()
        openedDb = null
        cleanArtifacts()
    }

    @Test
    fun testCorruptDbRenamedAndFreshDbCreated() {
        dbDir.mkdirs()
        dbFile.writeBytes(ByteArray(32) { 0x5A })
        val corruptionState = AppCorruptionState()

        val db = AppModule.provideSanctumDatabase(context, corruptionState, errorLog)
        openedDb = db

        assertTrue("fresh Room DB must be open after corruption recovery", db.isOpen)
        val renamedFile = dbDir.listFiles()?.firstOrNull {
            it.name.startsWith("${SanctumDatabase.DATABASE_NAME}.corrupt_")
        }
        assertNotNull(
            "expected sanctum.db.corrupt_* in ${dbDir.absolutePath}, got ${dbDir.list()?.toList()}",
            renamedFile,
        )
        assertTrue(
            "corruption flag must flip so HomeScreen can show the banner",
            corruptionState.corruptionOccurred,
        )
        awaitHistoryReadEntry()
        val logged = errorLogFile.readText()
        assertTrue(
            "expected history-read entry in log; got: $logged",
            logged.contains("ERROR [history-read]"),
        )
    }

    @Test
    fun testNormalDbOpenNoCorruptionFlag() {
        val corruptionState = AppCorruptionState()

        val db = AppModule.provideSanctumDatabase(context, corruptionState, errorLog)
        openedDb = db

        assertTrue("healthy Room DB must open normally", db.isOpen)
        assertFalse(
            "no corruption flag must be raised on a clean first open",
            corruptionState.corruptionOccurred,
        )
        val renamed = dbDir.listFiles()?.firstOrNull {
            it.name.startsWith("${SanctumDatabase.DATABASE_NAME}.corrupt_")
        }
        assertTrue(
            "no rename artefact should exist on clean open; found ${renamed?.name}",
            renamed == null,
        )
    }

    private fun awaitHistoryReadEntry() {
        val deadlineMs = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadlineMs) {
            if (errorLogFile.exists() &&
                errorLogFile.length() > 0 &&
                errorLogFile.readText().contains("ERROR [history-read]")
            ) {
                return
            }
            Thread.sleep(20)
        }
    }

    private fun cleanArtifacts() {
        if (dbDir.exists()) {
            dbDir.listFiles()?.forEach { file ->
                if (file.name.startsWith(SanctumDatabase.DATABASE_NAME)) {
                    file.delete()
                }
            }
        }
        errorLogFile.parentFile?.deleteRecursively()
    }
}
