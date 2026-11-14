package com.androidbackup.app.data.drive

import com.androidbackup.app.data.model.DriveUserIdentity

/**
 * Eccezioni tipizzate del client di verifica identità Google Drive.
 * Nessun access token è mai incluso nei messaggi.
 */
sealed class DriveIdentityException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    class Unauthorized(
        message: String = "Token Google Drive non valido o revocato (HTTP 401)."
    ) : DriveIdentityException(message)

    class Forbidden(
        message: String
    ) : DriveIdentityException(message)

    class HttpError(
        val statusCode: Int,
        message: String
    ) : DriveIdentityException(message)

    class MissingPermissionId(
        message: String = "Risposta Google Drive priva di user.permissionId."
    ) : DriveIdentityException(message)

    class MalformedResponse(
        message: String,
        cause: Throwable? = null
    ) : DriveIdentityException(message, cause)

    class Timeout(
        message: String = "Timeout di connessione durante la verifica dell'account Google Drive.",
        cause: Throwable? = null
    ) : DriveIdentityException(message, cause)

    class NetworkError(
        message: String = "Errore di rete durante la verifica dell'account Google Drive.",
        cause: Throwable? = null
    ) : DriveIdentityException(message, cause)
}

/**
 * Client per verificare l'identità dell'utente su Google Drive API v3 (`about.get`).
 */
interface DriveIdentityClient {
    @Throws(DriveIdentityException::class)
    suspend fun fetchUserIdentity(accessToken: String): DriveUserIdentity
}
