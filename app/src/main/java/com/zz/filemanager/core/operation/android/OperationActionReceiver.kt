package com.zz.filemanager.core.operation.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zz.filemanager.ZZFileManagerApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OperationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val operationId = intent.getStringExtra(EXTRA_OPERATION_ID) ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val controller = (context.applicationContext as ZZFileManagerApplication).container.operationController
                when (intent.action) {
                    ACTION_PAUSE -> controller.pause(operationId)
                    ACTION_CANCEL -> controller.cancel(operationId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.zz.filemanager.operation.PAUSE"
        const val ACTION_CANCEL = "com.zz.filemanager.operation.CANCEL"
        const val EXTRA_OPERATION_ID = "operation_id"
    }
}
