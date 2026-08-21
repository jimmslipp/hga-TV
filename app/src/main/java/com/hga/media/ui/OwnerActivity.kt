package com.hga.media.ui

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hga.media.R
import com.hga.media.ads.AdRepository
import com.hga.media.ads.AdSyncWorker
import com.hga.media.data.AdModeNames
import com.hga.media.data.Prefs
import com.hga.media.data.Repo
import com.hga.media.util.toast
import kotlinx.coroutines.launch

/**
 * The advert control panel. Reached by holding the logo on the home screen or
 * from the player options menu, and always behind the owner PIN, so this screen
 * never appears in front of a customer.
 */
class OwnerActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_owner)
        UiKit.goFullScreen(this)
        prefs = Repo.prefs
        container = findViewById(R.id.ownerContainer)
        build()
    }

    private fun rebuild() {
        container.removeAllViews()
        build()
    }

    private fun build() {
        findViewById<TextView>(R.id.ownerImpressions).text =
            "${prefs.totalImpressions} advert plays"

        // ---------------------------------------------------------- delivery
        UiKit.section(container, "Advert delivery")

        UiKit.row(container, "Adverts", "Master switch for this device", UiKit.onOff(prefs.adsEnabled)) {
            prefs.adsEnabled = !prefs.adsEnabled
            if (prefs.adsEnabled) AdSyncWorker.schedule(this, prefs.adSyncMinutes)
            else AdSyncWorker.cancel(this)
            rebuild()
        }

        val modes = AdModeNames.all()
        val modeLabels = listOf(
            "L-bar quarter panel - picture shrinks, nothing is covered",
            "Corner overlay banner - fades over the lower third",
            "Between channels only - full screen while tuning",
            "Off"
        )
        UiKit.row(container, "Display style", null, AdModeNames.label(prefs.adMode)) {
            UiKit.choose(this, "How adverts appear", modeLabels, modes.indexOf(prefs.adMode)) { index ->
                prefs.adMode = modes[index]
                rebuild()
            }
        }

        UiKit.row(container, "Preview on this TV", "Opens the player and shows one advert") {
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_PREVIEW_AD, true)
            })
        }

        // ---------------------------------------------------------- sources
        UiKit.section(container, "Where adverts come from")

        UiKit.row(
            container, "Primary source (your network)",
            "Tried first. Point this at the machine in the venue.",
            prefs.adPrimaryUrl.ifBlank { "Not set" }
        ) {
            UiKit.textInput(this, "Primary advert list address", prefs.adPrimaryUrl) {
                prefs.adPrimaryUrl = it
                rebuild()
            }
        }

        UiKit.row(
            container, "Fallback source (web)",
            "Used whenever the primary cannot be reached",
            prefs.adFallbackUrl.ifBlank { "Not set" }
        ) {
            UiKit.textInput(this, "Fallback advert list address", prefs.adFallbackUrl) {
                prefs.adFallbackUrl = it
                rebuild()
            }
        }

        UiKit.row(container, "Check for new adverts", null, "Every ${prefs.adSyncMinutes} min") {
            val options = listOf(15, 30, 60, 120, 240, 720)
            val labels = options.map { "Every $it minutes" }
            UiKit.choose(this, "Refresh interval", labels, options.indexOf(prefs.adSyncMinutes)) { index ->
                prefs.adSyncMinutes = options[index]
                AdSyncWorker.schedule(this, prefs.adSyncMinutes)
                rebuild()
            }
        }

        UiKit.row(container, "Sync now", prefs.adLastSyncResult, "Run") {
            toast("Checking for adverts…")
            lifecycleScope.launch {
                val result = AdRepository.sync()
                toast(result.message)
                rebuild()
            }
        }

        UiKit.row(
            container, "Take timings from the server",
            "When on, the settings below follow whatever your advert list says",
            UiKit.onOff(prefs.adUseServerSettings)
        ) {
            prefs.adUseServerSettings = !prefs.adUseServerSettings
            rebuild()
        }

        // ---------------------------------------------------------- timings
        UiKit.section(container, "Timings")

        UiKit.row(container, "Gap between advert breaks", null, "${prefs.adIntervalSeconds / 60} min") {
            val options = listOf(60, 120, 300, 600, 900, 1800, 3600)
            val labels = options.map { if (it < 60) "$it seconds" else "${it / 60} minutes" }
            UiKit.choose(this, "Advert break every", labels, options.indexOf(prefs.adIntervalSeconds)) { index ->
                prefs.adIntervalSeconds = options[index]
                rebuild()
            }
        }

        UiKit.row(container, "How long an advert stays up", null, "${prefs.adDisplaySeconds}s") {
            val options = listOf(8, 12, 15, 20, 30, 45, 60)
            UiKit.choose(this, "Advert duration", options.map { "$it seconds" },
                options.indexOf(prefs.adDisplaySeconds)) { index ->
                prefs.adDisplaySeconds = options[index]
                rebuild()
            }
        }

        UiKit.row(container, "Full-screen advert length", "Used between channels", "${prefs.adInterstitialSeconds}s") {
            val options = listOf(3, 4, 5, 6, 8, 10, 15)
            UiKit.choose(this, "Between-channel advert length", options.map { "$it seconds" },
                options.indexOf(prefs.adInterstitialSeconds)) { index ->
                prefs.adInterstitialSeconds = options[index]
                rebuild()
            }
        }

        UiKit.row(
            container, "Advert on channel change",
            "Also applies in L-bar and overlay modes",
            UiKit.onOff(prefs.adOnChannelChange)
        ) {
            prefs.adOnChannelChange = !prefs.adOnChannelChange
            rebuild()
        }

        UiKit.row(
            container, "Minimum gap when channel surfing",
            "Stops a viewer seeing an advert on every press",
            "${prefs.adZapCooldownSeconds}s"
        ) {
            val options = listOf(0, 30, 60, 120, 180, 300, 600)
            UiKit.choose(this, "Minimum gap", options.map { if (it == 0) "No limit" else "$it seconds" },
                options.indexOf(prefs.adZapCooldownSeconds)) { index ->
                prefs.adZapCooldownSeconds = options[index]
                rebuild()
            }
        }

        UiKit.row(
            container, "Duck live sound during overlays",
            "Lowers the TV audio while a video advert plays",
            UiKit.onOff(prefs.adMuteDuringOverlay)
        ) {
            prefs.adMuteDuringOverlay = !prefs.adMuteDuringOverlay
            rebuild()
        }

        val quiet = if (prefs.adQuietStart.isBlank() || prefs.adQuietEnd.isBlank()) "Off"
        else "${prefs.adQuietStart} - ${prefs.adQuietEnd}"
        UiKit.row(container, "Quiet hours", "No adverts at all during this window", quiet) {
            UiKit.textInput(this, "Quiet hours start (HH:mm, blank for off)", prefs.adQuietStart) { start ->
                prefs.adQuietStart = start
                UiKit.textInput(this, "Quiet hours end (HH:mm)", prefs.adQuietEnd) { end ->
                    prefs.adQuietEnd = end
                    rebuild()
                }
            }
        }

        // ---------------------------------------------------------- reporting
        UiKit.section(container, "Reporting")

        UiKit.row(container, "Adverts on this device", null,
            "${AdRepository.adCount} loaded · ${AdRepository.eligibleCount} live now")

        UiKit.row(container, "Play counts", "Per advert, since the last reset", "View") {
            val cached = AdRepository.allCached()
            val body = if (cached.isEmpty()) "No adverts loaded yet."
            else cached.joinToString("\n") { "${it.item.name}: ${prefs.impressions(it.item.id)}" }
            UiKit.info(this, "Advert play counts", body)
        }

        UiKit.row(container, "Reset play counts", null, "Reset") {
            UiKit.confirm(this, "Reset counters", "Clear every advert play count on this device?") {
                prefs.resetImpressions()
                rebuild()
            }
        }

        // ---------------------------------------------------------- venue
        UiKit.section(container, "Venue setup")

        UiKit.row(container, "Device name", "Shows in reports and on the home screen",
            prefs.deviceLabel.ifBlank { "Not set" }) {
            UiKit.textInput(this, "Device name (e.g. Main Bar TV)", prefs.deviceLabel) {
                prefs.deviceLabel = it
                rebuild()
            }
        }

        UiKit.row(container, "Start on power up", "Opens HGA-Media when the device boots",
            UiKit.onOff(prefs.autoStartOnBoot)) {
            prefs.autoStartOnBoot = !prefs.autoStartOnBoot
            rebuild()
        }

        UiKit.row(container, "Resume last channel", "Goes straight to live TV on start",
            UiKit.onOff(prefs.autoPlayLastChannel)) {
            prefs.autoPlayLastChannel = !prefs.autoPlayLastChannel
            rebuild()
        }

        UiKit.row(container, "Venue lock", "Stops anyone changing the playlist without the PIN",
            UiKit.onOff(prefs.venueLock)) {
            prefs.venueLock = !prefs.venueLock
            rebuild()
        }

        UiKit.row(container, "Change owner PIN", "Currently ${"*".repeat(prefs.ownerPin.length)}", "Change") {
            UiKit.textInput(this, "New PIN (4 to 8 digits)", "", numeric = true) { value ->
                if (value.length in 4..8) {
                    prefs.ownerPin = value
                    toast("PIN updated")
                } else toast("PIN must be 4 to 8 digits")
                rebuild()
            }
        }

        // ---------------------------------------------------------- maintenance
        UiKit.section(container, "Maintenance")

        UiKit.row(container, "Advert files on device", null,
            "%.1f MB".format(AdRepository.diskUsageBytes() / 1024.0 / 1024.0))

        UiKit.row(container, "Clear advert cache", "Downloads everything again on the next sync") {
            UiKit.confirm(this, "Clear adverts", "Remove all downloaded advert files?") {
                AdRepository.clearAll()
                rebuild()
            }
        }

        UiKit.row(container, "Factory reset", "Wipes the playlist, adverts and every setting") {
            UiKit.confirm(this, "Factory reset", "This clears everything on this device. Continue?") {
                AdRepository.clearAll()
                Repo.clearCache()
                prefs.wipeAll()
                startActivity(Intent(this, SplashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                finish()
            }
        }
    }
}
