package com.androidbackup.app.data.mock

import com.androidbackup.app.data.model.AppSettings
import com.androidbackup.app.data.model.BackupFrequency
import com.androidbackup.app.data.model.NetworkPolicy
import com.androidbackup.app.testutils.FakeSettingsDao
import com.androidbackup.app.data.repository.SettingsRepository
import com.androidbackup.app.ui.viewmodel.BackupViewModel
import com.androidbackup.app.testutils.MainDispatcherRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Test di isolamento rigoroso tra dati dimostrativi (MockDataProvider) e persistenza Room (Fase 01A).
 * Verifica il punto 8 della specifica.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MockDataIsolationTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    @Test
    fun test8_mockDataNeverInsertedIntoDatabaseAndDemoTogglePreservesUserRules() = runTest(testDispatcher) {
        val fakeDao = FakeSettingsDao()
        val repository = SettingsRepository(fakeDao, ioDispatcher = testDispatcher)

        // L'utente salva una configurazione reale personalizzata
        val userRules = AppSettings(
            frequency = BackupFrequency.FORTY_EIGHT_HOURS,
            networkPolicy = NetworkPolicy.ANY_NETWORK,
            reserveSpaceMb = 350,
            fifoRotationEnabled = true,
            allowCloudOnlyEviction = false,
            requireCharging = true,
            exclusions = listOf("my_private_docs", "personal_vault")
        )
        repository.updateSettings(userRules)
        advanceUntilIdle()

        // Creazione del ViewModel collegato al repository reale
        val viewModel = BackupViewModel(repository)
        advanceUntilIdle()

        // 1. Verifica che lo stato iniziale contenga le regole reali dell'utente
        assertEquals(userRules.frequency, viewModel.uiState.value.rules.frequency)
        assertEquals(userRules.networkPolicy, viewModel.uiState.value.rules.networkPolicy)
        assertEquals(userRules.reserveSpaceMb, viewModel.uiState.value.rules.reserveSpaceMb)
        assertEquals(userRules.exclusions, viewModel.uiState.value.rules.exclusions)

        // 2. Disattivazione della modalità Demo (passaggio a Scheletro Vuoto)
        viewModel.toggleDemoMode(false)
        advanceUntilIdle()

        assertFalse("La modalità demo deve essere disattivata", viewModel.uiState.value.isDemoMode)
        assertTrue("Le cartelle devono essere vuote", viewModel.uiState.value.folders.isEmpty())
        assertTrue("La cronologia deve essere vuota", viewModel.uiState.value.history.isEmpty())
        // FONDAMENTALE: Le regole utente persistite in Room NON devono essere state cancellate o azzerate
        assertEquals(userRules.frequency, viewModel.uiState.value.rules.frequency)
        assertEquals(userRules.reserveSpaceMb, viewModel.uiState.value.rules.reserveSpaceMb)
        assertEquals(userRules.exclusions, viewModel.uiState.value.rules.exclusions)

        // 3. Riattivazione della modalità Demo
        viewModel.toggleDemoMode(true)
        advanceUntilIdle()

        assertTrue("La modalità demo è attiva", viewModel.uiState.value.isDemoMode)
        assertEquals("Le cartelle demo sono ripristinate nella UI", MockDataProvider.demoFolders.size, viewModel.uiState.value.folders.size)
        // FONDAMENTALE: Le regole persistite NON devono essere state sovrascritte dai default di mock
        assertEquals(userRules.frequency, viewModel.uiState.value.rules.frequency)
        assertEquals(userRules.reserveSpaceMb, viewModel.uiState.value.rules.reserveSpaceMb)
        assertEquals(userRules.exclusions, viewModel.uiState.value.rules.exclusions)

        // 4. Verifica nel database DAO: nessuna cartella mock o log mock deve essere presente
        val daoSettings = fakeDao.getSettings()
        assertEquals("Nel database sono registrate esclusivamente le regole reali", "FORTY_EIGHT_HOURS", daoSettings?.frequency)
        assertEquals(350, daoSettings?.reserveSpaceMb)
    }
}
