package com.androidbackup.app.data.drive

import com.androidbackup.app.data.model.DriveUserIdentity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * Implementazione di [DriveIdentityClient] basata su [HttpsConnectionFactory] e `org.json.JSONObject`.
 *
 * Esegue esclusivamente:
 * `GET https://www.googleapis.com/drive/v3/about?fields=user(displayName,emailAddress,permissionId)`
 */
class HttpDriveIdentityClient(
    private val connectionFactory: HttpsConnectionFactory = DefaultHttpsConnectionFactory(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : DriveIdentityClient {

    override suspend fun fetchUserIdentity(accessToken: String): DriveUserIdentity = withContext(ioDispatcher) {
        if (accessToken.isBlank()) {
            throw DriveIdentityException.Unauthorized("Access token assente per la chiamata about.get.")
        }

        var connection: HttpConnectionHandle? = null
        try {
            connection = connectionFactory.openConnection(DRIVE_ABOUT_USER_URL).apply {
                setRequestMethod("GET")
                setConnectTimeout(CONNECT_TIMEOUT_MS)
                setReadTimeout(READ_TIMEOUT_MS)
                setUseCaches(false)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $accessToken")
            }

            val statusCode = connection.getResponseCode()
            if (statusCode in 200..299) {
                val body = readBoundedUtf8(connection.getInputStream())
                parseUserIdentity(body)
            } else {
                val errorBody = connection.getErrorStream()?.let { stream ->
                    runCatching { readBoundedUtf8(stream) }.getOrNull()
                }
                val parsedReason = errorBody?.let { extractApiErrorMessage(it) }
                when (statusCode) {
                    401 -> throw DriveIdentityException.Unauthorized()
                    403 -> throw DriveIdentityException.Forbidden(
                        parsedReason?.let { "Accesso Google Drive negato (HTTP 403): $it" }
                            ?: "Accesso Google Drive negato (HTTP 403)."
                    )
                    else -> throw DriveIdentityException.HttpError(
                        statusCode = statusCode,
                        message = parsedReason?.let { "Errore Google Drive API (HTTP $statusCode): $it" }
                            ?: "Errore Google Drive API (HTTP $statusCode)."
                    )
                }
            }
        } catch (e: DriveIdentityException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw DriveIdentityException.Timeout(cause = e)
        } catch (e: IOException) {
            throw DriveIdentityException.NetworkError(cause = e)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun parseUserIdentity(jsonBody: String): DriveUserIdentity {
        return try {
            val root = JSONObject(jsonBody)
            val userObj = root.optJSONObject("user")
                ?: throw DriveIdentityException.MalformedResponse(
                    "Risposta Google Drive priva dell'oggetto 'user'."
                )

            val permissionId = userObj.optNullableNonBlankString("permissionId")
                ?: throw DriveIdentityException.MissingPermissionId()

            val emailAddress = userObj.optNullableNonBlankString("emailAddress")
            val displayName = userObj.optNullableNonBlankString("displayName")

            DriveUserIdentity(
                permissionId = permissionId,
                emailAddress = emailAddress,
                displayName = displayName
            )
        } catch (e: DriveIdentityException) {
            throw e
        } catch (e: JSONException) {
            throw DriveIdentityException.MalformedResponse(
                message = "Risposta JSON Google Drive non valida.",
                cause = e
            )
        }
    }

    private fun extractApiErrorMessage(errorJson: String): String? {
        return try {
            val root = JSONObject(errorJson)
            val errorObj = root.optJSONObject("error") ?: return null
            errorObj.optNullableNonBlankString("message")?.take(160)
        } catch (_: JSONException) {
            null
        }
    }

    private fun JSONObject.optNullableNonBlankString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key) as? String ?: return null
        return value.trim().takeIf { it.isNotEmpty() }
    }

    private fun readBoundedUtf8(stream: InputStream): String {
        stream.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val out = ByteArrayOutputStream()
            var totalRead = 0
            while (totalRead < MAX_RESPONSE_BYTES) {
                val toRead = minOf(buffer.size, MAX_RESPONSE_BYTES - totalRead)
                val read = input.read(buffer, 0, toRead)
                if (read == -1) break
                out.write(buffer, 0, read)
                totalRead += read
            }
            return out.toString(StandardCharsets.UTF_8.name())
        }
    }

    companion object {
        const val DRIVE_ABOUT_USER_URL =
            "https://www.googleapis.com/drive/v3/about?fields=user(displayName,emailAddress,permissionId)"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}
