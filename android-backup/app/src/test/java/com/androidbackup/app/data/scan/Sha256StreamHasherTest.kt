package com.androidbackup.app.data.scan

import android.net.Uri
import android.provider.DocumentsContract
import com.androidbackup.app.data.saf.NullInputStreamException
import com.androidbackup.app.data.saf.SafDocumentMetadata
import com.androidbackup.app.testutils.FakeSafContentReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Test unitari per [Sha256StreamHasher] e [FakeSafContentReader] (Fase 1B.3A).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Sha256StreamHasherTest {

    private lateinit var contentReader: FakeSafContentReader
    private lateinit var hasher: Sha256StreamHasher

    @Before
    fun setUp() {
        contentReader = FakeSafContentReader()
        hasher = Sha256StreamHasher()
    }

    @Test
    fun testEmptyStreamProducesStandardSha256AndZeroBytesRead() = runBlocking {
        val uri = Uri.parse("content://test.provider/document/empty.bin")
        contentReader.putContent(uri, ByteArray(0))

        val stream = contentReader.openInputStream(uri)
        assertNotNull("Un file vuoto valido da 0 byte deve aprire uno stream non nullo", stream)

        val result = hasher.computeSha256(stream!!)

        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            result.sha256Hex
        )
        assertEquals(0L, result.bytesRead)
        assertTrue(contentReader.allOpenedStreamsClosed)
    }

    @Test
    fun testKnownVectorAbcProducesExpectedSha256AndExactBytesRead() = runBlocking {
        val uri = Uri.parse("content://test.provider/document/abc.txt")
        contentReader.putContent(uri, "abc".toByteArray(Charsets.UTF_8))

        val stream = contentReader.openInputStream(uri)!!
        val result = hasher.computeSha256(stream)

        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            result.sha256Hex
        )
        assertEquals(64, result.sha256Hex.length)
        assertEquals(result.sha256Hex.lowercase(), result.sha256Hex)
        assertEquals(3L, result.bytesRead)
        assertTrue(contentReader.allOpenedStreamsClosed)
    }

    @Test
    fun testSizesSmallerEqualAndLargerThanBufferMatchReferenceDigest() = runBlocking {
        val sizes = listOf(
            1,
            1_024,
            Sha256StreamHasher.BUFFER_SIZE_BYTES - 1,
            Sha256StreamHasher.BUFFER_SIZE_BYTES,
            Sha256StreamHasher.BUFFER_SIZE_BYTES + 1,
            Sha256StreamHasher.BUFFER_SIZE_BYTES * 3 + 777
        )
        val reusableBuffer = ByteArray(Sha256StreamHasher.BUFFER_SIZE_BYTES)

        for (size in sizes) {
            val payload = ByteArray(size) { idx -> ((idx * 31 + 7) and 0xFF).toByte() }
            val expectedHex = referenceSha256Hex(payload)
            val uri = Uri.parse("content://test.provider/document/size_$size.bin")
            contentReader.putContent(uri, payload)

            val stream = contentReader.openInputStream(uri)!!
            val result = hasher.computeSha256(stream, reusableBuffer = reusableBuffer)

            assertEquals("Digest errato per size=$size", expectedHex, result.sha256Hex)
            assertEquals("Conteggio byte errato per size=$size", size.toLong(), result.bytesRead)
        }
        assertTrue(contentReader.allOpenedStreamsClosed)
    }

    @Test
    fun testMultiMegabyteStreamingHashesAccuratelyAndClosesStream() = runBlocking {
        val totalBytes = 6L * 1024L * 1024L + 321L
        val bytePattern: (Long) -> Byte = { idx -> ((idx * 17L + 3L) and 0xFFL).toByte() }
        val uri = Uri.parse("content://test.provider/document/large_6mb.dat")

        contentReader.putStreamingContent(
            documentUri = uri,
            totalBytes = totalBytes,
            byteAt = bytePattern
        )

        // Calcolo di riferimento incrementale senza allocare 6 MB in un singolo array
        val refDigest = MessageDigest.getInstance("SHA-256")
        val chunk = ByteArray(8192)
        var offset = 0L
        while (offset < totalBytes) {
            val len = minOf(chunk.size.toLong(), totalBytes - offset).toInt()
            for (i in 0 until len) {
                chunk[i] = bytePattern(offset + i)
            }
            refDigest.update(chunk, 0, len)
            offset += len
        }
        val expectedHex = Sha256StreamHasher.toLowerHex(refDigest.digest())

        val stream = contentReader.openInputStream(uri)!!
        val result = hasher.computeSha256(stream)

        assertEquals(expectedHex, result.sha256Hex)
        assertEquals(totalBytes, result.bytesRead)
        assertTrue(contentReader.allOpenedStreamsClosed)
    }

    @Test
    fun testIrregularPartialReadsProduceExactSha256() = runBlocking {
        val payload = ByteArray(150_000) { idx -> ((idx * 13 + 5) and 0xFF).toByte() }
        val expectedHex = referenceSha256Hex(payload)
        val stepSizes = intArrayOf(1, 7, 13, 0, 512, 3333, 65536)
        var stepIdx = 0

        val irregularStream = object : InputStream() {
            private var pos = 0

            override fun read(): Int {
                if (pos >= payload.size) return -1
                return payload[pos++].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (pos >= payload.size) return -1
                val step = stepSizes[stepIdx % stepSizes.size]
                stepIdx++
                if (step == 0) return 0
                val toRead = minOf(len, step, payload.size - pos)
                System.arraycopy(payload, pos, b, off, toRead)
                pos += toRead
                return toRead
            }
        }

        val result = hasher.computeSha256(irregularStream)

        assertEquals(expectedHex, result.sha256Hex)
        assertEquals(payload.size.toLong(), result.bytesRead)
    }

    @Test
    fun testNullInputStreamIsDistinctFromValidEmptyFile() = runBlocking {
        val nullUri = Uri.parse("content://test.provider/document/null_stream")
        val emptyUri = Uri.parse("content://test.provider/document/valid_empty")

        contentReader.setOpenInputStreamNull(nullUri)
        contentReader.putContent(emptyUri, ByteArray(0))

        val nullStream = contentReader.openInputStream(nullUri)
        assertNull("openInputStream deve restituire null se il provider restituisce stream nullo", nullStream)

        var caughtNullStreamException = false
        try {
            if (nullStream == null) {
                throw NullInputStreamException("Stream nullo per $nullUri")
            }
        } catch (_: NullInputStreamException) {
            caughtNullStreamException = true
        }
        assertTrue(caughtNullStreamException)

        val emptyResult = hasher.computeSha256(contentReader.openInputStream(emptyUri)!!)
        assertEquals(0L, emptyResult.bytesRead)
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            emptyResult.sha256Hex
        )
        assertTrue(contentReader.allOpenedStreamsClosed)
    }

    @Test
    fun testVirtualDocumentFlagDetectedViaFakeSafContentReaderMetadata() {
        val virtualUri = Uri.parse("content://test.provider/document/virtual_doc")
        contentReader.putMetadata(
            virtualUri,
            SafDocumentMetadata(
                documentId = "doc_virt_1",
                displayName = "Documento Cloud",
                mimeType = "application/vnd.google-apps.document",
                sizeBytes = null,
                lastModifiedTimestamp = 1730000000000L,
                flags = DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT,
                isDirectory = false
            )
        )

        val metadata = contentReader.queryDocumentMetadata(virtualUri)
        assertNotNull(metadata)
        val flags = metadata!!.flags ?: 0
        assertTrue((flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0)
    }

    @Test
    fun testMidReadIoExceptionPropagatesAndClosesStream() = runBlocking {
        val uri = Uri.parse("content://test.provider/document/io_fail")
        val data = ByteArray(200_000) { 0x42 }
        contentReader.setMidReadError(
            documentUri = uri,
            bytes = data,
            failAfterBytes = 100_000L,
            exception = IOException("Errore I/O a metà lettura")
        )

        val stream = contentReader.openInputStream(uri)!!
        var caught: IOException? = null
        try {
            hasher.computeSha256(stream)
            fail("Doveva essere sollevata una IOException a metà lettura")
        } catch (e: IOException) {
            caught = e
        }

        assertNotNull(caught)
        assertTrue(caught!!.message!!.contains("Errore I/O"))
        assertTrue("Lo stream deve essere chiuso anche dopo IOException", contentReader.allOpenedStreamsClosed)
    }

    @Test
    fun testCancelRequestedBeforeAndDuringReadThrowsCancellationExceptionAndClosesStream() = runBlocking {
        // Caso 1: annullamento già richiesto prima dell'avvio
        val uriPre = Uri.parse("content://test.provider/document/cancel_pre")
        contentReader.putContent(uriPre, ByteArray(1024))
        var cancelledPre = false
        try {
            hasher.computeSha256(
                inputStream = contentReader.openInputStream(uriPre)!!,
                isCancelRequested = { true }
            )
            fail("Doveva lanciare CancellationException prima della lettura")
        } catch (_: CancellationException) {
            cancelledPre = true
        }
        assertTrue(cancelledPre)
        assertTrue(contentReader.allOpenedStreamsClosed)

        // Caso 2: annullamento cooperativo durante lettura multi-blocco
        val uriMid = Uri.parse("content://test.provider/document/cancel_mid")
        contentReader.putStreamingContent(uriMid, totalBytes = 512L * 1024L)
        val cancelFlag = AtomicBoolean(false)
        val baseStream = contentReader.openInputStream(uriMid)!!
        val wrappedStream = object : InputStream() {
            private var reads = 0
            override fun read(): Int = baseStream.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = baseStream.read(b, off, len)
                reads++
                if (reads >= 2) {
                    cancelFlag.set(true)
                }
                return n
            }

            override fun close() = baseStream.close()
        }

        var cancelledMid = false
        try {
            hasher.computeSha256(
                inputStream = wrappedStream,
                isCancelRequested = { cancelFlag.get() }
            )
            fail("Doveva lanciare CancellationException durante la lettura")
        } catch (_: CancellationException) {
            cancelledMid = true
        }
        assertTrue(cancelledMid)
        assertTrue(contentReader.allOpenedStreamsClosed)
    }

    @Test
    fun testPauseRequestedThrowsHashingPausedExceptionAndClosesStream() = runBlocking {
        val uri = Uri.parse("content://test.provider/document/pause_mid")
        contentReader.putStreamingContent(uri, totalBytes = 512L * 1024L)
        val pauseFlag = AtomicBoolean(false)
        val baseStream = contentReader.openInputStream(uri)!!
        val wrappedStream = object : InputStream() {
            private var reads = 0
            override fun read(): Int = baseStream.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = baseStream.read(b, off, len)
                reads++
                if (reads >= 2) {
                    pauseFlag.set(true)
                }
                return n
            }

            override fun close() = baseStream.close()
        }

        var paused = false
        try {
            hasher.computeSha256(
                inputStream = wrappedStream,
                isPauseRequested = { pauseFlag.get() }
            )
            fail("Doveva lanciare HashingPausedException")
        } catch (_: HashingPausedException) {
            paused = true
        }

        assertTrue(paused)
        assertTrue(contentReader.allOpenedStreamsClosed)
    }

    @Test
    fun testAbortActiveStreamUnblocksBlockedReadOnCancel() = runBlocking {
        val uri = Uri.parse("content://test.provider/document/blocked_cancel")
        val blockedEntered = CountDownLatch(1)
        val cancelFlag = AtomicBoolean(false)

        contentReader.setBlockingStream(
            documentUri = uri,
            initialBytes = ByteArray(1024) { 0x11 },
            onBlockedEntered = { blockedEntered.countDown() }
        )

        val stream = contentReader.openInputStream(uri)!!
        val deferred = async(Dispatchers.IO) {
            hasher.computeSha256(
                inputStream = stream,
                isCancelRequested = { cancelFlag.get() }
            )
        }

        assertTrue(
            "La lettura avrebbe dovuto raggiungere il punto di blocco",
            blockedEntered.await(3, TimeUnit.SECONDS)
        )

        cancelFlag.set(true)
        hasher.abortActiveStream()

        var caughtCancel = false
        try {
            withTimeout(3_000L) {
                deferred.await()
            }
            fail("Doveva terminare con CancellationException dopo abortActiveStream")
        } catch (_: CancellationException) {
            caughtCancel = true
        }

        assertTrue(caughtCancel)
        assertTrue(contentReader.allOpenedStreamsClosed)
    }

    @Test
    fun testAbortActiveStreamUnblocksBlockedReadOnPause() = runBlocking {
        val uri = Uri.parse("content://test.provider/document/blocked_pause")
        val blockedEntered = CountDownLatch(1)
        val pauseFlag = AtomicBoolean(false)

        contentReader.setBlockingStream(
            documentUri = uri,
            initialBytes = ByteArray(1024) { 0x22 },
            onBlockedEntered = { blockedEntered.countDown() }
        )

        val stream = contentReader.openInputStream(uri)!!
        val deferred = async(Dispatchers.IO) {
            hasher.computeSha256(
                inputStream = stream,
                isPauseRequested = { pauseFlag.get() }
            )
        }

        assertTrue(
            "La lettura avrebbe dovuto raggiungere il punto di blocco",
            blockedEntered.await(3, TimeUnit.SECONDS)
        )

        pauseFlag.set(true)
        hasher.abortActiveStream()

        var caughtPause = false
        try {
            withTimeout(3_000L) {
                deferred.await()
            }
            fail("Doveva terminare con HashingPausedException dopo abortActiveStream in pausa")
        } catch (_: HashingPausedException) {
            caughtPause = true
        }

        assertTrue(caughtPause)
        assertTrue(contentReader.allOpenedStreamsClosed)
    }

    private fun referenceSha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Sha256StreamHasher.toLowerHex(digest)
    }
}
