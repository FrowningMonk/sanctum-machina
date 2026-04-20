package app.sanctum.machina.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.sanctum.machina.data.dao.ChatDao
import app.sanctum.machina.data.dao.MessageDao
import app.sanctum.machina.data.model.ChatEntity
import app.sanctum.machina.data.model.MessageEntity

@Database(
    entities = [
        ChatEntity::class,
        MessageEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SanctumDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao

    companion object {
        const val DATABASE_NAME: String = "sanctum.db"

        fun create(context: Context): SanctumDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                SanctumDatabase::class.java,
                DATABASE_NAME,
            )
                .addCallback(ForeignKeysOnOpenCallback)
                .build()

        internal val ForeignKeysOnOpenCallback: Callback = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                db.execSQL("PRAGMA foreign_keys = ON")
            }
        }
    }
}
