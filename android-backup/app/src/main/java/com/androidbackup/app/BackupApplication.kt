package com.androidbackup.app

import android.app.Application
import androidx.room.withTransaction
import com.androidbackup.app.data.local.database.BackupDatabase
import com.androidbackup.app.data.repository.FolderLifecycleCoordinator
import com.androidbackup.app.data.repository.FolderRepository
import com.androidbackup.app.data.repository.ScanRepository
import com.androidbackup.app.data.repository.SettingsRepository
import com.androidbackup.app.data.saf.AndroidSafContentReader
import com.androidbackup.app.data.saf.AndroidSafPermissionManager
import com.androidbackup.app.data.saf.AndroidSafTreeReader
import com.androidbackup.app.data.saf.SafContentReader
import com.androidbackup.app.data.saf.SafPermissionManager
import com.androidbackup.app.data.saf.SafTreeReader
import com.androidbackup.app.data.scan.LocalFileFingerprinter
import com.androidbackup.app.data.scan.LocalFolderScanner
import com.androidbackup.app.data.scan.Sha256StreamHasher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Applicazione principale per Android Backup.
 *
 * Conforme alla Specifica Fase 1B.2 - 1B.3C:
 * - Inizializza istanze singleton di BackupDatabase (v4), SettingsRepository, FolderRepository e ScanRepository
 * - Istanzia una sola volta [FolderLifecycleCoordinator] per serializzare avvio scansioni e rimozione cartelle
 * - Fornisce [SafTreeReader], [SafContentReader], [Sha256StreamHasher], [LocalFolderScanner] e [LocalFileFingerprinter]
 * - Inizializza impostazioni predefinite e controlla sessioni interrotte al lancio
 */
class BackupApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: BackupDatabase by lazy {
        BackupDatabase.getInstance(this)
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(database.settingsDao())
    }

    val safPermissionManager: SafPermissionManager by lazy {
        AndroidSafPermissionManager(this)
    }

    val safTreeReader: SafTreeReader by lazy {
        AndroidSafTreeReader(this)
    }

    val safContentReader: SafContentReader by lazy {
        AndroidSafContentReader(this)
    }

    val streamHasher: Sha256StreamHasher by lazy {
        Sha256StreamHasher()
    }

    val folderLifecycleCoordinator: FolderLifecycleCoordinator by lazy {
        FolderLifecycleCoordinator()
    }

    val folderRepository: FolderRepository by lazy {
        FolderRepository(
            folderDao = database.folderDao(),
            safPermissionManager = safPermissionManager,
            scanSessionDao = database.scanSessionDao(),
            fileFingerprintDao = database.fileFingerprintDao(),
            lifecycleCoordinator = folderLifecycleCoordinator,
            runInTransaction = { block -> database.withTransaction { block() } }
        )
    }

    val localFolderScanner: LocalFolderScanner by lazy {
        LocalFolderScanner(
            scanSessionDao = database.scanSessionDao(),
            scannedDocumentDao = database.scannedDocumentDao(),
            pendingDirectoryDao = database.pendingDirectoryDao(),
            scanErrorDao = database.scanErrorDao(),
            safTreeReader = safTreeReader,
            safPermissionManager = safPermissionManager
        )
    }

    val localFileFingerprinter: LocalFileFingerprinter by lazy {
        LocalFileFingerprinter(
            fileFingerprintDao = database.fileFingerprintDao(),
            scannedDocumentDao = database.scannedDocumentDao(),
            scanErrorDao = database.scanErrorDao(),
            safContentReader = safContentReader,
            safPermissionManager = safPermissionManager,
            streamHasher = streamHasher,
            runInTransaction = { block -> database.withTransaction { block() } }
        )
    }

    val scanRepository: ScanRepository by lazy {
        ScanRepository(
            scanSessionDao = database.scanSessionDao(),
            scannedDocumentDao = database.scannedDocumentDao(),
            pendingDirectoryDao = database.pendingDirectoryDao(),
            scanErrorDao = database.scanErrorDao(),
            fileFingerprintDao = database.fileFingerprintDao(),
            folderDao = database.folderDao(),
            scanner = localFolderScanner,
            fingerprinter = localFileFingerprinter,
            safPermissionManager = safPermissionManager,
            settingsRepository = settingsRepository,
            lifecycleCoordinator = folderLifecycleCoordinator,
            runInTransaction = { block -> database.withTransaction { block() } }
        )
    }

    override fun onCreate() {
        super.onCreate()

        applicationScope.launch {
            settingsRepository.initializeDefaultsIfEmpty()
            scanRepository.checkAndRecoverInterruptedSession()
        }
    }
}
