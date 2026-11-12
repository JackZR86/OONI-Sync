package com.androidbackup.app.testutils

import android.net.Uri
import com.androidbackup.app.data.saf.NullCursorException
import com.androidbackup.app.data.saf.SafDocumentMetadata
import com.androidbackup.app.data.saf.SafTreeReader

/**
 * Fake implementation di [SafTreeReader] per test unitari del motore di scansione (Fase 1B.2 Revisione).
 * Simula una struttura gerarchica in memoria senza filesystem fisico reale, con supporto a:
 * - Elaborazione progressiva in streaming a batch controllati ([batchSize])
 * - Simulazione cursore nullo ([NullCursorException])
 * - Directory vuote (restituiscono lista vuota senza eccezioni)
 * - Hook [onDirectoryQueried] per test di interruzione / pausa a metà scansione
 */
class FakeSafTreeReader : SafTreeReader {

    // Chiave: "$treeUri#$parentDocId" -> Lista di figli
    private val childrenMap = mutableMapOf<String, MutableList<SafDocumentMetadata>>()

    // Cartelle configurate per lanciare eccezioni di accesso o cursore nullo
    private val errorMap = mutableMapOf<String, Exception>()

    // Hook opzionale invocato all'interrogazione di una directory (utile per test di pausa mid-scan)
    var onDirectoryQueried: ((parentDocumentId: String) -> Unit)? = null

    // Conteggio dei batch emessi (per asserzioni nei test)
    var totalBatchesEmitted: Int = 0
        private set

    // Dimensione massima dei batch effettivamente emessi (per asserzioni sul limite BATCH_SIZE)
    var maxBatchSizeEmitted: Int = 0
        private set

    // Stato della risorsa/cursore sottostante: simula cursor.use garantendo la chiusura anche in caso di eccezione
    var isResourceClosed: Boolean = true
        private set

    override suspend fun queryChildDocuments(
        treeUri: Uri,
        parentDocumentId: String,
        batchSize: Int,
        onBatch: suspend (List<SafDocumentMetadata>) -> Boolean
    ): Boolean {
        isResourceClosed = false
        try {
            val key = makeKey(treeUri, parentDocumentId)

            onDirectoryQueried?.invoke(parentDocumentId)

            errorMap[key]?.let { exception ->
                throw exception
            }

            val allItems = childrenMap[key]?.toList() ?: emptyList()
            if (allItems.isEmpty()) {
                return true
            }

            val effectiveBatchSize = if (batchSize > 0) batchSize else 100
            val chunks = allItems.chunked(effectiveBatchSize)

            for (chunk in chunks) {
                totalBatchesEmitted++
                if (chunk.size > maxBatchSizeEmitted) {
                    maxBatchSizeEmitted = chunk.size
                }
                val shouldContinue = onBatch(chunk)
                if (!shouldContinue) {
                    return false
                }
            }

            return true
        } finally {
            isResourceClosed = true
        }
    }

    fun addDirectory(
        treeUri: Uri,
        parentDocumentId: String,
        documentId: String,
        displayName: String,
        lastModified: Long? = System.currentTimeMillis()
    ) {
        val key = makeKey(treeUri, parentDocumentId)
        val list = childrenMap.getOrPut(key) { mutableListOf() }
        list.add(
            SafDocumentMetadata(
                documentId = documentId,
                displayName = displayName,
                mimeType = "vnd.android.document/directory",
                sizeBytes = null,
                lastModifiedTimestamp = lastModified,
                flags = 0,
                isDirectory = true
            )
        )
    }

    fun addFile(
        treeUri: Uri,
        parentDocumentId: String,
        documentId: String,
        displayName: String,
        sizeBytes: Long?,
        mimeType: String? = "application/octet-stream",
        lastModified: Long? = System.currentTimeMillis()
    ) {
        val key = makeKey(treeUri, parentDocumentId)
        val list = childrenMap.getOrPut(key) { mutableListOf() }
        list.add(
            SafDocumentMetadata(
                documentId = documentId,
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                lastModifiedTimestamp = lastModified,
                flags = 0,
                isDirectory = false
            )
        )
    }

    fun setDirectoryInaccessible(treeUri: Uri, parentDocumentId: String, exception: Exception = SecurityException("Accesso negato alla cartella")) {
        val key = makeKey(treeUri, parentDocumentId)
        errorMap[key] = exception
    }

    fun setDirectoryNullCursor(treeUri: Uri, parentDocumentId: String) {
        val key = makeKey(treeUri, parentDocumentId)
        errorMap[key] = NullCursorException("ContentProvider ha restituito cursore nullo per $parentDocumentId")
    }

    fun clear() {
        childrenMap.clear()
        errorMap.clear()
        onDirectoryQueried = null
        totalBatchesEmitted = 0
        maxBatchSizeEmitted = 0
        isResourceClosed = true
    }

    private fun makeKey(treeUri: Uri, parentDocId: String): String {
        return "${treeUri}#${parentDocId}"
    }
}
