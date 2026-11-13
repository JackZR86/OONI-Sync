package com.androidbackup.app.data.repository

import android.net.Uri
import android.util.Log
import com.androidbackup.app.data.local.dao.*
import com.androidbackup.app.data.local.entity.*
import com.androidbackup.app.data.saf.SafPermissionManager
import com.androidbackup.app.data.scan.DiscoveryOutcome
import com.androidbackup.app.data.scan.LocalFileFingerprinter
import com.androidbackup.app.data.scan.LocalFolderScanner
import com.androidbackup.app.data.scan.ScanPersistenceException
import com.androidbackup.app.data.scan.Sha256StreamHasher
import com.androidbackup.app.data.scan.SingleFileFingerprintRequest
import com.androidbackup.app.data.scan.SingleFileFingerprintResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Azione di arresto richiesta e potenzialmente in attesa dello sblocco dello stream SAF.
 */
enum class PendingStopAction {
    PAUSE,
    CANCEL
}

/**
 * Errore controllato restituito da [ScanRepository] quando una richiesta di pausa/annullamento
 * o un nuovo avvio incontra un job ancora in esecuzione oltre il timeout di arresto SAF.
 */
class ScanStopPendingException(
    val requestedAction: PendingStopAction,
    message: String
) : IllegalStateException(message)

/**
 * Repository orchestratore della pipeline locale completa Discovery -> Fingerprinting (Fase 1B.2 - 1B.3C).
 *
 * Responsabilità principali:
 * - Avvio coordinato con [FolderLifecycleCoordinator] per evitare corse con la rimozione cartelle.
 * - Esecuzione sequenziale `DISCOVERY` -> `FINGERPRINTING` -> `COMPLETED` con stato persistito su Room.
 * - Elaborazione chunked dei soli file inclusi `PENDING` con buffer riutilizzabile da 64 KB.
 * - Pausa, ripresa e annullamento cooperativi con attesa rigorosamente limitata su timeout SAF
 *   e completamento differito allo sblocco effettivo dello stream.
 * - Ripresa fase-consapevole dopo pausa, errore di persistenza (`ScanPersistenceException`) o crash/process death.
 * - Finalizzazione atomica e pulizia della cache stantia (`deleteStaleForFolderNotInSession`) nella medesima transazione Room,
 *   seguita dalla pulizia storica delle vecchie sessioni dopo il commit.
 */
