package com.androidbackup.app.data.saf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/**
 * Implementazione Android nativa di [SafPermissionManager] basata su [android.content.ContentResolver].
 *
 * Conforme alla Specifica Fase 1B.1:
 * - Richiede esclusivamente FLAG_GRANT_READ_URI_PERMISSION (mai permessi di scrittura).
 * - Non tocca mai né altera il filesystem locale.
 * - Gestisce eccezioni di sicurezza (SecurityException), provider non disponibili ed URI invalidi.
 */
class AndroidSafPermissionManager(
    private val context: Context
) : SafPermissionManager {

    override fun takePersistableReadPermission(uri: Uri): Result<Unit> {
        return runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            Log.d(TAG, "Permesso persistente in sola lettura acquisito con successo per: $uri")
            Unit
        }.onFailure { error ->
            Log.e(TAG, "Impossibile acquisire permesso persistente SAF per $uri: ${error.localizedMessage}", error)
        }
    }

    override fun releasePersistableReadPermission(uri: Uri): Result<Unit> {
        return runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.releasePersistableUriPermission(uri, flags)
            Log.d(TAG, "Permesso persistente rilasciato per: $uri")
            Unit
        }.onFailure { error ->
            Log.w(TAG, "Errore durante il rilascio del permesso persistente per $uri: ${error.localizedMessage}")
        }
    }

    override fun hasPersistedReadPermission(uri: Uri): Boolean {
        return try {
            val persisted = context.contentResolver.persistedUriPermissions
            persisted.any { it.uri == uri && it.isReadPermission }
        } catch (e: Exception) {
            Log.w(TAG, "Errore durante la verifica dei permessi persistenti per $uri: ${e.localizedMessage}")
            false
        }
    }

    override fun getFolderDisplayName(uri: Uri): String {
        return try {
            val treeDocId = DocumentsContract.getTreeDocumentId(uri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, treeDocId)
            var resolvedName: String? = null

            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (index >= 0) {
                        resolvedName = cursor.getString(index)
                    }
                }
            }

            resolvedName?.takeIf { it.isNotBlank() }
                ?: fallbackDisplayNameFromUri(uri, treeDocId)
        } catch (e: Exception) {
            Log.w(TAG, "Risoluzione nome visualizzato tramite DocumentsContract fallita per $uri: ${e.localizedMessage}")
            fallbackDisplayNameFromUri(uri, null)
        }
    }

    override fun getPersistedReadUris(): Set<Uri> {
        return try {
            context.contentResolver.persistedUriPermissions
                .filter { it.isReadPermission }
                .map { it.uri }
                .toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Errore nella lettura dell'elenco dei permessi persistenti: ${e.localizedMessage}")
            emptySet()
        }
    }

    private fun fallbackDisplayNameFromUri(uri: Uri, documentId: String?): String {
        val target = documentId ?: uri.lastPathSegment ?: ""
        val clean = target.substringAfterLast(':').substringAfterLast('/')
        return clean.ifBlank { "Cartella SAF (${uri.authority ?: "archivio"})" }
    }

    companion object {
        private const val TAG = "AndroidSafPermissionMgr"
    }
}
