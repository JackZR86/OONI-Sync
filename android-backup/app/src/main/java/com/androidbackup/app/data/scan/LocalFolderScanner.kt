package com.androidbackup.app.data.scan

import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.androidbackup.app.data.local.dao.PendingDirectoryDao
import com.androidbackup.app.data.local.dao.ScanErrorDao
import com.androidbackup.app.data.local.dao.ScanSessionDao
import com.androidbackup.app.data.local.dao.ScannedDocumentDao
import com.androidbackup.app.data.local.entity.*
import com.androidbackup.app.data.model.BackupSource
import com.androidbackup.app.data.saf.NullCursorException
import com.androidbackup.app.data.saf.SafDocumentMetadata
import com.androidbackup.app.data.saf.SafPermissionManager
import com.androidbackup.app.data.saf.SafTreeReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Esito tipizzato della sola fase di Discovery ([LocalFolderScanner.runDiscovery]).
 */
sealed class DiscoveryOutcome {
    object Completed : DiscoveryOutcome()
    object Paused : DiscoveryOutcome()
    object Cancelled : DiscoveryOutcome()
}

/**
 * Motore di scansione ricorsiva locale delle cartelle SAF (Fase 1B.2 - 1B.3C).
 *
 * Conforme alla Specifica Fase 1B.2, 1B.3B e 1B.3C:
 * - Esplorazione ricorsiva ad albero una directory alla volta tramite la coda persistente [PendingDirectoryEntity].
 * - Elaborazione progressiva in streaming a piccoli batch (50-100 elementi) per non accumulare dati in memoria.
 * - Idempotente e resiliente a interruzioni di processo.
 * - Distinzione netta tra directory vuota (0 elementi) e Cursor nullo (errore del provider).
 * - Prevenzione cicli e directory già visitate basata sull'identità persistita in [ScannedDocumentEntity].
 * - Inizializzazione sessione atomica e transazionale con configurazione di [FingerprintMode] e TTL.
 * - Supporto a Pausa, Ripresa e Annullamento cooperativo senza leakage di flag tra sessioni.
 * - Separazione esplicita della sola fase Discovery ([runDiscovery]) dalla finalizzazione completa gestita da [ScanRepository].
 */
