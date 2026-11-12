package com.androidbackup.app.data.saf

import android.net.Uri

/**
 * Interfaccia per la gestione delle autorizzazioni persistenti Storage Access Framework (SAF).
 *
 * Conforme alla Specifica Fase 1B.1 (Vincoli di Sicurezza):
 * - Accesso esclusivamente in lettura tramite FLAG_GRANT_READ_URI_PERMISSION.
 * - Nessuna richiesta o detenzione di permessi di scrittura.
 * - Nessuna operazione di cancellazione o modifica file.
 * - Isola la gestione del ContentResolver per consentire test unitari affidabili e riproducibili.
 */
interface SafPermissionManager {

    /**
     * Acquisisce il permesso persistente in sola lettura per l'URI dell'albero selezionato.
     * Utilizza rigorosamente ed esclusivamente FLAG_GRANT_READ_URI_PERMISSION.
     */
    fun takePersistableReadPermission(uri: Uri): Result<Unit>

    /**
     * Rilascia il permesso persistente in sola lettura quando una cartella viene rimossa dalla configurazione.
     */
    fun releasePersistableReadPermission(uri: Uri): Result<Unit>

    /**
     * Verifica se per l'URI specificato esiste attualmente un permesso persistente in lettura attivo.
     */
    fun hasPersistedReadPermission(uri: Uri): Boolean

    /**
     * Recupera il nome visualizzato leggibile della cartella (es. "Camera", "Documenti")
     * interrogando il provider di documenti SAF senza assumere che sia un filesystem path.
     */
    fun getFolderDisplayName(uri: Uri): String

    /**
     * Restituisce l'insieme di tutti gli URI con permesso persistente in lettura attualmente concessi.
     */
    fun getPersistedReadUris(): Set<Uri>
}
