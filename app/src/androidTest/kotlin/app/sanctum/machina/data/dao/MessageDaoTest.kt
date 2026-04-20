package app.sanctum.machina.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.sanctum.machina.data.SanctumDatabase
import app.sanctum.machina.data.model.ChatEntity
import app.sanctum.machina.data.model.MessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageDaoTest {

    private lateinit var db: SanctumDatabase
    private lateinit var chatDao: ChatDao
    private lateinit var messageDao: MessageDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SanctumDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        chatDao = db.chatDao()
        messageDao = db.messageDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertChat(): Long =
        chatDao.insert(ChatEntity(modelId = "m", createdAt = 1L, lastMessageAt = 1L))

    @Test
    fun insertAndGetByChatId() = runBlocking {
        val chatId = insertChat()

        messageDao.insert(
            MessageEntity(
                chatId = chatId,
                role = "user",
                text = "hello",
                createdAt = 100L
            )
        )

        val list = messageDao.getByChatId(chatId)
        assertEquals(1, list.size)
        assertEquals("hello", list[0].text)
        assertEquals("user", list[0].role)
        assertEquals(chatId, list[0].chatId)
    }

    @Test
    fun observeByChatSortedByCreatedAtAsc() = runBlocking {
        val chatId = insertChat()
        messageDao.insert(MessageEntity(chatId = chatId, role = "user", text = "B", createdAt = 200L))
        messageDao.insert(MessageEntity(chatId = chatId, role = "assistant", text = "A", createdAt = 100L))
        messageDao.insert(MessageEntity(chatId = chatId, role = "user", text = "C", createdAt = 300L))

        val emitted = messageDao.observeByChat(chatId).first()

        assertEquals(3, emitted.size)
        assertEquals("A", emitted[0].text)
        assertEquals("B", emitted[1].text)
        assertEquals("C", emitted[2].text)
    }

    @Test
    fun onDeleteCascadeRemovesMessages() = runBlocking {
        val chatId = insertChat()
        messageDao.insert(MessageEntity(chatId = chatId, role = "user", text = "x", createdAt = 10L))
        messageDao.insert(MessageEntity(chatId = chatId, role = "assistant", text = "y", createdAt = 20L))
        assertEquals(2, messageDao.countByChatId(chatId))

        chatDao.deleteById(chatId)

        assertEquals(0, messageDao.countByChatId(chatId))
        assertEquals(0, messageDao.getByChatId(chatId).size)
    }

    @Test
    fun textDefaultsToEmptyString() = runBlocking {
        val chatId = insertChat()

        messageDao.insert(MessageEntity(chatId = chatId, role = "user", createdAt = 5L))

        val msg = messageDao.getByChatId(chatId).single()
        assertEquals("", msg.text)
    }

    @Test
    fun deleteByIdRemovesSingleMessage() = runBlocking {
        val chatId = insertChat()
        val keep = messageDao.insert(
            MessageEntity(chatId = chatId, role = "user", text = "keep", createdAt = 1L)
        )
        val drop = messageDao.insert(
            MessageEntity(chatId = chatId, role = "assistant", text = "drop", createdAt = 2L)
        )

        messageDao.deleteById(drop)

        val left = messageDao.getByChatId(chatId)
        assertEquals(1, left.size)
        assertEquals(keep, left[0].id)
    }
}
