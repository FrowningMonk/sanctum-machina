package app.sanctum.machina

import android.app.Application
import app.sanctum.machina.core.data.DefaultDownloadRepository
import app.sanctum.machina.crash.CrashHandler
import app.sanctum.machina.crash.Killer
import app.sanctum.machina.data.ChatRepository
import app.sanctum.machina.data.SettingsMigrationHelper
import app.sanctum.machina.engine.StartupHousekeeper
import app.sanctum.machina.engine.WarmupCoordinator
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class SanctumApplication : Application() {

    @Inject lateinit var warmupCoordinator: WarmupCoordinator
    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var settingsMigrationHelper: SettingsMigrationHelper
    @Inject lateinit var startupHousekeeper: StartupHousekeeper

    override fun onCreate() {
        super.onCreate()
        // Guard: Application.onCreate runs in every OS process that mounts this <application>.
        // Installing CrashHandler in ":crash" would recurse when CrashReportActivity itself
        // crashes (Decision 4). The handler must be live before the DefaultDownloadRepository
        // assignment below so a ClassLoader failure on that line is still captured.
        if (getProcessName() == packageName) {
            installCrashHandler()

            // `warmupDefault()` is non-suspend — it schedules its own coroutine on
            // WarmupCoordinator's internal scope. Calling it directly avoids a redundant
            // outer launch whose body would be a synchronous function call.
            warmupCoordinator.warmupDefault()

            val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            // Separate Jobs so a migration failure cannot short-circuit on-disk housekeeping,
            // and vice versa. SupervisorJob enforces independence.
            appScope.launch { settingsMigrationHelper.migrateIfNeeded() }
            appScope.launch(Dispatchers.IO) { startupHousekeeper.run() }
        }
        DefaultDownloadRepository.mainActivityFqn = "app.sanctum.machina.MainActivity"
    }

    private fun installCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(applicationContext, Killer.Default))
    }
}
