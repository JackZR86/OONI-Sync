package com.androidbackup.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.androidbackup.app.data.local.database.BackupDatabase
import com.androidbackup.app.data.local.entity.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Test di migrazione Room Database per Android Backup (Fase 1B.2 + Fase 1B.3A).
 *
 * Dimostra:
 * 1. Migrazione SQLite nativa 1 -> 2 (tabella backup_folders e indice univoco su tree_uri).
 * 2. Migrazione SQLite nativa 2 -> 3 (tabelle scan_sessions, scanned_documents, pending_directories, scan_errors con indici e vincoli).
 * 3. Migrazione SQLite nativa 3 -> 4 (nuova tabella file_fingerprints ed estensione non distruttiva di scan_sessions, scanned_documents, scan_errors).
 * 4. Preservazione integrale di tutti i dati preesistenti da v1, v2 e v3 con valori di default coerenti.
 * 5. Percorso completo di migrazione 1 -> 2 -> 3 -> 4 aperto con successo dal runtime di Room v4 e piena operatività di tutti e 7 i DAO.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DatabaseMigrationTest {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private lateinit var openHelper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase
    private var roomDatabase: BackupDatabase? = null

    // Schema SQL ESATTO estratto da 1.json
    private val v1AppSettingsSql = """
        CREATE TABLE IF NOT EXISTS `app_settings` (
            `id` INTEGER NOT NULL,
            `frequency` TEXT NOT NULL,
            `network_policy` TEXT NOT NULL,
            `reserve_space_mb` INTEGER NOT NULL,
            `fifo_rotation_enabled` INTEGER NOT NULL,
            `allow_cloud_only_eviction` INTEGER NOT NULL,
            `require_charging` INTEGER NOT NULL,
            `last_modified_timestamp` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
    """.trimIndent()

    private val v1ExclusionPatternsSql = """
        CREATE TABLE IF NOT EXISTS `exclusion_patterns` (
            `pattern` TEXT NOT NULL,
            `position` INTEGER NOT NULL,
            PRIMARY KEY(`pattern`)
        )
    """.trimIndent()

    private val v2BackupFoldersSql = """
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

    private val v2BackupFoldersIndexSql = """
        CREATE UNIQUE INDEX IF NOT EXISTS `index_backup_folders_tree_uri` ON `backup_folders` (`tree_uri`)
    """.trimIndent()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = context.getDatabasePath("test_migration.db")
        if (dbFile.exists()) {
            dbFile.delete()
        }

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("test_migration.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(sqLiteDb: SupportSQLiteDatabase) {
                    // Crea esattamente lo schema v1 iniziale
                    sqLiteDb.execSQL(v1AppSettingsSql)
                    sqLiteDb.execSQL(v1ExclusionPatternsSql)
                    sqLiteDb.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                    sqLiteDb.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, '8e3c54d24f7b6adbc4594c96a324901f')")
                }

                override fun onUpgrade(
                    sqLiteDb: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int
                ) {
                    // Gestito nei test specifici
                }
            })
            .build()

        openHelper = FrameworkSQLiteOpenHelperFactory().create(config)
        db = openHelper.writableDatabase
    }

    @After
    fun tearDown() {
        if (roomDatabase != null && roomDatabase!!.isOpen) {
            roomDatabase!!.close()
        }
        if (::db.isInitialized && db.isOpen) {
            db.close()
        }
        if (::openHelper.isInitialized) {
            openHelper.close()
        }
        if (::dbFile.isInitialized && dbFile.exists()) {
            dbFile.delete()
        }
    }

    @Test
    fun testMigration1To2PreservesSettingsAndCreatesBackupFoldersTableWithUniqueIndex() {
        val testTimestamp = 1730000000000L

        // 1. Popola il database v1 con record reali coerenti con 1.json
        db.execSQL("""
            INSERT INTO app_settings (
                id, frequency, network_policy, reserve_space_mb,
                fifo_rotation_enabled, allow_cloud_only_eviction, require_charging, last_modified_timestamp
            ) VALUES (
                ${AppSettingsEntity.DEFAULT_ID}, 'FORTY_EIGHT_HOURS', 'ANY_NETWORK', 250,
                1, 0, 1, $testTimestamp
            )
        """.trimIndent())

        db.execSQL("INSERT INTO exclusion_patterns (pattern, position) VALUES ('cache', 0)")
        db.execSQL("INSERT INTO exclusion_patterns (pattern, position) VALUES ('*.tmp', 1)")
        db.execSQL("INSERT INTO exclusion_patterns (pattern, position) VALUES ('.thumbnails', 2)")

        // 2. Esegui la migrazione ufficiale MIGRATION_1_2
        BackupDatabase.MIGRATION_1_2.migrate(db)

        // 3. Verifica che i dati di app_settings siano rimasti intatti al 100%
        val settingsCursor = db.query("SELECT * FROM app_settings WHERE id = ${AppSettingsEntity.DEFAULT_ID}")
        assertTrue("Il record app_settings deve esistere dopo la migrazione", settingsCursor.moveToFirst())
        assertEquals(AppSettingsEntity.DEFAULT_ID, settingsCursor.getInt(settingsCursor.getColumnIndexOrThrow("id")))
        assertEquals("FORTY_EIGHT_HOURS", settingsCursor.getString(settingsCursor.getColumnIndexOrThrow("frequency")))
        assertEquals("ANY_NETWORK", settingsCursor.getString(settingsCursor.getColumnIndexOrThrow("network_policy")))
        assertEquals(250, settingsCursor.getInt(settingsCursor.getColumnIndexOrThrow("reserve_space_mb")))
        assertEquals(1, settingsCursor.getInt(settingsCursor.getColumnIndexOrThrow("fifo_rotation_enabled")))
        assertEquals(0, settingsCursor.getInt(settingsCursor.getColumnIndexOrThrow("allow_cloud_only_eviction")))
        assertEquals(1, settingsCursor.getInt(settingsCursor.getColumnIndexOrThrow("require_charging")))
        assertEquals(testTimestamp, settingsCursor.getLong(settingsCursor.getColumnIndexOrThrow("last_modified_timestamp")))
        settingsCursor.close()

        // 4. Verifica che i dati di exclusion_patterns siano rimasti intatti al 100%
        val patternsCursor = db.query("SELECT pattern, position FROM exclusion_patterns ORDER BY position ASC")
        assertEquals("Devono esserci 3 pattern", 3, patternsCursor.count)
        patternsCursor.moveToFirst()
        assertEquals("cache", patternsCursor.getString(0))
        assertEquals(0, patternsCursor.getInt(1))
        patternsCursor.moveToNext()
        assertEquals("*.tmp", patternsCursor.getString(0))
        assertEquals(1, patternsCursor.getInt(1))
        patternsCursor.moveToNext()
        assertEquals(".thumbnails", patternsCursor.getString(0))
        assertEquals(2, patternsCursor.getInt(1))
        patternsCursor.close()

        // 5. Verifica che la nuova tabella backup_folders esista e consenta inserimenti
        db.execSQL("""
            INSERT INTO backup_folders (id, tree_uri, display_name, authority, added_timestamp, priority, protect_from_eviction, enabled)
            VALUES ('folder-1', 'content://test.auth/tree/dcim', 'DCIM', 'test.auth', 123456789, 'ALTA', 1, 1)
        """.trimIndent())

        val folderCursor = db.query("SELECT * FROM backup_folders WHERE id = 'folder-1'")
        assertTrue(folderCursor.moveToFirst())
        assertEquals("folder-1", folderCursor.getString(folderCursor.getColumnIndexOrThrow("id")))
        assertEquals("content://test.auth/tree/dcim", folderCursor.getString(folderCursor.getColumnIndexOrThrow("tree_uri")))
        assertEquals("DCIM", folderCursor.getString(folderCursor.getColumnIndexOrThrow("display_name")))
        assertEquals("ALTA", folderCursor.getString(folderCursor.getColumnIndexOrThrow("priority")))
        assertEquals(1, folderCursor.getInt(folderCursor.getColumnIndexOrThrow("protect_from_eviction")))
        assertEquals(1, folderCursor.getInt(folderCursor.getColumnIndexOrThrow("enabled")))
        folderCursor.close()

        // 6. Verifica che l'indice univoco su tree_uri blocchi categoricamente inserimenti duplicati
        try {
            db.execSQL("""
                INSERT INTO backup_folders (id, tree_uri, display_name, authority, added_timestamp, priority, protect_from_eviction, enabled)
                VALUES ('folder-2', 'content://test.auth/tree/dcim', 'DCIM Duplicato', 'test.auth', 987654321, 'BASSA', 0, 1)
            """.trimIndent())
            fail("L'inserimento di un tree_uri duplicato deve fallire con SQLiteConstraintException a causa dell'indice univoco")
        } catch (e: SQLiteConstraintException) {
            assertTrue("Il vincolo di unicità ha impedito l'inserimento duplicato", true)
        }
    }

    @Test
    fun testMigration2To3CreatesScanTablesAndPreservesExistingData() {
        val testTimestamp = 1731000000000L

        // 1. Porta il database allo stato v2 eseguendo MIGRATION_1_2
        BackupDatabase.MIGRATION_1_2.migrate(db)

        // 2. Inserisci dati v1 e v2
        db.execSQL("""
            INSERT INTO app_settings (
                id, frequency, network_policy, reserve_space_mb,
                fifo_rotation_enabled, allow_cloud_only_eviction, require_charging, last_modified_timestamp
            ) VALUES (
                ${AppSettingsEntity.DEFAULT_ID}, 'TWENTY_FOUR_HOURS', 'WIFI_ONLY', 300,
                1, 1, 1, $testTimestamp
            )
        """.trimIndent())

        db.execSQL("INSERT INTO exclusion_patterns (pattern, position) VALUES ('*.bak', 0)")
        db.execSQL("INSERT INTO exclusion_patterns (pattern, position) VALUES ('.git', 1)")

        db.execSQL("""
            INSERT INTO backup_folders (id, tree_uri, display_name, authority, added_timestamp, priority, protect_from_eviction, enabled)
            VALUES ('folder-v2-1', 'content://com.android.externalstorage.documents/tree/primary%3ADocuments', 'Documents', 'com.android.externalstorage.documents', 1731000100000, 'NORMALE', 1, 1)
        """.trimIndent())

        // 3. Esegui la migrazione MIGRATION_2_3
        BackupDatabase.MIGRATION_2_3.migrate(db)

        // 4. Verifica che i dati preesistenti siano preservati
        val settingsCursor = db.query("SELECT * FROM app_settings WHERE id = ${AppSettingsEntity.DEFAULT_ID}")
        assertTrue(settingsCursor.moveToFirst())
        assertEquals("TWENTY_FOUR_HOURS", settingsCursor.getString(settingsCursor.getColumnIndexOrThrow("frequency")))
        assertEquals(300, settingsCursor.getInt(settingsCursor.getColumnIndexOrThrow("reserve_space_mb")))
        settingsCursor.close()

        val patternCursor = db.query("SELECT pattern FROM exclusion_patterns ORDER BY position ASC")
        assertEquals(2, patternCursor.count)
        patternCursor.moveToFirst()
        assertEquals("*.bak", patternCursor.getString(0))
        patternCursor.moveToNext()
        assertEquals(".git", patternCursor.getString(0))
        patternCursor.close()

        val folderCursor = db.query("SELECT display_name, protect_from_eviction FROM backup_folders WHERE id = 'folder-v2-1'")
        assertTrue(folderCursor.moveToFirst())
        assertEquals("Documents", folderCursor.getString(0))
        assertEquals(1, folderCursor.getInt(1))
        folderCursor.close()

        // 5. Inserisci e verifica record nelle nuove tabelle v3
        // 5a. scan_sessions
        db.execSQL("""
            INSERT INTO scan_sessions (id, started_timestamp, completed_timestamp, status, total_files, total_directories, known_total_bytes, unknown_size_files, excluded_items, error_count, error_message)
            VALUES ('sess-1', 1000, 2000, 'COMPLETED', 15, 3, 4096000, 1, 2, 0, NULL)
        """.trimIndent())

        val sessionCursor = db.query("SELECT status, total_files, known_total_bytes FROM scan_sessions WHERE id = 'sess-1'")
        assertTrue(sessionCursor.moveToFirst())
        assertEquals("COMPLETED", sessionCursor.getString(0))
        assertEquals(15, sessionCursor.getInt(1))
        assertEquals(4096000L, sessionCursor.getLong(2))
        sessionCursor.close()

        // 5b. scanned_documents
        db.execSQL("""
            INSERT INTO scanned_documents (id, scan_session_id, backup_folder_id, document_id, document_uri, relative_path, display_name, mime_type, size_bytes, last_modified_timestamp, is_directory, is_excluded)
            VALUES ('doc-1', 'sess-1', 'folder-v2-1', 'doc_primary:1', 'content://test/doc1', 'report.pdf', 'report.pdf', 'application/pdf', 1024, 1500, 0, 0)
        """.trimIndent())

        val docCursor = db.query("SELECT display_name, size_bytes FROM scanned_documents WHERE id = 'doc-1'")
        assertTrue(docCursor.moveToFirst())
        assertEquals("report.pdf", docCursor.getString(0))
        assertEquals(1024L, docCursor.getLong(1))
        docCursor.close()

        // 5c. pending_directories
        db.execSQL("""
            INSERT INTO pending_directories (id, scan_session_id, backup_folder_id, tree_uri, document_id, relative_path, enqueue_timestamp)
            VALUES ('pend-1', 'sess-1', 'folder-v2-1', 'content://test/tree', 'dir_1', 'subfolder', 1100)
        """.trimIndent())

        val pendCursor = db.query("SELECT document_id, relative_path FROM pending_directories WHERE id = 'pend-1'")
        assertTrue(pendCursor.moveToFirst())
        assertEquals("dir_1", pendCursor.getString(0))
        assertEquals("subfolder", pendCursor.getString(1))
        pendCursor.close()

        // 5d. scan_errors
        db.execSQL("""
            INSERT INTO scan_errors (id, scan_session_id, backup_folder_id, document_id, uri, error_type, message, timestamp)
            VALUES ('err-1', 'sess-1', 'folder-v2-1', 'doc_err', 'content://test/err', 'PERMISSION_REVOKED', 'Autorizzazione revocata', 1200)
        """.trimIndent())

        val errCursor = db.query("SELECT error_type, message FROM scan_errors WHERE id = 'err-1'")
        assertTrue(errCursor.moveToFirst())
        assertEquals("PERMISSION_REVOKED", errCursor.getString(0))
        assertEquals("Autorizzazione revocata", errCursor.getString(1))
        errCursor.close()

        // 6. Verifica che l'indice univoco su scanned_documents (scan_session_id, backup_folder_id, document_id) impedisca duplicati
        try {
            db.execSQL("""
                INSERT INTO scanned_documents (id, scan_session_id, backup_folder_id, document_id, document_uri, relative_path, display_name, mime_type, size_bytes, last_modified_timestamp, is_directory, is_excluded)
                VALUES ('doc-duplicate', 'sess-1', 'folder-v2-1', 'doc_primary:1', 'content://test/doc1_dup', 'report.pdf', 'report.pdf', 'application/pdf', 2048, 1600, 0, 0)
            """.trimIndent())
            fail("L'inserimento di un document_id duplicato nella stessa sessione e cartella deve fallire con SQLiteConstraintException")
        } catch (e: SQLiteConstraintException) {
            assertTrue("Il vincolo di unicità index_scanned_documents_session_folder_doc ha impedito il duplicato", true)
        }

        // 7. Verifica che l'indice univoco su pending_directories (scan_session_id, backup_folder_id, document_id) impedisca duplicati
        try {
            db.execSQL("""
                INSERT INTO pending_directories (id, scan_session_id, backup_folder_id, tree_uri, document_id, relative_path, enqueue_timestamp)
                VALUES ('pend-duplicate', 'sess-1', 'folder-v2-1', 'content://test/tree', 'dir_1', 'subfolder', 1200)
            """.trimIndent())
            fail("L'inserimento di una directory duplicata in coda deve fallire con SQLiteConstraintException")
        } catch (e: SQLiteConstraintException) {
            assertTrue("Il vincolo di unicità index_pending_directories_session_folder_doc ha impedito il duplicato in coda", true)
        }
    }

    @Test
    fun testMigration3To4PreservesExistingDataAndCreatesFileFingerprintsWithUniqueIndex() {
        val testTimestamp = 1732000000000L

        // 1. Porta il database allo stato v3 eseguendo MIGRATION_1_2 e MIGRATION_2_3
        BackupDatabase.MIGRATION_1_2.migrate(db)
        BackupDatabase.MIGRATION_2_3.migrate(db)

        // 2. Inserisci dati reali v1, v2 e v3 prima della migrazione v3 -> v4
        db.execSQL(
            """
            INSERT INTO app_settings (
                id, frequency, network_policy, reserve_space_mb,
                fifo_rotation_enabled, allow_cloud_only_eviction, require_charging, last_modified_timestamp
            ) VALUES (
                ${AppSettingsEntity.DEFAULT_ID}, 'TWENTY_FOUR_HOURS', 'WIFI_ONLY', 350,
                1, 0, 1, $testTimestamp
            )
            """.trimIndent()
        )
        db.execSQL("INSERT INTO exclusion_patterns (pattern, position) VALUES ('*.tmp', 0)")
        db.execSQL(
            """
            INSERT INTO backup_folders (id, tree_uri, display_name, authority, added_timestamp, priority, protect_from_eviction, enabled)
            VALUES ('folder-v3-1', 'content://test.auth/tree/photos', 'Photos', 'test.auth', $testTimestamp, 'ALTA', 1, 1)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO backup_folders (id, tree_uri, display_name, authority, added_timestamp, priority, protect_from_eviction, enabled)
            VALUES ('folder-v3-2', 'content://test.auth/tree/docs', 'Docs', 'test.auth', $testTimestamp, 'NORMALE', 0, 1)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO scan_sessions (id, started_timestamp, completed_timestamp, status, total_files, total_directories, known_total_bytes, unknown_size_files, excluded_items, error_count, error_message)
            VALUES ('sess-v3', 1000, 2000, 'COMPLETED', 42, 5, 987654, 0, 3, 1, 'Errore storico v3')
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO scanned_documents (id, scan_session_id, backup_folder_id, document_id, document_uri, relative_path, display_name, mime_type, size_bytes, last_modified_timestamp, is_directory, is_excluded)
            VALUES ('doc-v3', 'sess-v3', 'folder-v3-1', 'doc-id-1', 'content://test.auth/doc/1', 'img.jpg', 'img.jpg', 'image/jpeg', 2048, 1500, 0, 0)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO pending_directories (id, scan_session_id, backup_folder_id, tree_uri, document_id, relative_path, enqueue_timestamp)
            VALUES ('pend-v3', 'sess-v3', 'folder-v3-1', 'content://test.auth/tree/photos', 'dir-sub', 'sub', 1100)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO scan_errors (id, scan_session_id, backup_folder_id, document_id, uri, error_type, message, timestamp)
            VALUES ('err-v3', 'sess-v3', 'folder-v3-1', 'doc-err', 'content://test.auth/err', 'DIRECTORY_READ_ERROR', 'Errore storico discovery', 1200)
            """.trimIndent()
        )

        // 3. Esegui MIGRATION_3_4
        BackupDatabase.MIGRATION_3_4.migrate(db)

        // 4. Verifica preservazione integrale dei record v1/v2/v3 e applicazione dei valori di default v4
        val sessionCursor = db.query(
            """
            SELECT status, total_files, scan_phase, fingerprint_mode, fingerprint_max_cache_age_ms,
                   fingerprints_total, fingerprints_computed, fingerprints_cached, fingerprints_unsupported,
                   fingerprints_failed, fingerprints_bytes_hashed
            FROM scan_sessions WHERE id = 'sess-v3'
            """.trimIndent()
        )
        assertTrue(sessionCursor.moveToFirst())
        assertEquals("COMPLETED", sessionCursor.getString(0))
        assertEquals(42, sessionCursor.getInt(1))
        assertEquals("COMPLETED", sessionCursor.getString(2))
        assertEquals("INCREMENTAL", sessionCursor.getString(3))
        assertTrue(sessionCursor.isNull(4))
        assertEquals(0, sessionCursor.getInt(5))
        assertEquals(0, sessionCursor.getInt(6))
        assertEquals(0, sessionCursor.getInt(7))
        assertEquals(0, sessionCursor.getInt(8))
        assertEquals(0, sessionCursor.getInt(9))
        assertEquals(0L, sessionCursor.getLong(10))
        sessionCursor.close()

        val docCursor = db.query(
            """
            SELECT display_name, sha256_hex, fingerprint_status, fingerprint_error, fingerprint_timestamp, hashed_bytes_read
            FROM scanned_documents WHERE id = 'doc-v3'
            """.trimIndent()
        )
        assertTrue(docCursor.moveToFirst())
        assertEquals("img.jpg", docCursor.getString(0))
        assertTrue(docCursor.isNull(1))
        assertEquals("NOT_REQUIRED", docCursor.getString(2))
        assertTrue(docCursor.isNull(3))
        assertTrue(docCursor.isNull(4))
        assertTrue(docCursor.isNull(5))
        docCursor.close()

        val errCursor = db.query("SELECT error_type, error_phase, severity FROM scan_errors WHERE id = 'err-v3'")
        assertTrue(errCursor.moveToFirst())
        assertEquals("DIRECTORY_READ_ERROR", errCursor.getString(0))
        assertEquals("DISCOVERY", errCursor.getString(1))
        assertEquals("ERROR", errCursor.getString(2))
        errCursor.close()

        // 5. Verifica tabella file_fingerprints e isolamento per (backup_folder_id, document_id)
        db.execSQL(
            """
            INSERT INTO file_fingerprints (
                id, backup_folder_id, document_id, document_uri, relative_path, mime_type,
                size_bytes, last_modified_timestamp, computed_bytes, sha256_hex,
                last_content_hashed_timestamp, last_metadata_matched_timestamp, last_seen_session_id
            ) VALUES (
                'fp-1', 'folder-v3-1', 'shared-doc-id', 'content://test.auth/doc/shared', 'img.jpg', 'image/jpeg',
                2048, 1500, 2048, 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
                1732000000000, 1732000000000, 'sess-v3'
            )
            """.trimIndent()
        )

        // Stesso document_id in un'altra backup_folder_id deve essere consentito
        db.execSQL(
            """
            INSERT INTO file_fingerprints (
                id, backup_folder_id, document_id, document_uri, relative_path, mime_type,
                size_bytes, last_modified_timestamp, computed_bytes, sha256_hex,
                last_content_hashed_timestamp, last_metadata_matched_timestamp, last_seen_session_id
            ) VALUES (
                'fp-2', 'folder-v3-2', 'shared-doc-id', 'content://test.auth/doc/shared2', 'img.jpg', 'image/jpeg',
                2048, 1500, 2048, 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
                1732000000000, 1732000000000, 'sess-v3'
            )
            """.trimIndent()
        )

        // Duplicato sulla medesima coppia (backup_folder_id, document_id) deve fallire per indice univoco
        try {
            db.execSQL(
                """
                INSERT INTO file_fingerprints (
                    id, backup_folder_id, document_id, document_uri, relative_path, mime_type,
                    size_bytes, last_modified_timestamp, computed_bytes, sha256_hex,
                    last_content_hashed_timestamp, last_metadata_matched_timestamp, last_seen_session_id
                ) VALUES (
                    'fp-dup', 'folder-v3-1', 'shared-doc-id', 'content://test.auth/doc/shared', 'img.jpg', 'image/jpeg',
                    2048, 1500, 2048, 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
                    1732000001000, 1732000001000, 'sess-v3'
                )
                """.trimIndent()
            )
            fail("L'indice univoco index_file_fingerprints_folder_doc avrebbe dovuto impedire il duplicato")
        } catch (_: SQLiteConstraintException) {
            assertTrue(true)
        }
    }

    @Test
    fun testFullMigrationPath1To2To3To4OpensSuccessfullyWithRoomV4AndAllDaosOperate() = runBlocking {
        val testTimestamp = 1730005555555L

        // 1. Popola i dati v1 prima della migrazione
        db.execSQL("""
            INSERT INTO app_settings (
                id, frequency, network_policy, reserve_space_mb,
                fifo_rotation_enabled, allow_cloud_only_eviction, require_charging, last_modified_timestamp
            ) VALUES (
                ${AppSettingsEntity.DEFAULT_ID}, 'TWELVE_HOURS', 'WIFI_ONLY', 500,
                0, 1, 0, $testTimestamp
            )
        """.trimIndent())

        db.execSQL("INSERT INTO exclusion_patterns (pattern, position) VALUES ('.git', 0)")
        db.execSQL("INSERT INTO exclusion_patterns (pattern, position) VALUES ('node_modules', 1)")

        // Chiude la connessione SQLite a basso livello per consentire l'apertura tramite Room
        db.close()
        openHelper.close()

        // 2. Apri il database v1 con Room runtime v4 applicando MIGRATION_1_2, MIGRATION_2_3 e MIGRATION_3_4
        val roomDb = Room.databaseBuilder(
            context,
            BackupDatabase::class.java,
            "test_migration.db"
        )
            .addMigrations(
                BackupDatabase.MIGRATION_1_2,
                BackupDatabase.MIGRATION_2_3,
                BackupDatabase.MIGRATION_3_4
            )
            .allowMainThreadQueries()
            .build()
        roomDatabase = roomDb

        // 3. Verifica lettura impostazioni v1 tramite SettingsDao
        val settings = roomDb.settingsDao().getSettings()
        assertNotNull("Le impostazioni devono essere lette dal SettingsDao", settings)
        assertEquals("TWELVE_HOURS", settings!!.frequency)
        assertEquals("WIFI_ONLY", settings.networkPolicy)
        assertEquals(500, settings.reserveSpaceMb)
        assertFalse(settings.fifoRotationEnabled)
        assertTrue(settings.allowCloudOnlyEviction)
        assertFalse(settings.requireCharging)
        assertEquals(testTimestamp, settings.lastModifiedTimestamp)

        // 4. Verifica lettura pattern v1 tramite SettingsDao
        val patterns = roomDb.settingsDao().getPatterns()
        assertEquals(2, patterns.size)
        assertEquals(".git", patterns[0].pattern)
        assertEquals("node_modules", patterns[1].pattern)

        // 5. Verifica inserimento e lettura cartella tramite FolderDao v2
        val folderEntity = BackupFolderEntity(
            id = "room-migrated-1",
            treeUri = "content://com.android.externalstorage.documents/tree/primary%3ARoomTest",
            displayName = "RoomTest",
            authority = "com.android.externalstorage.documents",
            addedTimestamp = System.currentTimeMillis(),
            priority = "ALTA",
            protectFromEviction = true,
            enabled = true
        )
        roomDb.folderDao().insertFolder(folderEntity)

        val retrievedFolders = roomDb.folderDao().getFolders()
        assertEquals(1, retrievedFolders.size)
        assertEquals("room-migrated-1", retrievedFolders[0].id)
        assertEquals("RoomTest", retrievedFolders[0].displayName)
        assertTrue(retrievedFolders[0].protectFromEviction)

        // 6. Verifica ScanSessionDao v3
        val session = ScanSessionEntity(
            id = "sess-room-1",
            startedTimestamp = 1000L,
            completedTimestamp = 1500L,
            status = ScanSessionStatus.COMPLETED.name,
            totalFiles = 10,
            totalDirectories = 2,
            knownTotalBytes = 10240L,
            unknownSizeFiles = 0,
            excludedItems = 1,
            errorCount = 0
        )
        roomDb.scanSessionDao().insertSession(session)
        val loadedSession = roomDb.scanSessionDao().getSessionById("sess-room-1")
        assertNotNull(loadedSession)
        assertEquals(ScanSessionStatus.COMPLETED.name, loadedSession!!.status)
        assertEquals(10, loadedSession.totalFiles)
        assertEquals(10240L, loadedSession.knownTotalBytes)

        // 7. Verifica ScannedDocumentDao v3
        val doc1 = ScannedDocumentEntity(
            id = "doc-room-1",
            scanSessionId = "sess-room-1",
            backupFolderId = "room-migrated-1",
            documentId = "raw-doc-1",
            documentUri = "content://com.android.externalstorage.documents/document/raw-doc-1",
            relativePath = "images/photo.jpg",
            displayName = "photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 204800L,
            lastModifiedTimestamp = 2000L,
            isDirectory = false,
            isExcluded = false
        )
        roomDb.scannedDocumentDao().insertDocument(doc1)
        val loadedDocs = roomDb.scannedDocumentDao().getDocumentsForSession("sess-room-1")
        assertEquals(1, loadedDocs.size)
        assertEquals("photo.jpg", loadedDocs[0].displayName)
        assertEquals(204800L, loadedDocs[0].sizeBytes)

        // 8. Verifica PendingDirectoryDao v3
        val pend = PendingDirectoryEntity(
            id = "pend-room-1",
            scanSessionId = "sess-room-1",
            backupFolderId = "room-migrated-1",
            treeUri = "content://com.android.externalstorage.documents/tree/primary%3ARoomTest",
            documentId = "dir-sub-1",
            relativePath = "images/sub",
            enqueueTimestamp = 1010L
        )
        roomDb.pendingDirectoryDao().insertDirectory(pend)
        val nextPend = roomDb.pendingDirectoryDao().getNextPendingDirectory("sess-room-1")
        assertNotNull(nextPend)
        assertEquals("dir-sub-1", nextPend!!.documentId)
        roomDb.pendingDirectoryDao().deletePendingDirectoryById(nextPend.id)
        assertNull(roomDb.pendingDirectoryDao().getNextPendingDirectory("sess-room-1"))

        // 9. Verifica ScanErrorDao v4 (distinzione severità ERROR / UNSUPPORTED / WARNING e fase)
        val error = ScanErrorEntity(
            id = "err-room-1",
            scanSessionId = "sess-room-1",
            backupFolderId = "room-migrated-1",
            documentId = "bad-doc",
            uri = "content://bad-uri",
            errorType = "CURSOR_ERROR",
            message = "Errore cursore test",
            timestamp = 1020L,
            errorPhase = ScanErrorPhase.DISCOVERY.name,
            severity = ScanErrorSeverity.ERROR.name
        )
        val unsupportedNote = ScanErrorEntity(
            id = "err-room-virtual",
            scanSessionId = "sess-room-1",
            backupFolderId = "room-migrated-1",
            documentId = "virtual-doc",
            uri = "content://virtual-uri",
            errorType = "VIRTUAL_DOCUMENT_UNSUPPORTED",
            message = "Documento virtuale non supportato",
            timestamp = 1025L,
            errorPhase = ScanErrorPhase.FINGERPRINTING.name,
            severity = ScanErrorSeverity.UNSUPPORTED.name
        )
        roomDb.scanErrorDao().insertErrors(listOf(error, unsupportedNote))
        val loadedErrors = roomDb.scanErrorDao().getErrorsForSession("sess-room-1")
        assertEquals(2, loadedErrors.size)
        assertEquals(1, roomDb.scanErrorDao().countErrorsForSession("sess-room-1"))
        assertEquals(1, roomDb.scanErrorDao().countIssuesBySeverity("sess-room-1", ScanErrorSeverity.UNSUPPORTED.name))
        assertEquals(1, roomDb.scanErrorDao().countDiscoveryErrorsForFolder("sess-room-1", "room-migrated-1"))

        // 10. Verifica FileFingerprintDao v4
        val fp1 = FileFingerprintEntity(
            id = "fp-room-1",
            backupFolderId = "room-migrated-1",
            documentId = "raw-doc-1",
            documentUri = "content://com.android.externalstorage.documents/document/raw-doc-1",
            relativePath = "images/photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 204800L,
            lastModifiedTimestamp = 2000L,
            computedBytes = 204800L,
            sha256Hex = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            lastContentHashedTimestamp = 5000L,
            lastMetadataMatchedTimestamp = 5000L,
            lastSeenSessionId = "sess-room-1"
        )
        val fpStale = FileFingerprintEntity(
            id = "fp-room-stale",
            backupFolderId = "room-migrated-1",
            documentId = "deleted-doc-99",
            documentUri = "content://com.android.externalstorage.documents/document/deleted-doc-99",
            relativePath = "images/old.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 100L,
            lastModifiedTimestamp = 1000L,
            computedBytes = 100L,
            sha256Hex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            lastContentHashedTimestamp = 4000L,
            lastMetadataMatchedTimestamp = 4000L,
            lastSeenSessionId = "sess-old"
        )
        roomDb.fileFingerprintDao().upsertFingerprints(listOf(fp1, fpStale))

        val fetchedFp = roomDb.fileFingerprintDao().getByFolderAndDocument("room-migrated-1", "raw-doc-1")
        assertNotNull(fetchedFp)
        assertEquals(fp1.sha256Hex, fetchedFp!!.sha256Hex)

        // updateMetadataObservation non deve alterare lastContentHashedTimestamp
        roomDb.fileFingerprintDao().updateMetadataObservation(
            folderId = "room-migrated-1",
            documentId = "raw-doc-1",
            documentUri = "content://com.android.externalstorage.documents/document/raw-doc-1",
            relativePath = "images/photo.jpg",
            sessionId = "sess-room-2",
            matchedTimestamp = 9000L
        )
        val updatedFp = roomDb.fileFingerprintDao().getByFolderAndDocument("room-migrated-1", "raw-doc-1")!!
        assertEquals(5000L, updatedFp.lastContentHashedTimestamp)
        assertEquals(9000L, updatedFp.lastMetadataMatchedTimestamp)
        assertEquals("sess-room-2", updatedFp.lastSeenSessionId)

        // deleteStaleForFolderNotInSession su sess-room-1 (scanPhase=DISCOVERY ed errore DISCOVERY) NON deve cancellare nulla
        val blockedDelete = roomDb.fileFingerprintDao().deleteStaleForFolderNotInSession("room-migrated-1", "sess-room-1")
        assertEquals(0, blockedDelete)
        assertEquals(2, roomDb.fileFingerprintDao().countByFolder("room-migrated-1"))

        // Su una sessione completata senza errori Discovery per la cartella, rimuove solo deleted-doc-99 e mantiene raw-doc-1
        val cleanCompletedSession = ScanSessionEntity(
            id = "sess-room-clean",
            startedTimestamp = 6000L,
            completedTimestamp = 7000L,
            status = ScanSessionStatus.COMPLETED.name,
            scanPhase = ScanPhase.COMPLETED.name,
            totalFiles = 1
        )
        roomDb.scanSessionDao().insertSession(cleanCompletedSession)
        roomDb.scannedDocumentDao().insertDocument(
            doc1.copy(id = "doc-room-clean-1", scanSessionId = "sess-room-clean")
        )

        val deletedStale = roomDb.fileFingerprintDao().deleteStaleForFolderNotInSession("room-migrated-1", "sess-room-clean")
        assertEquals(1, deletedStale)
        assertEquals(1, roomDb.fileFingerprintDao().countByFolder("room-migrated-1"))
        assertNotNull(roomDb.fileFingerprintDao().getByFolderAndDocument("room-migrated-1", "raw-doc-1"))
        assertNull(roomDb.fileFingerprintDao().getByFolderAndDocument("room-migrated-1", "deleted-doc-99"))

        val deletedByFolder = roomDb.fileFingerprintDao().deleteByFolderId("room-migrated-1")
        assertEquals(1, deletedByFolder)
        assertEquals(0, roomDb.fileFingerprintDao().countByFolder("room-migrated-1"))
    }
}
