package com.hga.media.ads

import com.hga.media.data.AdModeNames
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * One advert. Everything except id/url is optional so a minimal manifest is
 * three lines long, but the scheduling fields let a campaign be pinned to
 * particular days, hours and date ranges without touching the app.
 */
data class AdItem(
    val id: String,
    val name: String,
    val type: String,               // "image" or "video"
    val url: String,                // full-screen / banner asset
    val lbarUrl: String?,           // optional purpose-made L-shaped asset
    val durationSeconds: Int,
    val weight: Int,
    val startDate: String,          // yyyy-MM-dd, blank = no limit
    val endDate: String,
    val daysOfWeek: Set<Int>,       // 1=Mon .. 7=Sun, empty = every day
    val startTime: String,          // HH:mm, blank = no limit
    val endTime: String,
    val clickText: String
) {
    val isVideo: Boolean get() = type.equals("video", true)

    fun isEligibleNow(cal: Calendar = Calendar.getInstance()): Boolean {
        if (!AdTime.withinDateRange(startDate, endDate, cal)) return false
        if (daysOfWeek.isNotEmpty() && !daysOfWeek.contains(AdTime.isoDayOfWeek(cal))) return false
        if (!AdTime.withinTimeRange(startTime, endTime, cal)) return false
        return true
    }

    companion object {
        fun fromJson(o: JSONObject, defaultDuration: Int): AdItem {
            val days = HashSet<Int>()
            o.optJSONArray("daysOfWeek")?.let { arr ->
                for (i in 0 until arr.length()) days.add(arr.optInt(i))
            }
            val url = o.optString("url").ifBlank { o.optString("file") }
            return AdItem(
                id = o.optString("id").ifBlank { url.hashCode().toString() },
                name = o.optString("name").ifBlank { "Advert" },
                type = o.optString("type").ifBlank { if (looksLikeVideo(url)) "video" else "image" },
                url = url,
                lbarUrl = o.optString("lbarUrl").ifBlank { null },
                durationSeconds = o.optInt("durationSeconds", defaultDuration),
                weight = o.optInt("weight", 1).coerceIn(1, 100),
                startDate = o.optString("startDate"),
                endDate = o.optString("endDate"),
                daysOfWeek = days,
                startTime = o.optString("startTime"),
                endTime = o.optString("endTime"),
                clickText = o.optString("clickText")
            )
        }

        fun looksLikeVideo(url: String): Boolean {
            val u = url.lowercase().substringBefore('?')
            return u.endsWith(".mp4") || u.endsWith(".mkv") || u.endsWith(".webm") ||
                    u.endsWith(".m4v") || u.endsWith(".mov")
        }
    }
}

/** Timing/behaviour that can be pushed from the server so venues stay in step. */
data class AdSettings(
    val mode: String?,
    val intervalSeconds: Int?,
    val displaySeconds: Int?,
    val interstitialSeconds: Int?,
    val showOnChannelChange: Boolean?,
    val zapCooldownSeconds: Int?,
    val quietStart: String?,
    val quietEnd: String?,
    val syncMinutes: Int?,
    val muteDuringOverlay: Boolean?
) {
    companion object {
        val NONE = AdSettings(null, null, null, null, null, null, null, null, null, null)

        fun fromJson(o: JSONObject?): AdSettings {
            if (o == null) return NONE
            fun str(k: String) = o.optString(k).ifBlank { null }
            fun int(k: String) = if (o.has(k)) o.optInt(k) else null
            fun bool(k: String) = if (o.has(k)) o.optBoolean(k) else null
            val mode = str("mode")?.lowercase()?.takeIf { AdModeNames.all().contains(it) }
            return AdSettings(
                mode = mode,
                intervalSeconds = int("intervalSeconds"),
                displaySeconds = int("displaySeconds"),
                interstitialSeconds = int("interstitialSeconds"),
                showOnChannelChange = bool("showOnChannelChange"),
                zapCooldownSeconds = int("zapCooldownSeconds"),
                quietStart = str("quietStart"),
                quietEnd = str("quietEnd"),
                syncMinutes = int("syncMinutes"),
                muteDuringOverlay = bool("muteDuringOverlay")
            )
        }
    }
}

data class AdManifest(
    val version: Int,
    val settings: AdSettings,
    val ads: List<AdItem>
) {
    companion object {
        fun parse(text: String, defaultDuration: Int): AdManifest {
            val root = JSONObject(text)
            val settings = AdSettings.fromJson(root.optJSONObject("settings"))
            val arr = root.optJSONArray("ads")
            val list = ArrayList<AdItem>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val item = AdItem.fromJson(o, settings.displaySeconds ?: defaultDuration)
                    if (item.url.isNotBlank()) list.add(item)
                }
            }
            return AdManifest(root.optInt("version", 0), settings, list)
        }
    }
}

/** An advert plus the on-device files it has been cached to. */
data class CachedAd(
    val item: AdItem,
    val mainFile: File,
    val lbarFile: File?
)

object AdTime {

    fun isoDayOfWeek(cal: Calendar): Int = when (cal.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> 1
        Calendar.TUESDAY -> 2
        Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY -> 4
        Calendar.FRIDAY -> 5
        Calendar.SATURDAY -> 6
        else -> 7
    }

    fun minutesOfDay(cal: Calendar) = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

    fun parseHhMm(s: String?): Int? {
        if (s.isNullOrBlank()) return null
        val parts = s.trim().split(":")
        if (parts.size < 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    /** Handles windows that cross midnight, e.g. 22:00 to 06:00. */
    fun withinTimeRange(start: String?, end: String?, cal: Calendar): Boolean {
        val s = parseHhMm(start)
        val e = parseHhMm(end)
        if (s == null && e == null) return true
        val now = minutesOfDay(cal)
        if (s == null) return now <= e!!
        if (e == null) return now >= s
        return if (s <= e) now in s..e else (now >= s || now <= e)
    }

    fun isQuietNow(quietStart: String?, quietEnd: String?, cal: Calendar = Calendar.getInstance()): Boolean {
        val s = parseHhMm(quietStart) ?: return false
        val e = parseHhMm(quietEnd) ?: return false
        if (s == e) return false
        val now = minutesOfDay(cal)
        return if (s <= e) now in s until e else (now >= s || now < e)
    }

    fun withinDateRange(start: String?, end: String?, cal: Calendar): Boolean {
        val today = String.format(
            "%04d-%02d-%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        )
        if (!start.isNullOrBlank() && today < start) return false
        if (!end.isNullOrBlank() && today > end) return false
        return true
    }
}
