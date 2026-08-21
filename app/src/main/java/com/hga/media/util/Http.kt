package com.hga.media.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Deliberately tiny HTTP helper built on the JDK so the app carries no extra
 * networking dependency. IPTV portals are frequently plain http and often slow,
 * so timeouts are generous and redirects are followed manually across schemes
 * (HttpURLConnection refuses http -> https redirects on its own).
 */
object Http {

    const val UA = "HGA-Media/1.0 (Android; ExoPlayer)"
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 30_000
    private const val MAX_REDIRECTS = 5

    class HttpException(val code: Int, message: String) : Exception(message)

    private fun open(urlString: String): HttpURLConnection {
        var url = URL(urlString.withScheme())
        var redirects = 0
        while (true) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("Connection", "close")
            }
            val code = conn.responseCode
            if (code in 300..399 && redirects < MAX_REDIRECTS) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw HttpException(code, "Redirect with no Location")
                url = URL(url, location)
                redirects++
                continue
            }
            if (code !in 200..299) {
                val body = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
                conn.disconnect()
                throw HttpException(code, "HTTP $code ${body?.take(180) ?: ""}")
            }
            return conn
        }
    }

    private fun HttpURLConnection.body(): InputStream {
        val raw = BufferedInputStream(inputStream)
        return if (contentEncoding?.contains("gzip", true) == true) GZIPInputStream(raw) else raw
    }

    suspend fun getString(url: String): String = withContext(Dispatchers.IO) {
        val conn = open(url)
        try { conn.body().bufferedReader().use { it.readText() } } finally { conn.disconnect() }
    }

    suspend fun getBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val conn = open(url)
        try {
            val out = ByteArrayOutputStream()
            conn.body().use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
            }
            out.toByteArray()
        } finally { conn.disconnect() }
    }

    /** Streams straight to disk. Used for advert media, which can be large videos. */
    suspend fun download(url: String, target: File): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(target.parentFile, target.name + ".part")
        try {
            val conn = open(url)
            try {
                tmp.parentFile?.mkdirs()
                FileOutputStream(tmp).use { out ->
                    conn.body().use { input ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                    }
                }
            } finally { conn.disconnect() }
            if (target.exists()) target.delete()
            val ok = tmp.renameTo(target)
            if (!ok) tmp.delete()
            ok
        } catch (e: Exception) {
            L.w("download failed $url : ${e.message}")
            tmp.delete()
            false
        }
    }


    /** Gives the caller the raw (de-chunked, de-gzipped-if-encoded) stream. */
    suspend fun <T> withStream(url: String, block: (InputStream) -> T): T =
        withContext(Dispatchers.IO) {
            val conn = open(url)
            try { conn.body().use { block(it) } } finally { conn.disconnect() }
        }

    /** Streams a large document line by line so huge XMLTV files never sit in memory. */
    suspend fun <T> streamLines(url: String, block: (Sequence<String>) -> T): T =
        withContext(Dispatchers.IO) {
            val conn = open(url)
            try {
                conn.body().bufferedReader().use { reader ->
                    block(reader.lineSequence())
                }
            } finally { conn.disconnect() }
        }
}
