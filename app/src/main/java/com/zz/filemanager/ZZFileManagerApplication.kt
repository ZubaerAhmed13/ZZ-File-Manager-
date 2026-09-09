package com.zz.filemanager

import android.app.Application
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.zz.filemanager.app.AppContainer
import com.zz.filemanager.core.step4.Step4RecoveryWorker

class ZZFileManagerApplication : Application() {
    val container: AppContainer by lazy { AppContainer(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        WorkManager.getInstance(this).enqueueUniqueWork(
            Step4RecoveryWorker.UNIQUE_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<Step4RecoveryWorker>().build(),
        )
    }
}
