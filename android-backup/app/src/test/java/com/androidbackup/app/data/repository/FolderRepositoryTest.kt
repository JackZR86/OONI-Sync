package com.androidbackup.app.data.repository

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.androidbackup.app.data.local.dao.FolderDao
import com.androidbackup.app.data.local.database.BackupDatabase
import com.androidbackup.app.data.local.entity.BackupFolderEntity
import com.androidbackup.app.data.local.entity.FileFingerprintEntity
import com.androidbackup.app.data.local.entity.ScanSessionEntity
import com.androidbackup.app.data.local.entity.ScanSessionStatus
import com.androidbackup.app.data.model.Priority
import com.androidbackup.app.testutils.FakeSafPermissionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Delegating FolderDao per simulare fallimenti specifici durante l'inserimento Room
 * senza dover chiudere prematuramente il database (Point 2).
 */
class DelegatingFolderDao(
    private val delegate: FolderDao,
    var shouldFailInsert: Boolean = false,
    var insertException: Exception = SQLiteConstraintException("Errore simulato inserimento Room")
) : FolderDao {
    override fun observeFolders(): Flow<List<BackupFolderEntity>> = delegate.observeFolders()
    override suspend fun getFolders(): List<BackupFolderEntity> = delegate.getFolders()
    override suspend fun getFolderById(id: String): BackupFolderEntity? = delegate.getFolderById(id)
    override suspend fun getFolderByUri(treeUri: String): BackupFolderEntity? = delegate.getFolderByUri(treeUri)
    override suspend fun insertFolder(folder: BackupFolderEntity): Long {
        if (shouldFailInsert) {
            throw insertException
        }
        return delegate.insertFolder(folder)
    }
    override suspend fun deleteFolderById(id: String): Int = delegate.deleteFolderById(id)
    override suspend fun deleteFolderByUri(treeUri: String): Int = delegate.deleteFolderByUri(treeUri)
    override suspend fun updatePriority(id: String, priority: String): Int = delegate.updatePriority(id, priority)
    override suspend fun updateEvictionProtection(id: String, protect: Boolean): Int = delegate.updateEvictionProtection(id, protect)
    override suspend fun updateEnabled(id: String, enabled: Boolean): Int = delegate.updateEnabled(id, enabled)
    override suspend fun clearAllFolders(): Int = delegate.clearAllFolders()
}

