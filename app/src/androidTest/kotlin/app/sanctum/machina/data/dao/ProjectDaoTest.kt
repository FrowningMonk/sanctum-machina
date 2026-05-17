package app.sanctum.machina.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.sanctum.machina.data.SanctumDatabase
import app.sanctum.machina.data.model.ChatEntity
import app.sanctum.machina.data.model.MessageEntity
import app.sanctum.machina.data.model.ProjectEmbeddingEntity
import app.sanctum.machina.data.model.ProjectEntity
import app.sanctum.machina.data.model.ProjectFileEntity
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
class ProjectDaoTest {

    private lateinit var db: SanctumDatabase
    private lateinit var dao: ProjectDao
    private lateinit var fileDao: ProjectFileDao
    private lateinit var embeddingDao: ProjectEmbeddingDao
    private lateinit var chatDao: ChatDao
    private lateinit var messageDao: MessageDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SanctumDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(SanctumDatabase.ForeignKeysOnOpenCallback)
            .build()
        dao = db.projectDao()
        fileDao = db.projectFileDao()
        embeddingDao = db.projectEmbeddingDao()
        chatDao = db.chatDao()
        messageDao = db.messageDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndGetById() = runBlocking {
        val id = dao.insert(
            ProjectEntity(name = "Doc set", defaultModelId = "m1", createdAt = 100L)
        )

        val project = dao.getById(id)

        assertNotNull(project)
        assertEquals("Doc set", project!!.name)
        assertEquals("m1", project.defaultModelId)
        assertNull(project.ragOverridesJson)
        assertEquals(100L, project.createdAt)
    }

    @Test
    fun updateName() = runBlocking {
        val id = dao.insert(ProjectEntity(name = "before", createdAt = 1L))
        val loaded = dao.getById(id)!!

        dao.update(loaded.copy(name = "after"))

        assertEquals("after", dao.getById(id)!!.name)
    }

    @Test
    fun deleteByIdRemovesRow() = runBlocking {
        val id = dao.insert(ProjectEntity(name = "p", createdAt = 1L))
        dao.deleteById(id)
        assertNull(dao.getById(id))
    }

    @Test
    fun observeAllOrdersByCreatedAtDesc() = runBlocking {
        dao.insert(ProjectEntity(name = "a", createdAt = 10L))
        dao.insert(ProjectEntity(name = "b", createdAt = 30L))
        dao.insert(ProjectEntity(name = "c", createdAt = 20L))

        val emitted = dao.observeAll().first()

        assertEquals(3, emitted.size)
        assertEquals(30L, emitted[0].createdAt)
        assertEquals(20L, emitted[1].createdAt)
        assertEquals(10L, emitted[2].createdAt)
    }

    @Test
    fun observeAllEmitsOnInsert() = runBlocking {
        val emissions = MutableSharedFlow<List<ProjectEntity>>(replay = 0, extraBufferCapacity = 16)
        val job = launch(Dispatchers.IO) {
            dao.observeAll().collect { emissions.emit(it) }
        }

        val initial = withTimeout(2_000L) { emissions.first() }
        assertEquals(0, initial.size)

        val waitUpdated = async(Dispatchers.IO) {
            emissions.first { it.isNotEmpty() }
        }
        delay(50L)
        dao.insert(ProjectEntity(name = "p", createdAt = 1L))

        val updated = withTimeout(5_000L) { waitUpdated.await() }
        assertEquals(1, updated.size)
        assertEquals("p", updated[0].name)

        job.cancel()
    }

    @Test
    fun cascadeDeleteRemovesChildren() = runBlocking {
        val projectId = dao.insert(ProjectEntity(name = "p", createdAt = 1L))
        val fileId = fileDao.insert(
            ProjectFileEntity(
                projectId = projectId,
                fileName = "a.pdf",
                relativePath = "projects/$projectId/docs/a.pdf",
                contentHash = "h1",
                status = "ready",
                createdAt = 1L,
            )
        )
        val embeddingId = embeddingDao.insertAll(
            listOf(
                ProjectEmbeddingEntity(
                    projectId = projectId,
                    fileId = fileId,
                    page = 1,
                    chunkText = "chunk",
                    embeddingBlob = byteArrayOf(1, 2, 3, 4),
                )
            )
        ).first()
        val chatId = chatDao.insert(
            ChatEntity(
                projectId = projectId,
                modelId = "m",
                createdAt = 1L,
                lastMessageAt = 1L,
            )
        )
        messageDao.insert(
            MessageEntity(chatId = chatId, role = "user", text = "hi", createdAt = 1L)
        )
        // Sanity-check the seed row landed before we cascade it away.
        assertEquals(1, messageDao.countByChatId(chatId))

        dao.deleteById(projectId)

        assertNull(dao.getById(projectId))
        assertNull(fileDao.getById(fileId))
        assertNull(embeddingDao.getById(embeddingId))
        assertNull(chatDao.getById(chatId))
        // Chained cascade: project → chat → message. messageDao has no getById, so probe
        // via the chat-scoped count which must be zero once the chat row itself is gone.
        assertEquals(
            "messages must cascade through chats on project delete",
            0,
            messageDao.countByChatId(chatId),
        )
    }
}
