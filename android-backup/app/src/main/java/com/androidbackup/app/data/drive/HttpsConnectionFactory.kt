package com.androidbackup.app.data.drive

import java.io.IOException
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Astrazione minimale su una connessione HTTPS per consentire test JVM deterministici
 * senza librerie HTTP esterne (OkHttp/MockWebServer).
 */
interface HttpConnectionHandle {
    fun setRequestMethod(method: String)
    fun setConnectTimeout(timeoutMs: Int)
    fun setReadTimeout(timeoutMs: Int)
    fun setUseCaches(useCaches: Boolean)
    fun setRequestProperty(key: String, value: String)
    @Throws(IOException::class)
    fun getResponseCode(): Int
    @Throws(IOException::class)
    fun getInputStream(): InputStream
    fun getErrorStream(): InputStream?
    fun disconnect()
}

/**
 * Factory iniettabile per aprire connessioni HTTPS.
 */
fun interface HttpsConnectionFactory {
    @Throws(IOException::class)
    fun openConnection(url: String): HttpConnectionHandle
}

/**
 * Implementazione di produzione basata esclusivamente su [HttpsURLConnection].
 */
class DefaultHttpsConnectionFactory : HttpsConnectionFactory {
    override fun openConnection(url: String): HttpConnectionHandle {
        val parsedUrl = URL(url)
        if (!parsedUrl.protocol.equals("https", ignoreCase = true)) {
            throw IOException("Sono consentite esclusivamente connessioni HTTPS.")
        }
        val connection = (parsedUrl.openConnection() as? HttpsURLConnection)
            ?: throw IOException("Impossibile aprire una connessione HttpsURLConnection.")

        return object : HttpConnectionHandle {
            override fun setRequestMethod(method: String) {
                connection.requestMethod = method
            }

            override fun setConnectTimeout(timeoutMs: Int) {
                connection.connectTimeout = timeoutMs
            }

            override fun setReadTimeout(timeoutMs: Int) {
                connection.readTimeout = timeoutMs
            }

            override fun setUseCaches(useCaches: Boolean) {
                connection.useCaches = useCaches
            }

            override fun setRequestProperty(key: String, value: String) {
                connection.setRequestProperty(key, value)
            }

            override fun getResponseCode(): Int = connection.responseCode

            override fun getInputStream(): InputStream = connection.inputStream

            override fun getErrorStream(): InputStream? = connection.errorStream

            override fun disconnect() {
                connection.disconnect()
            }
        }
    }
}
