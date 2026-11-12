package com.androidbackup.app.testutils

import com.androidbackup.app.data.local.dao.SettingsDao
import com.androidbackup.app.data.local.entity.AppSettingsEntity
import com.androidbackup.app.data.local.entity.ExclusionPatternEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fake in-memory DAO che replica il comportamento atomico di SettingsDao per test isolati.
 */
class FakeSettingsDao : SettingsDao {
    private val settingsState = MutableStateFlow<AppSettingsEntity?>(null)
    private val patternsState = MutableStateFlow<List<ExclusionPatternEntity>>(emptyList())

    override fun observeSettings(id: Int): Flow<AppSettingsEntity?> = settingsState.asStateFlow()

    override suspend fun getSettings(id: Int): AppSettingsEntity? = settingsState.value

    override suspend fun upsertSettings(settings: AppSettingsEntity) {
        settingsState.value = settings
    }

    override suspend fun updateFrequency(frequency: String, timestamp: Long, id: Int): Int {
        val current = settingsState.value ?: return 0
        settingsState.value = current.copy(frequency = frequency, lastModifiedTimestamp = timestamp)
        return 1
    }

    override suspend fun updateNetworkPolicy(networkPolicy: String, timestamp: Long, id: Int): Int {
        val current = settingsState.value ?: return 0
        settingsState.value = current.copy(networkPolicy = networkPolicy, lastModifiedTimestamp = timestamp)
        return 1
    }

    override suspend fun updateReserveSpace(mb: Int, timestamp: Long, id: Int): Int {
        val current = settingsState.value ?: return 0
        settingsState.value = current.copy(reserveSpaceMb = mb, lastModifiedTimestamp = timestamp)
        return 1
    }

    override suspend fun updateFifoRotation(enabled: Boolean, timestamp: Long, id: Int): Int {
        val current = settingsState.value ?: return 0
        settingsState.value = current.copy(fifoRotationEnabled = enabled, lastModifiedTimestamp = timestamp)
        return 1
    }

    override suspend fun updateCloudOnlyEviction(enabled: Boolean, timestamp: Long, id: Int): Int {
        val current = settingsState.value ?: return 0
        settingsState.value = current.copy(allowCloudOnlyEviction = enabled, lastModifiedTimestamp = timestamp)
        return 1
    }

    override suspend fun updateRequireCharging(required: Boolean, timestamp: Long, id: Int): Int {
        val current = settingsState.value ?: return 0
        settingsState.value = current.copy(requireCharging = required, lastModifiedTimestamp = timestamp)
        return 1
    }

    override fun observePatterns(): Flow<List<ExclusionPatternEntity>> = patternsState.asStateFlow()

    override suspend fun getPatterns(): List<ExclusionPatternEntity> = patternsState.value

    override suspend fun insertPatterns(patterns: List<ExclusionPatternEntity>) {
        val current = patternsState.value.associateBy { it.pattern }.toMutableMap()
        patterns.forEach { current[it.pattern] = it }
        patternsState.value = current.values.sortedBy { it.position }
    }

    override suspend fun clearPatterns() {
        patternsState.value = emptyList()
    }

    override suspend fun deletePattern(pattern: String) {
        patternsState.value = patternsState.value.filter { it.pattern != pattern }
    }
}
