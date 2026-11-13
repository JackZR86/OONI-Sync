package com.androidbackup.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Entità Room per la cache persistente delle impronte SHA-256 locali (Fase 1B.3).
 *
 * Scelte progettuali:
 * - Identità univoca composita su `(backup_folder_id, document_id)` per isolare le cartelle
 *   configurate ed evitare collisioni tra provider o selezioni sovrapposte.
 * - Distinzione esplicita tra [lastContentHashedTimestamp] (ultimo calcolo reale sullo stream,
 *   base per eventuali politiche TTL) e [lastMetadataMatchedTimestamp] (ultima conferma tramite metadati).
 * - Sopravvive alla pulizia storica delle sessioni di scansione.
 */
@Entity(
    tableName = "file_fingerprints",
    indices = [
        Index(
            value = ["backup_folder_id", "document_id"],
            unique = true,
            name = "index_file_fingerprints_folder_doc"
        ),
        Index(
            value = ["backup_folder_id"],
            name = "index_file_fingerprints_folder"
        )
    ]
)
data class FileFingerprintEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "backup_folder_id")
    val backupFolderId: String,

    @ColumnInfo(name = "document_id")
    val documentId: String,

    @ColumnInfo(name = "document_uri")
    val documentUri: String,

    @ColumnInfo(name = "relative_path")
    val relativePath: String,

    @ColumnInfo(name = "mime_type")
    val mimeType: String? = null,

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long? = null,

    @ColumnInfo(name = "last_modified_timestamp")
    val lastModifiedTimestamp: Long? = null,

    @ColumnInfo(name = "computed_bytes")
    val computedBytes: Long,

    @ColumnInfo(name = "sha256_hex")
    val sha256Hex: String,

    @ColumnInfo(name = "last_content_hashed_timestamp")
    val lastContentHashedTimestamp: Long,

    @ColumnInfo(name = "last_metadata_matched_timestamp")
    val lastMetadataMatchedTimestamp: Long,

    @ColumnInfo(name = "last_seen_session_id")
    val lastSeenSessionId: String
)
