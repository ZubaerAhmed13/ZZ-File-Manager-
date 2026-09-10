package com.zz.filemanager.core.operation.android

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.zz.filemanager.ZZFileManagerApplication
import com.zz.filemanager.core.operation.FileOperationState
import com.zz.filemanager.core.operation.autoResumeInterruptedTransfers
import com.zz.filemanager.core.operation.markHostExecutionInterrupted
import com.zz.filemanager.core.remote.AndroidRemoteNetworkSnapshotProvider
import com.zz.filemanager.core.remote.RemoteTransferExecutionPolicy
import com.zz.filemanager.core.remote.usesNetworkStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
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
            val store = container.operationStore
            store.initialize()
            val settings = container.networkConnections.settings()
            store.autoResumeInterruptedTransfers(settings.autoResumeInterruptedTransfers)
            val networkProvider = AndroidRemoteNetworkSnapshotProvider(this@OperationForegroundService)
            val initialNetwork = networkProvider.snapshot()

            // Temporarily take policy-blocked network work out of the runnable set so unrelated
            // local operations can still proceed. These operations are returned to QUEUED before
            // the service exits and the durable policy worker wakes them when connectivity fits.
            val policyBlockedIds = mutableSetOf<String>()
            store.operations.value
                .filter { it.state == FileOperationState.QUEUED && it.usesNetworkStorage() }
                .forEach { operation ->
                    if (!RemoteTransferExecutionPolicy.evaluate(operation, settings, initialNetwork).allowed) {
                        policyBlockedIds += operation.id
                        store.save(operation.copy(state = FileOperationState.PAUSED))
                    }
                }

            val manager = getSystemService(android.app.NotificationManager::class.java)
            val updates = launch {
                container.operationStore.operations.collectLatest { operations ->
                    val operation = operations.relevantOperation()
                    val prefix = operation
                        ?.takeIf { it.usesNetworkStorage() }
                        ?.let { RemoteTransferExecutionPolicy.evaluate(it, settings, networkProvider.snapshot()) }
                        ?.takeIf { it.warnMetered }
                        ?.let { "Metered network warning" }
                    manager.notify(OperationNotificationFactory.NOTIFICATION_ID, notifications.build(operation, prefix))
                }
            }

            val connectivity = getSystemService(ConnectivityManager::class.java)
            val networkChanges = Channel<Unit>(Channel.CONFLATED)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { networkChanges.trySend(Unit) }
                override fun onLost(network: Network) { networkChanges.trySend(Unit) }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { networkChanges.trySend(Unit) }
            }
            runCatching { connectivity.registerDefaultNetworkCallback(callback) }

            var execution: Job? = null
            var policyInterrupted = false
            val watcher = launch {
                networkChanges.trySend(Unit)
                for (ignored in networkChanges) {
                    val active = store.operations.value.firstOrNull {
                        it.state in setOf(FileOperationState.RUNNING, FileOperationState.PREPARING) && it.usesNetworkStorage()
                    } ?: continue
                    val decision = RemoteTransferExecutionPolicy.evaluate(active, settings, networkProvider.snapshot())
                    if (!decision.allowed) {
                        policyInterrupted = true
                        execution?.cancel()
                        break
                    }
                }
            }

            try {
                execution = launch { container.operationEngine.runAvailable() }
                execution?.join()
                if (policyInterrupted) {
                    store.markHostExecutionInterrupted()
                }
            } finally {
                watcher.cancel()
                networkChanges.close()
                runCatching { connectivity.unregisterNetworkCallback(callback) }
                updates.cancel()
            }

            // Restore only operations this service instance paused for network policy.
            policyBlockedIds.forEach { id ->
                store.get(id)?.takeIf { it.state == FileOperationState.PAUSED }?.let { blocked ->
                    store.save(blocked.copy(state = FileOperationState.QUEUED))
                }
            }

            val waiting = store.operations.value.firstOrNull { operation ->
                operation.state == FileOperationState.QUEUED &&
                    operation.usesNetworkStorage() &&
                    !RemoteTransferExecutionPolicy.evaluate(operation, settings, networkProvider.snapshot()).allowed
            }
            if (policyInterrupted || waiting != null) {
                val reason = if (settings.wifiOnlyBackgroundTransfers) "Waiting for Wi-Fi" else "Waiting for network"
                manager.notify(OperationNotificationFactory.NOTIFICATION_ID, notifications.build(waiting, reason))
                RemoteTransferPolicyWorker.schedule(this@OperationForegroundService)
                ServiceCompat.stopForeground(this@OperationForegroundService, ServiceCompat.STOP_FOREGROUND_DETACH)
            } else {
                ServiceCompat.stopForeground(this@OperationForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        runBlocking(Dispatchers.IO) {
            (application as ZZFileManagerApplication).container.operationStore.markHostExecutionInterrupted()
        }
        RemoteTransferPolicyWorker.schedule(this)
        stopSelf(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
