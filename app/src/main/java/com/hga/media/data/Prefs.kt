package com.hga.media.data

import android.content.Context
import android.content.SharedPreferences
import com.hga.media.util.L
import com.hga.media.util.withScheme
import org.json.JSONObject

/**
 * Every persisted setting in one place. Backed by SharedPreferences so there is
 * no database to migrate and a venue device can be reset by clearing app data.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("hga_media", Context.MODE_PRIVATE)

    // ------------------------------------------------------------ playlist
    var profile: Profile?
        get() = sp.getString(K_PROFILE, null)?.let {
            runCatching { Profile.fromJson(JSONObject(it)) }.getOrNull()
        }
        set(value) {
            if (value == null) sp.edit().remove(K_PROFILE).apply()
            else sp.edit().putString(K_PROFILE, value.toJson().toString()).apply()
        }

    val hasProfile: Boolean get() = profile != null

    var lastPlaylistSync: Long
        get() = sp.getLong(K_PL_SYNC, 0L)
        set(v) = sp.edit().putLong(K_PL_SYNC, v).apply()

    // ------------------------------------------------------------ favourites
    var favourites: MutableSet<String>
        get() = HashSet(sp.getStringSet(K_FAVS, emptySet()) ?: emptySet())
        set(v) = sp.edit().putStringSet(K_FAVS, v).apply()

    fun isFavourite(id: String) = favourites.contains(id)

    // ------------------------------------------------------------ category filter
    /**
     * Categories the venue does not want on screen. Stored as "hidden" rather
     * than "shown" so a new category appearing on the provider's line shows up
     * by default instead of silently vanishing.
     */
    /** Kept per section, so hiding foreign-language live channels does not also
     *  strip the movie list. kind is Kind.LIVE / Kind.VOD / Kind.SERIES. */
    fun hiddenCategories(kind: Int): MutableSet<String> =
        HashSet(sp.getStringSet(K_HIDDEN_CATS + kind, emptySet()) ?: emptySet())

    fun setHiddenCategories(kind: Int, ids: Set<String>) {
        sp.edit().putStringSet(K_HIDDEN_CATS + kind, ids).apply()
    }

    fun showAllCategories(kind: Int) {
        sp.edit().remove(K_HIDDEN_CATS + kind).apply()
    }

    fun showAllCategoriesEverywhere() {
        val e = sp.edit()
        for (k in 0..2) e.remove(K_HIDDEN_CATS + k)
        e.apply()
    }

    fun toggleFavourite(id: String): Boolean {
        val set = favourites
        val added = if (set.contains(id)) { set.remove(id); false } else { set.add(id); true }
        favourites = set
        return added
    }

    // ------------------------------------------------------------ playback
    var lastChannelId: String?
        get() = sp.getString(K_LAST_CH, null)
        set(v) = sp.edit().putString(K_LAST_CH, v).apply()

    var autoStartOnBoot: Boolean
        get() = sp.getBoolean(K_BOOT, false)
        set(v) = sp.edit().putBoolean(K_BOOT, v).apply()

    var autoPlayLastChannel: Boolean
        get() = sp.getBoolean(K_AUTOPLAY, false)
        set(v) = sp.edit().putBoolean(K_AUTOPLAY, v).apply()

    var bufferMs: Int
        get() = sp.getInt(K_BUFFER, 30_000)
        set(v) = sp.edit().putInt(K_BUFFER, v).apply()

    /** 0 = fit, 1 = fill (crop), 2 = stretch, 3 = zoom */
    var aspectMode: Int
        get() = sp.getInt(K_ASPECT, 0)
        set(v) = sp.edit().putInt(K_ASPECT, v).apply()

    // ------------------------------------------------------------ owner
    var ownerPin: String
        get() = sp.getString(K_PIN, DEFAULT_PIN) ?: DEFAULT_PIN
        set(v) = sp.edit().putString(K_PIN, v).apply()

    /** When locked, viewers cannot change the playlist or advert settings. */
    var venueLock: Boolean
        get() = sp.getBoolean(K_VENUE_LOCK, false)
        set(v) = sp.edit().putBoolean(K_VENUE_LOCK, v).apply()

    var deviceLabel: String
        get() = sp.getString(K_DEVICE_LABEL, "") ?: ""
        set(v) = sp.edit().putString(K_DEVICE_LABEL, v).apply()

    // ------------------------------------------------------------ adverts
    var adsEnabled: Boolean
        get() = sp.getBoolean(K_ADS_ON, true)
        set(v) = sp.edit().putBoolean(K_ADS_ON, v).apply()

    /** One of AdMode.LBAR / OVERLAY / INTERSTITIAL / OFF */
    var adMode: String
        get() = sp.getString(K_AD_MODE, AdModeNames.LBAR) ?: AdModeNames.LBAR
        set(v) = sp.edit().putString(K_AD_MODE, v).apply()

    /** Primary source: normally the box on your own network. */
    var adPrimaryUrl: String
        get() = sp.getString(K_AD_URL1, "") ?: ""
        set(v) = sp.edit().putString(K_AD_URL1, v.withScheme()).apply()

    /** Fallback source: a public web address, used when the venue box is unreachable. */
    var adFallbackUrl: String
        get() = sp.getString(K_AD_URL2, "") ?: ""
        set(v) = sp.edit().putString(K_AD_URL2, v.withScheme()).apply()

    var adSyncMinutes: Int
        get() = sp.getInt(K_AD_SYNC_MIN, 60)
        set(v) = sp.edit().putInt(K_AD_SYNC_MIN, v.coerceAtLeast(15)).apply()

    /** Seconds of viewing between advert breaks. */
    var adIntervalSeconds: Int
        get() = sp.getInt(K_AD_INTERVAL, 600)
        set(v) = sp.edit().putInt(K_AD_INTERVAL, v.coerceAtLeast(30)).apply()

    /** How long the L-bar or overlay stays on screen. */
    var adDisplaySeconds: Int
        get() = sp.getInt(K_AD_DISPLAY, 20)
        set(v) = sp.edit().putInt(K_AD_DISPLAY, v.coerceIn(3, 300)).apply()

    /** Length of the full-screen advert shown between channels. */
    var adInterstitialSeconds: Int
        get() = sp.getInt(K_AD_INTER, 6)
        set(v) = sp.edit().putInt(K_AD_INTER, v.coerceIn(1, 60)).apply()

    var adOnChannelChange: Boolean
        get() = sp.getBoolean(K_AD_ON_ZAP, true)
        set(v) = sp.edit().putBoolean(K_AD_ON_ZAP, v).apply()

    /** Minimum seconds between two interstitials, so channel surfing is not punished. */
    var adZapCooldownSeconds: Int
        get() = sp.getInt(K_AD_ZAP_COOL, 180)
        set(v) = sp.edit().putInt(K_AD_ZAP_COOL, v.coerceAtLeast(0)).apply()

    var adMuteDuringOverlay: Boolean
        get() = sp.getBoolean(K_AD_MUTE, false)
        set(v) = sp.edit().putBoolean(K_AD_MUTE, v).apply()

    /** "HH:mm" strings. Adverts are suppressed inside this window. Blank = always on. */
    var adQuietStart: String
        get() = sp.getString(K_AD_QUIET_S, "") ?: ""
        set(v) = sp.edit().putString(K_AD_QUIET_S, v).apply()

    var adQuietEnd: String
        get() = sp.getString(K_AD_QUIET_E, "") ?: ""
        set(v) = sp.edit().putString(K_AD_QUIET_E, v).apply()

    /** When true, timings that arrive in the manifest overwrite the values above. */
    var adUseServerSettings: Boolean
        get() = sp.getBoolean(K_AD_SRV_SET, true)
        set(v) = sp.edit().putBoolean(K_AD_SRV_SET, v).apply()

    var adManifestVersion: Int
        get() = sp.getInt(K_AD_VER, 0)
        set(v) = sp.edit().putInt(K_AD_VER, v).apply()

    var adLastSync: Long
        get() = sp.getLong(K_AD_SYNC, 0L)
        set(v) = sp.edit().putLong(K_AD_SYNC, v).apply()

    var adLastSyncResult: String
        get() = sp.getString(K_AD_SYNC_RES, "Never synced") ?: "Never synced"
        set(v) = sp.edit().putString(K_AD_SYNC_RES, v).apply()

    /** Rolling counters so you can prove impressions to an advertiser. */
    fun recordImpression(adId: String) {
        val key = "imp_$adId"
        sp.edit().putInt(key, sp.getInt(key, 0) + 1).apply()
        sp.edit().putInt(K_IMP_TOTAL, sp.getInt(K_IMP_TOTAL, 0) + 1).apply()
    }

    fun impressions(adId: String) = sp.getInt("imp_$adId", 0)
    val totalImpressions: Int get() = sp.getInt(K_IMP_TOTAL, 0)

    fun resetImpressions() {
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith("imp_") }.forEach { e.remove(it) }
        e.remove(K_IMP_TOTAL)
        e.apply()
    }

    fun wipeAll() {
        sp.edit().clear().apply()
        L.d("Preferences wiped")
    }

    companion object {
        const val DEFAULT_PIN = "4321"

        private const val K_PROFILE = "profile"
        private const val K_PL_SYNC = "playlist_sync"
        private const val K_FAVS = "favourites"
        private const val K_HIDDEN_CATS = "hidden_categories"
        private const val K_LAST_CH = "last_channel"
        private const val K_BOOT = "auto_boot"
        private const val K_AUTOPLAY = "auto_play"
        private const val K_BUFFER = "buffer_ms"
        private const val K_ASPECT = "aspect_mode"
        private const val K_PIN = "owner_pin"
        private const val K_VENUE_LOCK = "venue_lock"
        private const val K_DEVICE_LABEL = "device_label"
        private const val K_ADS_ON = "ads_enabled"
        private const val K_AD_MODE = "ad_mode"
        private const val K_AD_URL1 = "ad_url_primary"
        private const val K_AD_URL2 = "ad_url_fallback"
        private const val K_AD_SYNC_MIN = "ad_sync_minutes"
        private const val K_AD_INTERVAL = "ad_interval"
        private const val K_AD_DISPLAY = "ad_display"
        private const val K_AD_INTER = "ad_interstitial"
        private const val K_AD_ON_ZAP = "ad_on_zap"
        private const val K_AD_ZAP_COOL = "ad_zap_cooldown"
        private const val K_AD_MUTE = "ad_mute"
        private const val K_AD_QUIET_S = "ad_quiet_start"
        private const val K_AD_QUIET_E = "ad_quiet_end"
        private const val K_AD_SRV_SET = "ad_server_settings"
        private const val K_AD_VER = "ad_version"
        private const val K_AD_SYNC = "ad_last_sync"
        private const val K_AD_SYNC_RES = "ad_last_sync_result"
        private const val K_IMP_TOTAL = "imp_total"
    }
}

object AdModeNames {
    const val LBAR = "lbar"
    const val OVERLAY = "overlay"
    const val INTERSTITIAL = "interstitial"
    const val OFF = "off"

    fun label(mode: String) = when (mode) {
        LBAR -> "L-bar quarter panel"
        OVERLAY -> "Corner overlay banner"
        INTERSTITIAL -> "Between channels only"
        else -> "Adverts off"
    }

    fun all() = listOf(LBAR, OVERLAY, INTERSTITIAL, OFF)
}
