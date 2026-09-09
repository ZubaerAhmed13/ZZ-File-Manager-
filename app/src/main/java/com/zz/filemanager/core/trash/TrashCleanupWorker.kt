package com.zz.filemanager.core.trash

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zz.filemanager.ZZFileManagerApplication
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Periodic maintenance complements the opportunistic startup cleanup. Durable trash records,
 * rather than WorkManager delivery, remain the source of truth. */
class TrashCleanupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as ZZFileManagerApplication).container
        return runCatching {
            container.userLibrary.initialize()
            container.trashManager.reconcile()
            val days = container.preferences.trashRetentionDays.first()
            val retention = if (days < 0) Long.MAX_VALUE else days.toLong() * 24L * 60L * 60L * 1_000L
            container.trashManager.cleanupExpired(retention)
            Result.success()
        }.getOrElse { Result.retry() }
    }
}

object TrashCleanupScheduler {
    private const val UNIQUE_NAME = "step3-recycle-retention"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<TrashCleanupWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
