package app.sanctum.machina.data.dao

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.sanctum.machina.data.SanctumDatabase
import app.sanctum.machina.data.model.ProjectEmbeddingEntity
import app.sanctum.machina.data.model.ProjectEntity
import app.sanctum.machina.data.model.ProjectFileEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectFileDaoTest {

    private lateinit var db: SanctumDatabase
    private lateinit var dao: ProjectFileDao
    private lateinit var projectDao: ProjectDao
    private lateinit var embeddingDao: ProjectEmbeddingDao

    private var projectAId: Long = 0L
    private var projectBId: Long = 0L

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SanctumDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(SanctumDatabase.ForeignKeysOnOpenCallback)
            .build()
        dao = db.projectFileDao()
        projectDao = db.projectDao()
        embeddingDao = db.projectEmbeddingDao()

        projectAId = projectDao.insert(ProjectEntity(name = "A", createdAt = 1L))
        projectBId = projectDao.insert(ProjectEntity(name = "B", createdAt = 1L))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun file(
        projectId: Long = projectAId,
        contentHash: String = "h-default",
        status: String = "pending",
        createdAt: Long = 1L,
    ): ProjectFileEntity = ProjectFileEntity(
        projectId = projectId,
        fileName = "doc.pdf",
        relativePath = "projects/$projectId/docs/doc.pdf",
        contentHash = contentHash,
        status = status,
        createdAt = createdAt,
    )

    @Test
    fun insertAndGetById() = runBlocking {
        val id = dao.insert(file(contentHash = "h1"))
        val loaded = dao.getById(id)
        assertNotNull(loaded)
        assertEquals(projectAId, loaded!!.projectId)
        assertEquals("h1", loaded.contentHash)
        assertEquals("pending", loaded.status)
    }

    @Test
    fun updateStatusAndStatusMessage() = runBlocking {
        val id = dao.insert(file(contentHash = "h2"))
        val loaded = dao.getById(id)!!

        dao.update(loaded.copy(status = "failed", statusMessage = "Прервано"))

        val after = dao.getById(id)!!
        assertEquals("failed", after.status)
        assertEquals("Прервано", after.statusMessage)
    }

    @Test
    fun deleteById() = runBlocking {
        val id = dao.insert(file(contentHash = "h3"))
        dao.deleteById(id)
        assertNull(dao.getById(id))
    }

    @Test
    fun observeByProjectIsScopedAndReactive() = runBlocking {
        dao.insert(file(projectId = projectAId, contentHash = "ha"))
        dao.insert(file(projectId = projectBId, contentHash = "hb"))

        val a = dao.observeByProject(projectAId).first()
        val b = dao.observeByProject(projectBId).first()

        assertEquals(1, a.size)
        assertEquals(1, b.size)
        assertEquals(projectAId, a.single().projectId)
        assertEquals(projectBId, b.single().projectId)
    }

    @Test
    fun getByProjectAndHashReturnsMatch() = runBlocking {
        val id = dao.insert(file(contentHash = "hash-X"))
        val match = dao.getByProjectAndHash(projectAId, "hash-X")
        assertNotNull(match)
        assertEquals(id, match!!.id)
        assertNull(dao.getByProjectAndHash(projectAId, "missing"))
    }

    @Test
    fun insertDuplicateHashInSameProjectFailsUniqueConstraint() = runBlocking {
        dao.insert(file(contentHash = "dup"))
        try {
            dao.insert(file(contentHash = "dup"))
            fail("Expected unique-index violation on (project_id, content_hash)")
        } catch (e: SQLiteConstraintException) {
            // expected
        }
    }

    @Test
    fun sameHashInDifferentProjectAllowed() = runBlocking {
        val a = dao.insert(file(projectId = projectAId, contentHash = "shared"))
        val b = dao.insert(file(projectId = projectBId, contentHash = "shared"))
        assertNotNull(dao.getById(a))
        assertNotNull(dao.getById(b))
    }

    @Test
    fun cascadeDeleteRemovesEmbeddings() = runBlocking {
        val fileId = dao.insert(file(contentHash = "h-cascade"))
        val embId = embeddingDao.insertAll(
            listOf(
                ProjectEmbeddingEntity(
                    projectId = projectAId,
                    fileId = fileId,
                    page = 1,
                    chunkText = "x",
                    embeddingBlob = byteArrayOf(0, 1, 2),
                )
            )
        ).first()

        dao.deleteById(fileId)

        assertNull(dao.getById(fileId))
        assertNull(embeddingDao.getById(embId))
    }

    @Test
    fun observeReadyCountReflectsStatus() = runBlocking {
        val id = dao.insert(file(contentHash = "rc", status = "pending"))
        assertEquals(0, dao.observeReadyCount(projectAId).first())

        dao.update(dao.getById(id)!!.copy(status = "indexing"))
        assertEquals(0, dao.observeReadyCount(projectAId).first())

        dao.update(dao.getById(id)!!.copy(status = "ready"))
        assertEquals(1, dao.observeReadyCount(projectAId).first())

        dao.update(dao.getById(id)!!.copy(status = "failed"))
        assertEquals(0, dao.observeReadyCount(projectAId).first())
    }

    @Test
    fun findByProjectAndStatusFiltersAndScopes() = runBlocking {
        dao.insert(file(projectId = projectAId, contentHash = "f1", status = "failed", createdAt = 1L))
        dao.insert(file(projectId = projectAId, contentHash = "f2", status = "failed", createdAt = 2L))
        dao.insert(file(projectId = projectAId, contentHash = "f3", status = "ready", createdAt = 3L))
        dao.insert(file(projectId = projectBId, contentHash = "f4", status = "failed", createdAt = 1L))

        val failedInA = dao.findByProjectAndStatus(projectAId, "failed")

        assertEquals(2, failedInA.size)
        assertTrue(failedInA.all { it.projectId == projectAId && it.status == "failed" })
        assertEquals(1L, failedInA[0].createdAt) // ascending
        assertEquals(2L, failedInA[1].createdAt)
    }
}
