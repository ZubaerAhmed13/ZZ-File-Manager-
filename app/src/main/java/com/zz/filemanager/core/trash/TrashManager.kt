package com.zz.filemanager.core.trash

import com.zz.filemanager.core.library.ActivityKind
import com.zz.filemanager.core.library.LibraryItemStatus
import com.zz.filemanager.core.library.TrashBackendType
import com.zz.filemanager.core.library.TrashRecord
import com.zz.filemanager.core.library.TrashState
import com.zz.filemanager.core.library.UserLibraryManager
import com.zz.filemanager.core.library.UserLibraryStore
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.ScopedFileReference
import com.zz.filemanager.core.search.SearchCoordinator
import com.zz.filemanager.core.search.SearchRootSource
import com.zz.filemanager.core.storage.StorageCapability
import com.zz.filemanager.core.storage.StorageProviderRegistry
import com.zz.filemanager.core.storage.WritableStorageProvider
import com.zz.filemanager.core.operation.FileOperation
import com.zz.filemanager.core.operation.FileOperationController
import com.zz.filemanager.core.operation.FileOperationState
import com.zz.filemanager.core.operation.OperationSource
import com.zz.filemanager.core.operation.OperationStore
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RestoreCollisionPolicy { KEEP_BOTH, REPLACE, CANCEL }

sealed interface TrashResult {
    data class Success(val record: TrashRecord) : TrashResult
    data class Queued(val record: TrashRecord, val operationId: String) : TrashResult
    data class Collision(val existing: FileEntry, val canReplace: Boolean = false) : TrashResult
    data class MissingOriginal(val record: TrashRecord) : TrashResult
    data class Unsupported(val reason: String) : TrashResult
    data class Failed(val reason: String) : TrashResult
}

data class EmptyTrashResult(val deleted: Int, val failed: Int)

/**
 * Transactional app-managed recycle backend. Same-storage native move gives large directories and
 * 30+ GiB payloads bounded-memory, no-second-copy behavior. When native move is unavailable, the
 * durable Step 2 move engine provides copy/verification/source-delete ordering; providers lacking
 * either safe path are reported unsupported.
 */
