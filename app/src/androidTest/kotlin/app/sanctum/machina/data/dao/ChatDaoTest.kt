package app.sanctum.machina.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.sanctum.machina.data.SanctumDatabase
import app.sanctum.machina.data.model.ChatEntity
import app.sanctum.machina.data.model.ProjectEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
            .addCallback(SanctumDatabase.ForeignKeysOnOpenCallback)
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
    fun insertAndGetByIdPreservesProjectIdValue() = runBlocking {
        // v2 added an FK chats.project_id → projects(id); insert a parent project first
        // so the round-trip exercises the link without tripping FK enforcement.
        val projectId = db.projectDao().insert(ProjectEntity(name = "p", createdAt = 1L))
        val id = dao.insert(
            ChatEntity(
                projectId = projectId,
                modelId = "m",
                createdAt = 1L,
                lastMessageAt = 1L
            )
        )

        val chat = dao.getById(id)!!
        assertEquals(projectId, chat.projectId)
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
        // Live collector — must observe an emission triggered by the INSERT,
        // not just the initial emission Room delivers on subscribe.
        val emissions = MutableSharedFlow<List<ChatEntity>>(replay = 0, extraBufferCapacity = 16)
        val job = launch(Dispatchers.IO) {
            dao.observeAll().collect { emissions.emit(it) }
        }

        // First emission is the current (empty) state.
        val initial = withTimeout(2_000L) { emissions.first() }
        assertEquals(0, initial.size)

        // Trigger a re-emission via insert; wait for the non-empty list.
        val waitUpdated = async(Dispatchers.IO) {
            emissions.first { it.isNotEmpty() }
        }
        delay(50L) // let the collector re-subscribe for the next emission
        dao.insert(ChatEntity(modelId = "m", createdAt = 1L, lastMessageAt = 10L))

        val updated = withTimeout(5_000L) { waitUpdated.await() }
        assertEquals(1, updated.size)
        assertEquals(10L, updated[0].lastMessageAt)

        job.cancel()
    }
}
