package app.sanctum.machina

import androidx.test.core.app.ApplicationProvider
import app.sanctum.machina.crash.CrashHandler
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards Decision 4: `CrashHandler` is installed only when `getProcessName() == packageName`.
 *
 * [SanctumApplication] is `final` and annotated `@HiltAndroidApp`; tech-spec explicitly forbids
 * opening it for testability ("класс остаётся как есть"). We stub the process name via
 * reflection on `ActivityThread.mBoundApplication.processName` — the same backing field that
 * [android.app.Application.getProcessName] ultimately reads on API 28+. Robolectric 4.12
 * emulates the real `ActivityThread`, so the reflection path is stable across sdk=[33] runs.
 *
 * `@Before` snapshots and `@After` restores [Thread.getDefaultUncaughtExceptionHandler] because
 * Robolectric shares JVM globals across test classes; leaking a [CrashHandler] would poison
 * later suites. Mirrors the symmetric-cleanup pattern used by `ErrorLogTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SanctumApplicationTest {

    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var previousProcessName: String? = null

    @Before
    fun setUp() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        previousProcessName = readBoundProcessName()
        // Deterministic non-CrashHandler sentinel so guard-path assertions are unambiguous
        // regardless of what an earlier Robolectric test leaked into the JVM-global handler.
        Thread.setDefaultUncaughtExceptionHandler(SentinelHandler)
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        previousProcessName?.let(::writeBoundProcessName)
    }

    @Test
    fun installsCrashHandlerInMainProcess() {
        val app = ApplicationProvider.getApplicationContext<SanctumApplication>()
        writeBoundProcessName(app.packageName)

        app.onCreate()

        val installed = Thread.getDefaultUncaughtExceptionHandler()
        assertTrue(
            "main process must install CrashHandler, got ${installed?.javaClass?.name}",
            installed is CrashHandler,
        )
    }

    @Test
    fun skipsInstallInCrashProcess() {
        val app = ApplicationProvider.getApplicationContext<SanctumApplication>()
        writeBoundProcessName("${app.packageName}:crash")

        app.onCreate()

        val after = Thread.getDefaultUncaughtExceptionHandler()
        assertFalse(
            ":crash process must NOT install CrashHandler, got ${after?.javaClass?.name}",
            after is CrashHandler,
        )
        assertSame(
            ":crash process must leave the pre-existing handler untouched",
            SentinelHandler,
            after,
        )
    }

    private object SentinelHandler : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) = Unit
    }

    private fun readBoundProcessName(): String? {
        val boundApp = boundAppBindData() ?: return null
        val field = boundApp.javaClass.getDeclaredField("processName")
        field.isAccessible = true
        return field.get(boundApp) as? String
    }

    private fun writeBoundProcessName(name: String) {
        val boundApp = boundAppBindData()
            ?: error("ActivityThread.mBoundApplication is null under Robolectric — test harness broken")
        val field = boundApp.javaClass.getDeclaredField("processName")
        field.isAccessible = true
        field.set(boundApp, name)
    }

    private fun boundAppBindData(): Any? {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val currentThread = activityThreadClass
            .getMethod("currentActivityThread")
            .invoke(null)
            ?: return null
        val field = activityThreadClass.getDeclaredField("mBoundApplication")
        field.isAccessible = true
        return field.get(currentThread)
    }
}
