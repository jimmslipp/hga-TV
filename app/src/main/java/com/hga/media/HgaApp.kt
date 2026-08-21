package com.hga.media

import android.app.Application
import com.hga.media.ads.AdRepository
import com.hga.media.ads.AdSyncWorker
import com.hga.media.data.Repo
import com.hga.media.util.ImageLoader
import com.hga.media.util.L

class HgaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        L.d("HGA-Media starting")
        Repo.init(this)
        ImageLoader.init(this)
        AdRepository.init(this, Repo.prefs)

        if (Repo.prefs.adsEnabled) {
            AdSyncWorker.schedule(this, Repo.prefs.adSyncMinutes)
        }
    }
}
