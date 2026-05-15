package app.sanctum.machina.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.data.model.ChatEntity
import app.sanctum.machina.data.model.MessageEntity
import app.sanctum.machina.data.model.ProjectEntity
import app.sanctum.machina.data.model.ProjectFileEntity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Decision 8 integration test on real Room: 1000 cited messages, single `deleteFile`
 * invocation, assert (a) every matching `fileId` flipped to `stale = true`, (b) every
 * non-matching entry untouched, (c) elapsed time inside a loose cost bound. Spec target on
 * Honor 200 is <500 ms — the assertion here uses <10 s so CI / emulators do not flap.
 */
@RunWith(AndroidJUnit4::class)
class StaleCitationMarkTest {

    private lateinit var db: SanctumDatabase
    private lateinit var repo: DefaultProjectRepository
    private lateinit var filesDir: File
    private val gson = Gson()
    private val citationListType = object : TypeToken<List<Citation>>() {}.type

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SanctumDatabase::class.java)
            .addCallback(SanctumDatabase.ForeignKeysOnOpenCallback)
            .build()
        filesDir = context.filesDir
        repo = DefaultProjectRepository(
            database = db,
            projectDao = db.projectDao(),
            projectFileDao = db.projectFileDao(),
            projectEmbeddingDao = db.projectEmbeddingDao(),
            messageDao = db.messageDao(),
            errorLog = ErrorLog(context),
            gson = gson,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun eagerMarkOver1000Messages_completesWithinBound() = runBlocking(Dispatchers.IO) {
        val projectId = db.projectDao().insert(ProjectEntity(name = "p", createdAt = 1L))
        val chatId = db.chatDao().insert(
            ChatEntity(
                projectId = projectId,
                modelId = "m",
                createdAt = 1L,
                lastMessageAt = 1L,
            ),
        )
        val deletedFileId = db.projectFileDao().insert(
            ProjectFileEntity(
                projectId = projectId,
                fileName = "del.pdf",
                relativePath = "projects/$projectId/docs/del.pdf",
                contentHash = "h-del",
                status = "ready",
                createdAt = 1L,
            ),
        )
        val keptFileId = db.projectFileDao().insert(
            ProjectFileEntity(
                projectId = projectId,
                fileName = "keep.pdf",
                relativePath = "projects/$projectId/docs/keep.pdf",
                contentHash = "h-keep",
                status = "ready",
                createdAt = 1L,
            ),
        )

        // 1000 cited messages: half cite the file we're about to delete, half cite the other.
        // Interleave so batch boundaries (multiples of 50) see both flavours.
        val total = 1000
        for (i in 0 until total) {
            val targetFileId = if (i % 2 == 0) deletedFileId else keptFileId
            val name = if (targetFileId == deletedFileId) "del.pdf" else "keep.pdf"
            val json = gson.toJson(
                listOf(
                    Citation(
                        fileId = targetFileId,
                        fileName = name,
                        page = (i % 9) + 1,
                        chunkText = "chunk-$i",
                        stale = false,
                    ),
                ),
            )
            db.messageDao().insert(
                MessageEntity(
                    chatId = chatId,
                    role = "assistant",
                    text = "msg-$i",
                    createdAt = (i + 2).toLong(),
                    citations = json,
                ),
            )
        }

        val elapsed = measureTimeMillis {
            repo.deleteFile(deletedFileId, filesDir)
        }

        // Spec target on Honor 200 is <500 ms; loose CI bound is <3 s so the assertion
        // still detects a realistic regression (test-reviewer-1 minor — 10 s was 20× looser
        // than the task brief negotiated).
        assertTrue("deleteFile elapsed=${elapsed}ms exceeded 3s ceiling", elapsed < 3_000)

        // Walk the corpus and verify per-row outcome. Use chat-id-scoped getter to avoid
        // pulling all messages once we have hundreds of them.
        val allMessages = db.messageDao().getByChatId(chatId)
        var deletedStaleCount = 0
        var keptUntouchedCount = 0
        for (m in allMessages) {
            val citations: List<Citation> = gson.fromJson(m.citations!!, citationListType)
            val single = citations.single()
            when (single.fileId) {
                deletedFileId -> {
                    assertTrue("deleted-file citation must be stale, msgId=${m.id}", single.stale)
                    deletedStaleCount++
                }
                keptFileId -> {
                    assertTrue(
                        "kept-file citation must NOT be stale, msgId=${m.id}",
                        !single.stale,
                    )
                    keptUntouchedCount++
                }
                else -> error("unexpected fileId in citation: $single")
            }
        }
        assertTrue("at least 1 deleted-file citation processed", deletedStaleCount > 0)
        assertTrue("at least 1 kept-file citation untouched", keptUntouchedCount > 0)
        // The two halves should sum to the total — no rows skipped silently.
        assertTrue(
            "deleted+kept count $deletedStaleCount+$keptUntouchedCount = total $total",
            deletedStaleCount + keptUntouchedCount == total,
        )

        // FK CASCADE removed project_files row + project_embeddings (vacuously here, since
        // we never inserted any embeddings for this fixture). Confirm the row is gone.
        assertNull(db.projectFileDao().getById(deletedFileId))
    }
}
