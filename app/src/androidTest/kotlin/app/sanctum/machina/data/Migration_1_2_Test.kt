package app.sanctum.machina.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Integration test for [MIGRATION_1_2]. Builds a programmatic v1 fixture (raw SQL inserts —
 * no static `.db` blob), runs the migration, validates against `2.json`, then re-opens via
 * Room and asserts per-column preservation, declared FKs / indices, and the new column.
 *
 * `MigrationTestHelper.runMigrationsAndValidate(..., validateDroppedTables = true, ...)`
 * delegates the schema-equality check to Room — every column type / nullability / default /
 * index / FK declared in `2.json` is matched against the live SQLite. The additional
 * assertions below are belt-and-suspenders for the migration-specific behaviour: v1 row
 * preservation, `citations = NULL` on legacy rows, and the recreated `chats` keeping its
 * `last_message_at` index.
 */
class Migration_1_2_Test {

    private companion object {
        const val DB_NAME = "migration-test-db"
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SanctumDatabase::class.java,
    )

    @Test
    fun migrationFromV1ToV2_preservesChatsAndMessages() {
        val v1 = helper.createDatabase(DB_NAME, 1)
        seedV1(v1, chatCount = 10, messagesPerChat = 10)
        v1.close()

        val v2 = helper.runMigrationsAndValidate(DB_NAME, 2, /* validateDroppedTables = */ true, MIGRATION_1_2)

        // All v1 chats preserved column-for-column.
        v2.query("SELECT COUNT(*) FROM chats").use {
            it.moveToFirst()
            assertEquals(10, it.getInt(0))
        }
        v2.query(
            "SELECT id, project_id, model_id, title, is_manually_titled, created_at, last_message_at " +
                "FROM chats ORDER BY id ASC",
        ).use { cursor ->
            var i = 1
            while (cursor.moveToNext()) {
                assertEquals(i.toLong(), cursor.getLong(0))
                assertTrue("v1 project_id always NULL post-migration", cursor.isNull(1))
                assertEquals("model-$i", cursor.getString(2))
                assertEquals("title-$i", cursor.getString(3))
                assertEquals(0, cursor.getInt(4))
                assertEquals(1_000L + i, cursor.getLong(5))
                assertEquals(2_000L + i, cursor.getLong(6))
                i++
            }
        }

        // All v1 messages preserved; new `citations` column is NULL everywhere.
        v2.query("SELECT COUNT(*) FROM messages").use {
            it.moveToFirst()
            assertEquals(100, it.getInt(0))
        }
        v2.query("SELECT COUNT(*) FROM messages WHERE citations IS NOT NULL").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }

