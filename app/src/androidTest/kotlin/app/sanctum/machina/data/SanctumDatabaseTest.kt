package app.sanctum.machina.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.sanctum.machina.data.model.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SanctumDatabaseTest {

    private lateinit var db: SanctumDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SanctumDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(SanctumDatabase.ForeignKeysOnOpenCallback)
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun foreignKeysPragmaIsOn() {
        db.openHelper.readableDatabase.query("PRAGMA foreign_keys").use { cursor ->
            assertTrue("PRAGMA foreign_keys cursor empty", cursor.moveToFirst())
            assertEquals(
                "PRAGMA foreign_keys must be 1 (ON) — ForeignKeysOnOpenCallback should set it",
                1,
                cursor.getInt(0)
            )
        }
    }

    @Test
    fun foreignKeyEnforcementEnabled() = runBlocking {
        var threw = false
        try {
            db.messageDao().insert(
                MessageEntity(
                    chatId = 9_999L,
                    role = "user",
                    text = "orphan",
                    createdAt = 1L
                )
            )
        } catch (e: SQLiteConstraintException) {
            threw = true
        }
        assertTrue(
            "Inserting a message with a non-existent chatId must fail when PRAGMA foreign_keys = ON",
            threw
        )
    }

    @Test
    fun schemaContainsChatsAndMessages() {
        val names = readTableNames()
        assertTrue("chats table present", "chats" in names)
        assertTrue("messages table present", "messages" in names)
    }

    @Test
    fun noProjectFilesTable() {
        assertTrue("project_files absent in v1", "project_files" !in readTableNames())
    }

    @Test
    fun noProjectsTable() {
        assertTrue("projects absent in v1", "projects" !in readTableNames())
    }

    private fun readTableNames(): Set<String> {
        val names = mutableSetOf<String>()
        db.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type='table'")
            .use { cursor ->
                while (cursor.moveToNext()) names.add(cursor.getString(0))
            }
        return names
    }
}
