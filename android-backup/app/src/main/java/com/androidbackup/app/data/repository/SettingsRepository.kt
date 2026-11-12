package com.androidbackup.app.data.repository

import com.androidbackup.app.data.local.dao.SettingsDao
import com.androidbackup.app.data.local.entity.AppSettingsEntity
import com.androidbackup.app.data.local.entity.ExclusionPatternEntity
import com.androidbackup.app.data.model.AppSettings
import com.androidbackup.app.data.model.BackupFrequency
import com.androidbackup.app.data.model.NetworkPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Repository per la persistenza delle impostazioni globali dell'applicazione.
 * Conforme alla Specifica Fase 01A e revisione:
 * - Unico punto di accesso per ViewModel alle impostazioni persistite
 * - Gestione precisa della lista esclusioni vuota: distingue DB non inizializzato da DB con 0 esclusioni
 * - Aggiornamenti atomici per colonna e Mutex di serializzazione per prevenire perdita di modifiche concorrenti
 * - Esegue scritture asincrone su Dispatchers.IO
 * - Propaga e gestisce errori in modo esplicito tramite Result<Unit>
 */
class SettingsRepository(
    private val settingsDao: SettingsDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val defaultSettings = AppSettings()
    private val writeMutex = Mutex()

    /**
     * Flusso reattivo delle impostazioni globali e dei pattern ordinati.
     * Gestione delle esclusioni:
     * - Database non ancora inizializzato (settingsEntity == null): utilizza i valori predefiniti.
     * - Database inizializzato: utilizza esattamente i pattern salvati (anche se lista vuota!).
     */
    val settingsFlow: Flow<AppSettings> = combine(
        settingsDao.observeSettings(),
        settingsDao.observePatterns()
    ) { settingsEntity, patternEntities ->
        if (settingsEntity == null) {
            defaultSettings
        } else {
            val exclusions = patternEntities.map { it.pattern }
            settingsEntity.toDomainModel(exclusions)
        }
    }.flowOn(ioDispatcher)

    /**
     * Inizializza atomicamente i valori predefiniti solo se il database è privo di record.
     */
    suspend fun initializeDefaultsIfEmpty(): Result<Unit> = withContext(ioDispatcher) {
        writeMutex.withLock {
            runCatching {
                val defaultEntity = AppSettingsEntity.defaultEntity()
                val defaultPatterns = ExclusionPatternEntity.normalizeList(defaultSettings.exclusions)
                settingsDao.initializeDefaultsIfEmpty(defaultEntity, defaultPatterns)
            }
        }
    }

    /**
     * Salva atomicamente sia le impostazioni sia la lista dei pattern di esclusione.
     */
    suspend fun updateSettings(settings: AppSettings): Result<Unit> = withContext(ioDispatcher) {
        writeMutex.withLock {
            runCatching {
                val normalizedPatterns = ExclusionPatternEntity.normalizeList(settings.exclusions)
                val entity = AppSettingsEntity.fromDomainModel(settings)
                settingsDao.saveAll(entity, normalizedPatterns)
            }
        }
    }

    /**
     * Aggiorna atomicamente la frequenza in modo serializzato con writeMutex.
     */
    suspend fun updateFrequency(frequency: BackupFrequency): Result<Unit> = withContext(ioDispatcher) {
        writeMutex.withLock {
            runCatching {
                ensureInitializedInternal()
                settingsDao.updateFrequency(frequency.name)
                Unit
            }
        }
    }

    /**
     * Aggiorna atomicamente la policy di rete in modo serializzato con writeMutex.
     */
    suspend fun updateNetworkPolicy(policy: NetworkPolicy): Result<Unit> = withContext(ioDispatcher) {
        writeMutex.withLock {
            runCatching {
                ensureInitializedInternal()
                settingsDao.updateNetworkPolicy(policy.name)
                Unit
            }
        }
    }

    /**
     * Aggiorna atomicamente il vincolo di ricarica in modo serializzato con writeMutex.
     */
    suspend fun updateRequireCharging(required: Boolean): Result<Unit> = withContext(ioDispatcher) {
        writeMutex.withLock {
            runCatching {
                ensureInitializedInternal()
                settingsDao.updateRequireCharging(required)
                Unit
            }
        }
    }

    /**
     * Aggiorna atomicamente la rotazione FIFO in modo serializzato con writeMutex.
     */
    suspend fun updateFifoRotation(enabled: Boolean): Result<Unit> = withContext(ioDispatcher) {
        writeMutex.withLock {
            runCatching {
                ensureInitializedInternal()
                settingsDao.updateFifoRotation(enabled)
                Unit
            }
        }
    }

    /**
     * Aggiorna atomicamente l'autorizzazione alle rimozioni cloud-only in modo serializzato con writeMutex.
     */
    suspend fun updateCloudOnlyEviction(enabled: Boolean): Result<Unit> = withContext(ioDispatcher) {
        writeMutex.withLock {
            runCatching {
                ensureInitializedInternal()
                settingsDao.updateCloudOnlyEviction(enabled)
                Unit
            }
        }
    }

    /**
     * Aggiorna atomicamente la riserva di spazio in MB in modo serializzato con writeMutex.
     */
    suspend fun updateReserveSpace(reserveSpaceMb: Int): Result<Unit> = withContext(ioDispatcher) {
        writeMutex.withLock {
            runCatching {
                ensureInitializedInternal()
                settingsDao.updateReserveSpace(reserveSpaceMb)
                Unit
            }
        }
    }

    /**
     * Aggiunge un pattern in modo serializzato e atomico.
     */
    suspend fun addExclusionPattern(rawPattern: String): Result<Unit> = withContext(ioDispatcher) {
        writeMutex.withLock {
            runCatching {
                ensureInitializedInternal()
                val cleaned = rawPattern.trim()
                if (cleaned.isBlank()) return@runCatching

                val existing = settingsDao.getPatterns().map { it.pattern }
                if (cleaned in existing) return@runCatching

                val updatedList = existing + cleaned
                val normalizedEntities = ExclusionPatternEntity.normalizeList(updatedList)
                settingsDao.replacePatterns(normalizedEntities)
            }
        }
    }

    /**
     * Rimuove un pattern in modo serializzato e atomico.
     * Se la lista diventa vuota, la tabella exclusion_patterns rimane vuota.
     */
    suspend fun removeExclusionPattern(pattern: String): Result<Unit> = withContext(ioDispatcher) {
        writeMutex.withLock {
            runCatching {
                ensureInitializedInternal()
                val existing = settingsDao.getPatterns().map { it.pattern }
                val updatedList = existing.filter { it != pattern }
                val normalizedEntities = ExclusionPatternEntity.normalizeList(updatedList)
                settingsDao.replacePatterns(normalizedEntities)
            }
        }
    }

    /**
     * Rimuove tutte le esclusioni, lasciando la lista deliberatamente vuota nel DB.
     */
    suspend fun clearAllExclusions(): Result<Unit> = withContext(ioDispatcher) {
        writeMutex.withLock {
            runCatching {
                ensureInitializedInternal()
                settingsDao.clearPatterns()
            }
        }
    }

    /**
     * Ripristina le impostazioni e i pattern ai valori predefiniti di fabbrica.
     */
    suspend fun resetToDefaults(): Result<Unit> = withContext(ioDispatcher) {
        writeMutex.withLock {
            runCatching {
                val defaultEntity = AppSettingsEntity.defaultEntity()
                val defaultPatterns = ExclusionPatternEntity.normalizeList(defaultSettings.exclusions)
                settingsDao.saveAll(defaultEntity, defaultPatterns)
            }
        }
    }

    /**
     * Assicura che la riga id=1 e i pattern iniziali siano presenti prima di aggiornamenti parziali.
     */
    private suspend fun ensureInitializedInternal() {
        if (settingsDao.getSettings() == null) {
            val defaultEntity = AppSettingsEntity.defaultEntity()
            val defaultPatterns = ExclusionPatternEntity.normalizeList(defaultSettings.exclusions)
            settingsDao.initializeDefaultsIfEmpty(defaultEntity, defaultPatterns)
        }
    }

    suspend fun getCurrentSettings(): AppSettings = withContext(ioDispatcher) {
        val entity = settingsDao.getSettings()
        if (entity == null) {
            defaultSettings
        } else {
            val patterns = settingsDao.getPatterns().map { it.pattern }
            entity.toDomainModel(patterns)
        }
    }
}
