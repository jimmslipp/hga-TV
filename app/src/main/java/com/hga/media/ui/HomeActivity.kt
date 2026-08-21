package com.hga.media.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hga.media.R
import com.hga.media.ads.AdRepository
import com.hga.media.data.AdModeNames
import com.hga.media.data.Kind
import com.hga.media.data.Repo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var resume: TextView
    private lateinit var account: TextView
    private lateinit var adStatus: TextView
    private lateinit var favouritesRow: RecyclerView

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 20_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        UiKit.goFullScreen(this)
        if (!UiKit.ensureLoaded(this)) return

        clock = findViewById(R.id.homeClock)
        date = findViewById(R.id.homeDate)
        resume = findViewById(R.id.homeResume)
        account = findViewById(R.id.homeAccount)
        adStatus = findViewById(R.id.homeAdStatus)
        favouritesRow = findViewById(R.id.homeRecent)

        findViewById<TextView>(R.id.menuLive).setOnClickListener { openBrowse(Kind.LIVE, false) }
        findViewById<TextView>(R.id.menuMovies).setOnClickListener { openBrowse(Kind.VOD, false) }
        findViewById<TextView>(R.id.menuSeries).setOnClickListener { openBrowse(Kind.SERIES, false) }
        findViewById<TextView>(R.id.menuFavourites).setOnClickListener { openBrowse(Kind.LIVE, true) }
        findViewById<TextView>(R.id.menuSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Long press the logo to reach the owner console. Deliberately hidden so
        // staff and customers never stumble into the advert settings.
        findViewById<ImageView>(R.id.homeWordmark).setOnLongClickListener {
            UiKit.askPin(this, Repo.prefs) {
                startActivity(Intent(this, OwnerActivity::class.java))
            }
            true
        }

        favouritesRow.layoutManager = LinearLayoutManager(this)
        findViewById<TextView>(R.id.menuLive).requestFocus()
    }

    override fun onResume() {
        super.onResume()
        updateClock()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 20_000)
        bindResume()
        bindStatus()
        bindFavourites()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun updateClock() {
        val now = Date()
        clock.text = SimpleDateFormat("HH:mm", Locale.UK).format(now)
        date.text = SimpleDateFormat("EEEE d MMMM", Locale.UK).format(now)
    }

    private fun bindResume() {
        val channel = Repo.channelById(Repo.prefs.lastChannelId)
        if (channel == null) {
            resume.visibility = View.GONE
            return
        }
        resume.visibility = View.VISIBLE
        resume.text = "Resume  ·  ${channel.name}"
        resume.setOnClickListener {
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
            })
        }
    }

    private fun bindStatus() {
        val profile = Repo.prefs.profile
        account.text = buildString {
            append(profile?.name ?: "No playlist")
            append("  ·  ")
            append("${Repo.liveChannels.size} channels")
            if (Repo.movies.isNotEmpty()) append("  ·  ${Repo.movies.size} movies")
            if (Repo.seriesList.isNotEmpty()) append("  ·  ${Repo.seriesList.size} series")
        }

        val prefs = Repo.prefs
        adStatus.text = if (!prefs.adsEnabled || prefs.adMode == AdModeNames.OFF) {
            "Adverts off"
        } else {
            "Adverts: ${AdModeNames.label(prefs.adMode)}  ·  " +
                    "${AdRepository.adCount} loaded  ·  ${prefs.adLastSyncResult}"
        }
    }

    private fun bindFavourites() {
        val favourites = Repo.favouriteChannels()
        if (favourites.isEmpty()) {
            favouritesRow.visibility = View.GONE
            return
        }
        favouritesRow.visibility = View.VISIBLE
        favouritesRow.adapter = ChannelAdapter(favourites, onClick = { channel ->
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
            })
        })
    }

    private fun openBrowse(kind: Int, favouritesOnly: Boolean) {
        startActivity(Intent(this, BrowseActivity::class.java).apply {
            putExtra(BrowseActivity.EXTRA_KIND, kind)
            putExtra(BrowseActivity.EXTRA_FAVOURITES, favouritesOnly)
        })
    }
}