/**
 * Test di unità e integrazione per FolderRepository (Fase 1B.1 - Revisione Finale).
 *
 * Verifiche incluse:
 * 1. Aggiunta cartella SAF con acquisizione del permesso persistente in sola lettura.
 * 2. Rifiuto dei duplicati (stesso treeUri).
 * 3. Flow reattivo e verifica dinamica dell'accessibilità SAF (isAccessible).
 * 4. Procedura di compensazione automatica su fallimento inserimento (con DelegatingFolderDao).
 * 5. Gestione fallimento sia di inserimento che di compensazione: conservazione URI orfano e propagazione eccezione.
 * 6. Tracciamento reattivo permessi orfani e recupero tramite retryReleasePermission().
 * 7. Blocco del rilascio di permessi appartenenti a cartelle ancora configurate in Room.
 * 8. Ripristino autorizzazione (reauthorizeFolder) con successo e conservazione metadati.
 * 9. Rifiuto del ripristino con URI differente.
 * 10. Gestione errore di ripristino per permesso negato.
 * 11. Aggiornamento dello stato di accessibilità (refreshAccessibility) dopo revoca esterna senza alterare Room.
 * 12. Rimozione cartella a due fasi: cancellazione da Room seguita dal rilascio del permesso SAF.
 * 13. Gestione errore di rilascio permesso in removeFolder e retry.
 * 14. Aggiornamento atomico di priorità e flag di protezione da eliminazione.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class FolderRepositoryTest {

    private lateinit var database: BackupDatabase
    private lateinit var delegatingFolderDao: DelegatingFolderDao
    private lateinit var fakeSafPermissionManager: FakeSafPermissionManager
    private lateinit var repository: FolderRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BackupDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        delegatingFolderDao = DelegatingFolderDao(database.folderDao())
        fakeSafPermissionManager = FakeSafPermissionManager()
        repository = FolderRepository(
            folderDao = delegatingFolderDao,
            safPermissionManager = fakeSafPermissionManager,
            scanSessionDao = database.scanSessionDao(),
            fileFingerprintDao = database.fileFingerprintDao(),
            lifecycleCoordinator = FolderLifecycleCoordinator(),
            runInTransaction = { block -> database.withTransaction { block() } }
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testAddFolderAcquiresPersistablePermissionAndPersistsInRoom() = runBlocking {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADCIM")

        val result = repository.addFolder(
            treeUri = uri,
            priority = Priority.ALTA,
            protectFromEviction = true
        )

        assertTrue("Aggiunta cartella deve avere successo", result.isSuccess)
        val added = result.getOrThrow()
        assertEquals("Fotocamera", added.name)
        assertEquals(uri.toString(), added.path)
        assertEquals(Priority.ALTA, added.priority)
        assertTrue(added.protectFromEviction)
        assertTrue(added.isAccessible)
        assertEquals("Scansione non ancora eseguita: conteggio file a 0", 0, added.fileCount)
        assertEquals("Scansione non ancora eseguita: dimensione a 0", 0, added.totalSizeMb)

        // Verifica che il permesso SAF persistente sia stato acquisito
        assertTrue(fakeSafPermissionManager.hasPersistedReadPermission(uri))

        // Verifica lettura tramite Flow del repository
        val list = repository.foldersFlow.first()
        assertEquals(1, list.size)
        assertEquals("Fotocamera", list[0].name)
        assertTrue(list[0].isAccessible)

        // Nessun permesso orfano
        assertTrue(repository.getOrphanPermissions().isEmpty())
    }

    @Test
    fun testAddFolderFailsWhenUriAlreadyConfigured() = runBlocking {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments")

        val firstAdd = repository.addFolder(uri, Priority.NORMALE, false)
        assertTrue(firstAdd.isSuccess)

        // Tentativo di aggiungere di nuovo lo stesso URI
        val secondAdd = repository.addFolder(uri, Priority.ALTA, true)
        assertTrue("L'aggiunta di un URI già configurato deve fallire", secondAdd.isFailure)
        assertTrue(
            "Il messaggio deve indicare che la cartella è già configurata",
            secondAdd.exceptionOrNull()?.message?.contains("già configurata") == true
        )

        // Nel database deve rimanere solo una cartella
        val list = repository.getFolders()
        assertEquals(1, list.size)
    }

    @Test
    fun testAddFolderFailsGracefullyWhenSafPermissionDenied() = runBlocking {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ARestricted")
        fakeSafPermissionManager.shouldFailPermissionGrant = true
        fakeSafPermissionManager.failureErrorMessage = "Permesso negato da utente o sistema"

        val result = repository.addFolder(uri, Priority.NORMALE, false)
        assertTrue("Deve fallire se SAF non concede il permesso persistente", result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Permesso negato") == true)

        // Nessun dato inserito nel database Room
        assertTrue("Nessun record orfano deve essere salvato nel DB", repository.getFolders().isEmpty())
    }

    @Test
    fun testAddFolderCompensatesByReleasingPermissionIfInsertFails() = runBlocking {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")

        // Simuliamo il fallimento selettivo di insertFolder tramite DelegatingFolderDao
        delegatingFolderDao.shouldFailInsert = true
        delegatingFolderDao.insertException = SQLiteConstraintException("Errore simulato di scrittura DB")

        val result = repository.addFolder(uri, Priority.NORMALE, false)

        // L'operazione deve fallire
        assertTrue("L'inserimento deve fallire", result.isFailure)
        assertEquals("Errore simulato di scrittura DB", result.exceptionOrNull()?.message)

        // La compensazione deve aver rilasciato il permesso acquisito in SAF
        assertFalse(
            "Il permesso SAF deve essere stato rilasciato dalla compensazione",
            fakeSafPermissionManager.hasPersistedReadPermission(uri)
        )

        // Nessun record in Room e nessuna autorizzazione orfana
        assertTrue(repository.getFolders().isEmpty())
        assertTrue(repository.getOrphanPermissions().isEmpty())
    }

    @Test
    fun testAddFolderCompensatingReleaseFailurePreservesOrphanUri() = runBlocking {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APictures")

        // Simuliamo fallimento dell'inserimento Room E fallimento del rilascio compensativo SAF
        delegatingFolderDao.shouldFailInsert = true
        fakeSafPermissionManager.shouldFailReleasePermission = true
        fakeSafPermissionManager.failureReleaseErrorMessage = "Errore SecurityException nel rilascio"

        val result = repository.addFolder(uri, Priority.ALTA, false)

        // Deve sollevare PermissionCompensationFailedException senza ingoiare gli errori
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(
            "Deve essere sollevata un'eccezione esplicita di fallimento compensazione",
            ex is PermissionCompensationFailedException
        )
        val compEx = ex as PermissionCompensationFailedException
        assertEquals(uri, compEx.treeUri)
        assertNotNull(compEx.releaseCause)

        // Il permesso SAF è rimasto nel sistema
        assertTrue(fakeSafPermissionManager.hasPersistedReadPermission(uri))
        // La cartella NON è presente in Room
        assertTrue(repository.getFolders().isEmpty())

        // Viene correttamente tracciato come autorizzazione orfana
        val orphans = repository.getOrphanPermissions()
        assertEquals(1, orphans.size)
        assertEquals(uri, orphans[0])

        // Retry del rilascio
        fakeSafPermissionManager.shouldFailReleasePermission = false
        val retryResult = repository.retryReleasePermission(uri)
        assertTrue("Il retry del rilascio deve avere successo", retryResult.isSuccess)

        // Ora il permesso è stato rilasciato e l'elenco orfani è vuoto
        assertFalse(fakeSafPermissionManager.hasPersistedReadPermission(uri))
        assertTrue(repository.getOrphanPermissions().isEmpty())
    }

    @Test
    fun testRetryReleasePermissionRejectsActiveConfiguredFolder() = runBlocking {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADCIM")
        val addResult = repository.addFolder(uri, Priority.NORMALE, false)
        assertTrue(addResult.isSuccess)

        // Tentativo di rilasciare il permesso di una cartella ancora attiva in Room
        val rejectResult = repository.retryReleasePermission(uri)
        assertTrue("Non deve rilasciare il permesso di una cartella attiva", rejectResult.isFailure)
        assertTrue(
            rejectResult.exceptionOrNull()?.message?.contains("cartella attiva") == true
        )

        // Il permesso rimane intatto
        assertTrue(fakeSafPermissionManager.hasPersistedReadPermission(uri))
    }

    @Test
    fun testReauthorizeFolderSuccessPreservesMetadataAndPreferences() = runBlocking {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADCIM")
        val addResult = repository.addFolder(uri, Priority.ALTA, true)
        assertTrue(addResult.isSuccess)
        val initialFolder = addResult.getOrThrow()

        // Simula revoca esterna del permesso da impostazioni Android
        fakeSafPermissionManager.simulateExternalRevocation(uri)
        repository.refreshAccessibility()

        val inaccessible = repository.foldersFlow.first().first { it.id == initialFolder.id }
        assertFalse("La cartella deve risultare non accessibile dopo la revoca", inaccessible.isAccessible)

        // Esegui il ripristino autorizzazione
        val reauthResult = repository.reauthorizeFolder(initialFolder.id, uri)
        assertTrue("Il ripristino deve avere successo", reauthResult.isSuccess)
        val restored = reauthResult.getOrThrow()

        // Verifiche:
        assertTrue("Il permesso SAF è stato riacquisito", fakeSafPermissionManager.hasPersistedReadPermission(uri))
        assertTrue("isAccessible deve tornare true", restored.isAccessible)
        assertEquals("L'ID deve essere preservato", initialFolder.id, restored.id)
        assertEquals("La priorità deve essere preservata", Priority.ALTA, restored.priority)
        assertTrue("La protezione da eliminazione deve essere preservata", restored.protectFromEviction)
    }

    @Test
    fun testReauthorizeFolderRejectsDifferentUri() = runBlocking {
        val uriOriginal = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADCIM")
        val addResult = repository.addFolder(uriOriginal, Priority.NORMALE, false)
        val folderId = addResult.getOrThrow().id

        val uriDifferent = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")

        // Tentativo di ripristinare con un URI diverso
        val reauthResult = repository.reauthorizeFolder(folderId, uriDifferent)
        assertTrue("Il ripristino con URI diverso deve fallire", reauthResult.isFailure)
        assertTrue(
            "Il messaggio deve indicare che l'URI non corrisponde",
            reauthResult.exceptionOrNull()?.message?.contains("non corrisponde") == true
        )

        // Il record in Room non è stato alterato
        val current = repository.getFolders().first { it.id == folderId }
        assertEquals(uriOriginal.toString(), current.path)
    }

    @Test
    fun testReauthorizeFolderFailsWhenPermissionDenied() = runBlocking {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments")
        val folderId = repository.addFolder(uri, Priority.BASSA, false).getOrThrow().id

        fakeSafPermissionManager.simulateExternalRevocation(uri)
        fakeSafPermissionManager.shouldFailPermissionGrant = true
        fakeSafPermissionManager.failureErrorMessage = "Rifiuto esplicito dell'utente"

        val reauthResult = repository.reauthorizeFolder(folderId, uri)
        assertTrue("Il ripristino deve fallire se SAF nega il permesso", reauthResult.isFailure)
        assertTrue(reauthResult.exceptionOrNull()?.message?.contains("Rifiuto esplicito") == true)
    }

    @Test
    fun testRefreshAccessibilityUpdatesStateWithoutModifyingRoomRecords() = runBlocking {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")
        val addResult = repository.addFolder(uri, Priority.BASSA, false)
        assertTrue(addResult.isSuccess)

        // All'inizio il permesso è attivo
        val initialList = repository.foldersFlow.first()
        assertEquals(1, initialList.size)
        assertTrue(initialList[0].isAccessible)

        // Simula revoca esterna da impostazioni Android
        fakeSafPermissionManager.simulateExternalRevocation(uri)

        // Esegui refresh esplicito dell'accessibilità (simula evento ON_RESUME)
        repository.refreshAccessibility()

        val updatedList = repository.foldersFlow.first()
        assertEquals(1, updatedList.size)
        assertFalse(
            "La cartella deve risultare non accessibile dopo il refresh",
            updatedList[0].isAccessible
        )

        // Verifica che il database Room NON sia stato alterato o cancellato
        val dbEntities = database.folderDao().getFolders()
        assertEquals(1, dbEntities.size)
        assertEquals("Download", dbEntities[0].displayName)
        assertEquals(uri.toString(), dbEntities[0].treeUri)
    }

    @Test
    fun testRemoveFolderDeletesFromRoomAndReleasesSafPermission() = runBlocking {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADCIM")
        val addResult = repository.addFolder(uri, Priority.ALTA, false)
        val folderId = addResult.getOrThrow().id

        assertTrue(fakeSafPermissionManager.hasPersistedReadPermission(uri))
        assertEquals(1, repository.getFolders().size)

        // Rimozione cartella
        val removeResult = repository.removeFolder(folderId)
        assertTrue(removeResult.isSuccess)

        // Verifiche:
        // 1. Cancellata da Room
        assertTrue(repository.getFolders().isEmpty())
        // 2. Permesso persistente SAF rilasciato
        assertFalse(fakeSafPermissionManager.hasPersistedReadPermission(uri))
        // 3. Nessuna autorizzazione orfana
        assertTrue(repository.getOrphanPermissions().isEmpty())
    }

    @Test
    fun testRemoveFolderReportsFailureWhenSafReleaseFailsAndEnablesRetry() = runBlocking {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADCIM")
        val folderId = repository.addFolder(uri, Priority.ALTA, false).getOrThrow().id

        // Simula fallimento nel rilascio del permesso SAF
        fakeSafPermissionManager.shouldFailReleasePermission = true
        fakeSafPermissionManager.failureReleaseErrorMessage = "SecurityException durante il rilascio SAF"

        val removeResult = repository.removeFolder(folderId)
        assertTrue("La rimozione deve segnalare errore parziale se il rilascio SAF fallisce", removeResult.isFailure)
        assertTrue(removeResult.exceptionOrNull() is PermissionReleaseException)

        // Verifiche stato:
        // 1. Il record Room è stato comunque rimosso
        assertTrue(repository.getFolders().isEmpty())
        // 2. Il permesso è ancora presente poiché il rilascio è fallito
        assertTrue(fakeSafPermissionManager.hasPersistedReadPermission(uri))

        // 3. Tracciato come autorizzazione orfana
        val orphans = repository.getOrphanPermissions()
        assertEquals(1, orphans.size)
        assertEquals(uri, orphans[0])

        // 4. Retry affidabile del rilascio
        fakeSafPermissionManager.shouldFailReleasePermission = false
        val retryResult = repository.retryReleasePermission(uri)
        assertTrue("Il retry di rilascio deve avere successo", retryResult.isSuccess)
        assertFalse("Il permesso ora è stato effettivamente rilasciato", fakeSafPermissionManager.hasPersistedReadPermission(uri))
        assertTrue("La lista degli orfani ora è vuota", repository.getOrphanPermissions().isEmpty())
    }

    @Test
    fun testUpdatePriorityAndEvictionProtection() = runBlocking {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments")
        val folder = repository.addFolder(uri, Priority.BASSA, false).getOrThrow()

        // Cambia priorità
        val prioResult = repository.updatePriority(folder.id, Priority.ALTA)
        assertTrue(prioResult.isSuccess)
        var updated = repository.getFolders().first { it.id == folder.id }
        assertEquals(Priority.ALTA, updated.priority)
        assertFalse(updated.protectFromEviction)

        // Cambia protezione da eliminazione
        val protResult = repository.updateEvictionProtection(folder.id, true)
        assertTrue(protResult.isSuccess)
        updated = repository.getFolders().first { it.id == folder.id }
        assertEquals(Priority.ALTA, updated.priority)
        assertTrue(updated.protectFromEviction)
    }

    @Test
    fun testRemoveFolderAtomicallyDeletesFingerprintCache() = runBlocking {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APictures")
        val folder = repository.addFolder(uri, Priority.ALTA, false).getOrThrow()

        database.fileFingerprintDao().upsertFingerprint(
            FileFingerprintEntity(
                id = "fp-1",
                backupFolderId = folder.id,
                documentId = "doc:img1",
                documentUri = "$uri/document/doc%3Aimg1",
                relativePath = "img1.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 1024L,
                lastModifiedTimestamp = 10_000L,
                computedBytes = 1024L,
                sha256Hex = "a".repeat(64),
                lastContentHashedTimestamp = 20_000L,
                lastMetadataMatchedTimestamp = 20_000L,
                lastSeenSessionId = "sess-prev"
            )
        )
        assertEquals(1, database.fileFingerprintDao().countByFolder(folder.id))

        val removeResult = repository.removeFolder(folder.id)
        assertTrue(removeResult.isSuccess)
        assertTrue(repository.getFolders().isEmpty())
        assertEquals(0, database.fileFingerprintDao().countByFolder(folder.id))
        assertFalse(fakeSafPermissionManager.hasPersistedReadPermission(uri))
    }

    @Test
    fun testRemoveFolderRejectedWhileScanRunningOrPaused() = runBlocking {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADCIM")
        val folder = repository.addFolder(uri, Priority.ALTA, false).getOrThrow()

        database.scanSessionDao().insertSession(
            ScanSessionEntity(
                id = "sess-active",
                status = ScanSessionStatus.RUNNING.name
            )
        )

        val rejectRunning = repository.removeFolder(folder.id)
        assertTrue("La rimozione deve essere rifiutata con sessione RUNNING", rejectRunning.isFailure)
        assertEquals(1, repository.getFolders().size)
        assertTrue(fakeSafPermissionManager.hasPersistedReadPermission(uri))

        database.scanSessionDao().updateStatus("sess-active", ScanSessionStatus.PAUSED.name)
        val rejectPaused = repository.removeFolder(folder.id)
        assertTrue("La rimozione deve essere rifiutata con sessione PAUSED", rejectPaused.isFailure)
        assertEquals(1, repository.getFolders().size)
        assertTrue(fakeSafPermissionManager.hasPersistedReadPermission(uri))
    }

    @Test
    fun testRemoveFolderRollsBackTransactionIfCacheDeleteFails() = runBlocking {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")
        val folder = repository.addFolder(uri, Priority.NORMALE, false).getOrThrow()

        database.fileFingerprintDao().upsertFingerprint(
            FileFingerprintEntity(
                id = "fp-rollback",
                backupFolderId = folder.id,
                documentId = "doc:rb",
                documentUri = "$uri/document/doc%3Arb",
                relativePath = "rb.bin",
                mimeType = "application/octet-stream",
                sizeBytes = 64L,
                lastModifiedTimestamp = 10_000L,
                computedBytes = 64L,
                sha256Hex = "b".repeat(64),
                lastContentHashedTimestamp = 20_000L,
                lastMetadataMatchedTimestamp = 20_000L,
                lastSeenSessionId = "sess-prev"
            )
        )

        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER trigger_fail_fp_delete
            BEFORE DELETE ON file_fingerprints
            BEGIN
                SELECT RAISE(ABORT, 'Errore simulato eliminazione cache fingerprint');
            END;
            """.trimIndent()
        )

        val result = repository.removeFolder(folder.id)
        assertTrue("La rimozione deve fallire per rollback transazionale", result.isFailure)

        // Rollback integrale: cartella ancora presente, cache intatta, permesso SAF non rilasciato
        assertEquals(1, database.folderDao().getFolders().size)
        assertEquals(1, database.fileFingerprintDao().countByFolder(folder.id))
        assertTrue(fakeSafPermissionManager.hasPersistedReadPermission(uri))

        database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS trigger_fail_fp_delete;")
    }
}
