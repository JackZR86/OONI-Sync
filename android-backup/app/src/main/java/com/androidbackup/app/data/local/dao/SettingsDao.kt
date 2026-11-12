package com.androidbackup.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.androidbackup.app.data.local.entity.AppSettingsEntity
import com.androidbackup.app.data.local.entity.ExclusionPatternEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) per le impostazioni e i pattern di esclusione.
 * Conforme alla Specifica Fase 01A e revisione:
 * - Query reattive (Flow)
 * - Metodi suspend asincroni
 * - Query SQL UPDATE mirate per colonna che garantiscono l'atomicità ed evitano sovrascritture concorrenti
 * - Transazioni atomiche per il salvataggio atomico di impostazioni e pattern
 */
@Dao
interface SettingsDao {

    // ==========================================
    // LETTURA & OSSERVAZIONE IMPOSTAZIONI
    // ==========================================

    @Query("SELECT * FROM app_settings WHERE id = :id LIMIT 1")
    fun observeSettings(id: Int = AppSettingsEntity.DEFAULT_ID): Flow<AppSettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = :id LIMIT 1")
    suspend fun getSettings(id: Int = AppSettingsEntity.DEFAULT_ID): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(settings: AppSettingsEntity)

    // ==========================================
    // AGGIORNAMENTI ATOMICI PER SINGOLO CAMPO
    // ==========================================

    @Query("UPDATE app_settings SET frequency = :frequency, last_modified_timestamp = :timestamp WHERE id = :id")
    suspend fun updateFrequency(
        frequency: String,
        timestamp: Long = System.currentTimeMillis(),
        id: Int = AppSettingsEntity.DEFAULT_ID
    ): Int

    @Query("UPDATE app_settings SET network_policy = :networkPolicy, last_modified_timestamp = :timestamp WHERE id = :id")
    suspend fun updateNetworkPolicy(
        networkPolicy: String,
        timestamp: Long = System.currentTimeMillis(),
        id: Int = AppSettingsEntity.DEFAULT_ID
    ): Int

    @Query("UPDATE app_settings SET reserve_space_mb = :mb, last_modified_timestamp = :timestamp WHERE id = :id")
    suspend fun updateReserveSpace(
        mb: Int,
        timestamp: Long = System.currentTimeMillis(),
        id: Int = AppSettingsEntity.DEFAULT_ID
    ): Int

    @Query("UPDATE app_settings SET fifo_rotation_enabled = :enabled, last_modified_timestamp = :timestamp WHERE id = :id")
    suspend fun updateFifoRotation(
        enabled: Boolean,
        timestamp: Long = System.currentTimeMillis(),
        id: Int = AppSettingsEntity.DEFAULT_ID
    ): Int

    @Query("UPDATE app_settings SET allow_cloud_only_eviction = :enabled, last_modified_timestamp = :timestamp WHERE id = :id")
    suspend fun updateCloudOnlyEviction(
        enabled: Boolean,
        timestamp: Long = System.currentTimeMillis(),
        id: Int = AppSettingsEntity.DEFAULT_ID
    ): Int

    @Query("UPDATE app_settings SET require_charging = :required, last_modified_timestamp = :timestamp WHERE id = :id")
    suspend fun updateRequireCharging(
        required: Boolean,
        timestamp: Long = System.currentTimeMillis(),
        id: Int = AppSettingsEntity.DEFAULT_ID
    ): Int

    // ==========================================
    // PATTERN DI ESCLUSIONE (exclusion_patterns)
    // ==========================================

    @Query("SELECT * FROM exclusion_patterns ORDER BY position ASC")
    fun observePatterns(): Flow<List<ExclusionPatternEntity>>

    @Query("SELECT * FROM exclusion_patterns ORDER BY position ASC")
    suspend fun getPatterns(): List<ExclusionPatternEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPatterns(patterns: List<ExclusionPatternEntity>)

    @Query("DELETE FROM exclusion_patterns")
    suspend fun clearPatterns()

    @Query("DELETE FROM exclusion_patterns WHERE pattern = :pattern")
    suspend fun deletePattern(pattern: String)

    // ==========================================
    // TRANSAZIONI ATOMICHE
    // ==========================================

    /**
     * Sostituisce atomicamente l'intera lista dei pattern preservando la coerenza.
     */
    @Transaction
    suspend fun replacePatterns(patterns: List<ExclusionPatternEntity>) {
        clearPatterns()
        if (patterns.isNotEmpty()) {
            insertPatterns(patterns)
        }
    }

    /**
     * Salva sia le impostazioni globali sia la lista dei pattern in un'unica transazione atomica.
     */
    @Transaction
    suspend fun saveAll(
        settings: AppSettingsEntity,
        patterns: List<ExclusionPatternEntity>
    ) {
        upsertSettings(settings)
        replacePatterns(patterns)
    }

    /**
     * Inizializza i valori predefiniti solo se la tabella app_settings è vuota.
     */
    @Transaction
    suspend fun initializeDefaultsIfEmpty(
        defaultSettings: AppSettingsEntity,
        defaultPatterns: List<ExclusionPatternEntity>
    ) {
        val existing = getSettings(defaultSettings.id)
        if (existing == null) {
            upsertSettings(defaultSettings)
            clearPatterns()
            if (defaultPatterns.isNotEmpty()) {
                insertPatterns(defaultPatterns)
            }
        }
    }
}
