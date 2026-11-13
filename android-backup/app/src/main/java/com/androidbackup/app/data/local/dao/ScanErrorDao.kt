package com.androidbackup.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.androidbackup.app.data.local.entity.ScanErrorEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanErrorDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertError(error: ScanErrorEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertErrors(errors: List<ScanErrorEntity>)

    @Query("SELECT * FROM scan_errors WHERE scan_session_id = :sessionId ORDER BY timestamp DESC")
    suspend fun getErrorsForSession(sessionId: String): List<ScanErrorEntity>

    @Query("SELECT * FROM scan_errors WHERE scan_session_id = :sessionId AND severity = 'ERROR' ORDER BY timestamp DESC")
    suspend fun getEffectiveErrorsForSession(sessionId: String): List<ScanErrorEntity>

    @Query("SELECT * FROM scan_errors WHERE scan_session_id = :sessionId AND severity = 'UNSUPPORTED' ORDER BY timestamp DESC")
    suspend fun getUnsupportedForSession(sessionId: String): List<ScanErrorEntity>

    @Query("SELECT * FROM scan_errors WHERE scan_session_id = :sessionId ORDER BY timestamp DESC")
    fun observeErrorsForSession(sessionId: String): Flow<List<ScanErrorEntity>>

    @Query("SELECT COUNT(*) FROM scan_errors WHERE scan_session_id = :sessionId AND severity = 'ERROR'")
    suspend fun countErrorsForSession(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM scan_errors WHERE scan_session_id = :sessionId AND severity = :severity")
    suspend fun countIssuesBySeverity(sessionId: String, severity: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM scan_errors
        WHERE scan_session_id = :sessionId
          AND backup_folder_id = :folderId
          AND error_phase = 'DISCOVERY'
          AND severity = 'ERROR'
        """
    )
    suspend fun countDiscoveryErrorsForFolder(sessionId: String, folderId: String): Int

    @Query("DELETE FROM scan_errors WHERE scan_session_id = :sessionId")
    suspend fun deleteErrorsBySession(sessionId: String): Int

    @Query("DELETE FROM scan_errors WHERE scan_session_id NOT IN (:keepSessionIds)")
    suspend fun deleteErrorsNotInSessions(keepSessionIds: List<String>): Int

    @Query("DELETE FROM scan_errors")
    suspend fun clearAllErrors(): Int
}
