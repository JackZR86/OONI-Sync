package com.androidbackup.app.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.androidbackup.app.data.local.dao.*
import com.androidbackup.app.data.local.entity.*

/**
 * Database Room principale per Android Backup.
 *
 * Vincoli architetturali Fase 1B.2:
 * - Versione 3 (migrazione incrementale da v2 tramite MIGRATION_2_3)
 * - File SQLite stabile: "android_backup.db"
 * - NESSUN allowMainThreadQueries() nell'ambiente di produzione
 * - NESSUN fallbackToDestructiveMigration()
 * - Preserva integralmente impostazioni v1, esclusioni v1, cartelle v2
 * - Nuove tabelle per inventario file: scan_sessions, scanned_documents, pending_directories, scan_errors
 */
@Database(
    entities = [
        AppSettingsEntity::class,
        ExclusionPatternEntity::class,
        BackupFolderEntity::class,
        ScanSessionEntity::class,
        ScannedDocumentEntity::class,
        PendingDirectoryEntity::class,
        ScanErrorEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class BackupDatabase : RoomDatabase() {

    abstract fun settingsDao(): SettingsDao
    abstract fun folderDao(): FolderDao
    abstract fun scanSessionDao(): ScanSessionDao
    abstract fun scannedDocumentDao(): ScannedDocumentDao
    abstract fun pendingDirectoryDao(): PendingDirectoryDao
    abstract fun scanErrorDao(): ScanErrorDao

    companion object {
        const val DATABASE_NAME = "android_backup.db"

        @Volatile
        private var INSTANCE: BackupDatabase? = null

        /**
         * Migrazione esplicita dalla Versione 1 alla Versione 2.
         * Crea la nuova tabella `backup_folders` e l'indice univoco su `tree_uri`.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `backup_folders` (
                        `id` TEXT NOT NULL,
                        `tree_uri` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `authority` TEXT,
                        `added_timestamp` INTEGER NOT NULL,
                        `priority` TEXT NOT NULL,
                        `protect_from_eviction` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_backup_folders_tree_uri` ON `backup_folders` (`tree_uri`)"
                )
            }
        }

        /**
         * Migrazione esplicita dalla Versione 2 alla Versione 3 (Fase 1B.2).
         * Crea le tabelle per le sessioni di scansione, l'inventario dei documenti, la coda persistente e gli errori.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Tabella scan_sessions
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `scan_sessions` (
                        `id` TEXT NOT NULL,
                        `started_timestamp` INTEGER NOT NULL,
                        `completed_timestamp` INTEGER,
                        `status` TEXT NOT NULL,
                        `total_files` INTEGER NOT NULL,
                        `total_directories` INTEGER NOT NULL,
                        `known_total_bytes` INTEGER NOT NULL,
                        `unknown_size_files` INTEGER NOT NULL,
                        `excluded_items` INTEGER NOT NULL,
                        `error_count` INTEGER NOT NULL,
                        `error_message` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )

                // 2. Tabella scanned_documents
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `scanned_documents` (
                        `id` TEXT NOT NULL,
                        `scan_session_id` TEXT NOT NULL,
                        `backup_folder_id` TEXT NOT NULL,
                        `document_id` TEXT NOT NULL,
                        `document_uri` TEXT NOT NULL,
                        `relative_path` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `mime_type` TEXT,
                        `size_bytes` INTEGER,
                        `last_modified_timestamp` INTEGER,
                        `is_directory` INTEGER NOT NULL,
                        `is_excluded` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_scanned_documents_session_folder_doc` ON `scanned_documents` (`scan_session_id`, `backup_folder_id`, `document_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_scanned_documents_session` ON `scanned_documents` (`scan_session_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_scanned_documents_folder` ON `scanned_documents` (`backup_folder_id`)"
                )

                // 3. Tabella pending_directories
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pending_directories` (
                        `id` TEXT NOT NULL,
                        `scan_session_id` TEXT NOT NULL,
                        `backup_folder_id` TEXT NOT NULL,
                        `tree_uri` TEXT NOT NULL,
                        `document_id` TEXT NOT NULL,
                        `relative_path` TEXT NOT NULL,
                        `enqueue_timestamp` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_directories_session_folder_doc` ON `pending_directories` (`scan_session_id`, `backup_folder_id`, `document_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_directories_session` ON `pending_directories` (`scan_session_id`)"
                )

                // 4. Tabella scan_errors
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `scan_errors` (
                        `id` TEXT NOT NULL,
                        `scan_session_id` TEXT NOT NULL,
                        `backup_folder_id` TEXT NOT NULL,
                        `document_id` TEXT,
                        `uri` TEXT,
                        `error_type` TEXT NOT NULL,
                        `message` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_scan_errors_session` ON `scan_errors` (`scan_session_id`)"
                )
            }
        }

        /**
         * Restituisce l'istanza singleton del database SQLite con migrazioni configurate.
         */
        fun getInstance(context: Context): BackupDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BackupDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * Crea un database in-memory per scopi di testing.
         */
        fun createInMemoryDatabase(context: Context): BackupDatabase {
            return Room.inMemoryDatabaseBuilder(
                context,
                BackupDatabase::class.java
            )
                .allowMainThreadQueries()
                .build()
        }
    }
}
