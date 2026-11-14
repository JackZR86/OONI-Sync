package com.androidbackup.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.androidbackup.app.data.model.LinkedDriveAccount
import com.androidbackup.app.data.model.PersistedDriveCheckOutcome

/**
 * Entità Room per la persistenza dell'account Google Drive associato localmente (Fase 1C.1).
 *
 * Vincoli:
 * - Nessun token, refresh token, serverAuthCode o segreto salvato nel database.
 * - Comportamento singleton a livello applicativo garantito tramite uso esclusivo di `id = DEFAULT_ID (1)`
 *   in DAO e Repository (la sola PRIMARY KEY SQLite non impedisce tecnicamente ID diversi).
 * - I valori enum sono salvati come stringhe esplicite in colonna SQL TEXT.
 */
@Entity(tableName = "linked_drive_account")
data class LinkedDriveAccountEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int = DEFAULT_ID,

    @ColumnInfo(name = "permission_id")
    val permissionId: String,

    @ColumnInfo(name = "email_address")
    val emailAddress: String?,

    @ColumnInfo(name = "display_name")
    val displayName: String?,

    @ColumnInfo(name = "granted_scope")
    val grantedScope: String,

    @ColumnInfo(name = "linked_at")
    val linkedAt: Long,

    @ColumnInfo(name = "last_verified_at")
    val lastVerifiedAt: Long,

    @ColumnInfo(name = "last_persisted_outcome")
    val lastPersistedOutcome: String,

    @ColumnInfo(name = "last_verification_attempt_at")
    val lastVerificationAttemptAt: Long?,

    @ColumnInfo(name = "last_error_message")
    val lastErrorMessage: String?
) {
    fun toDomainModel(): LinkedDriveAccount {
        val parsedOutcome = try {
            PersistedDriveCheckOutcome.valueOf(lastPersistedOutcome)
        } catch (_: IllegalArgumentException) {
            safeLogWarn(
                TAG,
                "Esito persistito '$lastPersistedOutcome' non riconosciuto. Fallback a ${PersistedDriveCheckOutcome.LAST_CHECK_FAILED.name}."
            )
            PersistedDriveCheckOutcome.LAST_CHECK_FAILED
        }

        return LinkedDriveAccount(
            permissionId = permissionId,
            emailAddress = emailAddress?.takeIf { it.isNotBlank() },
            displayName = displayName?.takeIf { it.isNotBlank() },
            grantedScope = grantedScope,
            linkedAt = linkedAt,
            lastVerifiedAt = lastVerifiedAt,
            lastPersistedOutcome = parsedOutcome,
            lastVerificationAttemptAt = lastVerificationAttemptAt,
            lastErrorMessage = lastErrorMessage
        )
    }

    companion object {
        const val DEFAULT_ID = 1
        private const val TAG = "LinkedDriveAccountEntity"

        fun fromDomainModel(account: LinkedDriveAccount, id: Int = DEFAULT_ID): LinkedDriveAccountEntity {
            return LinkedDriveAccountEntity(
                id = id,
                permissionId = account.permissionId,
                emailAddress = account.emailAddress?.takeIf { it.isNotBlank() },
                displayName = account.displayName?.takeIf { it.isNotBlank() },
                grantedScope = account.grantedScope,
                linkedAt = account.linkedAt,
                lastVerifiedAt = account.lastVerifiedAt,
                lastPersistedOutcome = account.lastPersistedOutcome.name,
                lastVerificationAttemptAt = account.lastVerificationAttemptAt,
                lastErrorMessage = account.lastErrorMessage
            )
        }

        private fun safeLogWarn(tag: String, message: String) {
            try {
                android.util.Log.w(tag, message)
            } catch (_: Throwable) {
                System.err.println("WARN: [$tag] $message")
            }
        }
    }
}
