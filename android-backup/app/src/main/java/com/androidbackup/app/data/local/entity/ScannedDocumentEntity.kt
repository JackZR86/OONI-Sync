package com.androidbackup.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Entità Room per la memorizzazione dell'inventario persistente dei file e cartelle individuati durante la scansione SAF.
 *
 * Conforme alla Specifica Fase 1B.2:
 * - Vincolo di unicità sulla combinazione (scan_session_id, backup_folder_id, document_id) per prevenire duplicati
 * - La stessa risorsa in due diverse cartelle configurate non crea collisioni
 * - Sola lettura metadati: nessun dato di contenuto salvato
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
    val isExcluded: Boolean = false
)
