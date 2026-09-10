package com.zz.filemanager.core.remote

import com.zz.filemanager.core.model.StorageLocation
import com.zz.filemanager.core.model.StorageType
import com.zz.filemanager.core.storage.StorageRepository

/**
 * Rebuilds runtime providers from durable, non-secret connection metadata. The connection UUID is
 * the stable identity; secrets are resolved lazily from Android Keystore only when an operation
 * actually opens the provider.
 */
class RemoteProviderCoordinator(
    private val storage: StorageRepository,
    private val connections: NetworkConnectionRepository,
    private val credentials: SecureCredentialStore,
    private val factories: RemoteFileSystemFactoryRegistry,
) {
    @Synchronized
    fun syncSavedConnections() {
        val retained = linkedSetOf<String>()
        connections.list().forEach { connection ->
            if (connection.protocol == RemoteProtocol.DIRECT_CLOUD) return@forEach
            val hasFactory = runCatching { factories.factoryFor(connection.protocol) }.isSuccess
            if (!hasFactory) return@forEach
            val provider = RemoteStorageProvider(connection, connections, credentials, factories)
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
                // Avoid implicit connection attempts from an unscoped global search.
                includeInGlobalSearch = false,
            )
        }
        storage.retainExternalProviders(retained)
    }

    fun rootFor(connectionId: String) = connections.get(connectionId)?.let { connection ->
        storage.registeredStorageLocations().firstOrNull { it.root.providerId == connection.providerId }?.root
    }
}
