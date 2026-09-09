package com.zz.filemanager.core.library

import com.zz.filemanager.core.operation.FileOperation
import com.zz.filemanager.core.operation.FileOperationEngine
import com.zz.filemanager.core.operation.FileOperationType
import com.zz.filemanager.core.operation.OperationEvent
import com.zz.filemanager.core.operation.OperationItemState
import com.zz.filemanager.core.operation.OperationStore
import com.zz.filemanager.core.trash.TrashManager

class OperationLibrarySynchronizer(
    private val engine: FileOperationEngine,
    private val operations: OperationStore,
    private val library: UserLibraryStore,
    private val manager: UserLibraryManager,
    private val trashManager: TrashManager? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun run() {
        engine.events.collect { event ->
            val operationId = when (event) {
                is OperationEvent.Completed -> event.operationId
                is OperationEvent.Failed -> event.operationId
                else -> return@collect
            }
            val operation = operations.get(operationId) ?: return@collect
            if (trashManager?.onOperationTerminal(operation) == true) return@collect
            recordActivity(operation)
            applyReferenceChanges(operation)
        }
    }

    private suspend fun recordActivity(operation: FileOperation) {
        val successful = operation.items.count { it.state == OperationItemState.COMPLETED }.toLong().coerceAtLeast(
            if (operation.items.isEmpty() && operation.type in setOf(FileOperationType.CREATE_FILE, FileOperationType.CREATE_DIRECTORY)) 1L else 0L,
        )
        val firstName = operation.items.firstOrNull()?.source?.name ?: operation.targetName.orEmpty()
        val (kind, verb) = when (operation.type) {
            FileOperationType.COPY -> ActivityKind.COPIED to "Copied"
            FileOperationType.MOVE -> ActivityKind.MOVED to "Moved"
            FileOperationType.RENAME, FileOperationType.BATCH_RENAME -> ActivityKind.RENAMED to "Renamed"
            FileOperationType.DELETE -> ActivityKind.DELETED_PERMANENTLY to "Permanently deleted"
            FileOperationType.CREATE_DIRECTORY, FileOperationType.CREATE_FILE -> ActivityKind.CREATED to "Created"
        }
        val summary = if (successful == 1L && firstName.isNotBlank()) "$verb $firstName" else "$verb $successful items"
        manager.recordActivity(kind, summary, successful, operation.id)
    }

    private suspend fun applyReferenceChanges(operation: FileOperation) {
        operation.items.filter { it.state == OperationItemState.COMPLETED }.forEach { item ->
            val favorites = library.favorites.value.filter {
                it.reference.providerId == item.source.reference.providerId && it.reference.opaqueId == item.source.reference.opaqueId
            }
            val recents = library.recentFiles.value.filter {
                it.reference.providerId == item.source.reference.providerId && it.reference.opaqueId == item.source.reference.opaqueId
            }
            val result = item.resultReference
            when (operation.type) {
                FileOperationType.MOVE, FileOperationType.RENAME, FileOperationType.BATCH_RENAME -> if (result != null) {
                    val name = item.requestedName ?: item.source.name
                    favorites.forEach { library.updateFavorite(it.copy(reference = result.reference, rootReference = result.rootReference, storageId = result.storageId, displayName = name, lastValidatedAtMillis = now(), status = LibraryItemStatus.AVAILABLE)) }
                    recents.forEach { library.recordRecentFile(it.copy(reference = result.reference, rootReference = result.rootReference, storageId = result.storageId, displayName = name)) }
                }
                FileOperationType.DELETE -> {
                    favorites.forEach { library.updateFavorite(it.copy(status = LibraryItemStatus.UNAVAILABLE, lastValidatedAtMillis = now())) }
                    recents.forEach { library.recordRecentFile(it.copy(status = LibraryItemStatus.UNAVAILABLE)) }
                }
                else -> Unit
            }
        }
    }
}
