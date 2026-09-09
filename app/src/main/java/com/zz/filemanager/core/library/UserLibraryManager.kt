package com.zz.filemanager.core.library

import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.FileEntryType
import com.zz.filemanager.core.storage.StorageProviderRegistry
import java.util.UUID

class UserLibraryManager(
    private val store: UserLibraryStore,
    private val providers: StorageProviderRegistry,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun toggleFavorite(entry: FileEntry, parent: BrowserLocation): Boolean {
        store.initialize()
        val id = entry.reference.stableIdentity(parent.rootReference, parent.storageId)
        val existing = store.favorites.value.firstOrNull { it.id == id }
        if (existing != null) {
            store.removeFavorite(id)
            return false
        }
        store.upsertFavorite(
            FavoriteItem(
                id = id,
                reference = entry.reference,
                rootReference = parent.rootReference,
                storageId = parent.storageId,
                parentLocation = parent,
                displayName = entry.name,
                type = entry.type,
                addedAtMillis = now(),
            ),
        )
        return true
    }

    suspend fun favoriteCurrentFolder(location: BrowserLocation): Boolean {
        store.initialize()
        val reference = com.zz.filemanager.core.model.FileReference(
            location.providerId,
            location.id,
            uri = location.reference.takeIf { location.providerId == "saf" },
            path = location.reference.takeIf { location.providerId == "local" },
        )
        val id = reference.stableIdentity(location.rootReference, location.storageId)
        val existing = store.favorites.value.firstOrNull { it.id == id }
        if (existing != null) { store.removeFavorite(id); return false }
        val parent = runCatching { providers.providerFor(location.providerId).resolveParent(location) }.getOrNull()
        store.upsertFavorite(FavoriteItem(id, reference, location.rootReference, location.storageId, parent, location.displayName, FileEntryType.DIRECTORY, now()))
        return true
    }

    suspend fun recordOpened(entry: FileEntry, parent: BrowserLocation) {
        if (entry.isDirectory) return
        val timestamp = now()
        val id = entry.reference.stableIdentity(parent.rootReference, parent.storageId)
        store.recordRecentFile(RecentFile(id, entry.reference, parent.rootReference, parent.storageId, parent, entry.name, entry.type, timestamp))
        store.favorites.value.firstOrNull { it.id == id }?.let { store.updateFavorite(it.copy(lastOpenedAtMillis = timestamp)) }
    }

    suspend fun validateFavorites() {
        store.initialize()
        val timestamp = now()
        store.favorites.value.forEach { favorite ->
            if (favorite.status == LibraryItemStatus.TRASHED) return@forEach
            val available = runCatching { providers.providerFor(favorite.reference.providerId).exists(favorite.reference) }.getOrDefault(false)
            val status = if (available) LibraryItemStatus.AVAILABLE else LibraryItemStatus.UNAVAILABLE
            if (status != favorite.status || favorite.lastValidatedAtMillis == null) {
                store.updateFavorite(favorite.copy(status = status, lastValidatedAtMillis = timestamp))
            }
        }
    }

    suspend fun validateRecents() {
        store.initialize()
        store.recentFiles.value.forEach { recent ->
            if (recent.status == LibraryItemStatus.TRASHED) return@forEach
            val available = runCatching { providers.providerFor(recent.reference.providerId).exists(recent.reference) }.getOrDefault(false)
            val next = if (available) LibraryItemStatus.AVAILABLE else LibraryItemStatus.UNAVAILABLE
            if (next != recent.status) store.recordRecentFile(recent.copy(status = next))
        }
    }

    suspend fun recordActivity(kind: ActivityKind, summary: String, itemCount: Long, operationId: String? = null) {
        store.recordActivity(ActivityEntry(UUID.randomUUID().toString(), kind, summary, itemCount, now(), operationId))
    }
}
