package com.androidbackup.app.data.scan

/**
 * Eccezione specifica sollevata quando si verifica un errore durante le operazioni di persistenza
 * del database Room (es. fallimento della transazione atomica dei batch, vincoli SQLite o problemi I/O del DB).
 *
 * Consente al repository di distinguere un errore temporaneo di persistenza da errori fatali o da errori SAF:
 * in caso di [ScanPersistenceException], la sessione viene portata in stato PAUSED (e non FAILED),
 * la coda pendente viene integralmente conservata e l'utente o il sistema possono successivamente
 * riprendere la scansione tramite [com.androidbackup.app.data.repository.ScanRepository.resumeScan].
 */
class ScanPersistenceException(
    message: String,
    cause: Exception? = null
) : Exception(message, cause)
