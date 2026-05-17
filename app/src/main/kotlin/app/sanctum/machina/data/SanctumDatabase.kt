package app.sanctum.machina.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.sanctum.machina.data.dao.ChatDao
import app.sanctum.machina.data.dao.MessageDao
import app.sanctum.machina.data.dao.ProjectDao
import app.sanctum.machina.data.dao.ProjectEmbeddingDao
import app.sanctum.machina.data.dao.ProjectFileDao
import app.sanctum.machina.data.model.ChatEntity
import app.sanctum.machina.data.model.MessageEntity
import app.sanctum.machina.data.model.ProjectEmbeddingEntity
import app.sanctum.machina.data.model.ProjectEntity
import app.sanctum.machina.data.model.ProjectFileEntity

@Database(
    entities = [
        ChatEntity::class,
        MessageEntity::class,
        ProjectEntity::class,
        ProjectFileEntity::class,
        ProjectEmbeddingEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class SanctumDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun projectDao(): ProjectDao
    abstract fun projectFileDao(): ProjectFileDao
    abstract fun projectEmbeddingDao(): ProjectEmbeddingDao

    companion object {
        const val DATABASE_NAME: String = "sanctum.db"

        internal val ForeignKeysOnOpenCallback: Callback = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                db.execSQL("PRAGMA foreign_keys = ON")
            }
        }
    }
}
