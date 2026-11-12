package com.androidbackup.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.androidbackup.app.data.local.entity.BackupFolderEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) dedicato alla persistenza e gestione delle cartelle SAF.
 *
 * Conforme alla Specifica Fase 1B.1:
 * - Osservazione reattiva tramite Flow
 * - Inserimento con vincolo di unicità
 * - Eliminazione per ID o per URI
 * - Aggiornamento mirato di priorità, protezione ed abilitazione
 * - Lettura puntuale o complessiva
 */
@Dao
interface FolderDao {

    @Query("SELECT * FROM backup_folders ORDER BY added_timestamp DESC")
    fun observeFolders(): Flow<List<BackupFolderEntity>>

    @Query("SELECT * FROM backup_folders ORDER BY added_timestamp DESC")
    suspend fun getFolders(): List<BackupFolderEntity>

    @Query("SELECT * FROM backup_folders WHERE id = :id LIMIT 1")
    suspend fun getFolderById(id: String): BackupFolderEntity?

    @Query("SELECT * FROM backup_folders WHERE tree_uri = :treeUri LIMIT 1")
    suspend fun getFolderByUri(treeUri: String): BackupFolderEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFolder(folder: BackupFolderEntity): Long

    @Query("DELETE FROM backup_folders WHERE id = :id")
    suspend fun deleteFolderById(id: String): Int

    @Query("DELETE FROM backup_folders WHERE tree_uri = :treeUri")
    suspend fun deleteFolderByUri(treeUri: String): Int

    @Query("UPDATE backup_folders SET priority = :priority WHERE id = :id")
    suspend fun updatePriority(id: String, priority: String): Int

    @Query("UPDATE backup_folders SET protect_from_eviction = :protect WHERE id = :id")
    suspend fun updateEvictionProtection(id: String, protect: Boolean): Int

    @Query("UPDATE backup_folders SET enabled = :enabled WHERE id = :id")
    suspend fun updateEnabled(id: String, enabled: Boolean): Int

    @Query("DELETE FROM backup_folders")
    suspend fun clearAllFolders(): Int
}
