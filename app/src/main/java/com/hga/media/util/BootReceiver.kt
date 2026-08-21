package com.hga.media.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hga.media.data.Prefs
import com.hga.media.ui.SplashActivity

/**
 * Venue screens want to come straight back to live TV after a power cut.
 * Enable "Start on power up" in the owner console to switch this on.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.LOCKED_BOOT_COMPLETED"
        ) return

        val prefs = Prefs(context)
        if (!prefs.autoStartOnBoot) return

        L.d("Boot completed - launching HGA-Media")
        val launch = Intent(context, SplashActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(SplashActivity.EXTRA_FROM_BOOT, true)
        }
        runCatching { context.startActivity(launch) }
    }
}
