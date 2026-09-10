package com.zz.filemanager.core.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.zz.filemanager.core.operation.FileOperation

/** Immutable Android network facts used by the transfer execution gate. */
data class RemoteNetworkSnapshot(
    val connected: Boolean,
    val wifi: Boolean,
    val metered: Boolean,
)

data class RemoteTransferPolicyDecision(
    val allowed: Boolean,
    val waitingForNetwork: Boolean = false,
    val warnMetered: Boolean = false,
)

/**
 * Pure policy: Wi-Fi-only is a hard execution gate; metered warning never silently blocks.
 * The operation engine receives only operations for which allowed=true.
 */
object RemoteTransferExecutionPolicy {
    fun evaluate(
        operation: FileOperation,
        settings: RemoteTransferSettings,
        network: RemoteNetworkSnapshot,
    ): RemoteTransferPolicyDecision {
        if (!operation.usesNetworkStorage()) return RemoteTransferPolicyDecision(allowed = true)
        if (!network.connected) return RemoteTransferPolicyDecision(allowed = false, waitingForNetwork = true)
        if (settings.wifiOnlyBackgroundTransfers && !network.wifi) {
            return RemoteTransferPolicyDecision(allowed = false, waitingForNetwork = true)
        }
        return RemoteTransferPolicyDecision(
            allowed = true,
            warnMetered = settings.warnOnMeteredNetwork && network.metered,
        )
    }
}

class AndroidRemoteNetworkSnapshotProvider(context: Context) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    fun snapshot(): RemoteNetworkSnapshot {
        val network = connectivity.activeNetwork ?: return RemoteNetworkSnapshot(false, false, false)
        val capabilities = connectivity.getNetworkCapabilities(network)
            ?: return RemoteNetworkSnapshot(false, false, connectivity.isActiveNetworkMetered)
        val connected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        return RemoteNetworkSnapshot(
            connected = connected,
            wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            metered = connectivity.isActiveNetworkMetered,
        )
    }
}

fun FileOperation.usesNetworkStorage(): Boolean {
    if (destination?.providerId.isNetworkProviderId()) return true
    return items.any { it.source.reference.providerId.isNetworkProviderId() }
}

private fun String?.isNetworkProviderId(): Boolean {
    val value = this ?: return false
    return NETWORK_PREFIXES.any(value::startsWith)
}

private val NETWORK_PREFIXES = listOf("smb:", "ftp:", "ftps:", "sftp:", "webdav:", "cloud:")
