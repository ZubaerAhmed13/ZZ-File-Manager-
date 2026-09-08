package com.zz.filemanager.core.operation.android

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.zz.filemanager.ZZFileManagerApplication
import com.zz.filemanager.core.operation.markHostExecutionInterrupted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class OperationForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notifications: OperationNotificationFactory

    override fun onCreate() {
        super.onCreate()
        notifications = OperationNotificationFactory(this)
        notifications.ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as ZZFileManagerApplication).container
        ServiceCompat.startForeground(
            this,
            OperationNotificationFactory.NOTIFICATION_ID,
            notifications.build(container.operationStore.operations.value.relevantOperation()),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        scope.launch {
            val updates = launch {
                container.operationStore.operations.collectLatest { operations ->
                    val manager = getSystemService(android.app.NotificationManager::class.java)
                    manager.notify(OperationNotificationFactory.NOTIFICATION_ID, notifications.build(operations.relevantOperation()))
                }
            }
            try {
                container.operationEngine.runAvailable()
            } finally {
                updates.cancel()
                ServiceCompat.stopForeground(this@OperationForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        runBlocking(Dispatchers.IO) {
            (application as ZZFileManagerApplication).container.operationStore.markHostExecutionInterrupted()
        }
        stopSelf(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
