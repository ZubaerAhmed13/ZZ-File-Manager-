package com.zz.filemanager.core.operation

import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

fun interface OperationExecutionHost {
    fun requestExecution()
}

class FileOperationController(
    private val store: OperationStore,
    private val executionHost: OperationExecutionHost,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    val operations: StateFlow<List<FileOperation>> get() = store.operations
    private val submitMutex = Mutex()
    private var lastSubmission: Pair<String, Pair<Long, String>>? = null

    suspend fun initialize() = store.initialize()

    suspend fun enqueueCopy(sources: List<OperationSource>, destination: BrowserLocation): String = enqueueTransfer(FileOperationType.COPY, sources, destination)
    suspend fun enqueueMove(sources: List<OperationSource>, destination: BrowserLocation): String = enqueueTransfer(FileOperationType.MOVE, sources, destination)

    suspend fun enqueueDelete(sources: List<OperationSource>): String = enqueue(
        type = FileOperationType.DELETE,
        sources = sources,
        destination = null,
        signature = "delete:${sources.joinToString { it.reference.opaqueId }}",
    )

    suspend fun enqueueRename(source: OperationSource, parent: BrowserLocation, newName: String): String = enqueue(
        type = FileOperationType.RENAME,
        sources = listOf(source),
        destination = parent,
        targetName = newName,
        signature = "rename:${source.reference.opaqueId}:$newName",
    )

    suspend fun enqueueBatchRename(sources: List<OperationSource>, parent: BrowserLocation, proposedNames: Map<String, String>): String {
        val timestamp = now()
        val id = UUID.randomUUID().toString()
        val items = sources.mapIndexed { index, source ->
            OperationItem(
                id = "$id:$index",
                source = source,
                requestedName = proposedNames[source.reference.opaqueId] ?: source.name,
                destinationRelativePath = source.name,
            )
        }
        val operation = FileOperation(id, FileOperationType.BATCH_RENAME, FileOperationState.QUEUED, items, parent, createdAtMillis = timestamp)
        val canonicalMapping = sources
            .map { source -> source.reference.opaqueId to (proposedNames[source.reference.opaqueId] ?: source.name) }
            .sortedBy { it.first }
            .joinToString(separator = "|") { (sourceId, proposedName) -> "$sourceId=$proposedName" }
        return submit(operation, "batch:${parent.identity}:$canonicalMapping")
    }

    suspend fun enqueueCreateDirectory(destination: BrowserLocation, name: String): String = enqueueCreate(FileOperationType.CREATE_DIRECTORY, destination, name, null)
    suspend fun enqueueCreateFile(destination: BrowserLocation, name: String, mimeType: String? = null): String = enqueueCreate(FileOperationType.CREATE_FILE, destination, name, mimeType)

    suspend fun pause(id: String) {
        val operation = store.get(id) ?: return
        val state = when (operation.state) {
            FileOperationState.RUNNING, FileOperationState.PREPARING -> FileOperationState.PAUSING
            FileOperationState.QUEUED, FileOperationState.INTERRUPTED -> FileOperationState.PAUSED
            else -> operation.state
        }
        if (state != operation.state) store.save(operation.copy(state = state, updatedAtMillis = now()))
    }

    suspend fun resume(id: String) {
        val operation = store.get(id) ?: return
        if (operation.state == FileOperationState.PAUSED || operation.state == FileOperationState.INTERRUPTED) {
            store.save(operation.copy(state = FileOperationState.QUEUED, failure = null, updatedAtMillis = now()))
            executionHost.requestExecution()
        }
    }

    suspend fun cancel(id: String) {
        val operation = store.get(id) ?: return
        val direct = operation.state in setOf(FileOperationState.QUEUED, FileOperationState.PAUSED, FileOperationState.INTERRUPTED, FileOperationState.WAITING_FOR_USER)
        val state = if (direct) FileOperationState.CANCELLED else if (!operation.state.isTerminal) FileOperationState.CANCELLING else operation.state
        store.save(operation.copy(state = state, completedAtMillis = if (direct) now() else operation.completedAtMillis, pendingCollision = if (direct) null else operation.pendingCollision, updatedAtMillis = now()))
    }

    suspend fun resolveCollision(operationId: String, policy: CollisionPolicy, applyToAll: Boolean) {
        val operation = store.get(operationId) ?: return
        val collision = operation.pendingCollision ?: return
        if (policy !in collision.allowedPolicies) return
        val decisions = operation.collisionDecisions + (collision.id to policy)
        store.save(operation.copy(
            state = FileOperationState.QUEUED,
            pendingCollision = null,
            collisionDecisions = decisions,
            applyToAllCollisionPolicy = if (applyToAll) policy else operation.applyToAllCollisionPolicy,
            updatedAtMillis = now(),
        ))
        executionHost.requestExecution()
    }

    suspend fun retry(id: String): String? {
        val old = store.get(id) ?: return null
        if (old.state != FileOperationState.FAILED && old.state != FileOperationState.COMPLETED_WITH_WARNINGS) return null
        val newId = UUID.randomUUID().toString()
        val timestamp = now()
        val retry = old.copy(
            id = newId,
            state = FileOperationState.QUEUED,
            // A failed copy can deliberately retain a .zzpart reference when cleanup
            // was impossible (for example, removable storage disappeared). Carry that
            // reference into the retry so FileOperationEngine cleans/revalidates it
            // before creating a new temporary destination instead of orphaning it.
            items = old.items.mapIndexed { index, item -> item.copy(id = "$newId:$index", state = OperationItemState.QUEUED, rootItemId = null, processedBytes = 0L, failure = null, resultReference = null, partialOutput = item.partialOutput) },
            createdAtMillis = timestamp,
            startedAtMillis = null,
            completedAtMillis = null,
            updatedAtMillis = timestamp,
            prepared = false,
            processedBytes = 0L,
            processedItems = 0L,
            currentItemName = null,
            failure = null,
            warningCount = 0L,
            pendingCollision = null,
            collisionDecisions = emptyMap(),
            applyToAllCollisionPolicy = null,
            retryOfOperationId = old.id,
        )
        store.enqueue(retry)
        executionHost.requestExecution()
        return newId
    }

    fun source(entry: FileEntry, location: BrowserLocation): OperationSource = OperationSource(
        reference = entry.reference,
        rootReference = location.rootReference,
        storageId = location.storageId,
        name = entry.name,
        isDirectory = entry.isDirectory,
        sizeBytes = entry.sizeBytes,
        modifiedAtMillis = entry.modifiedAtMillis,
        mimeType = entry.mimeType,
        isSymbolicLink = entry.isSymbolicLink,
    )

    private suspend fun enqueueTransfer(type: FileOperationType, sources: List<OperationSource>, destination: BrowserLocation): String = enqueue(
        type = type,
        sources = sources,
        destination = destination,
        signature = "$type:${destination.identity}:${sources.joinToString { it.reference.opaqueId }}",
    )

    private suspend fun enqueueCreate(type: FileOperationType, destination: BrowserLocation, name: String, mimeType: String?): String {
        val timestamp = now(); val id = UUID.randomUUID().toString()
        val operation = FileOperation(id, type, FileOperationState.QUEUED, emptyList(), destination, targetName = name, targetMimeType = mimeType, createdAtMillis = timestamp)
        return submit(operation, "$type:${destination.identity}:$name")
    }

    private suspend fun enqueue(type: FileOperationType, sources: List<OperationSource>, destination: BrowserLocation?, targetName: String? = null, signature: String): String {
        val timestamp = now(); val id = UUID.randomUUID().toString()
        val items = sources.mapIndexed { index, source -> OperationItem(id = "$id:$index", source = source, requestedName = if (type == FileOperationType.RENAME) targetName else null, destinationRelativePath = source.name) }
        val operation = FileOperation(id, type, FileOperationState.QUEUED, items, destination, targetName = targetName, createdAtMillis = timestamp)
        return submit(operation, signature)
    }

    private suspend fun submit(operation: FileOperation, signature: String): String = submitMutex.withLock {
        val timestamp = now()
        val previous = lastSubmission
        val elapsed = previous?.let { timestamp - it.second.first }
        if (
            previous != null &&
            previous.first == signature &&
            elapsed != null &&
            elapsed >= 0L &&
            elapsed < DOUBLE_SUBMIT_WINDOW_MILLIS
        ) {
            return@withLock previous.second.second
        }
        store.enqueue(operation)
        lastSubmission = signature to (timestamp to operation.id)
        executionHost.requestExecution()
        operation.id
    }

    companion object { private const val DOUBLE_SUBMIT_WINDOW_MILLIS = 750L }
}
