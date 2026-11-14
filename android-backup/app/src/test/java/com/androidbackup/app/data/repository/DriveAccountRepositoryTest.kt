package com.androidbackup.app.data.repository

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentSender
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import com.androidbackup.app.data.drive.DriveAuthGateway
import com.androidbackup.app.data.drive.DriveGatewayAuthResult
import com.androidbackup.app.data.drive.DriveGatewayRevokeResult
import com.androidbackup.app.data.drive.DriveIdentityClient
import com.androidbackup.app.data.drive.DriveIdentityException
import com.androidbackup.app.data.drive.PlayServicesAvailabilityStatus
import com.androidbackup.app.data.local.entity.LinkedDriveAccountEntity
import com.androidbackup.app.data.model.DebugSilentTokenCheckOutcome
import com.androidbackup.app.data.model.DriveAuthFlowPurpose
import com.androidbackup.app.data.model.DriveDisconnectOutcome
import com.androidbackup.app.data.model.DriveOAuthConstants
import com.androidbackup.app.data.model.DriveSessionAuthState
import com.androidbackup.app.data.model.DriveUserIdentity
import com.androidbackup.app.data.model.PendingDriveAuthRequest
import com.androidbackup.app.data.model.PersistedDriveCheckOutcome
import com.androidbackup.app.testutils.FakeLinkedDriveAccountDao
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DriveAccountRepositoryTest {

    private fun createTestIntentSender(): IntentSender {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return PendingIntent.getActivity(
            context,
            1001,
            Intent("com.androidbackup.app.TEST_AUTH"),
            PendingIntent.FLAG_IMMUTABLE
        ).intentSender
    }

    private class FakeDriveAuthGateway : DriveAuthGateway {
        val interactiveCalls = mutableListOf<Pair<String?, Boolean>>()
        val silentCalls = mutableListOf<String?>()
        val clearedTokens = mutableListOf<String>()
        val revokedEmails = mutableListOf<String>()

        var interactiveQueue = ArrayDeque<DriveGatewayAuthResult>()
        var silentQueue = ArrayDeque<DriveGatewayAuthResult>()
        var extractedResult: DriveGatewayAuthResult = DriveGatewayAuthResult.Cancelled
        var revokeResult: DriveGatewayRevokeResult = DriveGatewayRevokeResult.Succeeded
        var customAuthorizeInteractive: (suspend (String?, Boolean) -> DriveGatewayAuthResult)? = null
        var customRevokeAccess: (suspend (String) -> DriveGatewayRevokeResult)? = null

        override fun checkPlayServicesAvailability(): PlayServicesAvailabilityStatus =
            PlayServicesAvailabilityStatus.Available

        override suspend fun authorizeInteractive(
            targetEmail: String?,
            promptSelectAccount: Boolean
        ): DriveGatewayAuthResult {
            interactiveCalls.add(targetEmail to promptSelectAccount)
            customAuthorizeInteractive?.let { return it(targetEmail, promptSelectAccount) }
            return if (interactiveQueue.isNotEmpty()) {
                interactiveQueue.removeFirst()
            } else {
                DriveGatewayAuthResult.Failed("Nessun risultato configurato")
            }
        }

        override suspend fun authorizeSilentNoUi(targetEmail: String?): DriveGatewayAuthResult {
            silentCalls.add(targetEmail)
            return if (silentQueue.isNotEmpty()) {
                silentQueue.removeFirst()
            } else {
                DriveGatewayAuthResult.Failed("Nessun risultato silenzioso configurato")
            }
        }

        override fun extractAuthorizationResultFromIntent(data: Intent?): DriveGatewayAuthResult =
            extractedResult

        override suspend fun clearToken(accessToken: String) {
            clearedTokens.add(accessToken)
        }

        override suspend fun revokeAccess(emailAddress: String): DriveGatewayRevokeResult {
            revokedEmails.add(emailAddress)
            customRevokeAccess?.let { return it(emailAddress) }
            return revokeResult
        }
    }

    private class FakeDriveIdentityClient : DriveIdentityClient {
        val requestedTokens = mutableListOf<String>()
        var responses = ArrayDeque<Result<DriveUserIdentity>>()
        var customFetchUserIdentity: (suspend (String) -> DriveUserIdentity)? = null

        override suspend fun fetchUserIdentity(accessToken: String): DriveUserIdentity {
            requestedTokens.add(accessToken)
            customFetchUserIdentity?.let { return it(accessToken) }
            val next = if (responses.isNotEmpty()) responses.removeFirst()
            else Result.failure(DriveIdentityException.NetworkError("Nessuna risposta mock"))
            return next.getOrThrow()
        }
    }

    private fun sampleEntity(
        permissionId: String = "perm-1",
        email: String? = "user1@example.com",
        displayName: String? = "User One"
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
    fun observeSessionAuthState_startupWithEmptyDbEmitsNotLinked_withExistingRowEmitsLinkedUnverifiedInSession() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val emptyRepo = DriveAccountRepository(
            linkedDriveAccountDao = FakeLinkedDriveAccountDao(null),
            authGateway = FakeDriveAuthGateway(),
            identityClient = FakeDriveIdentityClient(),
            ioDispatcher = dispatcher
        )
        assertEquals(DriveSessionAuthState.NotLinked, emptyRepo.observeSessionAuthState().first())

        val populatedRepo = DriveAccountRepository(
            linkedDriveAccountDao = FakeLinkedDriveAccountDao(sampleEntity()),
            authGateway = FakeDriveAuthGateway(),
            identityClient = FakeDriveIdentityClient(),
            ioDispatcher = dispatcher
        )
        val startupState = populatedRepo.observeSessionAuthState().first()
        assertTrue(startupState is DriveSessionAuthState.LinkedUnverifiedInSession)
        assertEquals("perm-1", (startupState as DriveSessionAuthState.LinkedUnverifiedInSession).account.permissionId)
    }

    @Test
    fun initialLink_needsResolutionThenCompleted_persistsAccountWithNullableFields() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val dao = FakeLinkedDriveAccountDao(null)
        val gateway = FakeDriveAuthGateway().apply {
            interactiveQueue.addLast(DriveGatewayAuthResult.NeedsUserInteraction(createTestIntentSender()))
            extractedResult = DriveGatewayAuthResult.Authorized(
                accessToken = "tok-1",
                grantedScopes = listOf(DriveOAuthConstants.DRIVE_FILE_SCOPE)
            )
        }
        val identityClient = FakeDriveIdentityClient().apply {
            responses.addLast(
                Result.success(
                    DriveUserIdentity(
                        permissionId = "perm-new",
                        emailAddress = null,
                        displayName = null
                    )
                )
            )
        }
        var currentNow = 5000L
        val repo = DriveAccountRepository(dao, gateway, identityClient, dispatcher) { currentNow }

        val step1 = repo.beginInteractiveAuthorization(DriveAuthFlowPurpose.INITIAL_LINK)
        assertTrue(step1 is DriveAuthStepOutcome.NeedsResolution)
        assertNull(dao.getAccount())
        assertTrue(repo.observeSessionAuthState().first() is DriveSessionAuthState.InProgress)

        // Mentre PendingIntent è aperta, una seconda operazione viene bloccata
        val concurrent = repo.beginInteractiveAuthorization(DriveAuthFlowPurpose.INITIAL_LINK)
        assertEquals(DriveAuthStepOutcome.BusyRejected, concurrent)

        currentNow = 6000L
        val step2 = repo.completeAuthorizationFromIntent(
            resultCodeOk = true,
            hasData = true,
            pendingRequest = (step1 as DriveAuthStepOutcome.NeedsResolution).pendingRequest,
            extractResult = { gateway.extractAuthorizationResultFromIntent(Intent()) }
        )

        assertTrue(step2 is DriveAuthStepOutcome.Completed)
        val persisted = dao.getAccount()
        assertNotNull(persisted)
        assertEquals("perm-new", persisted!!.permissionId)
        assertNull(persisted.emailAddress)
        assertNull(persisted.displayName)
        assertEquals(DriveOAuthConstants.DRIVE_FILE_SCOPE, persisted.grantedScope)
        assertEquals(6000L, persisted.linkedAt)

        val sessionState = repo.observeSessionAuthState().first()
        assertTrue(sessionState is DriveSessionAuthState.VerifiedInSession)
    }

    @Test
    fun completeAuthorization_missingDriveFileScope_failsWithoutWritingRoom() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val dao = FakeLinkedDriveAccountDao(null)
        val gateway = FakeDriveAuthGateway().apply {
            interactiveQueue.addLast(
                DriveGatewayAuthResult.Authorized(
                    accessToken = "tok-missing-scope",
                    grantedScopes = listOf("openid", "email")
                )
            )
        }
        val repo = DriveAccountRepository(dao, gateway, FakeDriveIdentityClient(), dispatcher)

        val outcome = repo.beginInteractiveAuthorization(DriveAuthFlowPurpose.INITIAL_LINK)
        assertTrue(outcome is DriveAuthStepOutcome.Failed)
        assertTrue((outcome as DriveAuthStepOutcome.Failed).requiresReauth)
        assertNull(dao.getAccount())
        assertEquals(0, dao.writeCount)
    }

    @Test
    fun initialLink_401ClearsTokenAndRetriesOnce_stoppingAfterSecond401WithoutInfiniteLoop() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val dao = FakeLinkedDriveAccountDao(null)
        val gateway = FakeDriveAuthGateway().apply {
            // Primo avvio -> Authorized(bad-tok-1)
            interactiveQueue.addLast(
                DriveGatewayAuthResult.Authorized(
                    accessToken = "bad-tok-1",
                    grantedScopes = listOf(DriveOAuthConstants.DRIVE_FILE_SCOPE)
                )
            )
            // Retry automatico -> NeedsUserInteraction con retryUsed = true
            interactiveQueue.addLast(DriveGatewayAuthResult.NeedsUserInteraction(createTestIntentSender()))
        }
        val identityClient = FakeDriveIdentityClient().apply {
            responses.addLast(Result.failure(DriveIdentityException.Unauthorized()))
            responses.addLast(Result.failure(DriveIdentityException.Unauthorized()))
        }
        val repo = DriveAccountRepository(dao, gateway, identityClient, dispatcher)

        val step1 = repo.beginInteractiveAuthorization(DriveAuthFlowPurpose.INITIAL_LINK)
        assertEquals(listOf("bad-tok-1"), gateway.clearedTokens)
        assertTrue(step1 is DriveAuthStepOutcome.NeedsResolution)
        val retryRequest = (step1 as DriveAuthStepOutcome.NeedsResolution).pendingRequest
        assertTrue(retryRequest.retryUsed)
        assertEquals(DriveAuthFlowPurpose.INITIAL_LINK, retryRequest.purpose)

        // Il secondo flusso restituisce un secondo 401: deve fermarsi senza un secondo clearToken
        gateway.extractedResult = DriveGatewayAuthResult.Authorized(
            accessToken = "bad-tok-2",
            grantedScopes = listOf(DriveOAuthConstants.DRIVE_FILE_SCOPE)
        )
        val step2 = repo.completeAuthorizationFromIntent(
            resultCodeOk = true,
            hasData = true,
            pendingRequest = retryRequest,
            extractResult = { gateway.extractAuthorizationResultFromIntent(Intent()) }
        )

        assertTrue(step2 is DriveAuthStepOutcome.Failed)
        assertTrue((step2 as DriveAuthStepOutcome.Failed).requiresReauth)
        assertEquals(listOf("bad-tok-1"), gateway.clearedTokens)
        assertNull(dao.getAccount())
    }

    @Test
    fun initialLink_401ClearsTokenAndImmediateAuthorizedRetrySucceeds() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val dao = FakeLinkedDriveAccountDao(null)
        val gateway = FakeDriveAuthGateway().apply {
            interactiveQueue.addLast(
                DriveGatewayAuthResult.Authorized("stale-tok", listOf(DriveOAuthConstants.DRIVE_FILE_SCOPE))
            )
            interactiveQueue.addLast(
                DriveGatewayAuthResult.Authorized("fresh-tok", listOf(DriveOAuthConstants.DRIVE_FILE_SCOPE))
            )
        }
        val identityClient = FakeDriveIdentityClient().apply {
            responses.addLast(Result.failure(DriveIdentityException.Unauthorized()))
            responses.addLast(Result.success(DriveUserIdentity("perm-ok", "ok@example.com", "OK")))
        }
        val repo = DriveAccountRepository(dao, gateway, identityClient, dispatcher)

        val outcome = repo.beginInteractiveAuthorization(DriveAuthFlowPurpose.INITIAL_LINK)
        assertTrue(outcome is DriveAuthStepOutcome.Completed)
        assertEquals(listOf("stale-tok"), gateway.clearedTokens)
        assertEquals("perm-ok", dao.getAccount()?.permissionId)
        assertTrue(repo.observeSessionAuthState().first() is DriveSessionAuthState.VerifiedInSession)
    }

    @Test
    fun reverify_usesTargetEmailWhenAvailableAndPromptSelectWhenEmailNull_andRejectsMismatch() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)

        // Case 1: email != null -> targetEmail passato, promptSelectAccount = false, mismatch rilevato
        val daoWithEmail = FakeLinkedDriveAccountDao(sampleEntity(permissionId = "perm-1", email = "user1@example.com"))
        val gw1 = FakeDriveAuthGateway().apply {
            interactiveQueue.addLast(
                DriveGatewayAuthResult.Authorized("tok-mismatch", listOf(DriveOAuthConstants.DRIVE_FILE_SCOPE))
            )
        }
        val id1 = FakeDriveIdentityClient().apply {
            responses.addLast(Result.success(DriveUserIdentity("perm-OTHER", "other@example.com", "Other")))
        }
        val repo1 = DriveAccountRepository(daoWithEmail, gw1, id1, dispatcher) { 9000L }

        val mismatchOutcome = repo1.beginInteractiveAuthorization(DriveAuthFlowPurpose.REVERIFY)
        assertTrue(mismatchOutcome is DriveAuthStepOutcome.Failed)
        assertEquals(listOf("user1@example.com" to false), gw1.interactiveCalls)
        // L'account precedente in Room non viene sovrascritto dal mismatch
        assertEquals("perm-1", daoWithEmail.getAccount()?.permissionId)
        assertEquals(PersistedDriveCheckOutcome.LAST_CHECK_FAILED.name, daoWithEmail.getAccount()?.lastPersistedOutcome)
        assertTrue(repo1.observeSessionAuthState().first() is DriveSessionAuthState.LastVerificationFailed)

        // Case 2: email == null -> targetEmail = null, promptSelectAccount = true
        val daoWithoutEmail = FakeLinkedDriveAccountDao(sampleEntity(permissionId = "perm-1", email = null, displayName = "Anon"))
        val gw2 = FakeDriveAuthGateway().apply {
            interactiveQueue.addLast(
                DriveGatewayAuthResult.Authorized("tok-ok", listOf(DriveOAuthConstants.DRIVE_FILE_SCOPE))
            )
        }
        val id2 = FakeDriveIdentityClient().apply {
            responses.addLast(Result.success(DriveUserIdentity("perm-1", null, "Anon Updated")))
        }
        val repo2 = DriveAccountRepository(daoWithoutEmail, gw2, id2, dispatcher) { 9500L }

        val reverifyOk = repo2.beginInteractiveAuthorization(DriveAuthFlowPurpose.REVERIFY)
        assertTrue(reverifyOk is DriveAuthStepOutcome.Completed)
        assertEquals(listOf(null to true), gw2.interactiveCalls)
        assertEquals("Anon Updated", daoWithoutEmail.getAccount()?.displayName)
    }

    @Test
    fun switchAccount_cancelKeepsOldAccount_successReplacesAtomically() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val dao = FakeLinkedDriveAccountDao(sampleEntity(permissionId = "perm-old", email = "old@example.com"))
        val gateway = FakeDriveAuthGateway().apply {
            interactiveQueue.addLast(DriveGatewayAuthResult.NeedsUserInteraction(createTestIntentSender()))
            interactiveQueue.addLast(
                DriveGatewayAuthResult.Authorized("tok-new", listOf(DriveOAuthConstants.DRIVE_FILE_SCOPE))
            )
        }
        val identityClient = FakeDriveIdentityClient().apply {
            responses.addLast(Result.success(DriveUserIdentity("perm-new", "new@example.com", "New User")))
        }
        var now = 10_000L
        val repo = DriveAccountRepository(dao, gateway, identityClient, dispatcher) { now }

        val step1 = repo.beginInteractiveAuthorization(DriveAuthFlowPurpose.SWITCH_ACCOUNT)
        assertTrue(step1 is DriveAuthStepOutcome.NeedsResolution)
        // L'utente annulla
        val cancelOutcome = repo.completeAuthorizationFromIntent(
            resultCodeOk = false,
            hasData = false,
            pendingRequest = (step1 as DriveAuthStepOutcome.NeedsResolution).pendingRequest,
            extractResult = { DriveGatewayAuthResult.Cancelled }
        )
        assertTrue(cancelOutcome is DriveAuthStepOutcome.Cancelled)
        assertEquals("perm-old", dao.getAccount()?.permissionId)
        assertEquals("old@example.com", dao.getAccount()?.emailAddress)

        // Ora completa il cambio account
        now = 11_000L
        val successOutcome = repo.beginInteractiveAuthorization(DriveAuthFlowPurpose.SWITCH_ACCOUNT)
        assertTrue(successOutcome is DriveAuthStepOutcome.Completed)
        assertTrue((successOutcome as DriveAuthStepOutcome.Completed).replacedExisting)
        assertEquals("perm-new", dao.getAccount()?.permissionId)
        assertEquals("new@example.com", dao.getAccount()?.emailAddress)
        assertEquals(11_000L, dao.getAccount()?.linkedAt)
    }

    @Test
    fun orphanAuthorizationResult_neverWritesToRoom() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val dao = FakeLinkedDriveAccountDao(null)
        val gateway = FakeDriveAuthGateway().apply {
            extractedResult = DriveGatewayAuthResult.Authorized("tok-orphan", listOf(DriveOAuthConstants.DRIVE_FILE_SCOPE))
        }
        val repo = DriveAccountRepository(dao, gateway, FakeDriveIdentityClient(), dispatcher)

        val outcome = repo.completeAuthorizationFromIntent(
            resultCodeOk = true,
            hasData = true,
            pendingRequest = null,
            extractResult = { gateway.extractAuthorizationResultFromIntent(Intent()) }
        )

        assertTrue(outcome is DriveAuthStepOutcome.Cancelled)
        assertEquals(0, dao.writeCount)
        assertNull(dao.getAccount())
    }

    @Test
    fun silentTokenCheckNoUi_handlesSuccessInteractionRequiredAndSingle401Retry() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val dao = FakeLinkedDriveAccountDao(sampleEntity(permissionId = "perm-1", email = "bg@example.com"))
        val gateway = FakeDriveAuthGateway().apply {
            silentQueue.addLast(DriveGatewayAuthResult.Authorized("bg-tok-1", listOf(DriveOAuthConstants.DRIVE_FILE_SCOPE)))
            silentQueue.addLast(DriveGatewayAuthResult.NeedsUserInteraction(createTestIntentSender()))
            silentQueue.addLast(DriveGatewayAuthResult.Authorized("bg-tok-401-a", listOf(DriveOAuthConstants.DRIVE_FILE_SCOPE)))
            silentQueue.addLast(DriveGatewayAuthResult.Authorized("bg-tok-401-b", listOf(DriveOAuthConstants.DRIVE_FILE_SCOPE)))
        }
        val identityClient = FakeDriveIdentityClient().apply {
            responses.addLast(Result.success(DriveUserIdentity("perm-1", "bg@example.com", "BG")))
            responses.addLast(Result.failure(DriveIdentityException.Unauthorized()))
            responses.addLast(Result.failure(DriveIdentityException.Unauthorized()))
        }
        val repo = DriveAccountRepository(dao, gateway, identityClient, dispatcher) { 20_000L }

        val ok = repo.runSilentTokenCheckNoUi()
        assertTrue(ok is DebugSilentTokenCheckOutcome.Success)
        assertTrue(repo.observeSessionAuthState().first() is DriveSessionAuthState.VerifiedInSession)

        val needsUi = repo.runSilentTokenCheckNoUi()
        assertTrue(needsUi is DebugSilentTokenCheckOutcome.InteractionRequired)
        assertEquals(PersistedDriveCheckOutcome.REAUTH_REQUIRED.name, dao.getAccount()?.lastPersistedOutcome)
        assertTrue(repo.observeSessionAuthState().first() is DriveSessionAuthState.ReauthRequired)

        val double401 = repo.runSilentTokenCheckNoUi()
        assertTrue(double401 is DebugSilentTokenCheckOutcome.InteractionRequired)
        assertEquals(listOf("bg-tok-401-a"), gateway.clearedTokens)
    }

    @Test
    fun disconnectWithRemoteRevoke_preservesRoomOnFailure_deletesOnSuccessOrExplicitLocalOnly_andNeverCallsRevokeWithoutEmail() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)

        // 1. email != null e revokeAccess fallisce -> riga Room PRESERVATA
        val daoWithEmail = FakeLinkedDriveAccountDao(sampleEntity(permissionId = "perm-1", email = "revoke@example.com"))
        val gwFail = FakeDriveAuthGateway().apply {
            revokeResult = DriveGatewayRevokeResult.Failed("Servizio Play Services offline")
        }
        val repoFail = DriveAccountRepository(daoWithEmail, gwFail, FakeDriveIdentityClient(), dispatcher)

        val failedOutcome = repoFail.disconnectWithRemoteRevoke()
        assertTrue(failedOutcome is DriveDisconnectOutcome.RemoteRevokeFailed)
        assertEquals(listOf("revoke@example.com"), gwFail.revokedEmails)
        assertNotNull(daoWithEmail.getAccount())

        // Conferma esplicita locale dopo fallimento -> rimuove riga Room
        val localFallback = repoFail.confirmLocalOnlyDisconnect()
        assertEquals(DriveDisconnectOutcome.LocalUnlinkOnly, localFallback)
        assertNull(daoWithEmail.getAccount())
        assertEquals(DriveSessionAuthState.NotLinked, repoFail.observeSessionAuthState().first())

        // 2. email != null e revokeAccess riesce -> rimuove riga Room
        val daoSuccess = FakeLinkedDriveAccountDao(sampleEntity(permissionId = "perm-2", email = "ok@example.com"))
        val gwSuccess = FakeDriveAuthGateway().apply {
            revokeResult = DriveGatewayRevokeResult.Succeeded
        }
        val repoSuccess = DriveAccountRepository(daoSuccess, gwSuccess, FakeDriveIdentityClient(), dispatcher)
        assertEquals(DriveDisconnectOutcome.RemoteRevokeSucceeded, repoSuccess.disconnectWithRemoteRevoke())
        assertEquals(listOf("ok@example.com"), gwSuccess.revokedEmails)
        assertNull(daoSuccess.getAccount())

        // 3. email == null -> MAI revokeAccess remoto; solo confirmLocalOnlyDisconnect() rimuove Room
        val daoNoEmail = FakeLinkedDriveAccountDao(sampleEntity(permissionId = "perm-no-email", email = null))
        val gwNoEmail = FakeDriveAuthGateway()
        val repoNoEmail = DriveAccountRepository(daoNoEmail, gwNoEmail, FakeDriveIdentityClient(), dispatcher)

        val blockedRemote = repoNoEmail.disconnectWithRemoteRevoke()
        assertTrue(blockedRemote is DriveDisconnectOutcome.RemoteRevokeFailed)
        assertTrue(gwNoEmail.revokedEmails.isEmpty())
        assertNotNull(daoNoEmail.getAccount())

        val localOnly = repoNoEmail.confirmLocalOnlyDisconnect()
        assertEquals(DriveDisconnectOutcome.LocalUnlinkOnly, localOnly)
        assertTrue(gwNoEmail.revokedEmails.isEmpty())
        assertNull(daoNoEmail.getAccount())
    }

    @Test
    fun cancellationDuringBeginInteractiveAuthorization_restoresNonBusyStateAndAllowsNextOperation() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val dao = FakeLinkedDriveAccountDao(null)
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<DriveGatewayAuthResult>()
        val gateway = FakeDriveAuthGateway().apply {
            customAuthorizeInteractive = { _, _ ->
                if (!entered.isCompleted) {
                    entered.complete(Unit)
                    gate.await()
                } else {
                    DriveGatewayAuthResult.Authorized("tok-after-cancel", listOf(DriveOAuthConstants.DRIVE_FILE_SCOPE))
                }
            }
        }
        val identityClient = FakeDriveIdentityClient().apply {
            responses.addLast(Result.success(DriveUserIdentity("perm-ok", "after@example.com", "After Cancel")))
        }
        val repo = DriveAccountRepository(dao, gateway, identityClient, dispatcher)

        val job = launch(dispatcher) {
            repo.beginInteractiveAuthorization(DriveAuthFlowPurpose.INITIAL_LINK)
        }
        entered.await()
        assertTrue(repo.observeSessionAuthState().first() is DriveSessionAuthState.InProgress)

        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(DriveSessionAuthState.NotLinked, repo.observeSessionAuthState().first())
        assertNull(dao.getAccount())
        assertEquals(0, dao.writeCount)

        // Una nuova operazione legittima deve avviarsi e completarsi senza restare bloccata
        val nextOutcome = repo.beginInteractiveAuthorization(DriveAuthFlowPurpose.INITIAL_LINK)
        assertTrue(nextOutcome is DriveAuthStepOutcome.Completed)
        assertTrue(repo.observeSessionAuthState().first() is DriveSessionAuthState.VerifiedInSession)
        assertEquals("perm-ok", dao.getAccount()?.permissionId)
    }

    @Test
    fun cancellationDuringCompleteAuthorizationFromIntentHttpVerify_restoresNonBusyStatePreservesRoomAndAllowsNextOperation() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val initial = sampleEntity(permissionId = "perm-1", email = "user1@example.com")
        val dao = FakeLinkedDriveAccountDao(initial)
        val enteredHttp = CompletableDeferred<Unit>()
        val httpGate = CompletableDeferred<DriveUserIdentity>()
        val gateway = FakeDriveAuthGateway().apply {
            interactiveQueue.addLast(DriveGatewayAuthResult.NeedsUserInteraction(createTestIntentSender()))
            interactiveQueue.addLast(
                DriveGatewayAuthResult.Authorized("tok-retry-ok", listOf(DriveOAuthConstants.DRIVE_FILE_SCOPE))
            )
            extractedResult = DriveGatewayAuthResult.Authorized("tok-1", listOf(DriveOAuthConstants.DRIVE_FILE_SCOPE))
        }
        var firstHttpCall = true
        val identityClient = FakeDriveIdentityClient().apply {
            customFetchUserIdentity = {
                if (firstHttpCall) {
                    firstHttpCall = false
                    enteredHttp.complete(Unit)
                    httpGate.await()
                } else {
                    DriveUserIdentity("perm-1", "user1@example.com", "User One Verified")
                }
            }
        }
        val repo = DriveAccountRepository(dao, gateway, identityClient, dispatcher) { 7000L }

        val step1 = repo.beginInteractiveAuthorization(DriveAuthFlowPurpose.REVERIFY)
        val pendingRequest = (step1 as DriveAuthStepOutcome.NeedsResolution).pendingRequest

        val job = launch(dispatcher) {
            repo.completeAuthorizationFromIntent(
                resultCodeOk = true,
                hasData = true,
                pendingRequest = pendingRequest,
                extractResult = { gateway.extractAuthorizationResultFromIntent(Intent()) }
            )
        }
        enteredHttp.await()
        assertTrue(repo.observeSessionAuthState().first() is DriveSessionAuthState.InProgress)

        job.cancelAndJoin()
        assertTrue(job.isCancelled)

        // Stato non deve restare InProgress, Room conservato e lastPersistedOutcome NON sovrascritto
        val stateAfterCancel = repo.observeSessionAuthState().first()
        assertTrue(stateAfterCancel is DriveSessionAuthState.LinkedUnverifiedInSession)
        val persisted = dao.getAccount()!!
        assertEquals("perm-1", persisted.permissionId)
        assertEquals(PersistedDriveCheckOutcome.LAST_CHECK_SUCCEEDED.name, persisted.lastPersistedOutcome)
        assertEquals(0, dao.writeCount)

        // Una nuova verifica può essere avviata immediatamente
        val nextOutcome = repo.beginInteractiveAuthorization(DriveAuthFlowPurpose.REVERIFY)
        assertTrue(nextOutcome is DriveAuthStepOutcome.Completed)
        assertTrue(repo.observeSessionAuthState().first() is DriveSessionAuthState.VerifiedInSession)
    }

    @Test
    fun cancellationDuringDisconnectWithRemoteRevoke_restoresNonDisconnectingPreservesRoomAndAllowsNextDisconnect() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val dao = FakeLinkedDriveAccountDao(sampleEntity(permissionId = "perm-1", email = "revoke@example.com"))
        val enteredRevoke = CompletableDeferred<Unit>()
        val revokeGate = CompletableDeferred<DriveGatewayRevokeResult>()
        var firstCall = true
        val gateway = FakeDriveAuthGateway().apply {
            customRevokeAccess = {
                if (firstCall) {
                    firstCall = false
                    enteredRevoke.complete(Unit)
                    revokeGate.await()
                } else {
                    DriveGatewayRevokeResult.Succeeded
                }
            }
        }
        val repo = DriveAccountRepository(dao, gateway, FakeDriveIdentityClient(), dispatcher)

        val job = launch(dispatcher) {
            repo.disconnectWithRemoteRevoke()
        }
        enteredRevoke.await()
        assertTrue(repo.observeSessionAuthState().first() is DriveSessionAuthState.Disconnecting)

        job.cancelAndJoin()
        assertTrue(job.isCancelled)

        // Non deve restare Disconnecting e la riga Room deve essere preservata
        val stateAfterCancel = repo.observeSessionAuthState().first()
        assertTrue(stateAfterCancel is DriveSessionAuthState.LinkedUnverifiedInSession)
        assertNotNull(dao.getAccount())
        assertEquals("perm-1", dao.getAccount()?.permissionId)
        assertEquals(0, dao.writeCount)

        // Una successiva disconnessione legittima completa regolarmente
        val secondOutcome = repo.disconnectWithRemoteRevoke()
        assertEquals(DriveDisconnectOutcome.RemoteRevokeSucceeded, secondOutcome)
        assertNull(dao.getAccount())
        assertEquals(DriveSessionAuthState.NotLinked, repo.observeSessionAuthState().first())
    }

    @Test
    fun cancellationRightAfterRemoteRevokeSucceeded_finishesSafeLocalCleanupWithoutStayingDisconnecting() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val dao = FakeLinkedDriveAccountDao(sampleEntity(permissionId = "perm-1", email = "postrevoke@example.com"))
        lateinit var cancelTargetJob: kotlinx.coroutines.Job
        val gateway = FakeDriveAuthGateway().apply {
            customRevokeAccess = {
                // Simula cancellazione concorrente mentre la revoca Google è appena terminata con successo
                cancelTargetJob.cancel()
                DriveGatewayRevokeResult.Succeeded
            }
            interactiveQueue.addLast(
                DriveGatewayAuthResult.Authorized("tok-new-link", listOf(DriveOAuthConstants.DRIVE_FILE_SCOPE))
            )
        }
        val identityClient = FakeDriveIdentityClient().apply {
            responses.addLast(Result.success(DriveUserIdentity("perm-new-2", "new2@example.com", "New Two")))
        }
        val repo = DriveAccountRepository(dao, gateway, identityClient, dispatcher)

        cancelTargetJob = launch(dispatcher) {
            repo.disconnectWithRemoteRevoke()
        }
        cancelTargetJob.join()

        assertTrue(cancelTargetJob.isCancelled)
        assertNull(dao.getAccount())
        assertEquals(DriveSessionAuthState.NotLinked, repo.observeSessionAuthState().first())

        // Deve essere subito possibile collegare un nuovo account
        val linkOutcome = repo.beginInteractiveAuthorization(DriveAuthFlowPurpose.INITIAL_LINK)
        assertTrue(linkOutcome is DriveAuthStepOutcome.Completed)
        assertEquals("perm-new-2", dao.getAccount()?.permissionId)
    }

    @Test
    fun remoteRevokeSucceeded_whenLocalDeleteAllThrowsSqliteError_neverStaysDisconnecting_marksReauthRequired_andAllowsLocalDisconnect() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val dao = FakeLinkedDriveAccountDao(
            sampleEntity(
                permissionId = "perm-1",
                email = "cleanup-fail@example.com"
            )
        ).apply {
            deleteAllException = SQLiteException("Simulated SQLite error on deleteAll")
        }
        val gateway = FakeDriveAuthGateway().apply {
            revokeResult = DriveGatewayRevokeResult.Succeeded
        }
        val repo = DriveAccountRepository(dao, gateway, FakeDriveIdentityClient(), dispatcher)

        val outcome = repo.disconnectWithRemoteRevoke()

        assertTrue(outcome is DriveDisconnectOutcome.RemoteRevokeSucceededLocalCleanupFailed)
        assertFalse(outcome is DriveDisconnectOutcome.RemoteRevokeFailed)
        assertEquals(listOf("cleanup-fail@example.com"), gateway.revokedEmails)

        val stateAfterError = repo.observeSessionAuthState().first()
        assertFalse(stateAfterError is DriveSessionAuthState.Disconnecting)
        assertFalse(stateAfterError is DriveSessionAuthState.VerifiedInSession)
        assertTrue(stateAfterError is DriveSessionAuthState.ReauthRequired)

        // La riga Room è preservata ma marcata REAUTH_REQUIRED, quindi non appare autorizzata
        val preserved = dao.getAccount()
        assertNotNull(preserved)
        assertEquals("perm-1", preserved?.permissionId)
        assertEquals(PersistedDriveCheckOutcome.REAUTH_REQUIRED.name, preserved?.lastPersistedOutcome)

        // Anche una nuova istanza del repository osserva lo stato persistito come non verificato/REAUTH_REQUIRED
        val freshRepo = DriveAccountRepository(dao, gateway, FakeDriveIdentityClient(), dispatcher)
        val freshState = freshRepo.observeSessionAuthState().first()
        assertTrue(freshState is DriveSessionAuthState.LinkedUnverifiedInSession)
        assertEquals(
            PersistedDriveCheckOutcome.REAUTH_REQUIRED,
            (freshState as DriveSessionAuthState.LinkedUnverifiedInSession).account.lastPersistedOutcome
        )

        // Risolto il problema SQLite, l'utente può completare lo scollegamento esclusivamente locale
        dao.deleteAllException = null
        val localOutcome = repo.confirmLocalOnlyDisconnect()
        assertEquals(DriveDisconnectOutcome.LocalUnlinkOnly, localOutcome)
        assertNull(dao.getAccount())
        assertEquals(DriveSessionAuthState.NotLinked, repo.observeSessionAuthState().first())
    }
}
