package com.androidbackup.app.data.saf

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/**
 * Eccezione specifica sollevata quando il ContentProvider SAF restituisce un cursore nullo (Fase 1B.2 Revisione).
 * Permette di distinguere in modo inequivocabile un errore del provider da una directory validamente vuota.
 */
class NullCursorException(message: String) : Exception(message)

/**
 * Metadati di un documento o cartella restituiti dal provider SAF (Fase 1B.2).
 * Non include contenuti o stream (accesso rigorosamente in sola lettura metadati).
 */
data class SafDocumentMetadata(
    val documentId: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val lastModifiedTimestamp: Long?,
    val flags: Int?,
    val isDirectory: Boolean
)

/**
 * Interfaccia astratta per l'esplorazione gerarchica dell'albero documentale SAF.
 * Consente l'elaborazione progressiva in streaming a piccoli batch per limitare il consumo di memoria
 * e consentire test unitari riproducibili con [FakeSafTreeReader] senza filesystem fisico reale.
 */
interface SafTreeReader {
    /**
     * Recupera in streaming ed elabora a blocchi di dimensione controllata i documenti figli di una directory SAF.
     *
     * @param treeUri URI dell'albero radice SAF autorizzato
     * @param parentDocumentId ID del documento directory genitore da esplorare
     * @param batchSize dimensione massima del blocco prima dell'emissione alla callback
     * @param onBatch callback sospesa invocata per ciascun blocco. Restituisce `true` per continuare,
     *                `false` per interrompere la lettura in modo cooperativo (es. su pausa o annullamento).
     * @return `true` se la directory è stata letta interamente, `false` se la lettura è stata interrotta anzitempo.
     * @throws NullCursorException se il ContentProvider restituisce un Cursor nullo
     * @throws SecurityException in caso di permessi revocati
     * @throws Exception in caso di errori I/O o directory inaccessibile
     */
    suspend fun queryChildDocuments(
        treeUri: Uri,
        parentDocumentId: String,
        batchSize: Int = 100,
        onBatch: suspend (List<SafDocumentMetadata>) -> Boolean
    ): Boolean
}

/**
 * Implementazione Android nativa basata su [android.content.ContentResolver] e [DocumentsContract].
 * Elabora il Cursor progressivamente garantendone sempre la chiusura tramite [use].
 */
class AndroidSafTreeReader(
    private val context: Context
) : SafTreeReader {

    override suspend fun queryChildDocuments(
        treeUri: Uri,
        parentDocumentId: String,
        batchSize: Int,
        onBatch: suspend (List<SafDocumentMetadata>) -> Boolean
    ): Boolean {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        )

        val cursor = try {
            context.contentResolver.query(childrenUri, projection, null, null, null)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permesso di lettura negato per $childrenUri: ${e.localizedMessage}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante la query dei figli per $childrenUri: ${e.localizedMessage}")
            throw e
        } ?: throw NullCursorException("Il ContentProvider ha restituito un Cursor nullo per la directory '$parentDocumentId' su $childrenUri")

        var fullyRead = true
        cursor.use { c ->
            val docIdIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val flagsIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)

            val buffer = ArrayList<SafDocumentMetadata>(batchSize)

            while (c.moveToNext()) {
                val docId = if (docIdIdx >= 0 && !c.isNull(docIdIdx)) c.getString(docIdIdx) else null
                if (docId.isNullOrBlank()) continue

                val mime = if (mimeIdx >= 0 && !c.isNull(mimeIdx)) c.getString(mimeIdx) else null
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR

                val rawName = if (nameIdx >= 0 && !c.isNull(nameIdx)) c.getString(nameIdx) else null
                val displayName = rawName?.takeIf { it.isNotBlank() } ?: docId

                val sizeBytes = if (!isDir && sizeIdx >= 0 && !c.isNull(sizeIdx)) {
                    val rawSize = c.getLong(sizeIdx)
                    if (rawSize >= 0) rawSize else null
                } else null

                val lastModified = if (modIdx >= 0 && !c.isNull(modIdx)) {
                    val rawMod = c.getLong(modIdx)
                    if (rawMod > 0) rawMod else null
                } else null

                val flags = if (flagsIdx >= 0 && !c.isNull(flagsIdx)) c.getInt(flagsIdx) else null

                buffer.add(
                    SafDocumentMetadata(
                        documentId = docId,
                        displayName = displayName,
                        mimeType = mime,
                        sizeBytes = sizeBytes,
                        lastModifiedTimestamp = lastModified,
                        flags = flags,
                        isDirectory = isDir
                    )
                )

                if (buffer.size >= batchSize) {
                    val keepGoing = onBatch(buffer)
                    buffer.clear()
                    if (!keepGoing) {
                        fullyRead = false
                        break
                    }
                }
            }

            if (fullyRead && buffer.isNotEmpty()) {
                val keepGoing = onBatch(buffer)
                buffer.clear()
                if (!keepGoing) {
                    fullyRead = false
                }
            }
        }

        return fullyRead
    }

    companion object {
        private const val TAG = "AndroidSafTreeReader"
    }
}
