package com.zz.filemanager.core.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-driven DNS-SD/mDNS discovery. This never scans IP ranges, probes arbitrary ports,
 * attempts credentials, or creates a saved connection automatically.
 */
class LanDiscoveryService(context: Context) {
    data class Candidate(
        val serviceName: String,
        val hostName: String,
        val address: String?,
        val protocol: RemoteProtocol,
        val port: Int,
        val serviceType: String,
    ) {
        val identity: String get() = "${protocol.name}|${address ?: hostName}|$port|$serviceName"
    }

    private val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _candidates = MutableStateFlow<List<Candidate>>(emptyList())
    val candidates: StateFlow<List<Candidate>> = _candidates.asStateFlow()

    private val listeners = linkedMapOf<String, NsdManager.DiscoveryListener>()

    @Synchronized
    fun start(enabled: Boolean) {
        if (!enabled || listeners.isNotEmpty()) return
        _candidates.value = emptyList()
        SERVICE_TYPES.forEach { (serviceType, protocol) ->
            val listener = discoveryListener(serviceType, protocol)
            listeners[serviceType] = listener
            runCatching { manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onFailure { listeners.remove(serviceType) }
        }
    }

    @Synchronized
    fun stop() {
        val active = listeners.values.toList()
        listeners.clear()
        active.forEach { listener -> runCatching { manager.stopServiceDiscovery(listener) } }
    }

    fun clear() { _candidates.value = emptyList() }

    private fun discoveryListener(serviceType: String, protocol: RemoteProtocol) = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            synchronized(this@LanDiscoveryService) { listeners.remove(serviceType) }
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            synchronized(this@LanDiscoveryService) { listeners.remove(serviceType) }
        }
        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            val name = serviceInfo.serviceName
            _candidates.value = _candidates.value.filterNot {
                it.protocol == protocol && it.serviceName == name && it.serviceType == serviceType
            }
        }
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (!sameServiceType(serviceInfo.serviceType, serviceType)) return
            resolve(serviceInfo, protocol, serviceType)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolve(serviceInfo: NsdServiceInfo, protocol: RemoteProtocol, serviceType: String) {
        manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val host = resolved.host ?: return
                val port = resolved.port.takeIf { it in 1..65535 } ?: protocol.defaultPort
                val candidate = Candidate(
                    serviceName = resolved.serviceName.ifBlank { host.hostName ?: host.hostAddress.orEmpty() },
                    hostName = host.hostName ?: host.hostAddress.orEmpty(),
                    address = host.hostAddress,
                    protocol = protocol,
                    port = port,
                    serviceType = serviceType,
                )
                val next = buildList {
                    add(candidate)
                    addAll(_candidates.value.filterNot { it.identity == candidate.identity })
                }.take(MAX_CANDIDATES)
                _candidates.value = next
            }
        })
    }

    private fun sameServiceType(actual: String?, expected: String): Boolean =
        actual?.trim()?.trimEnd('.')?.equals(expected.trim().trimEnd('.'), ignoreCase = true) == true

    companion object {
        private const val MAX_CANDIDATES = 100
        private val SERVICE_TYPES = listOf(
            "_smb._tcp." to RemoteProtocol.SMB,
            "_ftp._tcp." to RemoteProtocol.FTP,
            "_ftps._tcp." to RemoteProtocol.FTPS,
            "_ssh._tcp." to RemoteProtocol.SFTP,
            "_sftp-ssh._tcp." to RemoteProtocol.SFTP,
            "_webdav._tcp." to RemoteProtocol.WEBDAV,
            "_webdavs._tcp." to RemoteProtocol.WEBDAV,
        )
    }
}
