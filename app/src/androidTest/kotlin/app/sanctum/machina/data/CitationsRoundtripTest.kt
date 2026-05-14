package app.sanctum.machina.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.sanctum.machina.data.model.ChatEntity
import app.sanctum.machina.data.model.MessageEntity
import app.sanctum.machina.data.model.ProjectEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Storage-layer roundtrip for `messages.citations`. Asserts that the JSON string written
 * via `MessageDao.updateCitations` (and via direct insert) survives an in-memory Room
 * reopen bit-identically. Encoding/decoding (Gson + the `Citation` data class) belongs
 * to Task 6's domain mapper — this test pins the storage contract only.
 */
@RunWith(AndroidJUnit4::class)
class CitationsRoundtripTest {

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
    fun citationsJsonRoundtrip() = runBlocking {
        val projectDao = db.projectDao()
        val chatDao = db.chatDao()
        val messageDao = db.messageDao()

        val projectId = projectDao.insert(ProjectEntity(name = "p", createdAt = 1L))
        val chatId = chatDao.insert(
            ChatEntity(
                projectId = projectId,
                modelId = "m",
                createdAt = 1L,
                lastMessageAt = 1L,
            )
        )

        val json =
            "[{\"fileId\":1,\"fileName\":\"a.pdf\",\"page\":3," +
                "\"chunkText\":\"hello\",\"stale\":false}]"

        val messageId = messageDao.insert(
            MessageEntity(
                chatId = chatId,
                role = "assistant",
                text = "answer",
                createdAt = 2L,
                citations = json,
            )
        )

        // Insert path: read directly.
        val afterInsert = messageDao.getByChatId(chatId).single { it.id == messageId }
        assertEquals(json, afterInsert.citations)

        // Update path: overwrite with a different JSON, read back.
        val updated =
            "[{\"fileId\":2,\"fileName\":\"b.pdf\",\"page\":null," +
                "\"chunkText\":\"again\",\"stale\":true}]"
        messageDao.updateCitations(messageId, updated)
        val afterUpdate = messageDao.getByChatId(chatId).single { it.id == messageId }
        assertEquals(updated, afterUpdate.citations)

        // Null path: clear and re-read.
        messageDao.updateCitations(messageId, null)
        val afterNull = messageDao.getByChatId(chatId).single { it.id == messageId }
        assertNull(afterNull.citations)
    }
}
