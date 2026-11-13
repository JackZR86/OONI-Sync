package com.androidbackup.app.data.saf

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * Eccezione specifica sollevata quando [ContentResolver.openInputStream] restituisce `null`
 * per un URI SAF, permettendo di distinguerlo nettamente da un file vuoto valido da 0 byte.
 */
class NullInputStreamException(message: String) : IOException(message)

/**
 * Astrazione per l'accesso in sola lettura ai flussi binari e ai metadati puntuali
 * di singoli documenti tramite Storage Access Framework (SAF).
 *
 * Isola il [android.content.ContentResolver] per consentire test unitari deterministici
 * tramite [FakeSafContentReader] senza dipendere dal filesystem fisico.
 */
interface SafContentReader {

    /**
     * Apre uno stream di input rigorosamente in sola lettura per il documento identificato da [documentUri].
     *
     * @return [InputStream] aperto in sola lettura, oppure `null` se il provider restituisce stream nullo.
     * @throws SecurityException se i permessi di lettura sono mancanti o revocati.
     * @throws FileNotFoundException se il documento non esiste o non è apribile come file regolare.
     * @throws IOException per errori di I/O del provider.
     */
    @Throws(SecurityException::class, FileNotFoundException::class, IOException::class)
    fun openInputStream(documentUri: Uri): InputStream?

    /**
     * Interroga puntualmente i metadati aggiornati del singolo documento identificato da [documentUri].
     *
     * @return [SafDocumentMetadata] correnti (inclusi i flags per rilevare file virtuali),
     *         oppure `null` se il cursore non contiene righe.
     * @throws NullCursorException se il ContentProvider restituisce un Cursor nullo.
     * @throws SecurityException se i permessi di lettura sono negati o revocati.
     * @throws FileNotFoundException se il documento non esiste più.
     * @throws IOException per altri errori di interrogazione del provider.
     */
    @Throws(
        NullCursorException::class,
        SecurityException::class,
        FileNotFoundException::class,
        IOException::class
    )
    fun queryDocumentMetadata(documentUri: Uri): SafDocumentMetadata?
}

/**
 * Implementazione Android nativa di [SafContentReader] basata su [android.content.ContentResolver]
 * e [DocumentsContract]. Non utilizza mai percorsi filesystem assoluti ed opera esclusivamente in sola lettura.
 */
class AndroidSafContentReader(
    private val context: Context
) : SafContentReader {

    override fun openInputStream(documentUri: Uri): InputStream? {
        return try {
            context.contentResolver.openInputStream(documentUri)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permesso negato durante openInputStream per $documentUri: ${e.localizedMessage}")
            throw e
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "Documento non trovato o non apribile in lettura per $documentUri: ${e.localizedMessage}")
            throw e
        } catch (e: IOException) {
            Log.e(TAG, "Errore I/O durante openInputStream per $documentUri: ${e.localizedMessage}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Errore imprevisto durante openInputStream per $documentUri: ${e.localizedMessage}")
            throw IOException("Impossibile aprire lo stream di lettura per $documentUri", e)
        }
    }

    override fun queryDocumentMetadata(documentUri: Uri): SafDocumentMetadata? {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        )

        val cursor = try {
            context.contentResolver.query(documentUri, projection, null, null, null)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permesso negato durante queryDocumentMetadata per $documentUri: ${e.localizedMessage}")
            throw e
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "Documento non trovato durante queryDocumentMetadata per $documentUri: ${e.localizedMessage}")
            throw e
        } catch (e: IOException) {
            Log.e(TAG, "Errore I/O durante queryDocumentMetadata per $documentUri: ${e.localizedMessage}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante queryDocumentMetadata per $documentUri: ${e.localizedMessage}")
            throw IOException("Errore durante l'interrogazione dei metadati SAF per $documentUri", e)
        } ?: throw NullCursorException("Il ContentProvider ha restituito un Cursor nullo per $documentUri")

        cursor.use { c ->
            if (!c.moveToFirst()) {
                return null
            }

            val docIdIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val flagsIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)

            val docId = if (docIdIdx >= 0 && !c.isNull(docIdIdx)) c.getString(docIdIdx) else null
            if (docId.isNullOrBlank()) {
                return null
            }

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

            return SafDocumentMetadata(
                documentId = docId,
                displayName = displayName,
                mimeType = mime,
                sizeBytes = sizeBytes,
                lastModifiedTimestamp = lastModified,
                flags = flags,
                isDirectory = isDir
            )
        }
    }

    companion object {
        private const val TAG = "AndroidSafContentReader"
    }
}
