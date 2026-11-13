package com.androidbackup.app.testutils

import android.net.Uri
import com.androidbackup.app.data.saf.SafContentReader
import com.androidbackup.app.data.saf.SafDocumentMetadata
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fake implementation di [SafContentReader] per test unitari deterministici della Fase 1B.3.
 *
 * Supporta:
 * - Contenuti binari in memoria e generatori streaming lazy multi-megabyte (senza allocare interi file in RAM)
 * - Simulazione esplicita di `openInputStream() == null` distinta da file vuoti da 0 byte
 * - Errori configurabili su apertura stream, query metadati o a metà lettura (`failAfterBytes`)
 * - Stream bloccanti su `read()` sbloccabili dall'esterno tramite `close()` (es. `Sha256StreamHasher.abortActiveStream()`)
 * - Tracciamento rigoroso di URI aperti, metadati interrogati e chiusura degli stream
 */
class FakeSafContentReader : SafContentReader {

    private sealed class StreamSource {
        data class FixedBytes(val bytes: ByteArray) : StreamSource()
        data class GeneratedStream(
            val totalBytes: Long,
            val byteAt: (index: Long) -> Byte
        ) : StreamSource()
        data class MidReadFailStream(
            val bytes: ByteArray,
            val failAfterBytes: Long,
            val exception: IOException
        ) : StreamSource()
        data class BlockingAfterBytesStream(
            val initialBytes: ByteArray,
            val onBlockedEntered: () -> Unit
        ) : StreamSource()
        object NullStream : StreamSource()
    }

    private val streamSources = mutableMapOf<String, StreamSource>()
    private val openErrors = mutableMapOf<String, Exception>()
    private val metadataMap = mutableMapOf<String, SafDocumentMetadata?>()
    private val metadataErrors = mutableMapOf<String, Exception>()
    private val metadataSequences = mutableMapOf<String, ArrayDeque<() -> SafDocumentMetadata?>>()

    var onQueryMetadata: ((documentUri: Uri) -> Unit)? = null
    var onOpenInputStream: ((documentUri: Uri) -> Unit)? = null

    val openedUris: MutableList<Uri> = Collections.synchronizedList(mutableListOf())
    val queriedUris: MutableList<Uri> = Collections.synchronizedList(mutableListOf())

    private val openedCount = AtomicInteger(0)
    private val closedCount = AtomicInteger(0)

    val openStreamCount: Int
        get() = openedCount.get()

    val closedStreamCount: Int
        get() = closedCount.get()

    val allOpenedStreamsClosed: Boolean
        get() = openedCount.get() == closedCount.get()

    fun putContent(
        documentUri: Uri,
        bytes: ByteArray,
        metadata: SafDocumentMetadata? = null
    ) {
        val key = documentUri.toString()
        streamSources[key] = StreamSource.FixedBytes(bytes)
        if (metadata != null) {
            metadataMap[key] = metadata
        }
    }

    fun putStreamingContent(
        documentUri: Uri,
        totalBytes: Long,
        byteAt: (index: Long) -> Byte = { idx -> (idx and 0xFF).toByte() },
        metadata: SafDocumentMetadata? = null
    ) {
        val key = documentUri.toString()
        streamSources[key] = StreamSource.GeneratedStream(totalBytes, byteAt)
        if (metadata != null) {
            metadataMap[key] = metadata
        }
    }

    fun putMetadata(documentUri: Uri, metadata: SafDocumentMetadata?) {
        metadataMap[documentUri.toString()] = metadata
    }

    fun setMetadataSequence(documentUri: Uri, vararg providers: () -> SafDocumentMetadata?) {
        val queue = ArrayDeque<() -> SafDocumentMetadata?>()
        providers.forEach { queue.addLast(it) }
        metadataSequences[documentUri.toString()] = queue
    }

    fun setOpenInputStreamNull(documentUri: Uri) {
        streamSources[documentUri.toString()] = StreamSource.NullStream
    }

    fun setOpenInputStreamError(documentUri: Uri, exception: Exception) {
        openErrors[documentUri.toString()] = exception
    }

    fun setQueryMetadataError(documentUri: Uri, exception: Exception) {
        metadataErrors[documentUri.toString()] = exception
    }

    fun setMidReadError(
        documentUri: Uri,
        bytes: ByteArray,
        failAfterBytes: Long,
        exception: IOException = IOException("Errore di I/O simulato durante la lettura dello stream SAF")
    ) {
        streamSources[documentUri.toString()] = StreamSource.MidReadFailStream(
            bytes = bytes,
            failAfterBytes = failAfterBytes,
            exception = exception
        )
    }

    fun setBlockingStream(
        documentUri: Uri,
        initialBytes: ByteArray = ByteArray(0),
        onBlockedEntered: () -> Unit = {}
    ) {
        streamSources[documentUri.toString()] = StreamSource.BlockingAfterBytesStream(
            initialBytes = initialBytes,
            onBlockedEntered = onBlockedEntered
        )
    }

