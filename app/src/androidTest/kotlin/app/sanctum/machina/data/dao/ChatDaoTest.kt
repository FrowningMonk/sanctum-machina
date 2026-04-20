package app.sanctum.machina.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.sanctum.machina.data.SanctumDatabase
import app.sanctum.machina.data.model.ChatEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatDaoTest {

    private lateinit var db: SanctumDatabase
    private lateinit var dao: ChatDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SanctumDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.chatDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndGetById() = runBlocking {
        val now = 1_700_000_000_000L
        val id = dao.insert(
            ChatEntity(
                modelId = "litert-community/gemma-4-E4B-it-litert-lm",
                title = "First chat",
                createdAt = now,
                lastMessageAt = now
            )
        )

        val chat = dao.getById(id)

        assertNotNull(chat)
        assertEquals("litert-community/gemma-4-E4B-it-litert-lm", chat!!.modelId)
        assertEquals("First chat", chat.title)
        assertEquals(now, chat.createdAt)
        assertEquals(now, chat.lastMessageAt)
        assertEquals(0, chat.isManuallyTitled)
        assertNull(chat.projectId)
    }

    @Test
    fun updateTitleAndManualFlag() = runBlocking {
        val id = dao.insert(
            ChatEntity(modelId = "m", createdAt = 1L, lastMessageAt = 1L)
        )
        val loaded = dao.getById(id)!!

        dao.update(loaded.copy(title = "renamed by user", isManuallyTitled = 1))

        val after = dao.getById(id)!!
        assertEquals("renamed by user", after.title)
        assertEquals(1, after.isManuallyTitled)
    }

    @Test
    fun deleteById() = runBlocking {
        val id = dao.insert(
            ChatEntity(modelId = "m", createdAt = 1L, lastMessageAt = 1L)
        )

        dao.deleteById(id)

        assertNull(dao.getById(id))
    }

    @Test
    fun observeAllEmitsSortedByLastMessageDesc() = runBlocking {
        val emptyFirst = dao.observeAll().first()
        assertEquals(0, emptyFirst.size)

        dao.insert(ChatEntity(modelId = "m", createdAt = 1L, lastMessageAt = 10L))
        dao.insert(ChatEntity(modelId = "m", createdAt = 2L, lastMessageAt = 30L))
        dao.insert(ChatEntity(modelId = "m", createdAt = 3L, lastMessageAt = 20L))

        val emitted = dao.observeAll().first()
        assertEquals(3, emitted.size)
        assertEquals(30L, emitted[0].lastMessageAt)
        assertEquals(20L, emitted[1].lastMessageAt)
        assertEquals(10L, emitted[2].lastMessageAt)
    }

    @Test
    fun observeAllEmitsOnInsert() = runBlocking {
        val before = dao.observeAll().first()
        assertEquals(0, before.size)

        dao.insert(ChatEntity(modelId = "m", createdAt = 1L, lastMessageAt = 1L))

        val after = dao.observeAll().first()
        assertEquals(1, after.size)
    }
}
