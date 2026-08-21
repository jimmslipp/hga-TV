package com.hga.media.data

import android.content.Context
import com.hga.media.util.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The single source of truth for playlist content. Loads from the provider,
 * caches to disk as JSON so a venue screen comes back up instantly after a
 * power cut, and serves the UI from memory.
 */
object Repo {

    private const val CACHE_TTL_MS = 12 * 60 * 60 * 1000L

    /** Long-running work that must never hold up the screen. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appContext: Context
    lateinit var prefs: Prefs
        private set

    @Volatile var liveCategories: List<Category> = emptyList(); private set
    @Volatile var liveChannels: List<Channel> = emptyList(); private set
    @Volatile var vodCategories: List<Category> = emptyList(); private set
    @Volatile var movies: List<VodItem> = emptyList(); private set
    @Volatile var seriesCategories: List<Category> = emptyList(); private set
    @Volatile var seriesList: List<SeriesItem> = emptyList(); private set
    @Volatile var guide: EpgSource.Guide = EpgSource.Guide.EMPTY; private set

    @Volatile var loaded = false; private set

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = Prefs(appContext)
    }

    private fun cacheFile(name: String) = File(appContext.filesDir, "cache_$name.json")

    val isCacheFresh: Boolean
        get() = cacheFile("live_chans").exists() &&
                (System.currentTimeMillis() - prefs.lastPlaylistSync) < CACHE_TTL_MS

    // ------------------------------------------------------------------ load
    /**
     * @param force ignore the disk cache and go back to the provider
     * @param progress called on a background thread with a short status line
     */
    suspend fun load(force: Boolean, progress: (String) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            val profile = prefs.profile ?: return@withContext false

            if (!force && isCacheFresh && readCache()) {
                loaded = true
                progress("Loaded from cache")
                loadGuideInBackground(profile)
                return@withContext true
            }

            try {
                when (profile.type) {
                    PlaylistType.XTREAM -> loadXtream(profile, progress)
                    else -> loadM3u(profile, progress)
                }
                prefs.lastPlaylistSync = System.currentTimeMillis()
                writeCache()
                loaded = true
                progress("Ready")
                loadGuideInBackground(profile)
                true
            } catch (e: Exception) {
                L.e("Playlist load failed", e)
                progress("Could not reach the server - using saved copy")
                val ok = readCache()
                loaded = ok
                ok
            }
        }

    private suspend fun loadXtream(profile: Profile, progress: (String) -> Unit) {
        val client = XtreamClient(profile)
        progress("Signing in…")
        client.authenticate()

        progress("Loading channel groups…")
        liveCategories = client.categories(Kind.LIVE)
        progress("Loading channels…")
        liveChannels = client.liveChannels()

        progress("Loading movies…")
        vodCategories = runCatching { client.categories(Kind.VOD) }.getOrDefault(emptyList())
        movies = runCatching { client.movies() }.getOrDefault(emptyList())

        progress("Loading series…")
        seriesCategories = runCatching { client.categories(Kind.SERIES) }.getOrDefault(emptyList())
        seriesList = runCatching { client.series() }.getOrDefault(emptyList())

        L.d("Xtream loaded: ${liveChannels.size} live, ${movies.size} movies, ${seriesList.size} series")
    }

    private suspend fun loadM3u(profile: Profile, progress: (String) -> Unit) {
        progress("Downloading playlist…")
        val result = M3uParser.fetchAndParse(profile.m3uUrl)
        val all = result.channels

        liveChannels = all.filter { it.kind == Kind.LIVE }
        liveCategories = result.categories.filter { cat ->
            liveChannels.any { it.categoryId == cat.id }
        }

        val vodChannels = all.filter { it.kind == Kind.VOD }
        movies = vodChannels.map {
            VodItem(it.id, it.name, it.logo, it.categoryId, it.url)
        }
        vodCategories = result.categories
            .filter { cat -> vodChannels.any { it.categoryId == cat.id } }
            .map { it.copy(kind = Kind.VOD) }

        val seriesChannels = all.filter { it.kind == Kind.SERIES }
        seriesList = seriesChannels.map {
            SeriesItem(it.id, it.name, it.logo, it.categoryId)
        }
        seriesCategories = result.categories
            .filter { cat -> seriesChannels.any { it.categoryId == cat.id } }
            .map { it.copy(kind = Kind.SERIES) }

        L.d("M3U loaded: ${liveChannels.size} live, ${movies.size} vod, ${seriesList.size} series")
    }