class ScanRepository(
    private val scanSessionDao: ScanSessionDao,
    private val scannedDocumentDao: ScannedDocumentDao,
    private val pendingDirectoryDao: PendingDirectoryDao,
    private val scanErrorDao: ScanErrorDao,
    private val fileFingerprintDao: FileFingerprintDao,
    private val folderDao: FolderDao,
    private val scanner: LocalFolderScanner,
    private val fingerprinter: LocalFileFingerprinter,
    private val safPermissionManager: SafPermissionManager,
    private val settingsRepository: SettingsRepository,
    private val lifecycleCoordinator: FolderLifecycleCoordinator,
    private val runInTransaction: suspend (suspend () -> Unit) -> Unit,
    private val stopTimeoutMs: Long = DEFAULT_STOP_TIMEOUT_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val scanMutex = Mutex()
    private var scanJob: Job? = null
    private val repositoryScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    @Volatile
    private var pauseRequested = false

    @Volatile
    private var cancelRequested = false

    private val pendingStopAction = AtomicReference<PendingStopAction?>(null)

    /**
     * Flusso reattivo dell'ultima sessione di scansione (in corso, in pausa o terminata).
     */
    val latestSessionFlow: Flow<ScanSessionEntity?> = scanSessionDao.observeLatestSession()

    /**
     * Flusso reattivo dell'ultima sessione completata con successo.
     * Rimane disponibile e stabile anche quando viene avviata una nuova scansione.
     */
    val latestCompletedSessionFlow: Flow<ScanSessionEntity?> = scanSessionDao.observeLatestCompletedSession()

    private fun resetPipelineControlFlags() {
        pauseRequested = false
        cancelRequested = false
        scanner.resetControlFlags()
    }

    private fun resolveStopAction(): PendingStopAction? {
        return pendingStopAction.get() ?: when {
            cancelRequested -> PendingStopAction.CANCEL
            pauseRequested -> PendingStopAction.PAUSE
            else -> null
        }
    }

    private fun isPauseRequestedCooperatively(): Boolean = pauseRequested

    private fun isCancelRequestedCooperatively(): Boolean = cancelRequested

    private suspend fun checkCancelledCooperatively() {
        currentCoroutineContext().ensureActive()
        if (cancelRequested) {
            throw CancellationException("Pipeline annullata cooperativamente.")
        }
    }

    /**
     * Controlla all'avvio se è presente una sessione rimasta in stato RUNNING a causa della
     * terminazione improvvisa dell'applicazione. Se presente e non esiste alcun job attivo
     * nell'istanza corrente, la porta in stato PAUSED preservando `scan_phase`, modalità e coda.
     */
    suspend fun checkAndRecoverInterruptedSession(): ScanSessionEntity? = withContext(ioDispatcher) {
        scanMutex.withLock {
            val hasUnfinishedJob = scanJob?.isCompleted == false
            val runningOrPaused = scanSessionDao.getRunningOrPausedSession()
            if (!hasUnfinishedJob && runningOrPaused != null && runningOrPaused.status == ScanSessionStatus.RUNNING.name) {
                scanSessionDao.updateStatus(
                    sessionId = runningOrPaused.id,
                    status = ScanSessionStatus.PAUSED.name,
                    errorMessage = "Scansione interrotta in precedenza. Pronta per la ripresa dalla coda persistente."
                )
                return@withContext scanSessionDao.getSessionById(runningOrPaused.id)
            }
            runningOrPaused
        }
    }

    /**
     * Avvia una nuova pipeline completa Discovery + Fingerprinting delle cartelle configurate accessibili.
     *
     * Non annulla mai implicitamente una scansione in corso o in pausa e non consente due job concorrenti.
     */
    suspend fun startNewScan(
        mode: FingerprintMode = FingerprintMode.INCREMENTAL,
        maxCacheAgeMs: Long? = null
    ): Result<ScanSessionEntity> = withContext(ioDispatcher) {
        scanMutex.withLock {
            runCatching {
                // 1. Verifica che non esista alcun job non ancora effettivamente terminato
                if (scanJob?.isCompleted == false) {
                    val pendingStop = pendingStopAction.get()
                    if (pendingStop != null) {
                        throw ScanStopPendingException(
                            requestedAction = pendingStop,
                            message = "Impossibile avviare una nuova scansione: è in attesa la terminazione del job precedente ($pendingStop)."
                        )
                    }
                    throw IllegalStateException(
                        "È già presente una scansione attiva. Mettere in pausa o annullare esplicitamente la sessione corrente prima di avviarne una nuova."
                    )
                }
                scanJob = null
                pendingStopAction.set(null)

                // 2. Verifica che non esistano sessioni RUNNING o PAUSED nel database
                val activeSession = scanSessionDao.getRunningOrPausedSession()
                if (activeSession != null) {
                    if (activeSession.status == ScanSessionStatus.RUNNING.name) {
                        // Sessione rimasta RUNNING senza job attivo nell'istanza corrente: recupera in PAUSED
                        scanSessionDao.updateStatus(
                            sessionId = activeSession.id,
                            status = ScanSessionStatus.PAUSED.name,
                            errorMessage = "Scansione interrotta in precedenza. Riprendere o annullare esplicitamente la sessione prima di avviarne una nuova."
                        )
                        throw IllegalStateException(
                            "È presente una sessione precedente interrotta (id: ${activeSession.id}) ora recuperata in stato PAUSED. " +
                                "Riprendere o annullare esplicitamente la sessione prima di avviarne una nuova."
                        )
                    }
                    if (activeSession.status == ScanSessionStatus.PAUSED.name) {
                        throw IllegalStateException(
                            "È presente una sessione di scansione in pausa (id: ${activeSession.id}). " +
                                "Riprendere o annullare esplicitamente la sessione precedente prima di avviarne una nuova."
                        )
                    }
                }

                // 3. Sotto coordinatore condiviso: verifica cartelle accessibili e crea atomicamente la sessione
                val newSession = lifecycleCoordinator.withLock {
                    val accessibleFolders = folderDao.getFolders()
                        .map { entity ->
                            val uri = Uri.parse(entity.treeUri)
                            val hasPermission = safPermissionManager.hasPersistedReadPermission(uri)
                            entity.toDomainModel(isAccessible = hasPermission)
                        }
                        .filter { it.isAccessible }

                    if (accessibleFolders.isEmpty()) {
                        throw IllegalStateException("Nessuna cartella configurata o accessibile per la scansione.")
                    }

                    resetPipelineControlFlags()
                    scanner.createNewSession(
                        folders = accessibleFolders,
                        fingerprintMode = mode,
                        fingerprintMaxCacheAgeMs = maxCacheAgeMs
                    )
                }

                // 4. Recupera le regole di esclusione correnti
                val exclusions = settingsRepository.getCurrentSettings().exclusions

                // 5. Lancia la pipeline in background
                launchScanJob(newSession.id, exclusions)

                newSession
            }
        }
    }

    /**
     * Mette in pausa la scansione attualmente in corso con attesa rigorosamente limitata a [stopTimeoutMs].
     * Non dichiara lo stato PAUSED finché il job non ha effettivamente terminato l'eventuale lettura SAF in corso.
     */
    suspend fun pauseScan(): Result<Unit> = withContext(ioDispatcher) {
        scanMutex.withLock {
            runCatching {
                stopCurrentJobWithBoundedTimeout(PendingStopAction.PAUSE)

                val active = scanSessionDao.getRunningOrPausedSession()
                if (active != null && active.status == ScanSessionStatus.RUNNING.name) {
                    refreshStatisticsBestEffort(active.id)
                    scanSessionDao.updateStatus(active.id, ScanSessionStatus.PAUSED.name)
                }
                Unit
            }
        }
    }

    /**
     * Riprende una scansione in stato PAUSED dalla fase persistita (`DISCOVERY` o `FINGERPRINTING`).
     * Rifiuta la ripresa finché un eventuale job precedente non è completamente terminato (`isCompleted == true`).
     */
    suspend fun resumeScan(): Result<Unit> = withContext(ioDispatcher) {
        scanMutex.withLock {
            runCatching {
                if (scanJob?.isCompleted == false) {
                    val pendingStop = pendingStopAction.get()
                    if (pendingStop != null) {
                        throw ScanStopPendingException(
                            requestedAction = pendingStop,
                            message = "Impossibile riprendere: è ancora in corso l'arresto del job precedente ($pendingStop)."
                        )
                    }
                    throw IllegalStateException("Una scansione è già in esecuzione.")
                }
                scanJob = null
                pendingStopAction.set(null)

                val paused = scanSessionDao.getRunningOrPausedSession()
                    ?: throw IllegalStateException("Nessuna scansione in pausa da riprendere.")

                if (paused.status != ScanSessionStatus.PAUSED.name) {
                    throw IllegalStateException("La sessione corrente (${paused.status}) non è in stato PAUSED.")
                }

                resetPipelineControlFlags()

                val exclusions = settingsRepository.getCurrentSettings().exclusions
                launchScanJob(paused.id, exclusions)
                Unit
            }
        }
    }

    /**
     * Annulla la scansione in corso o in pausa con attesa rigorosamente limitata a [stopTimeoutMs].
     * Non dichiara lo stato CANCELLED né svuota la coda pendente finché il job è ancora in esecuzione.
     */
    suspend fun cancelScan(): Result<Unit> = withContext(ioDispatcher) {
        scanMutex.withLock {
            runCatching {
                stopCurrentJobWithBoundedTimeout(PendingStopAction.CANCEL)

                val active = scanSessionDao.getRunningOrPausedSession()
                if (active != null) {
                    scanSessionDao.updateStatus(active.id, ScanSessionStatus.CANCELLED.name)
                    pendingDirectoryDao.clearPendingForSession(active.id)
                }

                resetPipelineControlFlags()
                Unit
            }
        }
    }

    /**
     * Attesa limitata per la terminazione cooperativa di [scanJob].
     * Se il timeout scade mentre il provider SAF è ancora bloccato sulla lettura dello stream,
     * NON esegue una `join()` illimitata, NON azzera `scanJob` e solleva [ScanStopPendingException].
     */
    private suspend fun stopCurrentJobWithBoundedTimeout(requestedAction: PendingStopAction) {
        val currentJob = scanJob
        if (currentJob == null || currentJob.isCompleted) {
            scanJob = null
            pendingStopAction.set(null)
            return
        }

        val effectiveAction = if (pendingStopAction.get() == PendingStopAction.CANCEL) {
            PendingStopAction.CANCEL
        } else {
            requestedAction
        }

        when (effectiveAction) {
            PendingStopAction.PAUSE -> {
                pendingStopAction.compareAndSet(null, PendingStopAction.PAUSE)
                pauseRequested = true
                scanner.requestPause()
            }
            PendingStopAction.CANCEL -> {
                pendingStopAction.set(PendingStopAction.CANCEL)
                cancelRequested = true
                pauseRequested = false
                scanner.requestCancel()
            }
        }

        fingerprinter.abortActiveStream()

        val completedWithinTimeout = withTimeoutOrNull(stopTimeoutMs) {
            currentJob.join()
        } != null

        if (!completedWithinTimeout || !currentJob.isCompleted) {
            if (effectiveAction == PendingStopAction.CANCEL) {
                currentJob.cancel(CancellationException("Richiesta annullamento scansione oltre timeout SAF"))
            }
            throw ScanStopPendingException(
                requestedAction = effectiveAction,
                message = "Richiesta di ${if (effectiveAction == PendingStopAction.PAUSE) "pausa" else "annullamento"} " +
                    "in attesa: lo stream SAF non si è sbloccato entro ${stopTimeoutMs}ms."
            )
        }

        scanJob = null
        pendingStopAction.set(null)
        resetPipelineControlFlags()
    }

    private fun launchScanJob(sessionId: String, exclusions: List<String>) {
        check(scanJob == null || scanJob!!.isCompleted) {
            "Impossibile avviare una scansione: un job precedente non è ancora terminato."
        }
        scanJob = repositoryScope.launch {
            try {
                runPipeline(sessionId, exclusions)
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    completeDeferredStopIfRequested(sessionId)
                }
            } catch (e: ScanPersistenceException) {
                withContext(NonCancellable) {
                    if (resolveStopAction() == PendingStopAction.CANCEL) {
                        markSessionCancelledSafely(sessionId)
                    } else {
                        refreshStatisticsBestEffort(sessionId)
                        scanSessionDao.updateStatus(
                            sessionId = sessionId,
                            status = ScanSessionStatus.PAUSED.name,
                            errorMessage = "Scansione sospesa a causa di un errore temporaneo di persistenza del database. È possibile riprendere la scansione. Dettaglio: ${e.message}"
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(NonCancellable) {
                    when (resolveStopAction()) {
                        PendingStopAction.CANCEL -> markSessionCancelledSafely(sessionId)
                        PendingStopAction.PAUSE -> {
                            refreshStatisticsBestEffort(sessionId)
                            markSessionPausedSafely(sessionId)
                        }
                        null -> {
                            scanSessionDao.updateStatus(
                                sessionId = sessionId,
                                status = ScanSessionStatus.FAILED.name,
                                errorMessage = "Errore imprevisto durante la scansione: ${e.localizedMessage ?: e.javaClass.simpleName}"
                            )
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    completeDeferredStopIfRequested(sessionId)
                    resetPipelineControlFlags()
                    pendingStopAction.set(null)
                }
            }
        }
    }

    private suspend fun runPipeline(sessionId: String, exclusions: List<String>) {
        val session = executeRoomRead("lettura sessione '$sessionId'") {
            scanSessionDao.getSessionById(sessionId)
        } ?: return

        val phase = session.parsedScanPhase
        val mode = session.parsedFingerprintMode
        val maxCacheAgeMs = session.fingerprintMaxCacheAgeMs

        executeRoomWrite("ripristino stato RUNNING") {
            scanSessionDao.updateStatus(sessionId, ScanSessionStatus.RUNNING.name)
        }

        // 1. Fase DISCOVERY (eseguita solo se la sessione è ancora in fase DISCOVERY)
        if (phase == ScanPhase.DISCOVERY) {
            checkCancelledCooperatively()
            if (isPauseRequestedCooperatively()) {
                refreshStatisticsBestEffort(sessionId)
                markSessionPausedSafely(sessionId)
                return
            }

            when (scanner.runDiscovery(sessionId, exclusions)) {
                DiscoveryOutcome.Cancelled -> {
                    markSessionCancelledSafely(sessionId)
                    return
                }
                DiscoveryOutcome.Paused -> {
                    refreshStatisticsBestEffort(sessionId)
                    markSessionPausedSafely(sessionId)
                    return
                }
                DiscoveryOutcome.Completed -> {
                    // Discovery completata: verifica eventuali richieste di pausa/annullamento prima di avanzare fase
                    checkCancelledCooperatively()
                    if (isPauseRequestedCooperatively()) {
                        refreshStatisticsBestEffort(sessionId)
                        markSessionPausedSafely(sessionId)
                        return
                    }

                    executeRoomWrite("transizione fase FINGERPRINTING") {
                        scanSessionDao.updatePhase(sessionId, ScanPhase.FINGERPRINTING.name)
                    }
                    refreshFingerprintCounters(sessionId)
                }
            }
        }

        // 2. Fase FINGERPRINTING (se in DISCOVERY appena completata oppure ripresa in FINGERPRINTING)
        if (phase == ScanPhase.DISCOVERY || phase == ScanPhase.FINGERPRINTING) {
            val fingerprintingCompleted = runFingerprintingPhase(sessionId, mode, maxCacheAgeMs)
            if (!fingerprintingCompleted) {
                if (resolveStopAction() == PendingStopAction.CANCEL) {
                    markSessionCancelledSafely(sessionId)
                } else {
                    markSessionPausedSafely(sessionId)
                }
                return
            }
        }

        // 3. Controllo cooperativo finale prima della finalizzazione atomica
        checkCancelledCooperatively()
        if (isPauseRequestedCooperatively()) {
            refreshStatisticsBestEffort(sessionId)
            markSessionPausedSafely(sessionId)
            return
        }

        // 4. Finalizzazione completa e pulizia cache stantia nella stessa transazione Room
        finalizeSessionAtomically(sessionId)

        // 5. Pulizia storica delle vecchie sessioni eseguita SOLO dopo il commit della finalizzazione
        cleanupOldSessionsSafely(sessionId)
    }

    private suspend fun runFingerprintingPhase(
        sessionId: String,
        mode: FingerprintMode,
        maxCacheAgeMs: Long?
    ): Boolean {
        val effectiveMaxCacheAgeMs = maxCacheAgeMs ?: LocalFileFingerprinter.DEFAULT_MAX_CACHE_AGE_MS
        val reusableBuffer = ByteArray(Sha256StreamHasher.BUFFER_SIZE_BYTES)
        val folderTreeUriCache = mutableMapOf<String, Uri?>()
        var processedSinceStatsUpdate = 0

        while (true) {
            checkCancelledCooperatively()
            if (isPauseRequestedCooperatively()) {
                refreshFingerprintCounters(sessionId)
                return false
            }

            val batch = executeRoomRead("lettura batch PENDING fingerprinting") {
                scannedDocumentDao.getNextPendingFingerprintBatch(sessionId, FINGERPRINT_BATCH_SIZE)
            }
            if (batch.isEmpty()) {
                refreshFingerprintCounters(sessionId)
                return true
            }

            for (doc in batch) {
                checkCancelledCooperatively()
                if (isPauseRequestedCooperatively()) {
                    refreshFingerprintCounters(sessionId)
                    return false
                }

                val folderTreeUri = resolveFolderTreeUri(doc.backupFolderId, folderTreeUriCache)
                if (folderTreeUri == null) {
                    persistMissingFolderFailure(doc)
                    processedSinceStatsUpdate++
                    if (processedSinceStatsUpdate >= STATS_UPDATE_INTERVAL) {
                        refreshFingerprintCounters(sessionId)
                        processedSinceStatsUpdate = 0
                    }
                    continue
                }

                val request = SingleFileFingerprintRequest(
                    document = doc,
                    folderTreeUri = folderTreeUri,
                    mode = mode,
                    maxCacheAgeMs = effectiveMaxCacheAgeMs,
                    reusableBuffer = reusableBuffer
                )

                val outcome = fingerprinter.fingerprintFile(
                    request = request,
                    isPauseRequested = { isPauseRequestedCooperatively() },
                    isCancelRequested = { isCancelRequestedCooperatively() }
                )

                when (outcome) {
                    is SingleFileFingerprintResult.Paused -> {
                        refreshFingerprintCounters(sessionId)
                        return false
                    }
                    is SingleFileFingerprintResult.Computed,
                    is SingleFileFingerprintResult.ReusedCache,
                    is SingleFileFingerprintResult.Unsupported,
                    is SingleFileFingerprintResult.Failed,
                    is SingleFileFingerprintResult.NotRequired -> {
                        processedSinceStatsUpdate++
                        if (processedSinceStatsUpdate >= STATS_UPDATE_INTERVAL) {
                            refreshFingerprintCounters(sessionId)
                            processedSinceStatsUpdate = 0
                        }
                    }
                }
            }

            if (processedSinceStatsUpdate > 0) {
                refreshFingerprintCounters(sessionId)
                processedSinceStatsUpdate = 0
            }
        }
    }

    private suspend fun resolveFolderTreeUri(
        folderId: String,
        cache: MutableMap<String, Uri?>
    ): Uri? {
        if (cache.containsKey(folderId)) {
            return cache[folderId]
        }
        val entity = executeRoomRead("risoluzione cartella '$folderId'") {
            folderDao.getFolderById(folderId)
        }
        val uri = entity?.let { Uri.parse(it.treeUri) }
        cache[folderId] = uri
        return uri
    }

    private suspend fun persistMissingFolderFailure(doc: ScannedDocumentEntity) {
        val now = System.currentTimeMillis()
        val msg = "Cartella di backup '${doc.backupFolderId}' non più presente nella configurazione durante il fingerprinting."
        executeRoomTransaction("registrazione errore cartella mancante") {
            val updatedRows = scannedDocumentDao.updateFingerprintOutcome(
                rowId = doc.id,
                mimeType = doc.mimeType,
                sizeBytes = doc.sizeBytes,
                lastModifiedTimestamp = doc.lastModifiedTimestamp,
                sha256Hex = null,
                fingerprintStatus = FingerprintStatus.FAILED.name,
                fingerprintError = msg,
                fingerprintTimestamp = now,
                hashedBytesRead = null
            )
            if (updatedRows != 1) {
                throw IllegalStateException(
                    "Aggiornamento scanned_documents atteso per 1 riga (id='${doc.id}'), aggiornate=$updatedRows."
                )
            }
            scanErrorDao.insertError(
                ScanErrorEntity(
                    id = UUID.randomUUID().toString(),
                    scanSessionId = doc.scanSessionId,
                    backupFolderId = doc.backupFolderId,
                    documentId = doc.documentId,
                    uri = doc.documentUri,
                    errorType = ERROR_FOLDER_NOT_FOUND,
                    message = msg,
                    timestamp = now,
                    errorPhase = ScanErrorPhase.FINGERPRINTING.name,
                    severity = ScanErrorSeverity.ERROR.name
                )
            )
        }
    }

    private suspend fun refreshFingerprintCounters(sessionId: String) {
        executeRoomWrite("aggiornamento contatori fingerprinting") {
            val totalFiles = scannedDocumentDao.countFilesForSession(sessionId)
            val computed = scannedDocumentDao.countFingerprintsByStatus(sessionId, FingerprintStatus.COMPUTED.name)
            val cached = scannedDocumentDao.countFingerprintsByStatus(sessionId, FingerprintStatus.REUSED_CACHE.name)
            val unsupported = scannedDocumentDao.countFingerprintsByStatus(sessionId, FingerprintStatus.UNSUPPORTED.name)
            val failed = scannedDocumentDao.countFingerprintsByStatus(sessionId, FingerprintStatus.FAILED.name)
            val bytesHashed = scannedDocumentDao.sumHashedBytesComputedForSession(sessionId)
            val errorCount = scanErrorDao.countErrorsForSession(sessionId)

            scanSessionDao.updateFingerprintCounters(
                sessionId = sessionId,
                fingerprintsTotal = totalFiles,
                fingerprintsComputed = computed,
                fingerprintsCached = cached,
                fingerprintsUnsupported = unsupported,
                fingerprintsFailed = failed,
                fingerprintsBytesHashed = bytesHashed,
                errorCount = errorCount
            )
        }
    }

    private suspend fun refreshStatisticsBestEffort(sessionId: String) {
        try {
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

            val computed = scannedDocumentDao.countFingerprintsByStatus(sessionId, FingerprintStatus.COMPUTED.name)
            val cached = scannedDocumentDao.countFingerprintsByStatus(sessionId, FingerprintStatus.REUSED_CACHE.name)
            val unsupported = scannedDocumentDao.countFingerprintsByStatus(sessionId, FingerprintStatus.UNSUPPORTED.name)
            val failed = scannedDocumentDao.countFingerprintsByStatus(sessionId, FingerprintStatus.FAILED.name)
            val bytesHashed = scannedDocumentDao.sumHashedBytesComputedForSession(sessionId)

            scanSessionDao.updateFingerprintCounters(
                sessionId = sessionId,
                fingerprintsTotal = totalFiles,
                fingerprintsComputed = computed,
                fingerprintsCached = cached,
                fingerprintsUnsupported = unsupported,
                fingerprintsFailed = failed,
                fingerprintsBytesHashed = bytesHashed,
                errorCount = errorCount
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Aggiornamento best-effort statistiche non riuscito per $sessionId: ${e.localizedMessage}")
        }
    }

    private suspend fun finalizeSessionAtomically(sessionId: String) {
        executeRoomTransaction("finalizzazione sessione e pulizia cache stantia") {
            val totalFiles = scannedDocumentDao.countFilesForSession(sessionId)
            val totalDirs = scannedDocumentDao.countDirectoriesForSession(sessionId)
            val knownBytes = scannedDocumentDao.sumKnownBytesForSession(sessionId)
            val unknownFiles = scannedDocumentDao.countUnknownSizeFilesForSession(sessionId)
            val excluded = scannedDocumentDao.countExcludedForSession(sessionId)
            val errorCount = scanErrorDao.countErrorsForSession(sessionId)

            val fpComputed = scannedDocumentDao.countFingerprintsByStatus(sessionId, FingerprintStatus.COMPUTED.name)
            val fpCached = scannedDocumentDao.countFingerprintsByStatus(sessionId, FingerprintStatus.REUSED_CACHE.name)
            val fpUnsupported = scannedDocumentDao.countFingerprintsByStatus(sessionId, FingerprintStatus.UNSUPPORTED.name)
            val fpFailed = scannedDocumentDao.countFingerprintsByStatus(sessionId, FingerprintStatus.FAILED.name)
            val fpBytesHashed = scannedDocumentDao.sumHashedBytesComputedForSession(sessionId)

            val finalStatus = if (errorCount > 0) {
                ScanSessionStatus.COMPLETED_WITH_ERRORS
            } else {
                ScanSessionStatus.COMPLETED
            }
            val errorMessage = if (errorCount > 0) {
                "Scansione completata con $errorCount errori o avvisi di accesso."
            } else {
                null
            }

            scanSessionDao.finalizeSessionState(
                sessionId = sessionId,
                scanPhase = ScanPhase.COMPLETED.name,
                status = finalStatus.name,
                completedTimestamp = System.currentTimeMillis(),
                errorMessage = errorMessage,
                totalFiles = totalFiles,
                totalDirectories = totalDirs,
                knownTotalBytes = knownBytes,
                unknownSizeFiles = unknownFiles,
                excludedItems = excluded,
                errorCount = errorCount,
                fingerprintsTotal = totalFiles,
                fingerprintsComputed = fpComputed,
                fingerprintsCached = fpCached,
                fingerprintsUnsupported = fpUnsupported,
                fingerprintsFailed = fpFailed,
                fingerprintsBytesHashed = fpBytesHashed
            )

            pendingDirectoryDao.clearPendingForSession(sessionId)

            val folderIds = scannedDocumentDao.getDistinctFolderIdsForSession(sessionId)
            for (folderId in folderIds) {
                fileFingerprintDao.deleteStaleForFolderNotInSession(folderId, sessionId)
            }
        }
    }

    private suspend fun cleanupOldSessionsSafely(currentSessionId: String) {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Pulizia storica delle vecchie sessioni non riuscita (sessione corrente già COMPLETED): ${e.localizedMessage}")
        }
    }

    private suspend fun completeDeferredStopIfRequested(sessionId: String) {
        val action = resolveStopAction() ?: return
        try {
            val current = scanSessionDao.getSessionById(sessionId) ?: return
            if (current.status != ScanSessionStatus.RUNNING.name && current.status != ScanSessionStatus.PAUSED.name) {
                return
            }
            when (action) {
                PendingStopAction.CANCEL -> markSessionCancelledSafely(sessionId)
                PendingStopAction.PAUSE -> {
                    refreshStatisticsBestEffort(sessionId)
                    markSessionPausedSafely(sessionId)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Completamento differito dello stato di arresto non riuscito per $sessionId: ${e.localizedMessage}")
        }
    }

    private suspend fun markSessionPausedSafely(sessionId: String) {
        val current = scanSessionDao.getSessionById(sessionId) ?: return
        if (current.status == ScanSessionStatus.RUNNING.name) {
            scanSessionDao.updateStatus(sessionId, ScanSessionStatus.PAUSED.name)
        }
    }

    private suspend fun markSessionCancelledSafely(sessionId: String) {
        val current = scanSessionDao.getSessionById(sessionId) ?: return
        if (current.status == ScanSessionStatus.RUNNING.name || current.status == ScanSessionStatus.PAUSED.name) {
            scanSessionDao.updateStatus(sessionId, ScanSessionStatus.CANCELLED.name)
            pendingDirectoryDao.clearPendingForSession(sessionId)
        }
    }

    private suspend fun <T> executeRoomRead(
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

    private suspend fun <T> executeRoomWrite(
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

    private suspend fun executeRoomTransaction(
        operationName: String,
        block: suspend () -> Unit
    ) {
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
                "Errore Room nella transazione ($operationName): ${e.localizedMessage ?: e.javaClass.simpleName}",
                e
            )
        }
    }

    /**
     * Supporto deterministico per i test: attende l'eventuale completamento dell'ultimo job lanciato.
     */
    internal suspend fun awaitActiveJobCompletion() {
        scanJob?.join()
    }

    /**
     * Restituisce le statistiche dell'inventario per una specifica cartella, ricavate
     * dall'ultima sessione completata oppure dall'ultima sessione attiva.
     */
    suspend fun getFolderStats(folderId: String): FolderInventoryStats = withContext(ioDispatcher) {
        val targetSession = scanSessionDao.getLatestCompletedSession()
            ?: scanSessionDao.getLatestSession()

        if (targetSession == null) {
            return@withContext FolderInventoryStats(
                folderId = folderId,
                fileCount = 0,
                directoryCount = 0,
                totalKnownBytes = 0L,
                unknownSizeCount = 0,
                excludedCount = 0
            )
        }

        val fileCount = scannedDocumentDao.countFilesForFolder(targetSession.id, folderId)
        val dirCount = scannedDocumentDao.countDirectoriesForFolder(targetSession.id, folderId)
        val knownBytes = scannedDocumentDao.sumKnownBytesForFolder(targetSession.id, folderId)
        val unknownCount = scannedDocumentDao.countUnknownSizeFilesForFolder(targetSession.id, folderId)
        val excludedCount = scannedDocumentDao.countExcludedForFolder(targetSession.id, folderId)

        FolderInventoryStats(
            folderId = folderId,
            fileCount = fileCount,
            directoryCount = dirCount,
            totalKnownBytes = knownBytes,
            unknownSizeCount = unknownCount,
            excludedCount = excludedCount
        )
    }

    /**
     * Restituisce la lista dei documenti scansionati per una data sessione.
     */
    suspend fun getDocumentsForSession(sessionId: String): List<ScannedDocumentEntity> = withContext(ioDispatcher) {
        scannedDocumentDao.getDocumentsForSession(sessionId)
    }

    /**
     * Restituisce l'elenco degli errori registrati per una sessione.
     */
    suspend fun getErrorsForSession(sessionId: String): List<ScanErrorEntity> = withContext(ioDispatcher) {
        scanErrorDao.getErrorsForSession(sessionId)
    }

    companion object {
        private const val TAG = "ScanRepository"
        const val DEFAULT_STOP_TIMEOUT_MS = 10_000L
        const val FINGERPRINT_BATCH_SIZE = 50
        const val STATS_UPDATE_INTERVAL = 10
        const val ERROR_FOLDER_NOT_FOUND = "FOLDER_NOT_FOUND"
    }
}
