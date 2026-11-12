package com.androidbackup.app.testutils

import android.net.Uri
import com.androidbackup.app.data.saf.SafPermissionManager

/**
 * Fake implementation di [SafPermissionManager] per test unitari e di integrazione (Fase 1B.1).
 * Implementa rigorosamente tutti i metodi definiti nell'interfaccia [SafPermissionManager]:
 * - takePersistableReadPermission(uri)
 * - releasePersistableReadPermission(uri)
 * - hasPersistedReadPermission(uri)
 * - getFolderDisplayName(uri)
 * - getPersistedReadUris()
 */
class FakeSafPermissionManager : SafPermissionManager {

    private val grantedUris = mutableSetOf<String>()
    var shouldFailPermissionGrant = false
    var failureErrorMessage = "Errore autorizzazione SAF simulato"

    var shouldFailReleasePermission = false
    var failureReleaseErrorMessage = "Errore rilascio permesso SAF simulato"

    override fun takePersistableReadPermission(uri: Uri): Result<Unit> {
        if (shouldFailPermissionGrant) {
            return Result.failure(SecurityException(failureErrorMessage))
        }
        grantedUris.add(uri.toString())
        return Result.success(Unit)
    }

    override fun releasePersistableReadPermission(uri: Uri): Result<Unit> {
        if (shouldFailReleasePermission) {
            return Result.failure(SecurityException(failureReleaseErrorMessage))
        }
        grantedUris.remove(uri.toString())
        return Result.success(Unit)
    }

    override fun hasPersistedReadPermission(uri: Uri): Boolean {
        return grantedUris.contains(uri.toString())
    }

    override fun getFolderDisplayName(uri: Uri): String {
        val str = uri.toString()
        return when {
            str.contains("DCIM") -> "Fotocamera"
            str.contains("Documents") -> "Documenti"
            str.contains("Download") -> "Download"
            else -> "Cartella Test"
        }
    }

    override fun getPersistedReadUris(): Set<Uri> {
        return grantedUris.map { Uri.parse(it) }.toSet()
    }

    // Helper per test: simula revoca esterna da impostazioni di sistema Android
    fun simulateExternalRevocation(uri: Uri) {
        grantedUris.remove(uri.toString())
    }

    fun clear() {
        grantedUris.clear()
        shouldFailPermissionGrant = false
        shouldFailReleasePermission = false
    }
}
