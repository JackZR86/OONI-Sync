package com.androidbackup.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.androidbackup.app.data.local.entity.PendingDirectoryEntity
import com.androidbackup.app.data.local.entity.ScanSessionEntity
import com.androidbackup.app.data.local.entity.ScannedDocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ScanSessionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPendingDirectories(directories: List<PendingDirectoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScannedDocuments(documents: List<ScannedDocumentEntity>)

    /**
     * Inizializza atomicamente in una singola transazione la sessione di scansione,
     * la coda iniziale di directory pendenti e le directory radice nell'inventario.
     */
    @Transaction
    suspend fun initializeSessionWithDirectories(
        session: ScanSessionEntity,
        pendingDirectories: List<PendingDirectoryEntity>,
        rootDocuments: List<ScannedDocumentEntity>
    ) {
        insertSession(session)
        if (rootDocuments.isNotEmpty()) {
            insertScannedDocuments(rootDocuments)
        }
        if (pendingDirectories.isNotEmpty()) {
            insertPendingDirectories(pendingDirectories)
        }
    }

    /**
     * Inserisce atomicamente in una singola transazione Room i documenti scansionati
     * e le nuove directory scoperte nella coda pendente per il batch corrente.
     * Se una delle due operazioni fallisce, l'intera transazione viene annullata via rollback,
     * garantendo che nessuna directory sia registrata nell'inventario senza essere accodata.
     */
    @Transaction
    suspend fun insertBatchAtomically(
        documents: List<ScannedDocumentEntity>,
        pendingDirectories: List<PendingDirectoryEntity>
    ) {
        if (documents.isNotEmpty()) {
            insertScannedDocuments(documents)
        }
        if (pendingDirectories.isNotEmpty()) {
            insertPendingDirectories(pendingDirectories)
        }
    }

    @Update
    suspend fun updateSession(session: ScanSessionEntity)

    @Query(
        """
        UPDATE scan_sessions 
        SET status = :status, 
            completed_timestamp = :completedTimestamp, 
            error_message = :errorMessage 
        WHERE id = :sessionId
        """
    )
    suspend fun updateStatus(
        sessionId: String,
        status: String,
        completedTimestamp: Long? = null,
        errorMessage: String? = null
    )

    @Query(
        """
        UPDATE scan_sessions
        SET total_files = :totalFiles,
            total_directories = :totalDirectories,
            known_total_bytes = :knownTotalBytes,
            unknown_size_files = :unknownSizeFiles,
            excluded_items = :excludedItems,
            error_count = :errorCount
        WHERE id = :sessionId
        """
    )
    suspend fun updateCounters(
        sessionId: String,
        totalFiles: Int,
        totalDirectories: Int,
        knownTotalBytes: Long,
        unknownSizeFiles: Int,
        excludedItems: Int,
        errorCount: Int
    )

    @Query("SELECT * FROM scan_sessions WHERE id = :id LIMIT 1")
    suspend fun getSessionById(id: String): ScanSessionEntity?

    @Query("SELECT * FROM scan_sessions ORDER BY started_timestamp DESC LIMIT 1")
    fun observeLatestSession(): Flow<ScanSessionEntity?>

    @Query("SELECT * FROM scan_sessions ORDER BY started_timestamp DESC LIMIT 1")
    suspend fun getLatestSession(): ScanSessionEntity?

    @Query("SELECT * FROM scan_sessions WHERE status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS') ORDER BY started_timestamp DESC LIMIT 1")
    suspend fun getLatestCompletedSession(): ScanSessionEntity?

    @Query("SELECT * FROM scan_sessions WHERE status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS') ORDER BY started_timestamp DESC LIMIT 1")
    fun observeLatestCompletedSession(): Flow<ScanSessionEntity?>

    @Query("SELECT * FROM scan_sessions WHERE status IN ('RUNNING', 'PAUSED') ORDER BY started_timestamp DESC LIMIT 1")
    suspend fun getRunningOrPausedSession(): ScanSessionEntity?

    /**
     * Recupera gli identificativi delle ultime sessioni completate (ordinamento per completamento/inizio decrescente).
     */
    @Query("SELECT id FROM scan_sessions WHERE status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS') ORDER BY COALESCE(completed_timestamp, started_timestamp) DESC LIMIT :limit")
    suspend fun getRecentCompletedSessionIds(limit: Int = 2): List<String>

    /**
     * Recupera gli identificativi di tutte le sessioni attualmente attive (RUNNING o PAUSED) da non eliminare.
     */
    @Query("SELECT id FROM scan_sessions WHERE status IN ('RUNNING', 'PAUSED')")
    suspend fun getActiveSessionIds(): List<String>

    @Query("DELETE FROM scan_sessions WHERE id NOT IN (:keepIds)")
    suspend fun deleteSessionsExcept(keepIds: List<String>): Int

    @Query("DELETE FROM scan_sessions WHERE id = :id")
    suspend fun deleteSessionById(id: String): Int

    @Query("DELETE FROM scan_sessions")
    suspend fun clearAllSessions(): Int
}