    /**
     * Fires the guide download off to one side. A full XMLTV file can be tens of
     * megabytes; waiting for it would leave a venue screen sitting on the splash
     * for a minute after every power cut.
     */
    private fun loadGuideInBackground(profile: Profile) {
        val url = when {
            profile.epgUrl.isNotBlank() -> profile.epgUrl
            profile.type == PlaylistType.XTREAM && profile.server.isNotBlank() ->
                "${profile.server.trimEnd('/')}/xmltv.php?username=${profile.username}&password=${profile.password}"
            else -> ""
        }
        if (url.isBlank()) return
        background.launch {
            guide = EpgSource.load(url)
            L.d("Guide ready: ${guide.byChannelId.size} channels")
        }
    }

    /** Used by the info bar when there is no XMLTV file - asks the panel directly. */
    suspend fun shortEpgFor(channel: Channel): List<Programme> {
        val profile = prefs.profile ?: return emptyList()
        if (profile.type != PlaylistType.XTREAM) return emptyList()
        return runCatching { XtreamClient(profile).shortEpg(channel.id) }.getOrDefault(emptyList())
    }

    suspend fun episodesFor(seriesId: String): List<Episode> {
        val profile = prefs.profile ?: return emptyList()
        if (profile.type != PlaylistType.XTREAM) return emptyList()
        return runCatching { XtreamClient(profile).episodes(seriesId) }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------ query
    /** Every category for a section, whether hidden or not. Used by the picker. */
    fun allCategories(kind: Int): List<Category> = when (kind) {
        Kind.VOD -> vodCategories
        Kind.SERIES -> seriesCategories
        else -> liveCategories
    }

    /** The categories a venue has chosen to show for a section. */
    fun visibleCategories(kind: Int): List<Category> {
        val hidden = prefs.hiddenCategories(kind)
        val all = allCategories(kind)
        if (hidden.isEmpty()) return all
        return all.filter { !hidden.contains(it.id) }
    }

    fun visibleLiveCategories(): List<Category> = visibleCategories(Kind.LIVE)

    fun channelsIn(categoryId: String?): List<Channel> {
        val hidden = prefs.hiddenCategories(Kind.LIVE)
        return if (categoryId.isNullOrBlank() || categoryId == ALL) {
            if (hidden.isEmpty()) liveChannels
            else liveChannels.filter { !hidden.contains(it.categoryId) }
        } else {
            liveChannels.filter { it.categoryId == categoryId }
        }
    }

    fun moviesIn(categoryId: String?): List<VodItem> {
        val hidden = prefs.hiddenCategories(Kind.VOD)
        return if (categoryId.isNullOrBlank() || categoryId == ALL) {
            if (hidden.isEmpty()) movies else movies.filter { !hidden.contains(it.categoryId) }
        } else movies.filter { it.categoryId == categoryId }
    }

    fun seriesIn(categoryId: String?): List<SeriesItem> {
        val hidden = prefs.hiddenCategories(Kind.SERIES)
        return if (categoryId.isNullOrBlank() || categoryId == ALL) {
            if (hidden.isEmpty()) seriesList else seriesList.filter { !hidden.contains(it.categoryId) }
        } else seriesList.filter { it.categoryId == categoryId }
    }

    fun favouriteChannels(): List<Channel> {
        val favs = prefs.favourites
        return liveChannels.filter { favs.contains(it.id) }
    }

    fun channelById(id: String?): Channel? =
        if (id == null) null else liveChannels.firstOrNull { it.id == id }

    fun searchChannels(query: String): List<Channel> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val hidden = prefs.hiddenCategories(Kind.LIVE)
        return liveChannels
            .filter { !hidden.contains(it.categoryId) && it.name.lowercase().contains(q) }
            .take(300)
    }

