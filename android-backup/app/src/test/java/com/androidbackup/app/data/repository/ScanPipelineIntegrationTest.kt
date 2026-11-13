package com.androidbackup.app.data.repository

import android.content.Context
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.androidbackup.app.data.local.dao.FolderDao
import com.androidbackup.app.data.local.database.BackupDatabase
import com.androidbackup.app.data.local.entity.BackupFolderEntity
import com.androidbackup.app.data.local.entity.FingerprintMode
import com.androidbackup.app.data.local.entity.FingerprintStatus
import com.androidbackup.app.data.local.entity.ScanErrorPhase
import com.androidbackup.app.data.local.entity.ScanErrorSeverity
import com.androidbackup.app.data.local.entity.ScanPhase
import com.androidbackup.app.data.local.entity.ScanSessionEntity
import com.androidbackup.app.data.local.entity.ScanSessionStatus
import com.androidbackup.app.data.model.Priority
import com.androidbackup.app.data.saf.SafContentReader
import com.androidbackup.app.data.saf.SafDocumentMetadata
import com.androidbackup.app.data.scan.LocalFileFingerprinter
import com.androidbackup.app.data.scan.LocalFolderScanner
import com.androidbackup.app.data.scan.Sha256StreamHasher
import com.androidbackup.app.testutils.FakeSafContentReader
import com.androidbackup.app.testutils.FakeSafPermissionManager
import com.androidbackup.app.testutils.FakeSafTreeReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
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
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Suite di integrazione end-to-end della pipeline completa Discovery -> Fingerprinting (Fase 1B.3C).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ScanPipelineIntegrationTest {

    private lateinit var database: BackupDatabase
    private lateinit var treeReader: FakeSafTreeReader
    private lateinit var contentReader: ControllableSafContentReader
    private lateinit var permissionManager: FakeSafPermissionManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var lifecycleCoordinator: FolderLifecycleCoordinator
    private lateinit var delegatingFolderDao: PipelineDelegatingFolderDao
    private lateinit var folderRepository: FolderRepository
    private lateinit var scanner: LocalFolderScanner
    private lateinit var fingerprinter: LocalFileFingerprinter
    private lateinit var scanRepository: ScanRepository
    private lateinit var currentTimeMs: AtomicLong

    private val treeUriA = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AFolderA")
    private val rootDocA = "primary:FolderA"
    private val folderIdA = "folder-a"

    private val treeUriB = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AFolderB")
    private val rootDocB = "primary:FolderB"
    private val folderIdB = "folder-b"

    @Before
    fun setUp() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            database = Room.inMemoryDatabaseBuilder(context, BackupDatabase::class.java)
                .allowMainThreadQueries()
                .build()

            treeReader = FakeSafTreeReader()
            contentReader = ControllableSafContentReader(FakeSafContentReader())
            permissionManager = FakeSafPermissionManager()
            settingsRepository = SettingsRepository(database.settingsDao())
            settingsRepository.initializeDefaultsIfEmpty()

            lifecycleCoordinator = FolderLifecycleCoordinator()
            delegatingFolderDao = PipelineDelegatingFolderDao(database.folderDao())
            currentTimeMs = AtomicLong(1_700_000_000_000L)

            folderRepository = buildFolderRepository()
            scanner = buildScanner()
            fingerprinter = buildFingerprinter()
            scanRepository = buildScanRepository()

            permissionManager.takePersistableReadPermission(treeUriA)
            database.folderDao().insertFolder(
                BackupFolderEntity(
                    id = folderIdA,
                    treeUri = treeUriA.toString(),
                    displayName = "FolderA",
                    authority = "com.android.externalstorage.documents",
                    addedTimestamp = 1000L,
                    priority = Priority.ALTA.name,
                    protectFromEviction = false,
                    enabled = true
                )
            )
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            scanRepository.awaitActiveJobCompletion()
        }
        database.close()
    }

    private fun buildFolderRepository(): FolderRepository {
        return FolderRepository(
            folderDao = delegatingFolderDao,
            safPermissionManager = permissionManager,
            scanSessionDao = database.scanSessionDao(),
            fileFingerprintDao = database.fileFingerprintDao(),
            lifecycleCoordinator = lifecycleCoordinator,
            runInTransaction = { block -> database.withTransaction { block() } }
        )
    }

    private fun buildScanner(): LocalFolderScanner {
        return LocalFolderScanner(
            scanSessionDao = database.scanSessionDao(),
            scannedDocumentDao = database.scannedDocumentDao(),
            pendingDirectoryDao = database.pendingDirectoryDao(),
            scanErrorDao = database.scanErrorDao(),
            safTreeReader = treeReader,
            safPermissionManager = permissionManager
        )
    }

    private fun buildFingerprinter(): LocalFileFingerprinter {
        return LocalFileFingerprinter(
            fileFingerprintDao = database.fileFingerprintDao(),
            scannedDocumentDao = database.scannedDocumentDao(),
            scanErrorDao = database.scanErrorDao(),
            safContentReader = contentReader,
            safPermissionManager = permissionManager,
            streamHasher = Sha256StreamHasher(),
            runInTransaction = { block -> database.withTransaction { block() } },
            nowProvider = { currentTimeMs.get() }
        )
    }

    private fun buildScanRepository(stopTimeoutMs: Long = 10_000L): ScanRepository {
        return ScanRepository(
            scanSessionDao = database.scanSessionDao(),
            scannedDocumentDao = database.scannedDocumentDao(),
            pendingDirectoryDao = database.pendingDirectoryDao(),
            scanErrorDao = database.scanErrorDao(),
            fileFingerprintDao = database.fileFingerprintDao(),
            folderDao = delegatingFolderDao,
            scanner = scanner,
            fingerprinter = fingerprinter,
            safPermissionManager = permissionManager,
            settingsRepository = settingsRepository,
            lifecycleCoordinator = lifecycleCoordinator,
            runInTransaction = { block -> database.withTransaction { block() } },
            stopTimeoutMs = stopTimeoutMs
        )
    }

    private fun docUri(treeUri: Uri, documentId: String): Uri {
        return try {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        } catch (t: Throwable) {
            treeUri.buildUpon().appendPath("document").appendPath(documentId).build()
        }
    }

    private fun addFile(
        treeUri: Uri,
        parentDocId: String,
        docId: String,
        name: String,
        payload: ByteArray,
        mimeType: String = "application/octet-stream",
        lastModified: Long = 1_690_000_000_000L,
        flags: Int = 0
    ) {
        treeReader.addFile(
            treeUri = treeUri,
            parentDocumentId = parentDocId,
            documentId = docId,
            displayName = name,
            sizeBytes = payload.size.toLong(),
            mimeType = mimeType,
            lastModified = lastModified
        )
        contentReader.base.putContent(
            documentUri = docUri(treeUri, docId),
            bytes = payload,
            metadata = SafDocumentMetadata(
                documentId = docId,
                displayName = name,
                mimeType = mimeType,
                sizeBytes = payload.size.toLong(),
                lastModifiedTimestamp = lastModified,
                flags = flags,
                isDirectory = false
            )
        )
    }

    private suspend fun awaitSessionStatus(
        sessionId: String,
        vararg targets: ScanSessionStatus,
        timeoutMs: Long = 10_000L
    ): ScanSessionEntity {
        val targetNames = targets.map { it.name }.toSet()
        return withTimeout(timeoutMs) {
            database.scanSessionDao().observeLatestSession()
                .filterNotNull()
                .filter { it.id == sessionId && it.status in targetNames }
                .first()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun test1_EndToEndPipelineDiscoveryAndFingerprintingPersistsCacheAndStats() = runBlocking {
        val p1 = "Primo file di backup".toByteArray()
        val p2 = ByteArray(128 * 1024) { (it % 251).toByte() }
        addFile(treeUriA, rootDocA, "doc:1", "uno.txt", p1, mimeType = "text/plain")
        treeReader.addDirectory(treeUriA, rootDocA, "doc:sub", "sub")
        addFile(treeUriA, "doc:sub", "doc:2", "due.bin", p2)

        val session = scanRepository.startNewScan().getOrThrow()
        val completed = awaitSessionStatus(session.id, ScanSessionStatus.COMPLETED)
        scanRepository.awaitActiveJobCompletion()

        assertEquals(ScanPhase.COMPLETED.name, completed.scanPhase)
        assertEquals(ScanSessionStatus.COMPLETED.name, completed.status)
        assertNotNull(completed.completedTimestamp)
        assertEquals(2, completed.totalFiles)
        assertEquals(1, completed.totalDirectories)
        assertEquals(p1.size.toLong() + p2.size.toLong(), completed.knownTotalBytes)
        assertEquals(2, completed.fingerprintsTotal)
        assertEquals(2, completed.fingerprintsComputed)
        assertEquals(0, completed.fingerprintsCached)
        assertEquals(0, completed.fingerprintsUnsupported)
        assertEquals(0, completed.fingerprintsFailed)
        assertEquals(p1.size.toLong() + p2.size.toLong(), completed.fingerprintsBytesHashed)
        assertEquals(0, completed.errorCount)

        val docs = database.scannedDocumentDao().getIncludedFilesForSession(session.id)
        assertEquals(2, docs.size)
        val d1 = docs.first { it.documentId == "doc:1" }
        val d2 = docs.first { it.documentId == "doc:2" }
        assertEquals(FingerprintStatus.COMPUTED.name, d1.fingerprintStatus)
        assertEquals(sha256Hex(p1), d1.sha256Hex)
        assertEquals(FingerprintStatus.COMPUTED.name, d2.fingerprintStatus)
        assertEquals(sha256Hex(p2), d2.sha256Hex)

        val cacheEntries = database.fileFingerprintDao().getByFolder(folderIdA)
        assertEquals(2, cacheEntries.size)
    }

    @Test
    fun test2_MixedOutcomesAndUnsupportedDoesNotTriggerCompletedWithErrors() = runBlocking {
        // Caso A: solo COMPUTED + REUSED_CACHE + UNSUPPORTED -> stato finale COMPLETED
        val pOk = "Documento regolare".toByteArray()
        addFile(treeUriA, rootDocA, "doc:ok", "ok.txt", pOk, mimeType = "text/plain")
        addFile(
            treeUri = treeUriA,
            parentDocId = rootDocA,
            docId = "doc:virt",
            name = "virtuale.gdoc",
            payload = ByteArray(0),
            mimeType = "application/vnd.google-apps.document",
            flags = DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT
        )

        val s1 = scanRepository.startNewScan().getOrThrow()
        val s1Done = awaitSessionStatus(s1.id, ScanSessionStatus.COMPLETED)
        scanRepository.awaitActiveJobCompletion()

        assertEquals(ScanSessionStatus.COMPLETED.name, s1Done.status)
        assertEquals(0, s1Done.errorCount)
        assertEquals(1, s1Done.fingerprintsComputed)
        assertEquals(1, s1Done.fingerprintsUnsupported)
        assertEquals(0, s1Done.fingerprintsFailed)

        val s1Errors = database.scanErrorDao().getErrorsForSession(s1.id)
        assertEquals(1, s1Errors.size)
        assertEquals(ScanErrorSeverity.UNSUPPORTED.name, s1Errors[0].severity)

        // Caso B: seconda scansione con ok (REUSED_CACHE), new_ok (COMPUTED), virt (UNSUPPORTED), bad (FAILED)
        val pNew = "Nuovo contenuto".toByteArray()
        addFile(treeUriA, rootDocA, "doc:new", "new.txt", pNew, mimeType = "text/plain")
        treeReader.addFile(treeUriA, rootDocA, "doc:bad", "bad.bin", 500L)
        val badUri = docUri(treeUriA, "doc:bad")
        contentReader.base.putMetadata(
            badUri,
            SafDocumentMetadata(
                documentId = "doc:bad",
                displayName = "bad.bin",
                mimeType = "application/octet-stream",
                sizeBytes = 500L,
                lastModifiedTimestamp = 1_690_000_000_000L,
                flags = 0,
                isDirectory = false
            )
        )
        contentReader.base.setOpenInputStreamError(badUri, IOException("Lettura provider fallita"))

        val s2 = scanRepository.startNewScan().getOrThrow()
        val s2Done = awaitSessionStatus(s2.id, ScanSessionStatus.COMPLETED_WITH_ERRORS)
        scanRepository.awaitActiveJobCompletion()

        assertEquals(ScanPhase.COMPLETED.name, s2Done.scanPhase)
        assertEquals(4, s2Done.fingerprintsTotal)
        assertEquals(1, s2Done.fingerprintsComputed)
        assertEquals(1, s2Done.fingerprintsCached)
        assertEquals(1, s2Done.fingerprintsUnsupported)
        assertEquals(1, s2Done.fingerprintsFailed)
        assertEquals(pNew.size.toLong(), s2Done.fingerprintsBytesHashed)
        assertEquals(1, s2Done.errorCount)
    }

    @Test
    fun test3_ModesAndTtlBehaviorAcrossPauseAndResume() = runBlocking {
        val payload = "Dati stabili per verifica TTL".toByteArray()
        addFile(treeUriA, rootDocA, "doc:ttl", "ttl.txt", payload, mimeType = "text/plain")

        // Scan 1: popola cache al tempo t0
        val s1 = scanRepository.startNewScan(mode = FingerprintMode.INCREMENTAL).getOrThrow()
        awaitSessionStatus(s1.id, ScanSessionStatus.COMPLETED)
        scanRepository.awaitActiveJobCompletion()

        // Avanza di 3 giorni: con PERIODIC_REVERIFY e TTL null (default 7 giorni) deve riusare la cache
        currentTimeMs.addAndGet(3L * 24L * 60L * 60L * 1000L)
        val s2 = scanRepository.startNewScan(
            mode = FingerprintMode.PERIODIC_REVERIFY,
            maxCacheAgeMs = null
        ).getOrThrow()
        val s2Done = awaitSessionStatus(s2.id, ScanSessionStatus.COMPLETED)
        scanRepository.awaitActiveJobCompletion()

        assertEquals(FingerprintMode.PERIODIC_REVERIFY.name, s2Done.fingerprintMode)
        assertNull(s2Done.fingerprintMaxCacheAgeMs)
        assertEquals(1, s2Done.fingerprintsCached)
        assertEquals(0, s2Done.fingerprintsComputed)

        // Con TTL <= 0 in PERIODIC_REVERIFY la cache è sempre considerata scaduta
        val s3 = scanRepository.startNewScan(
            mode = FingerprintMode.PERIODIC_REVERIFY,
            maxCacheAgeMs = 0L
        ).getOrThrow()
        val s3Done = awaitSessionStatus(s3.id, ScanSessionStatus.COMPLETED)
        scanRepository.awaitActiveJobCompletion()

        assertEquals(0L, s3Done.fingerprintMaxCacheAgeMs)
        assertEquals(1, s3Done.fingerprintsComputed)
        assertEquals(0, s3Done.fingerprintsCached)

        // FORCE_RECOMPUTE preservato anche dopo pausa e ripresa
        val secondPayload = "Secondo file per pausa".toByteArray()
        treeReader.addFile(treeUriA, rootDocA, "doc:ttl2", "ttl2.txt", secondPayload.size.toLong())
        val ttl2Uri = docUri(treeUriA, "doc:ttl2")
        val ttl2Meta = SafDocumentMetadata(
            documentId = "doc:ttl2",
            displayName = "ttl2.txt",
            mimeType = "application/octet-stream",
            sizeBytes = secondPayload.size.toLong(),
            lastModifiedTimestamp = 1_690_000_000_000L,
            flags = 0,
            isDirectory = false
        )
        val ttl2Entered = CountDownLatch(1)
        contentReader.base.putMetadata(ttl2Uri, ttl2Meta)
        contentReader.base.setBlockingStream(ttl2Uri, onBlockedEntered = { ttl2Entered.countDown() })

        val s4 = scanRepository.startNewScan(mode = FingerprintMode.FORCE_RECOMPUTE).getOrThrow()
        assertTrue(ttl2Entered.await(5, TimeUnit.SECONDS))
        scanRepository.pauseScan().getOrThrow()

        val paused = database.scanSessionDao().getSessionById(s4.id)!!
        assertEquals(ScanSessionStatus.PAUSED.name, paused.status)
        assertEquals(FingerprintMode.FORCE_RECOMPUTE.name, paused.fingerprintMode)

        contentReader.base.putContent(ttl2Uri, secondPayload, ttl2Meta)
        scanRepository.resumeScan().getOrThrow()
        val s4Done = awaitSessionStatus(s4.id, ScanSessionStatus.COMPLETED)
        scanRepository.awaitActiveJobCompletion()
        assertEquals(2, s4Done.fingerprintsComputed)
        assertEquals(0, s4Done.fingerprintsCached)
    }

    @Test
    fun test4_ChunkedProcessingBeyondBatchSizeUpdatesStatsFromRoom() = runBlocking {
        val fileCount = 125
        val perFileSize = 16
        for (i in 1..fileCount) {
            val bytes = ByteArray(perFileSize) { ((i + it) and 0xFF).toByte() }
            addFile(treeUriA, rootDocA, "doc:chunk_$i", "chunk_%03d.dat".format(i), bytes)
        }

        val session = scanRepository.startNewScan().getOrThrow()
        val done = awaitSessionStatus(session.id, ScanSessionStatus.COMPLETED)
        scanRepository.awaitActiveJobCompletion()

        assertEquals(fileCount, done.totalFiles)
        assertEquals(fileCount, done.fingerprintsTotal)
        assertEquals(fileCount, done.fingerprintsComputed)
        assertEquals((fileCount * perFileSize).toLong(), done.fingerprintsBytesHashed)
        assertTrue(
            database.scannedDocumentDao().getNextPendingFingerprintBatch(session.id, 50).isEmpty()
        )
    }

    @Test
    fun test5_PauseDuringDiscoveryAndResumeCompletesBothPhases() = runBlocking {
        treeReader.addDirectory(treeUriA, rootDocA, "doc:d1", "d1")
        addFile(treeUriA, "doc:d1", "doc:f1", "f1.txt", "uno".toByteArray())
        treeReader.addDirectory(treeUriA, rootDocA, "doc:d2", "d2")
        addFile(treeUriA, "doc:d2", "doc:f2", "f2.txt", "due".toByteArray())

        val enteredDirLatch = CountDownLatch(1)
        treeReader.onDirectoryQueried = { parentId ->
            if (parentId == "doc:d1") {
                enteredDirLatch.countDown()
            }
        }

        val session = scanRepository.startNewScan().getOrThrow()
        assertTrue(enteredDirLatch.await(5, TimeUnit.SECONDS))
        scanRepository.pauseScan().getOrThrow()

        val paused = database.scanSessionDao().getSessionById(session.id)!!
        assertEquals(ScanSessionStatus.PAUSED.name, paused.status)
        assertEquals(ScanPhase.DISCOVERY.name, paused.scanPhase)

        treeReader.onDirectoryQueried = null
        scanRepository.resumeScan().getOrThrow()
        val done = awaitSessionStatus(session.id, ScanSessionStatus.COMPLETED)
        scanRepository.awaitActiveJobCompletion()

        assertEquals(ScanPhase.COMPLETED.name, done.scanPhase)
        assertEquals(2, done.totalFiles)
        assertEquals(2, done.fingerprintsComputed)
    }

    @Test
    fun test6_PauseDuringFingerprintingWithAbortActiveStreamResumesWithoutRehashingCompleted() = runBlocking {
        val p1 = "primo-completo".toByteArray()
        addFile(treeUriA, rootDocA, "doc:01_fast", "01_fast.txt", p1)

        treeReader.addFile(treeUriA, rootDocA, "doc:02_slow", "02_slow.txt", 128L)
        val slowUri = docUri(treeUriA, "doc:02_slow")
        val slowMeta = SafDocumentMetadata(
            documentId = "doc:02_slow",
            displayName = "02_slow.txt",
            mimeType = "application/octet-stream",
            sizeBytes = 128L,
            lastModifiedTimestamp = 1_690_000_000_000L,
            flags = 0,
            isDirectory = false
        )
        contentReader.base.putMetadata(slowUri, slowMeta)
        val blockedEntered = CountDownLatch(1)
        contentReader.base.setBlockingStream(
            documentUri = slowUri,
            initialBytes = byteArrayOf(1, 2, 3),
            onBlockedEntered = { blockedEntered.countDown() }
        )

        val session = scanRepository.startNewScan().getOrThrow()
        assertTrue("Lo stream lento deve entrare in lettura", blockedEntered.await(5, TimeUnit.SECONDS))

        // Pausa mentre doc:02_slow è bloccato: abortActiveStream() chiude lo stream e sblocca subito
        scanRepository.pauseScan().getOrThrow()

        val paused = database.scanSessionDao().getSessionById(session.id)!!
        assertEquals(ScanSessionStatus.PAUSED.name, paused.status)
        assertEquals(ScanPhase.FINGERPRINTING.name, paused.scanPhase)
        assertEquals(1, paused.fingerprintsComputed)

        val docsAtPause = database.scannedDocumentDao().getIncludedFilesForSession(session.id)
        val fastDoc = docsAtPause.first { it.documentId == "doc:01_fast" }
        val slowDoc = docsAtPause.first { it.documentId == "doc:02_slow" }
        assertEquals(FingerprintStatus.COMPUTED.name, fastDoc.fingerprintStatus)
        assertEquals(FingerprintStatus.PENDING.name, slowDoc.fingerprintStatus)

        // Prepara contenuto valido per 02_slow, azzera tracciamento aperture e riprendi
        val p2 = ByteArray(128) { it.toByte() }
        contentReader.base.openedUris.clear()
        contentReader.base.putContent(slowUri, p2, slowMeta)

        scanRepository.resumeScan().getOrThrow()
        val done = awaitSessionStatus(session.id, ScanSessionStatus.COMPLETED)
        scanRepository.awaitActiveJobCompletion()

        assertEquals(2, done.fingerprintsComputed)
        assertEquals(p1.size.toLong() + p2.size.toLong(), done.fingerprintsBytesHashed)
        // doc:01_fast NON deve essere stato riaperto alla ripresa
        assertFalse(contentReader.base.openedUris.contains(docUri(treeUriA, "doc:01_fast")))
        assertTrue(contentReader.base.openedUris.contains(slowUri))
    }

    @Test
    fun test7_SafStreamIgnoringCloseTriggersBoundedTimeoutAndDeferredPauseCompletion() = runBlocking {
        scanRepository = buildScanRepository(stopTimeoutMs = 150L)

        val p1 = "gia-calcolato".toByteArray()
        addFile(treeUriA, rootDocA, "doc:a_fast", "a_fast.txt", p1)

        val stuckPayload = "contenuto-lento-ignorato".toByteArray()
        treeReader.addFile(treeUriA, rootDocA, "doc:b_stuck", "b_stuck.txt", stuckPayload.size.toLong())
        val stuckUri = docUri(treeUriA, "doc:b_stuck")
        val stuckMeta = SafDocumentMetadata(
            documentId = "doc:b_stuck",
            displayName = "b_stuck.txt",
            mimeType = "application/octet-stream",
            sizeBytes = stuckPayload.size.toLong(),
            lastModifiedTimestamp = 1_690_000_000_000L,
            flags = 0,
            isDirectory = false
        )
        val enteredReadLatch = CountDownLatch(1)
        val releaseReadLatch = CountDownLatch(1)
        contentReader.base.putMetadata(stuckUri, stuckMeta)
        contentReader.setUnclosableStream(stuckUri) {
            UnclosableDelayedInputStream(
                payload = stuckPayload,
                onReadBlocked = { enteredReadLatch.countDown() },
                releaseLatch = releaseReadLatch
            )
        }

        val session = scanRepository.startNewScan().getOrThrow()
        assertTrue(enteredReadLatch.await(5, TimeUnit.SECONDS))

        // Pausa con provider che ignora close(): scade il timeout e lancia ScanStopPendingException
        val pauseResult = scanRepository.pauseScan()
        assertTrue("pauseScan deve restituire failure controllata dopo timeout", pauseResult.isFailure)
        val stopEx = pauseResult.exceptionOrNull()
        assertTrue(stopEx is ScanStopPendingException)
        assertEquals(PendingStopAction.PAUSE, (stopEx as ScanStopPendingException).requestedAction)

        // Finché la lettura non è terminata, lo stato DB NON deve essere dichiarato PAUSED
        val sessionWhileStuck = database.scanSessionDao().getSessionById(session.id)!!
        assertEquals(ScanSessionStatus.RUNNING.name, sessionWhileStuck.status)

        // Nessun nuovo job concorrente né rimozione cartella possono partire finché il job è attivo
        val concurrentStart = scanRepository.startNewScan()
        assertTrue(concurrentStart.isFailure)
        assertTrue(concurrentStart.exceptionOrNull() is ScanStopPendingException)

        val concurrentResume = scanRepository.resumeScan()
        assertTrue(concurrentResume.isFailure)
        assertTrue(concurrentResume.exceptionOrNull() is ScanStopPendingException)

        val removeBlocked = folderRepository.removeFolder(folderIdA)
        assertTrue(removeBlocked.isFailure)

        // Sblocca ora lo stream SAF dal test
        releaseReadLatch.countDown()
        val deferredPaused = awaitSessionStatus(session.id, ScanSessionStatus.PAUSED)
        scanRepository.awaitActiveJobCompletion()

        assertEquals(ScanPhase.FINGERPRINTING.name, deferredPaused.scanPhase)
        assertEquals(1, deferredPaused.fingerprintsComputed)

        // Sostituisce con stream normale e riprende: deve completarsi senza ricalcolare a_fast
        contentReader.clearUnclosableStream(stuckUri)
        contentReader.base.openedUris.clear()
        contentReader.base.putContent(stuckUri, stuckPayload, stuckMeta)

        scanRepository.resumeScan().getOrThrow()
        val done = awaitSessionStatus(session.id, ScanSessionStatus.COMPLETED)
        scanRepository.awaitActiveJobCompletion()

        assertEquals(2, done.fingerprintsComputed)
        assertFalse(contentReader.base.openedUris.contains(docUri(treeUriA, "doc:a_fast")))
    }

    @Test
    fun test8_SafStreamIgnoringCloseCancelTimeoutAndDeferredCancelledCompletion() = runBlocking {
        scanRepository = buildScanRepository(stopTimeoutMs = 150L)

        // Prima sessione completata per popolare cache e latestCompletedSession
        val stableBytes = "stabile".toByteArray()
        addFile(treeUriA, rootDocA, "doc:stable", "stable.txt", stableBytes)
        val first = scanRepository.startNewScan().getOrThrow()
        awaitSessionStatus(first.id, ScanSessionStatus.COMPLETED)
        scanRepository.awaitActiveJobCompletion()

        // Seconda sessione con stream che ignora close()
        val stuckBytes = "bloccato-cancel".toByteArray()
        treeReader.addFile(treeUriA, rootDocA, "doc:cancel_stuck", "cancel_stuck.txt", stuckBytes.size.toLong())
        val stuckUri = docUri(treeUriA, "doc:cancel_stuck")
        val stuckMeta = SafDocumentMetadata(
            documentId = "doc:cancel_stuck",
            displayName = "cancel_stuck.txt",
            mimeType = "application/octet-stream",
            sizeBytes = stuckBytes.size.toLong(),
            lastModifiedTimestamp = 1_690_000_000_000L,
            flags = 0,
            isDirectory = false
        )
        val enteredLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        contentReader.base.putMetadata(stuckUri, stuckMeta)
        contentReader.setUnclosableStream(stuckUri) {
            UnclosableDelayedInputStream(
                payload = stuckBytes,
                onReadBlocked = { enteredLatch.countDown() },
                releaseLatch = releaseLatch
            )
        }

        val second = scanRepository.startNewScan().getOrThrow()
        assertTrue(enteredLatch.await(5, TimeUnit.SECONDS))

        val cancelResult = scanRepository.cancelScan()
        assertTrue(cancelResult.isFailure)
        assertTrue(cancelResult.exceptionOrNull() is ScanStopPendingException)

        // NON ancora CANCELLED mentre lo stream è bloccato e nuovo avvio rifiutato
        assertEquals(ScanSessionStatus.RUNNING.name, database.scanSessionDao().getSessionById(second.id)!!.status)
        assertTrue(scanRepository.startNewScan().isFailure)

        // Rilascia lo stream -> transizione differita a CANCELLED
        releaseLatch.countDown()
        val deferredCancelled = awaitSessionStatus(second.id, ScanSessionStatus.CANCELLED)
        scanRepository.awaitActiveJobCompletion()

        assertEquals(ScanSessionStatus.CANCELLED.name, deferredCancelled.status)
        assertTrue(database.pendingDirectoryDao().getPendingDirectories(second.id).isEmpty())
        assertEquals(first.id, database.scanSessionDao().getLatestCompletedSession()?.id)
        assertEquals(1, database.fileFingerprintDao().countByFolder(folderIdA))
    }

    @Test
    fun test9_StartNewScanDoesNotImplicitlyCancelRunningJobOrOrphanedDbSession() = runBlocking {
        // Caso 1: sessione RUNNING orfana nel DB senza job attivo -> startNewScan recupera in PAUSED e rifiuta
        val orphan = ScanSessionEntity(
            id = "orphan-running",
            status = ScanSessionStatus.RUNNING.name,
            scanPhase = ScanPhase.FINGERPRINTING.name
        )
        database.scanSessionDao().insertSession(orphan)

        val result = scanRepository.startNewScan()
        assertTrue("startNewScan deve rifiutare e recuperare in PAUSED la sessione orfana", result.isFailure)
        val dbAfter = database.scanSessionDao().getSessionById("orphan-running")!!
        assertEquals(ScanSessionStatus.PAUSED.name, dbAfter.status)
        assertEquals(ScanPhase.FINGERPRINTING.name, dbAfter.scanPhase)

        // Annulla esplicitamente
        scanRepository.cancelScan().getOrThrow()
        assertEquals(ScanSessionStatus.CANCELLED.name, database.scanSessionDao().getSessionById("orphan-running")!!.status)

        // Caso 2: job realmente attivo nella stessa istanza -> startNewScan rifiuta senza annullare
        val slowBytes = "attivo".toByteArray()
        treeReader.addFile(treeUriA, rootDocA, "doc:active_file", "active.txt", slowBytes.size.toLong())
        val activeUri = docUri(treeUriA, "doc:active_file")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        contentReader.base.putMetadata(
            activeUri,
            SafDocumentMetadata("doc:active_file", "active.txt", "text/plain", slowBytes.size.toLong(), 1000L, 0, false)
        )
        contentReader.setUnclosableStream(activeUri) {
            UnclosableDelayedInputStream(slowBytes, { entered.countDown() }, release)
        }

        val active = scanRepository.startNewScan().getOrThrow()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val rejectSecond = scanRepository.startNewScan()
        assertTrue("startNewScan non deve annullare implicitamente la scansione attiva", rejectSecond.isFailure)
        assertEquals(ScanSessionStatus.RUNNING.name, database.scanSessionDao().getSessionById(active.id)!!.status)

        release.countDown()
        awaitSessionStatus(active.id, ScanSessionStatus.COMPLETED)
        scanRepository.awaitActiveJobCompletion()
    }

    @Test
    fun test10_ProcessDeathRecoveryInFingerprintingAcrossFreshInstances() = runBlocking {
        val p1 = "già-fatto".toByteArray()
        val p2 = "da-completare-dopo-crash".toByteArray()
        addFile(treeUriA, rootDocA, "doc:01_done", "01_done.txt", p1)
        addFile(treeUriA, rootDocA, "doc:02_pending", "02_pending.txt", p2)

        // Esegue Discovery + 1 file, poi interrompe con pausa simulando stato pre-crash RUNNING in FINGERPRINTING
        val blockedEntered = CountDownLatch(1)
        val slowUri = docUri(treeUriA, "doc:02_pending")
        val slowMeta = SafDocumentMetadata("doc:02_pending", "02_pending.txt", "application/octet-stream", p2.size.toLong(), 1_690_000_000_000L, 0, false)
        contentReader.base.setBlockingStream(slowUri, onBlockedEntered = { blockedEntered.countDown() })

        val session = scanRepository.startNewScan(
            mode = FingerprintMode.PERIODIC_REVERIFY,
            maxCacheAgeMs = 123_456L
        ).getOrThrow()
        assertTrue(blockedEntered.await(5, TimeUnit.SECONDS))
        scanRepository.pauseScan().getOrThrow()

        // Forza stato DB a RUNNING in FINGERPRINTING (come dopo process death improvvisa)
        database.scanSessionDao().updateStatus(session.id, ScanSessionStatus.RUNNING.name)

        // Ripristina contenuto reale per doc:02_pending e crea nuove istanze dopo riavvio processo
        contentReader.base.openedUris.clear()
        contentReader.base.putContent(slowUri, p2, slowMeta)

        val newRepo = buildScanRepository()
        val recovered = newRepo.checkAndRecoverInterruptedSession()
        assertNotNull(recovered)
        assertEquals(ScanSessionStatus.PAUSED.name, recovered!!.status)
        assertEquals(ScanPhase.FINGERPRINTING.name, recovered.scanPhase)
        assertEquals(FingerprintMode.PERIODIC_REVERIFY.name, recovered.fingerprintMode)
        assertEquals(123_456L, recovered.fingerprintMaxCacheAgeMs)

        newRepo.resumeScan().getOrThrow()
        val done = awaitSessionStatus(session.id, ScanSessionStatus.COMPLETED)
        newRepo.awaitActiveJobCompletion()

        assertEquals(2, done.fingerprintsComputed)
        assertEquals(p1.size.toLong() + p2.size.toLong(), done.fingerprintsBytesHashed)
        assertFalse(contentReader.base.openedUris.contains(docUri(treeUriA, "doc:01_done")))
    }

    @Test
    fun test11_FolderDaoErrorPropagatesScanPersistenceExceptionAndMissingFolderProducesFolderNotFound() = runBlocking {
        val p1 = "file-con-errore-dao".toByteArray()
        addFile(treeUriA, rootDocA, "doc:dao_err", "dao_err.txt", p1)

        // Forza errore SQLite su folderDao.getFolderById() durante la fase FINGERPRINTING
        val entered = CountDownLatch(1)
        treeReader.onDirectoryQueried = { id ->
            if (id == rootDocA) {
                delegatingFolderDao.getByIdException = SQLiteException("Errore temporaneo lettura backup_folders")
                entered.countDown()
            }
        }

        val session = scanRepository.startNewScan().getOrThrow()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val pausedOnDaoError = awaitSessionStatus(session.id, ScanSessionStatus.PAUSED)
        scanRepository.awaitActiveJobCompletion()

        // NON deve essere marcato FOLDER_NOT_FOUND né completato con errori!
        assertEquals(ScanPhase.FINGERPRINTING.name, pausedOnDaoError.scanPhase)
        assertTrue(pausedOnDaoError.errorMessage?.contains("temporaneo di persistenza", ignoreCase = true) == true)
        assertTrue(database.scanErrorDao().getErrorsForSession(session.id).isEmpty())
        assertEquals(
            FingerprintStatus.PENDING.name,
            database.scannedDocumentDao().getIncludedFilesForSession(session.id).first().fingerprintStatus
        )

        // Ripristina DAO e verifica caso reale getFolderById == null -> FOLDER_NOT_FOUND
        delegatingFolderDao.getByIdException = null
        treeReader.onDirectoryQueried = null
        database.folderDao().deleteFolderById(folderIdA)

        scanRepository.resumeScan().getOrThrow()
        val completedWithMissingFolder = awaitSessionStatus(session.id, ScanSessionStatus.COMPLETED_WITH_ERRORS)
        scanRepository.awaitActiveJobCompletion()

        assertEquals(1, completedWithMissingFolder.fingerprintsFailed)
        assertEquals(1, completedWithMissingFolder.errorCount)
        val errors = database.scanErrorDao().getErrorsForSession(session.id)
        assertEquals(1, errors.size)
        assertEquals(ScanRepository.ERROR_FOLDER_NOT_FOUND, errors[0].errorType)
        assertEquals(ScanErrorPhase.FINGERPRINTING.name, errors[0].errorPhase)
    }

    @Test
    fun test12_DeleteStaleCacheRollbackOnSqlErrorRevertsFinalizationAndResumesCleanly() = runBlocking {
        // Scan 1: salva f1 e f2 in file_fingerprints
        addFile(treeUriA, rootDocA, "doc:f1", "f1.txt", "uno".toByteArray())
        addFile(treeUriA, rootDocA, "doc:f2", "f2.txt", "due".toByteArray())
        val s1 = scanRepository.startNewScan().getOrThrow()
        awaitSessionStatus(s1.id, ScanSessionStatus.COMPLETED)
        scanRepository.awaitActiveJobCompletion()
        assertEquals(2, database.fileFingerprintDao().countByFolder(folderIdA))

        // Rimuove f2 dal tree SAF: nella scan 2 deleteStaleForFolderNotInSession tenterà di eliminarlo
        treeReader.clear()
        addFile(treeUriA, rootDocA, "doc:f1", "f1.txt", "uno".toByteArray())

        // Installa trigger che fallisce durante deleteStaleForFolderNotInSession nella transazione di finalizzazione
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER trigger_fail_stale_fp_delete
            BEFORE DELETE ON file_fingerprints
            BEGIN
                SELECT RAISE(ABORT, 'Errore SQLite simulato in pulizia cache stantia');
            END;
            """.trimIndent()
        )

        val s2 = scanRepository.startNewScan().getOrThrow()
        val pausedOnRollback = awaitSessionStatus(s2.id, ScanSessionStatus.PAUSED)
        scanRepository.awaitActiveJobCompletion()

        // Rollback completo della finalizzazione: NON COMPLETED, cache stantia non eliminata
        assertEquals(ScanSessionStatus.PAUSED.name, pausedOnRollback.status)
        assertNotEquals(ScanPhase.COMPLETED.name, pausedOnRollback.scanPhase)
        assertNull(pausedOnRollback.completedTimestamp)
        assertEquals(2, database.fileFingerprintDao().countByFolder(folderIdA))

        // Rimuove il trigger e riprende: finalizzazione e pulizia devono concludersi atomicamente
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS trigger_fail_stale_fp_delete;")
        scanRepository.resumeScan().getOrThrow()
        val s2Done = awaitSessionStatus(s2.id, ScanSessionStatus.COMPLETED)
        scanRepository.awaitActiveJobCompletion()

        assertEquals(ScanPhase.COMPLETED.name, s2Done.scanPhase)
        assertEquals(1, database.fileFingerprintDao().countByFolder(folderIdA))
        assertNotNull(database.fileFingerprintDao().getByFolderAndDocument(folderIdA, "doc:f1"))
        assertNull(database.fileFingerprintDao().getByFolderAndDocument(folderIdA, "doc:f2"))
    }

    @Test
    fun test13_CleanupOldSessionsFailureDoesNotRevertCompletedSession() = runBlocking {
        addFile(treeUriA, rootDocA, "doc:h1", "h1.txt", "uno".toByteArray())
        val s1 = scanRepository.startNewScan().getOrThrow()
        awaitSessionStatus(s1.id, ScanSessionStatus.COMPLETED)
        scanRepository.awaitActiveJobCompletion()

        val s2 = scanRepository.startNewScan().getOrThrow()
        awaitSessionStatus(s2.id, ScanSessionStatus.COMPLETED)
        scanRepository.awaitActiveJobCompletion()

        // Alla terza sessione cleanupOldSessionsSafely tenterà di cancellare s1 da scan_sessions
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER trigger_fail_old_session_delete
            BEFORE DELETE ON scan_sessions
            BEGIN
                SELECT RAISE(ABORT, 'Errore simulato pulizia vecchie sessioni');
            END;
            """.trimIndent()
        )

        val s3 = scanRepository.startNewScan().getOrThrow()
        val s3Done = awaitSessionStatus(s3.id, ScanSessionStatus.COMPLETED)
        scanRepository.awaitActiveJobCompletion()

        // La sessione s3 resta regolarmente COMPLETED (nessun ritorno a PAUSED/FAILED)
        val s3Persisted = database.scanSessionDao().getSessionById(s3.id)!!
        assertEquals(ScanSessionStatus.COMPLETED.name, s3Persisted.status)
        assertEquals(ScanPhase.COMPLETED.name, s3Persisted.scanPhase)
        assertNotNull(s3Done.completedTimestamp)

        database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS trigger_fail_old_session_delete;")
    }

    @Test
    fun test14_MultiFolderStaleCacheSafetyPreservesDiscoveryErrorFolderWhileCleaningHealthyFolder() = runBlocking {
        // Aggiunge seconda cartella B
        permissionManager.takePersistableReadPermission(treeUriB)
        database.folderDao().insertFolder(
            BackupFolderEntity(
                id = folderIdB,
                treeUri = treeUriB.toString(),
                displayName = "FolderB",
                authority = "com.android.externalstorage.documents",
                addedTimestamp = 2000L,
                priority = Priority.NORMALE.name,
                protectFromEviction = false,
                enabled = true
            )
        )

        // Scan 1: popola cache per A (a1, a2) e B (b1, b2)
        addFile(treeUriA, rootDocA, "doc:a1", "a1.txt", "a1".toByteArray())
        addFile(treeUriA, rootDocA, "doc:a2", "a2.txt", "a2".toByteArray())
        addFile(treeUriB, rootDocB, "doc:b1", "b1.txt", "b1".toByteArray())
        addFile(treeUriB, rootDocB, "doc:b2", "b2.txt", "b2".toByteArray())

        val s1 = scanRepository.startNewScan().getOrThrow()
        awaitSessionStatus(s1.id, ScanSessionStatus.COMPLETED)
        scanRepository.awaitActiveJobCompletion()
        assertEquals(2, database.fileFingerprintDao().countByFolder(folderIdA))
        assertEquals(2, database.fileFingerprintDao().countByFolder(folderIdB))

        // Scan 2: FolderA ha errore DISCOVERY in sottocartella; FolderB è sana e b2 è stato eliminato
        treeReader.clear()
        addFile(treeUriA, rootDocA, "doc:a1", "a1.txt", "a1".toByteArray())
        treeReader.addDirectory(treeUriA, rootDocA, "doc:a_broken", "broken")
        treeReader.setDirectoryInaccessible(treeUriA, "doc:a_broken", IOException("Subdir illeggibile"))
        addFile(treeUriB, rootDocB, "doc:b1", "b1.txt", "b1".toByteArray())

        val s2 = scanRepository.startNewScan().getOrThrow()
        val s2Done = awaitSessionStatus(s2.id, ScanSessionStatus.COMPLETED_WITH_ERRORS)
        scanRepository.awaitActiveJobCompletion()

        assertEquals(ScanPhase.COMPLETED.name, s2Done.scanPhase)
        // FolderA ha errore DISCOVERY: la cache stantia di a2 deve essere PRESERVATA
        assertEquals(2, database.fileFingerprintDao().countByFolder(folderIdA))
        // FolderB non ha errori DISCOVERY: b2 eliminato viene pulito da file_fingerprints
        assertEquals(1, database.fileFingerprintDao().countByFolder(folderIdB))
        assertNotNull(database.fileFingerprintDao().getByFolderAndDocument(folderIdB, "doc:b1"))
        assertNull(database.fileFingerprintDao().getByFolderAndDocument(folderIdB, "doc:b2"))
    }

    @Test
    fun test15_ConcurrencyRaceBetweenStartNewScanAndRemoveFolder() = runBlocking {
        addFile(treeUriA, rootDocA, "doc:race", "race.txt", "race-content".toByteArray())

        val insideStartLock = CountDownLatch(1)
        val allowStartProceed = CountDownLatch(1)
        delegatingFolderDao.onGetFoldersHook = {
            insideStartLock.countDown()
            allowStartProceed.await(5, TimeUnit.SECONDS)
        }

        // Lancia startNewScan() su pool separato per evitare deadlock su runBlocking + CountDownLatch.await
        val startDeferred = async(Dispatchers.Default) { scanRepository.startNewScan() }
        assertTrue(insideStartLock.await(5, TimeUnit.SECONDS))

        // Lancia removeFolder() concorrente: deve attendere il rilascio di FolderLifecycleCoordinator
        val removeFinishedEarly = AtomicBoolean(false)
        val removeDeferred = async(Dispatchers.Default) {
            val res = folderRepository.removeFolder(folderIdA)
            removeFinishedEarly.set(true)
            res
        }

        // Breve attesa per verificare che removeFolder non possa procedere mentre startNewScan detiene il coordinatore
        Thread.sleep(50L)
        assertFalse("removeFolder deve essere serializzata dal coordinatore condiviso", removeFinishedEarly.get())

        // Sblocca startNewScan(): crea la sessione RUNNING e rilascia il coordinatore
        allowStartProceed.countDown()
        val startResult = startDeferred.await()
        assertTrue(startResult.isSuccess)

        // Ora removeFolder entra nel coordinatore, vede la sessione RUNNING e rifiuta in sicurezza
        val removeResult = removeDeferred.await()
        assertTrue("removeFolder deve fallire perché startNewScan ha creato la sessione RUNNING", removeResult.isFailure)
        assertNotNull(database.folderDao().getFolderById(folderIdA))
        assertTrue(permissionManager.hasPersistedReadPermission(treeUriA))

        val session = startResult.getOrThrow()
        awaitSessionStatus(session.id, ScanSessionStatus.COMPLETED)
        scanRepository.awaitActiveJobCompletion()

        // Terminata la sessione COMPLETED, una rimozione successiva deve riuscire ed eliminare cartella + cache
        delegatingFolderDao.onGetFoldersHook = null
        val removeAfterComplete = folderRepository.removeFolder(folderIdA)
        assertTrue(removeAfterComplete.isSuccess)
        assertNull(database.folderDao().getFolderById(folderIdA))
        assertEquals(0, database.fileFingerprintDao().countByFolder(folderIdA))
    }

    private class PipelineDelegatingFolderDao(
        private val delegate: FolderDao
    ) : FolderDao {
        @Volatile
        var getByIdException: Exception? = null

        @Volatile
        var onGetFoldersHook: (() -> Unit)? = null

        override fun observeFolders(): Flow<List<BackupFolderEntity>> = delegate.observeFolders()

        override suspend fun getFolders(): List<BackupFolderEntity> {
            onGetFoldersHook?.invoke()
            return delegate.getFolders()
        }

        override suspend fun getFolderById(id: String): BackupFolderEntity? {
            getByIdException?.let { throw it }
            return delegate.getFolderById(id)
        }

        override suspend fun getFolderByUri(treeUri: String): BackupFolderEntity? = delegate.getFolderByUri(treeUri)
        override suspend fun insertFolder(folder: BackupFolderEntity): Long = delegate.insertFolder(folder)
        override suspend fun deleteFolderById(id: String): Int = delegate.deleteFolderById(id)
        override suspend fun deleteFolderByUri(treeUri: String): Int = delegate.deleteFolderByUri(treeUri)
        override suspend fun updatePriority(id: String, priority: String): Int = delegate.updatePriority(id, priority)
        override suspend fun updateEvictionProtection(id: String, protect: Boolean): Int = delegate.updateEvictionProtection(id, protect)
        override suspend fun updateEnabled(id: String, enabled: Boolean): Int = delegate.updateEnabled(id, enabled)
        override suspend fun clearAllFolders(): Int = delegate.clearAllFolders()
    }

    private class ControllableSafContentReader(
        val base: FakeSafContentReader
    ) : SafContentReader {
        private val customStreams = mutableMapOf<String, () -> InputStream?>()

        fun setUnclosableStream(uri: Uri, provider: () -> InputStream?) {
            customStreams[uri.toString()] = provider
        }

        fun clearUnclosableStream(uri: Uri) {
            customStreams.remove(uri.toString())
        }

        override fun openInputStream(documentUri: Uri): InputStream? {
            val custom = customStreams[documentUri.toString()]
            return if (custom != null) {
                custom.invoke()
            } else {
                base.openInputStream(documentUri)
            }
        }

        override fun queryDocumentMetadata(documentUri: Uri): SafDocumentMetadata? {
            return base.queryDocumentMetadata(documentUri)
        }
    }

    private class UnclosableDelayedInputStream(
        private val payload: ByteArray,
        private val onReadBlocked: () -> Unit,
        private val releaseLatch: CountDownLatch
    ) : InputStream() {
        private var blockedOnce = false
        private var pos = 0

        override fun read(): Int {
            val buf = ByteArray(1)
            val count = read(buf, 0, 1)
            return if (count <= 0) count else buf[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!blockedOnce) {
                blockedOnce = true
                onReadBlocked()
                releaseLatch.await()
            }
            if (pos >= payload.size) return -1
            val toCopy = minOf(len, payload.size - pos)
            System.arraycopy(payload, pos, b, off, toCopy)
            pos += toCopy
            return toCopy
        }

        override fun close() {
            // Simula intenzionalmente un ContentProvider SAF che ignora close() durante I/O nativo bloccante
        }
    }
}
