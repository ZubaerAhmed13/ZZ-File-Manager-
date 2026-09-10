package com.zz.filemanager.core.operation.android

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zz.filemanager.ZZFileManagerApplication
import com.zz.filemanager.core.operation.FileOperationState
import com.zz.filemanager.core.operation.autoResumeInterruptedTransfers
import com.zz.filemanager.core.remote.AndroidRemoteNetworkSnapshotProvider
import com.zz.filemanager.core.remote.RemoteTransferExecutionPolicy
import com.zz.filemanager.core.remote.usesNetworkStorage
import java.util.concurrent.TimeUnit

/**
 * Re-arms queued/interrupted network transfers when connectivity returns. WorkManager provides the
 * durable wake-up; the foreground service performs the exact Wi-Fi/metered policy check again
 * immediately before any protocol work starts.
 */
class RemoteTransferPolicyWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as ZZFileManagerApplication).container
        val settings = container.networkConnections.settings()
        container.operationStore.autoResumeInterruptedTransfers(settings.autoResumeInterruptedTransfers)

        val queued = container.operationStore.operations.value.filter {
            it.state == FileOperationState.QUEUED && it.usesNetworkStorage()
        }
        if (queued.isEmpty()) return Result.success()

        val network = AndroidRemoteNetworkSnapshotProvider(applicationContext).snapshot()
        val anyAllowed = queued.any { RemoteTransferExecutionPolicy.evaluate(it, settings, network).allowed }
        if (!anyAllowed) return Result.retry()

        container.operationExecutionHost.requestExecution()
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "zz_step5_remote_transfer_policy"

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<RemoteTransferPolicyWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
