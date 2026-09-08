package com.zz.filemanager.core.operation.android

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.zz.filemanager.core.operation.OperationExecutionHost

class AndroidOperationExecutionHost(context: Context) : OperationExecutionHost {
    private val appContext = context.applicationContext

    override fun requestExecution() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val scheduler = appContext.getSystemService(JobScheduler::class.java)
            val info = JobInfo.Builder(JOB_ID, ComponentName(appContext, OperationJobService::class.java))
                .setUserInitiated(true)
                .build()
            val result = runCatching { scheduler.schedule(info) }.getOrDefault(JobScheduler.RESULT_FAILURE)
            if (result == JobScheduler.RESULT_SUCCESS) return
        }
        runCatching {
            ContextCompat.startForegroundService(appContext, Intent(appContext, OperationForegroundService::class.java))
        }
    }

    companion object {
        const val JOB_ID = 0x5A5A0202
    }
}
