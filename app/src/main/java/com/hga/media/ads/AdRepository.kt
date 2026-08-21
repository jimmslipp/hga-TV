package com.hga.media.ads

import android.content.Context
import com.hga.media.data.Prefs
import com.hga.media.util.Http
import com.hga.media.util.L
import com.hga.media.util.withScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Fetches the advert manifest, caches every asset on the device, and hands the
 * player a ready-to-show advert. Two sources are tried in order: the primary
 * (normally a machine on your own network) and then the fallback (a public web
 * address). If neither answers, the last good copy on disk keeps running, so a
 * screen in a venue with a dead internet connection still shows your adverts.
 */
object AdRepository {

    private lateinit var appContext: Context
    private lateinit var prefs: Prefs

    private var cached: List<CachedAd> = emptyList()
    private var lastShownId: String? = null

    fun init(context: Context, preferences: Prefs) {
        appContext = context.applicationContext
        prefs = preferences
        loadFromDisk()
    }

    private val adDir: File
        get() = File(appContext.filesDir, "ads").apply { mkdirs() }

    private val manifestFile: File
        get() = File(adDir, "manifest.json")

    private val sourceFile: File
        get() = File(adDir, "manifest.src")

    val adCount: Int get() = cached.size

    val eligibleCount: Int get() = cached.count { it.item.isEligibleNow() }

    fun allCached(): List<CachedAd> = cached

    // ------------------------------------------------------------------ sync
    data class SyncResult(
        val ok: Boolean,
        val message: String,
        val source: String = "",
        val adsAvailable: Int = 0
    )

    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        val sources = listOfNotNull(
            prefs.adPrimaryUrl.takeIf { it.isNotBlank() }?.let { "Network" to it.withScheme("http://") },
            prefs.adFallbackUrl.takeIf { it.isNotBlank() }?.let { "Web" to it.withScheme() }
        )
        if (sources.isEmpty()) {
            val msg = "No advert source configured"
            prefs.adLastSyncResult = msg
            return@withContext SyncResult(false, msg)
        }

        var lastError = ""
        for ((label, url) in sources) {
            try {
                val text = Http.getString(url)
                val manifest = AdManifest.parse(text, prefs.adDisplaySeconds)
                val downloaded = cacheAssets(manifest, url)
                manifestFile.writeText(text)
                // Remember which source answered: asset filenames are derived from
                // the resolved URL, so an offline restart must use the same base.
                sourceFile.writeText(url)
                if (prefs.adUseServerSettings) applySettings(manifest.settings)
                prefs.adManifestVersion = manifest.version
                prefs.adLastSync = System.currentTimeMillis()
                cached = downloaded
                pruneUnused(downloaded)
                val stamp = SimpleDateFormat("d MMM HH:mm", Locale.UK).format(Date())
                val msg = "$label · v${manifest.version} · ${downloaded.size} adverts · $stamp"
                prefs.adLastSyncResult = msg
                L.d("Ad sync ok from $label ($url): ${downloaded.size} adverts")
                return@withContext SyncResult(true, msg, label, downloaded.size)
            } catch (e: Exception) {
                lastError = "$label source failed: ${e.message}"
                L.w(lastError)
            }
        }

