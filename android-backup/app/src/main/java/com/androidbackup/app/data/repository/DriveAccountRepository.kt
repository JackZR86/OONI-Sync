package com.androidbackup.app.data.repository

import com.androidbackup.app.data.drive.DriveAuthGateway
import com.androidbackup.app.data.drive.DriveGatewayAuthResult
import com.androidbackup.app.data.drive.DriveGatewayRevokeResult
import com.androidbackup.app.data.drive.DriveIdentityClient
import com.androidbackup.app.data.drive.DriveIdentityException
import com.androidbackup.app.data.local.dao.LinkedDriveAccountDao
import com.androidbackup.app.data.local.entity.LinkedDriveAccountEntity
import com.androidbackup.app.data.model.DebugSilentTokenCheckOutcome
import com.androidbackup.app.data.model.DriveAuthFlowPurpose
import com.androidbackup.app.data.model.DriveDisconnectOutcome
import com.androidbackup.app.data.model.DriveOAuthConstants
import com.androidbackup.app.data.model.DriveSessionAuthState
import com.androidbackup.app.data.model.DriveUserIdentity
import com.androidbackup.app.data.model.LinkedDriveAccount
import com.androidbackup.app.data.model.PendingDriveAuthRequest
import com.androidbackup.app.data.model.PersistedDriveCheckOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Esito di uno step interattivo di collegamento, ri-verifica o cambio account Google Drive.
 */
sealed interface DriveAuthStepOutcome {
    data class Completed(
        val account: LinkedDriveAccount,
        val replacedExisting: Boolean
    ) : DriveAuthStepOutcome

    data class NeedsResolution(
        val intentSender: android.content.IntentSender,
        val pendingRequest: PendingDriveAuthRequest
    ) : DriveAuthStepOutcome

    data class Cancelled(val message: String) : DriveAuthStepOutcome

    data class Failed(
        val reason: String,
        val requiresReauth: Boolean
    ) : DriveAuthStepOutcome

    data object BusyRejected : DriveAuthStepOutcome
}

/**
 * Repository responsabile dell'associazione locale dell'account Google Drive,
 * della verifica dell'identità (`drive/v3/about`), del cambio account atomico e della revoca.
 */
