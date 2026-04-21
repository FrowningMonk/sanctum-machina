package app.sanctum.machina.di

import android.content.Context
import androidx.room.Room
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.data.ChatRepository
import app.sanctum.machina.data.DefaultChatRepository
import app.sanctum.machina.data.SanctumDatabase
import app.sanctum.machina.data.dao.ChatDao
import app.sanctum.machina.data.dao.MessageDao
import app.sanctum.machina.engine.AppCorruptionState
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

private const val CORRUPT_TIMESTAMP_PATTERN = "yyyyMMdd-HHmmss"
private const val LOG_COMPONENT = "history-read"

/**
 * `:app`-layer Hilt bindings for Phase-3 persistence and engine warmup. Creates the single Room
 * [SanctumDatabase] instance, exposes its DAOs, and binds [DefaultChatRepository] to
 * [ChatRepository]. [app.sanctum.machina.engine.WarmupCoordinator] and
 * [app.sanctum.machina.engine.StartupHousekeeper] are resolved automatically via their
 * `@Singleton @Inject constructor` — no explicit provider needed, since every collaborator
 * (`ModelRegistry`, `AppSettingsRepository`, `ErrorLog`, `ChatRepository`) is already a
 * [SingletonComponent] binding.
 *
 * Task-6: [provideSanctumDatabase] wraps `Room.databaseBuilder.build()` and the forced
 * SQLite open in a `try/catch(Exception)`. On failure the corrupt file is renamed to
 * `sanctum.db.corrupt_{yyyyMMdd-HHmmss}`, `AppCorruptionState.corruptionOccurred` is set, and
 * `ErrorLog.e("history-read", …)` is written via [runBlocking] (the provider is synchronous).
 * Decision 6 / AC-R6.
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
    @JvmStatic
    fun provideSanctumDatabase(
      @ApplicationContext context: Context,
      appCorruptionState: AppCorruptionState,
      errorLog: ErrorLog,
    ): SanctumDatabase {
      val dbFile = context.getDatabasePath(SanctumDatabase.DATABASE_NAME)
      val firstAttempt = buildSanctumDatabase(context)
      try {
        firstAttempt.openHelper.writableDatabase
        return firstAttempt
      } catch (cause: Exception) {
        runCatching { firstAttempt.close() }

        val parent = dbFile.parentFile
        val corruptName = "${SanctumDatabase.DATABASE_NAME}.corrupt_${corruptTimestamp()}"
        if (parent != null && dbFile.exists()) {
          val target = File(parent, corruptName)
          dbFile.renameTo(target)
          // Drop SQLite sidecar files so the fresh build cannot resurrect the corrupt state
          // via a surviving `-journal` / `-wal` / `-shm` alongside the renamed main file.
          File(parent, "${SanctumDatabase.DATABASE_NAME}-journal").delete()
          File(parent, "${SanctumDatabase.DATABASE_NAME}-wal").delete()
          File(parent, "${SanctumDatabase.DATABASE_NAME}-shm").delete()
        }
        appCorruptionState.corruptionOccurred = true
        runBlocking {
          errorLog.e(LOG_COMPONENT, "db open failed — renamed to $corruptName", cause)
        }
        // Fresh build must not be wrapped: if it throws, `CrashHandler` (already installed
        // by this point) surfaces a deterministic crash instead of an infinite recovery loop.
        return buildSanctumDatabase(context)
      }
    }

    @Provides
    @Singleton
    fun provideChatDao(database: SanctumDatabase): ChatDao = database.chatDao()

    @Provides
    @Singleton
    fun provideMessageDao(database: SanctumDatabase): MessageDao = database.messageDao()

    private fun buildSanctumDatabase(context: Context): SanctumDatabase =
      Room.databaseBuilder(
        context.applicationContext,
        SanctumDatabase::class.java,
        SanctumDatabase.DATABASE_NAME,
      )
        .addCallback(SanctumDatabase.ForeignKeysOnOpenCallback)
        .build()

    private fun corruptTimestamp(): String =
      LocalDateTime.now().format(
        DateTimeFormatter.ofPattern(CORRUPT_TIMESTAMP_PATTERN, Locale.ROOT),
      )
  }
}
