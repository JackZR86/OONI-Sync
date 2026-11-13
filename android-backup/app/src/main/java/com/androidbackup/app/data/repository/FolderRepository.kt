package com.androidbackup.app.data.repository

import android.net.Uri
import com.androidbackup.app.data.local.dao.FileFingerprintDao
import com.androidbackup.app.data.local.dao.FolderDao
import com.androidbackup.app.data.local.dao.ScanSessionDao
import com.androidbackup.app.data.local.entity.BackupFolderEntity
import com.androidbackup.app.data.model.BackupSource
import com.androidbackup.app.data.model.Priority
import com.androidbackup.app.data.saf.SafPermissionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Eccezione specifica sollevata quando la cancellazione dal database Room ha avuto successo,
 * ma il rilascio del permesso persistente SAF ha restituito un errore (Point 1).
 */
class PermissionReleaseException(
    val treeUri: Uri,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Eccezione specifica sollevata quando l'inserimento nel database Room fallisce e
 * anche il successivo tentativo di rilascio di compensazione SAF fallisce (Point 2).
 */
class PermissionCompensationFailedException(
    val treeUri: Uri,
    message: String,
    val insertCause: Throwable,
    val releaseCause: Throwable? = null
) : Exception(message, insertCause)

/**
 * Repository per la gestione e persistenza delle cartelle monitorate tramite Storage Access Framework (SAF).
 *
 * Conforme alla Specifica Fase 1B.1 e Revisione Finale:
 * - Separato rigorosamente da [SettingsRepository].
 * - Gestione dei permessi persistenti in sola lettura tramite [SafPermissionManager].
 * - Compensazione automatica su errori di inserimento con rilevazione autorizzazioni orfane.
 * - Procedura dedicata di ripristino autorizzazioni per cartelle esistenti [reauthorizeFolder].
 * - Meccanismo esplicito di aggiornamento accessibilità [refreshAccessibility] attivabile da Lifecycle.
 * - Tracciamento reattivo delle autorizzazioni orfane [orphanPermissionsFlow] e [getOrphanPermissions].
 * - Retry affidabile del rilascio permessi [retryReleasePermission] senza mai rilasciare cartelle attive.
 * - Cancellazione a due fasi con controllo rigoroso dell'esito del rilascio permessi.
 * - Serializzazione completa con [Mutex] per prevenire race conditions.
 */
class FolderRepository(
    private val folderDao: FolderDao,
    private val safPermissionManager: SafPermissionManager,
    private val scanSessionDao: ScanSessionDao,
    private val fileFingerprintDao: FileFingerprintDao,
    private val lifecycleCoordinator: FolderLifecycleCoordinator,
    private val runInTransaction: suspend (suspend () -> Unit) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val writeMutex = Mutex()

    // Trigger reattivo per forzare il ricalcolo dinamico dell'accessibilità SAF e permessi orfani
    private val refreshTrigger = MutableStateFlow(0L)

    /**
     * Flusso reattivo delle cartelle configurate nel database Room.
     * Combina l'osservazione di Room con [refreshTrigger], garantendo che
     * ritorni in app (ON_RESUME) o revoche esterne aggiornino la UI
     * istantaneamente senza dover alterare artificialmente i record nel database.
     */
    val foldersFlow: Flow<List<BackupSource>> = combine(
        folderDao.observeFolders(),
        refreshTrigger
    ) { entities, _ ->
        entities.map { entity ->
            val uri = Uri.parse(entity.treeUri)
            val hasPermission = safPermissionManager.hasPersistedReadPermission(uri)
            entity.toDomainModel(isAccessible = hasPermission)
        }
    }.flowOn(ioDispatcher)

    /**
     * Flusso reattivo delle autorizzazioni SAF orfane (URI con permesso persistente nel sistema
     * per cui non esiste più una cartella configurata nel database Room).
     *
     * Sopravvive alla chiusura e riapertura dell'app confrontando dinamicamente i permessi SAF
     * di sistema con le cartelle configurate in Room.
     */
    val orphanPermissionsFlow: Flow<List<Uri>> = combine(
        folderDao.observeFolders(),
        refreshTrigger
    ) { entities, _ ->
        val configuredUris = entities.map { it.treeUri }.toSet()
        val persisted = safPermissionManager.getPersistedReadUris()
        persisted.filter { it.toString() !in configuredUris }
    }.flowOn(ioDispatcher)

    /**
     * Forza un ricalcolo immediato dello stato di accessibilità delle cartelle e permessi orfani.
     * Da invocare all'avvio dell'app e su eventi ON_RESUME del ciclo di vita.
     */
    fun refreshAccessibility() {
        refreshTrigger.value = System.currentTimeMillis()
    }

    /**
     * Restituisce l'elenco immediato di tutti gli URI con permesso SAF persistente nel sistema
     * che non risultano associati ad alcuna cartella configurata in Room.
     */
    suspend fun getOrphanPermissions(): List<Uri> = withContext(ioDispatcher) {
        val configuredUris = folderDao.getFolders().map { it.treeUri }.toSet()
        safPermissionManager.getPersistedReadUris().filter { it.toString() !in configuredUris }
    }

    /**
     * Aggiunge una nuova cartella SAF alla configurazione persistente.
     *
     * Flusso con compensazione:
     * 1. Verifica che l'URI non sia già configurato.
     * 2. Acquisisce il permesso persistente in sola lettura.
     * 3. Risolve il display name e inserisce in Room.
     * 4. In caso di fallimento dopo l'acquisizione del permesso, esegue la compensazione
     *    rilasciando il permesso appena acquisito per evitare autorizzazioni orfane.
     *    Se anche la compensazione fallisce, solleva [PermissionCompensationFailedException].
     */
    suspend fun addFolder(
        treeUri: Uri,
        priority: Priority = Priority.NORMALE,
        protectFromEviction: Boolean = false
    ): Result<BackupSource> = withContext(ioDispatcher) {
        writeMutex.withLock {
            runCatching {
                val uriString = treeUri.toString()

                // 1. Verifica duplicati
                val existing = folderDao.getFolderByUri(uriString)
                if (existing != null) {
                    throw IllegalArgumentException("La cartella selezionata è già configurata nell'applicazione.")
                }

                // 2. Acquisizione permesso persistente in sola lettura
                safPermissionManager.takePersistableReadPermission(treeUri).getOrThrow()

                // 3. Risoluzione nome e inserimento Room con compensazione
                try {
                    val displayName = safPermissionManager.getFolderDisplayName(treeUri).ifBlank { "Cartella SAF" }
                    val authority = treeUri.authority ?: ""

                    val entity = BackupFolderEntity(
                        id = UUID.randomUUID().toString(),
                        treeUri = uriString,
                        displayName = displayName,
                        authority = authority,
                        addedTimestamp = System.currentTimeMillis(),
                        priority = priority.name,
                        protectFromEviction = protectFromEviction,
                        enabled = true
                    )

                    folderDao.insertFolder(entity)
                    refreshAccessibility()
                    entity.toDomainModel(isAccessible = true)
                } catch (e: Throwable) {
                    // Compensazione: rilascia il permesso persistente acquisito al passo 2
                    val releaseResult = safPermissionManager.releasePersistableReadPermission(treeUri)
                    refreshAccessibility()
                    if (releaseResult.isFailure) {
                        val releaseError = releaseResult.exceptionOrNull()
                        throw PermissionCompensationFailedException(
                            treeUri = treeUri,
                            message = "Inserimento cartella fallito e rilascio di compensazione SAF non riuscito: ${e.localizedMessage}. Il permesso rimane nel sistema come orfano.",
                            insertCause = e,
                            releaseCause = releaseError
                        )
                    }
                    throw e
                }
            }
        }
    }

    /**
     * Procedura dedicata di ripristino autorizzazione SAF per una cartella esistente.
     *
     * Comportamento:
     * 1. Identifica la cartella esistente in Room tramite [folderId].
     * 2. Verifica che [selectedTreeUri] corrisponda esattamente all'URI della cartella da ripristinare.
     *    Se diverso, rifiuta l'operazione con eccezione esplicita per prevenire sovrascritture accidentali.
     * 3. Riacquisisce il permesso persistente in sola lettura.
     * 4. Conserva ID, data di aggiunta, priorità, protezione ed ogni altra preferenza già salvata.
     * 5. Notifica l'aggiornamento della UI tramite [refreshAccessibility].
     */
    suspend fun reauthorizeFolder(
        folderId: String,
        selectedTreeUri: Uri
    ): Result<BackupSource> = withContext(ioDispatcher) {
        writeMutex.withLock {
            runCatching {
                val existing = folderDao.getFolderById(folderId)
                    ?: throw IllegalArgumentException("Cartella con ID '$folderId' non trovata nel database.")

                val selectedUriString = selectedTreeUri.toString()
                if (existing.treeUri != selectedUriString) {
                    throw IllegalArgumentException(
                        "L'URI selezionato non corrisponde alla cartella da ripristinare."
                    )
                }

                // Riacquisizione permesso persistente in sola lettura
                safPermissionManager.takePersistableReadPermission(selectedTreeUri).getOrThrow()

                refreshAccessibility()
                existing.toDomainModel(isAccessible = true)
            }
        }
    }

    /**
     * Rimuove una cartella dalla configurazione in modo coordinato con l'avvio scansioni.
     *
     * Flusso:
     * 1. Sotto lock condiviso [lifecycleCoordinator], verifica l'assenza di sessioni RUNNING o PAUSED.
     * 2. In una singola transazione Room ([runInTransaction]), ri-verifica l'assenza di sessioni attive,
     *    elimina il record dalla tabella `backup_folders` e pulisce la cache `file_fingerprints`.
     * 3. Solo dopo il commit della transazione rilascia l'eventuale permesso persistente SAF.
     * 4. Se il rilascio SAF fallisce, solleva [PermissionReleaseException] per consentire retry.
     */
    suspend fun removeFolder(id: String): Result<Unit> = withContext(ioDispatcher) {
        writeMutex.withLock {
            runCatching {
                var uriToRelease: Uri? = null

                lifecycleCoordinator.withLock {
                    val activeSession = scanSessionDao.getRunningOrPausedSession()
                    if (activeSession != null) {
                        throw IllegalStateException(
                            "Impossibile rimuovere la cartella mentre è presente una scansione attiva o in pausa (sessione: ${activeSession.id}, stato: ${activeSession.status})."
                        )
                    }

                    runInTransaction {
                        val activeInsideTx = scanSessionDao.getRunningOrPausedSession()
                        if (activeInsideTx != null) {
                            throw IllegalStateException(
                                "Impossibile rimuovere la cartella mentre è presente una scansione attiva o in pausa (sessione: ${activeInsideTx.id}, stato: ${activeInsideTx.status})."
                            )
                        }

                        val folder = folderDao.getFolderById(id) ?: return@runInTransaction
                        folderDao.deleteFolderById(id)
                        fileFingerprintDao.deleteByFolderId(id)

                        val remainingWithSameUri = folderDao.getFolderByUri(folder.treeUri)
                        if (remainingWithSameUri == null) {
                            uriToRelease = Uri.parse(folder.treeUri)
                        }
                    }
                }

                val releaseTarget = uriToRelease
                if (releaseTarget != null) {
                    val releaseResult = safPermissionManager.releasePersistableReadPermission(releaseTarget)
                    refreshAccessibility()
                    if (releaseResult.isFailure) {
                        val cause = releaseResult.exceptionOrNull()
                        throw PermissionReleaseException(
                            treeUri = releaseTarget,
                            message = "Cartella rimossa dalla configurazione, ma il rilascio del permesso SAF di sistema è fallito: ${cause?.localizedMessage}. Il permesso può essere rilasciato tramite retry.",
                            cause = cause
                        )
                    }
                } else {
                    refreshAccessibility()
                }
            }
        }
    }

    /**
     * Consente di ritentare il rilascio di un permesso persistente SAF fallito in precedenza (Point 1).
     * Rilascia il permesso SOLO se non esistono cartelle configurate in Room con lo stesso URI,
     * garantendo che non vengano mai revocati permessi di cartelle ancora attive.
     */
    suspend fun retryReleasePermission(treeUri: Uri): Result<Unit> = withContext(ioDispatcher) {
        writeMutex.withLock {
            val remaining = folderDao.getFolderByUri(treeUri.toString())
            if (remaining != null) {
                Result.failure(IllegalStateException("Impossibile rilasciare il permesso: l'URI è ancora associato a una cartella attiva."))
            } else {
                val result = safPermissionManager.releasePersistableReadPermission(treeUri)
                if (result.isSuccess) {
                    refreshAccessibility()
                }
                result
            }
        }
    }

    /**
     * Aggiorna la priorità della cartella in Room.
     */
    suspend fun updatePriority(id: String, priority: Priority): Result<Unit> = withContext(ioDispatcher) {
        writeMutex.withLock {
            runCatching {
                folderDao.updatePriority(id, priority.name)
                Unit
            }
        }
    }

    /**
     * Aggiorna la protezione da eliminazione automatica in Room.
     */
    suspend fun updateEvictionProtection(id: String, protect: Boolean): Result<Unit> = withContext(ioDispatcher) {
        writeMutex.withLock {
            runCatching {
                folderDao.updateEvictionProtection(id, protect)
                Unit
            }
        }
    }

    /**
     * Aggiorna lo stato di abilitazione della cartella in Room.
     */
    suspend fun updateEnabled(id: String, enabled: Boolean): Result<Unit> = withContext(ioDispatcher) {
        writeMutex.withLock {
            runCatching {
                folderDao.updateEnabled(id, enabled)
                Unit
            }
        }
    }

    /**
     * Restituisce la lista istantanea delle cartelle configurate verificando i permessi attivi.
     */
    suspend fun getFolders(): List<BackupSource> = withContext(ioDispatcher) {
        folderDao.getFolders().map { entity ->
            val uri = Uri.parse(entity.treeUri)
            val hasPermission = safPermissionManager.hasPersistedReadPermission(uri)
            entity.toDomainModel(isAccessible = hasPermission)
        }
    }

    /**
     * Verifica la disponibilità del permesso per un dato URI.
     */
    fun hasPermission(treeUri: Uri): Boolean {
        return safPermissionManager.hasPersistedReadPermission(treeUri)
    }
}
