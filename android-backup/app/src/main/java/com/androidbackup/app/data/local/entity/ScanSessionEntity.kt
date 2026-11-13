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
 * Fasi della pipeline di scansione e fingerprinting (Fase 1B.3).
 */
enum class ScanPhase {
    DISCOVERY,
    FINGERPRINTING,
    COMPLETED
}

/**
 * Modalità di esecuzione del calcolo delle impronte SHA-256 (Fase 1B.3).
 */
enum class FingerprintMode {
    INCREMENTAL,
    FORCE_RECOMPUTE,
    PERIODIC_REVERIFY
}

/**
 * Entità Room per la memorizzazione persistente di una sessione di scansione locale.
 *
 * Conforme alla Specifica Fase 1B.2 e Fase 1B.3A:
 * - Identificativo univoco UUID.
 * - Timestamp di avvio e completamento.
 * - Stato della scansione ([ScanSessionStatus]), fase corrente ([ScanPhase]) e modalità fingerprinting ([FingerprintMode]).
 * - Statistiche aggregate di discovery e fingerprinting.
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
    val errorMessage: String? = null,

    @ColumnInfo(name = "scan_phase", defaultValue = "'COMPLETED'")
    val scanPhase: String = ScanPhase.DISCOVERY.name,

    @ColumnInfo(name = "fingerprint_mode", defaultValue = "'INCREMENTAL'")
    val fingerprintMode: String = FingerprintMode.INCREMENTAL.name,

    @ColumnInfo(name = "fingerprint_max_cache_age_ms")
    val fingerprintMaxCacheAgeMs: Long? = null,

    @ColumnInfo(name = "fingerprints_total", defaultValue = "0")
    val fingerprintsTotal: Int = 0,

    @ColumnInfo(name = "fingerprints_computed", defaultValue = "0")
    val fingerprintsComputed: Int = 0,

    @ColumnInfo(name = "fingerprints_cached", defaultValue = "0")
    val fingerprintsCached: Int = 0,

    @ColumnInfo(name = "fingerprints_unsupported", defaultValue = "0")
    val fingerprintsUnsupported: Int = 0,

    @ColumnInfo(name = "fingerprints_failed", defaultValue = "0")
    val fingerprintsFailed: Int = 0,

    @ColumnInfo(name = "fingerprints_bytes_hashed", defaultValue = "0")
    val fingerprintsBytesHashed: Long = 0L
) {
    val sessionStatus: ScanSessionStatus
        get() = try {
            ScanSessionStatus.valueOf(status)
        } catch (e: Exception) {
            ScanSessionStatus.FAILED
        }

    val parsedScanPhase: ScanPhase
        get() = try {
            ScanPhase.valueOf(scanPhase)
        } catch (e: Exception) {
            ScanPhase.COMPLETED
        }

    val parsedFingerprintMode: FingerprintMode
        get() = try {
            FingerprintMode.valueOf(fingerprintMode)
        } catch (e: Exception) {
            FingerprintMode.INCREMENTAL
        }
}
