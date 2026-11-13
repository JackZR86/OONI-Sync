package com.androidbackup.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.androidbackup.app.data.local.entity.FileFingerprintEntity

/**
 * Data Access Object per la cache persistente delle impronte SHA-256 ([FileFingerprintEntity]).
 */
@Dao
interface FileFingerprintDao {

    @Query(
        """
        SELECT * FROM file_fingerprints
        WHERE backup_folder_id = :folderId AND document_id = :documentId
        LIMIT 1
        """
    )
    suspend fun getByFolderAndDocument(folderId: String, documentId: String): FileFingerprintEntity?

    @Query(
        """
        SELECT * FROM file_fingerprints
        WHERE backup_folder_id = :folderId AND document_id IN (:documentIds)
        """
    )
    suspend fun getByFolderAndDocumentIds(folderId: String, documentIds: List<String>): List<FileFingerprintEntity>

    @Query("SELECT * FROM file_fingerprints WHERE backup_folder_id = :folderId")
    suspend fun getByFolder(folderId: String): List<FileFingerprintEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFingerprint(entity: FileFingerprintEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFingerprints(entities: List<FileFingerprintEntity>)

    /**
     * Aggiorna i metadati osservati di posizione e ultima verifica metadati su cache hit
     * senza alterare [last_content_hashed_timestamp].
     */
    @Query(
        """
        UPDATE file_fingerprints
        SET document_uri = :documentUri,
            relative_path = :relativePath,
            last_seen_session_id = :sessionId,
            last_metadata_matched_timestamp = :matchedTimestamp
        WHERE backup_folder_id = :folderId AND document_id = :documentId
        """
    )
    suspend fun updateMetadataObservation(
        folderId: String,
        documentId: String,
        documentUri: String,
        relativePath: String,
        sessionId: String,
        matchedTimestamp: Long
    ): Int

    @Query("DELETE FROM file_fingerprints WHERE backup_folder_id = :folderId")
    suspend fun deleteByFolderId(folderId: String): Int

    /**
     * Rimuove i record obsoleti di una cartella non più presenti tra i file inclusi dell'inventario
     * della sessione indicata.
     *
     * Protezione atomica lato database: la cancellazione avviene esclusivamente se:
     * - la sessione [sessionId] esiste;
     * - `scan_phase = 'COMPLETED'`;
     * - `status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS')`;
     * - non esistono errori in `scan_errors` con `error_phase = 'DISCOVERY'` e `severity = 'ERROR'`
     *   per la coppia ([sessionId], [folderId]).
     *
     * Errori in fase `FINGERPRINTING`, segnalazioni `UNSUPPORTED`/`WARNING` o errori Discovery su altre
     * cartelle non bloccano la pulizia della cartella verificata. In assenza di tali condizioni
     * restituisce `0` senza eliminare alcun record.
     */
    @Query(
        """
        DELETE FROM file_fingerprints
        WHERE backup_folder_id = :folderId
          AND EXISTS (
              SELECT 1 FROM scan_sessions
              WHERE id = :sessionId
                AND scan_phase = 'COMPLETED'
                AND status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS')
          )
          AND NOT EXISTS (
              SELECT 1 FROM scan_errors
              WHERE scan_session_id = :sessionId
                AND backup_folder_id = :folderId
                AND error_phase = 'DISCOVERY'
                AND severity = 'ERROR'
          )
          AND document_id NOT IN (
              SELECT document_id FROM scanned_documents
              WHERE scan_session_id = :sessionId
                AND backup_folder_id = :folderId
                AND is_directory = 0
                AND is_excluded = 0
          )
        """
    )
    suspend fun deleteStaleForFolderNotInSession(folderId: String, sessionId: String): Int

    @Query("SELECT COUNT(*) FROM file_fingerprints WHERE backup_folder_id = :folderId")
    suspend fun countByFolder(folderId: String): Int

    @Query("DELETE FROM file_fingerprints")
    suspend fun clearAllFingerprints(): Int
}
