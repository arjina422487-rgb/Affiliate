package com.ownerlink.agent.affiliate

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.BackoffPolicy
import com.ownerlink.agent.OwnerLinkApp
import com.ownerlink.agent.service.ConnectionService
import java.util.concurrent.TimeUnit

class AffiliateSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val sent = runCatching { OwnerLinkApp.instance.affiliate.backgroundAttempt() }.getOrDefault(false)
        return if (sent) Result.success() else Result.retry()
    }

    companion object {
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<AffiliateSyncWorker>(15, TimeUnit.MINUTES).setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("affiliate-sync", ExistingPeriodicWorkPolicy.UPDATE, work)
        }
        fun enqueue(context: Context) {
            val work = OneTimeWorkRequestBuilder<AffiliateSyncWorker>().setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("affiliate-submit-retry", ExistingWorkPolicy.REPLACE, work)
        }
    }
}
