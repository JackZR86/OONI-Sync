package com.androidbackup.app.ui.viewmodel

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidbackup.app.BuildConfig
import com.androidbackup.app.data.local.entity.ScanSessionEntity
import com.androidbackup.app.data.local.entity.ScanSessionStatus
import com.androidbackup.app.data.mock.MockDataProvider
import com.androidbackup.app.data.model.*
import com.androidbackup.app.data.repository.DriveAccountRepository
import com.androidbackup.app.data.repository.DriveAuthStepOutcome
import com.androidbackup.app.data.repository.FolderRepository
import com.androidbackup.app.data.repository.PermissionCompensationFailedException
import com.androidbackup.app.data.repository.PermissionReleaseException
import com.androidbackup.app.data.repository.ScanRepository
import com.androidbackup.app.data.repository.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Stato della UI dell'applicazione Android Backup (Fase 1B.2)
 */
data class BackupUiState(
    val status: BackupStatus = BackupStatus.COMPLETATO,
    val lastBackupTimestamp: String? = "5 Nov 2026, 09:15",
    val nextEstimatedBackupTime: String = "Domani, 09:15 (stimato WorkManager)",
    val backupProgress: Float = 0f,
    val currentOperationMessage: String? = null,
    val folders: List<BackupSource> = MockDataProvider.demoFolders,
    val rules: AppSettings = AppSettings(),
    val quota: GoogleStorageQuota = MockDataProvider.demoQuota,
    val history: List<BackupRun> = MockDataProvider.demoHistory,
    val logs: List<BackupLog> = MockDataProvider.demoLogs,
    val selectedLogLevel: LogLevel? = null, // null = TUTTI
    val logSearchQuery: String = "",
    val isDemoMode: Boolean = true,
    val isAddFolderDialogOpen: Boolean = false,
    val isFifoWarningDialogOpen: Boolean = false,
    val lastErrorMessage: String? = null,
    val orphanPermissions: List<Uri> = emptyList(),
    val orphanActionInProgress: Boolean = false,

    // Motore di scansione e inventario (Fase 1B.2)
    val scanSession: ScanSessionEntity? = null,
    val lastCompletedScanSession: ScanSessionEntity? = null,
    val folderInventoryMap: Map<String, FolderInventoryInfo> = emptyMap(),
    val isScanInProgress: Boolean = false,
    val isScanPaused: Boolean = false,

    // Identità e autorizzazione Google Drive (Fase 1C.1)
    val driveSessionAuthState: DriveSessionAuthState = DriveSessionAuthState.Initializing,
    val driveFeedbackMessage: String? = null,
    val driveErrorMessage: String? = null,
    val driveRemoteRevokeFailedMessage: String? = null
)

/**
 * ViewModel principale per Android Backup con integrazione persistenza Room, gestione permessi SAF,
 * motore di scansione ricorsiva locale e identità/autorizzazione Google Drive (Fase 1C.1).
 */