class DriveAccountRepository(
    private val linkedDriveAccountDao: LinkedDriveAccountDao,
    private val authGateway: DriveAuthGateway,
    private val identityClient: DriveIdentityClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowProvider: () -> Long = { System.currentTimeMillis() }
) {
    private sealed interface RuntimeAuthOverlay {
        data object UnverifiedInSession : RuntimeAuthOverlay
        data class InProgress(val purpose: DriveAuthFlowPurpose) : RuntimeAuthOverlay
        data object Disconnecting : RuntimeAuthOverlay
        data class VerifiedInSession(val permissionId: String, val verifiedAt: Long) : RuntimeAuthOverlay
        data class ReauthRequired(val permissionId: String?, val reason: String) : RuntimeAuthOverlay
        data class LastVerificationFailed(val permissionId: String?, val reason: String) : RuntimeAuthOverlay
    }

    private val operationMutex = Mutex()
    private val _runtimeOverlay = MutableStateFlow<RuntimeAuthOverlay>(RuntimeAuthOverlay.UnverifiedInSession)
    private var previousOverlayBeforeBusy: RuntimeAuthOverlay = RuntimeAuthOverlay.UnverifiedInSession

    val linkedAccountFlow: Flow<LinkedDriveAccount?> = linkedDriveAccountDao
        .observeAccount()
        .flowOn(ioDispatcher)
        .let { flow ->
            kotlinx.coroutines.flow.flow {
                flow.collect { entity -> emit(entity?.toDomainModel()) }
            }
        }

    /**
     * Osserva in modo reattivo la combinazione tra record persistito Room e stato runtime nella sessione corrente.
     * Dopo ogni riavvio, finché non viene eseguita una verifica positiva nella sessione, emette sempre
     * [DriveSessionAuthState.LinkedUnverifiedInSession] se esiste un account in Room.
     */
    fun observeSessionAuthState(): Flow<DriveSessionAuthState> {
        return combine(
            linkedDriveAccountDao.observeAccount(),
            _runtimeOverlay
        ) { entity, overlay ->
            val account = entity?.toDomainModel()
            mapToSessionState(account, overlay)
        }.flowOn(ioDispatcher)
    }

    private fun mapToSessionState(
        account: LinkedDriveAccount?,
        overlay: RuntimeAuthOverlay
    ): DriveSessionAuthState {
        return when (overlay) {
            RuntimeAuthOverlay.UnverifiedInSession -> {
                if (account == null) DriveSessionAuthState.NotLinked
                else DriveSessionAuthState.LinkedUnverifiedInSession(account)
            }

            is RuntimeAuthOverlay.InProgress -> {
                DriveSessionAuthState.InProgress(overlay.purpose, account)
            }

            RuntimeAuthOverlay.Disconnecting -> {
                if (account == null) DriveSessionAuthState.NotLinked
                else DriveSessionAuthState.Disconnecting(account)
            }

            is RuntimeAuthOverlay.VerifiedInSession -> {
                when {
                    account == null -> DriveSessionAuthState.NotLinked
                    account.permissionId == overlay.permissionId ->
                        DriveSessionAuthState.VerifiedInSession(account, overlay.verifiedAt)
                    else -> DriveSessionAuthState.LinkedUnverifiedInSession(account)
                }
            }

            is RuntimeAuthOverlay.ReauthRequired -> {
                when {
                    account == null -> DriveSessionAuthState.NotLinked
                    overlay.permissionId == null || account.permissionId == overlay.permissionId ->
                        DriveSessionAuthState.ReauthRequired(account, overlay.reason)
                    else -> DriveSessionAuthState.LinkedUnverifiedInSession(account)
                }
            }

            is RuntimeAuthOverlay.LastVerificationFailed -> {
                when {
                    account == null -> DriveSessionAuthState.NotLinked
                    overlay.permissionId == null || account.permissionId == overlay.permissionId ->
                        DriveSessionAuthState.LastVerificationFailed(account, overlay.reason)
                    else -> DriveSessionAuthState.LinkedUnverifiedInSession(account)
                }
            }
        }
    }

    private fun isBusyOverlay(overlay: RuntimeAuthOverlay): Boolean {
        return overlay is RuntimeAuthOverlay.InProgress || overlay is RuntimeAuthOverlay.Disconnecting
    }

    private fun enterInProgress(purpose: DriveAuthFlowPurpose) {
        val current = _runtimeOverlay.value
        if (!isBusyOverlay(current)) {
            previousOverlayBeforeBusy = current
        }
        _runtimeOverlay.value = RuntimeAuthOverlay.InProgress(purpose)
    }

    private fun restoreNonBusyOverlay(fallbackAccount: LinkedDriveAccount?) {
        val prev = previousOverlayBeforeBusy
        _runtimeOverlay.value = when {
            isBusyOverlay(prev) -> RuntimeAuthOverlay.UnverifiedInSession
            fallbackAccount == null -> RuntimeAuthOverlay.UnverifiedInSession
            else -> prev
        }
    }

    fun extractAuthorizationResult(data: android.content.Intent?): DriveGatewayAuthResult {
        return authGateway.extractAuthorizationResultFromIntent(data)
    }

    /**
     * Avvia un flusso interattivo di collegamento, ri-verifica o cambio account.
     * Restituisce [DriveAuthStepOutcome.BusyRejected] se un altro flusso è già attivo
     * (incluso mentre è aperta la PendingIntent).
     */
    suspend fun beginInteractiveAuthorization(
        purpose: DriveAuthFlowPurpose
    ): DriveAuthStepOutcome = withContext(ioDispatcher) {
        operationMutex.withLock {
            if (isBusyOverlay(_runtimeOverlay.value)) {
                return@withContext DriveAuthStepOutcome.BusyRejected
            }

            val existing = linkedDriveAccountDao.getAccount()?.toDomainModel()
            when (purpose) {
                DriveAuthFlowPurpose.INITIAL_LINK -> {
                    if (existing != null) {
                        return@withContext DriveAuthStepOutcome.Failed(
                            reason = "Esiste già un account Google Drive collegato. Usa 'Cambia account' o 'Disconnetti'.",
                            requiresReauth = false
                        )
                    }
                }

                DriveAuthFlowPurpose.REVERIFY, DriveAuthFlowPurpose.SWITCH_ACCOUNT -> {
                    if (existing == null) {
                        return@withContext DriveAuthStepOutcome.Failed(
                            reason = "Nessun account Google Drive associato da verificare o sostituire.",
                            requiresReauth = false
                        )
                    }
                }
            }

            val pendingRequest = PendingDriveAuthRequest(
                purpose = purpose,
                expectedPermissionId = existing?.permissionId,
                targetEmail = if (purpose == DriveAuthFlowPurpose.REVERIFY) existing?.emailAddress else null,
                retryUsed = false
            )

            enterInProgress(purpose)

            val promptSelect = shouldPromptSelectAccount(purpose, pendingRequest.targetEmail)
            val gatewayResult = authGateway.authorizeInteractive(
                targetEmail = pendingRequest.targetEmail,
                promptSelectAccount = promptSelect
            )

            completeWithGatewayResultLocked(
                gatewayResult = gatewayResult,
                request = pendingRequest,
                existingAccount = existing
            )
        }
    }

    /**
     * Completa il flusso al ritorno dell'ActivityResult dell'overlay Google.
     * Se `pendingRequest == null`, tratta il risultato come orfano senza scrivere in Room.
     */
    suspend fun completeAuthorizationFromIntent(
        resultCodeOk: Boolean,
        hasData: Boolean,
        pendingRequest: PendingDriveAuthRequest?,
        extractResult: () -> DriveGatewayAuthResult
    ): DriveAuthStepOutcome = withContext(ioDispatcher) {
        operationMutex.withLock {
            if (pendingRequest == null) {
                if (!isBusyOverlay(_runtimeOverlay.value)) {
                    // Nessun flusso pendente: risultato orfano ignorato senza scritture in Room
                    return@withContext DriveAuthStepOutcome.Cancelled(
                        "Risultato autorizzazione orfano ignorato."
                    )
                }
                val existing = linkedDriveAccountDao.getAccount()?.toDomainModel()
                restoreNonBusyOverlay(existing)
                return@withContext DriveAuthStepOutcome.Cancelled(
                    "Risultato autorizzazione privo di contesto operativo (orfano)."
                )
            }

            val existing = linkedDriveAccountDao.getAccount()?.toDomainModel()
            if (!resultCodeOk || !hasData) {
                restoreNonBusyOverlay(existing)
                return@withContext DriveAuthStepOutcome.Cancelled(
                    "Operazione Google Drive annullata dall'utente."
                )
            }

            // Assicura stato InProgress durante la verifica network about.get
            enterInProgress(pendingRequest.purpose)

            val extracted = extractResult()
            completeWithGatewayResultLocked(
                gatewayResult = extracted,
                request = pendingRequest,
                existingAccount = existing
            )
        }
    }

    /**
     * Ripristina lo stato non bloccato in caso di fallimento di lancio della PendingIntent lato UI.
     */
    suspend fun cancelPendingFlowAfterLaunchFailure(reason: String) = withContext(ioDispatcher) {
        operationMutex.withLock {
            val existing = linkedDriveAccountDao.getAccount()?.toDomainModel()
            restoreNonBusyOverlay(existing)
            if (existing != null) {
                linkedDriveAccountDao.updateVerificationOutcome(
                    outcome = PersistedDriveCheckOutcome.LAST_CHECK_FAILED.name,
                    attemptAt = nowProvider(),
                    errorMessage = reason
                )
            }
        }
    }

    private suspend fun completeWithGatewayResultLocked(
        gatewayResult: DriveGatewayAuthResult,
        request: PendingDriveAuthRequest,
        existingAccount: LinkedDriveAccount?
    ): DriveAuthStepOutcome {
        when (gatewayResult) {
            is DriveGatewayAuthResult.NeedsUserInteraction -> {
                // Mantiene InProgress mentre la PendingIntent è aperta
                return DriveAuthStepOutcome.NeedsResolution(
                    intentSender = gatewayResult.intentSender,
                    pendingRequest = request
                )
            }

            DriveGatewayAuthResult.Cancelled -> {
                restoreNonBusyOverlay(existingAccount)
                return DriveAuthStepOutcome.Cancelled("Autorizzazione Google Drive annullata dall'utente.")
            }

            is DriveGatewayAuthResult.Failed -> {
                return handleAuthFailureLocked(
                    request = request,
                    existingAccount = existingAccount,
                    reason = gatewayResult.message,
                    requiresReauth = gatewayResult.requiresUserInteraction
                )
            }

            is DriveGatewayAuthResult.Authorized -> {
                if (!gatewayResult.grantedScopes.contains(DriveOAuthConstants.DRIVE_FILE_SCOPE)) {
                    return handleAuthFailureLocked(
                        request = request,
                        existingAccount = existingAccount,
                        reason = "Permesso Google Drive (drive.file) non concesso.",
                        requiresReauth = true
                    )
                }

                return verifyIdentityAndHandle401Locked(
                    accessToken = gatewayResult.accessToken,
                    request = request,
                    existingAccount = existingAccount
                )
            }
        }
    }

    private suspend fun verifyIdentityAndHandle401Locked(
        accessToken: String,
        request: PendingDriveAuthRequest,
        existingAccount: LinkedDriveAccount?
    ): DriveAuthStepOutcome {
        return try {
            val identity = identityClient.fetchUserIdentity(accessToken)
            applyVerifiedIdentityLocked(identity, request, existingAccount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveIdentityException.Unauthorized) {
            if (request.retryUsed) {
                return handleAuthFailureLocked(
                    request = request,
                    existingAccount = existingAccount,
                    reason = "Token rifiutato da Google Drive (HTTP 401) anche dopo il rinnovo.",
                    requiresReauth = true
                )
            }

            // Singolo retry dopo clearToken() conservando esattamente lo scopo e l'identità attesa originali
            authGateway.clearToken(accessToken)
            val retryRequest = request.copy(retryUsed = true)
            val promptSelect = shouldPromptSelectAccount(retryRequest.purpose, retryRequest.targetEmail)
            val retryGatewayResult = authGateway.authorizeInteractive(
                targetEmail = retryRequest.targetEmail,
                promptSelectAccount = promptSelect
            )

            when (retryGatewayResult) {
                is DriveGatewayAuthResult.NeedsUserInteraction -> {
                    DriveAuthStepOutcome.NeedsResolution(
                        intentSender = retryGatewayResult.intentSender,
                        pendingRequest = retryRequest
                    )
                }

                DriveGatewayAuthResult.Cancelled -> {
                    restoreNonBusyOverlay(existingAccount)
                    DriveAuthStepOutcome.Cancelled("Autorizzazione annullata durante il rinnovo.")
                }

                is DriveGatewayAuthResult.Failed -> {
                    handleAuthFailureLocked(
                        request = retryRequest,
                        existingAccount = existingAccount,
                        reason = retryGatewayResult.message,
                        requiresReauth = true
                    )
                }

                is DriveGatewayAuthResult.Authorized -> {
                    if (!retryGatewayResult.grantedScopes.contains(DriveOAuthConstants.DRIVE_FILE_SCOPE)) {
                        return handleAuthFailureLocked(
                            request = retryRequest,
                            existingAccount = existingAccount,
                            reason = "Permesso Google Drive (drive.file) non concesso dopo il rinnovo.",
                            requiresReauth = true
                        )
                    }
                    try {
                        val retriedIdentity = identityClient.fetchUserIdentity(retryGatewayResult.accessToken)
                        applyVerifiedIdentityLocked(retriedIdentity, retryRequest, existingAccount)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (err: DriveIdentityException.Unauthorized) {
                        handleAuthFailureLocked(
                            request = retryRequest,
                            existingAccount = existingAccount,
                            reason = "Token rifiutato da Google Drive (HTTP 401) anche dopo il rinnovo.",
                            requiresReauth = true
                        )
                    } catch (err: DriveIdentityException) {
                        handleAuthFailureLocked(
                            request = retryRequest,
                            existingAccount = existingAccount,
                            reason = err.message ?: "Errore nella verifica identità Google Drive.",
                            requiresReauth = false
                        )
                    }
                }
            }
        } catch (e: DriveIdentityException) {
            handleAuthFailureLocked(
                request = request,
                existingAccount = existingAccount,
                reason = e.message ?: "Errore durante la verifica dell'account Google Drive.",
                requiresReauth = false
            )
        }
    }

    private suspend fun applyVerifiedIdentityLocked(
        identity: DriveUserIdentity,
        request: PendingDriveAuthRequest,
        existingAccount: LinkedDriveAccount?
    ): DriveAuthStepOutcome {
        val now = nowProvider()

        return when (request.purpose) {
            DriveAuthFlowPurpose.INITIAL_LINK -> {
                val newAccount = LinkedDriveAccount(
                    permissionId = identity.permissionId,
                    emailAddress = identity.emailAddress,
                    displayName = identity.displayName,
                    grantedScope = DriveOAuthConstants.DRIVE_FILE_SCOPE,
                    linkedAt = now,
                    lastVerifiedAt = now,
                    lastPersistedOutcome = PersistedDriveCheckOutcome.LAST_CHECK_SUCCEEDED,
                    lastVerificationAttemptAt = now,
                    lastErrorMessage = null
                )
                linkedDriveAccountDao.replaceAccount(LinkedDriveAccountEntity.fromDomainModel(newAccount))
                _runtimeOverlay.value = RuntimeAuthOverlay.VerifiedInSession(identity.permissionId, now)
                DriveAuthStepOutcome.Completed(account = newAccount, replacedExisting = false)
            }

            DriveAuthFlowPurpose.REVERIFY -> {
                val expectedId = request.expectedPermissionId ?: existingAccount?.permissionId
                if (expectedId != null && expectedId != identity.permissionId) {
                    val mismatchReason =
                        "L'account autorizzato non corrisponde all'account Google Drive collegato. Usa 'Cambia account' per sostituirlo."
                    return handleAuthFailureLocked(
                        request = request,
                        existingAccount = existingAccount,
                        reason = mismatchReason,
                        requiresReauth = false
                    )
                }

                val updatedAccount = LinkedDriveAccount(
                    permissionId = identity.permissionId,
                    emailAddress = identity.emailAddress ?: existingAccount?.emailAddress,
                    displayName = identity.displayName ?: existingAccount?.displayName,
                    grantedScope = DriveOAuthConstants.DRIVE_FILE_SCOPE,
                    linkedAt = existingAccount?.linkedAt ?: now,
                    lastVerifiedAt = now,
                    lastPersistedOutcome = PersistedDriveCheckOutcome.LAST_CHECK_SUCCEEDED,
                    lastVerificationAttemptAt = now,
                    lastErrorMessage = null
                )
                linkedDriveAccountDao.upsertAccount(LinkedDriveAccountEntity.fromDomainModel(updatedAccount))
                _runtimeOverlay.value = RuntimeAuthOverlay.VerifiedInSession(identity.permissionId, now)
                DriveAuthStepOutcome.Completed(account = updatedAccount, replacedExisting = false)
            }

            DriveAuthFlowPurpose.SWITCH_ACCOUNT -> {
                val sameAccount = existingAccount != null && existingAccount.permissionId == identity.permissionId
                val savedAccount = LinkedDriveAccount(
                    permissionId = identity.permissionId,
                    emailAddress = identity.emailAddress,
                    displayName = identity.displayName,
                    grantedScope = DriveOAuthConstants.DRIVE_FILE_SCOPE,
                    linkedAt = if (sameAccount) existingAccount!!.linkedAt else now,
                    lastVerifiedAt = now,
                    lastPersistedOutcome = PersistedDriveCheckOutcome.LAST_CHECK_SUCCEEDED,
                    lastVerificationAttemptAt = now,
                    lastErrorMessage = null
                )
                if (sameAccount) {
                    linkedDriveAccountDao.upsertAccount(LinkedDriveAccountEntity.fromDomainModel(savedAccount))
                } else {
                    linkedDriveAccountDao.replaceAccount(LinkedDriveAccountEntity.fromDomainModel(savedAccount))
                }
                _runtimeOverlay.value = RuntimeAuthOverlay.VerifiedInSession(identity.permissionId, now)
                DriveAuthStepOutcome.Completed(account = savedAccount, replacedExisting = !sameAccount)
            }
        }
    }

    private suspend fun handleAuthFailureLocked(
        request: PendingDriveAuthRequest,
        existingAccount: LinkedDriveAccount?,
        reason: String,
        requiresReauth: Boolean
    ): DriveAuthStepOutcome {
        val now = nowProvider()

        when (request.purpose) {
            DriveAuthFlowPurpose.INITIAL_LINK -> {
                _runtimeOverlay.value = RuntimeAuthOverlay.UnverifiedInSession
            }

            DriveAuthFlowPurpose.SWITCH_ACCOUNT -> {
                // In caso di fallimento durante il cambio account, l'associazione precedente resta intatta
                restoreNonBusyOverlay(existingAccount)
            }

            DriveAuthFlowPurpose.REVERIFY -> {
                if (existingAccount != null) {
                    val outcome = if (requiresReauth) {
                        PersistedDriveCheckOutcome.REAUTH_REQUIRED
                    } else {
                        PersistedDriveCheckOutcome.LAST_CHECK_FAILED
                    }
                    linkedDriveAccountDao.updateVerificationOutcome(
                        outcome = outcome.name,
                        attemptAt = now,
                        errorMessage = reason
                    )
                    _runtimeOverlay.value = if (requiresReauth) {
                        RuntimeAuthOverlay.ReauthRequired(existingAccount.permissionId, reason)
                    } else {
                        RuntimeAuthOverlay.LastVerificationFailed(existingAccount.permissionId, reason)
                    }
                } else {
                    _runtimeOverlay.value = RuntimeAuthOverlay.UnverifiedInSession
                }
            }
        }

        return DriveAuthStepOutcome.Failed(reason = reason, requiresReauth = requiresReauth)
    }

    /**
     * Recupero token e verifica silenziosa tramite Application Context (nessuna Activity, nessuna UI).
     * Utilizzabile nelle build debug per verificare la predisposizione ai futuri Worker in background.
     */
    suspend fun runSilentTokenCheckNoUi(): DebugSilentTokenCheckOutcome = withContext(ioDispatcher) {
        operationMutex.withLock {
            if (isBusyOverlay(_runtimeOverlay.value)) {
                return@withContext DebugSilentTokenCheckOutcome.Failed(
                    "Un'operazione di autorizzazione è già in corso."
                )
            }

            val account = linkedDriveAccountDao.getAccount()?.toDomainModel()
                ?: return@withContext DebugSilentTokenCheckOutcome.Failed(
                    "Nessun account Google Drive associato."
                )

            val now = nowProvider()
            val firstResult = authGateway.authorizeSilentNoUi(account.emailAddress)

            when (firstResult) {
                is DriveGatewayAuthResult.NeedsUserInteraction, DriveGatewayAuthResult.Cancelled -> {
                    val msg = "Intervento utente richiesto: impossibile ottenere il token senza UI."
                    markSilentReauthRequiredLocked(account, now, msg)
                    DebugSilentTokenCheckOutcome.InteractionRequired(msg)
                }

                is DriveGatewayAuthResult.Failed -> {
                    if (firstResult.requiresUserInteraction) {
                        markSilentReauthRequiredLocked(account, now, firstResult.message)
                        DebugSilentTokenCheckOutcome.InteractionRequired(
                            "${firstResult.message} (Un futuro Worker rinvierebbe l'esecuzione automatica.)"
                        )
                    } else {
                        linkedDriveAccountDao.updateVerificationOutcome(
                            outcome = PersistedDriveCheckOutcome.LAST_CHECK_FAILED.name,
                            attemptAt = now,
                            errorMessage = firstResult.message
                        )
                        _runtimeOverlay.value =
                            RuntimeAuthOverlay.LastVerificationFailed(account.permissionId, firstResult.message)
                        DebugSilentTokenCheckOutcome.Failed(firstResult.message)
                    }
                }

                is DriveGatewayAuthResult.Authorized -> {
                    if (!firstResult.grantedScopes.contains(DriveOAuthConstants.DRIVE_FILE_SCOPE)) {
                        val msg = "Scope drive.file non presente nel token silenzioso: richiesto intervento utente."
                        markSilentReauthRequiredLocked(account, now, msg)
                        return@withContext DebugSilentTokenCheckOutcome.InteractionRequired(msg)
                    }

                    verifySilentIdentityWithSingle401RetryLocked(
                        account = account,
                        token = firstResult.accessToken,
                        retryUsed = false
                    )
                }
            }
        }
    }

    private suspend fun verifySilentIdentityWithSingle401RetryLocked(
        account: LinkedDriveAccount,
        token: String,
        retryUsed: Boolean
    ): DebugSilentTokenCheckOutcome {
        val now = nowProvider()
        return try {
            val identity = identityClient.fetchUserIdentity(token)
            if (identity.permissionId != account.permissionId) {
                val msg = "Mismatch identità nel test silenzioso: l'account rilevato non coincide con quello collegato."
                linkedDriveAccountDao.updateVerificationOutcome(
                    outcome = PersistedDriveCheckOutcome.LAST_CHECK_FAILED.name,
                    attemptAt = now,
                    errorMessage = msg
                )
                _runtimeOverlay.value = RuntimeAuthOverlay.LastVerificationFailed(account.permissionId, msg)
                return DebugSilentTokenCheckOutcome.Failed(msg)
            }

            val updated = account.copy(
                emailAddress = identity.emailAddress ?: account.emailAddress,
                displayName = identity.displayName ?: account.displayName,
                lastVerifiedAt = now,
                lastPersistedOutcome = PersistedDriveCheckOutcome.LAST_CHECK_SUCCEEDED,
                lastVerificationAttemptAt = now,
                lastErrorMessage = null
            )
            linkedDriveAccountDao.upsertAccount(LinkedDriveAccountEntity.fromDomainModel(updated))
            _runtimeOverlay.value = RuntimeAuthOverlay.VerifiedInSession(updated.permissionId, now)
            DebugSilentTokenCheckOutcome.Success(
                "Token silenzioso ottenuto tramite Application Context (senza UI) e identità Drive verificata."
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: DriveIdentityException.Unauthorized) {
            if (retryUsed) {
                val msg = "Token silenzioso rifiutato (HTTP 401) anche dopo clearToken: rinnovo interattivo necessario."
                markSilentReauthRequiredLocked(account, now, msg)
                return DebugSilentTokenCheckOutcome.InteractionRequired(msg)
            }

            authGateway.clearToken(token)
            when (val retryResult = authGateway.authorizeSilentNoUi(account.emailAddress)) {
                is DriveGatewayAuthResult.Authorized -> {
                    if (!retryResult.grantedScopes.contains(DriveOAuthConstants.DRIVE_FILE_SCOPE)) {
                        val msg = "Scope drive.file non concesso dopo clearToken."
                        markSilentReauthRequiredLocked(account, now, msg)
                        DebugSilentTokenCheckOutcome.InteractionRequired(msg)
                    } else {
                        verifySilentIdentityWithSingle401RetryLocked(
                            account = account,
                            token = retryResult.accessToken,
                            retryUsed = true
                        )
                    }
                }

                is DriveGatewayAuthResult.Failed -> {
                    if (retryResult.requiresUserInteraction) {
                        markSilentReauthRequiredLocked(account, now, retryResult.message)
                        DebugSilentTokenCheckOutcome.InteractionRequired(retryResult.message)
                    } else {
                        linkedDriveAccountDao.updateVerificationOutcome(
                            outcome = PersistedDriveCheckOutcome.LAST_CHECK_FAILED.name,
                            attemptAt = now,
                            errorMessage = retryResult.message
                        )
                        _runtimeOverlay.value =
                            RuntimeAuthOverlay.LastVerificationFailed(account.permissionId, retryResult.message)
                        DebugSilentTokenCheckOutcome.Failed(retryResult.message)
                    }
                }

                is DriveGatewayAuthResult.NeedsUserInteraction, DriveGatewayAuthResult.Cancelled -> {
                    val msg = "Rinnovo interattivo necessario dopo 401: un Worker rinvierebbe l'operazione."
                    markSilentReauthRequiredLocked(account, now, msg)
                    DebugSilentTokenCheckOutcome.InteractionRequired(msg)
                }
            }
        } catch (e: DriveIdentityException) {
            val msg = e.message ?: "Errore HTTP/rete durante la verifica silenziosa."
            linkedDriveAccountDao.updateVerificationOutcome(
                outcome = PersistedDriveCheckOutcome.LAST_CHECK_FAILED.name,
                attemptAt = now,
                errorMessage = msg
            )
            _runtimeOverlay.value = RuntimeAuthOverlay.LastVerificationFailed(account.permissionId, msg)
            DebugSilentTokenCheckOutcome.Failed(msg)
        }
    }

    private suspend fun markSilentReauthRequiredLocked(
        account: LinkedDriveAccount,
        now: Long,
        reason: String
    ) {
        linkedDriveAccountDao.updateVerificationOutcome(
            outcome = PersistedDriveCheckOutcome.REAUTH_REQUIRED.name,
            attemptAt = now,
            errorMessage = reason
        )
        _runtimeOverlay.value = RuntimeAuthOverlay.ReauthRequired(account.permissionId, reason)
    }

    /**
     * Esegue la revoca remota esplicita tramite AuthorizationClient SOLO quando `emailAddress != null`.
     * Non cancella mai automaticamente la riga Room su errori diversi da [DriveGatewayRevokeResult.Succeeded].
     */
    suspend fun disconnectWithRemoteRevoke(): DriveDisconnectOutcome = withContext(ioDispatcher) {
        operationMutex.withLock {
            if (isBusyOverlay(_runtimeOverlay.value)) {
                return@withContext DriveDisconnectOutcome.RemoteRevokeFailed(
                    "Impossibile disconnettere mentre un'altra operazione è in corso."
                )
            }

            val existing = linkedDriveAccountDao.getAccount()?.toDomainModel()
                ?: return@withContext DriveDisconnectOutcome.RemoteRevokeSucceeded

            val email = existing.emailAddress?.takeIf { it.isNotBlank() }
                ?: return@withContext DriveDisconnectOutcome.RemoteRevokeFailed(
                    "Indirizzo email non disponibile: è consentito esclusivamente lo scollegamento locale esplicito."
                )

            previousOverlayBeforeBusy = _runtimeOverlay.value
            _runtimeOverlay.value = RuntimeAuthOverlay.Disconnecting

            when (val result = authGateway.revokeAccess(email)) {
                DriveGatewayRevokeResult.Succeeded -> {
                    linkedDriveAccountDao.deleteAll()
                    _runtimeOverlay.value = RuntimeAuthOverlay.UnverifiedInSession
                    DriveDisconnectOutcome.RemoteRevokeSucceeded
                }

                is DriveGatewayRevokeResult.Failed -> {
                    // NON cancellare automaticamente la riga Room
                    restoreNonBusyOverlay(existing)
                    DriveDisconnectOutcome.RemoteRevokeFailed(result.message)
                }
            }
        }
    }

    /**
     * Rimuove esclusivamente l'associazione locale in Room dopo conferma esplicita dell'utente.
     */
    suspend fun confirmLocalOnlyDisconnect(): DriveDisconnectOutcome = withContext(ioDispatcher) {
        operationMutex.withLock {
            if (isBusyOverlay(_runtimeOverlay.value)) {
                return@withContext DriveDisconnectOutcome.RemoteRevokeFailed(
                    "Impossibile scollegare mentre un'altra operazione è in corso."
                )
            }

            linkedDriveAccountDao.deleteAll()
            _runtimeOverlay.value = RuntimeAuthOverlay.UnverifiedInSession
            DriveDisconnectOutcome.LocalUnlinkOnly
        }
    }

    private fun shouldPromptSelectAccount(
        purpose: DriveAuthFlowPurpose,
        targetEmail: String?
    ): Boolean {
        return when (purpose) {
            DriveAuthFlowPurpose.INITIAL_LINK -> true
            DriveAuthFlowPurpose.SWITCH_ACCOUNT -> true
            DriveAuthFlowPurpose.REVERIFY -> targetEmail.isNullOrBlank()
        }
    }
}