        // Nothing reachable. Keep whatever is already on the device.
        loadFromDisk()
        val msg = if (cached.isNotEmpty())
            "Offline - showing ${cached.size} saved adverts ($lastError)"
        else "No adverts available ($lastError)"
        prefs.adLastSyncResult = msg
        SyncResult(false, msg, adsAvailable = cached.size)
    }

    private fun applySettings(s: AdSettings) {
        s.mode?.let { prefs.adMode = it }
        s.intervalSeconds?.let { prefs.adIntervalSeconds = it }
        s.displaySeconds?.let { prefs.adDisplaySeconds = it }
        s.interstitialSeconds?.let { prefs.adInterstitialSeconds = it }
        s.showOnChannelChange?.let { prefs.adOnChannelChange = it }
        s.zapCooldownSeconds?.let { prefs.adZapCooldownSeconds = it }
        s.quietStart?.let { prefs.adQuietStart = it }
        s.quietEnd?.let { prefs.adQuietEnd = it }
        s.syncMinutes?.let { prefs.adSyncMinutes = it }
        s.muteDuringOverlay?.let { prefs.adMuteDuringOverlay = it }
    }

    private suspend fun cacheAssets(manifest: AdManifest, manifestUrl: String): List<CachedAd> {
        val out = ArrayList<CachedAd>()
        for (item in manifest.ads) {
            val mainUrl = absolute(manifestUrl, item.url)
            val mainFile = fileFor(mainUrl)
            if (!mainFile.exists() || mainFile.length() == 0L) {
                if (!Http.download(mainUrl, mainFile)) {
                    L.w("Skipping advert '${item.name}' - could not download $mainUrl")
                    continue
                }
            }
            var lbarFile: File? = null
            item.lbarUrl?.let { rel ->
                val lbarUrl = absolute(manifestUrl, rel)
                val f = fileFor(lbarUrl)
                if (!f.exists() || f.length() == 0L) Http.download(lbarUrl, f)
                if (f.exists() && f.length() > 0) lbarFile = f
            }
            out.add(CachedAd(item, mainFile, lbarFile))
        }
        return out
    }

    /** Lets a manifest reference "ads/summer.jpg" instead of a full address. */
    private fun absolute(baseUrl: String, maybeRelative: String): String {
        if (maybeRelative.startsWith("http://", true) || maybeRelative.startsWith("https://", true))
            return maybeRelative
        return try {
            URI(baseUrl).resolve(maybeRelative).toString()
        } catch (e: Exception) {
            val root = baseUrl.substringBeforeLast('/')
            "$root/${maybeRelative.trimStart('/')}"
        }
    }

    private fun fileFor(url: String): File {
        val hash = MessageDigest.getInstance("MD5").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        // Only look for an extension in the last path segment, or a URL with no
        // filename would drag a slash into the cache filename and create folders.
        val ext = url.substringBefore('?').substringAfterLast('/', "")
            .substringAfterLast('.', "").filter { it.isLetterOrDigit() }.take(5)
            .ifEmpty { "bin" }
        return File(adDir, "$hash.$ext")
    }

    private fun pruneUnused(keep: List<CachedAd>) {
        val wanted = HashSet<String>()
        keep.forEach {
            wanted.add(it.mainFile.name)
            it.lbarFile?.let { f -> wanted.add(f.name) }
        }
        wanted.add(manifestFile.name)
        wanted.add(sourceFile.name)
        adDir.listFiles()?.forEach { f ->
            if (!wanted.contains(f.name)) {
                L.d("Pruning unused advert file ${f.name}")
                f.delete()
            }
        }
    }

    private fun loadFromDisk() {
        if (!manifestFile.exists()) {
            cached = emptyList()
            return
        }
        cached = try {
            val manifest = AdManifest.parse(manifestFile.readText(), prefs.adDisplaySeconds)
            val sourceUrl = if (sourceFile.exists()) sourceFile.readText().trim()
            else prefs.adPrimaryUrl.ifBlank { prefs.adFallbackUrl }
            manifest.ads.mapNotNull { item ->
                val mainFile = fileFor(absolute(sourceUrl, item.url))
                if (!mainFile.exists() || mainFile.length() == 0L) return@mapNotNull null
                val lbar = item.lbarUrl
                    ?.let { fileFor(absolute(sourceUrl, it)) }
                    ?.takeIf { it.exists() && it.length() > 0 }
                CachedAd(item, mainFile, lbar)
            }
        } catch (e: Exception) {
            L.w("Could not read saved adverts: ${e.message}")
            emptyList()
        }
    }

    // ------------------------------------------------------------------ pick
    /**
     * Weighted pick from whatever is eligible right now, avoiding an immediate
     * repeat so the same advert never plays twice back to back.
     */
    fun nextAd(): CachedAd? {
        val eligible = cached.filter { it.item.isEligibleNow() }
        if (eligible.isEmpty()) return null
        val pool = if (eligible.size > 1) eligible.filter { it.item.id != lastShownId } else eligible
        val candidates = pool.ifEmpty { eligible }

        val total = candidates.sumOf { it.item.weight }
        if (total <= 0) return candidates.random()
        var roll = Random.nextInt(total)
        for (ad in candidates) {
            roll -= ad.item.weight
            if (roll < 0) {
                lastShownId = ad.item.id
                return ad
            }
        }
        val fallback = candidates.last()
        lastShownId = fallback.item.id
        return fallback
    }

    fun isQuietTime(): Boolean = AdTime.isQuietNow(prefs.adQuietStart, prefs.adQuietEnd)

    /** True when everything needed to actually show an advert is in place. */
    fun canShow(): Boolean =
        prefs.adsEnabled && prefs.adMode != com.hga.media.data.AdModeNames.OFF &&
                !isQuietTime() && cached.any { it.item.isEligibleNow() }

    fun clearAll() {
        adDir.listFiles()?.forEach { it.delete() }
        cached = emptyList()
        prefs.adManifestVersion = 0
        prefs.adLastSync = 0
        prefs.adLastSyncResult = "Cleared"
    }

    fun diskUsageBytes(): Long = adDir.listFiles()?.sumOf { it.length() } ?: 0L
}
