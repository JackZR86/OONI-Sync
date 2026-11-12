package com.androidbackup.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Entità Room per la gestione persistente della coda di esplorazione ricorsiva delle directory (Fase 1B.2).
 *
 * Conforme alla Specifica Fase 1B.2:
 * - Resiliente a interruzioni di processo o riavvii dell'app.
 * - Indice univoco per evitare duplicati nella coda.
 * - Rimossa dalla coda man mano che ogni directory viene completata.
 */
@Entity(
    tableName = "pending_directories",
    indices = [
        Index(
            value = ["scan_session_id", "backup_folder_id", "document_id"],
            unique = true,
            name = "index_pending_directories_session_folder_doc"
        ),
        Index(
            value = ["scan_session_id"],
            name = "index_pending_directories_session"
        )
    ]
)
data class PendingDirectoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "scan_session_id")
    val scanSessionId: String,

    @ColumnInfo(name = "backup_folder_id")
    val backupFolderId: String,

    @ColumnInfo(name = "tree_uri")
    val treeUri: String,

    @ColumnInfo(name = "document_id")
    val documentId: String,

    @ColumnInfo(name = "relative_path")
    val relativePath: String,

    @ColumnInfo(name = "enqueue_timestamp")
    val enqueueTimestamp: Long = System.currentTimeMillis()
)
