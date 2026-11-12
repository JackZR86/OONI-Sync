package com.androidbackup.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Stati possibili per una sessione di scansione locale (Fase 1B.2).
 */
enum class ScanSessionStatus {
    RUNNING,
    PAUSED,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    CANCELLED,
    FAILED
}

/**
 * Entità Room per la memorizzazione persistente di una sessione di scansione locale.
 *
 * Conforme alla Specifica Fase 1B.2:
 * - Identificativo univoco UUID.
 * - Timestamp di avvio e completamento.
 * - Stato della scansione ([ScanSessionStatus]).
 * - Statistiche aggregate: conteggio file, directory, byte conosciuti, file con dimensione sconosciuta, elementi esclusi ed errori.
 * - Messaggio di errore sintetico riepilogativo.
 */
@Entity(tableName = "scan_sessions")
data class ScanSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "started_timestamp")
    val startedTimestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "completed_timestamp")
    val completedTimestamp: Long? = null,

    @ColumnInfo(name = "status")
    val status: String = ScanSessionStatus.RUNNING.name,

    @ColumnInfo(name = "total_files")
    val totalFiles: Int = 0,

    @ColumnInfo(name = "total_directories")
    val totalDirectories: Int = 0,

    @ColumnInfo(name = "known_total_bytes")
    val knownTotalBytes: Long = 0L,

    @ColumnInfo(name = "unknown_size_files")
    val unknownSizeFiles: Int = 0,

    @ColumnInfo(name = "excluded_items")
    val excludedItems: Int = 0,

    @ColumnInfo(name = "error_count")
    val errorCount: Int = 0,

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null
) {
    val sessionStatus: ScanSessionStatus
        get() = try {
            ScanSessionStatus.valueOf(status)
        } catch (e: Exception) {
            ScanSessionStatus.FAILED
        }
}
