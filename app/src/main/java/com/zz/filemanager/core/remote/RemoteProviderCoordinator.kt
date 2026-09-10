package com.zz.filemanager.core.remote

import com.zz.filemanager.core.cloud.CloudAccountIdentity
import com.zz.filemanager.core.cloud.DirectCloudAdapterRegistry
import com.zz.filemanager.core.cloud.DirectCloudStorageProvider
import com.zz.filemanager.core.model.StorageLocation
import com.zz.filemanager.core.model.StorageType
import com.zz.filemanager.core.storage.StorageRepository

/**
 * Rebuilds runtime providers from durable, non-secret connection/account metadata. Secrets are
 * resolved lazily from Android Keystore only when an operation actually opens a provider.
 */
class RemoteProviderCoordinator(
    private val storage: StorageRepository,
    private val connections: NetworkConnectionRepository,
    private val credentials: SecureCredentialStore,
    private val factories: RemoteFileSystemFactoryRegistry,
    private val cloudAdapters: DirectCloudAdapterRegistry = DirectCloudAdapterRegistry(emptyList()),
) {
    @Synchronized
    fun syncSavedConnections() {
        val retained = linkedSetOf<String>()
        connections.list().forEach { connection ->
            if (connection.protocol == RemoteProtocol.DIRECT_CLOUD) {
                registerDirectCloud(connection, retained)
                return@forEach
            }
            val hasFactory = runCatching { factories.factoryFor(connection.protocol) }.isSuccess
            if (!hasFactory) return@forEach
            val provider = RemoteStorageProvider(connection.id, connections, credentials, factories)
            retained += provider.id
            storage.registerExternalProvider(
                provider = provider,
                location = StorageLocation(
                    id = provider.id,
                    displayName = connection.displayName,
                    type = StorageType.NETWORK,
                    totalBytes = null,
                    freeBytes = null,
                    readable = true,
                    writable = !connection.readOnlyOverride,
                    available = connection.connectionState != RemoteConnectionState.OFFLINE,
                    root = provider.rootLocation(),
                ),
                includeInGlobalSearch = false,
            )
        }
        storage.retainExternalProviders(retained)
    }

    private fun registerDirectCloud(connection: NetworkConnection, retained: MutableSet<String>) {
        val providerKey = connection.host.takeIf { it.isNotBlank() } ?: return
        val accountId = connection.oauthAccountId?.takeIf { it.isNotBlank() } ?: return
        if (connection.connectionState == RemoteConnectionState.AUTH_REQUIRED) return
        if (cloudAdapters.adapterFor(providerKey) == null) return
        val account = CloudAccountIdentity(providerKey, accountId, connection.displayName)
        val provider = DirectCloudStorageProvider(
            account = account,
            credentials = credentials,
            adapters = cloudAdapters,
            id = connection.providerId,
        )
        retained += provider.id
        storage.registerExternalProvider(
            provider = provider,
            location = StorageLocation(
                id = provider.id,
                displayName = connection.displayName,
                type = StorageType.CLOUD,
                totalBytes = null,
                freeBytes = null,
                readable = true,
                writable = !connection.readOnlyOverride,
                available = connection.connectionState != RemoteConnectionState.OFFLINE,
                root = provider.rootLocation().copy(writable = !connection.readOnlyOverride),
            ),
            includeInGlobalSearch = false,
        )
    }

    fun rootFor(connectionId: String) = connections.get(connectionId)?.let { connection ->
        storage.registeredStorageLocations().firstOrNull { it.root.providerId == connection.providerId }?.root
    }
}
