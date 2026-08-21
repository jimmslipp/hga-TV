package com.hga.media.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hga.media.R
import com.hga.media.ads.AdController
import com.hga.media.data.Channel
import com.hga.media.data.Repo
import com.hga.media.util.Http
import com.hga.media.util.L
import com.hga.media.util.toast
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlayerActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var videoFrame: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var spinner: ProgressBar
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var infoBar: LinearLayout
    private lateinit var infoLogo: ImageView
    private lateinit var infoName: TextView
    private lateinit var infoNow: TextView
    private lateinit var infoNext: TextView
    private lateinit var infoClock: TextView
    private lateinit var infoProgress: ProgressBar
    private lateinit var drawer: LinearLayout
    private lateinit var drawerList: RecyclerView
    private lateinit var drawerSearch: EditText
    private lateinit var drawerCategory: TextView
    private lateinit var drawerFav: TextView
    private lateinit var zapNumber: TextView

    private var player: ExoPlayer? = null
    private var ads: AdController? = null
    private var drawerAdapter: ChannelAdapter? = null

    private var currentChannel: Channel? = null
    private var directUrl: String? = null
    private var directTitle: String? = null

    private var playlist: List<Channel> = emptyList()
    private var categoryId: String = Repo.ALL
    private var showingFavourites = false
    private var retryCount = 0
    /** Set when the owner console asked for an advert preview. */
    private var previewPending = false
    private var zapBuffer = StringBuilder()

    // ------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        UiKit.goFullScreen(this)
        if (!UiKit.ensureLoaded(this)) return

        playlist = Repo.liveChannels
        bindViews()
        ads = AdController(this, root, videoFrame, Repo.prefs).also { controller ->
            controller.onMuteRequest = { muted ->
                player?.volume = if (muted) 0.15f else 1f
            }
        }

        handleIntent(intent)
    }

    /**
     * The activity is singleTask so that a venue screen never stacks up copies
     * of the player. That means a second launch arrives here rather than in
     * onCreate, and we must adopt the new intent by hand.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(source: Intent) {
        previewPending = source.getBooleanExtra(EXTRA_PREVIEW_AD, false)
        directUrl = source.getStringExtra(EXTRA_URL)
        directTitle = source.getStringExtra(EXTRA_TITLE)

        if (directUrl != null) {
            startPlayback(directUrl!!, directTitle ?: "")
            return
        }

        val requested = source.getStringExtra(EXTRA_CHANNEL_ID) ?: Repo.prefs.lastChannelId
        val channel = Repo.channelById(requested) ?: playlist.firstOrNull()
        when {
            channel == null -> showError("There are no channels in this playlist.")
            // During a preview, skip the usual channel-change advert - otherwise it
            // occupies the screen and the preview we actually asked for is dropped.
            channel.id != currentChannel?.id ->
                switchTo(channel, showInterstitial = !previewPending)
        }
    }

    private fun bindViews() {
        root = findViewById(R.id.playerRoot)
        videoFrame = findViewById(R.id.videoFrame)
        playerView = findViewById(R.id.playerView)
        spinner = findViewById(R.id.playerSpinner)
        errorPanel = findViewById(R.id.errorPanel)
        errorText = findViewById(R.id.errorText)
        infoBar = findViewById(R.id.infoBar)
        infoLogo = findViewById(R.id.infoLogo)
        infoName = findViewById(R.id.infoName)
        infoNow = findViewById(R.id.infoNow)
        infoNext = findViewById(R.id.infoNext)
        infoClock = findViewById(R.id.infoClock)
        infoProgress = findViewById(R.id.infoProgress)
        drawer = findViewById(R.id.channelDrawer)
        drawerList = findViewById(R.id.drawerList)
        drawerSearch = findViewById(R.id.drawerSearch)
        drawerCategory = findViewById(R.id.drawerCategory)
        drawerFav = findViewById(R.id.drawerFav)
        zapNumber = findViewById(R.id.zapNumber)

        findViewById<TextView>(R.id.errorRetry).setOnClickListener { retryNow() }

        drawerList.layoutManager = LinearLayoutManager(this)
        drawerAdapter = ChannelAdapter(
            playlist,
            onClick = { channel -> hideDrawer(); switchTo(channel, showInterstitial = true) },
            onFavouriteToggle = { channel ->
                val added = Repo.prefs.toggleFavourite(channel.id)
                toast(if (added) "Added to favourites" else "Removed from favourites")
            }
        )
        drawerList.adapter = drawerAdapter

        drawerSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refreshDrawerList()
        })

        drawerCategory.setOnClickListener { pickCategory() }
        drawerFav.setOnClickListener {
            showingFavourites = !showingFavourites
            drawerFav.isSelected = showingFavourites
            refreshDrawerList()
        }
    }

    override fun onStart() {
        super.onStart()
        ensurePlayer()
    }

    override fun onResume() {
        super.onResume()
        UiKit.goFullScreen(this)
        player?.playWhenReady = true
        ads?.start()
        startClock()

        if (previewPending) {
            previewPending = false
            intent.removeExtra(EXTRA_PREVIEW_AD)
            // Give the stream a moment to actually appear, or an L-bar preview
            // shrinks a black rectangle and looks broken.
            handler.postDelayed({ ads?.previewCurrentMode() }, 4_000)
        }
    }

    override fun onPause() {
        super.onPause()
        player?.playWhenReady = false
        ads?.stop()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        ads?.release()
        player?.release()
        player = null
    }

    // ------------------------------------------------------------ playback
    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setKeepPostFor302Redirects(true)

        val buffer = Repo.prefs.bufferMs.coerceIn(10_000, 120_000)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(buffer / 2, buffer, 2_500, 5_000)
            .build()

        val created = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory))
            .setLoadControl(loadControl)
            .build()

        created.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> spinner.visibility = View.VISIBLE
                    Player.STATE_READY -> {
                        spinner.visibility = View.GONE
                        errorPanel.visibility = View.GONE
                        retryCount = 0
                    }
                    Player.STATE_ENDED -> if (directUrl != null) finish()
                    else -> spinner.visibility = View.GONE
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                L.w("Playback error: ${error.errorCodeName} ${error.message}")
                onStreamFailed(error)
            }
        })

        playerView.player = created
        playerView.resizeMode = resizeModeFor(Repo.prefs.aspectMode)
        player = created
        return created
    }

    private fun resizeModeFor(mode: Int) = when (mode) {
        1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        3 -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    private fun startPlayback(url: String, label: String) {
        val exo = ensurePlayer()
        errorPanel.visibility = View.GONE
        spinner.visibility = View.VISIBLE
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.playWhenReady = true
        infoName.text = label
        if (label.isNotBlank()) showInfoBar()
        L.d("Playing $label")
    }

    /**
     * Channel change. Any interstitial advert is played first, then the new
     * stream starts - so the advert covers the tuning delay rather than adding
     * to it, which is why this mode costs the viewer almost nothing.
     */
    private fun switchTo(channel: Channel, showInterstitial: Boolean) {
        val go = {
            currentChannel = channel
            Repo.prefs.lastChannelId = channel.id
            retryCount = 0
            startPlayback(channel.url, channel.name)
            bindInfoBar(channel)
            drawerAdapter?.highlightId = channel.id
        }
        if (showInterstitial) ads?.onChannelChange(go) ?: go() else go()
    }

    private fun step(direction: Int) {
        val list = currentList()
        if (list.isEmpty()) return
        val index = list.indexOfFirst { it.id == currentChannel?.id }
        val nextIndex = if (index < 0) 0 else (index + direction + list.size) % list.size
        switchTo(list[nextIndex], showInterstitial = true)
    }

    private fun retryNow() {
        errorPanel.visibility = View.GONE
        val channel = currentChannel
        if (channel != null) startPlayback(channel.url, channel.name)
        else directUrl?.let { startPlayback(it, directTitle ?: "") }
    }

    private fun onStreamFailed(error: PlaybackException) {
        spinner.visibility = View.GONE
        retryCount++
        if (retryCount <= 3) {
            // Providers drop connections constantly. Quiet retries first.
            handler.postDelayed({ retryNow() }, 1_500L * retryCount)
            return
        }
        showError(friendlyPlaybackError(error))
    }

    private fun friendlyPlaybackError(error: PlaybackException): String = when {
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
            "Cannot reach the stream. Check this device's internet connection."
        error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "The provider refused this stream. Your line may be at its connection limit."
        error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "That channel is no longer on the server."
        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
            "This device cannot decode that stream. Try a different quality on the same channel."
        else -> "This channel would not start.\n${error.errorCodeName}"
    }

    private fun showError(message: String) {
        errorText.text = message
        errorPanel.visibility = View.VISIBLE
        findViewById<TextView>(R.id.errorRetry).requestFocus()
    }

    // ------------------------------------------------------------ info bar
    private fun startClock() {
        handler.post(object : Runnable {
            override fun run() {
                infoClock.text = SimpleDateFormat("HH:mm", Locale.UK).format(Date())
                currentChannel?.let { updateProgrammeProgress(it) }
                handler.postDelayed(this, 30_000)
            }
        })
    }

    private fun bindInfoBar(channel: Channel) {
        infoName.text = channel.name
        com.hga.media.util.ImageLoader.load(infoLogo, channel.logo, R.drawable.ph_channel)
        showInfoBar()
        updateProgrammeProgress(channel)

        if (Repo.guide.isEmpty) {
            lifecycleScope.launch {
                val listings = Repo.shortEpgFor(channel)
                val now = listings.firstOrNull { it.isNow() }
                val next = listings.firstOrNull { it.startMs > System.currentTimeMillis() }
                if (now != null) {
                    infoNow.text = now.title
                    infoProgress.progress = now.progressPercent()
                }
                infoNext.text = next?.let { "Next: ${it.title}" } ?: ""
            }
        }
    }

    private fun updateProgrammeProgress(channel: Channel) {
        val (now, next) = Repo.guide.nowNext(channel.epgId, channel.name)
        if (now != null) {
            infoNow.text = "${clockOf(now.startMs)} - ${clockOf(now.stopMs)}  ${now.title}"
            infoProgress.progress = now.progressPercent()
        }
        infoNext.text = next?.let { "Next: ${clockOf(it.startMs)}  ${it.title}" } ?: ""
    }

    private fun clockOf(ms: Long) = SimpleDateFormat("HH:mm", Locale.UK).format(Date(ms))

    private fun showInfoBar() {
        infoBar.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfoRunnable)
        handler.postDelayed(hideInfoRunnable, 6_000)
    }

    private val hideInfoRunnable = Runnable { infoBar.visibility = View.GONE }

    // ------------------------------------------------------------ drawer
    private fun currentList(): List<Channel> = when {
        showingFavourites -> Repo.favouriteChannels()
        categoryId == Repo.ALL -> Repo.liveChannels
        else -> Repo.channelsIn(categoryId)
    }

    private fun refreshDrawerList() {
        val query = drawerSearch.text.toString().trim()
        val list = if (query.isNotEmpty()) Repo.searchChannels(query) else currentList()
        drawerAdapter?.highlightId = currentChannel?.id
        drawerAdapter?.submit(list)
        val index = drawerAdapter?.indexOfId(currentChannel?.id) ?: -1
        if (index >= 0) drawerList.scrollToPosition(index)
    }

    private fun showDrawer() {
        if (directUrl != null) return
        refreshDrawerList()
        drawer.visibility = View.VISIBLE
        drawerList.requestFocus()
    }

    private fun hideDrawer() {
        drawer.visibility = View.GONE
        drawerSearch.setText("")
    }

    private fun pickCategory() {
        val visible = Repo.visibleLiveCategories()
        val categories = mutableListOf(getString(R.string.all_channels))
        categories.addAll(visible.map { it.name })
        val current = if (categoryId == Repo.ALL) 0
        else visible.indexOfFirst { it.id == categoryId } + 1
        UiKit.choose(this, "Category", categories, current.coerceAtLeast(0)) { index ->
            categoryId = if (index == 0) Repo.ALL else visible[index - 1].id
            drawerCategory.text = categories[index]
            showingFavourites = false
            drawerFav.isSelected = false
            refreshDrawerList()
        }
    }

    // ------------------------------------------------------------ keys
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val drawerOpen = drawer.visibility == View.VISIBLE

        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (drawerOpen) { hideDrawer(); return true }
                if (infoBar.visibility == View.VISIBLE) { infoBar.visibility = View.GONE; return true }
                finish(); return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (!drawerOpen) { showDrawer(); return true }
            }

            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (!drawerOpen) { step(-1); return true }
            }

            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (!drawerOpen) { step(1); return true }
            }

            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> {
                if (!drawerOpen) { showInfoBar(); return true }
            }

            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_Y -> {
                showOptions(); return true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> {
                player?.let { it.playWhenReady = !it.playWhenReady }
                return true
            }

            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                if (!drawerOpen) { onZapDigit(keyCode - KeyEvent.KEYCODE_0); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun onZapDigit(digit: Int) {
        zapBuffer.append(digit)
        zapNumber.text = zapBuffer.toString()
        zapNumber.visibility = View.VISIBLE
        handler.removeCallbacks(zapCommit)
        handler.postDelayed(zapCommit, 1_800)
    }

    private val zapCommit = Runnable {
        val number = zapBuffer.toString().toIntOrNull()
        zapBuffer = StringBuilder()
        zapNumber.visibility = View.GONE
        if (number == null) return@Runnable
        val target = Repo.liveChannels.firstOrNull { it.num == number }
        if (target != null) switchTo(target, showInterstitial = true)
        else toast("No channel $number")
    }

    private fun showOptions() {
        val channel = currentChannel
        val options = mutableListOf(
            "Picture size: " + listOf("Fit", "Zoom", "Fill", "Stretch width")[Repo.prefs.aspectMode],
            if (channel != null && Repo.prefs.isFavourite(channel.id)) "Remove from favourites" else "Add to favourites",
            "TV guide for this channel",
            "Settings",
            "Owner console"
        )
        UiKit.choose(this, "Options", options, -1) { index ->
            when (index) {
                0 -> {
                    Repo.prefs.aspectMode = (Repo.prefs.aspectMode + 1) % 4
                    playerView.resizeMode = resizeModeFor(Repo.prefs.aspectMode)
                }
                1 -> channel?.let {
                    val added = Repo.prefs.toggleFavourite(it.id)
                    toast(if (added) "Added to favourites" else "Removed from favourites")
                }
                2 -> channel?.let { showGuideFor(it) }
                3 -> startActivity(Intent(this, SettingsActivity::class.java))
                4 -> UiKit.askPin(this, Repo.prefs) {
                    startActivity(Intent(this, OwnerActivity::class.java))
                }
            }
        }
    }

    private fun showGuideFor(channel: Channel) {
        lifecycleScope.launch {
            var listings = Repo.guide.forChannel(channel.epgId, channel.name)
            if (listings.isEmpty()) listings = Repo.shortEpgFor(channel)
            if (listings.isEmpty()) {
                toast("No guide data for this channel")
                return@launch
            }
            val text = listings.take(24).joinToString("\n") {
                "${clockOf(it.startMs)}  ${it.title}"
            }
            UiKit.info(this@PlayerActivity, channel.name, text)
        }
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_PREVIEW_AD = "preview_ad"
    }
}
