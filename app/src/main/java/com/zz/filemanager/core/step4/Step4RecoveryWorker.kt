package com.zz.filemanager.core.step4

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zz.filemanager.ZZFileManagerApplication

/**
 * Managed one-shot startup reconciliation. Ambiguous transactions are deliberately left in the
 * journal rather than guessed or deleted; the next application start can retry after storage or
 * permissions return.
 */
class Step4RecoveryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val application = applicationContext as? ZZFileManagerApplication ?: return Result.failure()
        return runCatching {
            application.container.safeOutputWriter.reconcile()
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object { const val UNIQUE_NAME = "step4-staged-write-recovery" }
}
