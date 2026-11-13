package com.androidbackup.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Fase della pipeline in cui si è verificata la segnalazione (Fase 1B.3).
 */
enum class ScanErrorPhase {
    DISCOVERY,
    FINGERPRINTING
}

/**
 * Severità della segnalazione registrata in [ScanErrorEntity] (Fase 1B.3).
 * Permette di distinguere errori effettivi di elaborazione, documenti virtuali non supportati
 * e avvisi informativi senza alterare i conteggi di errore.
 */
enum class ScanErrorSeverity {
    ERROR,
    UNSUPPORTED,
    WARNING
}

/**
 * Entità Room per la registrazione persistente degli errori e segnalazioni riscontrate durante la scansione e il fingerprinting SAF.
 *
 * Conforme alla Specifica Fase 1B.2 e Fase 1B.3A:
 * - Traccia errori specifici senza bloccare la scansione delle altre cartelle o elementi
 * - Distingue fase ([errorPhase]) e severità ([severity])
 * - Non espone o registra informazioni sensibili private
 */
@Entity(
    tableName = "scan_errors",
    indices = [
        Index(
            value = ["scan_session_id"],
            name = "index_scan_errors_session"
        ),
        Index(
            value = ["scan_session_id", "severity", "error_phase"],
            name = "index_scan_errors_session_severity_phase"
        )
    ]
)
data class ScanErrorEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "scan_session_id")
    val scanSessionId: String,

    @ColumnInfo(name = "backup_folder_id")
    val backupFolderId: String,

    @ColumnInfo(name = "document_id")
    val documentId: String? = null,

    @ColumnInfo(name = "uri")
    val uri: String? = null,

    @ColumnInfo(name = "error_type")
    val errorType: String,

    @ColumnInfo(name = "message")
    val message: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "error_phase", defaultValue = "'DISCOVERY'")
    val errorPhase: String = ScanErrorPhase.DISCOVERY.name,

    @ColumnInfo(name = "severity", defaultValue = "'ERROR'")
    val severity: String = ScanErrorSeverity.ERROR.name
) {
    val parsedErrorPhase: ScanErrorPhase
        get() = try {
            ScanErrorPhase.valueOf(errorPhase)
        } catch (e: Exception) {
            ScanErrorPhase.DISCOVERY
        }

    val parsedSeverity: ScanErrorSeverity
        get() = try {
            ScanErrorSeverity.valueOf(severity)
        } catch (e: Exception) {
            ScanErrorSeverity.ERROR
        }
}
