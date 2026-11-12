package com.androidbackup.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.androidbackup.app.data.model.BackupSource
import com.androidbackup.app.data.model.Priority
import java.util.UUID

/**
 * Entità Room per la persistenza delle cartelle selezionate dall'utente tramite Storage Access Framework (SAF).
 *
 * Conforme alla Specifica Fase 1B.1:
 * - Vincolo di unicità sull'URI SAF (tree_uri) per impedire inserimenti duplicati della stessa cartella.
 * - Non memorizza percorsi assoluti assumendo che siano filesystem path.
 * - Registra metadati essenziali: ID univoco, URI SAF, nome leggibile, autorità del provider,
 *   data/ora di aggiunta, priorità, protezione dalle eliminazioni automatiche e stato abilitato.
 */
@Entity(
    tableName = "backup_folders",
    indices = [
        Index(value = ["tree_uri"], unique = true)
    ]
)
data class BackupFolderEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "tree_uri")
    val treeUri: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "authority")
    val authority: String? = null,

    @ColumnInfo(name = "added_timestamp")
    val addedTimestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "priority")
    val priority: String = Priority.NORMALE.name,

    @ColumnInfo(name = "protect_from_eviction")
    val protectFromEviction: Boolean = false,

    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true
) {
    /**
     * Converte l'entità persistente nel modello di dominio [BackupSource].
     *
     * In conformità alla Fase 1B.1:
     * - Il conteggio file e la dimensione rimangono 0 (nessuna scansione o dato inventato).
     * - lastScanned indica che la scansione non è ancora stata eseguita.
     * - isAccessible riflette la disponibilità effettiva del permesso persistente SAF.
     */
    fun toDomainModel(isAccessible: Boolean = true): BackupSource {
        val parsedPriority = try {
            Priority.valueOf(priority)
        } catch (e: Exception) {
            Priority.NORMALE
        }

        return BackupSource(
            id = id,
            name = displayName,
            path = treeUri,
            priority = parsedPriority,
            protectFromEviction = protectFromEviction,
            enabled = enabled,
            fileCount = 0,
            totalSizeMb = 0L,
            lastScanned = null,
            isAccessible = isAccessible
        )
    }

    companion object {
        fun fromDomainModel(source: BackupSource, authority: String? = null): BackupFolderEntity {
            return BackupFolderEntity(
                id = source.id,
                treeUri = source.path,
                displayName = source.name,
                authority = authority,
                priority = source.priority.name,
                protectFromEviction = source.protectFromEviction,
                enabled = source.enabled
            )
        }
    }
}
