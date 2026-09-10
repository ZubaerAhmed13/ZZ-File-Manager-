package com.zz.filemanager.feature.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.remote.ConnectionTestResult
import com.zz.filemanager.core.remote.LanDiscoveryService
import com.zz.filemanager.core.remote.NetworkConnection
import com.zz.filemanager.core.remote.RemoteAuthenticationType
import com.zz.filemanager.core.remote.RemoteConnectionService
import com.zz.filemanager.core.remote.RemoteProtocol
import com.zz.filemanager.core.remote.RemoteTlsMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RemoteTestFeedback(val connectionId: String, val result: ConnectionTestResult)

data class RemoteLocationsUiState(
    val connections: List<NetworkConnection> = emptyList(),
    val busyConnectionId: String? = null,
    val feedback: RemoteTestFeedback? = null,
    val discovered: List<LanDiscoveryService.Candidate> = emptyList(),
    val discovering: Boolean = false,
    val lanDiscoveryEnabled: Boolean = true,
)

class RemoteLocationsViewModel(
    private val service: RemoteConnectionService,
    private val discovery: LanDiscoveryService,
) : ViewModel() {
    private val _state = MutableStateFlow(
        RemoteLocationsUiState(
            connections = service.list(),
            lanDiscoveryEnabled = service.settings().lanDiscoveryEnabled,
        ),
    )
    val state: StateFlow<RemoteLocationsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            discovery.candidates.collect { candidates ->
                _state.value = _state.value.copy(discovered = candidates)
            }
        }
    }

    fun refresh() {
        _state.value = _state.value.copy(
            connections = service.list(),
            lanDiscoveryEnabled = service.settings().lanDiscoveryEnabled,
        )
    }

    fun startDiscovery() {
        val enabled = service.settings().lanDiscoveryEnabled
        if (!enabled) {
            _state.value = _state.value.copy(lanDiscoveryEnabled = false, discovering = false)
            return
        }
        discovery.start(enabled = true)
        _state.value = _state.value.copy(discovering = true, lanDiscoveryEnabled = true)
    }

    fun stopDiscovery() {
        discovery.stop()
        _state.value = _state.value.copy(discovering = false)
    }

    fun clearDiscovery() {
        discovery.clear()
        _state.value = _state.value.copy(discovered = emptyList())
    }

    fun connectionDraft(candidate: LanDiscoveryService.Candidate): NetworkConnection {
        val host = candidate.address?.takeIf { it.isNotBlank() } ?: candidate.hostName
        val tls = when (candidate.protocol) {
            RemoteProtocol.FTPS -> if (candidate.port == 990) RemoteTlsMode.IMPLICIT else RemoteTlsMode.EXPLICIT
            RemoteProtocol.WEBDAV -> if (candidate.serviceType.contains("webdavs", ignoreCase = true)) RemoteTlsMode.HTTPS else RemoteTlsMode.NONE
            else -> RemoteTlsMode.NONE
        }
        val authentication = when (candidate.protocol) {
            RemoteProtocol.SMB -> RemoteAuthenticationType.PASSWORD
            RemoteProtocol.FTP, RemoteProtocol.FTPS -> RemoteAuthenticationType.PASSWORD
            RemoteProtocol.SFTP -> RemoteAuthenticationType.PASSWORD
            RemoteProtocol.WEBDAV -> RemoteAuthenticationType.PASSWORD
            RemoteProtocol.DIRECT_CLOUD -> RemoteAuthenticationType.OAUTH
        }
        return NetworkConnection(
            protocol = candidate.protocol,
            displayName = candidate.serviceName.ifBlank { candidate.hostName },
            host = host,
            port = candidate.port,
            authenticationType = authentication,
            tlsMode = tls,
            rootPath = "/",
        )
    }

    fun save(
        connection: NetworkConnection,
        password: CharArray?,
        privateKey: ByteArray?,
        privateKeyPassphrase: CharArray?,
    ) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { service.save(connection, password, privateKey, privateKeyPassphrase) }
            }.onSuccess {
                _state.value = _state.value.copy(connections = service.list(), feedback = null)
            }.onFailure {
                _state.value = _state.value.copy(
                    feedback = RemoteTestFeedback(
                        connection.id,
                        ConnectionTestResult.Failure(com.zz.filemanager.core.remote.RemoteConnectionState.UNKNOWN, "Connection could not be saved."),
                    ),
                )
            }
        }
    }

    fun remove(connectionId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { service.remove(connectionId) }
            _state.value = _state.value.copy(connections = service.list(), feedback = null)
        }
    }

    fun disconnect(connectionId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { service.disconnect(connectionId) }
            _state.value = _state.value.copy(connections = service.list())
        }
    }

    fun test(connectionId: String) {
        if (_state.value.busyConnectionId != null) return
        _state.value = _state.value.copy(busyConnectionId = connectionId, feedback = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { service.test(connectionId) }
            _state.value = _state.value.copy(
                connections = service.list(),
                busyConnectionId = null,
                feedback = RemoteTestFeedback(connectionId, result),
            )
        }
    }

    fun trustHostKeyAndRetest(connectionId: String, fingerprint: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { service.trustSftpHostKey(connectionId, fingerprint) }
            _state.value = _state.value.copy(connections = service.list(), feedback = null)
            test(connectionId)
        }
    }

    fun clearFeedback() { _state.value = _state.value.copy(feedback = null) }

    fun rootFor(connectionId: String): BrowserLocation? = service.rootFor(connectionId)

    override fun onCleared() {
        discovery.stop()
        super.onCleared()
    }

    class Factory(
        private val service: RemoteConnectionService,
        private val discovery: LanDiscoveryService,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RemoteLocationsViewModel(service, discovery) as T
    }
}
