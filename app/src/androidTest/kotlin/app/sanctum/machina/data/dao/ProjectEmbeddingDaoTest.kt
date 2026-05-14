package app.sanctum.machina.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.sanctum.machina.data.SanctumDatabase
import app.sanctum.machina.data.model.ProjectEmbeddingEntity
import app.sanctum.machina.data.model.ProjectEntity
import app.sanctum.machina.data.model.ProjectFileEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectEmbeddingDaoTest {

    private lateinit var db: SanctumDatabase
    private lateinit var dao: ProjectEmbeddingDao
    private lateinit var projectDao: ProjectDao
    private lateinit var fileDao: ProjectFileDao

    private var projectId: Long = 0L

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SanctumDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(SanctumDatabase.ForeignKeysOnOpenCallback)
            .build()
        dao = db.projectEmbeddingDao()
        projectDao = db.projectDao()
        fileDao = db.projectFileDao()

        projectId = projectDao.insert(ProjectEntity(name = "p", createdAt = 1L))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertFile(
        contentHash: String,
        status: String = "ready",
        fileName: String = "doc.pdf",
    ): Long = fileDao.insert(
        ProjectFileEntity(
            projectId = projectId,
            fileName = fileName,
            relativePath = "projects/$projectId/docs/$fileName",
            contentHash = contentHash,
            status = status,
            createdAt = 1L,
        )
    )

    private fun embedding(
        fileId: Long,
        page: Int? = null,
        chunkText: String = "chunk",
        blob: ByteArray = byteArrayOf(0, 1, 2),
    ) = ProjectEmbeddingEntity(
        projectId = projectId,
        fileId = fileId,
        page = page,
        chunkText = chunkText,
        embeddingBlob = blob,
    )

    @Test
    fun insertAllReturnsDistinctIdsAlignedWithInputOrder() = runBlocking {
        val fileId = insertFile("h1")
        val ids = dao.insertAll(
            listOf(
                embedding(fileId, page = 1, chunkText = "a"),
                embedding(fileId, page = 2, chunkText = "b"),
                embedding(fileId, page = 3, chunkText = "c"),
            )
        )
        assertEquals(3, ids.size)
        assertEquals("returned ids must be distinct", 3, ids.toSet().size)
        // Room's @Insert returns the rowids in the order of the insertion list, and the
        // AUTOINCREMENT PK is monotonic on an empty table — assert both invariants.
        assertEquals(ids.sorted(), ids)
        for (i in ids.indices) {
            val loaded = dao.getById(ids[i])
            assertEquals("input #$i must round-trip to id ${ids[i]}", i + 1, loaded!!.page)
        }
    }

    @Test
    fun blobRoundtripPreservesBytes() = runBlocking {
        val fileId = insertFile("h1")
        val payload = ByteArray(32) { (it * 7 - 3).toByte() }
        val id = dao.insertAll(listOf(embedding(fileId, blob = payload))).single()

        val loaded = dao.getById(id)
        assertNotNull(loaded)
        assertTrue(
            "BLOB bytes must round-trip exactly",
            payload.contentEquals(loaded!!.embeddingBlob),
        )
    }

    @Test
    fun blobRoundtripPreservesEmptyByteArray() = runBlocking {
        val fileId = insertFile("h-empty")
        val id = dao.insertAll(listOf(embedding(fileId, blob = ByteArray(0)))).single()
        val loaded = dao.getById(id)!!
        assertEquals(0, loaded.embeddingBlob.size)
    }

    @Test
    fun deleteByFileIdScopesToFile() = runBlocking {
        val fileA = insertFile("hA")
        val fileB = insertFile("hB")
        val keptId = dao.insertAll(listOf(embedding(fileA, chunkText = "keep"))).single()
        val droppedId = dao.insertAll(listOf(embedding(fileB, chunkText = "drop"))).single()

        dao.deleteByFileId(fileB)

        assertNotNull(dao.getById(keptId))
        assertNull(dao.getById(droppedId))
    }

    @Test
    fun allByProjectAndReadyFiles_excludesNonReady() = runBlocking {
        val readyFile = insertFile("h-ready", status = "ready", fileName = "ready.pdf")
        val pendingFile = insertFile("h-pending", status = "pending", fileName = "pending.pdf")
        dao.insertAll(listOf(embedding(readyFile, chunkText = "rdy")))
        dao.insertAll(listOf(embedding(pendingFile, chunkText = "pen")))

        val rows = dao.allByProjectAndReadyFiles(projectId)

        assertEquals(1, rows.size)
        assertEquals(readyFile, rows.single().fileId)
        assertEquals("ready.pdf", rows.single().fileName)
        assertEquals("rdy", rows.single().chunkText)
    }

    @Test
    fun allByProjectAndReadyFiles_projectionFieldsPopulated() = runBlocking {
        val fileId = insertFile("h-proj", fileName = "proj.pdf")
        dao.insertAll(
            listOf(
                embedding(
                    fileId = fileId,
                    page = 7,
                    chunkText = "hello",
                    blob = byteArrayOf(9, 8, 7),
                )
            )
        )

        val row = dao.allByProjectAndReadyFiles(projectId).single()

        assertEquals(fileId, row.fileId)
        assertEquals("proj.pdf", row.fileName)
        assertEquals(7, row.page)
        assertEquals("hello", row.chunkText)
        assertTrue(byteArrayOf(9, 8, 7).contentEquals(row.embeddingBlob))
    }
}
