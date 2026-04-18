package app.sanctum.machina.core.settings.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import app.sanctum.machina.core.settings.AppSettingsRepository
import app.sanctum.machina.core.settings.AppSettingsSerializer
import app.sanctum.machina.core.settings.DefaultAppSettingsRepository
import app.sanctum.machina.core.settings.proto.AppSettings
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

private const val DATASTORE_FILE = "datastore/app_settings.pb"

@Module
@InstallIn(SingletonComponent::class)
abstract class CoreSettingsModule {

  @Binds
  @Singleton
  abstract fun bindAppSettingsRepository(
    impl: DefaultAppSettingsRepository,
  ): AppSettingsRepository

  companion object {
    @Provides
    @Singleton
    fun provideAppSettingsDataStore(
      @ApplicationContext context: Context,
    ): DataStore<AppSettings> =
      DataStoreFactory.create(
        serializer = AppSettingsSerializer,
        produceFile = {
          File(context.filesDir, DATASTORE_FILE).also { it.parentFile?.mkdirs() }
        },
      )
  }
}
