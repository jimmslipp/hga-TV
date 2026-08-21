package com.hga.media.data

import android.util.Xml
import com.hga.media.util.Http
import com.hga.media.util.L
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/**
 * Streaming XMLTV reader. Providers routinely serve 50 MB guide files, so this
 * pulls events straight off the socket, keeps only a rolling time window, and
 * never holds the whole document in memory.
 */
object EpgSource {

    /** How far back and forward we keep guide data, in hours. */
    private const val PAST_HOURS = 3
    private const val FUTURE_HOURS = 36
    private const val MAX_EVENTS = 60_000

    data class Guide(
        val byChannelId: Map<String, List<Programme>>,
        val displayNameToId: Map<String, String>
    ) {
        fun nowNext(epgId: String?, name: String?): Pair<Programme?, Programme?> {
            val id = epgId?.takeIf { byChannelId.containsKey(it) }
                ?: name?.let { displayNameToId[it.lowercase().trim()] }
                ?: return null to null
            val list = byChannelId[id] ?: return null to null
            val now = System.currentTimeMillis()
            val current = list.firstOrNull { it.isNow(now) }
            val next = list.firstOrNull { it.startMs > now }
            return current to next
        }

        fun forChannel(epgId: String?, name: String?): List<Programme> {
            val id = epgId?.takeIf { byChannelId.containsKey(it) }
                ?: name?.let { displayNameToId[it.lowercase().trim()] }
                ?: return emptyList()
            return byChannelId[id] ?: emptyList()
        }

        val isEmpty: Boolean get() = byChannelId.isEmpty()

        companion object {
            val EMPTY = Guide(emptyMap(), emptyMap())
        }
    }

    suspend fun load(url: String): Guide {
        if (url.isBlank()) return Guide.EMPTY
        return try {
            Http.withStream(url) { raw -> parse(maybeGunzip(raw)) }
        } catch (e: Exception) {
            L.w("EPG load failed: ${e.message}")
            Guide.EMPTY
        }
    }

    private fun maybeGunzip(input: InputStream): InputStream {
        val pb = PushbackInputStream(input, 2)
        val header = ByteArray(2)
        var read = 0
        while (read < 2) {
            // A single read is allowed to return one byte; that must not be
            // mistaken for "this file is not gzipped".
            val n = pb.read(header, read, 2 - read)
            if (n <= 0) break
            read += n
        }
        if (read > 0) pb.unread(header, 0, read)
        val isGzip = read == 2 &&
                (header[0].toInt() and 0xff) == 0x1f &&
                (header[1].toInt() and 0xff) == 0x8b
        return if (isGzip) GZIPInputStream(pb) else pb
    }

    private fun parse(input: InputStream): Guide {
        val now = System.currentTimeMillis()
        val from = now - PAST_HOURS * 3600_000L
        val to = now + FUTURE_HOURS * 3600_000L

        val programmes = HashMap<String, ArrayList<Programme>>()
        val names = HashMap<String, String>()
        var count = 0

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var event = parser.eventType
        var channelId: String? = null
        var progStart = 0L
        var progStop = 0L
        var progChannel: String? = null
        var title = ""
        var desc = ""
        var inProgramme = false
        var capture: String? = null
        val text = StringBuilder()

        while (event != XmlPullParser.END_DOCUMENT && count < MAX_EVENTS) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "channel" -> channelId = parser.getAttributeValue(null, "id")
                        "display-name" -> if (!inProgramme) { capture = "display-name"; text.setLength(0) }
                        "programme" -> {
                            inProgramme = true
                            progChannel = parser.getAttributeValue(null, "channel")
                            progStart = parseTime(parser.getAttributeValue(null, "start"))
                            progStop = parseTime(parser.getAttributeValue(null, "stop"))
                            title = ""; desc = ""
                        }
                        "title" -> if (inProgramme) { capture = "title"; text.setLength(0) }
                        "desc" -> if (inProgramme) { capture = "desc"; text.setLength(0) }
                    }
                }

                XmlPullParser.TEXT -> if (capture != null && !parser.isWhitespace) text.append(parser.text)

                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "display-name" -> {
                            val id = channelId
                            val value = text.toString().trim()
                            if (!id.isNullOrBlank() && value.isNotEmpty()) {
                                names.putIfAbsentCompat(value.lowercase(), id)
                            }
                            capture = null
                        }
                        "title" -> { title = text.toString().trim(); capture = null }
                        "desc" -> { desc = text.toString().trim(); capture = null }
                        "channel" -> channelId = null
                        "programme" -> {
                            val ch = progChannel
                            if (!ch.isNullOrBlank() && progStop > from && progStart < to && progStop > progStart) {
                                programmes.getOrPut(ch) { ArrayList() }
                                    .add(Programme(ch, progStart, progStop, title.ifBlank { "No information" }, desc))
                                count++
                            }
                            inProgramme = false
                            capture = null
                        }
                    }
                }
            }
            event = parser.next()
        }

        val sorted = programmes.mapValues { entry -> entry.value.sortedBy { it.startMs } }
        L.d("EPG parsed: ${sorted.size} channels, $count events")
        return Guide(sorted, names)
    }

    private fun <K, V> HashMap<K, V>.putIfAbsentCompat(key: K, value: V) {
        if (!containsKey(key)) put(key, value)
    }

    /** XMLTV time: yyyyMMddHHmmss with an optional " +0100" offset. */
    fun parseTime(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val s = raw.trim()
        if (s.length < 14) return 0L
        return try {
            val year = s.substring(0, 4).toInt()
            val month = s.substring(4, 6).toInt()
            val day = s.substring(6, 8).toInt()
            val hour = s.substring(8, 10).toInt()
            val minute = s.substring(10, 12).toInt()
            val second = s.substring(12, 14).toInt()

            var offsetMillis = 0
            val rest = s.substring(14).trim()
            if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
                val sign = if (rest[0] == '-') -1 else 1
                val oh = rest.substring(1, 3).toIntOrNull() ?: 0
                val om = rest.substring(3, 5).toIntOrNull() ?: 0
                offsetMillis = sign * (oh * 3600_000 + om * 60_000)
            }

            val cal = GregorianCalendar(TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(year, month - 1, day, hour, minute, second)
            cal.set(Calendar.MILLISECOND, 0)
            cal.timeInMillis - offsetMillis
        } catch (e: Exception) {
            0L
        }
    }
}