class BackupViewModel(
    private val settingsRepository: SettingsRepository,
    private val folderRepository: FolderRepository? = null,
    private val scanRepository: ScanRepository? = null,
    private val driveAccountRepository: DriveAccountRepository? = null,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
) : ViewModel() {

    // Costruttori secondari per compatibilità con i test unitari focalizzati su specifiche porzioni
    constructor(settingsRepository: SettingsRepository) : this(settingsRepository, null, null, null, SavedStateHandle())
    constructor(settingsRepository: SettingsRepository, folderRepository: FolderRepository?) : this(settingsRepository, folderRepository, null, null, SavedStateHandle())
    constructor(
        settingsRepository: SettingsRepository,
        folderRepository: FolderRepository?,
        scanRepository: ScanRepository?
    ) : this(settingsRepository, folderRepository, scanRepository, null, SavedStateHandle())

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    private val _driveAuthEvents = Channel<DriveAuthUiEvent>(Channel.BUFFERED)
    val driveAuthEvents: Flow<DriveAuthUiEvent> = _driveAuthEvents.receiveAsFlow()

    private val _realFolders = MutableStateFlow<List<BackupSource>>(emptyList())
    private var simulationJob: Job? = null
    private var isDriveOperationDispatching = false

    init {
        // 1. Osserva costantemente le impostazioni reali dal repository Room
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { persistentRules ->
                _uiState.update { it.copy(rules = persistentRules) }
            }
        }

        // 2. Osserva reattivamente le cartelle SAF reali dal database Room
        if (folderRepository != null) {
            viewModelScope.launch {
                folderRepository.foldersFlow.collect { realFolders ->
                    _realFolders.value = realFolders
                    updateFoldersInUi(realFolders)
                }
            }

            // Osserva reattivamente le autorizzazioni orfane SAF
            viewModelScope.launch {
                folderRepository.orphanPermissionsFlow.collect { orphans ->
                    _uiState.update { it.copy(orphanPermissions = orphans) }
                }
            }
        }

        // 3. Osserva reattivamente le sessioni di scansione dal database Room (Fase 1B.2)
        if (scanRepository != null) {
            viewModelScope.launch {
                scanRepository.latestSessionFlow.collect { session ->
                    val isRunning = session?.sessionStatus == ScanSessionStatus.RUNNING
                    val isPaused = session?.sessionStatus == ScanSessionStatus.PAUSED

                    _uiState.update {
                        it.copy(
                            scanSession = session,
                            isScanInProgress = isRunning,
                            isScanPaused = isPaused
                        )
                    }

                    refreshFolderStats()
                }
            }

            viewModelScope.launch {
                scanRepository.latestCompletedSessionFlow.collect { completedSession ->
                    _uiState.update { it.copy(lastCompletedScanSession = completedSession) }
                    refreshFolderStats()
                }
            }
        }

        // 4. Osserva reattivamente lo stato Google Drive (Room + sessione corrente + SavedStateHandle)
        if (driveAccountRepository == null) {
            _uiState.update { it.copy(driveSessionAuthState = DriveSessionAuthState.NotLinked) }
        } else {
            viewModelScope.launch {
                kotlinx.coroutines.flow.combine(
                    driveAccountRepository.observeSessionAuthState(),
                    savedStateHandle.getStateFlow<String?>(KEY_PENDING_AUTH_PURPOSE, null)
                ) { repoState, _ ->
                    val restoredPending = readPendingDriveAuthRequest()
                    if (
                        restoredPending != null &&
                        repoState !is DriveSessionAuthState.InProgress &&
                        repoState !is DriveSessionAuthState.Disconnecting
                    ) {
                        DriveSessionAuthState.InProgress(
                            purpose = restoredPending.purpose,
                            existingAccount = extractAccountFromSessionState(repoState)
                        )
                    } else {
                        repoState
                    }
                }.collect { effectiveState ->
                    _uiState.update { it.copy(driveSessionAuthState = effectiveState) }
                }
            }
        }
    }

    private fun updateFoldersInUi(baseFolders: List<BackupSource>) {
        if (_uiState.value.isDemoMode) return

        val statsMap = _uiState.value.folderInventoryMap
        val mapped = baseFolders.map { folder ->
            val stats = statsMap[folder.id]
            if (stats != null && stats.fileCount > 0) {
                folder.copy(
                    fileCount = stats.fileCount,
                    totalSizeMb = stats.totalSizeMb,
                    lastScanned = "Scansione completata"
                )
            } else {
                folder
            }
        }

        _uiState.update { it.copy(folders = mapped) }
    }

    private fun refreshFolderStats() {
        if (scanRepository == null || _uiState.value.isDemoMode) return

        viewModelScope.launch {
            val currentFolders = _realFolders.value
            val newStatsMap = mutableMapOf<String, FolderInventoryInfo>()

            for (folder in currentFolders) {
                val dbStats = scanRepository.getFolderStats(folder.id)
                newStatsMap[folder.id] = FolderInventoryInfo(
                    folderId = folder.id,
                    fileCount = dbStats.fileCount,
                    directoryCount = dbStats.directoryCount,
                    totalKnownBytes = dbStats.totalKnownBytes,
                    unknownSizeCount = dbStats.unknownSizeCount,
                    excludedCount = dbStats.excludedCount
                )
            }

            _uiState.update { it.copy(folderInventoryMap = newStatsMap) }
            updateFoldersInUi(currentFolders)
        }
    }

    /**
     * Alterna la modalità Dati Dimostrativi con dati mock espliciti.
     * NOTA: Le impostazioni, le cartelle reali e le sessioni Room NON vengono toccate o cancellate.
     */
    fun toggleDemoMode(enableDemo: Boolean) {
        if (enableDemo) {
            _uiState.update {
                it.copy(
                    isDemoMode = true,
                    status = BackupStatus.COMPLETATO,
                    lastBackupTimestamp = "5 Nov 2026, 09:15",
                    nextEstimatedBackupTime = "Domani, 09:15 (stimato WorkManager)",
                    folders = MockDataProvider.demoFolders,
                    quota = MockDataProvider.demoQuota,
                    history = MockDataProvider.demoHistory,
                    logs = MockDataProvider.demoLogs
                )
            }
        } else {
            // Passa alla modalità reale con i dati salvati in Room
            val currentReal = _realFolders.value
            _uiState.update {
                it.copy(
                    isDemoMode = false,
                    status = if (currentReal.isNotEmpty()) BackupStatus.COMPLETATO else BackupStatus.INATTIVO,
                    lastBackupTimestamp = if (currentReal.isNotEmpty()) "Non ancora eseguito" else null,
                    nextEstimatedBackupTime = if (currentReal.isNotEmpty()) "In attesa di pianificazione" else "Non pianificato (Nessuna cartella configurata)",
                    folders = currentReal,
                    quota = GoogleStorageQuota(usedBytes = 0L, isMock = true),
                    history = emptyList(),
                    logs = listOf(
                        BackupLog(
                            id = "init-real",
                            timestamp = SimpleDateFormat("HH:mm:ss", Locale.ITALIAN).format(Date()),
                            level = LogLevel.INFO,
                            category = "SISTEMA",
                            message = "Modalità reale attiva. Monitoraggio cartelle SAF (totale: ${currentReal.size}).",
                            technicalCode = "REAL_MODE_ACTIVE"
                        )
                    )
                )
            }
            refreshFolderStats()
        }
    }

    // ==========================================
    // MOTORE DI SCANSIONE LOCALE (Fase 1B.2)
    // ==========================================

    /**
     * Avvia una nuova scansione locale delle cartelle configurate.
     */
    fun startScan() {
        if (_uiState.value.isDemoMode) {
            // In modalità demo simula un avanzamento UI per verifica grafica
            logInfo("Simulazione scansione demo avviata.")
            return
        }

        if (scanRepository == null) return

        viewModelScope.launch {
            scanRepository.startNewScan()
                .onSuccess { session ->
                    logInfo("Scansione locale avviata (Sessione: ${session.id.substring(0, 8)}).")
                }
                .onFailure { error ->
                    val msg = error.localizedMessage ?: "Errore durante l'avvio della scansione"
                    _uiState.update { it.copy(lastErrorMessage = msg) }
                    logPersistenceError("Avvio scansione non riuscito: $msg")
                }
        }
    }

    /**
     * Mette in pausa la scansione in corso.
     */
    fun pauseScan() {
        if (scanRepository == null) return
        viewModelScope.launch {
            scanRepository.pauseScan()
                .onSuccess {
                    logInfo("Scansione messa in pausa dall'utente.")
                }
                .onFailure { error ->
                    logPersistenceError("Pausa scansione fallita: ${error.localizedMessage}")
                }
        }
    }

    /**
     * Riprende una scansione in pausa o interrotta.
     */
    fun resumeScan() {
        if (scanRepository == null) return
        viewModelScope.launch {
            scanRepository.resumeScan()
                .onSuccess {
                    logInfo("Scansione ripresa dalla coda persistente.")
                }
                .onFailure { error ->
                    val msg = error.localizedMessage ?: "Errore durante la ripresa della scansione"
                    _uiState.update { it.copy(lastErrorMessage = msg) }
                    logPersistenceError("Ripresa scansione fallita: $msg")
                }
        }
    }

    /**
     * Annulla la scansione attiva.
     */
    fun cancelScan() {
        if (scanRepository == null) return
        viewModelScope.launch {
            scanRepository.cancelScan()
                .onSuccess {
                    logInfo("Scansione annullata dall'utente.")
                }
                .onFailure { error ->
                    logPersistenceError("Annullamento scansione fallito: ${error.localizedMessage}")
                }
        }
    }

    /**
     * Imposta manualmente lo stato per testare visivamente tutti gli stati richiesti:
     * (Inattivo, In attesa, In corso, Completato, Parziale, Errore)
     */
    fun setBackupStatus(status: BackupStatus) {
        _uiState.update { it.copy(status = status) }
    }

    /**
     * Simula il processo di backup (solo UI state chiaramente identificato come simulazione)
     */
    fun startBackupSimulation() {
        if (_uiState.value.status == BackupStatus.IN_CORSO) return

        simulationJob?.cancel()
        simulationJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    status = BackupStatus.IN_CORSO,
                    backupProgress = 0.05f,
                    currentOperationMessage = "SIMULAZIONE: Verifica vincoli di rete (Wi-Fi)..."
                )
            }
            delay(800)

            _uiState.update {
                it.copy(
                    backupProgress = 0.35f,
                    currentOperationMessage = "SIMULAZIONE: Scansione ricorsiva cartelle e fingerprint SHA-256..."
                )
            }
            delay(1000)

            _uiState.update {
                it.copy(
                    backupProgress = 0.75f,
                    currentOperationMessage = "SIMULAZIONE: Verifica quote e simulazione caricamento verso Drive..."
                )
            }
            delay(1200)

            val nowFormatted = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.ITALIAN).format(Date())
            val simulatedRun = BackupRun(
                id = "sim-${System.currentTimeMillis()}",
                timestamp = "$nowFormatted (Simulato)",
                durationSeconds = 3,
                status = BackupStatus.COMPLETATO,
                filesChecked = _uiState.value.folders.sumOf { it.fileCount }.coerceAtLeast(10),
                filesUploaded = 3,
                filesSkipped = (_uiState.value.folders.sumOf { it.fileCount } - 3).coerceAtLeast(7),
                bytesUploaded = 15L * 1024 * 1024,
                bytesFreed = 0L,
                triggerType = "SIMULAZIONE UI",
                notes = "[SIMULAZIONE DIMOSTRATIVA] Esecuzione di prova UI. Nessun file locale o su Google Drive è stato modificato."
            )

            val simulatedLog = BackupLog(
                id = "log-sim-${System.currentTimeMillis()}",
                timestamp = SimpleDateFormat("HH:mm:ss", Locale.ITALIAN).format(Date()),
                level = LogLevel.INFO,
                category = "SISTEMA",
                message = "Simulazione di backup completata con successo nella UI.",
                technicalCode = "SIMULATION_SUCCESS"
            )

            _uiState.update {
                it.copy(
                    backupProgress = 1.0f,
                    status = BackupStatus.COMPLETATO,
                    currentOperationMessage = null,
                    lastBackupTimestamp = "$nowFormatted (Simulato)",
                    nextEstimatedBackupTime = "Fra circa 24 ore (stimato WorkManager)",
                    history = listOf(simulatedRun) + it.history,
                    logs = listOf(simulatedLog) + it.logs
                )
            }
        }
    }

    fun cancelBackupSimulation() {
        simulationJob?.cancel()
        _uiState.update {
            it.copy(
                status = BackupStatus.INATTIVO,
                backupProgress = 0f,
                currentOperationMessage = null
            )
        }
    }

    // ==========================================
    // GESTIONE CARTELLE SAF & PERSISTENZA ROOM (Fase 1B.1)
    // ==========================================

    fun addFolderFromSaf(uri: Uri, priority: Priority = Priority.NORMALE, protectFromEviction: Boolean = false) {
        if (_uiState.value.isDemoMode || folderRepository == null) {
            val displayName = uri.lastPathSegment?.substringAfterLast(':') ?: "Cartella SAF"
            val demoFolder = BackupSource(
                id = UUID.randomUUID().toString(),
                name = displayName,
                path = uri.toString(),
                priority = priority,
                protectFromEviction = protectFromEviction,
                fileCount = 0,
                totalSizeMb = 0,
                lastScanned = null,
                isAccessible = true
            )
            _uiState.update { it.copy(folders = it.folders + demoFolder) }
            logInfo("Aggiunta cartella demo da SAF: $displayName")
            return
        }

        viewModelScope.launch {
            folderRepository.addFolder(uri, priority, protectFromEviction)
                .onSuccess { addedFolder ->
                    logInfo("Cartella '${addedFolder.name}' aggiunta con successo in sola lettura.")
                }
                .onFailure { error ->
                    val msg = error.localizedMessage ?: "Errore sconosciuto"
                    if (error is PermissionCompensationFailedException) {
                        _uiState.update {
                            it.copy(
                                lastErrorMessage = "Aggiunta cartella fallita e compensazione non riuscita. Il permesso Android è rimasto nel sistema ed è disponibile per il rilascio."
                            )
                        }
                    } else {
                        _uiState.update { it.copy(lastErrorMessage = msg) }
                    }
                    logPersistenceError("Aggiunta cartella SAF fallita: $msg")
                }
        }
    }

    fun addFolder(name: String, path: String, priority: Priority, protectFromEviction: Boolean) {
        val newFolder = BackupSource(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Nuova Cartella Dimostrativa" },
            path = path.ifBlank { "content://com.android.externalstorage.documents/tree/primary" },
            priority = priority,
            protectFromEviction = protectFromEviction,
            fileCount = 0,
            totalSizeMb = 0,
            lastScanned = null,
            isAccessible = true
        )
        _uiState.update { it.copy(folders = it.folders + newFolder) }
    }

    fun reauthorizeFolder(folderId: String, selectedUri: Uri) {
        if (_uiState.value.isDemoMode || folderRepository == null) {
            _uiState.update { state ->
                state.copy(folders = state.folders.map { if (it.id == folderId) it.copy(isAccessible = true) else it })
            }
            logInfo("Cartella demo ripristinata.")
            return
        }

        viewModelScope.launch {
            folderRepository.reauthorizeFolder(folderId, selectedUri)
                .onSuccess { restored ->
                    logInfo("Autorizzazione per la cartella '${restored.name}' ripristinata con successo.")
                }
                .onFailure { error ->
                    val msg = error.localizedMessage ?: "Errore durante il ripristino dell'autorizzazione"
                    _uiState.update { it.copy(lastErrorMessage = msg) }
                    logPersistenceError("Ripristino autorizzazione fallito: $msg")
                }
        }
    }

    fun refreshFolderPermissions() {
        folderRepository?.refreshAccessibility()
    }

    fun removeFolder(id: String) {
        if (_uiState.value.isDemoMode || folderRepository == null) {
            _uiState.update { it.copy(folders = it.folders.filter { f -> f.id != id }) }
            return
        }

        viewModelScope.launch {
            folderRepository.removeFolder(id)
                .onSuccess {
                    logInfo("Cartella rimossa dalla configurazione e permesso persistente rilasciato.")
                }
                .onFailure { error ->
                    val msg = error.localizedMessage ?: "Errore sconosciuto"
                    if (error is PermissionReleaseException) {
                        _uiState.update {
                            it.copy(
                                lastErrorMessage = "Cartella rimossa dalla configurazione, ma il permesso Android è ancora presente nel sistema. Usa 'Riprova rilascio' per rimuoverlo."
                            )
                        }
                    } else {
                        _uiState.update { it.copy(lastErrorMessage = msg) }
                    }
                    logPersistenceError("Rimozione cartella completata con avviso: $msg")
                }
        }
    }

    fun retryReleasePermission(uri: Uri) {
        if (folderRepository == null) return

        viewModelScope.launch {
            _uiState.update { it.copy(orphanActionInProgress = true) }
            folderRepository.retryReleasePermission(uri)
                .onSuccess {
                    logInfo("Rilascio del permesso persistente SAF completato con successo.")
                    _uiState.update { state ->
                        state.copy(
                            orphanActionInProgress = false,
                            orphanPermissions = state.orphanPermissions.filter { it != uri },
                            lastErrorMessage = null
                        )
                    }
                }
                .onFailure { error ->
                    val msg = error.localizedMessage ?: "Errore durante il rilascio"
                    logPersistenceError("Rilascio del permesso SAF fallito: $msg")
                    _uiState.update { state ->
                        state.copy(
                            orphanActionInProgress = false,
                            lastErrorMessage = "Rilascio del permesso SAF non riuscito: $msg"
                        )
                    }
                }
        }
    }

    fun updateFolderPriority(id: String, newPriority: Priority) {
        if (_uiState.value.isDemoMode || folderRepository == null) {
            _uiState.update {
                it.copy(
                    folders = it.folders.map { f ->
                        if (f.id == id) f.copy(priority = newPriority) else f
                    }
                )
            }
            return
        }

        viewModelScope.launch {
            folderRepository.updatePriority(id, newPriority)
                .onFailure { error ->
                    logPersistenceError("Aggiornamento priorità fallito: ${error.localizedMessage}")
                }
        }
    }

    fun toggleFolderProtection(id: String) {
        if (_uiState.value.isDemoMode || folderRepository == null) {
            _uiState.update {
                it.copy(
                    folders = it.folders.map { f ->
                        if (f.id == id) f.copy(protectFromEviction = !f.protectFromEviction) else f
                    }
                )
            }
            return
        }

        val current = _uiState.value.folders.find { it.id == id }?.protectFromEviction ?: false
        viewModelScope.launch {
            folderRepository.updateEvictionProtection(id, !current)
                .onFailure { error ->
                    logPersistenceError("Aggiornamento protezione fallito: ${error.localizedMessage}")
                }
        }
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(lastErrorMessage = null) }
    }

    // ==========================================
    // GESTIONE REGOLE E PERSISTENZA ROOM (Fase 01A)
    // ==========================================

    fun updateFrequency(frequency: BackupFrequency) {
        _uiState.update { it.copy(rules = it.rules.copy(frequency = frequency)) }
        viewModelScope.launch {
            settingsRepository.updateFrequency(frequency).onFailure { error ->
                logPersistenceError("Salvataggio frequenza non riuscito: ${error.localizedMessage}")
            }
        }
    }

    fun updateNetworkPolicy(policy: NetworkPolicy) {
        _uiState.update { it.copy(rules = it.rules.copy(networkPolicy = policy)) }
        viewModelScope.launch {
            settingsRepository.updateNetworkPolicy(policy).onFailure { error ->
                logPersistenceError("Salvataggio policy di rete non riuscito: ${error.localizedMessage}")
            }
        }
    }

    fun setRequireCharging(enabled: Boolean) {
        _uiState.update { it.copy(rules = it.rules.copy(requireCharging = enabled)) }
        viewModelScope.launch {
            settingsRepository.updateRequireCharging(enabled).onFailure { error ->
                logPersistenceError("Salvataggio vincolo ricarica non riuscito: ${error.localizedMessage}")
            }
        }
    }

    fun setFifoEnabled(enabled: Boolean) {
        _uiState.update { it.copy(rules = it.rules.copy(fifoRotationEnabled = enabled)) }
        viewModelScope.launch {
            settingsRepository.updateFifoRotation(enabled).onFailure { error ->
                logPersistenceError("Salvataggio rotazione FIFO non riuscito: ${error.localizedMessage}")
            }
        }
    }

    fun setAllowCloudOnlyEviction(enabled: Boolean) {
        _uiState.update { it.copy(rules = it.rules.copy(allowCloudOnlyEviction = enabled)) }
        viewModelScope.launch {
            settingsRepository.updateCloudOnlyEviction(enabled).onFailure { error ->
                logPersistenceError("Salvataggio rimozione cloud-only non riuscito: ${error.localizedMessage}")
            }
        }
    }

    fun setReserveSpace(mb: Int) {
        _uiState.update { it.copy(rules = it.rules.copy(reserveSpaceMb = mb)) }
        viewModelScope.launch {
            settingsRepository.updateReserveSpace(mb).onFailure { error ->
                logPersistenceError("Salvataggio riserva spazio non riuscito: ${error.localizedMessage}")
            }
        }
    }

    fun addExclusionPattern(pattern: String) {
        val cleanPattern = pattern.trim()
        if (cleanPattern.isEmpty()) return
        if (_uiState.value.rules.exclusions.contains(cleanPattern)) return

        _uiState.update { state ->
            state.copy(rules = state.rules.copy(exclusions = state.rules.exclusions + cleanPattern))
        }
        viewModelScope.launch {
            settingsRepository.addExclusionPattern(cleanPattern).onFailure { error ->
                logPersistenceError("Aggiunta pattern '$cleanPattern' non riuscita: ${error.localizedMessage}")
            }
        }
    }

    fun removeExclusionPattern(pattern: String) {
        _uiState.update {
            it.copy(rules = it.rules.copy(exclusions = it.rules.exclusions.filter { p -> p != pattern }))
        }
        viewModelScope.launch {
            settingsRepository.removeExclusionPattern(pattern).onFailure { error ->
                logPersistenceError("Rimozione pattern '$pattern' non riuscita: ${error.localizedMessage}")
            }
        }
    }

    fun clearAllExclusions() {
        _uiState.update {
            it.copy(rules = it.rules.copy(exclusions = emptyList()))
        }
        viewModelScope.launch {
            settingsRepository.clearAllExclusions().onFailure { error ->
                logPersistenceError("Cancellazione di tutte le esclusioni non riuscita: ${error.localizedMessage}")
            }
        }
    }

    fun resetRulesToDefaults() {
        viewModelScope.launch {
            settingsRepository.resetToDefaults().onFailure { error ->
                logPersistenceError("Ripristino impostazioni predefinite non riuscito: ${error.localizedMessage}")
            }
        }
    }

    private fun logPersistenceError(message: String) {
        val errorLog = BackupLog(
            id = "err-db-${System.currentTimeMillis()}",
            timestamp = SimpleDateFormat("HH:mm:ss", Locale.ITALIAN).format(Date()),
            level = LogLevel.ERRORE,
            category = "STORAGE",
            message = message,
            technicalCode = "SAF_STORAGE_ERROR"
        )
        _uiState.update { it.copy(logs = listOf(errorLog) + it.logs) }
    }

    private fun logInfo(message: String) {
        val infoLog = BackupLog(
            id = "info-saf-${System.currentTimeMillis()}",
            timestamp = SimpleDateFormat("HH:mm:ss", Locale.ITALIAN).format(Date()),
            level = LogLevel.INFO,
            category = "SAF",
            message = message,
            technicalCode = "SAF_PERMISSION_OK"
        )
        _uiState.update { it.copy(logs = listOf(infoLog) + it.logs) }
    }

    fun setLogLevelFilter(level: LogLevel?) {
        _uiState.update { it.copy(selectedLogLevel = level) }
    }

    fun setLogSearchQuery(query: String) {
        _uiState.update { it.copy(logSearchQuery = query) }
    }

    // ==========================================
    // IDENTITÀ E AUTORIZZAZIONE GOOGLE DRIVE (Fase 1C.1)
    // ==========================================

    fun connectDriveAccount() {
        startInteractiveDriveFlow(DriveAuthFlowPurpose.INITIAL_LINK)
    }

    fun verifyDriveConnection() {
        startInteractiveDriveFlow(DriveAuthFlowPurpose.REVERIFY)
    }

    fun switchDriveAccount() {
        startInteractiveDriveFlow(DriveAuthFlowPurpose.SWITCH_ACCOUNT)
    }

    private fun startInteractiveDriveFlow(purpose: DriveAuthFlowPurpose) {
        if (_uiState.value.isDemoMode) return
        val repo = driveAccountRepository ?: return
        if (isDriveFlowBusy()) return

        val existingAccount = extractAccountFromSessionState(_uiState.value.driveSessionAuthState)
        val initialRequest = PendingDriveAuthRequest(
            purpose = purpose,
            expectedPermissionId = existingAccount?.permissionId,
            targetEmail = if (purpose == DriveAuthFlowPurpose.REVERIFY) existingAccount?.emailAddress else null,
            retryUsed = false
        )

        isDriveOperationDispatching = true
        savePendingDriveAuthRequest(initialRequest)
        _uiState.update {
            it.copy(
                driveFeedbackMessage = null,
                driveErrorMessage = null,
                driveRemoteRevokeFailedMessage = null
            )
        }

        viewModelScope.launch {
            try {
                val outcome = repo.beginInteractiveAuthorization(purpose)
                handleDriveAuthStepOutcome(outcome)
            } finally {
                isDriveOperationDispatching = false
            }
        }
    }

    fun onDriveAuthorizationActivityResult(resultCode: Int, data: Intent?) {
        if (_uiState.value.isDemoMode) {
            clearPendingDriveAuthRequest()
            return
        }
        val repo = driveAccountRepository ?: return
        val pendingRequest = readPendingDriveAuthRequest()
        clearPendingDriveAuthRequest()

        isDriveOperationDispatching = true
        viewModelScope.launch {
            try {
                val outcome = repo.completeAuthorizationFromIntent(
                    resultCodeOk = (resultCode == Activity.RESULT_OK),
                    hasData = (data != null),
                    pendingRequest = pendingRequest,
                    extractResult = { repo.extractAuthorizationResult(data) }
                )
                handleDriveAuthStepOutcome(outcome)
            } finally {
                isDriveOperationDispatching = false
            }
        }
    }

    fun onDrivePendingIntentLaunchFailed(reason: String?) {
        clearPendingDriveAuthRequest()
        val repo = driveAccountRepository ?: return
        val message = reason?.takeIf { it.isNotBlank() }
            ?: "Impossibile avviare la schermata di autorizzazione Google."
        viewModelScope.launch {
            repo.cancelPendingFlowAfterLaunchFailure(message)
            _uiState.update { it.copy(driveErrorMessage = message) }
        }
    }

    private suspend fun handleDriveAuthStepOutcome(outcome: DriveAuthStepOutcome) {
        when (outcome) {
            is DriveAuthStepOutcome.NeedsResolution -> {
                // Conserva stato ricostruibile (scopo, identità attesa e retryUsed) in SavedStateHandle;
                // la PendingIntent passa solo come evento one-shot.
                savePendingDriveAuthRequest(outcome.pendingRequest)
                _uiState.update {
                    it.copy(
                        driveSessionAuthState = DriveSessionAuthState.InProgress(
                            purpose = outcome.pendingRequest.purpose,
                            existingAccount = extractAccountFromSessionState(it.driveSessionAuthState)
                        )
                    )
                }
                _driveAuthEvents.send(DriveAuthUiEvent.LaunchAuthorizationIntent(outcome.intentSender))
            }

            is DriveAuthStepOutcome.Completed -> {
                clearPendingDriveAuthRequest()
                val msg = if (outcome.replacedExisting) {
                    "Account Google Drive sostituito e verificato: ${outcome.account.primaryDisplayLabel()}."
                } else {
                    "Account Google Drive verificato nella sessione corrente: ${outcome.account.primaryDisplayLabel()}."
                }
                _uiState.update {
                    it.copy(
                        driveFeedbackMessage = msg,
                        driveErrorMessage = null,
                        driveRemoteRevokeFailedMessage = null
                    )
                }
            }

            is DriveAuthStepOutcome.Cancelled -> {
                clearPendingDriveAuthRequest()
                _uiState.update {
                    it.copy(
                        driveFeedbackMessage = outcome.message,
                        driveErrorMessage = null
                    )
                }
            }

            is DriveAuthStepOutcome.Failed -> {
                clearPendingDriveAuthRequest()
                _uiState.update {
                    it.copy(
                        driveErrorMessage = outcome.reason,
                        driveFeedbackMessage = null
                    )
                }
            }

            DriveAuthStepOutcome.BusyRejected -> {
                // Non sovrascrive lo stato dell'operazione già in corso
            }
        }
    }

    fun disconnectDriveAccountWithRemoteRevoke() {
        if (_uiState.value.isDemoMode) return
        val repo = driveAccountRepository ?: return
        if (isDriveFlowBusy()) return

        isDriveOperationDispatching = true
        _uiState.update {
            it.copy(
                driveFeedbackMessage = null,
                driveErrorMessage = null,
                driveRemoteRevokeFailedMessage = null
            )
        }

        viewModelScope.launch {
            try {
                when (val outcome = repo.disconnectWithRemoteRevoke()) {
                    DriveDisconnectOutcome.RemoteRevokeSucceeded -> {
                        _uiState.update {
                            it.copy(
                                driveFeedbackMessage = "Accesso Google Drive revocato e account scollegato.",
                                driveErrorMessage = null,
                                driveRemoteRevokeFailedMessage = null
                            )
                        }
                    }

                    is DriveDisconnectOutcome.RemoteRevokeFailed -> {
                        _uiState.update {
                            it.copy(
                                driveRemoteRevokeFailedMessage = outcome.reason,
                                driveFeedbackMessage = null
                            )
                        }
                    }

                    DriveDisconnectOutcome.LocalUnlinkOnly -> {
                        _uiState.update {
                            it.copy(
                                driveFeedbackMessage = "Associazione locale rimossa. L'autorizzazione Google potrebbe restare attiva nelle impostazioni del tuo Account Google.",
                                driveRemoteRevokeFailedMessage = null
                            )
                        }
                    }
                }
            } finally {
                isDriveOperationDispatching = false
            }
        }
    }

    fun confirmLocalOnlyDriveDisconnect() {
        if (_uiState.value.isDemoMode) return
        val repo = driveAccountRepository ?: return
        if (isDriveFlowBusy()) return

        isDriveOperationDispatching = true
        _uiState.update {
            it.copy(
                driveFeedbackMessage = null,
                driveErrorMessage = null,
                driveRemoteRevokeFailedMessage = null
            )
        }

        viewModelScope.launch {
            try {
                repo.confirmLocalOnlyDisconnect()
                _uiState.update {
                    it.copy(
                        driveFeedbackMessage = "Account scollegato solo localmente. Puoi revocare manualmente l'accesso dalle impostazioni di sicurezza del tuo Account Google.",
                        driveErrorMessage = null,
                        driveRemoteRevokeFailedMessage = null
                    )
                }
            } finally {
                isDriveOperationDispatching = false
            }
        }
    }

    fun runDebugSilentDriveTokenCheck() {
        if (!BuildConfig.DEBUG) return
        if (_uiState.value.isDemoMode) return
        val repo = driveAccountRepository ?: return
        if (isDriveFlowBusy()) return

        isDriveOperationDispatching = true
        _uiState.update {
            it.copy(
                driveFeedbackMessage = null,
                driveErrorMessage = null
            )
        }

        viewModelScope.launch {
            try {
                when (val outcome = repo.runSilentTokenCheckNoUi()) {
                    is DebugSilentTokenCheckOutcome.Success -> {
                        _uiState.update {
                            it.copy(
                                driveFeedbackMessage = outcome.message,
                                driveErrorMessage = null
                            )
                        }
                    }

                    is DebugSilentTokenCheckOutcome.InteractionRequired -> {
                        _uiState.update {
                            it.copy(
                                driveErrorMessage = outcome.message,
                                driveFeedbackMessage = null
                            )
                        }
                    }

                    is DebugSilentTokenCheckOutcome.Failed -> {
                        _uiState.update {
                            it.copy(
                                driveErrorMessage = outcome.message,
                                driveFeedbackMessage = null
                            )
                        }
                    }
                }
            } finally {
                isDriveOperationDispatching = false
            }
        }
    }

    fun clearDriveMessages() {
        _uiState.update {
            it.copy(
                driveFeedbackMessage = null,
                driveErrorMessage = null,
                driveRemoteRevokeFailedMessage = null
            )
        }
    }

    private fun isDriveFlowBusy(): Boolean {
        if (isDriveOperationDispatching) return true
        val state = _uiState.value.driveSessionAuthState
        if (state is DriveSessionAuthState.Initializing ||
            state is DriveSessionAuthState.InProgress ||
            state is DriveSessionAuthState.Disconnecting
        ) {
            return true
        }
        return readPendingDriveAuthRequest() != null
    }

    private fun savePendingDriveAuthRequest(request: PendingDriveAuthRequest) {
        savedStateHandle[KEY_PENDING_AUTH_PURPOSE] = request.purpose.name
        savedStateHandle[KEY_PENDING_EXPECTED_PERMISSION_ID] = request.expectedPermissionId
        savedStateHandle[KEY_PENDING_TARGET_EMAIL] = request.targetEmail
        savedStateHandle[KEY_PENDING_RETRY_USED] = request.retryUsed
    }

    private fun readPendingDriveAuthRequest(): PendingDriveAuthRequest? {
        val purposeName: String = savedStateHandle[KEY_PENDING_AUTH_PURPOSE] ?: return null
        val purpose = try {
            DriveAuthFlowPurpose.valueOf(purposeName)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return PendingDriveAuthRequest(
            purpose = purpose,
            expectedPermissionId = savedStateHandle[KEY_PENDING_EXPECTED_PERMISSION_ID],
            targetEmail = savedStateHandle[KEY_PENDING_TARGET_EMAIL],
            retryUsed = savedStateHandle.get<Boolean>(KEY_PENDING_RETRY_USED) ?: false
        )
    }

    private fun clearPendingDriveAuthRequest() {
        savedStateHandle.remove<String>(KEY_PENDING_AUTH_PURPOSE)
        savedStateHandle.remove<String>(KEY_PENDING_EXPECTED_PERMISSION_ID)
        savedStateHandle.remove<String>(KEY_PENDING_TARGET_EMAIL)
        savedStateHandle.remove<Boolean>(KEY_PENDING_RETRY_USED)
    }

    private fun extractAccountFromSessionState(state: DriveSessionAuthState): LinkedDriveAccount? {
        return when (state) {
            DriveSessionAuthState.Initializing, DriveSessionAuthState.NotLinked -> null
            is DriveSessionAuthState.LinkedUnverifiedInSession -> state.account
            is DriveSessionAuthState.InProgress -> state.existingAccount
            is DriveSessionAuthState.Disconnecting -> state.account
            is DriveSessionAuthState.VerifiedInSession -> state.account
            is DriveSessionAuthState.ReauthRequired -> state.account
            is DriveSessionAuthState.LastVerificationFailed -> state.account
        }
    }

    companion object {
        internal const val KEY_PENDING_AUTH_PURPOSE = "drive_pending_auth_purpose"
        internal const val KEY_PENDING_EXPECTED_PERMISSION_ID = "drive_pending_expected_permission_id"
        internal const val KEY_PENDING_TARGET_EMAIL = "drive_pending_target_email"
        internal const val KEY_PENDING_RETRY_USED = "drive_pending_retry_used"
    }
}
