package com.androidbackup.app.data.scan

import android.content.Context
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.androidbackup.app.data.local.dao.FileFingerprintDao
import com.androidbackup.app.data.local.database.BackupDatabase
import com.androidbackup.app.data.local.entity.FileFingerprintEntity
import com.androidbackup.app.data.local.entity.FingerprintMode
import com.androidbackup.app.data.local.entity.FingerprintStatus
import com.androidbackup.app.data.local.entity.ScanErrorPhase
import com.androidbackup.app.data.local.entity.ScanErrorSeverity
import com.androidbackup.app.data.local.entity.ScannedDocumentEntity
import com.androidbackup.app.data.saf.NullCursorException
import com.androidbackup.app.data.saf.SafDocumentMetadata
import com.androidbackup.app.testutils.FakeSafContentReader
import com.androidbackup.app.testutils.FakeSafPermissionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
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
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Suite di test unitari e concorrenti deterministici per [LocalFileFingerprinter] (Fase 1B.3B).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LocalFileFingerprinterTest {

    private lateinit var database: BackupDatabase
    private lateinit var contentReader: FakeSafContentReader
    private lateinit var permissionManager: FakeSafPermissionManager
    private lateinit var streamHasher: Sha256StreamHasher
    private lateinit var currentTimeMs: AtomicLong
    private lateinit var fingerprinter: LocalFileFingerprinter

    private val treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ATestDocs")
    private val folderId = "folder-fp-1"
    private val sessionId = "sess-fp-1"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BackupDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        contentReader = FakeSafContentReader()
        permissionManager = FakeSafPermissionManager()
        permissionManager.takePersistableReadPermission(treeUri)
        streamHasher = Sha256StreamHasher()
        currentTimeMs = AtomicLong(1_700_000_000_000L)

        fingerprinter = buildFingerprinter()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun buildFingerprinter(
        fingerprintDao: FileFingerprintDao = database.fileFingerprintDao(),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ): LocalFileFingerprinter {
        return LocalFileFingerprinter(
            fileFingerprintDao = fingerprintDao,
            scannedDocumentDao = database.scannedDocumentDao(),
            scanErrorDao = database.scanErrorDao(),
            safContentReader = contentReader,
            safPermissionManager = permissionManager,
            streamHasher = streamHasher,
            runInTransaction = { block -> database.withTransaction { block() } },
            ioDispatcher = ioDispatcher,
            nowProvider = { currentTimeMs.get() }
        )
    }

    @Test
    fun testFirstHashComputesSha256PersistsCacheAndInventory() = runBlocking {
        val payload = "Contenuto di test Fase 1B.3B".toByteArray(Charsets.UTF_8)
        val expectedHex = sha256Hex(payload)
        val docUri = Uri.parse("$treeUri/document/doc_1")
        val meta = regularMeta("doc_1", "doc1.txt", "text/plain", payload.size.toLong(), 50_000L)

        contentReader.putContent(docUri, payload, meta)
        val entity = insertDocEntity(
            docId = "doc_1",
            uri = docUri,
            relativePath = "doc1.txt",
            mimeType = "text/plain",
            sizeBytes = payload.size.toLong(),
            lastModified = 50_000L
        )

        val result = fingerprinter.fingerprintFile(
            SingleFileFingerprintRequest(document = entity, folderTreeUri = treeUri)
        )

        assertTrue(result is SingleFileFingerprintResult.Computed)
        val computed = result as SingleFileFingerprintResult.Computed
        assertEquals(expectedHex, computed.sha256Hex)
        assertEquals(payload.size.toLong(), computed.bytesRead)
        assertEquals(currentTimeMs.get(), computed.timestamp)
        assertTrue(contentReader.allOpenedStreamsClosed)
        assertEquals(2, contentReader.queriedUris.size) // pre-read e post-read

        val updatedDoc = database.scannedDocumentDao().getDocumentsForSession(sessionId).first { it.id == entity.id }
        assertEquals(FingerprintStatus.COMPUTED.name, updatedDoc.fingerprintStatus)
        assertEquals(expectedHex, updatedDoc.sha256Hex)
        assertEquals(payload.size.toLong(), updatedDoc.hashedBytesRead)
        assertEquals(currentTimeMs.get(), updatedDoc.fingerprintTimestamp)
        assertNull(updatedDoc.fingerprintError)

        val cached = database.fileFingerprintDao().getByFolderAndDocument(folderId, "doc_1")
        assertNotNull(cached)
        assertEquals(expectedHex, cached!!.sha256Hex)
        assertEquals(payload.size.toLong(), cached.computedBytes)
        assertEquals(currentTimeMs.get(), cached.lastContentHashedTimestamp)
        assertEquals(currentTimeMs.get(), cached.lastMetadataMatchedTimestamp)
        assertEquals(sessionId, cached.lastSeenSessionId)
    }

    @Test
    fun testEmptyZeroByteFileHashesToStandardEmptySha256WithZeroHashedBytesRead() = runBlocking {
        val docUri = Uri.parse("$treeUri/document/empty_0")
        val meta = regularMeta("empty_0", "empty.bin", "application/octet-stream", 0L, 12_345L)
        contentReader.putContent(docUri, ByteArray(0), meta)

        val entity = insertDocEntity(
            docId = "empty_0",
            uri = docUri,
            relativePath = "empty.bin",
            mimeType = "application/octet-stream",
            sizeBytes = 0L,
            lastModified = 12_345L
        )

        val result = fingerprinter.fingerprintFile(
            SingleFileFingerprintRequest(document = entity, folderTreeUri = treeUri)
        )

        assertTrue(result is SingleFileFingerprintResult.Computed)
        val computed = result as SingleFileFingerprintResult.Computed
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", computed.sha256Hex)
        assertEquals(0L, computed.bytesRead)

        val dbDoc = database.scannedDocumentDao().getDocumentsForSession(sessionId).first { it.id == entity.id }
        assertEquals(FingerprintStatus.COMPUTED.name, dbDoc.fingerprintStatus)
        assertEquals(0L, dbDoc.hashedBytesRead)
    }

    @Test
    fun testDirectoryAndExcludedReturnNotRequiredWithoutSafAccess() = runBlocking {
        val dirDoc = insertDocEntity(
            docId = "dir_1",
            uri = Uri.parse("$treeUri/document/dir_1"),
            relativePath = "sub",
            isDirectory = true,
            fingerprintStatus = FingerprintStatus.NOT_REQUIRED
        )
        val exclDoc = insertDocEntity(
            docId = "excl_1",
            uri = Uri.parse("$treeUri/document/excl_1"),
            relativePath = "temp.tmp",
            isExcluded = true,
            fingerprintStatus = FingerprintStatus.NOT_REQUIRED
        )

        val r1 = fingerprinter.fingerprintFile(SingleFileFingerprintRequest(dirDoc, treeUri))
        val r2 = fingerprinter.fingerprintFile(SingleFileFingerprintRequest(exclDoc, treeUri))

        assertTrue(r1 is SingleFileFingerprintResult.NotRequired)
        assertTrue(r2 is SingleFileFingerprintResult.NotRequired)
        assertTrue(contentReader.queriedUris.isEmpty())
        assertTrue(contentReader.openedUris.isEmpty())
    }

    @Test
    fun testIncrementalCacheHitReusesSha256WithoutOpeningStreamAndPreservesHashTimestamp() = runBlocking {
        val payload = "Contenuto stabile".toByteArray(Charsets.UTF_8)
        val docUri = Uri.parse("$treeUri/document/doc_cache")
        val meta = regularMeta("doc_cache", "stabile.txt", "text/plain", payload.size.toLong(), 80_000L)
        contentReader.putContent(docUri, payload, meta)

        val docS1 = insertDocEntity(
            docId = "doc_cache",
            uri = docUri,
            relativePath = "stabile.txt",
            mimeType = "text/plain",
            sizeBytes = payload.size.toLong(),
            lastModified = 80_000L
        )
        val initialHashTime = currentTimeMs.get()
        assertTrue(
            fingerprinter.fingerprintFile(SingleFileFingerprintRequest(docS1, treeUri))
                is SingleFileFingerprintResult.Computed
        )
        assertEquals(1, contentReader.openStreamCount)

        // Avanza l'orologio di 30 giorni: INCREMENTAL deve comunque riusare la cache senza aprire stream
        val laterTime = initialHashTime + 30L * LocalFileFingerprinter.DEFAULT_MAX_CACHE_AGE_MS
        currentTimeMs.set(laterTime)
        val docS2 = insertDocEntity(
            id = "row-s2",
            sessId = "sess-fp-2",
            docId = "doc_cache",
            uri = docUri,
            relativePath = "moved/stabile.txt",
            mimeType = "text/plain",
            sizeBytes = payload.size.toLong(),
            lastModified = 80_000L
        )

        val result = fingerprinter.fingerprintFile(
            SingleFileFingerprintRequest(
                document = docS2,
                folderTreeUri = treeUri,
                mode = FingerprintMode.INCREMENTAL
            )
        )

        assertTrue(result is SingleFileFingerprintResult.ReusedCache)
        val reused = result as SingleFileFingerprintResult.ReusedCache
        assertEquals(sha256Hex(payload), reused.sha256Hex)
        assertEquals(initialHashTime, reused.cachedTimestamp)
        assertEquals(laterTime, reused.matchedTimestamp)
        assertEquals("Nessuno stream deve essere aperto su cache hit", 1, contentReader.openStreamCount)

        val dbDocS2 = database.scannedDocumentDao().getDocumentsForSession("sess-fp-2").first { it.id == docS2.id }
        assertEquals(FingerprintStatus.REUSED_CACHE.name, dbDocS2.fingerprintStatus)
        assertEquals(sha256Hex(payload), dbDocS2.sha256Hex)
        assertNull("hashedBytesRead deve essere null su cache hit", dbDocS2.hashedBytesRead)

        val cached = database.fileFingerprintDao().getByFolderAndDocument(folderId, "doc_cache")!!
        assertEquals("lastContentHashedTimestamp deve restare invariato", initialHashTime, cached.lastContentHashedTimestamp)
        assertEquals(laterTime, cached.lastMetadataMatchedTimestamp)
        assertEquals("moved/stabile.txt", cached.relativePath)
        assertEquals("sess-fp-2", cached.lastSeenSessionId)
    }

    @Test
    fun testForceRecomputeIgnoresValidCacheAndUpdatesContentTimestamp() = runBlocking {
        val payload = "Forza ricalcolo".toByteArray(Charsets.UTF_8)
        val docUri = Uri.parse("$treeUri/document/doc_force")
        val meta = regularMeta("doc_force", "force.bin", "application/octet-stream", payload.size.toLong(), 90_000L)
        contentReader.putContent(docUri, payload, meta)

        val doc1 = insertDocEntity("doc_force", docUri, "force.bin", "application/octet-stream", payload.size.toLong(), 90_000L)
        val t1 = currentTimeMs.get()
        assertTrue(fingerprinter.fingerprintFile(SingleFileFingerprintRequest(doc1, treeUri)) is SingleFileFingerprintResult.Computed)

        val t2 = t1 + 5_000L
        currentTimeMs.set(t2)
        val doc2 = insertDocEntity(
            id = "row-force-2",
            sessId = "sess-fp-2",
            docId = "doc_force",
            uri = docUri,
            relativePath = "force.bin",
            mimeType = "application/octet-stream",
            sizeBytes = payload.size.toLong(),
            lastModified = 90_000L
        )

        val result = fingerprinter.fingerprintFile(
            SingleFileFingerprintRequest(doc2, treeUri, mode = FingerprintMode.FORCE_RECOMPUTE)
        )

        assertTrue(result is SingleFileFingerprintResult.Computed)
        assertEquals(2, contentReader.openStreamCount)
        val cached = database.fileFingerprintDao().getByFolderAndDocument(folderId, "doc_force")!!
        assertEquals(t2, cached.lastContentHashedTimestamp)
    }

    @Test
    fun testPeriodicReverifyRespects7DayTtlBoundaryAndZeroOrNegativeTtl() = runBlocking {
        val payload = "Verifica periodica TTL".toByteArray(Charsets.UTF_8)
        val docUri = Uri.parse("$treeUri/document/doc_ttl")
        val meta = regularMeta("doc_ttl", "ttl.dat", "application/octet-stream", payload.size.toLong(), 70_000L)
        contentReader.putContent(docUri, payload, meta)

        val t0 = currentTimeMs.get()
        val doc1 = insertDocEntity("doc_ttl", docUri, "ttl.dat", "application/octet-stream", payload.size.toLong(), 70_000L)
        assertTrue(fingerprinter.fingerprintFile(SingleFileFingerprintRequest(doc1, treeUri)) is SingleFileFingerprintResult.Computed)
        assertEquals(1, contentReader.openStreamCount)

        // 1 ms prima della scadenza (7 giorni - 1 ms) -> cache hit
        currentTimeMs.set(t0 + LocalFileFingerprinter.DEFAULT_MAX_CACHE_AGE_MS - 1L)
        val doc2 = insertDocEntity(
            id = "row-ttl-2",
            sessId = "sess-ttl-2",
            docId = "doc_ttl",
            uri = docUri,
            relativePath = "ttl.dat",
            mimeType = "application/octet-stream",
            sizeBytes = payload.size.toLong(),
            lastModified = 70_000L
        )
        val hit = fingerprinter.fingerprintFile(
            SingleFileFingerprintRequest(doc2, treeUri, mode = FingerprintMode.PERIODIC_REVERIFY)
        )
        assertTrue(hit is SingleFileFingerprintResult.ReusedCache)
        assertEquals(1, contentReader.openStreamCount)

        // Esattamente a 7 giorni -> scaduta -> ricalcolo
        val tExpired = t0 + LocalFileFingerprinter.DEFAULT_MAX_CACHE_AGE_MS
        currentTimeMs.set(tExpired)
        val doc3 = insertDocEntity(
            id = "row-ttl-3",
            sessId = "sess-ttl-3",
            docId = "doc_ttl",
            uri = docUri,
            relativePath = "ttl.dat",
            mimeType = "application/octet-stream",
            sizeBytes = payload.size.toLong(),
            lastModified = 70_000L
        )
        val expiredResult = fingerprinter.fingerprintFile(
            SingleFileFingerprintRequest(doc3, treeUri, mode = FingerprintMode.PERIODIC_REVERIFY)
        )
        assertTrue(expiredResult is SingleFileFingerprintResult.Computed)
        assertEquals(2, contentReader.openStreamCount)
        assertEquals(tExpired, database.fileFingerprintDao().getByFolderAndDocument(folderId, "doc_ttl")!!.lastContentHashedTimestamp)

        // TTL <= 0 significa sempre scaduto in PERIODIC_REVERIFY
        currentTimeMs.set(tExpired + 1L)
        val doc4 = insertDocEntity(
            id = "row-ttl-4",
            sessId = "sess-ttl-4",
            docId = "doc_ttl",
            uri = docUri,
            relativePath = "ttl.dat",
            mimeType = "application/octet-stream",
            sizeBytes = payload.size.toLong(),
            lastModified = 70_000L
        )
        val zeroTtlResult = fingerprinter.fingerprintFile(
            SingleFileFingerprintRequest(
                document = doc4,
                folderTreeUri = treeUri,
                mode = FingerprintMode.PERIODIC_REVERIFY,
                maxCacheAgeMs = 0L
            )
        )
        assertTrue(zeroTtlResult is SingleFileFingerprintResult.Computed)
        assertEquals(3, contentReader.openStreamCount)

        // Clock skew negativo (now < lastContentHashedTimestamp) -> ricalcolo sicuro
        currentTimeMs.set(tExpired - 10_000L)
        val doc5 = insertDocEntity(
            id = "row-ttl-5",
            sessId = "sess-ttl-5",
            docId = "doc_ttl",
            uri = docUri,
            relativePath = "ttl.dat",
            mimeType = "application/octet-stream",
            sizeBytes = payload.size.toLong(),
            lastModified = 70_000L
        )
        val skewResult = fingerprinter.fingerprintFile(
            SingleFileFingerprintRequest(doc5, treeUri, mode = FingerprintMode.PERIODIC_REVERIFY)
        )
        assertTrue(skewResult is SingleFileFingerprintResult.Computed)
        assertEquals(4, contentReader.openStreamCount)
    }

    @Test
    fun testUnreliableOrDiscoveryChangedMetadataForcesRecomputation() = runBlocking {
        val payload = "Metadati parziali".toByteArray(Charsets.UTF_8)
        val docUriNullMtime = Uri.parse("$treeUri/document/doc_unreliable")
        val metaNullMtime = regularMeta("doc_unreliable", "nomtime.dat", "application/octet-stream", payload.size.toLong(), null)
        contentReader.putContent(docUriNullMtime, payload, metaNullMtime)

        val d1 = insertDocEntity("doc_unreliable", docUriNullMtime, "nomtime.dat", "application/octet-stream", payload.size.toLong(), null)
        assertTrue(fingerprinter.fingerprintFile(SingleFileFingerprintRequest(d1, treeUri)) is SingleFileFingerprintResult.Computed)

        // Anche se i metadati coincidono, lastModifiedTimestamp null rende inaffidabile il cache hit -> ricalcolo
        val d2 = insertDocEntity("row-unrel-2", "sess-2", "doc_unreliable", docUriNullMtime, "nomtime.dat", "application/octet-stream", payload.size.toLong(), null)
        assertTrue(fingerprinter.fingerprintFile(SingleFileFingerprintRequest(d2, treeUri)) is SingleFileFingerprintResult.Computed)
        assertEquals(2, contentReader.openStreamCount)

        // Caso metadati live diversi da quelli registrati in Discovery (es. mimeType cambiato)
        val docUriChanged = Uri.parse("$treeUri/document/doc_disc_diff")
        val oldPayload = "Vecchio testo".toByteArray(Charsets.UTF_8)
        val newPayload = "Nuovo testo".toByteArray(Charsets.UTF_8)
        val oldMeta = regularMeta("doc_disc_diff", "note.txt", "text/plain", oldPayload.size.toLong(), 1000L)
        contentReader.putContent(docUriChanged, oldPayload, oldMeta)

        val firstDoc = insertDocEntity("doc_disc_diff", docUriChanged, "note.txt", "text/plain", oldPayload.size.toLong(), 1000L)
        assertTrue(fingerprinter.fingerprintFile(SingleFileFingerprintRequest(firstDoc, treeUri)) is SingleFileFingerprintResult.Computed)

        // Simula Discovery obsoleta rispetto ai metadati correnti (anche se per assurdo cache avesse vecchi metadati)
        val newMeta = regularMeta("doc_disc_diff", "note.txt", "text/markdown", newPayload.size.toLong(), 2000L)
        contentReader.putContent(docUriChanged, newPayload, newMeta)
        val staleDiscoveryDoc = insertDocEntity(
            id = "row-disc-diff",
            sessId = "sess-diff",
            docId = "doc_disc_diff",
            uri = docUriChanged,
            relativePath = "note.txt",
            mimeType = "text/plain",
            sizeBytes = oldPayload.size.toLong(),
            lastModified = 1000L
        )

        val recomputed = fingerprinter.fingerprintFile(SingleFileFingerprintRequest(staleDiscoveryDoc, treeUri))
        assertTrue(recomputed is SingleFileFingerprintResult.Computed)
        assertEquals(sha256Hex(newPayload), (recomputed as SingleFileFingerprintResult.Computed).sha256Hex)

        val updatedDoc = database.scannedDocumentDao().getDocumentsForSession("sess-diff").first { it.id == staleDiscoveryDoc.id }
        assertEquals("text/markdown", updatedDoc.mimeType)
        assertEquals(newPayload.size.toLong(), updatedDoc.sizeBytes)
        assertEquals(2000L, updatedDoc.lastModifiedTimestamp)
    }

    @Test
    fun testVirtualDocumentClassifiedUnsupportedWithoutOpeningStreamOrCountingAsError() = runBlocking {
        val virtualUri = Uri.parse("$treeUri/document/virtual_doc")
        val virtualMeta = SafDocumentMetadata(
            documentId = "virtual_doc",
            displayName = "Foglio Cloud",
            mimeType = "application/vnd.google-apps.spreadsheet",
            sizeBytes = null,
            lastModifiedTimestamp = 123_456L,
            flags = DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT,
            isDirectory = false
        )
        contentReader.putMetadata(virtualUri, virtualMeta)

        // Pre-popola valori obsoleti su scanned_documents per verificarne l'azzeramento
        val entity = insertDocEntity(
            docId = "virtual_doc",
            uri = virtualUri,
            relativePath = "Foglio Cloud",
            mimeType = "application/vnd.google-apps.spreadsheet",
            sizeBytes = null,
            lastModified = 123_456L,
            sha256Hex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            hashedBytesRead = 999L
        )

        val result = fingerprinter.fingerprintFile(SingleFileFingerprintRequest(entity, treeUri))

        assertTrue(result is SingleFileFingerprintResult.Unsupported)
        assertEquals(LocalFileFingerprinter.ERROR_VIRTUAL_DOCUMENT_UNSUPPORTED, (result as SingleFileFingerprintResult.Unsupported).errorType)
        assertEquals("Nessuno stream deve essere aperto per documenti virtuali", 0, contentReader.openStreamCount)

        val dbDoc = database.scannedDocumentDao().getDocumentsForSession(sessionId).first { it.id == entity.id }
        assertEquals(FingerprintStatus.UNSUPPORTED.name, dbDoc.fingerprintStatus)
        assertNull("sha256Hex deve essere azzerato su UNSUPPORTED", dbDoc.sha256Hex)
        assertNull("hashedBytesRead deve essere azzerato su UNSUPPORTED", dbDoc.hashedBytesRead)

        val allErrors = database.scanErrorDao().getErrorsForSession(sessionId)
        assertEquals(1, allErrors.size)
        assertEquals(ScanErrorPhase.FINGERPRINTING.name, allErrors[0].errorPhase)
        assertEquals(ScanErrorSeverity.UNSUPPORTED.name, allErrors[0].severity)
        assertEquals(0, database.scanErrorDao().countErrorsForSession(sessionId))
        assertEquals(1, database.scanErrorDao().countIssuesBySeverity(sessionId, ScanErrorSeverity.UNSUPPORTED.name))
    }

    @Test
    fun testFailedAttemptResetsStaleSha256AndHashedBytesRead() = runBlocking {
        val docUri = Uri.parse("$treeUri/document/stale_fail")
        val data = ByteArray(100_000) { 0x55 }
        val meta = regularMeta("stale_fail", "fail.bin", "application/octet-stream", data.size.toLong(), 55_000L)
        contentReader.putMetadata(docUri, meta)
        contentReader.setMidReadError(docUri, data, failAfterBytes = 40_000L, exception = IOException("Read failure"))

        val entity = insertDocEntity(
            docId = "stale_fail",
            uri = docUri,
            relativePath = "fail.bin",
            mimeType = "application/octet-stream",
            sizeBytes = data.size.toLong(),
            lastModified = 55_000L,
            sha256Hex = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            hashedBytesRead = 123_456L
        )

        val result = fingerprinter.fingerprintFile(SingleFileFingerprintRequest(entity, treeUri))

        assertTrue(result is SingleFileFingerprintResult.Failed)
        assertEquals(LocalFileFingerprinter.ERROR_HASH_IO, (result as SingleFileFingerprintResult.Failed).errorType)
        assertTrue(contentReader.allOpenedStreamsClosed)

        val dbDoc = database.scannedDocumentDao().getDocumentsForSession(sessionId).first { it.id == entity.id }
        assertEquals(FingerprintStatus.FAILED.name, dbDoc.fingerprintStatus)
        assertNull("sha256Hex precedente deve essere azzerato su FAILED", dbDoc.sha256Hex)
        assertNull("hashedBytesRead precedente deve essere azzerato su FAILED", dbDoc.hashedBytesRead)
        assertNull(database.fileFingerprintDao().getByFolderAndDocument(folderId, "stale_fail"))
    }

    @Test
    fun testSafPermissionRevokedNullStreamMissingFileNullCursorAndDocumentIdChanged() = runBlocking {
        // 1. Permesso revocato
        val revUri = Uri.parse("$treeUri/document/rev")
        val revDoc = insertDocEntity("rev", revUri, "rev.txt", "text/plain", 10L, 1000L)
        // Semina cache preesistente: non deve essere corrotta
        database.fileFingerprintDao().upsertFingerprint(makeFingerprintEntity("rev", revUri, "rev.txt", 10L, 1000L))
        permissionManager.simulateExternalRevocation(treeUri)

        val resRev = fingerprinter.fingerprintFile(SingleFileFingerprintRequest(revDoc, treeUri))
        assertTrue(resRev is SingleFileFingerprintResult.Failed)
        assertEquals(LocalFileFingerprinter.ERROR_PERMISSION_REVOKED, (resRev as SingleFileFingerprintResult.Failed).errorType)
        assertTrue(contentReader.queriedUris.isEmpty())
        assertNotNull(database.fileFingerprintDao().getByFolderAndDocument(folderId, "rev"))

        permissionManager.takePersistableReadPermission(treeUri)

        // 2. Document ID cambiato rispetto alla Discovery
        val renamedUri = Uri.parse("$treeUri/document/old_id")
        contentReader.putMetadata(renamedUri, regularMeta("new_id_from_provider", "doc.txt", "text/plain", 10L, 1000L))
        val idChangedDoc = insertDocEntity("old_id", renamedUri, "doc.txt", "text/plain", 10L, 1000L)
        val resIdChanged = fingerprinter.fingerprintFile(SingleFileFingerprintRequest(idChangedDoc, treeUri))
        assertEquals(LocalFileFingerprinter.ERROR_DOCUMENT_ID_CHANGED, (resIdChanged as SingleFileFingerprintResult.Failed).errorType)
        assertEquals(0, contentReader.openStreamCount)
        assertNull(database.fileFingerprintDao().getByFolderAndDocument(folderId, "old_id"))
        assertNull(database.fileFingerprintDao().getByFolderAndDocument(folderId, "new_id_from_provider"))

        // 3. File rimosso (FileNotFoundException) e Cursor nullo
        val missingUri = Uri.parse("$treeUri/document/missing")
        contentReader.setQueryMetadataError(missingUri, FileNotFoundException("Non trovato"))
        val missingDoc = insertDocEntity("missing", missingUri, "missing.txt", "text/plain", 10L, 1000L)
        val resMissing = fingerprinter.fingerprintFile(SingleFileFingerprintRequest(missingDoc, treeUri))
        assertEquals(LocalFileFingerprinter.ERROR_FILE_NOT_FOUND, (resMissing as SingleFileFingerprintResult.Failed).errorType)

        val nullCursorUri = Uri.parse("$treeUri/document/null_cursor")
        contentReader.setQueryMetadataError(nullCursorUri, NullCursorException("Cursore nullo"))
        val nullCursorDoc = insertDocEntity("null_cursor", nullCursorUri, "nc.txt", "text/plain", 10L, 1000L)
        val resNc = fingerprinter.fingerprintFile(SingleFileFingerprintRequest(nullCursorDoc, treeUri))
        assertEquals(LocalFileFingerprinter.ERROR_NULL_CURSOR, (resNc as SingleFileFingerprintResult.Failed).errorType)

        // 4. openInputStream == null distinto da 0-byte
        val nullStreamUri = Uri.parse("$treeUri/document/null_stream")
        contentReader.putMetadata(nullStreamUri, regularMeta("null_stream", "ns.dat", "application/octet-stream", 0L, 2000L))
        contentReader.setOpenInputStreamNull(nullStreamUri)
        val nullStreamDoc = insertDocEntity("null_stream", nullStreamUri, "ns.dat", "application/octet-stream", 0L, 2000L)
        val resNs = fingerprinter.fingerprintFile(SingleFileFingerprintRequest(nullStreamDoc, treeUri))
        assertEquals(LocalFileFingerprinter.ERROR_NULL_INPUT_STREAM, (resNs as SingleFileFingerprintResult.Failed).errorType)
    }

    @Test
    fun testSizeMismatchAndPostReadModificationNeverSaveFingerprintCache() = runBlocking {
        // Caso A: byte letti != sizeBytes attesa pre-lettura
        val mismatchUri = Uri.parse("$treeUri/document/mismatch")
        val actualBytes = "12345".toByteArray(Charsets.UTF_8) // 5 byte reali vs 10 attesi
        val mismatchMeta = regularMeta("mismatch", "mismatch.bin", "application/octet-stream", 10L, 3000L)
        contentReader.putContent(mismatchUri, actualBytes, mismatchMeta)
        val mismatchDoc = insertDocEntity("mismatch", mismatchUri, "mismatch.bin", "application/octet-stream", 10L, 3000L)

        val resMismatch = fingerprinter.fingerprintFile(SingleFileFingerprintRequest(mismatchDoc, treeUri))
        assertEquals(LocalFileFingerprinter.ERROR_SIZE_MISMATCH, (resMismatch as SingleFileFingerprintResult.Failed).errorType)
        assertNull(database.fileFingerprintDao().getByFolderAndDocument(folderId, "mismatch"))

        // Caso B: modifica concorrente tra pre-read e post-read
        val modifiedUri = Uri.parse("$treeUri/document/mod_during_read")
        val initialBytes = "ABCDE".toByteArray(Charsets.UTF_8)
        val preMeta = regularMeta("mod_during_read", "live.txt", "text/plain", 5L, 4000L)
        val postMeta = regularMeta("mod_during_read", "live.txt", "text/plain", 5L, 4500L)
        contentReader.putContent(modifiedUri, initialBytes)
        contentReader.setMetadataSequence(modifiedUri, { preMeta }, { postMeta })

        val modDoc = insertDocEntity("mod_during_read", modifiedUri, "live.txt", "text/plain", 5L, 4000L)
        val resMod = fingerprinter.fingerprintFile(SingleFileFingerprintRequest(modDoc, treeUri))
        assertEquals(LocalFileFingerprinter.ERROR_FILE_MODIFIED_DURING_HASH, (resMod as SingleFileFingerprintResult.Failed).errorType)
        assertNull("Impronta di file modificato durante la lettura NON deve essere salvata in cache", database.fileFingerprintDao().getByFolderAndDocument(folderId, "mod_during_read"))

        val dbModDoc = database.scannedDocumentDao().getDocumentsForSession(sessionId).first { it.id == modDoc.id }
        assertEquals(FingerprintStatus.FAILED.name, dbModDoc.fingerprintStatus)
        assertEquals(4500L, dbModDoc.lastModifiedTimestamp)
        assertNull(dbModDoc.sha256Hex)
        assertNull(dbModDoc.hashedBytesRead)
    }

    @Test
    fun testPostReadTypeChangesDirectoryAndVirtualAreClassifiedExplicitlyWithoutCaching() = runBlocking {
        // Caso 1: postMeta.isDirectory == true -> FAILED / UNEXPECTED_DIRECTORY / severity ERROR
        val dirSwapUri = Uri.parse("$treeUri/document/post_dir")
        val bytes = "1234".toByteArray(Charsets.UTF_8)
        val preFileMeta = regularMeta("post_dir", "item", "application/octet-stream", 4L, 5000L)
        val postDirMeta = SafDocumentMetadata("post_dir", "item", DocumentsContract.Document.MIME_TYPE_DIR, null, 5000L, 0, isDirectory = true)
        contentReader.putContent(dirSwapUri, bytes)
        contentReader.setMetadataSequence(dirSwapUri, { preFileMeta }, { postDirMeta })

        val dirSwapDoc = insertDocEntity(
            docId = "post_dir",
            uri = dirSwapUri,
            relativePath = "item",
            mimeType = "application/octet-stream",
            sizeBytes = 4L,
            lastModified = 5000L,
            sha256Hex = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            hashedBytesRead = 4L
        )
        val resDirSwap = fingerprinter.fingerprintFile(SingleFileFingerprintRequest(dirSwapDoc, treeUri))
        assertTrue(resDirSwap is SingleFileFingerprintResult.Failed)
        assertEquals(LocalFileFingerprinter.ERROR_UNEXPECTED_DIRECTORY, (resDirSwap as SingleFileFingerprintResult.Failed).errorType)
        assertNull(database.fileFingerprintDao().getByFolderAndDocument(folderId, "post_dir"))

        val dbDirSwap = database.scannedDocumentDao().getDocumentsForSession(sessionId).first { it.id == dirSwapDoc.id }
        assertEquals(FingerprintStatus.FAILED.name, dbDirSwap.fingerprintStatus)
        assertNull(dbDirSwap.sha256Hex)
        assertNull(dbDirSwap.hashedBytesRead)
        val dirSwapErr = database.scanErrorDao().getErrorsForSession(sessionId).first { it.documentId == "post_dir" }
        assertEquals(LocalFileFingerprinter.ERROR_UNEXPECTED_DIRECTORY, dirSwapErr.errorType)
        assertEquals(ScanErrorSeverity.ERROR.name, dirSwapErr.severity)

        // Caso 2: postMeta con FLAG_VIRTUAL_DOCUMENT -> UNSUPPORTED / VIRTUAL_DOCUMENT_UNSUPPORTED / severity UNSUPPORTED
        val virtSwapUri = Uri.parse("$treeUri/document/post_virt")
        val preVirtMeta = regularMeta("post_virt", "cloud", "application/octet-stream", 4L, 6000L)
        val postVirtMeta = SafDocumentMetadata(
            documentId = "post_virt",
            displayName = "cloud",
            mimeType = "application/vnd.google-apps.document",
            sizeBytes = null,
            lastModifiedTimestamp = 6000L,
            flags = DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT,
            isDirectory = false
        )
        contentReader.putContent(virtSwapUri, bytes)
        contentReader.setMetadataSequence(virtSwapUri, { preVirtMeta }, { postVirtMeta })

        val virtSwapDoc = insertDocEntity(
            docId = "post_virt",
            uri = virtSwapUri,
            relativePath = "cloud",
            mimeType = "application/octet-stream",
            sizeBytes = 4L,
            lastModified = 6000L,
            sha256Hex = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
            hashedBytesRead = 4L
        )
        val resVirtSwap = fingerprinter.fingerprintFile(SingleFileFingerprintRequest(virtSwapDoc, treeUri))
        assertTrue(resVirtSwap is SingleFileFingerprintResult.Unsupported)
        assertEquals(LocalFileFingerprinter.ERROR_VIRTUAL_DOCUMENT_UNSUPPORTED, (resVirtSwap as SingleFileFingerprintResult.Unsupported).errorType)
        assertNull(database.fileFingerprintDao().getByFolderAndDocument(folderId, "post_virt"))

        val dbVirtSwap = database.scannedDocumentDao().getDocumentsForSession(sessionId).first { it.id == virtSwapDoc.id }
        assertEquals(FingerprintStatus.UNSUPPORTED.name, dbVirtSwap.fingerprintStatus)
        assertNull(dbVirtSwap.sha256Hex)
        assertNull(dbVirtSwap.hashedBytesRead)
        val virtSwapErr = database.scanErrorDao().getErrorsForSession(sessionId).first { it.documentId == "post_virt" }
        assertEquals(LocalFileFingerprinter.ERROR_VIRTUAL_DOCUMENT_UNSUPPORTED, virtSwapErr.errorType)
        assertEquals(ScanErrorSeverity.UNSUPPORTED.name, virtSwapErr.severity)
    }

    @Test
    fun testRoomReadCacheFailureOutsideTransactionThrowsScanPersistenceExceptionWithoutScanError() = runBlocking {
        val payload = "Read cache fail".toByteArray(Charsets.UTF_8)
        val docUri = Uri.parse("$treeUri/document/cache_read_fail")
        val meta = regularMeta("cache_read_fail", "crf.txt", "text/plain", payload.size.toLong(), 9000L)
        contentReader.putContent(docUri, payload, meta)
        val entity = insertDocEntity("cache_read_fail", docUri, "crf.txt", "text/plain", payload.size.toLong(), 9000L)

        // DAO delegante che simula errore SQLite durante getByFolderAndDocument()
        val failingDao = object : FileFingerprintDao by database.fileFingerprintDao() {
            override suspend fun getByFolderAndDocument(folderId: String, documentId: String): FileFingerprintEntity? {
                throw SQLiteException("Errore SQLite simulato in lettura cache")
            }
        }
        val fpWithFailingRead = buildFingerprinter(fingerprintDao = failingDao)

        var caughtPersistence: ScanPersistenceException? = null
        try {
            fpWithFailingRead.fingerprintFile(SingleFileFingerprintRequest(entity, treeUri))
            fail("Doveva propagare ScanPersistenceException")
        } catch (e: ScanPersistenceException) {
            caughtPersistence = e
        }

        assertNotNull(caughtPersistence)
        assertEquals("Nessun ScanErrorEntity SAF deve essere registrato per errori Room", 0, database.scanErrorDao().getErrorsForSession(sessionId).size)
        assertEquals(0, contentReader.openStreamCount)

        // Verifica che CancellationException su lettura DAO si propaghi intatta e NON diventi ScanPersistenceException
        val cancellingDao = object : FileFingerprintDao by database.fileFingerprintDao() {
            override suspend fun getByFolderAndDocument(folderId: String, documentId: String): FileFingerprintEntity? {
                throw CancellationException("Cancellato durante lettura cache")
            }
        }
        val fpCancelling = buildFingerprinter(fingerprintDao = cancellingDao)
        var caughtCancellation = false
        try {
            fpCancelling.fingerprintFile(SingleFileFingerprintRequest(entity, treeUri))
            fail("Doveva propagare CancellationException")
        } catch (_: CancellationException) {
            caughtCancellation = true
        }
        assertTrue(caughtCancellation)
    }

    @Test
    fun testRoomTransactionFailureRollsBackAllTablesAndThrowsScanPersistenceException() = runBlocking {
        val payload = "Rollback atomico".toByteArray(Charsets.UTF_8)
        val docUri = Uri.parse("$treeUri/document/tx_rollback")
        val meta = regularMeta("tx_rollback", "tx.txt", "text/plain", payload.size.toLong(), 10_000L)
        contentReader.putContent(docUri, payload, meta)
        val entity = insertDocEntity("tx_rollback", docUri, "tx.txt", "text/plain", payload.size.toLong(), 10_000L)

        // Trigger SQLite che fallisce su UPDATE di scanned_documents dopo l'inserimento in file_fingerprints
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER trigger_fail_scanned_doc_fp_update
            BEFORE UPDATE OF fingerprint_status ON scanned_documents
            WHEN NEW.document_id = 'tx_rollback'
            BEGIN
                SELECT RAISE(ABORT, 'Errore SQLite simulato in transazione fingerprinting');
            END;
            """.trimIndent()
        )

        var caught: ScanPersistenceException? = null
        try {
            fingerprinter.fingerprintFile(SingleFileFingerprintRequest(entity, treeUri))
            fail("Doveva essere sollevata ScanPersistenceException")
        } catch (e: ScanPersistenceException) {
            caught = e
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS trigger_fail_scanned_doc_fp_update;")
        }

        assertNotNull(caught)
        // Rollback completo: né file_fingerprints né scanned_documents devono avere dati parziali
        assertNull("Rollback atomico: file_fingerprints non deve conservare l'inserimento", database.fileFingerprintDao().getByFolderAndDocument(folderId, "tx_rollback"))
        val dbDoc = database.scannedDocumentDao().getDocumentsForSession(sessionId).first { it.id == entity.id }
        assertEquals(FingerprintStatus.PENDING.name, dbDoc.fingerprintStatus)
        assertNull(dbDoc.sha256Hex)
        assertTrue(database.scanErrorDao().getErrorsForSession(sessionId).isEmpty())
    }

    @Test
    fun testExecutionRunsOnIoDispatcherAndMutexSerializesConcurrentCallsWithAbort() = runBlocking {
        val ioExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "fingerprinter-io-test-thread")
        }
        val testIoDispatcher = ioExecutor.asCoroutineDispatcher()
        try {
            val serializedFp = buildFingerprinter(ioDispatcher = testIoDispatcher)
            val observedThreadNames = Collections.synchronizedList(mutableListOf<String>())
            val executionEvents = Collections.synchronizedList(mutableListOf<String>())

            val uriA = Uri.parse("$treeUri/document/file_a")
            val uriB = Uri.parse("$treeUri/document/file_b")
            val payloadB = "Contenuto File B".toByteArray(Charsets.UTF_8)

            val metaA = regularMeta("file_a", "a.bin", "application/octet-stream", 1024L, 11_000L)
            val metaB = regularMeta("file_b", "b.bin", "application/octet-stream", payloadB.size.toLong(), 12_000L)

            val blockedEntered = CountDownLatch(1)
            val pauseA = AtomicBoolean(false)

            contentReader.putMetadata(uriA, metaA)
            contentReader.setBlockingStream(
                documentUri = uriA,
                initialBytes = ByteArray(256) { 0x01 },
                onBlockedEntered = { blockedEntered.countDown() }
            )
            contentReader.putContent(uriB, payloadB, metaB)

            contentReader.onQueryMetadata = { uri ->
                observedThreadNames.add(Thread.currentThread().name)
                executionEvents.add("query:${uri.lastPathSegment}")
            }
            contentReader.onOpenInputStream = { uri ->
                observedThreadNames.add(Thread.currentThread().name)
                executionEvents.add("open:${uri.lastPathSegment}")
            }

            val docA = insertDocEntity("file_a", uriA, "a.bin", "application/octet-stream", 1024L, 11_000L)
            val docB = insertDocEntity("file_b", uriB, "b.bin", "application/octet-stream", payloadB.size.toLong(), 12_000L)

            val jobA = async(Dispatchers.Default) {
                serializedFp.fingerprintFile(
                    request = SingleFileFingerprintRequest(docA, treeUri),
                    isPauseRequested = { pauseA.get() }
                )
            }

            assertTrue("File A doveva raggiungere la lettura bloccante", blockedEntered.await(3, TimeUnit.SECONDS))

            // Avvia File B mentre File A è ancora dentro il Mutex sullo stream bloccato
            val jobB = async(Dispatchers.Default) {
                serializedFp.fingerprintFile(SingleFileFingerprintRequest(docB, treeUri))
            }

            // Attende brevemente per assicurarsi che File B resti accodato sul Mutex senza toccare SAF
            Thread.sleep(100)
            assertFalse("File B non deve accedere a SAF prima del rilascio del Mutex", executionEvents.any { it.endsWith("file_b") })

            // Sblocca File A in pausa tramite abortActiveStream(): solo File A deve andare in Paused, poi File B completa
            pauseA.set(true)
            serializedFp.abortActiveStream()

            val resultA = try {
                withTimeout(3_000L) { jobA.await() }
            } catch (e: TimeoutCancellationException) {
                fail("Timeout in attesa della pausa su File A: ${e.message}")
                return@runBlocking
            }
            val resultB = try {
                withTimeout(3_000L) { jobB.await() }
            } catch (e: TimeoutCancellationException) {
                fail("Timeout in attesa del completamento su File B: ${e.message}")
                return@runBlocking
            }

            assertTrue("File A deve restituire Paused", resultA is SingleFileFingerprintResult.Paused)
            assertTrue("File B deve completare regolarmente con Computed", resultB is SingleFileFingerprintResult.Computed)
            assertEquals(sha256Hex(payloadB), (resultB as SingleFileFingerprintResult.Computed).sha256Hex)

            // Verifica che tutte le operazioni SAF siano girate esclusivamente sul thread di ioDispatcher
            assertTrue(observedThreadNames.isNotEmpty())
            assertTrue("Tutte le operazioni SAF devono girare su ioDispatcher: $observedThreadNames", observedThreadNames.all { it == "fingerprinter-io-test-thread" })

            // Verifica ordine strettamente serializzato (prima A, poi B)
            val lastAIdx = executionEvents.indexOfLast { it.endsWith("file_a") }
            val firstBIdx = executionEvents.indexOfFirst { it.endsWith("file_b") }
            assertTrue("Le operazioni di File B devono iniziare solo dopo la fine di File A: $executionEvents", lastAIdx in 0 until firstBIdx)

            // File A in pausa rimane PENDING senza errori
            val dbDocA = database.scannedDocumentDao().getDocumentsForSession(sessionId).first { it.id == docA.id }
            assertEquals(FingerprintStatus.PENDING.name, dbDocA.fingerprintStatus)
            assertTrue(database.scanErrorDao().getErrorsForSession(sessionId).isEmpty())
            assertTrue(contentReader.allOpenedStreamsClosed)
        } finally {
            ioExecutor.shutdownNow()
        }
    }

    @Test
    fun testCancellationDuringBlockedReadPropagatesWithoutDbModification() = runBlocking {
        val uri = Uri.parse("$treeUri/document/cancel_blocked")
        val meta = regularMeta("cancel_blocked", "cb.bin", "application/octet-stream", 1024L, 15_000L)
        val blockedEntered = CountDownLatch(1)
        val cancelFlag = AtomicBoolean(false)

        contentReader.putMetadata(uri, meta)
        contentReader.setBlockingStream(uri, ByteArray(128) { 0x07 }) {
            blockedEntered.countDown()
        }

        val doc = insertDocEntity("cancel_blocked", uri, "cb.bin", "application/octet-stream", 1024L, 15_000L)
        val job = async(Dispatchers.Default) {
            try {
                fingerprinter.fingerprintFile(
                    request = SingleFileFingerprintRequest(doc, treeUri),
                    isCancelRequested = { cancelFlag.get() }
                )
                Result.success(Unit)
            } catch (t: Throwable) {
                Result.failure<Unit>(t)
            }
        }

        assertTrue(blockedEntered.await(3, TimeUnit.SECONDS))
        cancelFlag.set(true)
        fingerprinter.abortActiveStream()

        val outcome = withTimeout(3_000L) { job.await() }
        val failure = outcome.exceptionOrNull()
        assertTrue("Attesa CancellationException reale, trovata: $failure", failure is CancellationException)
        assertFalse(failure is TimeoutCancellationException)

        val dbDoc = database.scannedDocumentDao().getDocumentsForSession(sessionId).first { it.id == doc.id }
        assertEquals(FingerprintStatus.PENDING.name, dbDoc.fingerprintStatus)
        assertNull(dbDoc.sha256Hex)
        assertTrue(database.scanErrorDao().getErrorsForSession(sessionId).isEmpty())
        assertTrue(contentReader.allOpenedStreamsClosed)
    }

    private suspend fun insertDocEntity(
        docId: String,
        uri: Uri,
        relativePath: String,
        mimeType: String? = "application/octet-stream",
        sizeBytes: Long? = 100L,
        lastModified: Long? = 1000L,
        isDirectory: Boolean = false,
        isExcluded: Boolean = false,
        fingerprintStatus: FingerprintStatus = FingerprintStatus.PENDING,
        sha256Hex: String? = null,
        hashedBytesRead: Long? = null,
        id: String = "row-$sessionId-$docId",
        sessId: String = sessionId
    ): ScannedDocumentEntity {
        val entity = ScannedDocumentEntity(
            id = id,
            scanSessionId = sessId,
            backupFolderId = folderId,
            documentId = docId,
            documentUri = uri.toString(),
            relativePath = relativePath,
            displayName = relativePath.substringAfterLast('/'),
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            lastModifiedTimestamp = lastModified,
            isDirectory = isDirectory,
            isExcluded = isExcluded,
            sha256Hex = sha256Hex,
            fingerprintStatus = fingerprintStatus.name,
            hashedBytesRead = hashedBytesRead
        )
        database.scannedDocumentDao().insertDocument(entity)
        return entity
    }

    private fun regularMeta(
        docId: String,
        displayName: String,
        mimeType: String?,
        sizeBytes: Long?,
        lastModified: Long?
    ) = SafDocumentMetadata(
        documentId = docId,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        lastModifiedTimestamp = lastModified,
        flags = 0,
        isDirectory = false
    )

    private fun makeFingerprintEntity(
        docId: String,
        uri: Uri,
        relativePath: String,
        sizeBytes: Long,
        lastModified: Long
    ) = FileFingerprintEntity(
        backupFolderId = folderId,
        documentId = docId,
        documentUri = uri.toString(),
        relativePath = relativePath,
        mimeType = "text/plain",
        sizeBytes = sizeBytes,
        lastModifiedTimestamp = lastModified,
        computedBytes = sizeBytes,
        sha256Hex = sha256Hex("seed".toByteArray(Charsets.UTF_8)),
        lastContentHashedTimestamp = 500_000L,
        lastMetadataMatchedTimestamp = 500_000L,
        lastSeenSessionId = "seed-session"
    )

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Sha256StreamHasher.toLowerHex(digest)
    }
}
