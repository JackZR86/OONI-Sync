package com.androidbackup.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.androidbackup.app.data.local.entity.LinkedDriveAccountEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO Room per l'account Google Drive associato localmente (Fase 1C.1).
 * Utilizza esclusivamente `id = LinkedDriveAccountEntity.DEFAULT_ID (1)` a livello applicativo.
 */
@Dao
interface LinkedDriveAccountDao {

    @Query("SELECT * FROM linked_drive_account WHERE id = :id LIMIT 1")
    fun observeAccount(id: Int = LinkedDriveAccountEntity.DEFAULT_ID): Flow<LinkedDriveAccountEntity?>

    @Query("SELECT * FROM linked_drive_account WHERE id = :id LIMIT 1")
    suspend fun getAccount(id: Int = LinkedDriveAccountEntity.DEFAULT_ID): LinkedDriveAccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccount(account: LinkedDriveAccountEntity)

    @Query(
        """
        UPDATE linked_drive_account
        SET last_persisted_outcome = :outcome,
            last_verification_attempt_at = :attemptAt,
            last_error_message = :errorMessage
        WHERE id = :id
        """
    )
    suspend fun updateVerificationOutcome(
        outcome: String,
        attemptAt: Long,
        errorMessage: String?,
        id: Int = LinkedDriveAccountEntity.DEFAULT_ID
    ): Int

    @Query("DELETE FROM linked_drive_account")
    suspend fun deleteAll()

    /**
     * Sostituisce atomicamente l'associazione locale garantendo un solo record attivo con id = DEFAULT_ID.
     */
    @Transaction
    suspend fun replaceAccount(account: LinkedDriveAccountEntity) {
        deleteAll()
        upsertAccount(account.copy(id = LinkedDriveAccountEntity.DEFAULT_ID))
    }
}
