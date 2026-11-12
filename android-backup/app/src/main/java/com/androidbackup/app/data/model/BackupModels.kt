package com.androidbackup.app.data.model

/**
 * Stati operativi del backup conformi alla Specifica 1.0
 */
enum class BackupStatus(val displayName: String, val description: String) {
    INATTIVO("Inattivo", "Backup automatico non attivo. Esecuzione manuale disponibile."),
    IN_ATTESA("In attesa", "Pianificato in background con WorkManager. In attesa di vincoli di rete/ricarica."),
    IN_CORSO("In corso", "Scansione differenziale e trasferimento sicuro verso Google Drive..."),
    COMPLETATO("Completato", "Ultima sincronizzazione terminata con successo senza errori bloccanti."),
    PARZIALE("Parziale", "Alcuni file sono stati esclusi o non sono risultati leggibili."),
    ERRORE("Errore", "Si è verificato un problema bloccante che richiede verifica manuale.")
}

/**
 * Priorità cartella: determina l'ordine di scansione e upload.
 * NOTA: Non influisce sulla protezione dalle cancellazioni (vincolo di sicurezza Specifica 1.0).
 */
enum class Priority(val label: String) {
    ALTA("Alta"),
    NORMALE("Normale"),
    BASSA("Bassa")
}

/**
 * Cartella sorgente locale da sottoporre a scansione unidirezionale
 */
data class BackupSource(
    val id: String,
    val name: String,
    val path: String,
    val priority: Priority = Priority.NORMALE,
    val protectFromEviction: Boolean = false, // "Proteggi dalle eliminazioni automatiche"
    val enabled: Boolean = true,
    val fileCount: Int = 0,
    val totalSizeMb: Long = 0,
    val lastScanned: String? = null,
    val isAccessible: Boolean = true
)

/**
 * Statistiche dettagliate dell'inventario file per una cartella (Fase 1B.2).
 */
data class FolderInventoryInfo(
    val folderId: String,
    val fileCount: Int = 0,
    val directoryCount: Int = 0,
    val totalKnownBytes: Long = 0L,
    val unknownSizeCount: Int = 0,
    val excludedCount: Int = 0
) {
    val totalSizeMb: Long
        get() = totalKnownBytes / (1024 * 1024)
}

/**
 * Vincolo di rete per l'esecuzione in background
 */
enum class NetworkPolicy(val label: String) {
    ONLY_WIFI("Solo Wi-Fi (Consigliato)"),
    ANY_NETWORK("Qualsiasi rete (inclusi dati mobili)")
}

/**
 * Frequenza di backup pianificato
 */
enum class BackupFrequency(val label: String) {
    TWELVE_HOURS("Ogni 12 ore"),
    TWENTY_FOUR_HOURS("Ogni 24 ore (Predefinito)"),
    FORTY_EIGHT_HOURS("Ogni 48 ore"),
    MANUAL_ONLY("Solo manuale")
}

/**
 * Regole e preferenze globali
 */
data class AppSettings(
    val frequency: BackupFrequency = BackupFrequency.TWENTY_FOUR_HOURS,
    val networkPolicy: NetworkPolicy = NetworkPolicy.ONLY_WIFI,
    val exclusions: List<String> = listOf("cache", "code_cache", ".thumbnails", "tmp", "*.tmp", "*.temp", "*.part"),
    val reserveSpaceMb: Int = 100,
    val fifoRotationEnabled: Boolean = false, // Disattivata per impostazione predefinita
    val allowCloudOnlyEviction: Boolean = false, // Copie cloud-only protette per impostazione predefinita
    val requireCharging: Boolean = false
)

/**
 * Stato della quota Google Drive (completamente separata dai mock con flag trasparente)
 */
data class GoogleStorageQuota(
    val totalBytes: Long = 15L * 1024 * 1024 * 1024, // 15 GB standard
    val usedBytes: Long = 0L,
    val appUsedBytes: Long = 0L,
    val trashBytes: Long = 0L,
    val isMock: Boolean = true
) {
    val usagePercentage: Float
        get() = if (totalBytes > 0) ((usedBytes.toFloat() / totalBytes) * 100f).coerceIn(0f, 100f) else 0f
}

/**
 * Singola sessione registrata nella Cronologia
 */
data class BackupRun(
    val id: String,
    val timestamp: String,
    val durationSeconds: Long,
    val status: BackupStatus,
    val filesChecked: Int,
    val filesUploaded: Int,
    val filesSkipped: Int,
    val bytesUploaded: Long,
    val bytesFreed: Long = 0L,
    val triggerType: String = "AUTOMATICO",
    val notes: String = ""
)

/**
 * Livelli dei log tecnici
 */
enum class LogLevel {
    ERRORE,
    INFO,
    DEBUG
}

/**
 * Voce del registro di log filtrabile
 */
data class BackupLog(
    val id: String,
    val timestamp: String,
    val level: LogLevel,
    val category: String,
    val message: String,
    val fileName: String? = null,
    val technicalCode: String? = null
)
