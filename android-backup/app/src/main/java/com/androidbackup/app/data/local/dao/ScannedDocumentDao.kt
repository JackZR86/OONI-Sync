package com.androidbackup.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.androidbackup.app.data.local.entity.ScannedDocumentEntity
import kotlinx.coroutines.flow.Flow

/**
 * Riepilogo statistiche inventario per cartella principale.
 */
data class FolderInventoryStats(
    val folderId: String,
    val fileCount: Int,
    val directoryCount: Int,
    val totalKnownBytes: Long,
    val unknownSizeCount: Int,
    val excludedCount: Int
)

@Dao
interface ScannedDocumentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocuments(documents: List<ScannedDocumentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: ScannedDocumentEntity)

    @Query("SELECT * FROM scanned_documents WHERE scan_session_id = :sessionId ORDER BY relative_path ASC")
    suspend fun getDocumentsForSession(sessionId: String): List<ScannedDocumentEntity>

    @Query("SELECT COUNT(*) > 0 FROM scanned_documents WHERE scan_session_id = :sessionId AND backup_folder_id = :folderId AND document_id = :documentId")
    suspend fun isDocumentRecorded(sessionId: String, folderId: String, documentId: String): Boolean

    @Query("SELECT * FROM scanned_documents WHERE scan_session_id = :sessionId AND is_directory = 0 AND is_excluded = 0 ORDER BY relative_path ASC")
    suspend fun getIncludedFilesForSession(sessionId: String): List<ScannedDocumentEntity>

    @Query("SELECT * FROM scanned_documents WHERE scan_session_id = :sessionId ORDER BY relative_path ASC")
    fun observeDocumentsForSession(sessionId: String): Flow<List<ScannedDocumentEntity>>

    @Query("SELECT COUNT(*) FROM scanned_documents WHERE scan_session_id = :sessionId AND is_directory = 0 AND is_excluded = 0")
    suspend fun countFilesForSession(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM scanned_documents WHERE scan_session_id = :sessionId AND is_directory = 1 AND is_excluded = 0 AND relative_path != ''")
    suspend fun countDirectoriesForSession(sessionId: String): Int

    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM scanned_documents WHERE scan_session_id = :sessionId AND is_directory = 0 AND is_excluded = 0 AND size_bytes IS NOT NULL")
    suspend fun sumKnownBytesForSession(sessionId: String): Long

    @Query("SELECT COUNT(*) FROM scanned_documents WHERE scan_session_id = :sessionId AND is_directory = 0 AND is_excluded = 0 AND size_bytes IS NULL")
    suspend fun countUnknownSizeFilesForSession(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM scanned_documents WHERE scan_session_id = :sessionId AND is_excluded = 1")
    suspend fun countExcludedForSession(sessionId: String): Int

    // Conteggi per singola cartella di backup
    @Query("SELECT COUNT(*) FROM scanned_documents WHERE scan_session_id = :sessionId AND backup_folder_id = :folderId AND is_directory = 0 AND is_excluded = 0")
    suspend fun countFilesForFolder(sessionId: String, folderId: String): Int

    @Query("SELECT COUNT(*) FROM scanned_documents WHERE scan_session_id = :sessionId AND backup_folder_id = :folderId AND is_directory = 1 AND is_excluded = 0 AND relative_path != ''")
    suspend fun countDirectoriesForFolder(sessionId: String, folderId: String): Int

    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM scanned_documents WHERE scan_session_id = :sessionId AND backup_folder_id = :folderId AND is_directory = 0 AND is_excluded = 0 AND size_bytes IS NOT NULL")
    suspend fun sumKnownBytesForFolder(sessionId: String, folderId: String): Long

    @Query("SELECT COUNT(*) FROM scanned_documents WHERE scan_session_id = :sessionId AND backup_folder_id = :folderId AND is_directory = 0 AND is_excluded = 0 AND size_bytes IS NULL")
    suspend fun countUnknownSizeFilesForFolder(sessionId: String, folderId: String): Int

    @Query("SELECT COUNT(*) FROM scanned_documents WHERE scan_session_id = :sessionId AND backup_folder_id = :folderId AND is_excluded = 1")
    suspend fun countExcludedForFolder(sessionId: String, folderId: String): Int

    @Query("DELETE FROM scanned_documents WHERE scan_session_id = :sessionId")
    suspend fun deleteDocumentsBySession(sessionId: String): Int

    @Query("DELETE FROM scanned_documents WHERE scan_session_id NOT IN (:keepSessionIds)")
    suspend fun deleteDocumentsNotInSessions(keepSessionIds: List<String>): Int

    @Query("DELETE FROM scanned_documents")
    suspend fun clearAllDocuments(): Int
}
