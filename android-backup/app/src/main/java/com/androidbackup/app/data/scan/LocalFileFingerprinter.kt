package com.androidbackup.app.data.scan

import android.net.Uri
import android.provider.DocumentsContract
import com.androidbackup.app.data.local.dao.FileFingerprintDao
import com.androidbackup.app.data.local.dao.ScanErrorDao
import com.androidbackup.app.data.local.dao.ScannedDocumentDao
import com.androidbackup.app.data.local.entity.FileFingerprintEntity
import com.androidbackup.app.data.local.entity.FingerprintMode
import com.androidbackup.app.data.local.entity.FingerprintStatus
import com.androidbackup.app.data.local.entity.ScanErrorEntity
import com.androidbackup.app.data.local.entity.ScanErrorPhase
import com.androidbackup.app.data.local.entity.ScanErrorSeverity
import com.androidbackup.app.data.local.entity.ScannedDocumentEntity
import com.androidbackup.app.data.saf.NullCursorException
import com.androidbackup.app.data.saf.SafContentReader
import com.androidbackup.app.data.saf.SafDocumentMetadata
import com.androidbackup.app.data.saf.SafPermissionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

/**
 * Richiesta di elaborazione della fingerprint SHA-256 per un singolo documento inventariato.
 */
data class SingleFileFingerprintRequest(
    val document: ScannedDocumentEntity,
    val folderTreeUri: Uri,
    val mode: FingerprintMode = FingerprintMode.INCREMENTAL,
    val maxCacheAgeMs: Long = LocalFileFingerprinter.DEFAULT_MAX_CACHE_AGE_MS,
    val reusableBuffer: ByteArray? = null
)

/**
 * Esito tipizzato dell'elaborazione di un singolo file da parte di [LocalFileFingerprinter].
 */
sealed class SingleFileFingerprintResult {
    data class Computed(
        val sha256Hex: String,
        val bytesRead: Long,
        val timestamp: Long
    ) : SingleFileFingerprintResult()

    data class ReusedCache(
        val sha256Hex: String,
        val cachedTimestamp: Long,
        val matchedTimestamp: Long
    ) : SingleFileFingerprintResult()

    data class Unsupported(
        val errorType: String,
        val reason: String
    ) : SingleFileFingerprintResult()

    data class Failed(
        val errorType: String,
        val message: String
    ) : SingleFileFingerprintResult()

    object NotRequired : SingleFileFingerprintResult()

    object Paused : SingleFileFingerprintResult()
}

/**
 * Motore di fingerprinting SHA-256 per singolo file locale tramite SAF (Fase 1B.3B).
 *
 * Responsabilità e garanzie:
 * - Esegue tutte le interrogazioni SAF, lo streaming SHA-256 e le scritture Room su [ioDispatcher].
 * - Serializza le chiamate tramite [executionMutex] interno affinché [Sha256StreamHasher.abortActiveStream]
 *   non possa interferire con altri file e senza elaborazione parallela.
 * - Verifica permessi SAF e metadati live prima di qualsiasi riuso cache.
 * - Gestisce documenti virtuali ([DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT]) come UNSUPPORTED
 *   senza mai aprire o convertire lo stream.
 * - Verifica corrispondenza byte letti e stabilità dei metadati pre/post-lettura prima di salvare in cache.
 * - Propaga ogni errore DAO Room (in lettura o scrittura) come [ScanPersistenceException], escludendo
 *   sempre [CancellationException].
 */
