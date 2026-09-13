package com.zz.filemanager

import android.app.Application
import android.os.StrictMode
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.zz.filemanager.app.AppContainer
import com.zz.filemanager.core.operation.android.RemoteTransferPolicyWorker
import com.zz.filemanager.core.step4.Step4RecoveryWorker

class ZZFileManagerApplication : Application() {
    val container: AppContainer by lazy { AppContainer(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectActivityLeaks().penaltyLog().build())
        }
        WorkManager.getInstance(this).enqueueUniqueWork(
            Step4RecoveryWorker.UNIQUE_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<Step4RecoveryWorker>().build(),
        )
        // Durable Step 5 wake-up for interrupted/queued network transfers. The worker and
        // foreground service both re-evaluate current network policy before protocol I/O.
        RemoteTransferPolicyWorker.schedule(this)
    }
}
