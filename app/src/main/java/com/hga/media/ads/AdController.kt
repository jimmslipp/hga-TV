package com.hga.media.ads

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hga.media.data.AdModeNames
import com.hga.media.data.Prefs
import com.hga.media.util.ImageLoader
import com.hga.media.util.L
import com.hga.media.util.dp

/**
 * Owns everything the viewer actually sees of an advert.
 *
 * Three modes, all switchable at runtime from the owner console or from the
 * server manifest:
 *
 *  LBAR          the picture shrinks into the top-left three quarters of the
 *                screen and the advert fills the resulting L. Nothing is
 *                covered, sound carries on, the viewer misses nothing.
 *  OVERLAY       a banner fades in over the lower third for a few seconds.
 *  INTERSTITIAL  a full-screen advert plays between channels only.
 *
 * The L-bar geometry is deliberately 75% x 75%: on a 16:9 screen that keeps the
 * shrunken picture at exactly 16:9, so live TV is never letterboxed or squashed.
 */
class AdController(
    private val activity: Activity,
    private val root: FrameLayout,
    private val videoFrame: FrameLayout,
    private val prefs: Prefs
) {

    companion object {
        /** Fraction of the screen the live picture keeps during an L-bar break. */
        const val VIDEO_SCALE = 0.75f
        private const val SHRINK_MS = 550L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var adPlayer: ExoPlayer? = null
    private var videoAnim: ValueAnimator? = null

    /** Held so a channel change can never be lost if an advert is interrupted. */
    private var pendingProceed: (() -> Unit)? = null

    private var running = false
    private var showing = false
    private var lastInterstitialAt = 0L

    /** Set by PlayerActivity so the controller can duck live audio if asked. */
    var onMuteRequest: ((Boolean) -> Unit)? = null

    val isShowing: Boolean get() = showing

    // ---------------------------------------------------------------- ad layer
    private val adLayer: FrameLayout = FrameLayout(activity).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        visibility = View.GONE
        isClickable = false
        isFocusable = false
    }

    private val lbarFull = ImageView(activity).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        scaleType = ImageView.ScaleType.FIT_XY
        visibility = View.GONE
    }

    private val lbarBackdrop = View(activity).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(Color.parseColor("#0B0C0E"))
        visibility = View.GONE
    }

    private val lbarRight = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = View.GONE
    }

    private val lbarBottom = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = View.GONE
    }

    private val overlayCard = FrameLayout(activity).apply {
        visibility = View.GONE
    }

    private val overlayImage = ImageView(activity).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private val overlayVideo = PlayerView(activity).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        visibility = View.GONE
    }

    private val interstitial = FrameLayout(activity).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
    }

    private val interImage = ImageView(activity).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private val interVideo = PlayerView(activity).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        visibility = View.GONE
    }

    private val countdown = TextView(activity).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            val m = activity.dp(28f)
            setMargins(m, m, m, m)
        }
        setTextColor(Color.parseColor("#9BA3AB"))
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
    }

    private val adBadge = TextView(activity).apply {
        text = "AD"
        setTextColor(Color.parseColor("#0B0C0E"))
        setBackgroundColor(Color.parseColor("#00B9F1"))
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
        val px = activity.dp(5f)
        setPadding(px * 2, px, px * 2, px)
        visibility = View.GONE
    }

    init {
        adLayer.addView(lbarBackdrop)
        adLayer.addView(lbarFull)
        adLayer.addView(lbarRight)
        adLayer.addView(lbarBottom)

        overlayCard.addView(overlayImage)
        overlayCard.addView(overlayVideo)
        adLayer.addView(overlayCard)

        interstitial.addView(interImage)
        interstitial.addView(interVideo)
        interstitial.addView(countdown)
        adLayer.addView(interstitial)

        adLayer.addView(adBadge, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            val m = activity.dp(14f)
            setMargins(m, m, m, m)
        })

        root.addView(adLayer)
    }

    // ---------------------------------------------------------------- lifecycle
    fun start() {
        if (running) return
        running = true
        scheduleNextBreak()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        hideEverything()
    }

    fun release() {
        stop()
        adPlayer?.release()
        adPlayer = null
    }

    private fun scheduleNextBreak() {
        handler.removeCallbacks(breakRunnable)
        if (!running) return
        val delayMs = prefs.adIntervalSeconds.coerceAtLeast(30) * 1000L
        handler.postDelayed(breakRunnable, delayMs)
        L.d("Next advert break in ${delayMs / 1000}s")
    }

    private val breakRunnable = Runnable {
        if (!running) return@Runnable
        showTimedBreak()
        scheduleNextBreak()
    }

    // ---------------------------------------------------------------- entry points
    /** Called by the interval timer. Respects the current mode. */
    fun showTimedBreak() {
        if (showing || !AdRepository.canShow()) return
        when (prefs.adMode) {
            AdModeNames.LBAR -> pickFor(imagesOnly = true)?.let { showLbar(it) }
            AdModeNames.OVERLAY -> pickFor(imagesOnly = false)?.let { showOverlay(it) }
            else -> Unit // interstitial mode only fires on a channel change
        }
    }

    /**
     * Called before the player switches channel. If an interstitial is due it
     * plays first and [proceed] runs when it finishes; otherwise [proceed] runs
     * straight away so channel surfing stays snappy.
     */
    fun onChannelChange(proceed: () -> Unit) {
        val interstitialsWanted =
            prefs.adMode == AdModeNames.INTERSTITIAL || prefs.adOnChannelChange
        if (!interstitialsWanted || showing || !AdRepository.canShow()) {
            proceed(); return
        }
        val sinceLast = System.currentTimeMillis() - lastInterstitialAt
        if (sinceLast < prefs.adZapCooldownSeconds * 1000L) {
            proceed(); return
        }
        val ad = pickFor(imagesOnly = false)
        if (ad == null) { proceed(); return }
        lastInterstitialAt = System.currentTimeMillis()
        showInterstitial(ad, proceed)
    }

    /** Owner console "preview" button. */
    fun previewCurrentMode() {
        if (showing) {
            // Something else is mid-advert. Wait for it rather than doing nothing,
            // which just looks like the preview button is broken.
            handler.postDelayed({ if (!showing) previewCurrentMode() }, 2_000)
            return
        }
        when (prefs.adMode) {
            AdModeNames.LBAR -> pickFor(true)?.let { showLbar(it) }
            AdModeNames.OVERLAY -> pickFor(false)?.let { showOverlay(it) }
            AdModeNames.INTERSTITIAL -> pickFor(false)?.let { showInterstitial(it) {} }
            else -> Unit
        }
    }

    private fun pickFor(imagesOnly: Boolean): CachedAd? {
        var attempts = 0
        while (attempts < 6) {
            val ad = AdRepository.nextAd() ?: return null
            if (!imagesOnly || !ad.item.isVideo) return ad
            attempts++
        }
        return null
    }

    // ---------------------------------------------------------------- L-bar
    private fun showLbar(ad: CachedAd) {
        val w = root.width
        val h = root.height
        if (w <= 0 || h <= 0) return

        showing = true
        prefs.recordImpression(ad.item.id)

        val videoW = (w * VIDEO_SCALE).toInt()
        val videoH = (h * VIDEO_SCALE).toInt()

        // The backdrop must never cover the live picture: the two arms of the L
        // carry their own background instead.
        lbarBackdrop.visibility = View.GONE
        lbarRight.setBackgroundColor(Color.parseColor("#0B0C0E"))
        lbarBottom.setBackgroundColor(Color.parseColor("#0B0C0E"))
        adLayer.visibility = View.VISIBLE
        adLayer.alpha = 0f
        adLayer.animate().alpha(1f).setDuration(280).start()

        val custom = ad.lbarFile
        if (custom != null) {
            // A purpose-made 16:9 asset with a transparent hole for the picture.
            ImageLoader.decodeFullQuality(custom, w)?.let { lbarFull.setImageBitmap(it) }
            lbarFull.visibility = View.VISIBLE
            lbarRight.visibility = View.GONE
            lbarBottom.visibility = View.GONE
        } else {
            // No bespoke asset: letterbox the ordinary advert into both arms of
            // the L. Never stretched, never cropped.
            val bmp = ImageLoader.decodeFullQuality(ad.mainFile, w)
            lbarFull.visibility = View.GONE
            lbarRight.layoutParams = FrameLayout.LayoutParams(w - videoW, videoH).apply {
                gravity = Gravity.TOP or Gravity.END
            }
            lbarBottom.layoutParams = FrameLayout.LayoutParams(w, h - videoH).apply {
                gravity = Gravity.BOTTOM or Gravity.START
            }
            bmp?.let {
                lbarRight.setImageBitmap(it)
                lbarBottom.setImageBitmap(it)
            }
            lbarRight.visibility = View.VISIBLE
            lbarBottom.visibility = View.VISIBLE
        }

        adBadge.visibility = View.VISIBLE
        animateVideo(videoW, videoH, Gravity.TOP or Gravity.START)

        val holdMs = ad.item.durationSeconds.coerceIn(3, 300) * 1000L
        handler.postDelayed({ endLbar() }, holdMs + SHRINK_MS)
        L.d("L-bar advert '${ad.item.name}' for ${holdMs / 1000}s")
    }

    private fun endLbar() {
        animateVideo(root.width, root.height, Gravity.CENTER)
        adLayer.animate().alpha(0f).setDuration(SHRINK_MS).withEndAction {
            hideEverything()
        }.start()
    }

    private fun animateVideo(targetW: Int, targetH: Int, gravity: Int) {
        videoAnim?.cancel()
        videoAnim = null
        val lp = videoFrame.layoutParams as? FrameLayout.LayoutParams ?: return
        val startW = if (lp.width > 0) lp.width else videoFrame.width
        val startH = if (lp.height > 0) lp.height else videoFrame.height
        if (startW <= 0 || startH <= 0) {
            lp.width = targetW; lp.height = targetH; lp.gravity = gravity
            videoFrame.layoutParams = lp
            return
        }
        lp.gravity = gravity
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = SHRINK_MS
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener { a ->
            val t = a.animatedValue as Float
            val p = videoFrame.layoutParams as? FrameLayout.LayoutParams ?: return@addUpdateListener
            p.width = (startW + (targetW - startW) * t).toInt()
            p.height = (startH + (targetH - startH) * t).toInt()
            p.gravity = gravity
            videoFrame.layoutParams = p
        }
        videoAnim = anim
        anim.start()
    }

    // ---------------------------------------------------------------- overlay
    private fun showOverlay(ad: CachedAd) {
        val w = root.width
        val h = root.height
        if (w <= 0 || h <= 0) return

        showing = true
        prefs.recordImpression(ad.item.id)

        val cardW = (w * 0.62f).toInt()
        val cardH = (h * 0.20f).toInt()
        overlayCard.layoutParams = FrameLayout.LayoutParams(cardW, cardH).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = activity.dp(46f)
        }
        overlayCard.setBackgroundColor(Color.parseColor("#CC0B0C0E"))
        overlayCard.visibility = View.VISIBLE
        overlayCard.alpha = 0f
        overlayCard.translationY = cardH * 0.4f

        adLayer.visibility = View.VISIBLE
        adLayer.alpha = 1f
        adBadge.visibility = View.VISIBLE

        if (ad.item.isVideo) {
            overlayImage.visibility = View.GONE
            overlayVideo.visibility = View.VISIBLE
            playAdVideo(overlayVideo, ad)
            if (prefs.adMuteDuringOverlay) onMuteRequest?.invoke(true)
        } else {
            overlayVideo.visibility = View.GONE
            overlayImage.visibility = View.VISIBLE
            ImageLoader.decodeFullQuality(ad.mainFile, cardW)?.let { overlayImage.setImageBitmap(it) }
        }

        overlayCard.animate().alpha(1f).translationY(0f).setDuration(420).start()

        val holdMs = ad.item.durationSeconds.coerceIn(3, 120) * 1000L
        handler.postDelayed({ endOverlay() }, holdMs)
        L.d("Overlay advert '${ad.item.name}' for ${holdMs / 1000}s")
    }

    private fun endOverlay() {
        onMuteRequest?.invoke(false)
        overlayCard.animate().alpha(0f).translationY(overlayCard.height * 0.4f)
            .setDuration(320).withEndAction { hideEverything() }.start()
    }

    // ---------------------------------------------------------------- interstitial
    private fun showInterstitial(ad: CachedAd, onDone: () -> Unit) {
        showing = true
        pendingProceed = onDone
        prefs.recordImpression(ad.item.id)

        adLayer.visibility = View.VISIBLE
        adLayer.alpha = 1f
        interstitial.visibility = View.VISIBLE
        adBadge.visibility = View.VISIBLE

        var seconds = if (ad.item.isVideo)
            ad.item.durationSeconds.coerceIn(2, 60)
        else
            prefs.adInterstitialSeconds.coerceIn(1, 60)

        if (ad.item.isVideo) {
            interImage.visibility = View.GONE
            interVideo.visibility = View.VISIBLE
            playAdVideo(interVideo, ad)
        } else {
            interVideo.visibility = View.GONE
            interImage.visibility = View.VISIBLE
            ImageLoader.decodeFullQuality(ad.mainFile, root.width.coerceAtLeast(1280))
                ?.let { interImage.setImageBitmap(it) }
        }

        val tick = object : Runnable {
            override fun run() {
                if (seconds <= 0) {
                    // hideEverything() invokes pendingProceed, so the channel
                    // change happens exactly once even if we are interrupted.
                    hideEverything()
                    return
                }
                countdown.text = activity.getString(
                    com.hga.media.R.string.ad_skip_in, seconds
                )
                seconds--
                handler.postDelayed(this, 1000L)
            }
        }
        handler.post(tick)
        L.d("Interstitial advert '${ad.item.name}'")
    }

    // ---------------------------------------------------------------- helpers
    private fun playAdVideo(view: PlayerView, ad: CachedAd) {
        val player = adPlayer ?: ExoPlayer.Builder(activity).build().also { adPlayer = it }
        // Media3 allows a player on one view at a time; detach the other first.
        if (overlayVideo !== view) overlayVideo.player = null
        if (interVideo !== view) interVideo.player = null
        view.player = player
        // If we are ducking the live audio, the advert is meant to be heard.
        player.volume = if (prefs.adMuteDuringOverlay) 1f else 0f
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(ad.mainFile)))
        player.prepare()
        player.playWhenReady = true
    }

    private fun hideEverything() {
        handler.removeCallbacksAndMessages(null)
        showing = false

        // Whatever happens, a channel change that was waiting behind an advert
        // must still go ahead. Losing it would strand the viewer on a dead screen.
        val proceed = pendingProceed
        pendingProceed = null
        onMuteRequest?.invoke(false)

        adPlayer?.let {
            it.playWhenReady = false
            it.stop()
            it.clearMediaItems()
        }
        overlayVideo.player = null
        interVideo.player = null

        adLayer.visibility = View.GONE
        adLayer.alpha = 1f
        lbarFull.visibility = View.GONE
        lbarRight.visibility = View.GONE
        lbarBottom.visibility = View.GONE
        lbarBackdrop.visibility = View.GONE
        overlayCard.visibility = View.GONE
        interstitial.visibility = View.GONE
        interVideo.visibility = View.GONE
        overlayVideo.visibility = View.GONE
        adBadge.visibility = View.GONE
        countdown.text = ""

        videoAnim?.cancel()
        videoAnim = null

        val lp = videoFrame.layoutParams as? FrameLayout.LayoutParams
        if (lp != null) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.CENTER
            videoFrame.layoutParams = lp
        }

        if (running) scheduleNextBreak()
        proceed?.invoke()
    }
}
