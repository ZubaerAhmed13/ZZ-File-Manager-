package com.zz.filemanager.core.operation.android

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import androidx.annotation.RequiresApi
import com.zz.filemanager.ZZFileManagerApplication
import com.zz.filemanager.core.operation.FileOperationState
import com.zz.filemanager.core.operation.markHostExecutionInterrupted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class OperationJobService : JobService() {
    private var serviceJob: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as ZZFileManagerApplication).container
        val notifications = OperationNotificationFactory(this)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serviceJob = scope.coroutineContext[Job]

        setNotification(
            params,
            OperationNotificationFactory.NOTIFICATION_ID,
            notifications.build(container.operationStore.operations.value.relevantOperation()),
            JOB_END_NOTIFICATION_POLICY_DETACH,
        )

        scope.launch {
            val notificationUpdates = launch {
                container.operationStore.operations.collectLatest { operations ->
                    setNotification(
                        params,
                        OperationNotificationFactory.NOTIFICATION_ID,
                        notifications.build(operations.relevantOperation()),
                        JOB_END_NOTIFICATION_POLICY_DETACH,
                    )
                }
            }
            try {
                container.operationEngine.runAvailable()
            } finally {
                notificationUpdates.cancel()
                jobFinished(params, false)
                scope.cancel()
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        serviceJob?.cancel()
        runBlocking(Dispatchers.IO) {
            (application as ZZFileManagerApplication).container.operationStore.markHostExecutionInterrupted()
        }
        return true
    }
}

internal fun List<com.zz.filemanager.core.operation.FileOperation>.relevantOperation() = firstOrNull {
    it.state in setOf(
        FileOperationState.RUNNING,
        FileOperationState.PREPARING,
        FileOperationState.PAUSING,
        FileOperationState.CANCELLING,
        FileOperationState.WAITING_FOR_USER,
    )
} ?: firstOrNull { it.state == FileOperationState.QUEUED }
