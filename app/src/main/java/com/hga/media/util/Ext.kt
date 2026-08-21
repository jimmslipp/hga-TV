package com.hga.media.util

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.Toast

fun Context.dp(value: Float): Int = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
).toInt()

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun View.show() { if (visibility != View.VISIBLE) visibility = View.VISIBLE }
fun View.hide() { if (visibility != View.GONE) visibility = View.GONE }
fun View.invisible() { if (visibility != View.INVISIBLE) visibility = View.INVISIBLE }
fun View.showIf(condition: Boolean) { visibility = if (condition) View.VISIBLE else View.GONE }

/** Trim, drop a trailing slash, and make sure a scheme is present. */
fun String.normaliseBaseUrl(): String {
    var s = trim()
    if (s.isEmpty()) return s
    if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
    while (s.endsWith("/")) s = s.dropLast(1)
    return s
}

/**
 * Android refuses any address without http:// or https:// on the front, with an
 * unhelpful "no protocol" error. Nobody types that on a TV remote, so we add it.
 */
fun String.withScheme(default: String = "https://"): String {
    val s = trim()
    if (s.isEmpty()) return s
    if (s.contains("://")) return s
    return default + s.trimStart('/')
}

fun Long.asClock(): String {
    val totalSec = this / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}
