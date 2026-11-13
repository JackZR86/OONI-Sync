package com.androidbackup.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.androidbackup.app.data.local.dao.FileFingerprintDao
import com.androidbackup.app.data.local.dao.ScanErrorDao
import com.androidbackup.app.data.local.dao.ScanSessionDao
import com.androidbackup.app.data.local.dao.ScannedDocumentDao
import com.androidbackup.app.data.local.database.BackupDatabase
import com.androidbackup.app.data.local.entity.FileFingerprintEntity
import com.androidbackup.app.data.local.entity.ScanErrorEntity
import com.androidbackup.app.data.local.entity.ScanErrorPhase
import com.androidbackup.app.data.local.entity.ScanErrorSeverity
import com.androidbackup.app.data.local.entity.ScanPhase
import com.androidbackup.app.data.local.entity.ScanSessionEntity
import com.androidbackup.app.data.local.entity.ScanSessionStatus
import com.androidbackup.app.data.local.entity.ScannedDocumentEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Test dedicati per [FileFingerprintDao], con focus sulla protezione atomica di
 * [FileFingerprintDao.deleteStaleForFolderNotInSession] (Revisione Fase 1B.3A).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class FileFingerprintDaoTest {

    private lateinit var database: BackupDatabase
    private lateinit var fingerprintDao: FileFingerprintDao
    private lateinit var sessionDao: ScanSessionDao
    private lateinit var documentDao: ScannedDocumentDao
    private lateinit var errorDao: ScanErrorDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BackupDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fingerprintDao = database.fileFingerprintDao()
        sessionDao = database.scanSessionDao()
        documentDao = database.scannedDocumentDao()
        errorDao = database.scanErrorDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testDeleteStaleDeletesOnlyStaleForTargetFolderWhenSessionCompletedWithoutDiscoveryErrors() = runBlocking {
        seedBaselineFingerprints()
        val sessionId = "sess-completed-clean"
        insertSession(
            id = sessionId,
            status = ScanSessionStatus.COMPLETED,
            scanPhase = ScanPhase.COMPLETED
        )
        // Inventario corrente di folder-a: keep-doc-1 (incluso), dir-1 (directory), excluded-doc-1 (escluso)
        // stale-doc-1 non è presente nell'inventario corrente; excluded-doc-1 è escluso -> entrambe le fingerprint vanno eliminate
        documentDao.insertDocuments(
            listOf(
                makeDoc(sessionId, "folder-a", "keep-doc-1", isDirectory = false, isExcluded = false),
                makeDoc(sessionId, "folder-a", "dir-1", isDirectory = true, isExcluded = false),
                makeDoc(sessionId, "folder-a", "excluded-doc-1", isDirectory = false, isExcluded = true)
            )
        )

        val deleted = fingerprintDao.deleteStaleForFolderNotInSession("folder-a", sessionId)

        assertEquals("Devono essere eliminate esattamente le 2 impronte obsolete/escluse di folder-a", 2, deleted)
        assertNotNull(fingerprintDao.getByFolderAndDocument("folder-a", "keep-doc-1"))
        assertNull(fingerprintDao.getByFolderAndDocument("folder-a", "stale-doc-1"))
        assertNull(fingerprintDao.getByFolderAndDocument("folder-a", "excluded-doc-1"))
        // Le impronte di folder-b non devono mai essere toccate
        assertEquals(2, fingerprintDao.countByFolder("folder-b"))
        assertNotNull(fingerprintDao.getByFolderAndDocument("folder-b", "other-keep-1"))
        assertNotNull(fingerprintDao.getByFolderAndDocument("folder-b", "other-stale-1"))
    }

    @Test
    fun testDeleteStaleReturnsZeroWhenSessionIsRunningOrPaused() = runBlocking {
        seedBaselineFingerprints()

        // Caso RUNNING con scanPhase DISCOVERY
        insertSession("sess-running", ScanSessionStatus.RUNNING, ScanPhase.DISCOVERY)
        documentDao.insertDocument(makeDoc("sess-running", "folder-a", "keep-doc-1"))
        assertEquals(0, fingerprintDao.deleteStaleForFolderNotInSession("folder-a", "sess-running"))
        assertEquals(3, fingerprintDao.countByFolder("folder-a"))

        // Caso RUNNING anche se scanPhase fosse FINGERPRINTING
        insertSession("sess-running-fp", ScanSessionStatus.RUNNING, ScanPhase.FINGERPRINTING)
        documentDao.insertDocument(makeDoc("sess-running-fp", "folder-a", "keep-doc-1"))
        assertEquals(0, fingerprintDao.deleteStaleForFolderNotInSession("folder-a", "sess-running-fp"))
        assertEquals(3, fingerprintDao.countByFolder("folder-a"))

        // Caso PAUSED
        insertSession("sess-paused", ScanSessionStatus.PAUSED, ScanPhase.DISCOVERY)
        documentDao.insertDocument(makeDoc("sess-paused", "folder-a", "keep-doc-1"))
        assertEquals(0, fingerprintDao.deleteStaleForFolderNotInSession("folder-a", "sess-paused"))
        assertEquals(3, fingerprintDao.countByFolder("folder-a"))
    }

    @Test
    fun testDeleteStaleReturnsZeroWhenSessionIsCancelledOrFailed() = runBlocking {
        seedBaselineFingerprints()

        insertSession("sess-cancelled", ScanSessionStatus.CANCELLED, ScanPhase.COMPLETED)
        documentDao.insertDocument(makeDoc("sess-cancelled", "folder-a", "keep-doc-1"))
        assertEquals(0, fingerprintDao.deleteStaleForFolderNotInSession("folder-a", "sess-cancelled"))
        assertEquals(3, fingerprintDao.countByFolder("folder-a"))

        insertSession("sess-failed", ScanSessionStatus.FAILED, ScanPhase.COMPLETED)
        documentDao.insertDocument(makeDoc("sess-failed", "folder-a", "keep-doc-1"))
        assertEquals(0, fingerprintDao.deleteStaleForFolderNotInSession("folder-a", "sess-failed"))
        assertEquals(3, fingerprintDao.countByFolder("folder-a"))

        // Anche COMPLETED con scanPhase non ancora COMPLETED non deve cancellare nulla
        insertSession("sess-phase-incomplete", ScanSessionStatus.COMPLETED, ScanPhase.FINGERPRINTING)
        documentDao.insertDocument(makeDoc("sess-phase-incomplete", "folder-a", "keep-doc-1"))
        assertEquals(0, fingerprintDao.deleteStaleForFolderNotInSession("folder-a", "sess-phase-incomplete"))
        assertEquals(3, fingerprintDao.countByFolder("folder-a"))
    }

    @Test
    fun testDeleteStaleReturnsZeroWhenDiscoveryErrorExistsForTargetFolder() = runBlocking {
        seedBaselineFingerprints()
        val sessionId = "sess-disc-err"
        insertSession(
            id = sessionId,
            status = ScanSessionStatus.COMPLETED_WITH_ERRORS,
            scanPhase = ScanPhase.COMPLETED
        )
        documentDao.insertDocument(makeDoc(sessionId, "folder-a", "keep-doc-1"))
        errorDao.insertError(
            ScanErrorEntity(
                scanSessionId = sessionId,
                backupFolderId = "folder-a",
                documentId = "sub-dir",
                uri = "content://test/sub-dir",
                errorType = "DIRECTORY_READ_ERROR",
                message = "Errore lettura sottocartella",
                errorPhase = ScanErrorPhase.DISCOVERY.name,
                severity = ScanErrorSeverity.ERROR.name
            )
        )

        val deleted = fingerprintDao.deleteStaleForFolderNotInSession("folder-a", sessionId)

        assertEquals(0, deleted)
        assertEquals(3, fingerprintDao.countByFolder("folder-a"))
    }

    @Test
    fun testDeleteStaleAllowedWhenSessionHasOnlyFingerprintingErrorsOrUnsupportedOrWarnings() = runBlocking {
        seedBaselineFingerprints()
        val sessionId = "sess-fp-err-and-warnings"
        insertSession(
            id = sessionId,
            status = ScanSessionStatus.COMPLETED_WITH_ERRORS,
            scanPhase = ScanPhase.COMPLETED
        )
        documentDao.insertDocument(makeDoc(sessionId, "folder-a", "keep-doc-1"))

        errorDao.insertErrors(
            listOf(
                ScanErrorEntity(
                    scanSessionId = sessionId,
                    backupFolderId = "folder-a",
                    documentId = "keep-doc-1",
                    uri = "content://test/keep-doc-1",
                    errorType = "HASH_IO_ERROR",
                    message = "Errore lettura stream durante fingerprinting",
                    errorPhase = ScanErrorPhase.FINGERPRINTING.name,
                    severity = ScanErrorSeverity.ERROR.name
                ),
                ScanErrorEntity(
                    scanSessionId = sessionId,
                    backupFolderId = "folder-a",
                    documentId = "virtual-1",
                    uri = "content://test/virtual-1",
                    errorType = "VIRTUAL_DOCUMENT_UNSUPPORTED",
                    message = "File virtuale non supportato",
                    errorPhase = ScanErrorPhase.DISCOVERY.name,
                    severity = ScanErrorSeverity.UNSUPPORTED.name
                ),
                ScanErrorEntity(
                    scanSessionId = sessionId,
                    backupFolderId = "folder-a",
                    documentId = "warn-1",
                    uri = "content://test/warn-1",
                    errorType = "METADATA_WARNING",
                    message = "Avviso informativo metadati",
                    errorPhase = ScanErrorPhase.DISCOVERY.name,
                    severity = ScanErrorSeverity.WARNING.name
                )
            )
        )

        val deleted = fingerprintDao.deleteStaleForFolderNotInSession("folder-a", sessionId)

        assertEquals(2, deleted)
        assertNotNull(fingerprintDao.getByFolderAndDocument("folder-a", "keep-doc-1"))
        assertNull(fingerprintDao.getByFolderAndDocument("folder-a", "stale-doc-1"))
        assertNull(fingerprintDao.getByFolderAndDocument("folder-a", "excluded-doc-1"))
        assertEquals(2, fingerprintDao.countByFolder("folder-b"))
    }

    @Test
    fun testDeleteStaleAllowedForCleanFolderEvenWhenOtherFolderHasDiscoveryErrorInSameSession() = runBlocking {
        seedBaselineFingerprints()
        val sessionId = "sess-other-folder-err"
        insertSession(
            id = sessionId,
            status = ScanSessionStatus.COMPLETED_WITH_ERRORS,
            scanPhase = ScanPhase.COMPLETED
        )
        documentDao.insertDocuments(
            listOf(
                makeDoc(sessionId, "folder-a", "keep-doc-1"),
                makeDoc(sessionId, "folder-b", "other-keep-1")
            )
        )
        // Errore Discovery ERROR solo su folder-b
        errorDao.insertError(
            ScanErrorEntity(
                scanSessionId = sessionId,
                backupFolderId = "folder-b",
                documentId = "bad-dir-b",
                uri = "content://test/bad-dir-b",
                errorType = "PERMISSION_REVOKED",
                message = "Permesso revocato su folder-b",
                errorPhase = ScanErrorPhase.DISCOVERY.name,
                severity = ScanErrorSeverity.ERROR.name
            )
        )

        // folder-a è pulita -> pulizia consentita
        val deletedA = fingerprintDao.deleteStaleForFolderNotInSession("folder-a", sessionId)
        assertEquals(2, deletedA)
        assertEquals(1, fingerprintDao.countByFolder("folder-a"))

        // folder-b ha errore Discovery -> protetta
        val deletedB = fingerprintDao.deleteStaleForFolderNotInSession("folder-b", sessionId)
        assertEquals(0, deletedB)
        assertEquals(2, fingerprintDao.countByFolder("folder-b"))
    }

    @Test
    fun testDeleteStaleReturnsZeroWhenSessionDoesNotExist() = runBlocking {
        seedBaselineFingerprints()

        val deleted = fingerprintDao.deleteStaleForFolderNotInSession("folder-a", "non-existent-session")

        assertEquals(0, deleted)
        assertEquals(3, fingerprintDao.countByFolder("folder-a"))
        assertEquals(2, fingerprintDao.countByFolder("folder-b"))
    }

    private suspend fun seedBaselineFingerprints() {
        fingerprintDao.upsertFingerprints(
            listOf(
                makeFingerprint("folder-a", "keep-doc-1"),
                makeFingerprint("folder-a", "stale-doc-1"),
                makeFingerprint("folder-a", "excluded-doc-1"),
                makeFingerprint("folder-b", "other-keep-1"),
                makeFingerprint("folder-b", "other-stale-1")
            )
        )
    }

    private suspend fun insertSession(
        id: String,
        status: ScanSessionStatus,
        scanPhase: ScanPhase
    ) {
        sessionDao.insertSession(
            ScanSessionEntity(
                id = id,
                startedTimestamp = 1000L,
                completedTimestamp = if (scanPhase == ScanPhase.COMPLETED) 2000L else null,
                status = status.name,
                scanPhase = scanPhase.name
            )
        )
    }

    private fun makeFingerprint(folderId: String, docId: String) = FileFingerprintEntity(
        id = "fp-$folderId-$docId",
        backupFolderId = folderId,
        documentId = docId,
        documentUri = "content://test/$folderId/$docId",
        relativePath = "$docId.bin",
        mimeType = "application/octet-stream",
        sizeBytes = 1024L,
        lastModifiedTimestamp = 1500L,
        computedBytes = 1024L,
        sha256Hex = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        lastContentHashedTimestamp = 1600L,
        lastMetadataMatchedTimestamp = 1600L,
        lastSeenSessionId = "seed-session"
    )

    private fun makeDoc(
        sessionId: String,
        folderId: String,
        docId: String,
        isDirectory: Boolean = false,
        isExcluded: Boolean = false
    ) = ScannedDocumentEntity(
        id = "doc-$sessionId-$folderId-$docId",
        scanSessionId = sessionId,
        backupFolderId = folderId,
        documentId = docId,
        documentUri = "content://test/$folderId/$docId",
        relativePath = docId,
        displayName = docId,
        mimeType = if (isDirectory) "vnd.android.document/directory" else "application/octet-stream",
        sizeBytes = if (isDirectory) null else 1024L,
        lastModifiedTimestamp = 1500L,
        isDirectory = isDirectory,
        isExcluded = isExcluded
    )
}
