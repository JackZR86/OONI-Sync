package com.androidbackup.app.data.scan

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * Risultato del calcolo crittografico SHA-256 eseguito in streaming su un [InputStream].
 *
 * @property sha256Hex Impronta esadecimale lowercase a 64 caratteri (compatibile con Google Drive v3 `sha256Checksum`).
 * @property bytesRead Numero effettivo di byte letti dallo stream fino a EOF.
 */
data class StreamHashResult(
    val sha256Hex: String,
    val bytesRead: Long
)

/**
 * Segnale interno/controllato emesso quando il calcolo SHA-256 viene interrotto cooperativamente
 * per una richiesta di pausa dell'utente o del sistema.
 */
class HashingPausedException(
    message: String = "Calcolo SHA-256 interrotto cooperativamente per pausa."
) : Exception(message)

/**
 * Motore di calcolo SHA-256 in streaming a consumo di memoria costante (Fase 1B.3A).
 *
 * Caratteristiche:
 * - Elabora lo stream a blocchi tramite buffer fisso da [BUFFER_SIZE_BYTES] (65.536 byte).
 * - Non carica mai l'intero file in RAM (idoneo per file multi-gigabyte e file vuoti da 0 byte).
 * - Supporta controlli cooperativi di pausa/annullamento e propagazione rigorosa di [CancellationException].
 * - Mantiene un riferimento atomico allo stream attivo ([activeStream]) con metodo [abortActiveStream],
 *   consentendo di tentare lo sblocco dall'esterno di una eventuale `InputStream.read()` bloccante
 *   chiudendo il descrittore sottostante (senza garantire tempistiche fisse, che dipendono dal ContentProvider).
 */
class Sha256StreamHasher {

    private val activeStream = AtomicReference<InputStream?>(null)

    /**
     * Tenta di interrompere una lettura potenzialmente bloccata chiudendo l'eventuale stream attivo.
     * Non garantisce tempistiche deterministiche se il provider SAF sottostante ignora `close()` in I/O nativo.
     */
    fun abortActiveStream() {
        val stream = activeStream.getAndSet(null)
        if (stream != null) {
            try {
                stream.close()
            } catch (_: Exception) {
                // Errori di chiusura durante l'interruzione esterna vengono ignorati in sicurezza
            }
        }
    }

    /**
     * Calcola il digest SHA-256 leggendo [inputStream] fino a EOF e garantendone la chiusura.
     */
    suspend fun computeSha256(
        inputStream: InputStream,
        isPauseRequested: () -> Boolean = { false },
        isCancelRequested: () -> Boolean = { false },
        reusableBuffer: ByteArray? = null
    ): StreamHashResult {
        val coroutineCtx = currentCoroutineContext()

        if (isCancelRequested() || !coroutineCtx.isActive) {
            try {
                inputStream.close()
            } catch (_: Exception) {
            }
            throw CancellationException("Calcolo SHA-256 annullato prima dell'avvio della lettura.")
        }

        if (isPauseRequested()) {
            try {
                inputStream.close()
            } catch (_: Exception) {
            }
            throw HashingPausedException()
        }

        val buffer = if (reusableBuffer != null && reusableBuffer.isNotEmpty()) {
            reusableBuffer
        } else {
            ByteArray(BUFFER_SIZE_BYTES)
        }

        val digest = MessageDigest.getInstance(ALGORITHM_SHA256)
        var totalBytesRead = 0L

        activeStream.set(inputStream)
        try {
            inputStream.use { stream ->
                while (true) {
                    if (isCancelRequested()) {
                        throw CancellationException("Calcolo SHA-256 annullato cooperativamente.")
                    }
                    coroutineCtx.ensureActive()
                    if (isPauseRequested()) {
                        throw HashingPausedException()
                    }

                    val bytesRead = try {
                        stream.read(buffer)
                    } catch (e: IOException) {
                        if (isCancelRequested() || !coroutineCtx.isActive) {
                            throw CancellationException(
                                "Calcolo SHA-256 annullato con chiusura dello stream durante read().",
                                e
                            )
                        }
                        if (isPauseRequested()) {
                            throw HashingPausedException()
                        }
                        throw e
                    }

                    if (bytesRead == -1) {
                        break
                    }

                    if (bytesRead > 0) {
                        digest.update(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                    }
                }
            }
        } finally {
            activeStream.compareAndSet(inputStream, null)
            try {
                inputStream.close()
            } catch (_: Exception) {
            }
        }

        // Controllo finale prima di restituire il risultato: garantisce nessuna consegna su cancellazione/pausa finale
        if (isCancelRequested()) {
            throw CancellationException("Calcolo SHA-256 annullato al termine dello stream.")
        }
        coroutineCtx.ensureActive()
        if (isPauseRequested()) {
            throw HashingPausedException()
        }

        val hex = toLowerHex(digest.digest())
        return StreamHashResult(
            sha256Hex = hex,
            bytesRead = totalBytesRead
        )
    }

    companion object {
        const val ALGORITHM_SHA256 = "SHA-256"
        const val BUFFER_SIZE_BYTES = 65_536
        private val HEX_CHARS = charArrayOf(
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
        )

        fun toLowerHex(bytes: ByteArray): String {
            val out = CharArray(bytes.size * 2)
            for (i in bytes.indices) {
                val v = bytes[i].toInt() and 0xFF
                out[i * 2] = HEX_CHARS[v ushr 4]
                out[i * 2 + 1] = HEX_CHARS[v and 0x0F]
            }
            return String(out)
        }
    }
}
