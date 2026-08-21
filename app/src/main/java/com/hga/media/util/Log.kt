package com.hga.media.util

import android.util.Log

object L {
    private const val TAG = "HGA"
    var verbose = true

    fun d(msg: String) { if (verbose) Log.d(TAG, msg) }
    fun w(msg: String) { Log.w(TAG, msg) }
    fun e(msg: String, t: Throwable? = null) { Log.e(TAG, msg, t) }
}
