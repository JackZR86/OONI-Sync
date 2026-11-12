package com.androidbackup.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.androidbackup.app.data.local.dao.FolderDao
import com.androidbackup.app.data.local.database.BackupDatabase
import com.androidbackup.app.data.local.entity.BackupFolderEntity
import com.androidbackup.app.data.model.Priority
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Test di integrazione per FolderDao su vero database SQLite Room in-memory (Fase 1B.1).
 * Verifica le operazioni CRUD, vincolo di unicità su tree_uri, aggiornamenti atomici e Flow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class FolderDaoTest {

    private lateinit var database: BackupDatabase
    private lateinit var dao: FolderDao

    @Before
    fun initDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BackupDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.folderDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testInsertAndObserveFolders() = runBlocking {
        // Inizialmente vuoto
        val initialList = dao.getFolders()
        assertTrue("Inizialmente la lista cartelle è vuota", initialList.isEmpty())

        val folder1 = BackupFolderEntity(
            id = UUID.randomUUID().toString(),
            treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADCIM",
            displayName = "Fotocamera",
            authority = "com.android.externalstorage.documents",
            addedTimestamp = System.currentTimeMillis(),
            priority = Priority.ALTA.name,
            protectFromEviction = true,
            enabled = true
        )

        val rowId = dao.insertFolder(folder1)
        assertTrue("Inserimento riuscito", rowId > 0)

        // Lettura diretta
        val loaded = dao.getFolders()
        assertEquals(1, loaded.size)
        assertEquals("Fotocamera", loaded[0].displayName)
        assertEquals(folder1.treeUri, loaded[0].treeUri)
        assertEquals(Priority.ALTA.name, loaded[0].priority)
        assertTrue(loaded[0].protectFromEviction)

        // Lettura via Flow
        val flowList = dao.observeFolders().first()
        assertEquals(1, flowList.size)
        assertEquals("Fotocamera", flowList[0].displayName)
    }

    @Test
    fun testUniqueTreeUriConstraintRejectsDuplicates() = runBlocking {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments"
        val folder1 = BackupFolderEntity(
            id = UUID.randomUUID().toString(),
            treeUri = uri,
            displayName = "Documenti 1",
            authority = "com.android.externalstorage.documents",
            addedTimestamp = System.currentTimeMillis(),
            priority = Priority.NORMALE.name,
            protectFromEviction = false,
            enabled = true
        )
        dao.insertFolder(folder1)

        val folderDuplicate = BackupFolderEntity(
            id = UUID.randomUUID().toString(),
            treeUri = uri, // Stesso URI
            displayName = "Documenti Duplicato",
            authority = "com.android.externalstorage.documents",
            addedTimestamp = System.currentTimeMillis(),
            priority = Priority.ALTA.name,
            protectFromEviction = true,
            enabled = true
        )

        try {
            dao.insertFolder(folderDuplicate)
            fail("L'inserimento con lo stesso treeUri deve sollevare SQLiteConstraintException per violazione dell'indice unico")
        } catch (e: SQLiteConstraintException) {
            // Successo: il vincolo UNIQUE ha protetto il database
            assertTrue(e.message?.contains("UNIQUE") == true || e.message?.contains("tree_uri") == true)
        }

        // Il database contiene solo la prima cartella
        val allFolders = dao.getFolders()
        assertEquals(1, allFolders.size)
        assertEquals("Documenti 1", allFolders[0].displayName)
    }

    @Test
    fun testUpdatePriorityAndEvictionProtection() = runBlocking {
        val folder = BackupFolderEntity(
            id = "test-id-1",
            treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownloads",
            displayName = "Download",
            authority = "com.android.externalstorage.documents",
            addedTimestamp = System.currentTimeMillis(),
            priority = Priority.BASSA.name,
            protectFromEviction = false,
            enabled = true
        )
        dao.insertFolder(folder)

        // Aggiorna priorità ad ALTA
        dao.updatePriority("test-id-1", Priority.ALTA.name)
        val updatedPrio = dao.getFolderById("test-id-1")
        assertEquals(Priority.ALTA.name, updatedPrio?.priority)
        assertFalse(updatedPrio?.protectFromEviction ?: true)

        // Aggiorna protezione da eliminazione a true
        dao.updateEvictionProtection("test-id-1", true)
        val updatedProt = dao.getFolderById("test-id-1")
        assertEquals(Priority.ALTA.name, updatedProt?.priority)
        assertTrue(updatedProt?.protectFromEviction ?: false)
    }

    @Test
    fun testDeleteFolderByIdAndUri() = runBlocking {
        val f1 = BackupFolderEntity(
            id = "id-1",
            treeUri = "content://com.android.providers.media.documents/tree/image%3A",
            displayName = "Immagini",
            authority = "com.android.providers.media.documents",
            addedTimestamp = System.currentTimeMillis(),
            priority = Priority.NORMALE.name,
            protectFromEviction = false,
            enabled = true
        )
        val f2 = BackupFolderEntity(
            id = "id-2",
            treeUri = "content://com.android.providers.media.documents/tree/audio%3A",
            displayName = "Audio",
            authority = "com.android.providers.media.documents",
            addedTimestamp = System.currentTimeMillis(),
            priority = Priority.BASSA.name,
            protectFromEviction = false,
            enabled = true
        )
        dao.insertFolder(f1)
        dao.insertFolder(f2)
        assertEquals(2, dao.getFolders().size)

        // Cancella f1 per ID
        dao.deleteFolderById("id-1")
        val remainingAfterId = dao.getFolders()
        assertEquals(1, remainingAfterId.size)
        assertEquals("id-2", remainingAfterId[0].id)

        // Cancella f2 per URI
        dao.deleteFolderByUri(f2.treeUri)
        val emptyList = dao.getFolders()
        assertTrue("La lista deve essere vuota", emptyList.isEmpty())
    }
}
