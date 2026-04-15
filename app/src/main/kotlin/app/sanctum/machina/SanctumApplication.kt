package app.sanctum.machina

import android.app.Application
import app.sanctum.machina.core.data.DefaultDownloadRepository
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SanctumApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DefaultDownloadRepository.mainActivityFqn = "app.sanctum.machina.MainActivity"
    }
}
