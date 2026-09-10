package com.zz.filemanager.feature.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.remote.ConnectionTestResult
import com.zz.filemanager.core.remote.NetworkConnection
import com.zz.filemanager.core.remote.RemoteConnectionService
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
)

class RemoteLocationsViewModel(
    private val service: RemoteConnectionService,
) : ViewModel() {
    private val _state = MutableStateFlow(RemoteLocationsUiState(connections = service.list()))
    val state: StateFlow<RemoteLocationsUiState> = _state.asStateFlow()

    fun refresh() {
        _state.value = _state.value.copy(connections = service.list())
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

    class Factory(private val service: RemoteConnectionService) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RemoteLocationsViewModel(service) as T
    }
}