class LocalFileFingerprinter(
    private val fileFingerprintDao: FileFingerprintDao,
    private val scannedDocumentDao: ScannedDocumentDao,
    private val scanErrorDao: ScanErrorDao,
    private val safContentReader: SafContentReader,
    private val safPermissionManager: SafPermissionManager,
    private val streamHasher: Sha256StreamHasher,
    private val runInTransaction: suspend (suspend () -> Unit) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowProvider: () -> Long = System::currentTimeMillis
) {

    private val executionMutex = Mutex()

    /**
     * Tenta di sbloccare dall'esterno una eventuale lettura bloccante sullo stream attualmente attivo.
     * Non acquisisce [executionMutex] per evitare deadlock durante il blocco I/O nativo.
     */
    fun abortActiveStream() {
        streamHasher.abortActiveStream()
    }

    /**
     * Elabora la fingerprint SHA-256 di un singolo [ScannedDocumentEntity].
     */
    suspend fun fingerprintFile(
        request: SingleFileFingerprintRequest,
        isPauseRequested: () -> Boolean = { false },
        isCancelRequested: () -> Boolean = { false }
    ): SingleFileFingerprintResult = withContext(ioDispatcher) {
        executionMutex.withLock {
            val doc = request.document

            // 1. Directory o elementi esclusi non richiedono fingerprinting
            if (doc.isDirectory || doc.isExcluded) {
                if (doc.parsedFingerprintStatus != FingerprintStatus.NOT_REQUIRED ||
                    doc.sha256Hex != null ||
                    doc.hashedBytesRead != null
                ) {
                    checkCancelled(isCancelRequested)
                    if (isPauseRequested()) {
                        return@withLock SingleFileFingerprintResult.Paused
                    }
                    executeRoomTransaction {
                        updateScannedDocumentRow(
                            rowId = doc.id,
                            mimeType = doc.mimeType,
                            sizeBytes = doc.sizeBytes,
                            lastModifiedTimestamp = doc.lastModifiedTimestamp,
                            sha256Hex = null,
                            status = FingerprintStatus.NOT_REQUIRED,
                            error = null,
                            timestamp = null,
                            hashedBytesRead = null
                        )
                    }
                }
                return@withLock SingleFileFingerprintResult.NotRequired
            }

            // 2. Check cooperativo pre-avvio
            checkCancelled(isCancelRequested)
            if (isPauseRequested()) {
                return@withLock SingleFileFingerprintResult.Paused
            }

            // 3. Verifica permesso SAF persistente sull'albero
            if (!safPermissionManager.hasPersistedReadPermission(request.folderTreeUri)) {
                checkCancelled(isCancelRequested)
                return@withLock persistFailedOutcome(
                    doc = doc,
                    mimeType = doc.mimeType,
                    sizeBytes = doc.sizeBytes,
                    lastModifiedTimestamp = doc.lastModifiedTimestamp,
                    errorType = ERROR_PERMISSION_REVOKED,
                    message = "Autorizzazione SAF di lettura revocata per la cartella principale."
                )
            }

            // 4. Query metadati live pre-lettura
            val documentUri = Uri.parse(doc.documentUri)
            val preMeta = when (val queryResult = queryLiveMetadata(documentUri, request.folderTreeUri)) {
                is MetadataQueryOutcome.Success -> queryResult.metadata
                is MetadataQueryOutcome.Failure -> {
                    checkCancelled(isCancelRequested)
                    return@withLock persistFailedOutcome(
                        doc = doc,
                        mimeType = doc.mimeType,
                        sizeBytes = doc.sizeBytes,
                        lastModifiedTimestamp = doc.lastModifiedTimestamp,
                        errorType = queryResult.errorType,
                        message = queryResult.message
                    )
                }
            }

            checkCancelled(isCancelRequested)
            if (isPauseRequested()) {
                return@withLock SingleFileFingerprintResult.Paused
            }

            // 5. Verifica identità SAF e tipologia documento pre-lettura
            if (preMeta.documentId != doc.documentId) {
                return@withLock persistFailedOutcome(
                    doc = doc,
                    mimeType = doc.mimeType,
                    sizeBytes = doc.sizeBytes,
                    lastModifiedTimestamp = doc.lastModifiedTimestamp,
                    errorType = ERROR_DOCUMENT_ID_CHANGED,
                    message = "Identificativo SAF del documento cambiato rispetto alla Discovery ('${doc.documentId}' -> '${preMeta.documentId}'). Necessaria nuova Discovery."
                )
            }

            if (preMeta.isDirectory) {
                return@withLock persistFailedOutcome(
                    doc = doc,
                    mimeType = preMeta.mimeType,
                    sizeBytes = preMeta.sizeBytes,
                    lastModifiedTimestamp = preMeta.lastModifiedTimestamp,
                    errorType = ERROR_UNEXPECTED_DIRECTORY,
                    message = "Il documento '${doc.relativePath}' risulta una directory sul provider SAF."
                )
            }

            if (isVirtualDocument(preMeta)) {
                return@withLock persistUnsupportedOutcome(
                    doc = doc,
                    liveMeta = preMeta,
                    reason = "Documento virtuale SAF (FLAG_VIRTUAL_DOCUMENT) non supportato per il calcolo SHA-256 diretto."
                )
            }

            // 6. Confronto con i metadati registrati in Discovery ed eventuale cache hit
            val changedSinceDiscovery = hasMetadataChanged(
                liveMeta = preMeta,
                expectedSize = doc.sizeBytes,
                expectedLastModified = doc.lastModifiedTimestamp,
                expectedMimeType = doc.mimeType
            )

            if (!changedSinceDiscovery && request.mode != FingerprintMode.FORCE_RECOMPUTE) {
                val cached = executeRoomRead {
                    fileFingerprintDao.getByFolderAndDocument(doc.backupFolderId, doc.documentId)
                }
                val matchTime = nowProvider()
                if (cached != null && isCacheEligibleForHit(preMeta, cached, request.mode, request.maxCacheAgeMs, matchTime)) {
                    checkCancelled(isCancelRequested)
                    if (isPauseRequested()) {
                        return@withLock SingleFileFingerprintResult.Paused
                    }

                    executeRoomTransaction {
                        val updatedCacheRows = fileFingerprintDao.updateMetadataObservation(
                            folderId = doc.backupFolderId,
                            documentId = doc.documentId,
                            documentUri = doc.documentUri,
                            relativePath = doc.relativePath,
                            sessionId = doc.scanSessionId,
                            matchedTimestamp = matchTime
                        )
                        if (updatedCacheRows <= 0) {
                            throw IllegalStateException(
                                "Impossibile aggiornare l'osservazione cache per (${doc.backupFolderId}, ${doc.documentId})."
                            )
                        }
                        updateScannedDocumentRow(
                            rowId = doc.id,
                            mimeType = preMeta.mimeType,
                            sizeBytes = preMeta.sizeBytes,
                            lastModifiedTimestamp = preMeta.lastModifiedTimestamp,
                            sha256Hex = cached.sha256Hex,
                            status = FingerprintStatus.REUSED_CACHE,
                            error = null,
                            timestamp = matchTime,
                            hashedBytesRead = null
                        )
                    }

                    return@withLock SingleFileFingerprintResult.ReusedCache(
                        sha256Hex = cached.sha256Hex,
                        cachedTimestamp = cached.lastContentHashedTimestamp,
                        matchedTimestamp = matchTime
                    )
                }
            }

            // 7. Apertura stream SAF in sola lettura e calcolo SHA-256 in streaming
            checkCancelled(isCancelRequested)
            if (isPauseRequested()) {
                return@withLock SingleFileFingerprintResult.Paused
            }

            val inputStream = try {
                safContentReader.openInputStream(documentUri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                checkCancelled(isCancelRequested)
                val errorType = classifySecurityException(request.folderTreeUri)
                return@withLock persistFailedOutcome(
                    doc = doc,
                    mimeType = preMeta.mimeType,
                    sizeBytes = preMeta.sizeBytes,
                    lastModifiedTimestamp = preMeta.lastModifiedTimestamp,
                    errorType = errorType,
                    message = "Permesso negato durante l'apertura dello stream SAF: ${e.localizedMessage ?: e.javaClass.simpleName}"
                )
            } catch (e: FileNotFoundException) {
                checkCancelled(isCancelRequested)
                return@withLock persistFailedOutcome(
                    doc = doc,
                    mimeType = preMeta.mimeType,
                    sizeBytes = preMeta.sizeBytes,
                    lastModifiedTimestamp = preMeta.lastModifiedTimestamp,
                    errorType = ERROR_FILE_NOT_FOUND,
                    message = "File non trovato durante l'apertura dello stream SAF: ${e.localizedMessage ?: e.javaClass.simpleName}"
                )
            } catch (e: IOException) {
                checkCancelled(isCancelRequested)
                return@withLock persistFailedOutcome(
                    doc = doc,
                    mimeType = preMeta.mimeType,
                    sizeBytes = preMeta.sizeBytes,
                    lastModifiedTimestamp = preMeta.lastModifiedTimestamp,
                    errorType = ERROR_HASH_IO,
                    message = "Errore I/O durante l'apertura dello stream SAF: ${e.localizedMessage ?: e.javaClass.simpleName}"
                )
            } catch (e: Exception) {
                checkCancelled(isCancelRequested)
                return@withLock persistFailedOutcome(
                    doc = doc,
                    mimeType = preMeta.mimeType,
                    sizeBytes = preMeta.sizeBytes,
                    lastModifiedTimestamp = preMeta.lastModifiedTimestamp,
                    errorType = ERROR_HASH_IO,
                    message = "Errore imprevisto durante l'apertura dello stream SAF: ${e.localizedMessage ?: e.javaClass.simpleName}"
                )
            }

            if (inputStream == null) {
                checkCancelled(isCancelRequested)
                return@withLock persistFailedOutcome(
                    doc = doc,
                    mimeType = preMeta.mimeType,
                    sizeBytes = preMeta.sizeBytes,
                    lastModifiedTimestamp = preMeta.lastModifiedTimestamp,
                    errorType = ERROR_NULL_INPUT_STREAM,
                    message = "Il provider SAF ha restituito uno stream nullo per '${doc.relativePath}'."
                )
            }

            val hashResult = try {
                streamHasher.computeSha256(
                    inputStream = inputStream,
                    isPauseRequested = isPauseRequested,
                    isCancelRequested = isCancelRequested,
                    reusableBuffer = request.reusableBuffer
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: HashingPausedException) {
                return@withLock SingleFileFingerprintResult.Paused
            } catch (e: IOException) {
                checkCancelled(isCancelRequested)
                return@withLock persistFailedOutcome(
                    doc = doc,
                    mimeType = preMeta.mimeType,
                    sizeBytes = preMeta.sizeBytes,
                    lastModifiedTimestamp = preMeta.lastModifiedTimestamp,
                    errorType = ERROR_HASH_IO,
                    message = "Errore I/O durante la lettura in streaming dello stream SAF: ${e.localizedMessage ?: e.javaClass.simpleName}"
                )
            } catch (e: Exception) {
                checkCancelled(isCancelRequested)
                return@withLock persistFailedOutcome(
                    doc = doc,
                    mimeType = preMeta.mimeType,
                    sizeBytes = preMeta.sizeBytes,
                    lastModifiedTimestamp = preMeta.lastModifiedTimestamp,
                    errorType = ERROR_HASH_IO,
                    message = "Errore imprevisto durante il calcolo SHA-256: ${e.localizedMessage ?: e.javaClass.simpleName}"
                )
            }

            // 8. Verifica corrispondenza byte letti vs dimensione attesa pre-lettura
            val expectedPreSize = preMeta.sizeBytes
            if (expectedPreSize != null && expectedPreSize >= 0L && hashResult.bytesRead != expectedPreSize) {
                checkCancelled(isCancelRequested)
                return@withLock persistFailedOutcome(
                    doc = doc,
                    mimeType = preMeta.mimeType,
                    sizeBytes = preMeta.sizeBytes,
                    lastModifiedTimestamp = preMeta.lastModifiedTimestamp,
                    errorType = ERROR_SIZE_MISMATCH,
                    message = "Numero di byte letti (${hashResult.bytesRead}) diverso dalla dimensione attesa ($expectedPreSize) per '${doc.relativePath}'."
                )
            }

            // 9. Verifica metadati live post-lettura (coerenza contro modifiche concorrenti)
            checkCancelled(isCancelRequested)
            if (isPauseRequested()) {
                return@withLock SingleFileFingerprintResult.Paused
            }

            val postMeta = when (val postQuery = queryLiveMetadata(documentUri, request.folderTreeUri)) {
                is MetadataQueryOutcome.Success -> postQuery.metadata
                is MetadataQueryOutcome.Failure -> {
                    checkCancelled(isCancelRequested)
                    return@withLock persistFailedOutcome(
                        doc = doc,
                        mimeType = preMeta.mimeType,
                        sizeBytes = preMeta.sizeBytes,
                        lastModifiedTimestamp = preMeta.lastModifiedTimestamp,
                        errorType = postQuery.errorType,
                        message = postQuery.message
                    )
                }
            }

            checkCancelled(isCancelRequested)
            if (isPauseRequested()) {
                return@withLock SingleFileFingerprintResult.Paused
            }

            if (postMeta.documentId != doc.documentId) {
                return@withLock persistFailedOutcome(
                    doc = doc,
                    mimeType = preMeta.mimeType,
                    sizeBytes = preMeta.sizeBytes,
                    lastModifiedTimestamp = preMeta.lastModifiedTimestamp,
                    errorType = ERROR_DOCUMENT_ID_CHANGED,
                    message = "Identificativo SAF del documento cambiato durante la lettura ('${doc.documentId}' -> '${postMeta.documentId}')."
                )
            }

            if (postMeta.isDirectory) {
                return@withLock persistFailedOutcome(
                    doc = doc,
                    mimeType = postMeta.mimeType,
                    sizeBytes = postMeta.sizeBytes,
                    lastModifiedTimestamp = postMeta.lastModifiedTimestamp,
                    errorType = ERROR_UNEXPECTED_DIRECTORY,
                    message = "Il documento '${doc.relativePath}' è diventato una directory al termine della lettura."
                )
            }

            if (isVirtualDocument(postMeta)) {
                return@withLock persistUnsupportedOutcome(
                    doc = doc,
                    liveMeta = postMeta,
                    reason = "Il documento '${doc.relativePath}' è risultato virtuale (FLAG_VIRTUAL_DOCUMENT) nella verifica post-lettura."
                )
            }

            val modifiedDuringHash = hasMetadataChanged(
                liveMeta = postMeta,
                expectedSize = preMeta.sizeBytes,
                expectedLastModified = preMeta.lastModifiedTimestamp,
                expectedMimeType = preMeta.mimeType
            ) || (postMeta.sizeBytes != null && postMeta.sizeBytes >= 0L && hashResult.bytesRead != postMeta.sizeBytes)

            if (modifiedDuringHash) {
                return@withLock persistFailedOutcome(
                    doc = doc,
                    mimeType = postMeta.mimeType,
                    sizeBytes = postMeta.sizeBytes,
                    lastModifiedTimestamp = postMeta.lastModifiedTimestamp,
                    errorType = ERROR_FILE_MODIFIED_DURING_HASH,
                    message = "Il file '${doc.relativePath}' o i relativi metadati risultano modificati durante il calcolo SHA-256."
                )
            }

            // 10. Persistenza atomica di successo in cache + inventario sessione
            checkCancelled(isCancelRequested)
            if (isPauseRequested()) {
                return@withLock SingleFileFingerprintResult.Paused
            }

            val computedTimestamp = nowProvider()
            executeRoomTransaction {
                fileFingerprintDao.upsertFingerprint(
                    FileFingerprintEntity(
                        id = UUID.randomUUID().toString(),
                        backupFolderId = doc.backupFolderId,
                        documentId = doc.documentId,
                        documentUri = doc.documentUri,
                        relativePath = doc.relativePath,
                        mimeType = postMeta.mimeType,
                        sizeBytes = postMeta.sizeBytes,
                        lastModifiedTimestamp = postMeta.lastModifiedTimestamp,
                        computedBytes = hashResult.bytesRead,
                        sha256Hex = hashResult.sha256Hex,
                        lastContentHashedTimestamp = computedTimestamp,
                        lastMetadataMatchedTimestamp = computedTimestamp,
                        lastSeenSessionId = doc.scanSessionId
                    )
                )
                updateScannedDocumentRow(
                    rowId = doc.id,
                    mimeType = postMeta.mimeType,
                    sizeBytes = postMeta.sizeBytes,
                    lastModifiedTimestamp = postMeta.lastModifiedTimestamp,
                    sha256Hex = hashResult.sha256Hex,
                    status = FingerprintStatus.COMPUTED,
                    error = null,
                    timestamp = computedTimestamp,
                    hashedBytesRead = hashResult.bytesRead
                )
            }

            SingleFileFingerprintResult.Computed(
                sha256Hex = hashResult.sha256Hex,
                bytesRead = hashResult.bytesRead,
                timestamp = computedTimestamp
            )
        }
    }

    private sealed class MetadataQueryOutcome {
        data class Success(val metadata: SafDocumentMetadata) : MetadataQueryOutcome()
        data class Failure(val errorType: String, val message: String) : MetadataQueryOutcome()
    }

    private fun queryLiveMetadata(
        documentUri: Uri,
        treeUri: Uri
    ): MetadataQueryOutcome {
        return try {
            val metadata = safContentReader.queryDocumentMetadata(documentUri)
            if (metadata == null) {
                MetadataQueryOutcome.Failure(
                    errorType = ERROR_FILE_NOT_FOUND,
                    message = "Documento SAF non più presente o cursore vuoto per '$documentUri'."
                )
            } else {
                MetadataQueryOutcome.Success(metadata)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            MetadataQueryOutcome.Failure(
                errorType = classifySecurityException(treeUri),
                message = "Accesso ai metadati SAF negato per '$documentUri': ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
        } catch (e: FileNotFoundException) {
            MetadataQueryOutcome.Failure(
                errorType = ERROR_FILE_NOT_FOUND,
                message = "Documento SAF non trovato durante la lettura dei metadati: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
        } catch (e: NullCursorException) {
            MetadataQueryOutcome.Failure(
                errorType = ERROR_NULL_CURSOR,
                message = e.message ?: "Il provider SAF ha restituito un Cursor nullo per '$documentUri'."
            )
        } catch (e: IOException) {
            MetadataQueryOutcome.Failure(
                errorType = ERROR_METADATA_READ,
                message = "Errore I/O durante l'interrogazione dei metadati SAF: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
        } catch (e: Exception) {
            MetadataQueryOutcome.Failure(
                errorType = ERROR_METADATA_READ,
                message = "Errore imprevisto durante l'interrogazione dei metadati SAF: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
        }
    }

    private suspend fun persistFailedOutcome(
        doc: ScannedDocumentEntity,
        mimeType: String?,
        sizeBytes: Long?,
        lastModifiedTimestamp: Long?,
        errorType: String,
        message: String
    ): SingleFileFingerprintResult.Failed {
        val now = nowProvider()
        executeRoomTransaction {
            updateScannedDocumentRow(
                rowId = doc.id,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                lastModifiedTimestamp = lastModifiedTimestamp,
                sha256Hex = null,
                status = FingerprintStatus.FAILED,
                error = message,
                timestamp = now,
                hashedBytesRead = null
            )
            scanErrorDao.insertError(
                ScanErrorEntity(
                    id = UUID.randomUUID().toString(),
                    scanSessionId = doc.scanSessionId,
                    backupFolderId = doc.backupFolderId,
                    documentId = doc.documentId,
                    uri = doc.documentUri,
                    errorType = errorType,
                    message = message,
                    timestamp = now,
                    errorPhase = ScanErrorPhase.FINGERPRINTING.name,
                    severity = ScanErrorSeverity.ERROR.name
                )
            )
        }
        return SingleFileFingerprintResult.Failed(errorType = errorType, message = message)
    }

    private suspend fun persistUnsupportedOutcome(
        doc: ScannedDocumentEntity,
        liveMeta: SafDocumentMetadata,
        reason: String
    ): SingleFileFingerprintResult.Unsupported {
        val now = nowProvider()
        executeRoomTransaction {
            updateScannedDocumentRow(
                rowId = doc.id,
                mimeType = liveMeta.mimeType,
                sizeBytes = liveMeta.sizeBytes,
                lastModifiedTimestamp = liveMeta.lastModifiedTimestamp,
                sha256Hex = null,
                status = FingerprintStatus.UNSUPPORTED,
                error = reason,
                timestamp = now,
                hashedBytesRead = null
            )
            scanErrorDao.insertError(
                ScanErrorEntity(
                    id = UUID.randomUUID().toString(),
                    scanSessionId = doc.scanSessionId,
                    backupFolderId = doc.backupFolderId,
                    documentId = doc.documentId,
                    uri = doc.documentUri,
                    errorType = ERROR_VIRTUAL_DOCUMENT_UNSUPPORTED,
                    message = reason,
                    timestamp = now,
                    errorPhase = ScanErrorPhase.FINGERPRINTING.name,
                    severity = ScanErrorSeverity.UNSUPPORTED.name
                )
            )
        }
        return SingleFileFingerprintResult.Unsupported(
            errorType = ERROR_VIRTUAL_DOCUMENT_UNSUPPORTED,
            reason = reason
        )
    }

    private suspend fun updateScannedDocumentRow(
        rowId: String,
        mimeType: String?,
        sizeBytes: Long?,
        lastModifiedTimestamp: Long?,
        sha256Hex: String?,
        status: FingerprintStatus,
        error: String?,
        timestamp: Long?,
        hashedBytesRead: Long?
    ) {
        val updatedRows = scannedDocumentDao.updateFingerprintOutcome(
            rowId = rowId,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            lastModifiedTimestamp = lastModifiedTimestamp,
            sha256Hex = sha256Hex,
            fingerprintStatus = status.name,
            fingerprintError = error,
            fingerprintTimestamp = timestamp,
            hashedBytesRead = hashedBytesRead
        )
        if (updatedRows != 1) {
            throw IllegalStateException(
                "Aggiornamento scanned_documents atteso per 1 riga (id='$rowId'), aggiornate=$updatedRows."
            )
        }
    }

    private suspend fun <T> executeRoomRead(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ScanPersistenceException) {
            throw e
        } catch (e: Exception) {
            throw ScanPersistenceException(
                "Errore Room durante la lettura della cache fingerprint: ${e.localizedMessage ?: e.javaClass.simpleName}",
                e
            )
        }
    }

    private suspend fun executeRoomTransaction(block: suspend () -> Unit) {
        try {
            runInTransaction {
                block()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ScanPersistenceException) {
            throw e
        } catch (e: Exception) {
            throw ScanPersistenceException(
                "Errore Room durante il salvataggio atomico del fingerprinting: ${e.localizedMessage ?: e.javaClass.simpleName}",
                e
            )
        }
    }

    private suspend fun checkCancelled(isCancelRequested: () -> Boolean) {
        currentCoroutineContext().ensureActive()
        if (isCancelRequested()) {
            throw CancellationException("Fingerprinting annullato cooperativamente.")
        }
    }

    private fun classifySecurityException(treeUri: Uri): String {
        return if (!safPermissionManager.hasPersistedReadPermission(treeUri)) {
            ERROR_PERMISSION_REVOKED
        } else {
            ERROR_FILE_PERMISSION_DENIED
        }
    }

    private fun isVirtualDocument(metadata: SafDocumentMetadata): Boolean {
        val flags = metadata.flags ?: 0
        return (flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0
    }

    private fun hasMetadataChanged(
        liveMeta: SafDocumentMetadata,
        expectedSize: Long?,
        expectedLastModified: Long?,
        expectedMimeType: String?
    ): Boolean {
        return liveMeta.sizeBytes != expectedSize ||
            liveMeta.lastModifiedTimestamp != expectedLastModified ||
            liveMeta.mimeType != expectedMimeType
    }

    private fun isCacheEligibleForHit(
        liveMeta: SafDocumentMetadata,
        cached: FileFingerprintEntity,
        mode: FingerprintMode,
        maxCacheAgeMs: Long,
        nowMs: Long
    ): Boolean {
        if (mode == FingerprintMode.FORCE_RECOMPUTE) return false
        if (!isValidSha256Hex(cached.sha256Hex)) return false

        val liveSize = liveMeta.sizeBytes
        val liveMtime = liveMeta.lastModifiedTimestamp
        val cachedSize = cached.sizeBytes
        val cachedMtime = cached.lastModifiedTimestamp

        // Affidabilità minima richiesta per fidarsi dei metadati senza ricalcolo
        if (liveSize == null || liveSize < 0L || liveMtime == null || liveMtime <= 0L) {
            return false
        }
        if (cachedSize == null || cachedSize < 0L || cachedMtime == null || cachedMtime <= 0L) {
            return false
        }

        if (liveSize != cachedSize ||
            liveMtime != cachedMtime ||
            liveMeta.mimeType != cached.mimeType ||
            cached.computedBytes != liveSize
        ) {
            return false
        }

        if (mode == FingerprintMode.PERIODIC_REVERIFY) {
            if (maxCacheAgeMs <= 0L) return false
            val ageMs = nowMs - cached.lastContentHashedTimestamp
            if (ageMs < 0L || ageMs >= maxCacheAgeMs) {
                return false
            }
        }

        return true
    }

    private fun isValidSha256Hex(hex: String): Boolean {
        if (hex.length != 64) return false
        return hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    companion object {
        const val DEFAULT_MAX_CACHE_AGE_MS: Long = 7L * 24L * 60L * 60L * 1000L

        const val ERROR_PERMISSION_REVOKED = "PERMISSION_REVOKED"
        const val ERROR_FILE_PERMISSION_DENIED = "FILE_PERMISSION_DENIED"
        const val ERROR_FILE_NOT_FOUND = "FILE_NOT_FOUND"
        const val ERROR_NULL_CURSOR = "NULL_CURSOR"
        const val ERROR_METADATA_READ = "METADATA_READ_ERROR"
        const val ERROR_DOCUMENT_ID_CHANGED = "DOCUMENT_ID_CHANGED"
        const val ERROR_UNEXPECTED_DIRECTORY = "UNEXPECTED_DIRECTORY"
        const val ERROR_VIRTUAL_DOCUMENT_UNSUPPORTED = "VIRTUAL_DOCUMENT_UNSUPPORTED"
        const val ERROR_NULL_INPUT_STREAM = "NULL_INPUT_STREAM"
        const val ERROR_HASH_IO = "HASH_IO_ERROR"
        const val ERROR_SIZE_MISMATCH = "SIZE_MISMATCH"
        const val ERROR_FILE_MODIFIED_DURING_HASH = "FILE_MODIFIED_DURING_HASH"
    }
}
