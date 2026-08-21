package com.hga.media.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.util.LruCache
import com.hga.media.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/**
 * Minimal async image loader (memory + disk cache). Written by hand instead of
 * pulling in Glide/Picasso so the project has no annotation processors and
 * builds cleanly on a plain machine with nothing but the Android SDK.
 */
object ImageLoader {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var diskDir: File? = null
    private var maxPx = 640

    private val mem = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024).toInt() / 8).coerceAtLeast(4096)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun init(context: Context) {
        diskDir = File(context.cacheDir, "img").apply { mkdirs() }
    }

    private fun key(url: String): String {
        val md = MessageDigest.getInstance("MD5").digest(url.toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }

    fun clearDisk() {
        diskDir?.listFiles()?.forEach { it.delete() }
        mem.evictAll()
    }

    fun load(view: ImageView, url: String?, placeholderRes: Int = R.drawable.ph_channel) {
        view.setTag(R.id.tag_image_url, url)
        if (url.isNullOrBlank()) {
            view.setImageResource(placeholderRes)
            return
        }
        mem.get(url)?.let { view.setImageBitmap(it); return }
        view.setImageResource(placeholderRes)

        scope.launch {
            val bmp = fetch(url) ?: return@launch
            mem.put(url, bmp)
            main.post {
                if (view.getTag(R.id.tag_image_url) == url) view.setImageBitmap(bmp)
            }
        }
    }

    private suspend fun fetch(url: String): Bitmap? {
        val dir = diskDir ?: return null
        val file = File(dir, key(url))
        if (file.exists() && file.length() > 0) {
            decode(file)?.let { return it }
            file.delete()
        }
        return try {
            val bytes = Http.getBytes(url)
            if (bytes.isEmpty()) return null
            file.writeBytes(bytes)
            decode(file)
        } catch (e: Exception) {
            L.w("image failed $url : ${e.message}")
            null
        }
    }

    private fun decode(file: File): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (w / sample > maxPx || h / sample > maxPx) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeFile(file.absolutePath, opts)
    } catch (e: Throwable) {
        null
    }

    /** Full-quality decode used for advert artwork, which must not be downsampled hard. */
    fun decodeFullQuality(file: File, maxWidth: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxWidth * 2) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    } catch (e: Throwable) {
        null
    }
}
