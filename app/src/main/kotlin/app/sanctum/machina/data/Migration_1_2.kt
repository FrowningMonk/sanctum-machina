package app.sanctum.machina.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * First migration in the codebase. v1 → v2 adds the Phase 4 (Projects + RAG) data layer:
 *
 *  1. CREATE `projects`.
 *  2. CREATE `project_files` (FK → projects, unique `(project_id, content_hash)` for SHA-256
 *     dedup, index on `project_id`).
 *  3. CREATE `project_embeddings` (FKs → projects + project_files, indices on both).
 *  4. Recreate `chats` so the existing v1 `project_id` column gains an `ON DELETE CASCADE`
 *     FK to `projects(id)` — SQLite has no `ADD FOREIGN KEY`. v1 rows are preserved
 *     column-for-column; the FK is not validated on existing rows (legal SQLite behaviour:
 *     `PRAGMA foreign_keys = ON` only checks rows touched *after* the constraint is in
 *     place, and v1 cannot hold non-null `project_id` values that match a real project
 *     since `projects` did not exist yet).
 *  5. ALTER `messages` to add the nullable `citations` TEXT column for Decision 7 JSON
 *     blob storage.
 *
 * The CREATE statements below match Room's generated v2 schema verbatim (compiled
 * `2.json` is the source of truth — re-sync if it diverges). All steps run inside the
 * single transaction Room wraps around `migrate()`. The migration is registered in
 * `AppModule.buildSanctumDatabase` (Decision 13); there is no `fallbackToDestructiveMigration`
 * because the corruption-recovery path already handles destructive recovery.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. projects
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `projects` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`default_model_id` TEXT, " +
                "`rag_overrides_json` TEXT, " +
                "`created_at` INTEGER NOT NULL)",
        )

        // 2. project_files
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `project_files` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`project_id` INTEGER NOT NULL, " +
                "`file_name` TEXT NOT NULL, " +
                "`relative_path` TEXT NOT NULL, " +
                "`content_hash` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`status_message` TEXT, " +
                "`chunk_count` INTEGER, " +
                "`created_at` INTEGER NOT NULL, " +
                "FOREIGN KEY(`project_id`) REFERENCES `projects`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_project_files_project_id` " +
                "ON `project_files` (`project_id`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_project_files_project_id_content_hash` " +
                "ON `project_files` (`project_id`, `content_hash`)",
        )

        // 3. project_embeddings
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `project_embeddings` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`project_id` INTEGER NOT NULL, " +
                "`file_id` INTEGER NOT NULL, " +
                "`page` INTEGER, " +
                "`chunk_text` TEXT NOT NULL, " +
                "`embedding_blob` BLOB NOT NULL, " +
                "FOREIGN KEY(`project_id`) REFERENCES `projects`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`file_id`) REFERENCES `project_files`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_project_embeddings_project_id` " +
                "ON `project_embeddings` (`project_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_project_embeddings_file_id` " +
                "ON `project_embeddings` (`file_id`)",
        )

        // 4. Recreate `chats` with FK to projects(id). SQLite cannot ADD FOREIGN KEY
        //    on an existing column, hence the rename-trick. v1 indices are dropped
        //    with the table and re-created on the renamed table below.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `chats_new` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`project_id` INTEGER, " +
                "`model_id` TEXT NOT NULL, " +
                "`title` TEXT, " +
                "`is_manually_titled` INTEGER NOT NULL DEFAULT 0, " +
                "`created_at` INTEGER NOT NULL, " +
                "`last_message_at` INTEGER NOT NULL, " +
                "FOREIGN KEY(`project_id`) REFERENCES `projects`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "INSERT INTO `chats_new` " +
                "(`id`, `project_id`, `model_id`, `title`, `is_manually_titled`, " +
                "`created_at`, `last_message_at`) " +
                "SELECT `id`, `project_id`, `model_id`, `title`, `is_manually_titled`, " +
                "`created_at`, `last_message_at` FROM `chats`",
        )
        db.execSQL("DROP TABLE `chats`")
        db.execSQL("ALTER TABLE `chats_new` RENAME TO `chats`")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chats_last_message_at` " +
                "ON `chats` (`last_message_at`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chats_project_id` " +
                "ON `chats` (`project_id`)",
        )

        // 5. messages.citations (nullable, additive — no rewrite needed).
        db.execSQL("ALTER TABLE `messages` ADD COLUMN `citations` TEXT")
    }
}
