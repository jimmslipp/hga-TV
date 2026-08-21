package com.hga.media.data

import org.json.JSONArray
import org.json.JSONObject

object Kind {
    const val LIVE = 0
    const val VOD = 1
    const val SERIES = 2
}

object PlaylistType {
    const val XTREAM = "xtream"
    const val M3U = "m3u"
}

data class Profile(
    val id: String = "default",
    val name: String = "",
    val type: String = PlaylistType.XTREAM,
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val epgUrl: String = "",
    /** Some portals only serve .m3u8; flip this if .ts streams stall. */
    val preferHls: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("type", type)
        put("server", server); put("username", username); put("password", password)
        put("m3uUrl", m3uUrl); put("epgUrl", epgUrl); put("preferHls", preferHls)
    }

    companion object {
        fun fromJson(o: JSONObject) = Profile(
            id = o.optString("id", "default"),
            name = o.optString("name"),
            type = o.optString("type", PlaylistType.XTREAM),
            server = o.optString("server"),
            username = o.optString("username"),
            password = o.optString("password"),
            m3uUrl = o.optString("m3uUrl"),
            epgUrl = o.optString("epgUrl"),
            preferHls = o.optBoolean("preferHls", false)
        )
    }
}

data class Category(
    val id: String,
    val name: String,
    val kind: Int
)

data class Channel(
    val id: String,
    val num: Int,
    val name: String,
    val logo: String?,
    val categoryId: String,
    val url: String,
    val epgId: String?,
    val kind: Int = Kind.LIVE,
    val archiveDays: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("num", num); put("name", name)
        put("logo", logo ?: ""); put("cat", categoryId); put("url", url)
        put("epg", epgId ?: ""); put("kind", kind); put("arch", archiveDays)
    }

    companion object {
        fun fromJson(o: JSONObject) = Channel(
            id = o.optString("id"),
            num = o.optInt("num"),
            name = o.optString("name"),
            logo = o.optString("logo").ifBlank { null },
            categoryId = o.optString("cat"),
            url = o.optString("url"),
            epgId = o.optString("epg").ifBlank { null },
            kind = o.optInt("kind", Kind.LIVE),
            archiveDays = o.optInt("arch", 0)
        )
    }
}

data class VodItem(
    val id: String,
    val name: String,
    val cover: String?,
    val categoryId: String,
    val url: String,
    val plot: String = "",
    val rating: String = "",
    val year: String = "",
    val duration: String = ""
)

data class SeriesItem(
    val id: String,
    val name: String,
    val cover: String?,
    val categoryId: String,
    val plot: String = "",
    val rating: String = "",
    val year: String = ""
)

data class Episode(
    val id: String,
    val title: String,
    val season: Int,
    val episode: Int,
    val url: String,
    val plot: String = "",
    val cover: String? = null,
    val durationSecs: Int = 0
)

data class Programme(
    val epgId: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val description: String = ""
) {
    fun isNow(nowMs: Long = System.currentTimeMillis()) = nowMs in startMs until stopMs

    fun progressPercent(nowMs: Long = System.currentTimeMillis()): Int {
        val span = (stopMs - startMs).coerceAtLeast(1)
        return (((nowMs - startMs).toDouble() / span) * 100).toInt().coerceIn(0, 100)
    }
}

/** Helper for stashing lists of channels on disk as JSON. */
object Json {
    fun channelsToString(list: List<Channel>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    fun channelsFromString(s: String): List<Channel> = try {
        val arr = JSONArray(s)
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Channel.fromJson(it) }
        }
    } catch (e: Exception) { emptyList() }

    fun categoriesToString(list: List<Category>): String {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind)) }
        return arr.toString()
    }

    fun categoriesFromString(s: String): List<Category> = try {
        val arr = JSONArray(s)
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Category(it.optString("id"), it.optString("name"), it.optInt("kind")) }
        }
    } catch (e: Exception) { emptyList() }
}
