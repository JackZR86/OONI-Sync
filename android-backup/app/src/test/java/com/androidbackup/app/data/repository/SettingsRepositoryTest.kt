package com.androidbackup.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.androidbackup.app.data.local.dao.SettingsDao
import com.androidbackup.app.data.local.database.BackupDatabase
import com.androidbackup.app.data.local.entity.AppSettingsEntity
import com.androidbackup.app.data.local.entity.ExclusionPatternEntity
import com.androidbackup.app.data.model.AppSettings
import com.androidbackup.app.data.model.BackupFrequency
import com.androidbackup.app.data.model.NetworkPolicy
import com.androidbackup.app.testutils.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Test di SettingsRepository eseguiti su VERO database Room SQLite in-memory (Robolectric).
 * Verifica le operazioni di lettura, persistenza, svuotamento esclusioni, atomicità
 * e la concorrenza serializzata tramite writeMutex (sia tra update parziali, sia con saveAll).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private lateinit var database: BackupDatabase
    private lateinit var settingsDao: SettingsDao
    private lateinit var repository: SettingsRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BackupDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settingsDao = database.settingsDao()
        repository = SettingsRepository(settingsDao, ioDispatcher = testDispatcher)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun test1_emptyDatabaseReturnsDefaultSettings() = runTest(testDispatcher) {
        val emitted = repository.settingsFlow.first()

        assertEquals(BackupFrequency.TWENTY_FOUR_HOURS, emitted.frequency)
        assertEquals(NetworkPolicy.ONLY_WIFI, emitted.networkPolicy)
        assertEquals(100, emitted.reserveSpaceMb)
        assertFalse(emitted.fifoRotationEnabled)
        assertFalse(emitted.allowCloudOnlyEviction)
        assertFalse(emitted.requireCharging)
        assertTrue(emitted.exclusions.contains("cache"))
    }

    @Test
    fun test2_savedSettingsAreReadCorrectly() = runTest(testDispatcher) {
        val customSettings = AppSettings(
            frequency = BackupFrequency.TWELVE_HOURS,
            networkPolicy = NetworkPolicy.ANY_NETWORK,
            reserveSpaceMb = 250,
            fifoRotationEnabled = true,
            allowCloudOnlyEviction = true,
            requireCharging = true,
            exclusions = listOf("custom_folder", "temp_files")
        )

        val saveResult = repository.updateSettings(customSettings)
        assertTrue("Il salvataggio deve avere successo", saveResult.isSuccess)

        val readSettings = repository.settingsFlow.first()

        assertEquals(BackupFrequency.TWELVE_HOURS, readSettings.frequency)
        assertEquals(NetworkPolicy.ANY_NETWORK, readSettings.networkPolicy)
        assertEquals(250, readSettings.reserveSpaceMb)
        assertTrue(readSettings.fifoRotationEnabled)
        assertTrue(readSettings.allowCloudOnlyEviction)
        assertTrue(readSettings.requireCharging)
        assertEquals(listOf("custom_folder", "temp_files"), readSettings.exclusions)
    }

    @Test
    fun test3_emptyExclusionsListPreservedAfterClearAll() = runTest(testDispatcher) {
        // 1. Inizializza il database Room reale con le impostazioni e i pattern di default
        repository.initializeDefaultsIfEmpty()
        val initial = repository.settingsFlow.first()
        assertTrue("Inizialmente ci sono esclusioni", initial.exclusions.isNotEmpty())

        // 2. Rimuove tutte le esclusioni
        val clearResult = repository.clearAllExclusions()
        assertTrue(clearResult.isSuccess)

        // 3. Nuova lettura dal database Room tramite Flow
        val afterClear = repository.settingsFlow.first()
        assertTrue(
            "La lista delle esclusioni DEVE rimanere vuota e NON essere sostituita dai default di fabbrica",
            afterClear.exclusions.isEmpty()
        )

        // 4. Nuova lettura diretta tramite getCurrentSettings su SQLite
        val currentDirect = repository.getCurrentSettings()
        assertTrue(
            "getCurrentSettings deve confermare la lista vuota nel database inizializzato",
            currentDirect.exclusions.isEmpty()
        )
    }

    @Test
    fun test4_rapidSuccessiveUpdatesDoNotOverwriteEachOther() = runTest(testDispatcher) {
        // Inizializza la riga del database Room in-memory
        repository.initializeDefaultsIfEmpty()

        // Esegui tre modifiche su campi diversi in rapida successione/concorrenza su SQLite
        val job1 = async { repository.updateFrequency(BackupFrequency.FORTY_EIGHT_HOURS) }
        val job2 = async { repository.updateNetworkPolicy(NetworkPolicy.ANY_NETWORK) }
        val job3 = async { repository.updateReserveSpace(300) }

        val results = awaitAll(job1, job2, job3)
        results.forEach { assertTrue("Tutte le operazioni devono riuscire", it.isSuccess) }

        // Rileggi lo stato finale direttamente dal database Room SQLite
        val finalSettings = repository.getCurrentSettings()

        assertEquals("La frequenza deve essere conservata", BackupFrequency.FORTY_EIGHT_HOURS, finalSettings.frequency)
        assertEquals("La policy di rete deve essere conservata", NetworkPolicy.ANY_NETWORK, finalSettings.networkPolicy)
        assertEquals("La riserva spazio deve essere conservata", 300, finalSettings.reserveSpaceMb)
    }

    @Test
    fun test5_concurrencyBetweenPartialUpdatesAndFullConfigurationSave() = runTest(testDispatcher) {
        repository.initializeDefaultsIfEmpty()

        val fullConfiguration = AppSettings(
            frequency = BackupFrequency.TWELVE_HOURS,
            networkPolicy = NetworkPolicy.ONLY_WIFI,
            reserveSpaceMb = 250,
            fifoRotationEnabled = true,
            allowCloudOnlyEviction = false,
            requireCharging = true,
            exclusions = listOf("docs", "images")
        )

        // Lanciamo in concorrenza un salvataggio completo e aggiornamenti parziali (frequenza, spazio, pattern)
        val jobFull = async { repository.updateSettings(fullConfiguration) }
        val jobFreq = async { repository.updateFrequency(BackupFrequency.FORTY_EIGHT_HOURS) }
        val jobSpace = async { repository.updateReserveSpace(450) }
        val jobPattern = async { repository.addExclusionPattern("audio_recordings") }

        val results = awaitAll(jobFull, jobFreq, jobSpace, jobPattern)
        results.forEach { assertTrue("Tutte le operazioni coordinate da writeMutex devono riuscire", it.isSuccess) }

        val finalSettings = repository.getCurrentSettings()
        assertNotNull(finalSettings)
        assertTrue(
            "La frequenza finale deve essere uno dei valori validi impostati",
            finalSettings.frequency in listOf(BackupFrequency.TWELVE_HOURS, BackupFrequency.FORTY_EIGHT_HOURS)
        )
        assertTrue(
            "La riserva spazio deve riflettere un valore valido",
            finalSettings.reserveSpaceMb in listOf(250, 450)
        )
        assertTrue(
            "La lista esclusioni deve essere consistente e non vuota",
            finalSettings.exclusions.isNotEmpty()
        )
    }

    @Test
    fun test7_savingSettingsAndPatternsIsAtomic() = runTest(testDispatcher) {
        repository.initializeDefaultsIfEmpty()

        val newSettings = AppSettings(
            frequency = BackupFrequency.FORTY_EIGHT_HOURS,
            networkPolicy = NetworkPolicy.ONLY_WIFI,
            reserveSpaceMb = 500,
            fifoRotationEnabled = false,
            exclusions = listOf("new_pattern_1", "new_pattern_2")
        )

        repository.updateSettings(newSettings)

        val daoSettings = settingsDao.getSettings()
        val daoPatterns = settingsDao.getPatterns()

        assertNotNull(daoSettings)
        assertEquals("FORTY_EIGHT_HOURS", daoSettings?.frequency)
        assertEquals(500, daoSettings?.reserveSpaceMb)
        assertEquals(2, daoPatterns.size)
        assertEquals("new_pattern_1", daoPatterns[0].pattern)
        assertEquals(0, daoPatterns[0].position)
        assertEquals("new_pattern_2", daoPatterns[1].pattern)
        assertEquals(1, daoPatterns[1].position)
    }

    @Test
    fun testUnknownEnumFallbackIsSafe() {
        val corruptedEntity = AppSettingsEntity(
            id = 1,
            frequency = "NON_EXISTING_FREQUENCY_XYZ",
            networkPolicy = "UNKNOWN_NETWORK_POLICY_123",
            reserveSpaceMb = 100,
            fifoRotationEnabled = false,
            allowCloudOnlyEviction = false,
            requireCharging = false,
            lastModifiedTimestamp = System.currentTimeMillis()
        )

        val domain = corruptedEntity.toDomainModel(listOf("cache"))

        assertEquals("Frequenza sconosciuta deve ricadere su TWENTY_FOUR_HOURS", BackupFrequency.TWENTY_FOUR_HOURS, domain.frequency)
        assertEquals("Policy sconosciuta deve ricadere su ONLY_WIFI", NetworkPolicy.ONLY_WIFI, domain.networkPolicy)
    }
}
