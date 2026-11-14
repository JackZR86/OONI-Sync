package com.androidbackup.app.ui.viewmodel

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.androidbackup.app.data.drive.DriveAuthGateway
import com.androidbackup.app.data.drive.DriveGatewayAuthResult
import com.androidbackup.app.data.drive.DriveGatewayRevokeResult
import com.androidbackup.app.data.drive.DriveIdentityClient
import com.androidbackup.app.data.drive.DriveIdentityException
import com.androidbackup.app.data.drive.PlayServicesAvailabilityStatus
import com.androidbackup.app.data.local.entity.LinkedDriveAccountEntity
import com.androidbackup.app.data.model.DriveAuthFlowPurpose
import com.androidbackup.app.data.model.DriveAuthUiEvent
import com.androidbackup.app.data.model.DriveOAuthConstants
import com.androidbackup.app.data.model.DriveSessionAuthState
import com.androidbackup.app.data.model.DriveUserIdentity
import com.androidbackup.app.data.model.PersistedDriveCheckOutcome
import com.androidbackup.app.data.repository.DriveAccountRepository
import com.androidbackup.app.data.repository.SettingsRepository
import com.androidbackup.app.testutils.FakeLinkedDriveAccountDao
import com.androidbackup.app.testutils.FakeSettingsDao
import com.androidbackup.app.testutils.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BackupViewModelDriveTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun createTestIntentSender(requestCode: Int = 2001): IntentSender {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return PendingIntent.getActivity(
            context,
            requestCode,
            Intent("com.androidbackup.app.TEST_VM_AUTH_$requestCode"),
            PendingIntent.FLAG_IMMUTABLE
        ).intentSender
    }

    private class FakeVmDriveAuthGateway : DriveAuthGateway {
        val interactiveCalls = mutableListOf<Pair<String?, Boolean>>()
        val silentCalls = mutableListOf<String?>()
        val clearedTokens = mutableListOf<String>()
        val revokedEmails = mutableListOf<String>()

        var interactiveQueue = ArrayDeque<DriveGatewayAuthResult>()
        var silentQueue = ArrayDeque<DriveGatewayAuthResult>()
        var extractQueue = ArrayDeque<DriveGatewayAuthResult>()
        var revokeResult: DriveGatewayRevokeResult = DriveGatewayRevokeResult.Succeeded

        override fun checkPlayServicesAvailability(): PlayServicesAvailabilityStatus =
            PlayServicesAvailabilityStatus.Available

        override suspend fun authorizeInteractive(
            targetEmail: String?,
            promptSelectAccount: Boolean
        ): DriveGatewayAuthResult {
            interactiveCalls.add(targetEmail to promptSelectAccount)
            return if (interactiveQueue.isNotEmpty()) {
                interactiveQueue.removeFirst()
            } else {
                DriveGatewayAuthResult.Failed("Nessun esito interattivo configurato")
            }
        }

        override suspend fun authorizeSilentNoUi(targetEmail: String?): DriveGatewayAuthResult {
            silentCalls.add(targetEmail)
            return if (silentQueue.isNotEmpty()) {
                silentQueue.removeFirst()
            } else {
                DriveGatewayAuthResult.Failed("Nessun esito silenzioso configurato")
            }
        }

        override fun extractAuthorizationResultFromIntent(data: Intent?): DriveGatewayAuthResult {
            return if (extractQueue.isNotEmpty()) {
                extractQueue.removeFirst()
            } else {
                DriveGatewayAuthResult.Cancelled
            }
        }

        override suspend fun clearToken(accessToken: String) {
            clearedTokens.add(accessToken)
        }

        override suspend fun revokeAccess(emailAddress: String): DriveGatewayRevokeResult {
            revokedEmails.add(emailAddress)
            return revokeResult
        }
    }

    private class FakeVmDriveIdentityClient : DriveIdentityClient {
        var responses = ArrayDeque<Result<DriveUserIdentity>>()

        override suspend fun fetchUserIdentity(accessToken: String): DriveUserIdentity {
            val next = if (responses.isNotEmpty()) responses.removeFirst()
            else Result.failure(DriveIdentityException.NetworkError("Mock vuoto"))
            return next.getOrThrow()
        }
    }

    private fun sampleEntity(
        permissionId: String = "perm-1",
        email: String? = "real.user@example.com",
        displayName: String? = "Real User"
    ) = LinkedDriveAccountEntity(
        id = LinkedDriveAccountEntity.DEFAULT_ID,
        permissionId = permissionId,
        emailAddress = email,
        displayName = displayName,
        grantedScope = DriveOAuthConstants.DRIVE_FILE_SCOPE,
        linkedAt = 1000L,
        lastVerifiedAt = 2000L,
        lastPersistedOutcome = PersistedDriveCheckOutcome.LAST_CHECK_SUCCEEDED.name,
        lastVerificationAttemptAt = 2000L,
        lastErrorMessage = null
    )

    @Test
    fun initialState_isInitializingBeforeFirstRoomEmission() = runTest {
        val queuedIoDispatcher = StandardTestDispatcher(testScheduler)
        val settingsRepo = SettingsRepository(FakeSettingsDao(), ioDispatcher = queuedIoDispatcher)
        val driveRepo = DriveAccountRepository(
            linkedDriveAccountDao = FakeLinkedDriveAccountDao(sampleEntity()),
            authGateway = FakeVmDriveAuthGateway(),
            identityClient = FakeVmDriveIdentityClient(),
            ioDispatcher = queuedIoDispatcher
        )

        val viewModel = BackupViewModel(
            settingsRepository = settingsRepo,
            driveAccountRepository = driveRepo,
            savedStateHandle = SavedStateHandle()
        )

        // Prima che il flusso Room emetta, lo stato deve essere Initializing (mai NotLinked)
        assertEquals(DriveSessionAuthState.Initializing, viewModel.uiState.value.driveSessionAuthState)

        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.driveSessionAuthState is DriveSessionAuthState.LinkedUnverifiedInSession)
    }

    @Test
    fun pendingIntentFlow_survivesViewModelRecreation_blocksConcurrency_andNeverStoresPendingIntentInSavedStateOrUiState() = runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val dao = FakeLinkedDriveAccountDao(null)
        val gateway = FakeVmDriveAuthGateway().apply {
            interactiveQueue.addLast(DriveGatewayAuthResult.NeedsUserInteraction(createTestIntentSender(1)))
            extractQueue.addLast(
                DriveGatewayAuthResult.Authorized("tok-ok", listOf(DriveOAuthConstants.DRIVE_FILE_SCOPE))
            )
        }
        val identityClient = FakeVmDriveIdentityClient().apply {
            responses.addLast(Result.success(DriveUserIdentity("perm-recreated", "recreated@example.com", "Recreated")))
        }
        val settingsRepo = SettingsRepository(FakeSettingsDao(), io)
        val driveRepo1 = DriveAccountRepository(dao, gateway, identityClient, io) { 5000L }
        val savedStateHandle = SavedStateHandle()

        val vm1 = BackupViewModel(settingsRepo, driveAccountRepository = driveRepo1, savedStateHandle = savedStateHandle)
        vm1.toggleDemoMode(false)

        val eventsVm1 = mutableListOf<DriveAuthUiEvent>()
        val collectJob1 = backgroundScope.launch {
            vm1.driveAuthEvents.collect { eventsVm1.add(it) }
        }

        vm1.connectDriveAccount()

        // Evento one-shot emesso esattamente una volta; SavedStateHandle ha solo metadati testuali/booleani
        assertEquals(1, eventsVm1.size)
        assertTrue(eventsVm1[0] is DriveAuthUiEvent.LaunchAuthorizationIntent)
        assertEquals("INITIAL_LINK", savedStateHandle.get<String>(BackupViewModel.KEY_PENDING_AUTH_PURPOSE))
        assertEquals(false, savedStateHandle.get<Boolean>(BackupViewModel.KEY_PENDING_RETRY_USED))
        assertFalse(savedStateHandle.keys().any { key ->
            val v = savedStateHandle.get<Any?>(key)
            v is PendingIntent || v is IntentSender || (v is String && v.contains("tok-"))
        })
        assertTrue(vm1.uiState.value.driveSessionAuthState is DriveSessionAuthState.InProgress)
        collectJob1.cancel()

        // Simula ricreazione completa di Activity/ViewModel e Repository mantenendo lo stesso SavedStateHandle
        val driveRepo2 = DriveAccountRepository(dao, gateway, identityClient, io) { 6000L }
        val vm2 = BackupViewModel(settingsRepo, driveAccountRepository = driveRepo2, savedStateHandle = savedStateHandle)
        vm2.toggleDemoMode(false)

        val eventsVm2 = mutableListOf<DriveAuthUiEvent>()
        val collectJob2 = backgroundScope.launch {
            vm2.driveAuthEvents.collect { eventsVm2.add(it) }
        }

        // Il ViewModel ricreato NON ri-emette la PendingIntent e mantiene stato InProgress + blocco concorrenza
        assertTrue(eventsVm2.isEmpty())
        assertTrue(vm2.uiState.value.driveSessionAuthState is DriveSessionAuthState.InProgress)

        val callsBeforeConcurrentAttempt = gateway.interactiveCalls.size
        vm2.connectDriveAccount()
        assertEquals(callsBeforeConcurrentAttempt, gateway.interactiveCalls.size)

        // Al rientro dell'ActivityResult, completa il flusso
        vm2.onDriveAuthorizationActivityResult(Activity.RESULT_OK, Intent())

        assertTrue(vm2.uiState.value.driveSessionAuthState is DriveSessionAuthState.VerifiedInSession)
        assertEquals("perm-recreated", dao.getAccount()?.permissionId)
        assertNull(savedStateHandle.get<String>(BackupViewModel.KEY_PENDING_AUTH_PURPOSE))
        collectJob2.cancel()
    }

    @Test
    fun cancelledActivityResultAfterRecreation_doesNotRemainStuckInProgress() = runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val dao = FakeLinkedDriveAccountDao(null)
        val gateway = FakeVmDriveAuthGateway()
        val settingsRepo = SettingsRepository(FakeSettingsDao(), io)
        val driveRepoAfterDeath = DriveAccountRepository(dao, gateway, FakeVmDriveIdentityClient(), io)

        // SavedStateHandle ricostruito dopo morte processo con flusso pendente
        val restoredHandle = SavedStateHandle(
            mapOf(
                BackupViewModel.KEY_PENDING_AUTH_PURPOSE to DriveAuthFlowPurpose.INITIAL_LINK.name,
                BackupViewModel.KEY_PENDING_RETRY_USED to false
            )
        )

        val recreatedVm = BackupViewModel(settingsRepo, driveAccountRepository = driveRepoAfterDeath, savedStateHandle = restoredHandle)
        recreatedVm.toggleDemoMode(false)
        assertTrue(recreatedVm.uiState.value.driveSessionAuthState is DriveSessionAuthState.InProgress)

        recreatedVm.onDriveAuthorizationActivityResult(Activity.RESULT_CANCELED, null)

        assertEquals(DriveSessionAuthState.NotLinked, recreatedVm.uiState.value.driveSessionAuthState)
        assertNull(restoredHandle.get<String>(BackupViewModel.KEY_PENDING_AUTH_PURPOSE))
    }

    @Test
    fun recreationAcross401RetryPendingIntent_preservesRetryUsedAndStopsOnSecond401() = runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val dao = FakeLinkedDriveAccountDao(sampleEntity(permissionId = "perm-1", email = "user1@example.com"))
        val gateway = FakeVmDriveAuthGateway().apply {
            interactiveQueue.addLast(DriveGatewayAuthResult.NeedsUserInteraction(createTestIntentSender(10)))
            // Ritorno prima PendingIntent -> token scaduto
            extractQueue.addLast(DriveGatewayAuthResult.Authorized("tok-401-a", listOf(DriveOAuthConstants.DRIVE_FILE_SCOPE)))
            // Retry dopo 401 -> seconda PendingIntent
            interactiveQueue.addLast(DriveGatewayAuthResult.NeedsUserInteraction(createTestIntentSender(11)))
            // Ritorno seconda PendingIntent dopo ricreazione -> ancora 401
            extractQueue.addLast(DriveGatewayAuthResult.Authorized("tok-401-b", listOf(DriveOAuthConstants.DRIVE_FILE_SCOPE)))
        }
        val identityClient = FakeVmDriveIdentityClient().apply {
            responses.addLast(Result.failure(DriveIdentityException.Unauthorized()))
            responses.addLast(Result.failure(DriveIdentityException.Unauthorized()))
        }
        val settingsRepo = SettingsRepository(FakeSettingsDao(), io)
        val savedStateHandle = SavedStateHandle()

        val vm1 = BackupViewModel(
            settingsRepo,
            driveAccountRepository = DriveAccountRepository(dao, gateway, identityClient, io),
            savedStateHandle = savedStateHandle
        )
        vm1.toggleDemoMode(false)

        val events = mutableListOf<DriveAuthUiEvent>()
        val job = backgroundScope.launch { vm1.driveAuthEvents.collect { events.add(it) } }

        // Avvio REVERIFY -> 1a PendingIntent
        vm1.verifyDriveConnection()
        assertEquals(1, events.size)

        // Rientro 1a PendingIntent -> 401 -> clearToken -> 2a PendingIntent con retryUsed=true
        vm1.onDriveAuthorizationActivityResult(Activity.RESULT_OK, Intent())
        assertEquals(2, events.size)
        assertEquals(listOf("tok-401-a"), gateway.clearedTokens)
        assertEquals(true, savedStateHandle.get<Boolean>(BackupViewModel.KEY_PENDING_RETRY_USED))
        assertEquals(DriveAuthFlowPurpose.REVERIFY.name, savedStateHandle.get<String>(BackupViewModel.KEY_PENDING_AUTH_PURPOSE))
        assertEquals("perm-1", savedStateHandle.get<String>(BackupViewModel.KEY_PENDING_EXPECTED_PERMISSION_ID))
        job.cancel()

        // Ricreazione ViewModel prima del rientro della seconda PendingIntent
        val vm2 = BackupViewModel(
            settingsRepo,
            driveAccountRepository = DriveAccountRepository(dao, gateway, identityClient, io),
            savedStateHandle = savedStateHandle
        )
        vm2.toggleDemoMode(false)
        assertTrue(vm2.uiState.value.driveSessionAuthState is DriveSessionAuthState.InProgress)

        vm2.onDriveAuthorizationActivityResult(Activity.RESULT_OK, Intent())

        // Deve fermarsi dopo il secondo 401 senza ulteriori retry né loop
        assertEquals(listOf("tok-401-a"), gateway.clearedTokens)
        assertNull(savedStateHandle.get<String>(BackupViewModel.KEY_PENDING_AUTH_PURPOSE))
        assertNotNull(vm2.uiState.value.driveErrorMessage)
        assertTrue(vm2.uiState.value.driveSessionAuthState is DriveSessionAuthState.ReauthRequired)
    }

    @Test
    fun orphanActivityResult_performsZeroRoomWritesAndKeepsStateClean() = runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val dao = FakeLinkedDriveAccountDao(null)
        val gateway = FakeVmDriveAuthGateway().apply {
            extractQueue.addLast(DriveGatewayAuthResult.Authorized("orphan-tok", listOf(DriveOAuthConstants.DRIVE_FILE_SCOPE)))
        }
        val vm = BackupViewModel(
            settingsRepository = SettingsRepository(FakeSettingsDao(), io),
            driveAccountRepository = DriveAccountRepository(dao, gateway, FakeVmDriveIdentityClient(), io),
            savedStateHandle = SavedStateHandle()
        )
        vm.toggleDemoMode(false)

        vm.onDriveAuthorizationActivityResult(Activity.RESULT_OK, Intent())

        assertEquals(0, dao.writeCount)
        assertNull(dao.getAccount())
        assertEquals(DriveSessionAuthState.NotLinked, vm.uiState.value.driveSessionAuthState)
    }

    @Test
    fun demoModeActive_blocksAllGoogleDriveOperations() = runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val dao = FakeLinkedDriveAccountDao(sampleEntity())
        val gateway = FakeVmDriveAuthGateway()
        val identityClient = FakeVmDriveIdentityClient()
        val vm = BackupViewModel(
            settingsRepository = SettingsRepository(FakeSettingsDao(), io),
            driveAccountRepository = DriveAccountRepository(dao, gateway, identityClient, io),
            savedStateHandle = SavedStateHandle()
        )

        // Default all'avvio: isDemoMode == true
        assertTrue(vm.uiState.value.isDemoMode)

        vm.connectDriveAccount()
        vm.verifyDriveConnection()
        vm.switchDriveAccount()
        vm.disconnectDriveAccountWithRemoteRevoke()
        vm.confirmLocalOnlyDriveDisconnect()
        vm.runDebugSilentDriveTokenCheck()

        assertTrue(gateway.interactiveCalls.isEmpty())
        assertTrue(gateway.silentCalls.isEmpty())
        assertTrue(gateway.revokedEmails.isEmpty())
        assertEquals(0, dao.writeCount)
    }

    @Test
    fun disconnectFailureAndExplicitLocalFallback_andDebugSilentCheck_workThroughViewModel() = runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val dao = FakeLinkedDriveAccountDao(sampleEntity(permissionId = "perm-1", email = "user1@example.com"))
        val gateway = FakeVmDriveAuthGateway().apply {
            silentQueue.addLast(DriveGatewayAuthResult.Authorized("bg-tok", listOf(DriveOAuthConstants.DRIVE_FILE_SCOPE)))
            revokeResult = DriveGatewayRevokeResult.Failed("Timeout Google Play Services")
        }
        val identityClient = FakeVmDriveIdentityClient().apply {
            responses.addLast(Result.success(DriveUserIdentity("perm-1", "user1@example.com", "User One")))
        }
        val vm = BackupViewModel(
            settingsRepository = SettingsRepository(FakeSettingsDao(), io),
            driveAccountRepository = DriveAccountRepository(dao, gateway, identityClient, io),
            savedStateHandle = SavedStateHandle()
        )
        vm.toggleDemoMode(false)

        val events = mutableListOf<DriveAuthUiEvent>()
        val job = backgroundScope.launch { vm.driveAuthEvents.collect { events.add(it) } }

        // Test silenzioso debug (no UI) -> verifica senza emettere alcuna PendingIntent
        vm.runDebugSilentDriveTokenCheck()
        assertTrue(events.isEmpty())
        assertTrue(vm.uiState.value.driveSessionAuthState is DriveSessionAuthState.VerifiedInSession)

        // Disconnessione remota fallisce -> preserva Room e espone messaggio per scelta esplicita
        vm.disconnectDriveAccountWithRemoteRevoke()
        assertNotNull(vm.uiState.value.driveRemoteRevokeFailedMessage)
        assertNotNull(dao.getAccount())

        // Conferma esplicita scollegamento locale -> rimuove Room e transisce a NotLinked
        vm.confirmLocalOnlyDriveDisconnect()
        assertNull(dao.getAccount())
        assertNull(vm.uiState.value.driveRemoteRevokeFailedMessage)
        assertEquals(DriveSessionAuthState.NotLinked, vm.uiState.value.driveSessionAuthState)
        job.cancel()
    }
}
