package com.androidbackup.app

import android.app.Application
import com.androidbackup.app.data.local.database.BackupDatabase
import com.androidbackup.app.data.repository.FolderRepository
import com.androidbackup.app.data.repository.ScanRepository
import com.androidbackup.app.data.repository.SettingsRepository
import com.androidbackup.app.data.saf.AndroidSafPermissionManager
import com.androidbackup.app.data.saf.AndroidSafTreeReader
import com.androidbackup.app.data.saf.SafPermissionManager
import com.androidbackup.app.data.saf.SafTreeReader
import com.androidbackup.app.data.scan.LocalFolderScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Applicazione principale per Android Backup.
 *
 * Conforme alla Specifica Fase 1B.2:
 * - Inizializza istanze singleton di BackupDatabase (v3), SettingsRepository, FolderRepository e ScanRepository
 * - Fornisce SafTreeReader e LocalFolderScanner per l'esplorazione gerarchica SAF
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

    val folderRepository: FolderRepository by lazy {
        FolderRepository(database.folderDao(), safPermissionManager)
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

    val scanRepository: ScanRepository by lazy {
        ScanRepository(
            scanSessionDao = database.scanSessionDao(),
            scannedDocumentDao = database.scannedDocumentDao(),
            pendingDirectoryDao = database.pendingDirectoryDao(),
            scanErrorDao = database.scanErrorDao(),
            scanner = localFolderScanner,
            folderRepository = folderRepository,
            settingsRepository = settingsRepository
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
