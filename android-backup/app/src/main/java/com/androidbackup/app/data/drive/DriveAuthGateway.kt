package com.androidbackup.app.data.drive

import android.content.Intent
import android.content.IntentSender

/**
 * Risultato della verifica preventiva di Google Play Services.
 */
sealed interface PlayServicesAvailabilityStatus {
    data object Available : PlayServicesAvailabilityStatus
    data class Unavailable(
        val connectionResultCode: Int,
        val isUserResolvable: Boolean,
        val message: String
    ) : PlayServicesAvailabilityStatus
}

/**
 * Esito dell'operazione su Google Identity AuthorizationClient.
 */
sealed interface DriveGatewayAuthResult {
    /** Token transiente in memoria e lista di scope testuali concessi (List<String>). */
    data class Authorized(
        val accessToken: String,
        val grantedScopes: List<String>
    ) : DriveGatewayAuthResult

    /** Consenso/selezione account interattiva richiesta tramite PendingIntent -> IntentSender. */
    data class NeedsUserInteraction(
        val intentSender: IntentSender
    ) : DriveGatewayAuthResult

    /** Flusso interattivo annullato dall'utente o Intent di ritorno nullo. */
    data object Cancelled : DriveGatewayAuthResult

    /** Errore tecnico o intervento utente non eseguibile in contesto silenzioso. */
    data class Failed(
        val message: String,
        val requiresUserInteraction: Boolean = false
    ) : DriveGatewayAuthResult
}

/**
 * Esito della revoca dell'accesso su Google Play Services.
 */
sealed interface DriveGatewayRevokeResult {
    data object Succeeded : DriveGatewayRevokeResult
    data class Failed(val message: String) : DriveGatewayRevokeResult
}

/**
 * Gateway testabile per l'integrazione con Google Play Services Identity AuthorizationClient.
 *
 * Opera esclusivamente tramite Application Context (nessun riferimento ad Activity).
 */
interface DriveAuthGateway {
    fun checkPlayServicesAvailability(): PlayServicesAvailabilityStatus

    suspend fun authorizeInteractive(
        targetEmail: String?,
        promptSelectAccount: Boolean
    ): DriveGatewayAuthResult

    suspend fun authorizeSilentNoUi(
        targetEmail: String?
    ): DriveGatewayAuthResult

    fun extractAuthorizationResultFromIntent(data: Intent?): DriveGatewayAuthResult

    suspend fun clearToken(accessToken: String)

    suspend fun revokeAccess(emailAddress: String): DriveGatewayRevokeResult
}
