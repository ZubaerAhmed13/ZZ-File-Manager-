package com.zz.filemanager.core.remote

sealed interface ConnectionTestResult {
    data object Success : ConnectionTestResult
    data class HostKeyTrustRequired(val fingerprint: String, val algorithm: String?) : ConnectionTestResult
    data class ServerIdentityChanged(val expected: String?, val observed: String?) : ConnectionTestResult
    data class Failure(val state: RemoteConnectionState, val message: String) : ConnectionTestResult
}

/** Saved-connection lifecycle used by UI. No plaintext secret is returned to presentation code. */
class RemoteConnectionService(
    private val repository: NetworkConnectionRepository,
    private val manager: NetworkConnectionManager,
    private val credentials: SecureCredentialStore,
    private val factories: RemoteFileSystemFactoryRegistry,
    private val providers: RemoteProviderCoordinator,
) {
    fun list(): List<NetworkConnection> = repository.list()

    fun save(
        connection: NetworkConnection,
        password: CharArray? = null,
        privateKey: ByteArray? = null,
        privateKeyPassphrase: CharArray? = null,
    ) {
        try {
            manager.save(connection, password, privateKey, privateKeyPassphrase)
            providers.syncSavedConnections()
        } finally {
            password?.fill('\u0000')
            privateKey?.fill(0)
            privateKeyPassphrase?.fill('\u0000')
        }
    }

    fun remove(connectionId: String) {
        manager.remove(connectionId)
        providers.syncSavedConnections()
    }

    fun disconnect(connectionId: String) {
        repository.updateState(connectionId, RemoteConnectionState.DISCONNECTED)
        providers.syncSavedConnections()
    }

    fun trustSftpHostKey(connectionId: String, observedSha256: String) {
        val connection = repository.get(connectionId) ?: throw IllegalArgumentException("Unknown connection")
        require(connection.protocol == RemoteProtocol.SFTP)
        val normalized = observedSha256.trim()
        require(normalized.startsWith("SHA256:") && normalized.length > "SHA256:".length)
        repository.upsert(connection.copy(sshHostKeySha256 = normalized, connectionState = RemoteConnectionState.DISCONNECTED))
        providers.syncSavedConnections()
    }

    fun test(connectionId: String): ConnectionTestResult {
        val connection = repository.get(connectionId)
            ?: return ConnectionTestResult.Failure(RemoteConnectionState.UNKNOWN, "Saved connection no longer exists.")
        repository.updateState(connection.id, RemoteConnectionState.CONNECTING)
        val secrets = RemoteSessionSecrets(
            password = credentials.getChars(AndroidKeystoreCredentialStore.passwordReference(connection.id)),
            privateKey = credentials.getBytes(AndroidKeystoreCredentialStore.privateKeyReference(connection.id)),
            privateKeyPassphrase = credentials.getChars(AndroidKeystoreCredentialStore.privateKeyPassphraseReference(connection.id)),
        )
        return try {
            factories.factoryFor(connection.protocol).open(connection, secrets, repository.settings()).use { fs ->
                fs.stat(connection.rootPath)
                repository.updateState(connection.id, RemoteConnectionState.CONNECTED, endpoint = "${connection.host}:${connection.port}")
                providers.syncSavedConnections()
                ConnectionTestResult.Success
            }
        } catch (trust: RemoteAccessException.HostKeyTrustRequired) {
            repository.updateState(connection.id, RemoteConnectionState.AUTH_REQUIRED)
            providers.syncSavedConnections()
            ConnectionTestResult.HostKeyTrustRequired(trust.fingerprint, trust.algorithm)
        } catch (changed: RemoteAccessException.ServerIdentityChanged) {
            repository.updateState(connection.id, RemoteConnectionState.SERVER_CHANGED)
            providers.syncSavedConnections()
            ConnectionTestResult.ServerIdentityChanged(changed.expected, changed.observed)
        } catch (error: Throwable) {
            val state = stateFor(error)
            repository.updateState(connection.id, state)
            providers.syncSavedConnections()
            ConnectionTestResult.Failure(state, safeMessage(state))
        } finally {
            secrets.clear()
        }
    }

    fun rootFor(connectionId: String) = providers.rootFor(connectionId)

    private fun stateFor(error: Throwable): RemoteConnectionState = when (error) {
        is RemoteAccessException.AuthenticationRequired -> RemoteConnectionState.AUTH_REQUIRED
        is RemoteAccessException.AuthenticationFailed -> RemoteConnectionState.AUTH_FAILED
        is RemoteAccessException.HostUnreachable -> RemoteConnectionState.HOST_UNREACHABLE
        is RemoteAccessException.Timeout -> RemoteConnectionState.TIMEOUT
        is RemoteAccessException.Tls -> RemoteConnectionState.TLS_ERROR
        is RemoteAccessException.Certificate -> RemoteConnectionState.CERTIFICATE_ERROR
        is RemoteAccessException.ServerIdentityChanged -> RemoteConnectionState.SERVER_CHANGED
        is RemoteAccessException.PermissionDenied -> RemoteConnectionState.PERMISSION_DENIED
        is RemoteAccessException.ReadOnly -> RemoteConnectionState.READ_ONLY
        is RemoteAccessException.Protocol -> RemoteConnectionState.PROTOCOL_ERROR
        else -> RemoteConnectionState.UNKNOWN
    }

    private fun safeMessage(state: RemoteConnectionState): String = when (state) {
        RemoteConnectionState.AUTH_REQUIRED -> "Authentication is required."
        RemoteConnectionState.AUTH_FAILED -> "Authentication failed."
        RemoteConnectionState.HOST_UNREACHABLE -> "Server is unreachable."
        RemoteConnectionState.TIMEOUT -> "Connection timed out."
        RemoteConnectionState.TLS_ERROR -> "TLS negotiation failed."
        RemoteConnectionState.CERTIFICATE_ERROR -> "Server certificate could not be trusted."
        RemoteConnectionState.PERMISSION_DENIED -> "Server denied this operation."
        RemoteConnectionState.READ_ONLY -> "Server location is read-only."
        RemoteConnectionState.PROTOCOL_ERROR -> "The server returned a protocol error."
        RemoteConnectionState.SERVER_CHANGED -> "Server identity changed."
        else -> "Connection failed."
    }
}