    fun searchMovies(query: String): List<VodItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val hidden = prefs.hiddenCategories(Kind.VOD)
        return movies.filter { !hidden.contains(it.categoryId) && it.name.lowercase().contains(q) }
            .take(300)
    }

    fun searchSeries(query: String): List<SeriesItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val hidden = prefs.hiddenCategories(Kind.SERIES)
        return seriesList.filter { !hidden.contains(it.categoryId) && it.name.lowercase().contains(q) }
            .take(300)
    }

    // ------------------------------------------------------------------ cache
    private fun writeCache() {
        runCatching {
            cacheFile("live_cats").writeText(Json.categoriesToString(liveCategories))
            cacheFile("live_chans").writeText(Json.channelsToString(liveChannels))
            cacheFile("vod_cats").writeText(Json.categoriesToString(vodCategories))
            cacheFile("vod").writeText(vodToString(movies))
            cacheFile("ser_cats").writeText(Json.categoriesToString(seriesCategories))
            cacheFile("series").writeText(seriesToString(seriesList))
        }.onFailure { L.w("cache write failed: ${it.message}") }
    }

    private fun readCache(): Boolean = runCatching {
        val chansFile = cacheFile("live_chans")
        if (!chansFile.exists()) return false
        liveChannels = Json.channelsFromString(chansFile.readText())
        liveCategories = readOr("live_cats") { Json.categoriesFromString(it) }
        vodCategories = readOr("vod_cats") { Json.categoriesFromString(it) }
        movies = readOr("vod") { vodFromString(it) }
        seriesCategories = readOr("ser_cats") { Json.categoriesFromString(it) }
        seriesList = readOr("series") { seriesFromString(it) }
        liveChannels.isNotEmpty()
    }.getOrElse { false }

    private fun <T> readOr(name: String, block: (String) -> List<T>): List<T> {
        val f = cacheFile(name)
        return if (f.exists()) runCatching { block(f.readText()) }.getOrDefault(emptyList()) else emptyList()
    }

    fun clearCache() {
        listOf("live_cats", "live_chans", "vod_cats", "vod", "ser_cats", "series")
            .forEach { cacheFile(it).delete() }
        liveChannels = emptyList(); liveCategories = emptyList()
        movies = emptyList(); vodCategories = emptyList()
        seriesList = emptyList(); seriesCategories = emptyList()
        guide = EpgSource.Guide.EMPTY
        loaded = false
        prefs.lastPlaylistSync = 0L
    }

    private fun vodToString(list: List<VodItem>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("n", it.name); put("c", it.cover ?: "")
                put("cat", it.categoryId); put("u", it.url); put("p", it.plot)
                put("r", it.rating); put("y", it.year)
            })
        }
        return arr.toString()
    }

    private fun vodFromString(s: String): List<VodItem> {
        val arr = JSONArray(s)
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                VodItem(
                    it.optString("id"), it.optString("n"),
                    it.optString("c").ifBlank { null }, it.optString("cat"),
                    it.optString("u"), it.optString("p"), it.optString("r"), it.optString("y")
                )
            }
        }
    }

    private fun seriesToString(list: List<SeriesItem>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("n", it.name); put("c", it.cover ?: "")
                put("cat", it.categoryId); put("p", it.plot); put("r", it.rating); put("y", it.year)
            })
        }
        return arr.toString()
    }

    private fun seriesFromString(s: String): List<SeriesItem> {
        val arr = JSONArray(s)
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                SeriesItem(
                    it.optString("id"), it.optString("n"),
                    it.optString("c").ifBlank { null }, it.optString("cat"),
                    it.optString("p"), it.optString("r"), it.optString("y")
                )
            }
        }
    }

    const val ALL = "__all__"
}
