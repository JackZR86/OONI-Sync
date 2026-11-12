package com.androidbackup.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.androidbackup.app.data.local.dao.SettingsDao
import com.androidbackup.app.data.local.database.BackupDatabase
import com.androidbackup.app.data.local.entity.AppSettingsEntity
import com.androidbackup.app.data.local.entity.ExclusionPatternEntity
import com.androidbackup.app.data.model.BackupFrequency
import com.androidbackup.app.data.model.NetworkPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Test di integrazione su VERO database SQLite Room in-memory (Fase 01A revisionata).
 * Verifica le operazioni reali di lettura/scrittura, aggiornamenti atomici per colonna e transazioni.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RoomSettingsDaoTest {

    private lateinit var database: BackupDatabase
    private lateinit var dao: SettingsDao

    @Before
    fun initDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BackupDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.settingsDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testRealRoomReadWriteAndAtomicUpdates() = runBlocking {
        // 1. Database inizialmente vuoto
        assertNull("All'inizio il DB Room è privo di record", dao.getSettings())
        assertTrue("All'inizio i pattern sono vuoti", dao.getPatterns().isEmpty())

        // 2. Inizializzazione atomica predefinita
        val defaultEntity = AppSettingsEntity.defaultEntity()
        val defaultPatterns = listOf(
            ExclusionPatternEntity("cache", 0),
            ExclusionPatternEntity("tmp", 1),
            ExclusionPatternEntity(".thumbnails", 2)
        )
        dao.initializeDefaultsIfEmpty(defaultEntity, defaultPatterns)

        // 3. Verifica persistenza reale in SQLite
        val loaded = dao.getSettings()
        assertNotNull(loaded)
        assertEquals(BackupFrequency.TWENTY_FOUR_HOURS.name, loaded?.frequency)
        assertEquals(NetworkPolicy.ONLY_WIFI.name, loaded?.networkPolicy)
        assertEquals(100, loaded?.reserveSpaceMb)
        assertFalse(loaded?.fifoRotationEnabled ?: true)

        val loadedPatterns = dao.getPatterns()
        assertEquals(3, loadedPatterns.size)
        assertEquals(listOf("cache", "tmp", ".thumbnails"), loadedPatterns.map { it.pattern })

        // 4. Test aggiornamenti atomici mirati per colonna (UPDATE SQL diretto)
        dao.updateFrequency(BackupFrequency.FORTY_EIGHT_HOURS.name)
        dao.updateNetworkPolicy(NetworkPolicy.ANY_NETWORK.name)

        val updated = dao.getSettings()
        assertEquals(BackupFrequency.FORTY_EIGHT_HOURS.name, updated?.frequency)
        assertEquals(NetworkPolicy.ANY_NETWORK.name, updated?.networkPolicy)
        // Gli altri campi rimangono integri
        assertEquals(100, updated?.reserveSpaceMb)

        // 5. Test transazione replacePatterns
        val newPatterns = listOf(
            ExclusionPatternEntity("only_one_folder", 0)
        )
        dao.replacePatterns(newPatterns)
        val singlePatternList = dao.getPatterns()
        assertEquals(1, singlePatternList.size)
        assertEquals("only_one_folder", singlePatternList[0].pattern)

        // 6. Test cancellazione completa (lista vuota)
        dao.clearPatterns()
        val emptyList = dao.getPatterns()
        assertTrue("La lista dei pattern deve poter essere completamente vuota in Room", emptyList.isEmpty())
    }

    @Test
    fun testSaveAllAtomicallyPersistsSettingsAndExclusionPatterns() = runBlocking {
        // Verifica salvataggio atomico congiunto di impostazioni e pattern in Room reale
        val initialSettings = dao.getSettings()
        assertNull("All'inizio il DB è vuoto", initialSettings)

        val newSettings = AppSettingsEntity(
            id = AppSettingsEntity.DEFAULT_ID,
            frequency = BackupFrequency.FORTY_EIGHT_HOURS.name,
            networkPolicy = NetworkPolicy.ANY_NETWORK.name,
            reserveSpaceMb = 350,
            fifoRotationEnabled = true,
            allowCloudOnlyEviction = true,
            requireCharging = true
        )
        val newPatterns = listOf(
            ExclusionPatternEntity("cache", 0),
            ExclusionPatternEntity("code_cache", 1),
            ExclusionPatternEntity("*.log", 2)
        )

        // Esegui la transazione reale saveAll su SQLite Room
        dao.saveAll(newSettings, newPatterns)

        // Verifica lettura impostazioni
        val loadedSettings = dao.getSettings()
        assertNotNull("Le impostazioni devono essere state scritte", loadedSettings)
        assertEquals(BackupFrequency.FORTY_EIGHT_HOURS.name, loadedSettings?.frequency)
        assertEquals(NetworkPolicy.ANY_NETWORK.name, loadedSettings?.networkPolicy)
        assertEquals(350, loadedSettings?.reserveSpaceMb)
        assertTrue("FIFO rotation deve essere true", loadedSettings?.fifoRotationEnabled == true)
        assertTrue("Allow cloud only eviction deve essere true", loadedSettings?.allowCloudOnlyEviction == true)
        assertTrue("Require charging deve essere true", loadedSettings?.requireCharging == true)

        // Verifica lettura pattern ordinati
        val loadedPatterns = dao.getPatterns()
        assertEquals("Devono essere presenti 3 pattern", 3, loadedPatterns.size)
        assertEquals("cache", loadedPatterns[0].pattern)
        assertEquals(0, loadedPatterns[0].position)
        assertEquals("code_cache", loadedPatterns[1].pattern)
        assertEquals(1, loadedPatterns[1].position)
        assertEquals("*.log", loadedPatterns[2].pattern)
        assertEquals(2, loadedPatterns[2].position)

        // Verifica sovrascrittura atomica di saveAll con nuovi pattern
        val replacedSettings = newSettings.copy(
            frequency = BackupFrequency.TWELVE_HOURS.name,
            reserveSpaceMb = 500
        )
        val replacedPatterns = listOf(
            ExclusionPatternEntity("replacement_folder", 0)
        )
        dao.saveAll(replacedSettings, replacedPatterns)

        val reloadedSettings = dao.getSettings()
        assertEquals(BackupFrequency.TWELVE_HOURS.name, reloadedSettings?.frequency)
        assertEquals(500, reloadedSettings?.reserveSpaceMb)

        val reloadedPatterns = dao.getPatterns()
        assertEquals("La lista pattern deve essere stata completamente rimpiazzata da saveAll", 1, reloadedPatterns.size)
        assertEquals("replacement_folder", reloadedPatterns[0].pattern)
    }
}