class LocalFolderScanner(
    private val scanSessionDao: ScanSessionDao,
    private val scannedDocumentDao: ScannedDocumentDao,
    private val pendingDirectoryDao: PendingDirectoryDao,
    private val scanErrorDao: ScanErrorDao,
    private val safTreeReader: SafTreeReader,
    private val safPermissionManager: SafPermissionManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    @Volatile
    private var pauseRequested = false

    @Volatile
    private var cancelRequested = false

    /**
     * Resetta in modo esplicito i flag di controllo per evitare contaminazioni tra sessioni.
     */
    fun resetControlFlags() {
        pauseRequested = false
        cancelRequested = false
    }

    fun requestPause() {
        pauseRequested = true
    }

    fun requestCancel() {
        cancelRequested = true
    }

    /**
     * Inizializza atomicamente una nuova sessione di scansione per le cartelle fornite.
     * Crea in una sola transazione la riga della sessione, la coda di directory pendenti
     * e registra le directory radice in [scanned_documents] per abilitare la prevenzione dei cicli.
     */
    suspend fun createNewSession(
        folders: List<BackupSource>,
        fingerprintMode: FingerprintMode = FingerprintMode.INCREMENTAL,
        fingerprintMaxCacheAgeMs: Long? = null
    ): ScanSessionEntity = withContext(ioDispatcher) {
        resetControlFlags()

        val sessionId = UUID.randomUUID().toString()
        val session = ScanSessionEntity(
            id = sessionId,
            startedTimestamp = System.currentTimeMillis(),
            status = ScanSessionStatus.RUNNING.name,
            scanPhase = ScanPhase.DISCOVERY.name,
            fingerprintMode = fingerprintMode.name,
            fingerprintMaxCacheAgeMs = fingerprintMaxCacheAgeMs
        )

        val rootPending = mutableListOf<PendingDirectoryEntity>()
        val rootDocuments = mutableListOf<ScannedDocumentEntity>()

        for (folder in folders) {
            val treeUri = Uri.parse(folder.path)
            val rootDocId = extractTreeDocumentId(treeUri)

            rootPending.add(
                PendingDirectoryEntity(
                    id = UUID.randomUUID().toString(),
                    scanSessionId = sessionId,
                    backupFolderId = folder.id,
                    treeUri = folder.path,
                    documentId = rootDocId,
                    relativePath = "",
                    enqueueTimestamp = System.currentTimeMillis()
                )
            )

            // Registra la radice come directory già incontrata per prevenire cicli verso la radice
            rootDocuments.add(
                ScannedDocumentEntity(
                    id = UUID.randomUUID().toString(),
                    scanSessionId = sessionId,
                    backupFolderId = folder.id,
                    documentId = rootDocId,
                    documentUri = folder.path,
                    relativePath = "",
                    displayName = folder.name,
                    mimeType = "vnd.android.document/directory",
                    sizeBytes = null,
                    lastModifiedTimestamp = null,
                    isDirectory = true,
                    isExcluded = false,
                    fingerprintStatus = FingerprintStatus.NOT_REQUIRED.name
                )
            )
        }

        scanSessionDao.initializeSessionWithDirectories(session, rootPending, rootDocuments)
        session
    }

    /**
     * Esegue o riprende esclusivamente la fase di Discovery per la sessione specificata.
     * NON finalizza la sessione, NON imposta `scan_phase = COMPLETED` e NON esegue la pulizia
     * delle sessioni precedenti: tali operazioni appartengono esclusivamente a [ScanRepository].
     */
    suspend fun runDiscovery(
        sessionId: String,
        exclusionPatterns: List<String>
    ): DiscoveryOutcome = withContext(ioDispatcher) {
        try {
            executeRoomOperation("ripristino stato RUNNING in Discovery") {
                scanSessionDao.updateStatus(sessionId, ScanSessionStatus.RUNNING.name)
            }

            while (true) {
                currentCoroutineContext().ensureActive()

                if (cancelRequested) {
                    return@withContext DiscoveryOutcome.Cancelled
                }

                if (pauseRequested) {
                    return@withContext DiscoveryOutcome.Paused
                }

                // Preleva la prossima directory pendente dalla coda persistente
                val pending = executeRoomOperation("lettura prossima directory pendente") {
                    pendingDirectoryDao.getNextPendingDirectory(sessionId)
                } ?: return@withContext DiscoveryOutcome.Completed

                val fullyProcessed = processDirectory(sessionId, pending, exclusionPatterns)

                // Aggiorna le statistiche aggregate correnti
                executeRoomOperation("aggiornamento statistiche Discovery") {
                    updateSessionStatistics(sessionId)
                }

                if (!fullyProcessed) {
                    if (cancelRequested) {
                        return@withContext DiscoveryOutcome.Cancelled
                    }
                    if (pauseRequested) {
                        return@withContext DiscoveryOutcome.Paused
                    }
                }
            }

            DiscoveryOutcome.Completed
        } finally {
            resetControlFlags()
        }
    }

    /**
     * Mantenuto per retrocompatibilità dei test unitari isolati della Fase 1B.2 su [LocalFolderScanner]:
     * delega a [runDiscovery] e applica la finalizzazione legacy della sola Discovery.
     */
    suspend fun runScan(
        sessionId: String,
        exclusionPatterns: List<String>
    ): ScanSessionStatus = withContext(ioDispatcher) {
        when (runDiscovery(sessionId, exclusionPatterns)) {
            DiscoveryOutcome.Cancelled -> {
                scanSessionDao.updateStatus(sessionId, ScanSessionStatus.CANCELLED.name)
                pendingDirectoryDao.clearPendingForSession(sessionId)
                ScanSessionStatus.CANCELLED
            }
            DiscoveryOutcome.Paused -> {
                scanSessionDao.updateStatus(sessionId, ScanSessionStatus.PAUSED.name)
                ScanSessionStatus.PAUSED
            }
            DiscoveryOutcome.Completed -> {
                finalizeSession(sessionId)
            }
        }
    }

    private suspend fun <T> executeRoomOperation(
        operationName: String,
        block: suspend () -> T
    ): T {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ScanPersistenceException) {
            throw e
        } catch (e: Exception) {
            throw ScanPersistenceException(
                "Errore Room durante $operationName: ${e.localizedMessage ?: e.javaClass.simpleName}",
                e
            )
        }
    }

    /**
     * Elabora una singola directory in streaming a piccoli blocchi.
     * Restituisce `true` se la directory è stata letta interamente, `false` se interrotta da pausa/annullamento.
     */
    private suspend fun processDirectory(
        sessionId: String,
        pending: PendingDirectoryEntity,
        exclusionPatterns: List<String>
    ): Boolean {
        val treeUri = Uri.parse(pending.treeUri)

        // 1. Verifica che il permesso SAF sia ancora attivo
        if (!safPermissionManager.hasPersistedReadPermission(treeUri)) {
            recordError(
                sessionId = sessionId,
                folderId = pending.backupFolderId,
                docId = pending.documentId,
                uri = pending.treeUri,
                errorType = "PERMISSION_REVOKED",
                message = "Autorizzazione SAF di lettura revocata per la cartella principale."
            )
            pendingDirectoryDao.deletePendingDirectoryById(pending.id)
            return true
        }

        // 2. Interroga il provider SAF in piccoli blocchi progressivi
        var roomDatabaseException: Exception? = null

        val fullCompleted = try {
            safTreeReader.queryChildDocuments(treeUri, pending.documentId, batchSize = BATCH_SIZE) { batch ->
                try {
                    processChildBatch(sessionId, pending, treeUri, batch, exclusionPatterns)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Errore Room durante il salvataggio dei batch: memorizza per propagazione ed interrompe lo streaming SAF.
                    // Eccezioni critiche JVM (Error) non vengono intercettate.
                    roomDatabaseException = e
                    false
                }
            }
        } catch (e: NullCursorException) {
            recordError(
                sessionId = sessionId,
                folderId = pending.backupFolderId,
                docId = pending.documentId,
                uri = "${pending.treeUri}/${pending.documentId}",
                errorType = "NULL_CURSOR",
                message = e.message ?: "Il provider SAF ha restituito un Cursor nullo."
            )
            pendingDirectoryDao.deletePendingDirectoryById(pending.id)
            return true
        } catch (e: SecurityException) {
            val isTreePermissionRevoked = !safPermissionManager.hasPersistedReadPermission(treeUri) ||
                pending.relativePath.isEmpty()
            val errorType = if (isTreePermissionRevoked) {
                "PERMISSION_REVOKED"
            } else {
                "DIRECTORY_READ_ERROR"
            }
            val message = if (isTreePermissionRevoked) {
                "Autorizzazione SAF negata durante la lettura: ${e.localizedMessage ?: e.javaClass.simpleName}"
            } else {
                "Impossibile accedere alla sottocartella '${pending.relativePath}': ${e.localizedMessage ?: e.javaClass.simpleName}"
            }
            recordError(
                sessionId = sessionId,
                folderId = pending.backupFolderId,
                docId = pending.documentId,
                uri = "${pending.treeUri}/${pending.documentId}",
                errorType = errorType,
                message = message
            )
            pendingDirectoryDao.deletePendingDirectoryById(pending.id)
            return true
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Rilancia immediatamente l'eccezione di cancellazione: non registra falso errore né rimuove la directory pendente
            throw e
        } catch (e: Exception) {
            // Errore SAF durante l'interrogazione del ContentProvider
            recordError(
                sessionId = sessionId,
                folderId = pending.backupFolderId,
                docId = pending.documentId,
                uri = "${pending.treeUri}/${pending.documentId}",
                errorType = "DIRECTORY_READ_ERROR",
                message = "Impossibile leggere il contenuto della directory: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
            pendingDirectoryDao.deletePendingDirectoryById(pending.id)
            return true
        }

        // Se si è verificato un errore Room durante il salvataggio del batch:
        // propaga ScanPersistenceException. La transazione Room è già stata sottoposta a rollback,
        // nessun falso errore DIRECTORY_READ_ERROR è stato registrato e la directory genitore (pending)
        // NON viene eliminata da pending_directories, consentendo la ripresa/retry.
        roomDatabaseException?.let { e ->
            throw ScanPersistenceException(
                "Errore durante la persistenza atomica del batch su Room: ${e.localizedMessage ?: e.javaClass.simpleName}",
                e
            )
        }

        // 3. Se tutti i figli sono stati letti ed elaborati con successo, rimuove la directory completata dalla coda
        if (fullCompleted) {
            pendingDirectoryDao.deletePendingDirectoryById(pending.id)
        }
        return fullCompleted
    }

    /**
     * Elabora un piccolo blocco di documenti figli e li salva immediatamente in Room.
     * Restituisce `false` se è stata richiesta una pausa o annullamento, fermando lo streaming.
     */
    private suspend fun processChildBatch(
        sessionId: String,
        pending: PendingDirectoryEntity,
        treeUri: Uri,
        batch: List<SafDocumentMetadata>,
        exclusionPatterns: List<String>
    ): Boolean {
        val documentsToInsert = mutableListOf<ScannedDocumentEntity>()
        val newDirectoriesToEnqueue = mutableListOf<PendingDirectoryEntity>()
        val batchEncounteredDirIds = HashSet<String>()

        for (child in batch) {
            val childRelativePath = if (pending.relativePath.isEmpty()) {
                child.displayName
            } else {
                "${pending.relativePath}/${child.displayName}"
            }

            val isExcluded = ExclusionMatcher.matches(
                displayName = child.displayName,
                relativePath = childRelativePath,
                patterns = exclusionPatterns
            )

            val childUri = buildDocumentUriUsingTree(treeUri, child.documentId)

            if (isExcluded) {
                // Registra come elemento escluso nell'inventario per conteggio statistico
                documentsToInsert.add(
                    ScannedDocumentEntity(
                        id = UUID.randomUUID().toString(),
                        scanSessionId = sessionId,
                        backupFolderId = pending.backupFolderId,
                        documentId = child.documentId,
                        documentUri = childUri,
                        relativePath = childRelativePath,
                        displayName = child.displayName,
                        mimeType = child.mimeType,
                        sizeBytes = child.sizeBytes,
                        lastModifiedTimestamp = child.lastModifiedTimestamp,
                        isDirectory = child.isDirectory,
                        isExcluded = true,
                        fingerprintStatus = FingerprintStatus.NOT_REQUIRED.name
                    )
                )
                // Se è una directory esclusa, non la esplora ulteriormente
                continue
            }

            // Elemento NON escluso
            if (child.isDirectory) {
                // Prevenzione dei cicli e delle directory già visitate o già in coda
                val alreadyInBatch = !batchEncounteredDirIds.add(child.documentId)
                val alreadyRecorded = alreadyInBatch || scannedDocumentDao.isDocumentRecorded(
                    sessionId = sessionId,
                    folderId = pending.backupFolderId,
                    documentId = child.documentId
                )

                if (!alreadyRecorded) {
                    documentsToInsert.add(
                        ScannedDocumentEntity(
                            id = UUID.randomUUID().toString(),
                            scanSessionId = sessionId,
                            backupFolderId = pending.backupFolderId,
                            documentId = child.documentId,
                            documentUri = childUri,
                            relativePath = childRelativePath,
                            displayName = child.displayName,
                            mimeType = child.mimeType,
                            sizeBytes = null,
                            lastModifiedTimestamp = child.lastModifiedTimestamp,
                            isDirectory = true,
                            isExcluded = false,
                            fingerprintStatus = FingerprintStatus.NOT_REQUIRED.name
                        )
                    )

                    newDirectoriesToEnqueue.add(
                        PendingDirectoryEntity(
                            id = UUID.randomUUID().toString(),
                            scanSessionId = sessionId,
                            backupFolderId = pending.backupFolderId,
                            treeUri = pending.treeUri,
                            documentId = child.documentId,
                            relativePath = childRelativePath,
                            enqueueTimestamp = System.currentTimeMillis()
                        )
                    )
                }
            } else {
                // File regolare
                documentsToInsert.add(
                    ScannedDocumentEntity(
                        id = UUID.randomUUID().toString(),
                        scanSessionId = sessionId,
                        backupFolderId = pending.backupFolderId,
                        documentId = child.documentId,
                        documentUri = childUri,
                        relativePath = childRelativePath,
                        displayName = child.displayName,
                        mimeType = child.mimeType,
                        sizeBytes = child.sizeBytes,
                        lastModifiedTimestamp = child.lastModifiedTimestamp,
                        isDirectory = false,
                        isExcluded = false,
                        fingerprintStatus = FingerprintStatus.PENDING.name
                    )
                )
            }
        }

        // Salva il blocco corrente atomicamente in una singola transazione Room
        if (documentsToInsert.isNotEmpty() || newDirectoriesToEnqueue.isNotEmpty()) {
            scanSessionDao.insertBatchAtomically(documentsToInsert, newDirectoriesToEnqueue)
        }

        // Verifica cooperativa: se è stata richiesta pausa o cancellazione, interrompe lo streaming
        return !pauseRequested && !cancelRequested
    }

    private suspend fun recordError(
        sessionId: String,
        folderId: String,
        docId: String?,
        uri: String?,
        errorType: String,
        message: String
    ) {
        val errorEntity = ScanErrorEntity(
            id = UUID.randomUUID().toString(),
            scanSessionId = sessionId,
            backupFolderId = folderId,
            documentId = docId,
            uri = uri,
            errorType = errorType,
            message = message,
            timestamp = System.currentTimeMillis()
        )
        scanErrorDao.insertError(errorEntity)
    }

    private suspend fun updateSessionStatistics(sessionId: String) {
        val totalFiles = scannedDocumentDao.countFilesForSession(sessionId)
        val totalDirs = scannedDocumentDao.countDirectoriesForSession(sessionId)
        val knownBytes = scannedDocumentDao.sumKnownBytesForSession(sessionId)
        val unknownFiles = scannedDocumentDao.countUnknownSizeFilesForSession(sessionId)
        val excluded = scannedDocumentDao.countExcludedForSession(sessionId)
        val errorCount = scanErrorDao.countErrorsForSession(sessionId)

        scanSessionDao.updateCounters(
            sessionId = sessionId,
            totalFiles = totalFiles,
            totalDirectories = totalDirs,
            knownTotalBytes = knownBytes,
            unknownSizeFiles = unknownFiles,
            excludedItems = excluded,
            errorCount = errorCount
        )
    }

    private suspend fun finalizeSession(sessionId: String): ScanSessionStatus {
        updateSessionStatistics(sessionId)
        val errorCount = scanErrorDao.countErrorsForSession(sessionId)
        val finalStatus = if (errorCount > 0) {
            ScanSessionStatus.COMPLETED_WITH_ERRORS
        } else {
            ScanSessionStatus.COMPLETED
        }

        val errorMessage = if (errorCount > 0) {
            "Scansione completata con $errorCount errori o avvisi di accesso."
        } else null

        scanSessionDao.updateStatus(
            sessionId = sessionId,
            status = finalStatus.name,
            completedTimestamp = System.currentTimeMillis(),
            errorMessage = errorMessage
        )

        // Rimuove i dati pendenti rimanenti per questa sessione
        pendingDirectoryDao.clearPendingForSession(sessionId)

        // Pulisce le sessioni obsolete conservando le ultime due sessioni completate
        cleanupOldSessions(sessionId)

        return finalStatus
    }

    /**
     * Conserva le 2 sessioni completate più recenti, tutte le sessioni RUNNING o PAUSED,
     * e la sessione corrente, eliminando in modo consistente solo i dati obsoleti.
     */
    private suspend fun cleanupOldSessions(currentSessionId: String) {
        try {
            val completedToKeep = scanSessionDao.getRecentCompletedSessionIds(2)
            val activeToKeep = scanSessionDao.getActiveSessionIds()
            val keepIds = (completedToKeep + activeToKeep + currentSessionId).distinct()

            if (keepIds.isNotEmpty()) {
                scannedDocumentDao.deleteDocumentsNotInSessions(keepIds)
                pendingDirectoryDao.deletePendingNotInSessions(keepIds)
                scanErrorDao.deleteErrorsNotInSessions(keepIds)
                scanSessionDao.deleteSessionsExcept(keepIds)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Pulizia vecchie sessioni non riuscita: ${e.localizedMessage}")
        }
    }

    private fun extractTreeDocumentId(treeUri: Uri): String {
        return try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (t: Throwable) {
            val segments = treeUri.pathSegments
            if (segments != null && segments.size >= 2 && segments[0] == "tree") {
                segments[1]
            } else {
                treeUri.lastPathSegment?.substringAfterLast(':') ?: "root"
            }
        }
    }

    private fun buildDocumentUriUsingTree(treeUri: Uri, documentId: String): String {
        return try {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).toString()
        } catch (t: Throwable) {
            treeUri.buildUpon().appendPath("document").appendPath(documentId).build().toString()
        }
    }

    companion object {
        private const val TAG = "LocalFolderScanner"
        const val BATCH_SIZE = 100
    }
}
