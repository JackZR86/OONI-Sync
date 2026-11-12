package com.androidbackup.app.data.repository

import com.androidbackup.app.data.local.dao.*
import com.androidbackup.app.data.local.entity.ScanSessionEntity
import com.androidbackup.app.data.local.entity.ScanSessionStatus
import com.androidbackup.app.data.local.entity.ScannedDocumentEntity
import com.androidbackup.app.data.local.entity.ScanErrorEntity
import com.androidbackup.app.data.scan.LocalFolderScanner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Repository per la gestione dell'inventario dei file locali e del ciclo di vita delle scansioni (Fase 1B.2).
 *
 * Conforme alla Specifica Fase 1B.2:
 * - Separa rigorosamente l'accesso al database e al motore di scansione dal ViewModel e dalla UI.
 * - Coordina avvio, pausa, ripresa e annullamento delle scansioni.
 * - Riconosce sessioni interrotte (process death) e ne consente la ripresa dalla coda persistente.
 * - Conserva l'ultimo inventario completato anche mentre una nuova scansione è in corso.
 */
class ScanRepository(
    private val scanSessionDao: ScanSessionDao,
    private val scannedDocumentDao: ScannedDocumentDao,
    private val pendingDirectoryDao: PendingDirectoryDao,
    private val scanErrorDao: ScanErrorDao,
    private val scanner: LocalFolderScanner,
    private val folderRepository: FolderRepository,
    private val settingsRepository: SettingsRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val scanMutex = Mutex()
    private var scanJob: Job? = null
    private val repositoryScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /**
     * Flusso reattivo dell'ultima sessione di scansione (in corso, in pausa o terminata).
     */
    val latestSessionFlow: Flow<ScanSessionEntity?> = scanSessionDao.observeLatestSession()

    /**
     * Flusso reattivo dell'ultima sessione completata con successo.
     * Rimane disponibile e stabile anche quando viene avviata una nuova scansione.
     */
    val latestCompletedSessionFlow: Flow<ScanSessionEntity?> = scanSessionDao.observeLatestCompletedSession()

    /**
     * Controlla all'avvio se è presente una sessione rimasta in stato RUNNING a causa della
     * terminazione improvvisa dell'applicazione. Se presente, la porta in stato PAUSED
     * affinché l'utente possa visualizzarla e riprenderla esplicitamente dalla coda persistente.
     */
    suspend fun checkAndRecoverInterruptedSession(): ScanSessionEntity? = withContext(ioDispatcher) {
        scanMutex.withLock {
            val runningOrPaused = scanSessionDao.getRunningOrPausedSession()
            if (runningOrPaused != null && runningOrPaused.status == ScanSessionStatus.RUNNING.name) {
                // Imposta come PAUSED per consentire la ripresa
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
     * Termina in modo controllato e cooperativo l'eventuale job di scansione in corso,
     * attendendone la conclusione effettiva prima di rilasciare il controllo.
     */
    private suspend fun stopCurrentJobControlled(markAsCancelled: Boolean = false) {
        val currentJob = scanJob
        if (currentJob != null && currentJob.isActive) {
            scanner.requestCancel()
            // Attesa cooperativa della terminazione naturale del job
            val completedCooperatively = withTimeoutOrNull(10000L) {
                currentJob.join()
            } != null

            if (!completedCooperatively) {
                // In caso di mancata risposta cooperativa entro il timeout, interrompe forzatamente
                currentJob.cancel(CancellationException("Timeout terminazione cooperativa scansione"))
                currentJob.join()
            }
        }
        scanJob = null

        if (markAsCancelled) {
            val active = scanSessionDao.getRunningOrPausedSession()
            if (active != null) {
                scanSessionDao.updateStatus(active.id, ScanSessionStatus.CANCELLED.name)
                pendingDirectoryDao.clearPendingForSession(active.id)
            }
        }
        scanner.resetControlFlags()
    }

    /**
     * Avvia una nuova scansione completa delle cartelle attualmente configurate ed accessibili.
     */
    suspend fun startNewScan(): Result<ScanSessionEntity> = withContext(ioDispatcher) {
        scanMutex.withLock {
            runCatching {
                // 1. Se esiste un job in corso, completa una procedura di annullamento controllata
                if (scanJob != null && scanJob!!.isActive) {
                    stopCurrentJobControlled(markAsCancelled = true)
                } else {
                    scanJob = null
                }

                // 2. Verifica che non rimangano sessioni nello stato RUNNING nel database
                val activeSession = scanSessionDao.getRunningOrPausedSession()
                if (activeSession != null && activeSession.status == ScanSessionStatus.RUNNING.name) {
                    scanSessionDao.updateStatus(activeSession.id, ScanSessionStatus.CANCELLED.name)
                    pendingDirectoryDao.clearPendingForSession(activeSession.id)
                }

                // 3. Recupera le cartelle configurate e verifica che ce ne sia almeno una accessibile
                val folders = folderRepository.getFolders().filter { it.isAccessible }
                if (folders.isEmpty()) {
                    throw IllegalStateException("Nessuna cartella configurata o accessibile per la scansione.")
                }

                // 4. Garantisce che nessun flag residui prima di creare la nuova sessione
                scanner.resetControlFlags()

                // 5. Crea la nuova sessione e popola la coda iniziale in modo atomico
                val newSession = scanner.createNewSession(folders)

                // 6. Recupera le regole di esclusione correnti
                val rules = settingsRepository.getCurrentSettings()
                val exclusions = rules.exclusions

                // 7. Lancia la scansione in background solo dopo che il job precedente è definitivamente terminato
                launchScanJob(newSession.id, exclusions)

                newSession
            }
        }
    }

    /**
     * Mette in pausa la scansione attualmente in corso.
     * Non dichiara lo stato PAUSED prima che il motore abbia terminato il batch corrente
     * e lasciato intatta la directory pendente.
     */
    suspend fun pauseScan(): Result<Unit> = withContext(ioDispatcher) {
        scanMutex.withLock {
            runCatching {
                val currentJob = scanJob
                if (currentJob != null && currentJob.isActive) {
                    // 1. Richiede la pausa cooperativa
                    scanner.requestPause()

                    // 2. Attende che il motore termini effettivamente il batch corrente e si arresti
                    val completed = withTimeoutOrNull(10000L) {
                        currentJob.join()
                    } != null

                    if (!completed) {
                        currentJob.cancel(CancellationException("Timeout durante la sospensione della scansione"))
                        currentJob.join()
                    }
                    scanJob = null
                }

                // 3. Garantisce che lo stato nel DB sia coerente con PAUSED se la sessione era in corso
                val active = scanSessionDao.getRunningOrPausedSession()
                if (active != null && active.status == ScanSessionStatus.RUNNING.name) {
                    scanSessionDao.updateStatus(active.id, ScanSessionStatus.PAUSED.name)
                }
                Unit
            }
        }
    }

    /**
     * Riprende una scansione in stato PAUSED.
     * Avvia un nuovo job soltanto quando quello precedente è definitivamente terminato.
     */
    suspend fun resumeScan(): Result<Unit> = withContext(ioDispatcher) {
        scanMutex.withLock {
            runCatching {
                // 1. Verifica che non esista già un job attivo
                if (scanJob != null && scanJob!!.isActive) {
                    throw IllegalStateException("Una scansione è già in esecuzione.")
                }
                scanJob = null

                // 2. Verifica che esista una sessione in pausa da riprendere
                val paused = scanSessionDao.getRunningOrPausedSession()
                    ?: throw IllegalStateException("Nessuna scansione in pausa da riprendere.")

                if (paused.status != ScanSessionStatus.PAUSED.name) {
                    throw IllegalStateException("La sessione corrente (${paused.status}) non è in stato PAUSED.")
                }

                // 3. Reset dei flag di controllo
                scanner.resetControlFlags()

                // 4. Recupera le esclusioni aggiornate
                val rules = settingsRepository.getCurrentSettings()
                val exclusions = rules.exclusions

                // 5. Avvia il nuovo job di scansione
                launchScanJob(paused.id, exclusions)
                Unit
            }
        }
    }

    /**
     * Annulla la scansione in corso o in pausa.
     * Richiede l'annullamento cooperativo, attende la terminazione effettiva del job,
     * aggiorna lo stato a CANCELLED ed elimina la coda pendente, azzerando i flag solo alla fine.
     */
    suspend fun cancelScan(): Result<Unit> = withContext(ioDispatcher) {
        scanMutex.withLock {
            runCatching {
                val currentJob = scanJob
                if (currentJob != null && currentJob.isActive) {
                    // 1. Richiede inizialmente l'annullamento cooperativo
                    scanner.requestCancel()

                    // 2. Attende la terminazione effettiva del job
                    val completed = withTimeoutOrNull(10000L) {
                        currentJob.join()
                    } != null

                    if (!completed) {
                        currentJob.cancel(CancellationException("Scansione interrotta forzatamente per timeout dell'annullamento cooperativo."))
                        currentJob.join()
                    }
                }
                scanJob = null

                // 3. Solo DOPO la terminazione effettiva aggiorna lo stato a CANCELLED ed elimina la coda
                val active = scanSessionDao.getRunningOrPausedSession()
                if (active != null) {
                    scanSessionDao.updateStatus(active.id, ScanSessionStatus.CANCELLED.name)
                    pendingDirectoryDao.clearPendingForSession(active.id)
                }

                // 4. Ora che il job è terminato, azzera i flag di controllo
                scanner.resetControlFlags()
                Unit
            }
        }
    }

    /**
     * Lancia la coroutine di scansione. Non cancella incondizionatamente il job precedente,
     * ma verifica che non esista alcun job attivo prima di avviare.
     */
    private fun launchScanJob(sessionId: String, exclusions: List<String>) {
        check(scanJob == null || !scanJob!!.isActive) {
            "Impossibile avviare una scansione: un job è già attivo per questa istanza."
        }
        scanJob = repositoryScope.launch {
            try {
                scanner.runScan(sessionId, exclusions)
            } catch (e: CancellationException) {
                // Job cancellato regolarmente
            } catch (e: Exception) {
                scanSessionDao.updateStatus(
                    sessionId = sessionId,
                    status = ScanSessionStatus.FAILED.name,
                    errorMessage = "Errore imprevisto durante la scansione: ${e.localizedMessage}"
                )
            }
        }
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
}
