package com.androidbackup.app.data.model

import android.content.IntentSender

/**
 * Scope OAuth minimo ed esclusivo richiesto da OONI-Sync nella Fase 1C.1.
 */
object DriveOAuthConstants {
    const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val GOOGLE_ACCOUNT_TYPE = "com.google"
}

/**
 * Esito storico dell'ultima verifica persistita in Room.
 *
 * IMPORTANTE: rappresenta esclusivamente l'esito dell'ultimo controllo registrato su disco;
 * dopo un riavvio NON attesta che esista ancora un token valido o una sessione attiva.
 */
enum class PersistedDriveCheckOutcome {
    LAST_CHECK_SUCCEEDED,
    REAUTH_REQUIRED,
    LAST_CHECK_FAILED
}

/**
 * Scopo di un flusso autorizzativo interattivo in corso (persistito in SavedStateHandle).
 */
enum class DriveAuthFlowPurpose {
    INITIAL_LINK,
    REVERIFY,
    SWITCH_ACCOUNT
}

/**
 * Modello di dominio dei metadati non segreti dell'account Google Drive associato localmente.
 * Nessun accessToken, refreshToken, serverAuthCode o PendingIntent è presente o persistito.
 */
data class LinkedDriveAccount(
    val permissionId: String,
    val emailAddress: String?,
    val displayName: String?,
    val grantedScope: String,
    val linkedAt: Long,
    val lastVerifiedAt: Long,
    val lastPersistedOutcome: PersistedDriveCheckOutcome,
    val lastVerificationAttemptAt: Long?,
    val lastErrorMessage: String?
) {
    fun primaryDisplayLabel(): String {
        val cleanEmail = emailAddress?.takeIf { it.isNotBlank() }
        if (cleanEmail != null) return cleanEmail
        val cleanName = displayName?.takeIf { it.isNotBlank() }
        if (cleanName != null) return cleanName
        val suffix = permissionId.takeLast(6)
        return "Account Google Drive (ID: …$suffix)"
    }
}

/**
 * Identità dell'utente restituita da Drive API v3 `about.get?fields=user(displayName,emailAddress,permissionId)`.
 */
data class DriveUserIdentity(
    val permissionId: String,
    val emailAddress: String?,
    val displayName: String?
)

/**
 * Stato runtime nella sessione applicativa corrente dell'associazione Google Drive.
 * Distingue nettamente l'account associato in Room dalla verifica attiva nella sessione corrente.
 */
sealed interface DriveSessionAuthState {
    /** Osservazione iniziale di Room non ancora completata all'avvio. */
    data object Initializing : DriveSessionAuthState

    /** Nessun account associato in Room. */
    data object NotLinked : DriveSessionAuthState

    /**
     * Account associato presente in Room, ma nessuna verifica eseguita nella sessione corrente
     * (stato obbligatorio al riavvio finché non viene eseguita una nuova verifica).
     */
    data class LinkedUnverifiedInSession(
        val account: LinkedDriveAccount
    ) : DriveSessionAuthState

    /**
     * Operazione autorizzativa o verifica in corso (include l'attesa della PendingIntent).
     */
    data class InProgress(
        val purpose: DriveAuthFlowPurpose,
        val existingAccount: LinkedDriveAccount?
    ) : DriveSessionAuthState

    /**
     * Disconnessione / revoca in corso.
     */
    data class Disconnecting(
        val account: LinkedDriveAccount
    ) : DriveSessionAuthState

    /**
     * Verifica positiva completata nella sessione applicativa corrente.
     */
    data class VerifiedInSession(
        val account: LinkedDriveAccount,
        val verifiedAt: Long
    ) : DriveSessionAuthState

    /**
     * L'autorizzazione richiede un intervento esplicito dell'utente (rinnovo consenso/credenziali).
     */
    data class ReauthRequired(
        val account: LinkedDriveAccount?,
        val reason: String
    ) : DriveSessionAuthState

    /**
     * L'ultimo tentativo di verifica nella sessione corrente è fallito (es. offline o mismatch).
     */
    data class LastVerificationFailed(
        val account: LinkedDriveAccount?,
        val reason: String
    ) : DriveSessionAuthState
}

/**
 * Esito esplicito della disconnessione dell'account Google Drive.
 */
sealed interface DriveDisconnectOutcome {
    /** Revoca remota via AuthorizationClient riuscita e riga Room rimossa. */
    data object RemoteRevokeSucceeded : DriveDisconnectOutcome

    /** Revoca remota fallita: la riga Room è stata preservata. */
    data class RemoteRevokeFailed(val reason: String) : DriveDisconnectOutcome

    /** Scollegamento esclusivamente locale eseguito su conferma esplicita dell'utente. */
    data object LocalUnlinkOnly : DriveDisconnectOutcome
}

/**
 * Eventi one-shot verso la UI Compose per il lancio di una PendingIntent (mai salvati in UiState/SavedStateHandle).
 */
sealed interface DriveAuthUiEvent {
    data class LaunchAuthorizationIntent(val intentSender: IntentSender) : DriveAuthUiEvent
}

/**
 * Esito del test diagnostico silenzioso (disponibile esclusivamente nelle build BuildConfig.DEBUG).
 */
sealed interface DebugSilentTokenCheckOutcome {
    data class Success(val message: String) : DebugSilentTokenCheckOutcome
    data class InteractionRequired(val message: String) : DebugSilentTokenCheckOutcome
    data class Failed(val message: String) : DebugSilentTokenCheckOutcome
}

/**
 * Stato serializzato della richiesta di flusso in attesa nel SavedStateHandle.
 * Non contiene token, PendingIntent o credenziali.
 */
data class PendingDriveAuthRequest(
    val purpose: DriveAuthFlowPurpose,
    val expectedPermissionId: String?,
    val targetEmail: String?,
    val retryUsed: Boolean
)