class TrashManager(
    private val providers: StorageProviderRegistry,
    private val store: UserLibraryStore,
    private val libraryManager: UserLibraryManager,
    private val rootSource: SearchRootSource? = null,
    private val operationController: FileOperationController? = null,
    private val operationStore: OperationStore? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val mutationMutex = Mutex()
    private val activeMutations = mutableSetOf<String>()
    suspend fun recordPlatformTrash(entries: List<FileEntry>, parent: BrowserLocation) {
        store.initialize()
        val timestamp = now()
        var recorded = 0L
        entries.forEach { entry ->
            if (store.trashRecords.value.any { it.originalReference.providerId == entry.reference.providerId && it.originalReference.opaqueId == entry.reference.opaqueId }) return@forEach
            val record = TrashRecord(
                UUID.randomUUID().toString(), TrashBackendType.MEDIA_STORE, entry.reference, parent, entry.name,
                entry.type, entry.sizeBytes, entry.modifiedAtMillis,
                ScopedFileReference(entry.reference, parent.rootReference, parent.storageId), null, timestamp, timestamp, TrashState.TRASHED,
            )
            store.upsertTrash(record)
            markRelatedItemsTrashed(record)
            recorded++
        }
        if (recorded > 0L) libraryManager.recordActivity(ActivityKind.TRASHED, "Moved $recorded media item(s) to Recycle Bin", recorded)
    }

    suspend fun completePlatformRestore(records: List<TrashRecord>) {
        records.forEach { record ->
            store.removeTrash(record.id)
            val metadata = providers.providerFor(record.originalReference.providerId).getMetadata(record.originalReference)
            if (metadata != null) restoreRelatedItems(record, metadata)
            else {
                store.favorites.value.filter { it.trashId == record.id }.forEach { store.updateFavorite(it.copy(status = LibraryItemStatus.AVAILABLE, trashId = null, lastValidatedAtMillis = now())) }
                store.recentFiles.value.filter { it.trashId == record.id }.forEach { store.recordRecentFile(it.copy(status = LibraryItemStatus.AVAILABLE, trashId = null)) }
            }
        }
        if (records.isNotEmpty()) libraryManager.recordActivity(ActivityKind.RESTORED, "Restored ${records.size} media item(s)", records.size.toLong())
    }

    suspend fun completePlatformDelete(records: List<TrashRecord>) {
        records.forEach { store.removeTrash(it.id) }
    }

    suspend fun trash(entry: FileEntry, parent: BrowserLocation, operationId: String? = null): TrashResult = guarded(
        "source:${entry.reference.providerId}:${entry.reference.opaqueId}",
        TrashResult.Failed("A recycle operation for this item is already active."),
    ) { trashInternal(entry, parent, operationId) }

    private suspend fun trashInternal(entry: FileEntry, parent: BrowserLocation, operationId: String?): TrashResult {
        store.initialize()
        val previousAtReference = store.trashRecords.value.firstOrNull {
            it.originalReference.providerId == entry.reference.providerId && it.originalReference.opaqueId == entry.reference.opaqueId && it.state != TrashState.DELETED
        }
        if (previousAtReference != null) {
            val sourceExists = runCatching { providers.providerFor(entry.reference.providerId).exists(entry.reference) }.getOrDefault(false)
            // A proven trashed item followed by a newly created object at the same path/document
            // is legitimate (for example, report.pdf recreated after the old copy was trashed).
            // In-flight/failed transactions remain locked even while their source is preserved.
            if (previousAtReference.state != TrashState.TRASHED || !sourceExists) {
                return TrashResult.Failed("This item already has a Recycle Bin transaction.")
            }
        }
        if (entry.reference.providerId == "media") return TrashResult.Unsupported("Platform media confirmation is required.")
        val provider = providers.writableProviderFor(entry.reference.providerId)
            ?: return TrashResult.Unsupported("Recycle Bin is not supported for this location.")
        val root = rootLocation(parent)
        val trashRoot = ensureDirectory(provider, root, SearchCoordinator.RESERVED_RECYCLE_DIRECTORY)
            ?: return TrashResult.Unsupported("This provider cannot create a safe recycle namespace.")
        val id = UUID.randomUUID().toString()
        var record = TrashRecord(
            id = id, backend = TrashBackendType.APP_MANAGED, originalReference = entry.reference,
            originalParent = parent, originalName = entry.name, type = entry.type, sizeBytes = entry.sizeBytes,
            modifiedAtMillis = entry.modifiedAtMillis, trashReference = null, trashedAtMillis = now(),
            containerReference = null,
            updatedAtMillis = now(), state = TrashState.PREPARING, operationId = operationId,
        )
        store.upsertTrash(record)
        return try {
            val container = provider.createDirectory(trashRoot, id)
            val containerLocation = container.asLocation(trashRoot)
            val containerScoped = ScopedFileReference(container.reference, parent.rootReference, parent.storageId)
            record = record.copy(trashReference = containerScoped, containerReference = containerScoped, state = TrashState.MOVING, updatedAtMillis = now())
            store.upsertTrash(record)
            val source = ScopedFileReference(entry.reference, parent.rootReference, parent.storageId)
            if (!provider.canMoveNative(source, containerLocation, entry.name)) {
                val controller = operationController
                if (controller == null) {
                    record = record.copy(state = TrashState.FAILED, updatedAtMillis = now(), failureReason = "Safe same-storage native move is unavailable.")
                    store.upsertTrash(record)
                    runCatching { provider.delete(containerScoped) }
                    TrashResult.Unsupported("Recycle Bin is not supported safely for this provider.")
                } else {
                    val queuedId = controller.enqueueMove(
                        listOf(OperationSource(entry.reference, parent.rootReference, parent.storageId, entry.name, entry.isDirectory, entry.sizeBytes, entry.modifiedAtMillis, entry.mimeType, entry.isSymbolicLink)),
                        containerLocation,
                    )
                    record = record.copy(state = TrashState.COPYING, operationId = queuedId, updatedAtMillis = now(), failureReason = null)
                    store.upsertTrash(record)
                    TrashResult.Queued(record, queuedId)
                }
            } else {
                val payload = provider.moveNative(source, containerLocation, entry.name)
                    ?: throw IllegalStateException("Provider declined a previously confirmed native move")
                record = record.copy(
                    trashReference = ScopedFileReference(payload.reference, parent.rootReference, parent.storageId),
                    state = TrashState.TRASHED,
                    updatedAtMillis = now(),
                )
                store.upsertTrash(record)
                markRelatedItemsTrashed(record)
                libraryManager.recordActivity(ActivityKind.TRASHED, "Moved ${entry.name} to Recycle Bin", 1L, operationId)
                TrashResult.Success(record)
            }
        } catch (error: Throwable) {
            record = record.copy(state = TrashState.INTERRUPTED, updatedAtMillis = now(), failureReason = error.message)
            store.upsertTrash(record)
            TrashResult.Failed(error.message ?: "Recycle operation was interrupted.")
        }
    }

    suspend fun restore(recordId: String, policy: RestoreCollisionPolicy = RestoreCollisionPolicy.CANCEL): TrashResult = guarded(
        "record:$recordId",
        TrashResult.Failed("This Recycle Bin item is already being changed."),
    ) { restoreInternal(recordId, policy) }

    private suspend fun restoreInternal(recordId: String, policy: RestoreCollisionPolicy): TrashResult {
        val original = store.trashRecords.value.firstOrNull { it.id == recordId }
            ?: return TrashResult.Failed("Recycle item is unavailable.")
        if (original.state !in setOf(TrashState.TRASHED, TrashState.INTERRUPTED, TrashState.FAILED)) return TrashResult.Failed("Recycle item is busy.")
        if (hasActiveLinkedOperation(original)) return TrashResult.Failed("The linked file operation must finish or be cancelled first.")
        val payload = original.trashReference ?: return TrashResult.Failed("Recycle payload is missing.")
        val provider = providers.writableProviderFor(payload.reference.providerId)
            ?: return TrashResult.Unsupported("Original storage is unavailable.")
        if (!providers.providerFor(original.originalParent.providerId).existsLocation(original.originalParent)) {
            return TrashResult.MissingOriginal(original)
        }
        val existing = provider.findChild(original.originalParent, original.originalName)
        if (existing != null && policy == RestoreCollisionPolicy.CANCEL) {
            val canReplace = StorageCapability.ATOMIC_RENAME in provider.capabilities(original.originalParent)
            return TrashResult.Collision(existing, canReplace)
        }
        val plannedName = when {
            existing == null -> original.originalName
            policy == RestoreCollisionPolicy.KEEP_BOTH -> keepBothName(provider, original.originalParent, original.originalName)
            else -> original.originalName
        }
        var record = original.copy(
            state = TrashState.RESTORING, updatedAtMillis = now(), failureReason = null, operationId = null,
            restoreDestination = original.originalParent, restoreName = plannedName,
            restoreReplace = existing != null && policy == RestoreCollisionPolicy.REPLACE,
        )
        store.upsertTrash(record)
        return try {
            val restored = when {
                existing == null -> movePayload(provider, payload, original.originalParent, plannedName)
                policy == RestoreCollisionPolicy.KEEP_BOTH -> movePayload(provider, payload, original.originalParent, plannedName)
                policy == RestoreCollisionPolicy.REPLACE -> replaceFromTrash(provider, payload, existing, original) { staged ->
                    record = record.copy(trashReference = staged, updatedAtMillis = now())
                    store.upsertTrash(record)
                }
                else -> return TrashResult.Collision(existing, false)
            }
            val restoredRecord = record.copy(
                state = TrashState.DELETED,
                updatedAtMillis = now(),
                trashReference = ScopedFileReference(restored.reference, original.originalParent.rootReference, original.originalParent.storageId),
            )
            store.removeTrash(record.id)
            restoreRelatedItems(original, restored)
            cleanupContainer(provider, payload, original)
            libraryManager.recordActivity(ActivityKind.RESTORED, "Restored ${original.originalName}", 1L, original.operationId)
            TrashResult.Success(restoredRecord)
        } catch (error: Throwable) {
            record = record.copy(state = TrashState.INTERRUPTED, updatedAtMillis = now(), failureReason = error.message)
            store.upsertTrash(record)
            TrashResult.Failed(error.message ?: "Restore was interrupted; the recycle payload was preserved.")
        }
    }

    /** Restores to a user-approved alternate SAF/local location using a native move when possible,
     * otherwise the durable Step 2 move engine. The trash record is retained until that engine
     * proves the destination and removes the payload. */
    suspend fun restoreTo(recordId: String, destination: BrowserLocation): TrashResult = guarded(
        "record:$recordId",
        TrashResult.Failed("This Recycle Bin item is already being changed."),
    ) { restoreToInternal(recordId, destination) }

    private suspend fun restoreToInternal(recordId: String, destination: BrowserLocation): TrashResult {
        val original = store.trashRecords.value.firstOrNull { it.id == recordId }
            ?: return TrashResult.Failed("Recycle item is unavailable.")
        if (original.state !in setOf(TrashState.TRASHED, TrashState.INTERRUPTED, TrashState.FAILED)) return TrashResult.Failed("Recycle item is busy.")
        if (hasActiveLinkedOperation(original)) return TrashResult.Failed("The linked file operation must finish or be cancelled first.")
        val payload = original.trashReference ?: return TrashResult.Failed("Recycle payload is missing.")
        val payloadProvider = providers.writableProviderFor(payload.reference.providerId)
            ?: return TrashResult.Unsupported("Recycle storage is unavailable.")
        val destinationProvider = providers.writableProviderFor(destination.providerId)
            ?: return TrashResult.Unsupported("Chosen destination is read-only.")
        destinationProvider.findChild(destination, original.originalName)?.let { return TrashResult.Collision(it, false) }
        var recoveryRecord = original
        return try {
            if (payload.reference.providerId == destination.providerId && payloadProvider.canMoveNative(payload, destination, original.originalName)) {
                val moving = original.copy(
                    state = TrashState.RESTORING, updatedAtMillis = now(), failureReason = null, operationId = null,
                    restoreDestination = destination, restoreName = original.originalName, restoreReplace = false,
                )
                recoveryRecord = moving
                store.upsertTrash(moving)
                val restored = payloadProvider.moveNative(payload, destination, original.originalName)
                    ?: throw IllegalStateException("Native restore did not complete")
                store.removeTrash(original.id)
                restoreRelatedItems(original, restored)
                cleanupContainer(payloadProvider, payload, original)
                libraryManager.recordActivity(ActivityKind.RESTORED, "Restored ${original.originalName}", 1L, original.operationId)
                TrashResult.Success(moving.copy(state = TrashState.DELETED, trashReference = ScopedFileReference(restored.reference, destination.rootReference, destination.storageId), updatedAtMillis = now()))
            } else {
                val controller = operationController ?: return TrashResult.Unsupported("Safe cross-location restore is unavailable.")
                val operationId = controller.enqueueMove(
                    listOf(OperationSource(payload.reference, payload.rootReference, payload.storageId, original.originalName, original.type == com.zz.filemanager.core.model.FileEntryType.DIRECTORY, original.sizeBytes, original.modifiedAtMillis, null)),
                    destination,
                )
                val queued = original.copy(
                    state = TrashState.RESTORING, operationId = operationId, updatedAtMillis = now(), failureReason = null,
                    restoreDestination = destination, restoreName = original.originalName, restoreReplace = false,
                )
                recoveryRecord = queued
                store.upsertTrash(queued)
                TrashResult.Queued(queued, operationId)
            }
        } catch (error: Throwable) {
            val interrupted = recoveryRecord.copy(state = TrashState.INTERRUPTED, updatedAtMillis = now(), failureReason = error.message)
            store.upsertTrash(interrupted)
            TrashResult.Failed(error.message ?: "Restore was interrupted; the recycle payload was preserved.")
        }
    }

    suspend fun deletePermanently(recordId: String): Boolean = guarded("record:$recordId", false) {
        val original = store.trashRecords.value.firstOrNull { it.id == recordId } ?: return@guarded false
        if (original.state !in setOf(TrashState.TRASHED, TrashState.FAILED, TrashState.CORRUPTED) || hasActiveLinkedOperation(original)) return@guarded false
        val payload = original.trashReference ?: return@guarded false
        val provider = providers.writableProviderFor(payload.reference.providerId) ?: return@guarded false
        store.upsertTrash(original.copy(state = TrashState.DELETE_PENDING, updatedAtMillis = now()))
        return@guarded try {
            deleteTree(provider, payload, original)
            store.removeTrash(recordId)
            cleanupContainer(provider, payload, original)
            true
        } catch (error: Throwable) {
            store.upsertTrash(original.copy(state = TrashState.FAILED, updatedAtMillis = now(), failureReason = error.message))
            false
        }
    }

    suspend fun empty(): EmptyTrashResult {
        var deleted = 0
        var failed = 0
        store.trashRecords.value.filter { it.backend == TrashBackendType.APP_MANAGED && (it.state == TrashState.TRASHED || it.state == TrashState.FAILED) }.forEach {
            if (deletePermanently(it.id)) deleted++ else failed++
        }
        libraryManager.recordActivity(ActivityKind.TRASH_EMPTIED, "Emptied Recycle Bin: $deleted deleted, $failed failed", deleted.toLong())
        return EmptyTrashResult(deleted, failed)
    }

    suspend fun cleanupExpired(retentionMillis: Long): EmptyTrashResult {
        if (retentionMillis == Long.MAX_VALUE) return EmptyTrashResult(0, 0)
        val threshold = now() - retentionMillis
        var deleted = 0
        var failed = 0
        store.trashRecords.value.filter { it.backend == TrashBackendType.APP_MANAGED && it.state == TrashState.TRASHED && it.trashedAtMillis <= threshold }.forEach {
            if (deletePermanently(it.id)) deleted++ else failed++
        }
        return EmptyTrashResult(deleted, failed)
    }

    suspend fun reconcile() {
        store.initialize()
        store.trashRecords.value.forEach { record ->
            if (record.backend != TrashBackendType.APP_MANAGED || record.state == TrashState.TRASHED) return@forEach
            val linkedOperation = record.operationId?.let { operationStore?.get(it) }
            if (linkedOperation != null && linkedOperation.state.isTerminal) {
                finalizeFallbackRecord(record, linkedOperation)
                return@forEach
            }
            val provider = runCatching { providers.providerFor(record.originalReference.providerId) }.getOrNull() ?: return@forEach
            val sourceExists = runCatching { provider.exists(record.originalReference) }.getOrDefault(false)
            val container = record.containerReference
            val recordedPayload = record.trashReference?.takeUnless { candidate -> candidate.reference.opaqueId == container?.reference?.opaqueId }
            val discoveredPayload = container?.let { containerRef ->
                val raw = containerRef.reference.uri ?: containerRef.reference.path ?: containerRef.reference.opaqueId
                val location = BrowserLocation(containerRef.reference.providerId, containerRef.reference.opaqueId, record.id, raw, containerRef.rootReference, containerRef.storageId, true, true)
                runCatching { provider.listChildren(location).singleOrNull { it.name == record.originalName } }.getOrNull()
                    ?.let { ScopedFileReference(it.reference, containerRef.rootReference, containerRef.storageId) }
            }
            val physicalPayload = discoveredPayload ?: recordedPayload
            val trashExists = physicalPayload?.let { runCatching { provider.exists(it.reference) }.getOrDefault(false) } == true
            if (record.state == TrashState.RESTORING && !trashExists) {
                val destination = record.restoreDestination
                val restored = if (destination != null && record.restoreName != null) runCatching {
                    providers.writableProviderFor(destination.providerId)?.findChild(destination, record.restoreName)
                }.getOrNull() else null
                if (restored != null && !record.restoreReplace) {
                    store.removeTrash(record.id)
                    restoreRelatedItems(record, restored)
                    libraryManager.recordActivity(ActivityKind.RESTORED, "Recovered restored ${record.originalName}", 1L, record.operationId)
                    return@forEach
                }
            }
            val reconciled = when {
                trashExists && !sourceExists -> record.copy(trashReference = physicalPayload, state = TrashState.TRASHED, updatedAtMillis = now(), failureReason = null)
                sourceExists && !trashExists -> record.copy(state = TrashState.FAILED, updatedAtMillis = now(), failureReason = "Source is safe; recycle move did not complete.")
                trashExists && sourceExists -> record.copy(state = TrashState.INTERRUPTED, updatedAtMillis = now(), failureReason = "Both source and recycle data exist; manual recovery is required.")
                else -> record.copy(state = TrashState.CORRUPTED, updatedAtMillis = now(), failureReason = "Neither source nor recycle payload can be verified.")
            }
            store.upsertTrash(reconciled)
        }
        reconcileOrphanPayloads()
    }

    suspend fun onOperationTerminal(operation: FileOperation): Boolean {
        val records = store.trashRecords.value.filter { it.operationId == operation.id && it.state in setOf(TrashState.COPYING, TrashState.RESTORING, TrashState.INTERRUPTED, TrashState.FAILED) }
        records.forEach { finalizeFallbackRecord(it, operation) }
        return records.isNotEmpty()
    }

    private suspend fun finalizeFallbackRecord(record: TrashRecord, operation: FileOperation) {
        val rootItem = operation.items.firstOrNull { it.rootItemId == it.id } ?: operation.items.firstOrNull()
        val isRestoreOperation = record.state == TrashState.RESTORING || operation.items.firstOrNull()?.source?.reference?.opaqueId == record.trashReference?.reference?.opaqueId
        if (operation.state == FileOperationState.COMPLETED && rootItem?.resultReference != null) {
            if (isRestoreOperation) {
                val result = rootItem.resultReference
                val metadata = providers.providerFor(result.reference.providerId).getMetadata(result.reference)
                    ?: FileEntry(
                        id = result.reference.opaqueId, reference = result.reference, name = record.originalName,
                        extension = record.originalName.substringAfterLast('.', "").takeIf { it.isNotEmpty() }, mimeType = null,
                        type = record.type, sizeBytes = record.sizeBytes, modifiedAtMillis = record.modifiedAtMillis,
                        createdAtMillis = null, isHidden = false, isReadable = true, isWritable = true,
                        childCount = null, storageId = result.storageId, thumbnailKey = null,
                    )
                store.removeTrash(record.id)
                restoreRelatedItems(record, metadata)
                val payload = record.trashReference
                val provider = payload?.let { providers.writableProviderFor(it.reference.providerId) }
                if (payload != null && provider != null) cleanupContainer(provider, payload, record)
                libraryManager.recordActivity(ActivityKind.RESTORED, "Restored ${record.originalName}", 1L, operation.id)
                return
            }
            val completed = record.copy(trashReference = rootItem.resultReference, state = TrashState.TRASHED, updatedAtMillis = now(), failureReason = null)
            store.upsertTrash(completed)
            markRelatedItemsTrashed(completed)
            libraryManager.recordActivity(ActivityKind.TRASHED, "Moved ${record.originalName} to Recycle Bin", 1L, operation.id)
        } else {
            val interrupted = operation.state == FileOperationState.INTERRUPTED || operation.state == FileOperationState.COMPLETED_WITH_WARNINGS
            store.upsertTrash(record.copy(
                state = if (interrupted) TrashState.INTERRUPTED else TrashState.FAILED,
                updatedAtMillis = now(),
                failureReason = operation.failure?.message ?: rootItem?.failure?.message ?: "Recycle transfer did not reach a proven terminal state.",
            ))
        }
    }

    private suspend fun reconcileOrphanPayloads() {
        val roots = rootSource?.accessibleRoots().orEmpty().filter { it.writable && it.providerId != "media" }
        val knownContainers = store.trashRecords.value.mapNotNull { it.containerReference?.reference?.opaqueId }.toHashSet()
        roots.forEach { root ->
            val provider = providers.writableProviderFor(root.providerId) ?: return@forEach
            val recycle = runCatching { provider.findChild(rootLocation(root), SearchCoordinator.RESERVED_RECYCLE_DIRECTORY) }.getOrNull()
                ?: return@forEach
            if (!recycle.isDirectory) return@forEach
            val recycleLocation = recycle.asLocation(root)
            val containers = runCatching { provider.listChildren(recycleLocation) }.getOrDefault(emptyList())
            containers.filter { it.isDirectory && it.reference.opaqueId !in knownContainers }.forEach { container ->
                val containerLocation = container.asLocation(recycleLocation)
                val payloads = runCatching { provider.listChildren(containerLocation) }.getOrDefault(emptyList())
                if (payloads.isEmpty()) {
                    runCatching { provider.delete(ScopedFileReference(container.reference, root.rootReference, root.storageId)) }
                    return@forEach
                }
                val payload = payloads.singleOrNull()
                val timestamp = now()
                val containerScoped = ScopedFileReference(container.reference, root.rootReference, root.storageId)
                val payloadScoped = payload?.let { ScopedFileReference(it.reference, root.rootReference, root.storageId) } ?: containerScoped
                val id = "orphan-${container.reference.opaqueId.hashCode().toUInt().toString(16)}"
                store.upsertTrash(
                    TrashRecord(
                        id = id,
                        backend = TrashBackendType.APP_MANAGED,
                        originalReference = payload?.reference ?: container.reference,
                        originalParent = root,
                        originalName = payload?.name ?: container.name,
                        type = payload?.type ?: container.type,
                        sizeBytes = payload?.sizeBytes,
                        modifiedAtMillis = payload?.modifiedAtMillis,
                        trashReference = payloadScoped,
                        containerReference = containerScoped,
                        trashedAtMillis = timestamp,
                        updatedAtMillis = timestamp,
                        state = TrashState.CORRUPTED,
                        failureReason = "Recycle payload has no trusted catalog record. It was preserved for manual recovery.",
                    ),
                )
            }
        }
    }

    private suspend fun ensureDirectory(provider: WritableStorageProvider, parent: BrowserLocation, name: String): BrowserLocation? {
        val capabilities = provider.capabilities(parent)
        if (StorageCapability.CREATE_DIRECTORY !in capabilities || StorageCapability.RENAME !in capabilities || StorageCapability.DELETE !in capabilities) return null
        val entry = provider.findChild(parent, name) ?: provider.createDirectory(parent, name)
        if (!entry.isDirectory) return null
        return entry.asLocation(parent)
    }

    private fun rootLocation(location: BrowserLocation) = location.copy(
        id = "${location.providerId}:${location.rootReference}", displayName = location.storageId,
        reference = location.rootReference, readable = true, writable = true,
    )

    private fun FileEntry.asLocation(parent: BrowserLocation) = BrowserLocation(
        reference.providerId, id, name, reference.uri ?: reference.path ?: reference.opaqueId,
        parent.rootReference, parent.storageId, isReadable, isWritable,
    )

    private suspend fun movePayload(provider: WritableStorageProvider, payload: ScopedFileReference, destination: BrowserLocation, name: String): FileEntry {
        if (!provider.canMoveNative(payload, destination, name)) throw IllegalStateException("Safe native restore is unavailable")
        return provider.moveNative(payload, destination, name) ?: throw IllegalStateException("Native restore did not complete")
    }

    private suspend fun replaceFromTrash(
        provider: WritableStorageProvider,
        payload: ScopedFileReference,
        existing: FileEntry,
        record: TrashRecord,
        persistStaged: suspend (ScopedFileReference) -> Unit,
    ): FileEntry {
        val capabilities = provider.capabilities(record.originalParent)
        if (StorageCapability.ATOMIC_RENAME !in capabilities) throw IllegalStateException("Safe Replace is unavailable for this provider")
        val stageName = ".zzrestore-${record.id}"
        val existingStage = provider.findChild(record.originalParent, stageName)
        val stagedEntry = if (existingStage?.reference?.opaqueId == payload.reference.opaqueId) existingStage
            else movePayload(provider, payload, record.originalParent, stageName)
        val staged = ScopedFileReference(stagedEntry.reference, record.originalParent.rootReference, record.originalParent.storageId)
        persistStaged(staged)
        val existingScoped = ScopedFileReference(existing.reference, record.originalParent.rootReference, record.originalParent.storageId)
        return provider.replaceAtomically(staged, existingScoped, record.originalName)
            ?: run {
                runCatching { provider.moveNative(staged, payloadParent(record), record.originalName) }.getOrNull()?.let { rolledBack ->
                    persistStaged(ScopedFileReference(rolledBack.reference, payload.rootReference, payload.storageId))
                }
                throw IllegalStateException("Atomic Replace was not available; existing destination was preserved")
            }
    }

    private fun payloadParent(record: TrashRecord): BrowserLocation {
        val container = requireNotNull(record.containerReference)
        val raw = container.reference.uri ?: container.reference.path ?: container.reference.opaqueId
        return BrowserLocation(container.reference.providerId, container.reference.opaqueId, record.id, raw, container.rootReference, container.storageId, true, true)
    }

    private suspend fun keepBothName(provider: WritableStorageProvider, parent: BrowserLocation, original: String): String {
        val dot = original.lastIndexOf('.').takeIf { it > 0 }
        val base = if (dot == null) original else original.substring(0, dot)
        val extension = if (dot == null) "" else original.substring(dot)
        var index = 1
        while (true) {
            val candidate = "$base ($index)$extension"
            if (provider.findChild(parent, candidate) == null) return candidate
            index++
        }
    }

    private suspend fun deleteTree(provider: WritableStorageProvider, payload: ScopedFileReference, record: TrashRecord) {
        val readProvider = providers.providerFor(payload.reference.providerId)
        val metadata = readProvider.getMetadata(payload.reference)
        if (metadata == null) {
            if (readProvider.exists(payload.reference)) throw IllegalStateException("Recycle payload exists but its metadata is unavailable")
            return
        }
        if (!metadata.isDirectory || metadata.isSymbolicLink) {
            if (!provider.delete(payload) && providers.providerFor(payload.reference.providerId).exists(payload.reference)) {
                throw IllegalStateException("Provider did not delete the recycle payload")
            }
            return
        }
        val root = metadata.asLocation(payloadParent(record))
        data class Pending(val entry: FileEntry, val parent: BrowserLocation, val visited: Boolean)
        val stack = ArrayDeque<Pending>()
        stack.addLast(Pending(metadata, payloadParent(record), false))
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val scoped = ScopedFileReference(current.entry.reference, payload.rootReference, payload.storageId)
            if (!current.entry.isDirectory || current.entry.isSymbolicLink || current.visited) {
                if (!provider.delete(scoped) && providers.providerFor(payload.reference.providerId).exists(scoped.reference)) {
                    throw IllegalStateException("Provider did not delete ${current.entry.name}")
                }
            } else {
                stack.addLast(current.copy(visited = true))
                val location = current.entry.asLocation(current.parent)
                providers.providerFor(payload.reference.providerId).listChildren(location).forEach { stack.addLast(Pending(it, location, false)) }
            }
        }
        if (providers.providerFor(payload.reference.providerId).exists(payload.reference)) throw IllegalStateException("Recycle payload still exists after deletion")
    }

    private suspend fun cleanupContainer(provider: WritableStorageProvider, payload: ScopedFileReference, record: TrashRecord) {
        val container = record.containerReference ?: return
        runCatching { provider.delete(container) }
    }

    private suspend fun markRelatedItemsTrashed(record: TrashRecord) {
        store.favorites.value.filter { it.reference.providerId == record.originalReference.providerId && it.reference.opaqueId == record.originalReference.opaqueId }.forEach { store.updateFavorite(it.copy(status = LibraryItemStatus.TRASHED, trashId = record.id)) }
        store.recentFiles.value.filter { it.reference.providerId == record.originalReference.providerId && it.reference.opaqueId == record.originalReference.opaqueId }.forEach { store.recordRecentFile(it.copy(status = LibraryItemStatus.TRASHED, trashId = record.id)) }
    }

    private suspend fun restoreRelatedItems(record: TrashRecord, restored: FileEntry) {
        store.favorites.value.filter { it.trashId == record.id }.forEach { favorite ->
            store.updateFavorite(favorite.copy(reference = restored.reference, displayName = restored.name, status = LibraryItemStatus.AVAILABLE, trashId = null, lastValidatedAtMillis = now()))
        }
        store.recentFiles.value.filter { it.trashId == record.id }.forEach { recent ->
            store.recordRecentFile(recent.copy(reference = restored.reference, displayName = restored.name, status = LibraryItemStatus.AVAILABLE, trashId = null))
        }
    }

    private suspend fun com.zz.filemanager.core.storage.StorageProvider.existsLocation(location: BrowserLocation): Boolean {
        val isContentUri = location.reference.startsWith("content://")
        val reference = com.zz.filemanager.core.model.FileReference(
            providerId = location.providerId,
            opaqueId = location.id,
            uri = location.reference.takeIf { isContentUri },
            path = location.reference.takeUnless { isContentUri },
        )
        return exists(reference)
    }

    private suspend fun <T> guarded(key: String, busyResult: T, block: suspend () -> T): T {
        val acquired = mutationMutex.withLock { activeMutations.add(key) }
        if (!acquired) return busyResult
        return try { block() } finally { mutationMutex.withLock { activeMutations.remove(key) } }
    }

    private suspend fun hasActiveLinkedOperation(record: TrashRecord): Boolean {
        val operation = record.operationId?.let { operationStore?.get(it) } ?: return false
        return !operation.state.isTerminal
    }
}
