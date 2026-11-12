package com.androidbackup.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Entità Room per la registrazione persistente degli errori riscontrati durante la scansione SAF.
 *
 * Conforme alla Specifica Fase 1B.2:
 * - Traccia errori specifici senza bloccare la scansione delle altre cartelle o elementi
 * - Non espone o registra informazioni sensibili private
 */
@Entity(
    tableName = "scan_errors",
    indices = [
        Index(
            value = ["scan_session_id"],
            name = "index_scan_errors_session"
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
    val timestamp: Long = System.currentTimeMillis()
)
