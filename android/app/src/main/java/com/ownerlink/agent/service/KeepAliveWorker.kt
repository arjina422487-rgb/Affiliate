package com.ownerlink.agent.service

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.ownerlink.agent.security.SecureStore
import java.util.concurrent.TimeUnit

class KeepAliveWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val store = SecureStore(applicationContext)
        if (store.serviceWanted && store.serverUrl.isNotBlank()) {
            if (!ConnectionService.start(applicationContext) && store.remoteAccessConsent) return Result.retry()
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<KeepAliveWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "ownerlink-keepalive",
                ExistingPeriodicWorkPolicy.UPDATE,
                req,
            )
        }
    }
}
