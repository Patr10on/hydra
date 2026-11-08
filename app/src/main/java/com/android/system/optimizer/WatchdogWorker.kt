package com.android.system.optimizer

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.*
import java.util.concurrent.TimeUnit

class WatchdogWorker(private val context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        try {
            val svcIntent = Intent(context, PhantomService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svcIntent)
                } else {
                    context.startService(svcIntent)
                }
            } catch (e: Exception) {
                context.startService(svcIntent)
            }
        } catch (e: Exception) {
            return Result.retry()
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val workReq = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "SystemWatchdog",
                ExistingPeriodicWorkPolicy.KEEP,
                workReq
            )
        }
    }
}

