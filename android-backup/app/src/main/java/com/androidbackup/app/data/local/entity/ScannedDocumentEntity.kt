package com.androidbackup.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Stati di elaborazione della fingerprint SHA-256 per singolo documento nella sessione (Fase 1B.3).
 */
enum class FingerprintStatus {
    NOT_REQUIRED,
    PENDING,
    COMPUTED,
    REUSED_CACHE,
    UNSUPPORTED,
    FAILED
}

/**
 * Entità Room per la memorizzazione dell'inventario persistente dei file e cartelle individuati durante la scansione SAF.
 *
 * Conforme alla Specifica Fase 1B.2 e Fase 1B.3A:
 * - Vincolo di unicità sulla combinazione (scan_session_id, backup_folder_id, document_id) per prevenire duplicati
 * - La stessa risorsa in due diverse cartelle configurate non crea collisioni
 * - Supporto alle colonne di fingerprinting SHA-256 per la singola sessione
 * - Gestione corretta di dimensioni o date sconosciute (nullable)
 */
@Entity(
    tableName = "scanned_documents",
    indices = [
        Index(
            value = ["scan_session_id", "backup_folder_id", "document_id"],
            unique = true,
            name = "index_scanned_documents_session_folder_doc"
        ),
        Index(
            value = ["scan_session_id"],
            name = "index_scanned_documents_session"
        ),
        Index(
            value = ["backup_folder_id"],
            name = "index_scanned_documents_folder"
        ),
        Index(
            value = ["scan_session_id", "fingerprint_status", "is_directory", "is_excluded"],
            name = "index_scanned_documents_session_fp_status"
        )
    ]
)
data class ScannedDocumentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "scan_session_id")
    val scanSessionId: String,

    @ColumnInfo(name = "backup_folder_id")
    val backupFolderId: String,

    @ColumnInfo(name = "document_id")
    val documentId: String,

    @ColumnInfo(name = "document_uri")
    val documentUri: String,

    @ColumnInfo(name = "relative_path")
    val relativePath: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "mime_type")
    val mimeType: String? = null,

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long? = null,

    @ColumnInfo(name = "last_modified_timestamp")
    val lastModifiedTimestamp: Long? = null,

    @ColumnInfo(name = "is_directory")
    val isDirectory: Boolean = false,

    @ColumnInfo(name = "is_excluded")
    val isExcluded: Boolean = false,

    @ColumnInfo(name = "sha256_hex")
    val sha256Hex: String? = null,

    @ColumnInfo(name = "fingerprint_status", defaultValue = "'NOT_REQUIRED'")
    val fingerprintStatus: String = FingerprintStatus.NOT_REQUIRED.name,

    @ColumnInfo(name = "fingerprint_error")
    val fingerprintError: String? = null,

    @ColumnInfo(name = "fingerprint_timestamp")
    val fingerprintTimestamp: Long? = null,

    @ColumnInfo(name = "hashed_bytes_read")
    val hashedBytesRead: Long? = null
) {
    val parsedFingerprintStatus: FingerprintStatus
        get() = try {
            FingerprintStatus.valueOf(fingerprintStatus)
        } catch (e: Exception) {
            FingerprintStatus.FAILED
        }
}
