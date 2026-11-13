package com.androidbackup.app.data.saf

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException

/**
 * Test Robolectric per [AndroidSafContentReader] (Fase 1B.3A).
 * Verifica apertura stream in sola lettura, gestione cursori nulli/vuoti, flag virtuali
 * e propagazione corretta di [SecurityException] e [FileNotFoundException].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AndroidSafContentReaderTest {

    private lateinit var context: Context
    private lateinit var reader: AndroidSafContentReader

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val info = ProviderInfo().apply {
            authority = AUTHORITY
            exported = false
        }
        val provider = Robolectric.buildContentProvider(TestDocumentProvider::class.java)
            .create(info)
            .get()
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
        reader = AndroidSafContentReader(context)
    }

    @Test
    fun testOpenInputStreamReadsBytesInReadOnlyMode() {
        val uri = Uri.parse("content://$AUTHORITY/document/valid_file")
        val bytes = reader.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            input.copyTo(out)
            out.toByteArray()
        }

        assertNotNull(bytes)
        assertEquals("hello-saf-sha256", String(bytes!!, Charsets.UTF_8))
    }

    @Test(expected = FileNotFoundException::class)
    fun testOpenInputStreamPropagatesFileNotFoundException() {
        val uri = Uri.parse("content://$AUTHORITY/document/missing_file")
        reader.openInputStream(uri)
    }

    @Test(expected = SecurityException::class)
    fun testOpenInputStreamPropagatesSecurityException() {
        val uri = Uri.parse("content://$AUTHORITY/document/forbidden_file")
        reader.openInputStream(uri)
    }

    @Test
    fun testQueryDocumentMetadataExtractsFieldsAndVirtualFlag() {
        val uri = Uri.parse("content://$AUTHORITY/document/virtual_doc")
        val metadata = reader.queryDocumentMetadata(uri)

        assertNotNull(metadata)
        assertEquals("virtual_doc", metadata!!.documentId)
        assertEquals("Nota.gdoc", metadata.displayName)
        assertEquals("application/vnd.google-apps.document", metadata.mimeType)
        assertNull(metadata.sizeBytes)
        assertEquals(1730000123000L, metadata.lastModifiedTimestamp)
        assertNotNull(metadata.flags)
        assertTrue((metadata.flags!! and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0)
        assertFalse(metadata.isDirectory)
    }

    @Test(expected = NullCursorException::class)
    fun testQueryDocumentMetadataThrowsNullCursorExceptionOnNullCursor() {
        val uri = Uri.parse("content://$AUTHORITY/document/null_cursor")
        reader.queryDocumentMetadata(uri)
    }

    @Test
    fun testQueryDocumentMetadataReturnsNullOnEmptyCursor() {
        val uri = Uri.parse("content://$AUTHORITY/document/empty_cursor")
        val metadata = reader.queryDocumentMetadata(uri)
        assertNull(metadata)
    }

    @Test(expected = SecurityException::class)
    fun testQueryDocumentMetadataPropagatesSecurityException() {
        val uri = Uri.parse("content://$AUTHORITY/document/forbidden_meta")
        reader.queryDocumentMetadata(uri)
    }

    class TestDocumentProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor? {
            return when (uri.lastPathSegment) {
                "null_cursor" -> null
                "empty_cursor" -> MatrixCursor(
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                        DocumentsContract.Document.COLUMN_FLAGS
                    )
                )
                "forbidden_meta" -> throw SecurityException("Permesso negato su query metadati")
                "virtual_doc" -> MatrixCursor(
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                        DocumentsContract.Document.COLUMN_FLAGS
                    )
                ).apply {
                    addRow(
                        arrayOf<Any?>(
                            "virtual_doc",
                            "Nota.gdoc",
                            "application/vnd.google-apps.document",
                            null,
                            1730000123000L,
                            DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT
                        )
                    )
                }
                else -> throw FileNotFoundException("Documento sconosciuto: $uri")
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            if (mode != "r") {
                throw SecurityException("Consentita solo lettura (mode=$mode)")
            }
            return when (uri.lastPathSegment) {
                "valid_file" -> {
                    val pipe = ParcelFileDescriptor.createPipe()
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                        out.write("hello-saf-sha256".toByteArray(Charsets.UTF_8))
                    }
                    pipe[0]
                }
                "forbidden_file" -> throw SecurityException("Permesso revocato in apertura")
                else -> throw FileNotFoundException("File non trovato: $uri")
            }
        }

        override fun getType(uri: Uri): String? = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    companion object {
        private const val AUTHORITY = "com.androidbackup.app.test.safprovider"
    }
}
