package com.hga.media.ads

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hga.media.data.Repo
import com.hga.media.util.L
import java.util.concurrent.TimeUnit

/**
 * Background advert refresh. Runs even when the app is sitting on a channel all
 * day, which is exactly what a venue screen does.
 */
class AdSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val result = AdRepository.sync()
            L.d("Scheduled advert sync: ${result.message}")
            // Only ask for a retry when there is something to retry for. A device
            // with no advert source configured must not burn battery backing off.
            when {
                result.ok -> Result.success()
                AdRepository.adCount > 0 -> Result.success()
                Repo.prefs.adPrimaryUrl.isBlank() && Repo.prefs.adFallbackUrl.isBlank() -> Result.success()
                else -> Result.retry()
            }
        } catch (e: Exception) {
            L.e("Advert sync worker failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "hga_ad_sync"

        fun schedule(context: Context, everyMinutes: Int) {
            val minutes = everyMinutes.coerceAtLeast(15).toLong()
            val request = PeriodicWorkRequestBuilder<AdSyncWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
            L.d("Advert sync scheduled every $minutes minutes")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }
    }
}
