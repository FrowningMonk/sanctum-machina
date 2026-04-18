package app.sanctum.machina

import android.app.Application
import app.sanctum.machina.core.data.DefaultDownloadRepository
import app.sanctum.machina.crash.CrashHandler
import app.sanctum.machina.crash.Killer
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SanctumApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Guard: Application.onCreate runs in every OS process that mounts this <application>.
        // Installing CrashHandler in ":crash" would recurse when CrashReportActivity itself
        // crashes (Decision 4). The handler must be live before the DefaultDownloadRepository
        // assignment below so a ClassLoader failure on that line is still captured.
        if (getProcessName() == packageName) {
            installCrashHandler()
        }
        DefaultDownloadRepository.mainActivityFqn = "app.sanctum.machina.MainActivity"
    }

    private fun installCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(applicationContext, Killer.Default))
    }
}
