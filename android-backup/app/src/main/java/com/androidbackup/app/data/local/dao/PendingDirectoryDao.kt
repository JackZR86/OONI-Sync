package com.androidbackup.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.androidbackup.app.data.local.entity.PendingDirectoryEntity

@Dao
interface PendingDirectoryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDirectories(directories: List<PendingDirectoryEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDirectory(directory: PendingDirectoryEntity)

    @Query("SELECT * FROM pending_directories WHERE scan_session_id = :sessionId ORDER BY enqueue_timestamp ASC LIMIT 1")
    suspend fun getNextPendingDirectory(sessionId: String): PendingDirectoryEntity?

    @Query("DELETE FROM pending_directories WHERE id = :id")
    suspend fun deletePendingDirectoryById(id: String): Int

    @Query("SELECT COUNT(*) FROM pending_directories WHERE scan_session_id = :sessionId")
    suspend fun countPendingForSession(sessionId: String): Int

    @Query("SELECT * FROM pending_directories WHERE scan_session_id = :sessionId ORDER BY enqueue_timestamp ASC")
    suspend fun getPendingDirectories(sessionId: String): List<PendingDirectoryEntity>

    @Query("DELETE FROM pending_directories WHERE scan_session_id = :sessionId")
    suspend fun clearPendingForSession(sessionId: String): Int

    @Query("DELETE FROM pending_directories WHERE scan_session_id NOT IN (:keepSessionIds)")
    suspend fun deletePendingNotInSessions(keepSessionIds: List<String>): Int

    @Query("DELETE FROM pending_directories")
    suspend fun clearAllPending(): Int
}
