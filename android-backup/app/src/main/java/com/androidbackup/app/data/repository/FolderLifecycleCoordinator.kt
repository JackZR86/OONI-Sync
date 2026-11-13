package com.androidbackup.app.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinatore condiviso per serializzare le operazioni mutuamente critiche sul ciclo di vita
 * delle cartelle configurate (avvio di una nuova sessione di scansione e rimozione cartella).
 *
 * Istanziato come singleton in [com.androidbackup.app.BackupApplication] e condiviso tra
 * [ScanRepository] e [FolderRepository] per prevenire race condition tra `startNewScan()`
 * e `removeFolder()` mantenendo una gerarchia di lock rigorosamente aciclica.
 */
class FolderLifecycleCoordinator {

    private val lifecycleMutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T {
        return lifecycleMutex.withLock {
            block()
        }
    }
}
