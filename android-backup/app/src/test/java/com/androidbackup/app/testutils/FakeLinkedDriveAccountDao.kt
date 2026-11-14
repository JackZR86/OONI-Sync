package com.androidbackup.app.testutils

import com.androidbackup.app.data.local.dao.LinkedDriveAccountDao
import com.androidbackup.app.data.local.entity.LinkedDriveAccountEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fake in-memory DAO che replica il comportamento singleton (`DEFAULT_ID = 1`) di [LinkedDriveAccountDao].
 */
class FakeLinkedDriveAccountDao(
    initialAccount: LinkedDriveAccountEntity? = null
) : LinkedDriveAccountDao {

    private val accountState = MutableStateFlow(initialAccount)

    var writeCount: Int = 0
        private set

    var deleteAllException: Exception? = null

    override fun observeAccount(id: Int): Flow<LinkedDriveAccountEntity?> = accountState.asStateFlow()

    override suspend fun getAccount(id: Int): LinkedDriveAccountEntity? =
        accountState.value?.takeIf { it.id == id }

    override suspend fun upsertAccount(account: LinkedDriveAccountEntity) {
        writeCount++
        accountState.value = account
    }

    override suspend fun updateVerificationOutcome(
        outcome: String,
        attemptAt: Long,
        errorMessage: String?,
        id: Int
    ): Int {
        val current = accountState.value ?: return 0
        if (current.id != id) return 0
        writeCount++
        accountState.value = current.copy(
            lastPersistedOutcome = outcome,
            lastVerificationAttemptAt = attemptAt,
            lastErrorMessage = errorMessage
        )
        return 1
    }

    override suspend fun deleteAll() {
        deleteAllException?.let { throw it }
        writeCount++
        accountState.value = null
    }
}
