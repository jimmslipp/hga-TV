package com.hga.media.data

import android.util.Base64
import com.hga.media.util.Http
import com.hga.media.util.L
import com.hga.media.util.normaliseBaseUrl
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Talks to a standard Xtream Codes panel (player_api.php). This is the same API
 * IPTV Smarters, TiviMate and friends use, so any line that works in those apps
 * works here.
 */
class XtreamClient(private val profile: Profile) {

    private val base = profile.server.normaliseBaseUrl()
    private val user = enc(profile.username)
    private val pass = enc(profile.password)
    private val pathUser = pathSeg(profile.username)
    private val pathPass = pathSeg(profile.password)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * Query strings and path segments escape differently: a space is "+" in a
     * query but must be "%20" in a path, or accounts with spaces in the
     * username silently produce unplayable stream addresses.
     */
    private fun pathSeg(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun api(action: String, extra: String = ""): String =
        "$base/player_api.php?username=$user&password=$pass" +
                (if (action.isBlank()) "" else "&action=$action") + extra

    /** Returns a human readable summary on success, throws on failure. */
    suspend fun authenticate(): AuthResult {
        val text = Http.getString(api(""))
        val root = JSONObject(text)
        val info = root.optJSONObject("user_info")
            ?: throw IllegalStateException("Server did not return account info")
        val auth = info.optInt("auth", 0)
        val status = info.optString("status", "")
        if (auth != 1 || status.equals("Banned", true) || status.equals("Disabled", true)) {
            throw IllegalStateException("Login rejected by server (status: ${status.ifBlank { "unknown" }})")
        }
        val server = root.optJSONObject("server_info")
        return AuthResult(
            status = status.ifBlank { "Active" },
            expiry = info.optString("exp_date", ""),
            maxConnections = info.optString("max_connections", "1"),
            activeConnections = info.optString("active_cons", "0"),
            timezone = server?.optString("timezone", "") ?: ""
        )
    }

    // -------------------------------------------------------------- categories
    suspend fun categories(kind: Int): List<Category> {
        val action = when (kind) {
            Kind.VOD -> "get_vod_categories"
            Kind.SERIES -> "get_series_categories"
            else -> "get_live_categories"
        }
        val arr = safeArray(Http.getString(api(action)))
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                Category(
                    id = it.optString("category_id"),
                    name = it.optString("category_name").ifBlank { "Unsorted" },
                    kind = kind
                )
            }
        }
    }

    // -------------------------------------------------------------- live
    suspend fun liveChannels(): List<Channel> {
        val arr = safeArray(Http.getString(api("get_live_streams")))
        val ext = if (profile.preferHls) "m3u8" else "ts"
        val out = ArrayList<Channel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("stream_id")
            if (id.isBlank()) continue
            val direct = o.optString("direct_source")
            out.add(
                Channel(
                    id = id,
                    num = o.optInt("num", i + 1),
                    name = o.optString("name").trim().ifBlank { "Channel $id" },
                    logo = o.optString("stream_icon").ifBlank { null },
                    categoryId = o.optString("category_id"),
                    url = if (direct.isNotBlank() && direct.startsWith("http"))
                        direct else "$base/live/$pathUser/$pathPass/$id.$ext",
                    epgId = o.optString("epg_channel_id").ifBlank { null },
                    kind = Kind.LIVE,
                    archiveDays = o.optInt("tv_archive_duration", 0)
                )
            )
        }
        return out
    }

    // -------------------------------------------------------------- movies
    suspend fun movies(): List<VodItem> {
        val arr = safeArray(Http.getString(api("get_vod_streams")))
        val out = ArrayList<VodItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("stream_id")
            if (id.isBlank()) continue
            val extension = o.optString("container_extension").ifBlank { "mp4" }
            val direct = o.optString("direct_source")
            out.add(
                VodItem(
                    id = id,
                    name = o.optString("name").trim(),
                    cover = (o.optString("stream_icon").ifBlank { o.optString("cover") }).ifBlank { null },
                    categoryId = o.optString("category_id"),
                    url = if (direct.isNotBlank() && direct.startsWith("http"))
                        direct else "$base/movie/$pathUser/$pathPass/$id.$extension",
                    plot = o.optString("plot"),
                    rating = o.optString("rating"),
                    year = o.optString("year")
                )
            )
        }
        return out
    }

    // -------------------------------------------------------------- series
    suspend fun series(): List<SeriesItem> {
        val arr = safeArray(Http.getString(api("get_series")))
        val out = ArrayList<SeriesItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("series_id")
            if (id.isBlank()) continue
            out.add(
                SeriesItem(
                    id = id,
                    name = o.optString("name").trim(),
                    cover = o.optString("cover").ifBlank { null },
                    categoryId = o.optString("category_id"),
                    plot = o.optString("plot"),
                    rating = o.optString("rating"),
                    year = o.optString("releaseDate").take(4)
                )
            )
        }
        return out
    }

    suspend fun episodes(seriesId: String): List<Episode> {
        val text = Http.getString(api("get_series_info", "&series_id=${enc(seriesId)}"))
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val seasons = root.optJSONObject("episodes") ?: return emptyList()
        val out = ArrayList<Episode>()
        val keys = seasons.keys()
        while (keys.hasNext()) {
            val seasonKey = keys.next()
            val arr = seasons.optJSONArray(seasonKey) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank()) continue
                val extension = o.optString("container_extension").ifBlank { "mp4" }
                val info = o.optJSONObject("info")
                out.add(
                    Episode(
                        id = id,
                        title = o.optString("title").ifBlank { "Episode ${o.optString("episode_num")}" },
                        season = seasonKey.toIntOrNull() ?: o.optInt("season", 1),
                        episode = o.optString("episode_num").toIntOrNull() ?: (i + 1),
                        url = "$base/series/$pathUser/$pathPass/$id.$extension",
                        plot = info?.optString("plot") ?: "",
                        cover = info?.optString("movie_image")?.ifBlank { null },
                        durationSecs = info?.optInt("duration_secs", 0) ?: 0
                    )
                )
            }
        }
        return out.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    // -------------------------------------------------------------- EPG
    /**
     * Per channel "now and next". Much lighter than pulling a whole XMLTV file,
     * so this is what the channel list and info bar use.
     */
    suspend fun shortEpg(streamId: String, limit: Int = 4): List<Programme> {
        val text = Http.getString(api("get_short_epg", "&stream_id=${enc(streamId)}&limit=$limit"))
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("epg_listings") ?: return emptyList()
        val out = ArrayList<Programme>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val start = o.optString("start_timestamp").toLongOrNull()?.times(1000) ?: continue
            val stop = o.optString("stop_timestamp").toLongOrNull()?.times(1000) ?: continue
            out.add(
                Programme(
                    epgId = streamId,
                    startMs = start,
                    stopMs = stop,
                    title = b64(o.optString("title")),
                    description = b64(o.optString("description"))
                )
            )
        }
        return out.sortedBy { it.startMs }
    }

    private fun b64(s: String): String = try {
        if (s.isBlank()) "" else String(Base64.decode(s, Base64.DEFAULT)).trim()
    } catch (e: Exception) { s }

    private fun safeArray(text: String): JSONArray = try {
        JSONArray(text)
    } catch (e: Exception) {
        L.w("Expected a JSON list, got: ${text.take(160)}")
        JSONArray()
    }

    data class AuthResult(
        val status: String,
        val expiry: String,
        val maxConnections: String,
        val activeConnections: String,
        val timezone: String
    )
}
