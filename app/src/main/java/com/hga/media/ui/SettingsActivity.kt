package com.hga.media.ui

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hga.media.BuildConfig
import com.hga.media.R
import com.hga.media.data.Kind
import com.hga.media.data.Repo
import com.hga.media.util.ImageLoader
import com.hga.media.util.toast
import kotlinx.coroutines.launch

/**
 * Everyday settings. Anything that affects revenue lives in the owner console
 * instead, behind the PIN.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        UiKit.goFullScreen(this)
        container = findViewById(R.id.settingsContainer)
        build()
    }

    private fun rebuild() {
        container.removeAllViews()
        build()
    }

    private fun build() {
        val prefs = Repo.prefs
        val locked = prefs.venueLock

        UiKit.section(container, "Playlist")

        UiKit.row(
            container,
            "Playlist details",
            prefs.profile?.name ?: "Not set",
            if (locked) "Locked" else "Change"
        ) {
            if (locked) {
                UiKit.askPin(this, prefs) { startActivity(Intent(this, LoginActivity::class.java)) }
            } else {
                startActivity(Intent(this, LoginActivity::class.java))
            }
        }

        UiKit.row(container, "Refresh playlist now", "Pull the latest channels from your provider") {
            toast("Refreshing…")
            lifecycleScope.launch {
                val ok = Repo.load(force = true)
                toast(if (ok) "Playlist updated" else "Refresh failed")
                rebuild()
            }
        }

        UiKit.row(
            container, "Channels loaded", null,
            "${Repo.liveChannels.size} live · ${Repo.movies.size} movies · ${Repo.seriesList.size} series"
        )

        // ---------------------------------------------------------- categories
        UiKit.section(container, "Channel categories")

        for ((kind, label) in listOf(
            Kind.LIVE to "Live TV categories",
            Kind.VOD to "Movie categories",
            Kind.SERIES to "Series categories"
        )) {
            val all = Repo.allCategories(kind)
            val hidden = prefs.hiddenCategories(kind)
            val shown = all.count { !hidden.contains(it.id) }
            UiKit.row(
                container, label,
                "Tick only the ones this screen should show",
                when {
                    all.isEmpty() -> "None loaded"
                    hidden.isEmpty() -> "All ${all.size} showing"
                    else -> "$shown of ${all.size} showing"
                }
            ) {
                if (all.isEmpty()) {
                    toast("Load a playlist first")
                    return@row
                }
                val checked = BooleanArray(all.size) { i -> !hidden.contains(all[i].id) }
                UiKit.multiChoose(this, label, all.map { it.name }, checked) { result ->
                    val nowHidden = HashSet<String>()
                    for (i in all.indices) if (!result[i]) nowHidden.add(all[i].id)
                    if (nowHidden.size == all.size) {
                        toast("Leave at least one category ticked")
                    } else {
                        prefs.setHiddenCategories(kind, nowHidden)
                        toast(
                            if (nowHidden.isEmpty()) "Showing every category"
                            else "Hiding ${nowHidden.size} categories"
                        )
                    }
                    rebuild()
                }
            }
        }

        if ((0..2).any { prefs.hiddenCategories(it).isNotEmpty() }) {
            UiKit.row(container, "Show every category again", "Across all three sections", "Reset") {
                prefs.showAllCategoriesEverywhere()
                toast("All categories showing")
                rebuild()
            }
        }

        UiKit.section(container, "Picture and sound")

        val aspectLabels = listOf("Fit (no cropping)", "Zoom to fill", "Stretch", "Fit width")
        UiKit.row(container, "Picture size", null, aspectLabels[prefs.aspectMode]) {
            UiKit.choose(this, "Picture size", aspectLabels, prefs.aspectMode) { index ->
                prefs.aspectMode = index
                rebuild()
            }
        }

        val bufferLabels = listOf("Low (10s) - fastest zapping", "Normal (30s)", "High (60s) - weak wifi", "Maximum (120s)")
        val bufferValues = listOf(10_000, 30_000, 60_000, 120_000)
        val bufferIndex = bufferValues.indexOf(prefs.bufferMs).coerceAtLeast(1)
        UiKit.row(container, "Buffer size", "Raise this if the picture stutters", bufferLabels[bufferIndex]) {
            UiKit.choose(this, "Buffer size", bufferLabels, bufferIndex) { index ->
                prefs.bufferMs = bufferValues[index]
                toast("Applies next time a channel starts")
                rebuild()
            }
        }

        val profile = prefs.profile
        if (profile != null) {
            UiKit.row(
                container, "Stream format",
                "Switch to HLS if channels stall on this device",
                if (profile.preferHls) "HLS (.m3u8)" else "Standard (.ts)"
            ) {
                prefs.profile = profile.copy(preferHls = !profile.preferHls)
                Repo.clearCache()
                toast("Changed. Refresh the playlist to apply.")
                rebuild()
            }
        }

        UiKit.section(container, "TV guide")

        UiKit.row(
            container, "Guide source",
            "Leave blank to use your provider's built-in guide",
            profile?.epgUrl?.ifBlank { "Provider default" } ?: "Provider default"
        ) {
            UiKit.textInput(this, "XMLTV guide address", profile?.epgUrl ?: "") { value ->
                profile?.let { prefs.profile = it.copy(epgUrl = value) }
                toast("Saved. Refresh the playlist to load it.")
                rebuild()
            }
        }

        UiKit.row(container, "Guide status", null,
            if (Repo.guide.isEmpty) "Not loaded" else "${Repo.guide.byChannelId.size} channels")

        UiKit.section(container, "Storage")

        UiKit.row(container, "Clear image cache", "Frees space used by channel logos and posters") {
            ImageLoader.clearDisk()
            toast("Image cache cleared")
        }

        UiKit.section(container, "About")

        UiKit.row(container, "HGA-Media", "Version ${BuildConfig.VERSION_NAME}", prefs.deviceLabel.ifBlank { "" })

        UiKit.row(container, "Owner console", "Advert and venue settings", "PIN") {
            UiKit.askPin(this, prefs) { startActivity(Intent(this, OwnerActivity::class.java)) }
        }
    }
}