        // Spot-check per-column preservation on the first message, including the new
        // `citations` column (must be NULL on every legacy row).
        v2.query(
            "SELECT chat_id, role, text, thinking_text, image_path, audio_path, " +
                "created_at, token_count, citations FROM messages WHERE id = 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
            assertEquals("user", cursor.getString(1))
            assertEquals("msg-1-1", cursor.getString(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
            assertTrue(cursor.isNull(5))
            assertEquals(3_000L + 1, cursor.getLong(6))
            assertTrue(cursor.isNull(7))
            assertTrue("citations must be NULL on legacy rows", cursor.isNull(8))
        }

        v2.close()
    }

    @Test
    fun migrationCreatesProjectTablesWithExpectedSchema() {
        helper.createDatabase(DB_NAME, 1).close()
        val v2 = helper.runMigrationsAndValidate(DB_NAME, 2, true, MIGRATION_1_2)

        assertTrue(tableExists(v2, "projects"))
        assertTrue(tableExists(v2, "project_files"))
        assertTrue(tableExists(v2, "project_embeddings"))

        // projects columns
        val projectCols = columnInfo(v2, "projects")
        assertColumn(projectCols, name = "id", type = "INTEGER", notNull = true)
        assertColumn(projectCols, name = "name", type = "TEXT", notNull = true)
        assertColumn(projectCols, name = "default_model_id", type = "TEXT", notNull = false)
        assertColumn(projectCols, name = "rag_overrides_json", type = "TEXT", notNull = false)
        assertColumn(projectCols, name = "created_at", type = "INTEGER", notNull = true)

        // project_files columns
        val fileCols = columnInfo(v2, "project_files")
        assertColumn(fileCols, name = "id", type = "INTEGER", notNull = true)
        assertColumn(fileCols, name = "project_id", type = "INTEGER", notNull = true)
        assertColumn(fileCols, name = "file_name", type = "TEXT", notNull = true)
        assertColumn(fileCols, name = "relative_path", type = "TEXT", notNull = true)
        assertColumn(fileCols, name = "content_hash", type = "TEXT", notNull = true)
        assertColumn(fileCols, name = "status", type = "TEXT", notNull = true)
        assertColumn(fileCols, name = "status_message", type = "TEXT", notNull = false)
        assertColumn(fileCols, name = "chunk_count", type = "INTEGER", notNull = false)
        assertColumn(fileCols, name = "created_at", type = "INTEGER", notNull = true)

        // project_embeddings columns
        val embedCols = columnInfo(v2, "project_embeddings")
        assertColumn(embedCols, name = "id", type = "INTEGER", notNull = true)
        assertColumn(embedCols, name = "project_id", type = "INTEGER", notNull = true)
        assertColumn(embedCols, name = "file_id", type = "INTEGER", notNull = true)
        assertColumn(embedCols, name = "page", type = "INTEGER", notNull = false)
        assertColumn(embedCols, name = "chunk_text", type = "TEXT", notNull = true)
        assertColumn(embedCols, name = "embedding_blob", type = "BLOB", notNull = true)

        // Indices on project_files include the UNIQUE composite.
        val fileIndices = indexList(v2, "project_files")
        assertTrue("plain project_id index", fileIndices.any { it.name == "index_project_files_project_id" && !it.unique })
        val uniqueCompound = fileIndices.singleOrNull {
            it.name == "index_project_files_project_id_content_hash"
        }
        assertNotNull("unique composite index present", uniqueCompound)
        assertTrue("composite index is UNIQUE", uniqueCompound!!.unique)
        assertEquals(
            listOf("project_id", "content_hash"),
            indexColumns(v2, uniqueCompound.name),
        )

        val embedIndices = indexList(v2, "project_embeddings")
        assertTrue(embedIndices.any { it.name == "index_project_embeddings_project_id" })
        assertTrue(embedIndices.any { it.name == "index_project_embeddings_file_id" })

        v2.close()
    }

    @Test
    fun migrationInstallsForeignKeysOnChats() {
        helper.createDatabase(DB_NAME, 1).close()
        val v2 = helper.runMigrationsAndValidate(DB_NAME, 2, true, MIGRATION_1_2)

        // chats now has a FK to projects(id) ON DELETE CASCADE.
        val chatFks = foreignKeyList(v2, "chats")
        val toProjects = chatFks.singleOrNull { it.table == "projects" }
        assertNotNull("chats has FK → projects", toProjects)
        assertEquals("project_id", toProjects!!.from)
        assertEquals("id", toProjects.to)
        assertEquals("CASCADE", toProjects.onDelete)

        // last_message_at index intact after recreate-table; project_id index added.
        val chatIndices = indexList(v2, "chats").map { it.name }
        assertTrue("last_message_at index intact", "index_chats_last_message_at" in chatIndices)
        assertTrue("project_id index added", "index_chats_project_id" in chatIndices)

        // project_files cascades from projects.
        val pfFks = foreignKeyList(v2, "project_files")
        assertTrue(pfFks.any { it.table == "projects" && it.onDelete == "CASCADE" })

        // project_embeddings cascades from BOTH projects and project_files.
        val peFks = foreignKeyList(v2, "project_embeddings")
        assertTrue(peFks.any { it.table == "projects" && it.onDelete == "CASCADE" })
        assertTrue(peFks.any { it.table == "project_files" && it.onDelete == "CASCADE" })

        v2.close()
    }

    @Test
    fun migrationAddsCitationsColumnToMessages() {
        helper.createDatabase(DB_NAME, 1).close()
        val v2 = helper.runMigrationsAndValidate(DB_NAME, 2, true, MIGRATION_1_2)

        val cols = columnInfo(v2, "messages")
        assertColumn(cols, name = "citations", type = "TEXT", notNull = false)

        v2.close()
    }

    @Test
    fun migrationOpensViaRoomAndEnforcesNewForeignKey() {
        val v1 = helper.createDatabase(DB_NAME, 1)
        seedV1(v1, chatCount = 1, messagesPerChat = 1)
        v1.close()
        // First open through MigrationTestHelper applies the migration in isolation.
        helper.runMigrationsAndValidate(DB_NAME, 2, true, MIGRATION_1_2).close()

        // Re-open via the production builder — same migration list — to assert that the
        // resulting DB is also usable through Room's runtime, not just at the raw SQLite
        // layer. FK enforcement is then validated by attempting an orphan chat insert.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, SanctumDatabase::class.java, DB_NAME)
            .addMigrations(MIGRATION_1_2)
            .addCallback(SanctumDatabase.ForeignKeysOnOpenCallback)
            .build()
        try {
            val support = db.openHelper.writableDatabase
            // Legacy row preserved with project_id = NULL.
            support.query("SELECT COUNT(*) FROM chats WHERE project_id IS NULL").use {
                it.moveToFirst()
                assertEquals(1, it.getInt(0))
            }
            // FK is enforced — attempting an orphan chat fails with the precise
            // SQLite constraint exception, not just any throwable.
            var threw = false
            try {
                support.execSQL(
                    "INSERT INTO chats (project_id, model_id, title, is_manually_titled, " +
                        "created_at, last_message_at) VALUES (9999, 'm', NULL, 0, 1, 1)",
                )
            } catch (e: SQLiteConstraintException) {
                threw = true
            }
            assertTrue("Orphan chat insert with non-existent project_id must fail under FK", threw)

            // Exercise the project_files → project_embeddings cascade end-to-end on the
            // migrated DB (not just on a fresh inMemory v2 build) so we catch any FK
            // mismatch between the migration's CREATE statements and Room's expectations.
            val projectDao = db.projectDao()
            val fileDao = db.projectFileDao()
            val embeddingDao = db.projectEmbeddingDao()
            kotlinx.coroutines.runBlocking {
                val projectId = projectDao.insert(
                    app.sanctum.machina.data.model.ProjectEntity(
                        name = "migrated-cascade",
                        createdAt = 1L,
                    )
                )
                val fileId = fileDao.insert(
                    app.sanctum.machina.data.model.ProjectFileEntity(
                        projectId = projectId,
                        fileName = "a.pdf",
                        relativePath = "projects/$projectId/docs/a.pdf",
                        contentHash = "h-cascade",
                        status = "ready",
                        createdAt = 1L,
                    )
                )
                val embId = embeddingDao.insertAll(
                    listOf(
                        app.sanctum.machina.data.model.ProjectEmbeddingEntity(
                            projectId = projectId,
                            fileId = fileId,
                            page = 1,
                            chunkText = "chunk",
                            embeddingBlob = byteArrayOf(1, 2, 3),
                        )
                    )
                ).single()
                fileDao.deleteById(fileId)
                assertEquals(
                    "project_embeddings must cascade from project_files on migrated DB",
                    null,
                    embeddingDao.getById(embId),
                )
            }
        } finally {
            db.close()
            // Clean up the on-disk DB file so a re-run of this test does not see leftovers.
            context.deleteDatabase(DB_NAME)
        }
    }

    // ---------- fixture builders ----------

    private fun seedV1(db: SupportSQLiteDatabase, chatCount: Int, messagesPerChat: Int) {
        for (i in 1..chatCount) {
            db.execSQL(
                "INSERT INTO chats (id, project_id, model_id, title, is_manually_titled, " +
                    "created_at, last_message_at) VALUES (?, NULL, ?, ?, 0, ?, ?)",
                arrayOf<Any?>(i.toLong(), "model-$i", "title-$i", 1_000L + i, 2_000L + i),
            )
            for (m in 1..messagesPerChat) {
                db.execSQL(
                    "INSERT INTO messages (chat_id, role, text, created_at) " +
                        "VALUES (?, ?, ?, ?)",
                    arrayOf<Any?>(
                        i.toLong(),
                        if (m % 2 == 1) "user" else "assistant",
                        "msg-$i-$m",
                        3_000L + m,
                    ),
                )
            }
        }
    }

    // ---------- PRAGMA readers ----------

    private data class IndexEntry(val name: String, val unique: Boolean)
    private data class ForeignKeyEntry(
        val table: String,
        val from: String,
        val to: String,
        val onDelete: String,
    )

    private fun tableExists(db: SupportSQLiteDatabase, name: String): Boolean {
        db.query(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf<Any?>(name),
        ).use { return it.moveToFirst() }
    }

    private fun columnInfo(
        db: SupportSQLiteDatabase,
        table: String,
    ): Map<String, Pair<String, Boolean>> {
        val out = mutableMapOf<String, Pair<String, Boolean>>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            val typeIdx = cursor.getColumnIndex("type")
            val notNullIdx = cursor.getColumnIndex("notnull")
            while (cursor.moveToNext()) {
                out[cursor.getString(nameIdx)] = cursor.getString(typeIdx).uppercase() to
                    (cursor.getInt(notNullIdx) == 1)
            }
        }
        return out
    }

