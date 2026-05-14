package app.sanctum.machina.di

import android.content.Context
import androidx.room.Room
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.data.ChatRepository
import app.sanctum.machina.data.DefaultChatRepository
import app.sanctum.machina.data.MIGRATION_1_2
import app.sanctum.machina.data.SanctumDatabase
import app.sanctum.machina.data.dao.ChatDao
import app.sanctum.machina.data.dao.MessageDao
import app.sanctum.machina.data.dao.ProjectDao
import app.sanctum.machina.data.dao.ProjectEmbeddingDao
import app.sanctum.machina.data.dao.ProjectFileDao
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
      var firstAttempt: SanctumDatabase? = null
      try {
        firstAttempt = buildSanctumDatabase(context)
        firstAttempt.openHelper.writableDatabase
        return firstAttempt
      } catch (cause: Exception) {
        runCatching { firstAttempt?.close() }

        val parent = dbFile.parentFile
        val corruptName = "${SanctumDatabase.DATABASE_NAME}.corrupt_${corruptTimestamp()}"
        val renameOutcome: String = when {
          parent == null || !dbFile.exists() -> "no-op (missing parent or file)"
          dbFile.renameTo(File(parent, corruptName)) -> {
            // Drop SQLite sidecar files so the fresh build cannot resurrect the corrupt state
            // via a surviving `-journal` / `-wal` / `-shm` alongside the renamed main file.
            File(parent, "${SanctumDatabase.DATABASE_NAME}-journal").delete()
            File(parent, "${SanctumDatabase.DATABASE_NAME}-wal").delete()
            File(parent, "${SanctumDatabase.DATABASE_NAME}-shm").delete()
            "renamed to $corruptName"
          }
          else -> {
            // Rename refused (cross-device, locked file, etc.) — last-resort purge so the
            // fresh build does not try to open on top of the still-corrupt main file.
            dbFile.delete()
            File(parent, "${SanctumDatabase.DATABASE_NAME}-journal").delete()
            File(parent, "${SanctumDatabase.DATABASE_NAME}-wal").delete()
            File(parent, "${SanctumDatabase.DATABASE_NAME}-shm").delete()
            "rename refused — corrupt file deleted"
          }
        }
        appCorruptionState.corruptionOccurred = true
        runBlocking {
          errorLog.e(LOG_COMPONENT, "db open failed — $renameOutcome", cause)
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

    @Provides
    @Singleton
    fun provideProjectDao(database: SanctumDatabase): ProjectDao = database.projectDao()

    @Provides
    @Singleton
    fun provideProjectFileDao(database: SanctumDatabase): ProjectFileDao =
      database.projectFileDao()

    @Provides
    @Singleton
    fun provideProjectEmbeddingDao(database: SanctumDatabase): ProjectEmbeddingDao =
      database.projectEmbeddingDao()

    // `MIGRATION_1_2` is registered on BOTH the first-attempt builder and the
    // post-corruption rebuild path (Decision 13). The corruption catch branch
    // renames the v1 file and creates a fresh DB — Room still installs the v2
    // schema directly from the @Database annotation in that path, so the
    // migration list is irrelevant there, but registering it keeps both
    // builders identical and avoids subtle divergence if v3 lands later.
    private fun buildSanctumDatabase(context: Context): SanctumDatabase =
      Room.databaseBuilder(
        context.applicationContext,
        SanctumDatabase::class.java,
        SanctumDatabase.DATABASE_NAME,
      )
        .addMigrations(MIGRATION_1_2)
        .addCallback(SanctumDatabase.ForeignKeysOnOpenCallback)
        .build()

    private fun corruptTimestamp(): String =
      LocalDateTime.now().format(
        DateTimeFormatter.ofPattern(CORRUPT_TIMESTAMP_PATTERN, Locale.ROOT),
      )
  }
}
