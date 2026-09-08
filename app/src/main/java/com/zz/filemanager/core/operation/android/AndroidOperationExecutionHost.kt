package com.zz.filemanager.core.operation.android

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.zz.filemanager.core.operation.OperationExecutionHost

/**
 * Step 2 hosts local/SAF user-started file operations in a foreground service.
 *
 * Android's API 34+ user-initiated JobScheduler mode is limited to network data
 * transfers, so it is intentionally not used for local/SAF copies or moves.
 * OperationForegroundService handles Android 15 dataSync timeouts by reconciling
 * the persistent operation journal to an interrupted/recoverable state.
 */
class AndroidOperationExecutionHost(context: Context) : OperationExecutionHost {
    private val appContext = context.applicationContext

    override fun requestExecution() {
        runCatching {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, OperationForegroundService::class.java),
            )
        }
    }
}
