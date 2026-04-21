package app.sanctum.machina.di

import android.content.Context
import app.sanctum.machina.data.ChatRepository
import app.sanctum.machina.data.DefaultChatRepository
import app.sanctum.machina.data.SanctumDatabase
import app.sanctum.machina.data.dao.ChatDao
import app.sanctum.machina.data.dao.MessageDao
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * `:app`-layer Hilt bindings for Phase-3 persistence and engine warmup. Creates the single Room
 * [SanctumDatabase] instance, exposes its DAOs, and binds [DefaultChatRepository] to
 * [ChatRepository]. [app.sanctum.machina.engine.WarmupCoordinator] is resolved automatically via
 * its `@Singleton @Inject constructor` — no explicit provider needed, since every collaborator
 * (`ModelRegistry`, `AppSettingsRepository`, `ErrorLog`) is already a [SingletonComponent]
 * binding.
 *
 * Task-6 follow-up: the DB provider here is the spot where `try/catch` around the Room
 * `build()` call will land, wiring `AppCorruptionState` + the rename-and-retry fallback.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

  @Binds
  @Singleton
  abstract fun bindChatRepository(impl: DefaultChatRepository): ChatRepository

  companion object {

    @Provides
    @Singleton
    fun provideSanctumDatabase(@ApplicationContext context: Context): SanctumDatabase =
      SanctumDatabase.create(context)

    @Provides
    @Singleton
    fun provideChatDao(database: SanctumDatabase): ChatDao = database.chatDao()

    @Provides
    @Singleton
    fun provideMessageDao(database: SanctumDatabase): MessageDao = database.messageDao()
  }
}
