package app.sanctum.machina.crash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
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
class CrashStateTest {

    private lateinit var context: Context
    private lateinit var logsDir: File
    private lateinit var crashLog: File
    private lateinit var dismissedFlag: File
    private lateinit var state: CrashState

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        logsDir = File(context.filesDir, "logs")
        crashLog = File(logsDir, "crash.log")
        dismissedFlag = File(logsDir, "crash.log.dismissed")
        logsDir.deleteRecursively()
        state = CrashState(context)
    }

    @After
    fun tearDown() {
        logsDir.deleteRecursively()
    }

    @Test
    fun crashLogExists_noDismissed_flowTrue() {
        logsDir.mkdirs()
        crashLog.writeText("stacktrace")

        state.refresh()

        assertTrue(state.hasUnresolvedCrash.value)
    }

    @Test
    fun bothExist_flowFalse() {
        logsDir.mkdirs()
        crashLog.writeText("stacktrace")
        dismissedFlag.createNewFile()

        state.refresh()

        assertFalse(state.hasUnresolvedCrash.value)
    }

    @Test
    fun neitherExists_flowFalse() {
        logsDir.mkdirs()

        state.refresh()

        assertFalse(state.hasUnresolvedCrash.value)
    }

    @Test
    fun markDismissed_flipsToFalseAndCreatesFlag() {
        logsDir.mkdirs()
        crashLog.writeText("stacktrace")
        state.refresh()
        assertTrue("precondition: flow must be true", state.hasUnresolvedCrash.value)

        state.markDismissed()

        assertTrue("dismissed flag must exist after markDismissed()", dismissedFlag.exists())
        assertFalse("flow must flip to false", state.hasUnresolvedCrash.value)
    }

    @Test
    fun clear_deletesBothFiles() {
        logsDir.mkdirs()
        crashLog.writeText("stacktrace")
        dismissedFlag.createNewFile()

        state.clear()

        assertFalse("crash.log must be deleted", crashLog.exists())
        assertFalse("dismissed flag must be deleted", dismissedFlag.exists())
        assertFalse("flow must be false", state.hasUnresolvedCrash.value)
    }

    @Test
    fun refresh_rereadsAfterExternalChange() {
        logsDir.mkdirs()
        crashLog.writeText("stacktrace")
        dismissedFlag.createNewFile()
        state.refresh()
        assertFalse("precondition: flow must be false", state.hasUnresolvedCrash.value)

        assertTrue("external: delete .dismissed", dismissedFlag.delete())
        state.refresh()

        assertTrue("flow must reflect re-read disk state", state.hasUnresolvedCrash.value)
    }

    @Test
    fun freshInstall_noLogsDir_flowFalseNoException() {
        assertFalse("precondition: logs dir must not exist", logsDir.exists())

        state.refresh()

        assertFalse(state.hasUnresolvedCrash.value)
    }

    @Test
    fun dismissedThenNewCrash_reappears() {
        logsDir.mkdirs()
        dismissedFlag.createNewFile()
        assertTrue("seed: dismissed flag must exist", dismissedFlag.exists())

        val killer = CountingKiller()
        val handler = CrashHandler(context, killer)
        handler.uncaughtException(Thread.currentThread(), RuntimeException("second"))

        state.refresh()

        assertEquals("killer invoked once", 1, killer.callCount)
        assertFalse("dismissed flag must be removed by new crash", dismissedFlag.exists())
        assertTrue("crash.log must be written by new crash", crashLog.exists())
        assertTrue("flow must flip back to true after new crash", state.hasUnresolvedCrash.value)
    }

    private class CountingKiller : Killer {
        var callCount: Int = 0
        override fun kill(pid: Int) {
            callCount++
        }
    }
}
