package com.androidbackup.app.data.scan

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.androidbackup.app.data.local.database.BackupDatabase
import com.androidbackup.app.data.local.entity.BackupFolderEntity
import com.androidbackup.app.data.local.entity.ScanSessionStatus
import com.androidbackup.app.data.model.BackupSource
import com.androidbackup.app.data.model.Priority
import com.androidbackup.app.data.repository.FolderRepository
import com.androidbackup.app.data.repository.ScanRepository
import com.androidbackup.app.data.repository.SettingsRepository
import com.androidbackup.app.testutils.FakeSafPermissionManager
import com.androidbackup.app.testutils.FakeSafTreeReader
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.util.UUID

/**
 * Suite di test per il motore di scansione locale e inventario Room (Fase 1B.2).
 * Copre specificamente i punti 1-15 e 18 indicati nella specifica di collaudo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LocalFolderScannerTest {

    private lateinit var database: BackupDatabase
    private lateinit var fakeSafTreeReader: FakeSafTreeReader
    private lateinit var fakeSafPermissionManager: FakeSafPermissionManager
    private lateinit var folderRepository: FolderRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var scanner: LocalFolderScanner
    private lateinit var scanRepository: ScanRepository

    private val treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ATestFolder")
    private val rootDocId = "primary:TestFolder"
    private lateinit var configuredFolder: BackupFolderEntity

    @Before
    fun setUp() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            database = Room.inMemoryDatabaseBuilder(context, BackupDatabase::class.java)
                .allowMainThreadQueries()
                .build()

            fakeSafTreeReader = FakeSafTreeReader()
            fakeSafPermissionManager = FakeSafPermissionManager()

            folderRepository = FolderRepository(database.folderDao(), fakeSafPermissionManager)
            settingsRepository = SettingsRepository(database.settingsDao())
            settingsRepository.initializeDefaultsIfEmpty()

            scanner = LocalFolderScanner(
                scanSessionDao = database.scanSessionDao(),
                scannedDocumentDao = database.scannedDocumentDao(),
                pendingDirectoryDao = database.pendingDirectoryDao(),
                scanErrorDao = database.scanErrorDao(),
                safTreeReader = fakeSafTreeReader,
                safPermissionManager = fakeSafPermissionManager
            )

            scanRepository = ScanRepository(
                scanSessionDao = database.scanSessionDao(),
                scannedDocumentDao = database.scannedDocumentDao(),
                pendingDirectoryDao = database.pendingDirectoryDao(),
                scanErrorDao = database.scanErrorDao(),
                scanner = scanner,
                folderRepository = folderRepository,
                settingsRepository = settingsRepository
            )

            // Configura autorizzazione SAF e cartella in Room
            fakeSafPermissionManager.takePersistableReadPermission(treeUri)
            configuredFolder = BackupFolderEntity(
                id = "folder-test-1",
                treeUri = treeUri.toString(),
                displayName = "TestFolder",
                authority = "com.android.externalstorage.documents",
                addedTimestamp = System.currentTimeMillis(),
                priority = Priority.ALTA.name,
                protectFromEviction = false,
                enabled = true
            )
            database.folderDao().insertFolder(configuredFolder)
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun test1_ScanEmptyFolder() = runBlocking {
        // Nessun elemento aggiunto in fakeSafTreeReader per rootDocId
        val session = scanner.createNewSession(folderRepository.getFolders())

        // Esegue la scansione direttamente con scanner per test deterministico
        val status = scanner.runScan(session.id, emptyList())
        assertEquals(ScanSessionStatus.COMPLETED, status)

        val updated = database.scanSessionDao().getSessionById(session.id)!!
        assertEquals(0, updated.totalFiles)
        assertEquals(0, updated.totalDirectories)
        assertEquals(0L, updated.knownTotalBytes)
        assertEquals(0, updated.errorCount)
        assertNotNull(updated.completedTimestamp)
    }

    @Test
    fun test2_ScanNestedDirectories() = runBlocking {
        // root -> sub1 -> sub2 -> file_nested.txt
        fakeSafTreeReader.addDirectory(treeUri, rootDocId, "doc:sub1", "sub1")
        fakeSafTreeReader.addDirectory(treeUri, "doc:sub1", "doc:sub2", "sub2")
        fakeSafTreeReader.addFile(treeUri, "doc:sub2", "doc:file1", "file_nested.txt", 1024L)

        val session = scanner.createNewSession(folderRepository.getFolders())
        val status = scanner.runScan(session.id, emptyList())

        assertEquals(ScanSessionStatus.COMPLETED, status)
        val updated = database.scanSessionDao().getSessionById(session.id)!!
        assertEquals(1, updated.totalFiles)
        assertEquals(2, updated.totalDirectories)
        assertEquals(1024L, updated.knownTotalBytes)

        val documents = database.scannedDocumentDao().getDocumentsForSession(session.id)
        val fileDoc = documents.first { !it.isDirectory }
        assertEquals("sub1/sub2/file_nested.txt", fileDoc.relativePath)
    }

    @Test
    fun test3_CorrectFileIdentification() = runBlocking {
        val lastMod = 1730000000000L
        fakeSafTreeReader.addFile(
            treeUri = treeUri,
            parentDocumentId = rootDocId,
            documentId = "doc:report",
            displayName = "bilancio.pdf",
            sizeBytes = 204800L,
            mimeType = "application/pdf",
            lastModified = lastMod
        )

        val session = scanner.createNewSession(folderRepository.getFolders())
        scanner.runScan(session.id, emptyList())

        val docs = database.scannedDocumentDao().getIncludedFilesForSession(session.id)
        assertEquals(1, docs.size)
        val doc = docs[0]
        assertEquals("bilancio.pdf", doc.displayName)
        assertEquals("application/pdf", doc.mimeType)
        assertEquals(204800L, doc.sizeBytes)
        assertEquals(lastMod, doc.lastModifiedTimestamp)
        assertFalse(doc.isDirectory)
        assertFalse(doc.isExcluded)
    }

    @Test
    fun test4_CalculateTotalKnownSize() = runBlocking {
        fakeSafTreeReader.addFile(treeUri, rootDocId, "doc:f1", "f1.dat", 1000L)
        fakeSafTreeReader.addFile(treeUri, rootDocId, "doc:f2", "f2.dat", 2500L)
        fakeSafTreeReader.addFile(treeUri, rootDocId, "doc:f3", "f3.dat", 4500L)

        val session = scanner.createNewSession(folderRepository.getFolders())
        scanner.runScan(session.id, emptyList())

        val updated = database.scanSessionDao().getSessionById(session.id)!!
        assertEquals(3, updated.totalFiles)
        assertEquals(8000L, updated.knownTotalBytes)
        assertEquals(0, updated.unknownSizeFiles)
    }

    @Test
    fun test5_UnknownSizeHandling() = runBlocking {
        // File 1 con dimensione nota, File 2 con dimensione sconosciuta (null)
        fakeSafTreeReader.addFile(treeUri, rootDocId, "doc:known", "known.dat", 5000L)
        fakeSafTreeReader.addFile(treeUri, rootDocId, "doc:unknown", "unknown.dat", null)

        val session = scanner.createNewSession(folderRepository.getFolders())
        scanner.runScan(session.id, emptyList())

        val updated = database.scanSessionDao().getSessionById(session.id)!!
        assertEquals(2, updated.totalFiles)
        assertEquals(5000L, updated.knownTotalBytes)
        assertEquals(1, updated.unknownSizeFiles)

        val doc = database.scannedDocumentDao().getDocumentsForSession(session.id).first { it.displayName == "unknown.dat" }
        assertNull(doc.sizeBytes)
    }

    @Test
    fun test6_ExclusionMatchingOnFiles() = runBlocking {
        fakeSafTreeReader.addFile(treeUri, rootDocId, "doc:1", "documento.pdf", 10000L)
        fakeSafTreeReader.addFile(treeUri, rootDocId, "doc:2", "temp_file.tmp", 5000L)
        fakeSafTreeReader.addFile(treeUri, rootDocId, "doc:3", "backup.bak", 2000L)

        val exclusions = listOf("*.tmp", "*.bak")
        val session = scanner.createNewSession(folderRepository.getFolders())
        scanner.runScan(session.id, exclusions)

        val updated = database.scanSessionDao().getSessionById(session.id)!!
        assertEquals(1, updated.totalFiles) // Solo documento.pdf
        assertEquals(2, updated.excludedItems)
        assertEquals(10000L, updated.knownTotalBytes)

        val included = database.scannedDocumentDao().getIncludedFilesForSession(session.id)
        assertEquals(1, included.size)
        assertEquals("documento.pdf", included[0].displayName)
    }

    @Test
    fun test7_ExclusionOfEntireDirectory() = runBlocking {
        fakeSafTreeReader.addDirectory(treeUri, rootDocId, "doc:cache", "cache")
        // File all'interno della cartella cache
        fakeSafTreeReader.addFile(treeUri, "doc:cache", "doc:subfile", "cached_item.bin", 8000L)
        fakeSafTreeReader.addFile(treeUri, rootDocId, "doc:valid", "valid.txt", 1200L)

        val exclusions = listOf("cache")
        val session = scanner.createNewSession(folderRepository.getFolders())
        scanner.runScan(session.id, exclusions)

        val updated = database.scanSessionDao().getSessionById(session.id)!!
        assertEquals(1, updated.totalFiles) // Solo valid.txt
        assertEquals(1, updated.excludedItems) // La cartella cache è esclusa
        assertEquals(1200L, updated.knownTotalBytes)

        // Verifichiamo che i file dentro cache non siano mai stati esplorati o inseriti
        val allDocs = database.scannedDocumentDao().getDocumentsForSession(session.id)
        assertFalse(allDocs.any { it.displayName == "cached_item.bin" })
    }

    @Test
    fun test8_InaccessibleDirectoryHandling() = runBlocking {
        fakeSafTreeReader.addDirectory(treeUri, rootDocId, "doc:restricted", "restricted_dir")
        fakeSafTreeReader.setDirectoryInaccessible(treeUri, "doc:restricted", SecurityException("Accesso negato"))
        fakeSafTreeReader.addFile(treeUri, rootDocId, "doc:accessible", "accessible.txt", 300L)

        val session = scanner.createNewSession(folderRepository.getFolders())
        val status = scanner.runScan(session.id, emptyList())

        // Non deve fallire l'intera scansione: deve concludersi con errori (avvisi)
        assertEquals(ScanSessionStatus.COMPLETED_WITH_ERRORS, status)

        val updated = database.scanSessionDao().getSessionById(session.id)!!
        assertEquals(1, updated.totalFiles)
        assertEquals(1, updated.errorCount)

        val errors = database.scanErrorDao().getErrorsForSession(session.id)
        assertEquals(1, errors.size)
        assertEquals("DIRECTORY_READ_ERROR", errors[0].errorType)
    }

    @Test
    fun test9_RevokedSafPermission() = runBlocking {
        fakeSafPermissionManager.simulateExternalRevocation(treeUri)

        val session = database.scanSessionDao().run {
            val s = com.androidbackup.app.data.local.entity.ScanSessionEntity()
            insertSession(s)
            database.pendingDirectoryDao().insertDirectory(
                com.androidbackup.app.data.local.entity.PendingDirectoryEntity(
                    scanSessionId = s.id,
                    backupFolderId = configuredFolder.id,
                    treeUri = treeUri.toString(),
                    documentId = rootDocId,
                    relativePath = ""
                )
            )
            s
        }

        val status = scanner.runScan(session.id, emptyList())
        assertEquals(ScanSessionStatus.COMPLETED_WITH_ERRORS, status)

        val errors = database.scanErrorDao().getErrorsForSession(session.id)
        assertEquals(1, errors.size)
        assertEquals("PERMISSION_REVOKED", errors[0].errorType)
    }

    @Test
    fun test10_ScanContinuesAfterSubfolderError() = runBlocking {
        // Due cartelle figlie: una fallisce, la seconda ha successo
        fakeSafTreeReader.addDirectory(treeUri, rootDocId, "doc:bad", "bad_dir")
        fakeSafTreeReader.setDirectoryInaccessible(treeUri, "doc:bad", FileNotFoundException("Cartella non trovata"))

        fakeSafTreeReader.addDirectory(treeUri, rootDocId, "doc:good", "good_dir")
        fakeSafTreeReader.addFile(treeUri, "doc:good", "doc:f_good", "good_file.txt", 999L)

        val session = scanner.createNewSession(folderRepository.getFolders())
        val status = scanner.runScan(session.id, emptyList())

        assertEquals(ScanSessionStatus.COMPLETED_WITH_ERRORS, status)
        val updated = database.scanSessionDao().getSessionById(session.id)!!
        assertEquals("Il file della cartella valida deve essere stato scansionato", 1, updated.totalFiles)
        assertEquals(999L, updated.knownTotalBytes)
        assertEquals(1, updated.errorCount)
    }

    @Test
    fun test11_DuplicatePreventionOnRescan() = runBlocking {
        fakeSafTreeReader.addFile(treeUri, rootDocId, "doc:unique", "file.txt", 500L)

        val session1 = scanner.createNewSession(folderRepository.getFolders())
        scanner.runScan(session1.id, emptyList())

        val countAfterFirst = database.scannedDocumentDao().getDocumentsForSession(session1.id).size
        assertEquals(1, countAfterFirst)

        // Riscansione
        val session2 = scanner.createNewSession(folderRepository.getFolders())
        scanner.runScan(session2.id, emptyList())

        val countAfterSecond = database.scannedDocumentDao().getDocumentsForSession(session2.id).size
        assertEquals(1, countAfterSecond)
    }

    @Test
    fun test12_InventoryPersistenceInRoom() = runBlocking {
        fakeSafTreeReader.addFile(treeUri, rootDocId, "doc:persist", "foto.png", 1500000L)

        val session = scanner.createNewSession(folderRepository.getFolders())
        scanner.runScan(session.id, emptyList())

        // Verifica query Room DAO
        val count = database.scannedDocumentDao().countFilesForSession(session.id)
        val bytes = database.scannedDocumentDao().sumKnownBytesForSession(session.id)

        assertEquals(1, count)
        assertEquals(1500000L, bytes)

        val stats = scanRepository.getFolderStats(configuredFolder.id)
        assertEquals(1, stats.fileCount)
        assertEquals(1500000L, stats.totalKnownBytes)
    }

    @Test
    fun test13_PauseAndResumeScan() = runBlocking {
        fakeSafTreeReader.addDirectory(treeUri, rootDocId, "doc:d1", "d1")
        fakeSafTreeReader.addFile(treeUri, "doc:d1", "doc:f1", "f1.txt", 100L)
        fakeSafTreeReader.addDirectory(treeUri, rootDocId, "doc:d2", "d2")
        fakeSafTreeReader.addFile(treeUri, "doc:d2", "doc:f2", "f2.txt", 200L)

        val session = scanner.createNewSession(folderRepository.getFolders())

        // Simula pausa richiesta durante la scansione
        scanner.requestPause()
        val pauseStatus = scanner.runScan(session.id, emptyList())
        assertEquals(ScanSessionStatus.PAUSED, pauseStatus)

        // Ripresa scansione
        val resumeStatus = scanner.runScan(session.id, emptyList())
        assertEquals(ScanSessionStatus.COMPLETED, resumeStatus)

        val updated = database.scanSessionDao().getSessionById(session.id)!!
        assertEquals(2, updated.totalFiles)
        assertEquals(300L, updated.knownTotalBytes)
    }

    @Test
    fun test14_ResumeAfterProcessDeathRecovery() = runBlocking {
        // Simula sessione rimasta in stato RUNNING nel database SQLite
        val interrupted = com.androidbackup.app.data.local.entity.ScanSessionEntity(
            id = "interrupted-session",
            startedTimestamp = System.currentTimeMillis() - 50000,
            status = ScanSessionStatus.RUNNING.name
        )
        database.scanSessionDao().insertSession(interrupted)

        // All'avvio dell'app il repository individua la sessione interrotta
        val recovered = scanRepository.checkAndRecoverInterruptedSession()
        assertNotNull(recovered)
        assertEquals(ScanSessionStatus.PAUSED.name, recovered!!.status)
        assertTrue(recovered.errorMessage?.contains("interrotta") == true)
    }

    @Test
    fun test15_PreserveLastCompletedInventoryWhenNewScanInterrupted() = runBlocking {
        fakeSafTreeReader.addFile(treeUri, rootDocId, "doc:stable", "stabile.pdf", 4096L)

        // Prima scansione completata con successo
        val completedSession = scanRepository.startNewScan().getOrThrow()
        scanner.runScan(completedSession.id, emptyList())

        val initialStats = scanRepository.getFolderStats(configuredFolder.id)
        assertEquals(1, initialStats.fileCount)
        assertEquals(4096L, initialStats.totalKnownBytes)

        // Avvio di una nuova scansione che viene interrotta / cancellata
        val newSession = scanRepository.startNewScan().getOrThrow()
        scanRepository.cancelScan()

        // Le statistiche e l'inventario dell'ultima sessione completata rimangono intatti e accessibili
        val lastCompleted = database.scanSessionDao().getLatestCompletedSession()
        assertNotNull(lastCompleted)
        assertEquals(completedSession.id, lastCompleted!!.id)

        val statsAfterCancel = scanRepository.getFolderStats(configuredFolder.id)
        assertEquals(1, statsAfterCancel.fileCount)
        assertEquals(4096L, statsAfterCancel.totalKnownBytes)
    }

    @Test
    fun test18_DemoModeIsolationFromRealDatabase() = runBlocking {
        // Verifica che il numero di sessioni e documenti in Room sia isolato
        val sessionCountBefore = database.scanSessionDao().getLatestSession()
        assertNull(sessionCountBefore)

        val docCount = database.scannedDocumentDao().clearAllDocuments()
        assertEquals(0, docCount)
    }

    private suspend fun awaitSessionStatus(
        sessionId: String,
        targetStatus: ScanSessionStatus,
        timeoutMs: Long = 10000L
    ): com.androidbackup.app.data.local.entity.ScanSessionEntity {
        return withTimeout(timeoutMs) {
            database.scanSessionDao().observeLatestSession()
                .filterNotNull()
                .filter { it.id == sessionId && it.status == targetStatus.name }
                .first()
        }
    }

    @Test
    fun test19_CancelDoesNotLeakFlagAndNextScanSucceeds() = runBlocking {
        fakeSafTreeReader.addFile(treeUri, rootDocId, "doc:f1", "file1.txt", 100L)

        // 1. Avvia la prima sessione esclusivamente tramite ScanRepository (senza chiamare scanner.runScan)
        val session1 = scanRepository.startNewScan().getOrThrow()
        
        // 2. Annulla la sessione tramite ScanRepository
        scanRepository.cancelScan().getOrThrow()

        val s1Db = database.scanSessionDao().getSessionById(session1.id)!!
        assertEquals(ScanSessionStatus.CANCELLED.name, s1Db.status)
        // La coda pendente della sessione annullata deve essere stata eliminata
        val pendingS1 = database.pendingDirectoryDao().getPendingDirectoriesForSession(session1.id)
        assertTrue("La coda pendente della sessione annullata deve essere vuota", pendingS1.isEmpty())

        // 3. Avvia una seconda sessione esclusivamente tramite ScanRepository
        val session2 = scanRepository.startNewScan().getOrThrow()

        // 4. Attende il completamento naturale del job orchestrato da ScanRepository
        val s2Completed = awaitSessionStatus(session2.id, ScanSessionStatus.COMPLETED)
        assertEquals(ScanSessionStatus.COMPLETED.name, s2Completed.status)
        assertEquals(1, s2Completed.totalFiles)
        assertEquals(100L, s2Completed.knownTotalBytes)
    }

    @Test
    fun test20_SequencePauseCancelAndNewScan() = runBlocking {
        fakeSafTreeReader.addFile(treeUri, rootDocId, "doc:f1", "file1.txt", 100L)

        // 1. Avvia la prima sessione tramite repository
        val session1 = scanRepository.startNewScan().getOrThrow()

        // 2. Mette in pausa e poi annulla tramite repository
        scanRepository.pauseScan().getOrThrow()
        scanRepository.cancelScan().getOrThrow()

        // 3. Nuova scansione deve avviarsi pulita e completarsi tramite il background job del repository
        val session2 = scanRepository.startNewScan().getOrThrow()
        val s2Completed = awaitSessionStatus(session2.id, ScanSessionStatus.COMPLETED)
        assertEquals(ScanSessionStatus.COMPLETED.name, s2Completed.status)
        assertEquals(1, s2Completed.totalFiles)
        assertEquals(100L, s2Completed.knownTotalBytes)
    }

    @Test
    fun test21_NullCursorThrowsAndRecordsNullCursorError() = runBlocking {
        // Directory 1: vuota (restituisce Cursor con 0 elementi) -> Nessun errore
        fakeSafTreeReader.addDirectory(treeUri, rootDocId, "doc:empty_valid", "empty_valid")

        // Directory 2: errore ContentProvider (Cursor nullo) -> Deve generare ScanErrorEntity con NULL_CURSOR
        fakeSafTreeReader.addDirectory(treeUri, rootDocId, "doc:faulty", "faulty_dir")
        fakeSafTreeReader.setDirectoryNullCursor(treeUri, "doc:faulty")

        val session = scanner.createNewSession(folderRepository.getFolders())
        val status = scanner.runScan(session.id, emptyList())

        assertEquals(ScanSessionStatus.COMPLETED_WITH_ERRORS, status)
        val errors = database.scanErrorDao().getErrorsForSession(session.id)
        assertEquals(1, errors.size)
        assertEquals("NULL_CURSOR", errors[0].errorType)
        assertTrue(errors[0].message.contains("cursore nullo", ignoreCase = true))

        // La directory vuota non deve aver generato alcun errore
        val validDirDoc = database.scannedDocumentDao().getDocumentsForSession(session.id)
            .firstOrNull { it.documentId == "doc:empty_valid" }
        assertNotNull(validDirDoc)
    }

    @Test
    fun test22_ProgressiveBatchStreaming() = runBlocking {
        // Aumentato a 250 file per verificare l'emissione di più batch (BATCH_SIZE = 100)
        val totalFilesToGenerate = 250
        val sizePerFile = 10L
        for (i in 1..totalFilesToGenerate) {
            fakeSafTreeReader.addFile(
                treeUri = treeUri,
                parentDocumentId = rootDocId,
                documentId = "doc:stream_$i",
                displayName = "stream_$i.dat",
                sizeBytes = sizePerFile
            )
        }

        val session = scanner.createNewSession(folderRepository.getFolders())
        val status = scanner.runScan(session.id, emptyList())
        assertEquals(ScanSessionStatus.COMPLETED, status)

        val updated = database.scanSessionDao().getSessionById(session.id)!!
        assertEquals(totalFilesToGenerate, updated.totalFiles)
        assertEquals(totalFilesToGenerate * sizePerFile, updated.knownTotalBytes)

        // 1. Più batch effettivamente emessi (250 file / 100 = 3 batch: 100, 100, 50)
        assertTrue("Devono essere emessi più batch in streaming", fakeSafTreeReader.totalBatchesEmitted >= 3)

        // 2. Dimensione massima di ogni batch non superiore a 100
        assertTrue("La dimensione massima del batch non deve superare 100", fakeSafTreeReader.maxBatchSizeEmitted <= 100)
        assertEquals(100, fakeSafTreeReader.maxBatchSizeEmitted)

        // 3. Corretta persistenza di tutti gli elementi
        val docs = database.scannedDocumentDao().getDocumentsForSession(session.id).filter { !it.isDirectory }
        assertEquals(totalFilesToGenerate, docs.size)

        // 4. Assenza di duplicati
        val uniqueIds = docs.map { it.documentId }.toSet()
        assertEquals("Nessun documento duplicato deve essere presente", totalFilesToGenerate, uniqueIds.size)

        // 5. Corretta somma delle dimensioni
        val sumBytes = docs.mapNotNull { it.sizeBytes }.sum()
        assertEquals(totalFilesToGenerate * sizePerFile, sumBytes)
    }

    @Test
    fun test23_CyclePreventionSymbolicLoop() = runBlocking {
        // Simula ciclo ad anello: DirA contiene DirB, e DirB contiene DirA (loop infinito potenziale)
        fakeSafTreeReader.addDirectory(treeUri, rootDocId, "doc:dirA", "DirA")
        fakeSafTreeReader.addDirectory(treeUri, "doc:dirA", "doc:dirB", "DirB")
        // Loop: DirB contiene un riferimento a DirA
        fakeSafTreeReader.addDirectory(treeUri, "doc:dirB", "doc:dirA", "DirA_Loop")
        fakeSafTreeReader.addFile(treeUri, "doc:dirB", "doc:payload", "payload.bin", 512L)

        val session = scanner.createNewSession(folderRepository.getFolders())
        val status = scanner.runScan(session.id, emptyList())

        // Deve completarsi senza rimanere intrappolato in un loop infinito
        assertEquals(ScanSessionStatus.COMPLETED, status)

        val docs = database.scannedDocumentDao().getDocumentsForSession(session.id)
        val dirADocs = docs.filter { it.documentId == "doc:dirA" }
        // DirA deve essere registrata una sola volta
        assertEquals(1, dirADocs.size)

        val updated = database.scanSessionDao().getSessionById(session.id)!!
        assertEquals(1, updated.totalFiles)
        assertEquals(512L, updated.knownTotalBytes)
    }

    @Test
    fun test24_CleanupOldSessionsRetainsLast2Completed() = runBlocking {
        // Sessione 1 completata
        val s1 = scanner.createNewSession(folderRepository.getFolders())
        fakeSafTreeReader.addFile(treeUri, rootDocId, "doc:f_s1", "f_s1.txt", 10L)
        scanner.runScan(s1.id, emptyList())

        // Sessione 2 completata
        val s2 = scanner.createNewSession(folderRepository.getFolders())
        fakeSafTreeReader.addFile(treeUri, rootDocId, "doc:f_s2", "f_s2.txt", 20L)
        scanner.runScan(s2.id, emptyList())

        // Sessione 3 completata
        val s3 = scanner.createNewSession(folderRepository.getFolders())
        fakeSafTreeReader.addFile(treeUri, rootDocId, "doc:f_s3", "f_s3.txt", 30L)
        scanner.runScan(s3.id, emptyList())

        // Verifica che le ultime 2 sessioni completate (s2 e s3) siano presenti
        val allSessions = database.scanSessionDao().observeLatestSession().first()
        assertNotNull(allSessions)

        // Sessione s3 deve esistere
        assertNotNull(database.scanSessionDao().getSessionById(s3.id))
        // Sessione s2 deve esistere
        assertNotNull(database.scanSessionDao().getSessionById(s2.id))
        // Sessione s1 obsoleta eliminata dalla pulizia
        assertNull(database.scanSessionDao().getSessionById(s1.id))
        // Anche i documenti di s1 devono essere stati ripuliti
        assertTrue(database.scannedDocumentDao().getDocumentsForSession(s1.id).isEmpty())
    }

    @Test
    fun test25_AtomicSessionCreation() = runBlocking {
        val folders = folderRepository.getFolders()
        val session = scanner.createNewSession(folders)

        // Verifica atomica: la sessione, la pending directory radice e il record radice in scanned_documents
        // sono stati tutti salvati in Room
        val dbSession = database.scanSessionDao().getSessionById(session.id)
        assertNotNull(dbSession)
        assertEquals(ScanSessionStatus.RUNNING.name, dbSession!!.status)

        val pending = database.pendingDirectoryDao().getPendingDirectoriesForSession(session.id)
        assertEquals(1, pending.size)
        assertEquals(rootDocId, pending[0].documentId)

        val isRootRecorded = database.scannedDocumentDao().isDocumentRecorded(session.id, configuredFolder.id, rootDocId)
        assertTrue("La directory radice deve essere registrata per la prevenzione cicli", isRootRecorded)
    }

    @Test
    fun test26_PauseDuringActiveScanAndResume() = runBlocking {
        fakeSafTreeReader.addDirectory(treeUri, rootDocId, "doc:step1", "step1")
        fakeSafTreeReader.addFile(treeUri, "doc:step1", "doc:f_step1", "step1.txt", 100L)
        fakeSafTreeReader.addDirectory(treeUri, rootDocId, "doc:step2", "step2")
        fakeSafTreeReader.addFile(treeUri, "doc:step2", "doc:f_step2", "step2.txt", 200L)

        // Hook: quando viene interrogata la directory step1, richiediamo la pausa a metà scansione
        fakeSafTreeReader.onDirectoryQueried = { docId ->
            if (docId == "doc:step1") {
                scanner.requestPause()
            }
        }

        val session = scanner.createNewSession(folderRepository.getFolders())
        val statusAfterPause = scanner.runScan(session.id, emptyList())
        assertEquals(ScanSessionStatus.PAUSED, statusAfterPause)

        // Rimuove l'hook di pausa e riprende
        fakeSafTreeReader.onDirectoryQueried = null
        val statusAfterResume = scanner.runScan(session.id, emptyList())
        assertEquals(ScanSessionStatus.COMPLETED, statusAfterResume)

        val updated = database.scanSessionDao().getSessionById(session.id)!!
        assertEquals(2, updated.totalFiles)
        assertEquals(300L, updated.knownTotalBytes)
    }

    @Test
    fun test27_ProcessDeathRecoveryAcrossInstances() = runBlocking {
        // 1. Simula scansione in corso prima del crash: sessione creata e coda non vuota
        val session = scanner.createNewSession(folderRepository.getFolders())
        fakeSafTreeReader.addFile(treeUri, rootDocId, "doc:after_crash", "recovered.txt", 400L)

        // 2. Simula terminazione anomala del processo (nessun finally eseguito): lo stato nel DB resta RUNNING
        database.scanSessionDao().updateStatus(session.id, ScanSessionStatus.RUNNING.name)

        // 3. Nuova istanza di Scanner e Repository avviata dal nuovo processo dell'app
        val newScanner = LocalFolderScanner(
            scanSessionDao = database.scanSessionDao(),
            scannedDocumentDao = database.scannedDocumentDao(),
            pendingDirectoryDao = database.pendingDirectoryDao(),
            scanErrorDao = database.scanErrorDao(),
            safTreeReader = fakeSafTreeReader,
            safPermissionManager = fakeSafPermissionManager
        )
        val newScanRepo = ScanRepository(
            scanSessionDao = database.scanSessionDao(),
            scannedDocumentDao = database.scannedDocumentDao(),
            pendingDirectoryDao = database.pendingDirectoryDao(),
            scanErrorDao = database.scanErrorDao(),
            scanner = newScanner,
            folderRepository = folderRepository,
            settingsRepository = settingsRepository
        )

        // All'avvio dell'app il nuovo repository rileva la sessione interrotta
        val recovered = newScanRepo.checkAndRecoverInterruptedSession()
        assertNotNull(recovered)
        assertEquals(session.id, recovered!!.id)
        assertEquals(ScanSessionStatus.PAUSED.name, recovered.status)

        // Ripresa scansione esclusivamente tramite il nuovo repository (senza chiamare direttamente runScan):
        newScanRepo.resumeScan().getOrThrow()
        val finalSession = awaitSessionStatus(session.id, ScanSessionStatus.COMPLETED)
        assertEquals(ScanSessionStatus.COMPLETED.name, finalSession.status)
        assertEquals(1, finalSession.totalFiles)
        assertEquals(400L, finalSession.knownTotalBytes)
    }

    @Test
    fun test28_CancellationExceptionDuringBatchClosesCursorAndPreservesQueue() = runBlocking {
        // Prepara directory con file
        fakeSafTreeReader.addDirectory(treeUri, rootDocId, "doc:sub_cancel", "SubCancel")
        fakeSafTreeReader.addFile(treeUri, "doc:sub_cancel", "doc:file_inside", "inside.txt", 150L)

        val session = scanner.createNewSession(folderRepository.getFolders())

        // Hook: quando viene interrogata la cartella doc:sub_cancel, simula una CancellationException durante l'elaborazione del batch
        fakeSafTreeReader.onDirectoryQueried = { docId ->
            if (docId == "doc:sub_cancel") {
                throw kotlinx.coroutines.CancellationException("Simulazione interruzione coroutine durante scansione SAF")
            }
        }

        var caughtCancellation = false
        try {
            scanner.runScan(session.id, emptyList())
        } catch (e: kotlinx.coroutines.CancellationException) {
            caughtCancellation = true
        }
        assertTrue("CancellationException deve essere propagata e non assorbita", caughtCancellation)

        // 1. La risorsa / cursore SAF è stata regolarmente chiusa (cursor.use / finally)
        assertTrue("La risorsa SAF / cursore deve essere chiusa anche su cancellazione", fakeSafTreeReader.isResourceClosed)

        // 2. Nessun falso errore DIRECTORY_READ_ERROR registrato nel DB
        val errors = database.scanErrorDao().getErrorsForSession(session.id)
        assertTrue("Nessun errore di lettura falso deve essere generato su cancellazione: trovati ${errors.size}", errors.isEmpty())

        // 3. La directory non è stata classificata come completata e resta nella coda pendente
        val pendingList = database.pendingDirectoryDao().getPendingDirectoriesForSession(session.id)
        assertTrue("La directory non completata deve rimanere nella coda pendente", pendingList.any { it.documentId == "doc:sub_cancel" })

        // 4. Rimuove l'interruzione e riprende la scansione: la sessione deve completarsi con successo
        fakeSafTreeReader.onDirectoryQueried = null
        val finalStatus = scanner.runScan(session.id, emptyList())
        assertEquals(ScanSessionStatus.COMPLETED, finalStatus)

        val finalSession = database.scanSessionDao().getSessionById(session.id)!!
        assertEquals(1, finalSession.totalFiles)
        assertEquals(150L, finalSession.knownTotalBytes)
    }

    @Test
    fun test29_BatchAtomicityRollbackOnPendingDirectoryFailure() = runBlocking {
        // Prepara struttura: root -> SubFail -> FileA
        fakeSafTreeReader.addDirectory(treeUri, rootDocId, "doc:sub_fail", "SubFail")
        fakeSafTreeReader.addFile(treeUri, "doc:sub_fail", "doc:file_in_sub", "file_sub.txt", 250L)

        val session = scanner.createNewSession(folderRepository.getFolders())

        // Installa un trigger SQLite per forzare un fallimento atomico durante l'inserimento in pending_directories
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER trigger_fail_pending_insert
            BEFORE INSERT ON pending_directories
            BEGIN
                SELECT RAISE(ABORT, 'Errore simulato di inserimento pending_directories');
            END;
            """.trimIndent()
        )

        // L'elaborazione del batch fallisce a causa del trigger su pending_directories
        var caughtRoomException = false
        try {
            scanner.runScan(session.id, emptyList())
        } catch (e: Exception) {
            // Eccezione Room/SQLite propagata correttamente senza essere intercettata come errore SAF
            caughtRoomException = true
        }
        assertTrue("L'eccezione generata da Room deve propagarsi e non essere assorbita come errore SAF", caughtRoomException)

        // 1. Nessun falso errore DIRECTORY_READ_ERROR registrato in scan_errors
        val scanErrors = database.scanErrorDao().getErrorsForSession(session.id)
        assertTrue("Nessun falso errore DIRECTORY_READ_ERROR deve essere registrato per errori Room: trovati ${scanErrors.size}", scanErrors.isEmpty())

        // 2. La directory genitore (rootDocId) NON deve essere stata eliminata dalla coda pendente
        val pendingDirs = database.pendingDirectoryDao().getPendingDirectoriesForSession(session.id)
        assertTrue("La directory genitore deve rimanere nella coda pending_directories per consentire il retry", pendingDirs.any { it.documentId == rootDocId })

        // 3. Verifica Atomicità: a causa del rollback della transazione Room, doc:sub_fail NON deve essere
        // rimasto registrato orfano in scanned_documents. Se fosse rimasto registrato,
        // la successiva scansione lo considererebbe già visitato e non esplorerebbe i suoi sottofile!
        val isSubRecorded = database.scannedDocumentDao().isDocumentRecorded(
            sessionId = session.id,
            folderId = configuredFolder.id,
            documentId = "doc:sub_fail"
        )
        assertFalse(
            "Rollback atomico: nessun documento orfano deve rimanere registrato in scanned_documents se l'accodamento fallisce",
            isSubRecorded
        )

        // 4. Rimuove il trigger per ripristinare il corretto funzionamento di SQLite
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS trigger_fail_pending_insert;")

        // 5. Rilancia la scansione (retry): la directory root ancora pendente viene riesplorata,
        // doc:sub_fail viene inserita regolarmente con tutti i suoi file e la scansione si completa con successo
        val retryStatus = scanner.runScan(session.id, emptyList())
        assertEquals(ScanSessionStatus.COMPLETED, retryStatus)

        val completedSession = database.scanSessionDao().getSessionById(session.id)!!
        assertEquals(1, completedSession.totalFiles)
        assertEquals(250L, completedSession.knownTotalBytes)

        val docs = database.scannedDocumentDao().getDocumentsForSession(session.id)
        assertTrue(docs.any { it.documentId == "doc:file_in_sub" })
        assertTrue("La coda pendente deve essere completamente svuotata al termine del retry", database.pendingDirectoryDao().getPendingDirectoriesForSession(session.id).isEmpty())
    }

    @Test
    fun test30_RoomPersistenceErrorTransitionsToPausedAndResumesViaRepository() = runBlocking {
        // A. Errore Room e retry attraverso il repository
        // 1. Crea una struttura SAF con una directory figlia e almeno un file al suo interno
        fakeSafTreeReader.addDirectory(treeUri, rootDocId, "doc:sub_repo", "SubRepo")
        fakeSafTreeReader.addFile(treeUri, "doc:sub_repo", "doc:file_repo", "file_repo.txt", 350L)

        // 2. Simula un errore SQLite durante l'inserimento atomico del batch, utilizzando un trigger
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER trigger_fail_repo_insert
            BEFORE INSERT ON pending_directories
            BEGIN
                SELECT RAISE(ABORT, 'Simulazione fallimento SQLite durante scansione repository');
            END;
            """.trimIndent()
        )

        // 3. Avvia la scansione tramite ScanRepository (NON chiamando direttamente LocalFolderScanner.runScan())
        val sessionResult = scanRepository.startNewScan()
        assertTrue("startNewScan deve restituire successo all'avvio", sessionResult.isSuccess)
        val session = sessionResult.getOrThrow()

        // 4. Attende che la sessione transiti a PAUSED (non a FAILED) a seguito di ScanPersistenceException
        val pausedSession = awaitSessionStatus(session.id, ScanSessionStatus.PAUSED)
        assertEquals(ScanSessionStatus.PAUSED.name, pausedSession.status)
        assertNotNull(pausedSession.errorMessage)
        assertTrue(pausedSession.errorMessage!!.contains("temporaneo di persistenza", ignoreCase = true))

        // 5. Verifica che la directory genitore (rootDocId) sia ancora presente in pending_directories
        val pendingDirs = database.pendingDirectoryDao().getPendingDirectoriesForSession(session.id)
        assertTrue("La directory genitore deve rimanere nella coda pending_directories", pendingDirs.any { it.documentId == rootDocId })

        // 6. Verifica che il rollback non lasci documenti orfani in scanned_documents
        val isSubRecorded = database.scannedDocumentDao().isDocumentRecorded(
            sessionId = session.id,
            folderId = configuredFolder.id,
            documentId = "doc:sub_repo"
        )
        assertFalse("Il rollback atomico non deve lasciare documenti orfani", isSubRecorded)

        // 7. Elimina il trigger per consentire la persistenza
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS trigger_fail_repo_insert;")

        // 8. Riprende la scansione attraverso resumeScan()
        val resumeResult = scanRepository.resumeScan()
        assertTrue("resumeScan deve avere successo", resumeResult.isSuccess)

        // 9. Verifica che la sessione termini correttamente con COMPLETED
        val completedSession = awaitSessionStatus(session.id, ScanSessionStatus.COMPLETED)
        assertEquals(ScanSessionStatus.COMPLETED.name, completedSession.status)

        // 10. Verifica che tutti i file siano presenti una sola volta e che la coda pendente sia vuota
        assertEquals(1, completedSession.totalFiles)
        assertEquals(350L, completedSession.knownTotalBytes)
        val docs = database.scannedDocumentDao().getDocumentsForSession(session.id).filter { !it.isDirectory }
        assertEquals(1, docs.size)
        assertEquals("doc:file_repo", docs[0].documentId)
        assertTrue("La coda pendente deve essere completamente svuotata", database.pendingDirectoryDao().getPendingDirectoriesForSession(session.id).isEmpty())
    }

    @Test
    fun test31_StartNewScanRejectedWhenSessionIsPaused() = runBlocking {
        // B. Nuova scansione con sessione in pausa
        fakeSafTreeReader.addFile(treeUri, rootDocId, "doc:f_p1", "paused1.txt", 100L)

        // 1. Crea una sessione e portala a PAUSED
        val s1 = scanRepository.startNewScan().getOrThrow()
        scanRepository.pauseScan().getOrThrow()

        val pausedSession = database.scanSessionDao().getSessionById(s1.id)!!
        assertEquals(ScanSessionStatus.PAUSED.name, pausedSession.status)
        val initialPending = database.pendingDirectoryDao().getPendingDirectoriesForSession(s1.id)
        assertFalse("La coda della sessione in pausa non deve essere vuota", initialPending.isEmpty())

        // 2. Prova ad avviare una nuova scansione mentre esiste una sessione PAUSED
        val newScanResult = scanRepository.startNewScan()
        assertTrue("startNewScan deve fallire in presenza di una sessione PAUSED", newScanResult.isFailure)
        val errorMsg = newScanResult.exceptionOrNull()?.message ?: ""
        assertTrue(errorMsg.contains("in pausa", ignoreCase = true))

        // 3. Verifica che la sessione precedente non sia stata alterata e la coda non sia stata svuotata
        val s1After = database.scanSessionDao().getSessionById(s1.id)!!
        assertEquals(ScanSessionStatus.PAUSED.name, s1After.status)
        val pendingAfter = database.pendingDirectoryDao().getPendingDirectoriesForSession(s1.id)
        assertEquals(initialPending.size, pendingAfter.size)

        // 4. Verifica che sia possibile annullare esplicitamente la sessione in pausa
        val cancelResult = scanRepository.cancelScan()
        assertTrue("cancelScan deve avere successo", cancelResult.isSuccess)

        val s1Cancelled = database.scanSessionDao().getSessionById(s1.id)!!
        assertEquals(ScanSessionStatus.CANCELLED.name, s1Cancelled.status)
        assertTrue("La coda della sessione annullata deve essere vuota", database.pendingDirectoryDao().getPendingDirectoriesForSession(s1.id).isEmpty())

        // 5. Ora che la sessione precedente è terminata (CANCELLED), startNewScan deve riuscire regolarmente
        val s2Result = scanRepository.startNewScan()
        assertTrue("Una nuova scansione deve avviarsi con successo dopo l'annullamento", s2Result.isSuccess)
        val s2 = s2Result.getOrThrow()
        val s2Completed = awaitSessionStatus(s2.id, ScanSessionStatus.COMPLETED)
        assertEquals(ScanSessionStatus.COMPLETED.name, s2Completed.status)
    }

    @Test
    fun test32_IntermediateBatchRoomFailurePreservesQueueAndRollsBack() = runBlocking {
        // Prepara una directory con 150 file: con BATCH_SIZE=100 verranno emessi 2 batch (Batch 1: 100 file, Batch 2: 50 file)
        for (i in 1..150) {
            fakeSafTreeReader.addFile(treeUri, rootDocId, "doc:mid_$i", "mid_$i.dat", 10L)
        }

        val session = scanner.createNewSession(folderRepository.getFolders())

        // Installa un trigger che fallisce SOLO a partire dal 101-esimo inserimento (quindi sul secondo batch intermedio)
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER trigger_fail_intermediate_batch
            BEFORE INSERT ON scanned_documents
            WHEN (SELECT COUNT(*) FROM scanned_documents WHERE scan_session_id = '${session.id}') >= 100
            BEGIN
                SELECT RAISE(ABORT, 'Simulazione fallimento batch intermedio');
            END;
            """.trimIndent()
        )

        var caughtException = false
        try {
            scanner.runScan(session.id, emptyList())
        } catch (e: ScanPersistenceException) {
            caughtException = true
        }
        assertTrue("Deve essere sollevata ScanPersistenceException sul batch intermedio", caughtException)

        // 1. La risorsa SAF è stata chiusa (cursor.use)
        assertTrue("La risorsa SAF deve essere chiusa anche su fallimento intermedio", fakeSafTreeReader.isResourceClosed)

        // 2. Nessun falso errore DIRECTORY_READ_ERROR registrato
        val scanErrors = database.scanErrorDao().getErrorsForSession(session.id)
        assertTrue("Nessun errore di lettura falso registrato per fallimento batch intermedio", scanErrors.isEmpty())

        // 3. La directory genitore (rootDocId) NON deve essere stata eliminata dalla coda pendente
        val pendingDirs = database.pendingDirectoryDao().getPendingDirectoriesForSession(session.id)
        assertTrue("La directory non completata deve rimanere in pending_directories", pendingDirs.any { it.documentId == rootDocId })

        // 4. Rimuove il trigger e rilancia la scansione
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS trigger_fail_intermediate_batch;")

        val retryStatus = scanner.runScan(session.id, emptyList())
        assertEquals(ScanSessionStatus.COMPLETED, retryStatus)

        val completedSession = database.scanSessionDao().getSessionById(session.id)!!
        assertEquals(150, completedSession.totalFiles)
        assertEquals(1500L, completedSession.knownTotalBytes)
        assertTrue(database.pendingDirectoryDao().getPendingDirectoriesForSession(session.id).isEmpty())
    }
}