    private fun assertColumn(
        cols: Map<String, Pair<String, Boolean>>,
        name: String,
        type: String,
        notNull: Boolean,
    ) {
        val actual = cols[name]
        assertNotNull("column $name present", actual)
        assertEquals("column $name type", type.uppercase(), actual!!.first)
        if (notNull) {
            assertTrue("column $name must be NOT NULL", actual.second)
        } else {
            assertFalse("column $name must be nullable", actual.second)
        }
    }

    private fun indexList(db: SupportSQLiteDatabase, table: String): List<IndexEntry> {
        val out = mutableListOf<IndexEntry>()
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            val uniqueIdx = cursor.getColumnIndex("unique")
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx)
                // Skip auto-created indices for PRIMARY KEY / UNIQUE — they start with "sqlite_".
                if (name.startsWith("sqlite_")) continue
                out += IndexEntry(name, cursor.getInt(uniqueIdx) == 1)
            }
        }
        return out
    }

    private fun indexColumns(db: SupportSQLiteDatabase, indexName: String): List<String> {
        val out = mutableListOf<String>()
        db.query("PRAGMA index_info(`$indexName`)").use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) out += cursor.getString(nameIdx)
        }
        return out
    }

    private fun foreignKeyList(
        db: SupportSQLiteDatabase,
        table: String,
    ): List<ForeignKeyEntry> {
        val out = mutableListOf<ForeignKeyEntry>()
        db.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
            val tableIdx = cursor.getColumnIndex("table")
            val fromIdx = cursor.getColumnIndex("from")
            val toIdx = cursor.getColumnIndex("to")
            val onDeleteIdx = cursor.getColumnIndex("on_delete")
            while (cursor.moveToNext()) {
                out += ForeignKeyEntry(
                    table = cursor.getString(tableIdx),
                    from = cursor.getString(fromIdx),
                    to = cursor.getString(toIdx),
                    onDelete = cursor.getString(onDeleteIdx),
                )
            }
        }
        return out
    }

}
