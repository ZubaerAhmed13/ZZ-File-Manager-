package com.zz.filemanager.core.cloud

import com.zz.filemanager.core.remote.AndroidKeystoreCredentialStore
import com.zz.filemanager.core.remote.NetworkConnection
import com.zz.filemanager.core.remote.NetworkConnectionRepository
import com.zz.filemanager.core.remote.RemoteAuthenticationType
import com.zz.filemanager.core.remote.RemoteConnectionState
import com.zz.filemanager.core.remote.RemoteProtocol
import com.zz.filemanager.core.remote.RemoteTlsMode
import com.zz.filemanager.core.remote.SecureCredentialStore

/**
 * Owns the local OAuth account lifecycle without embedding vendor client credentials.
 * A vendor-specific authorization/PKCE flow hands an already authorized refresh token to connect().
 */
class DirectCloudAccountManager(
    private val connections: NetworkConnectionRepository,
    private val credentials: SecureCredentialStore,
    private val adapters: DirectCloudAdapterRegistry,
    private val oauthDrivers: DirectCloudOAuthDriverRegistry,
    private val onAccountsChanged: () -> Unit = {},
) {
    fun connect(account: CloudAccountIdentity, refreshToken: CharArray) {
        require(adapters.adapterFor(account.providerKey) != null) { "No direct-cloud adapter registered for ${account.providerKey}" }
        require(refreshToken.isNotEmpty()) { "Refresh token is required" }
        val connectionId = stableConnectionId(account)
        val secretRef = AndroidKeystoreCredentialStore.oauthRefreshReference("cloud:${account.providerKey}:${account.accountId}")
        try {
            credentials.putChars(secretRef, refreshToken)
            connections.upsert(
                NetworkConnection(
                    id = connectionId,
                    protocol = RemoteProtocol.DIRECT_CLOUD,
                    displayName = account.displayName,
                    host = account.providerKey,
                    port = RemoteProtocol.DIRECT_CLOUD.defaultPort,
                    authenticationType = RemoteAuthenticationType.OAUTH,
                    rootPath = "/",
                    tlsMode = RemoteTlsMode.HTTPS,
                    oauthAccountId = account.accountId,
                    connectionState = RemoteConnectionState.DISCONNECTED,
                ),
            )
            onAccountsChanged()
        } finally {
            refreshToken.fill('\u0000')
        }
    }

    suspend fun refresh(providerKey: String, accountId: String) {
        val connection = requireConnection(providerKey, accountId)
        val account = CloudAccountIdentity(providerKey, accountId, connection.displayName)
        val secretRef = AndroidKeystoreCredentialStore.oauthRefreshReference(connection.providerId)
        val current = credentials.getChars(secretRef) ?: throw CloudAuthException.ReauthenticationRequired()
        var replacement: CharArray? = null
        try {
            val driver = oauthDrivers.driverFor(providerKey) ?: throw CloudAuthException.ReauthenticationRequired()
            replacement = driver.refresh(account, current)
            require(replacement.isNotEmpty()) { "OAuth refresh returned an empty token" }
            credentials.putChars(secretRef, replacement)
            connections.updateState(connection.id, RemoteConnectionState.DISCONNECTED)
            onAccountsChanged()
        } finally {
            current.fill('\u0000')
            replacement?.fill('\u0000')
        }
    }

    /** Disconnect locally without revoking the provider-side OAuth grant. */
    fun disconnect(providerKey: String, accountId: String) {
        val connection = requireConnection(providerKey, accountId)
        credentials.remove(AndroidKeystoreCredentialStore.oauthRefreshReference(connection.providerId))
        connections.updateState(connection.id, RemoteConnectionState.AUTH_REQUIRED)
        onAccountsChanged()
    }

    /** Revoke the provider-side grant first, then remove the local token and mark re-auth required. */
    suspend fun revoke(providerKey: String, accountId: String) {
        val connection = requireConnection(providerKey, accountId)
        val account = CloudAccountIdentity(providerKey, accountId, connection.displayName)
        val secretRef = AndroidKeystoreCredentialStore.oauthRefreshReference(connection.providerId)
        val current = credentials.getChars(secretRef) ?: throw CloudAuthException.ReauthenticationRequired()
        try {
            val driver = oauthDrivers.driverFor(providerKey) ?: throw CloudAuthException.ReauthenticationRequired()
            driver.revoke(account, current)
        } finally {
            current.fill('\u0000')
        }
        credentials.remove(secretRef)
        connections.updateState(connection.id, RemoteConnectionState.AUTH_REQUIRED)
        onAccountsChanged()
    }

    fun removeLocalAccount(providerKey: String, accountId: String) {
        val connection = requireConnection(providerKey, accountId)
        credentials.remove(AndroidKeystoreCredentialStore.oauthRefreshReference(connection.providerId))
        connections.remove(connection.id)
        onAccountsChanged()
    }

    private fun requireConnection(providerKey: String, accountId: String): NetworkConnection =
        connections.list().firstOrNull {
            it.protocol == RemoteProtocol.DIRECT_CLOUD && it.host == providerKey && it.oauthAccountId == accountId
        } ?: throw IllegalArgumentException("Direct-cloud account is not connected")

    companion object {
        fun stableConnectionId(account: CloudAccountIdentity): String = "${account.providerKey}:${account.accountId}"
    }
}
