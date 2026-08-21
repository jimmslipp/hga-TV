package com.hga.media.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hga.media.R
import com.hga.media.ads.AdRepository
import com.hga.media.ads.AdSyncWorker
import com.hga.media.data.Repo
import com.hga.media.util.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SplashActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var retry: TextView
    private lateinit var bar: ProgressBar
    private var fromBoot = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        UiKit.goFullScreen(this)

        status = findViewById(R.id.splashStatus)
        retry = findViewById(R.id.splashRetry)
        bar = findViewById(R.id.splashBar)
        fromBoot = intent.getBooleanExtra(EXTRA_FROM_BOOT, false)

        retry.setOnClickListener { begin() }
        begin()
    }

    private fun begin() {
        retry.visibility = View.GONE
        bar.visibility = View.VISIBLE

        if (!Repo.prefs.hasProfile) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        lifecycleScope.launch {
            // Adverts are refreshed in parallel so a venue screen is ready to
            // earn its keep the moment the picture appears.
            launch(Dispatchers.IO) {
                runCatching { AdRepository.sync() }
                    .onSuccess { L.d("Startup advert sync: ${it.message}") }
                AdSyncWorker.schedule(this@SplashActivity, Repo.prefs.adSyncMinutes)
            }

            val ok = Repo.load(force = false) { line ->
                runOnUiThread { status.text = line }
            }

            withContext(Dispatchers.Main) {
                bar.visibility = View.GONE
                if (!ok) {
                    status.text = "Could not load your playlist.\nCheck the network, then try again."
                    retry.visibility = View.VISIBLE
                    retry.requestFocus()
                    return@withContext
                }
                routeOnwards()
            }
        }
    }

    private fun routeOnwards() {
        val prefs = Repo.prefs
        val resumeId = prefs.lastChannelId
        val shouldResume = (prefs.autoPlayLastChannel || fromBoot) &&
                resumeId != null && Repo.channelById(resumeId) != null

        val next = if (shouldResume) {
            Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_CHANNEL_ID, resumeId)
            }
        } else {
            Intent(this, HomeActivity::class.java)
        }
        startActivity(next)
        finish()
    }

    companion object {
        const val EXTRA_FROM_BOOT = "from_boot"
    }
}
