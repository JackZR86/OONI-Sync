package com.androidbackup.app.data.drive

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.androidbackup.app.data.model.DriveOAuthConstants
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Implementazione reale di [DriveAuthGateway] basata su `Identity.getAuthorizationClient(applicationContext)`.
 *
 * Vincoli:
 * - Utilizza esclusivamente `context.applicationContext` (nessun Activity leak).
 * - Richiede esclusivamente lo scope `drive.file`.
 * - Confronta gli scope concessi tramite `AuthorizationResult.getGrantedScopes(): List<String>`.
 * - Non lancia mai UI né restituisce PendingIntent dal metodo silenzioso `authorizeSilentNoUi`.
 * - Non esegue mai `RevokeAccessRequest` senza un `Account` esplicito.
 */
class AndroidDriveAuthGateway(
    context: Context
) : DriveAuthGateway {

    private val appContext: Context = context.applicationContext

    private val authorizationClient: AuthorizationClient by lazy {
        Identity.getAuthorizationClient(appContext)
    }

    override fun checkPlayServicesAvailability(): PlayServicesAvailabilityStatus {
        val availability = GoogleApiAvailability.getInstance()
        val resultCode = availability.isGooglePlayServicesAvailable(appContext)
        return if (resultCode == ConnectionResult.SUCCESS) {
            PlayServicesAvailabilityStatus.Available
        } else {
            val isResolvable = availability.isUserResolvableError(resultCode)
            val errorText = availability.getErrorString(resultCode)
            PlayServicesAvailabilityStatus.Unavailable(
                connectionResultCode = resultCode,
                isUserResolvable = isResolvable,
                message = "Google Play Services non disponibile ($errorText, codice $resultCode)."
            )
        }
    }

    override suspend fun authorizeInteractive(
        targetEmail: String?,
        promptSelectAccount: Boolean
    ): DriveGatewayAuthResult {
        return executeAuthorize(
            targetEmail = targetEmail,
            promptSelectAccount = promptSelectAccount,
            allowInteractiveResolution = true
        )
    }

    override suspend fun authorizeSilentNoUi(
        targetEmail: String?
    ): DriveGatewayAuthResult {
        return executeAuthorize(
            targetEmail = targetEmail,
            promptSelectAccount = false,
            allowInteractiveResolution = false
        )
    }

    private suspend fun executeAuthorize(
        targetEmail: String?,
        promptSelectAccount: Boolean,
        allowInteractiveResolution: Boolean
    ): DriveGatewayAuthResult {
        val availability = checkPlayServicesAvailability()
        if (availability is PlayServicesAvailabilityStatus.Unavailable) {
            return DriveGatewayAuthResult.Failed(message = availability.message)
        }

        val requestBuilder = AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(Scope(DriveOAuthConstants.DRIVE_FILE_SCOPE)))

        val cleanEmail = targetEmail?.takeIf { it.isNotBlank() }
        if (cleanEmail != null && !promptSelectAccount) {
            requestBuilder.setAccount(Account(cleanEmail, DriveOAuthConstants.GOOGLE_ACCOUNT_TYPE))
        }

        requestBuilder.setPrompt(
            if (promptSelectAccount) {
                AuthorizationRequest.Prompt.SELECT_ACCOUNT
            } else {
                AuthorizationRequest.Prompt.NOT_SET
            }
        )

        return try {
            val result = authorizationClient.authorize(requestBuilder.build()).await()
            if (result.hasResolution()) {
                if (!allowInteractiveResolution) {
                    DriveGatewayAuthResult.Failed(
                        message = "Intervento utente richiesto per rinnovare o concedere l'autorizzazione Google Drive.",
                        requiresUserInteraction = true
                    )
                } else {
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent != null) {
                        DriveGatewayAuthResult.NeedsUserInteraction(pendingIntent.intentSender)
                    } else {
                        DriveGatewayAuthResult.Failed(
                            message = "Google Play Services ha richiesto una risoluzione ma non ha restituito una PendingIntent valida."
                        )
                    }
                }
            } else {
                val token = result.accessToken
                val scopes: List<String> = result.grantedScopes ?: emptyList()
                if (token.isNullOrBlank()) {
                    DriveGatewayAuthResult.Failed(
                        message = "Autorizzazione Google completata senza access token disponibile."
                    )
                } else {
                    DriveGatewayAuthResult.Authorized(
                        accessToken = token,
                        grantedScopes = scopes
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            DriveGatewayAuthResult.Failed(
                message = "Errore autorizzazione Google Play Services (codice ${e.statusCode})."
            )
        } catch (e: Exception) {
            DriveGatewayAuthResult.Failed(
                message = "Errore imprevisto durante l'autorizzazione Google: ${e.javaClass.simpleName}."
            )
        }
    }

    override fun extractAuthorizationResultFromIntent(data: Intent?): DriveGatewayAuthResult {
        if (data == null) {
            return DriveGatewayAuthResult.Cancelled
        }

        return try {
            val result = authorizationClient.getAuthorizationResultFromIntent(data)
            val token = result.accessToken
            val scopes: List<String> = result.grantedScopes ?: emptyList()
            if (token.isNullOrBlank()) {
                DriveGatewayAuthResult.Failed(
                    message = "Risultato autorizzazione privo di access token."
                )
            } else {
                DriveGatewayAuthResult.Authorized(
                    accessToken = token,
                    grantedScopes = scopes
                )
            }
        } catch (e: ApiException) {
            DriveGatewayAuthResult.Failed(
                message = "Errore nel completamento dell'autorizzazione Google (codice ${e.statusCode})."
            )
        } catch (e: Exception) {
            DriveGatewayAuthResult.Failed(
                message = "Impossibile leggere il risultato dell'autorizzazione Google: ${e.javaClass.simpleName}."
            )
        }
    }

    override suspend fun clearToken(accessToken: String) {
        if (accessToken.isBlank()) return
        try {
            val request = ClearTokenRequest.builder()
                .setToken(accessToken)
                .build()
            authorizationClient.clearToken(request).await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best-effort per invalidare la cache locale Play Services prima di un retry
        }
    }

    override suspend fun revokeAccess(emailAddress: String): DriveGatewayRevokeResult {
        val cleanEmail = emailAddress.trim()
        if (cleanEmail.isEmpty()) {
            return DriveGatewayRevokeResult.Failed(
                "Indirizzo email dell'account non disponibile per una revoca mirata."
            )
        }

        val availability = checkPlayServicesAvailability()
        if (availability is PlayServicesAvailabilityStatus.Unavailable) {
            return DriveGatewayRevokeResult.Failed(availability.message)
        }

        return try {
            val request = RevokeAccessRequest.builder()
                .setAccount(Account(cleanEmail, DriveOAuthConstants.GOOGLE_ACCOUNT_TYPE))
                .setScopes(listOf(Scope(DriveOAuthConstants.DRIVE_FILE_SCOPE)))
                .build()
            authorizationClient.revokeAccess(request).await()
            DriveGatewayRevokeResult.Succeeded
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            DriveGatewayRevokeResult.Failed(
                "Revoca accesso Google non riuscita (codice ${e.statusCode})."
            )
        } catch (e: Exception) {
            DriveGatewayRevokeResult.Failed(
                "Revoca accesso Google non riuscita: ${e.javaClass.simpleName}."
            )
        }
    }
}