    override fun openInputStream(documentUri: Uri): InputStream? {
        openedUris.add(documentUri)
        onOpenInputStream?.invoke(documentUri)
        val key = documentUri.toString()

        openErrors[key]?.let { throw it }

        val source = streamSources[key]
            ?: throw java.io.FileNotFoundException("Nessun contenuto Fake configurato per $documentUri")

        val rawStream: InputStream? = when (source) {
            is StreamSource.NullStream -> null
            is StreamSource.FixedBytes -> ByteArrayInputStream(source.bytes)
            is StreamSource.GeneratedStream -> LazyGeneratedInputStream(source.totalBytes, source.byteAt)
            is StreamSource.MidReadFailStream -> FailingAfterBytesInputStream(
                source.bytes,
                source.failAfterBytes,
                source.exception
            )
            is StreamSource.BlockingAfterBytesStream -> BlockingUntilClosedInputStream(
                source.initialBytes,
                source.onBlockedEntered
            )
        }

        if (rawStream == null) {
            return null
        }

        return TrackingInputStream(rawStream)
    }

    override fun queryDocumentMetadata(documentUri: Uri): SafDocumentMetadata? {
        queriedUris.add(documentUri)
        onQueryMetadata?.invoke(documentUri)
        val key = documentUri.toString()
        val seq = metadataSequences[key]
        if (seq != null && seq.isNotEmpty()) {
            return seq.removeFirst().invoke()
        }
        metadataErrors[key]?.let { throw it }
        return metadataMap[key]
    }

    fun clear() {
        streamSources.clear()
        openErrors.clear()
        metadataMap.clear()
        metadataErrors.clear()
        metadataSequences.clear()
        onQueryMetadata = null
        onOpenInputStream = null
        openedUris.clear()
        queriedUris.clear()
        openedCount.set(0)
        closedCount.set(0)
    }

    private inner class TrackingInputStream(
        private val delegate: InputStream
    ) : InputStream() {
        private val closed = AtomicBoolean(false)

        init {
            openedCount.incrementAndGet()
        }

        override fun read(): Int = delegate.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)

        override fun close() {
            try {
                delegate.close()
            } finally {
                if (closed.compareAndSet(false, true)) {
                    closedCount.incrementAndGet()
                }
            }
        }
    }

    private class LazyGeneratedInputStream(
        private val totalBytes: Long,
        private val byteAt: (index: Long) -> Byte
    ) : InputStream() {
        private var position = 0L

        override fun read(): Int {
            if (position >= totalBytes) return -1
            val b = byteAt(position)
            position++
            return b.toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (position >= totalBytes) return -1
            val remaining = totalBytes - position
            val toRead = minOf(len.toLong(), remaining).toInt()
            for (i in 0 until toRead) {
                b[off + i] = byteAt(position + i)
            }
            position += toRead
            return toRead
        }
    }

    private class FailingAfterBytesInputStream(
        private val bytes: ByteArray,
        private val failAfterBytes: Long,
        private val exception: IOException
    ) : InputStream() {
        private var pos = 0

        override fun read(): Int {
            if (pos.toLong() >= failAfterBytes) {
                throw exception
            }
            if (pos >= bytes.size) return -1
            return bytes[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos.toLong() >= failAfterBytes) {
                throw exception
            }
            if (pos >= bytes.size) return -1
            val allowedBeforeError = (failAfterBytes - pos).toInt().coerceAtLeast(1)
            val toCopy = minOf(len, bytes.size - pos, allowedBeforeError)
            System.arraycopy(bytes, pos, b, off, toCopy)
            pos += toCopy
            return toCopy
        }
    }

    private class BlockingUntilClosedInputStream(
        private val initialBytes: ByteArray,
        private val onBlockedEntered: () -> Unit
    ) : InputStream() {
        private var pos = 0
        private val closedLatch = CountDownLatch(1)
        private val isClosed = AtomicBoolean(false)
        private val notified = AtomicBoolean(false)

        override fun read(): Int {
            val single = ByteArray(1)
            val count = read(single, 0, 1)
            return if (count <= 0) count else single[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (isClosed.get()) {
                throw IOException("Stream chiuso esternamente.")
            }
            if (pos < initialBytes.size) {
                val toCopy = minOf(len, initialBytes.size - pos)
                System.arraycopy(initialBytes, pos, b, off, toCopy)
                pos += toCopy
                return toCopy
            }
            if (notified.compareAndSet(false, true)) {
                onBlockedEntered()
            }
            try {
                closedLatch.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            throw IOException("Lettura sbloccata dalla chiusura dello stream.")
        }

        override fun close() {
            if (isClosed.compareAndSet(false, true)) {
                closedLatch.countDown()
            }
        }
    }
}
