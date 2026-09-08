package com.zz.filemanager.core.operation

import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.ScopedFileReference
import com.zz.filemanager.core.storage.StorageAccessException
import com.zz.filemanager.core.storage.StorageCapability
import com.zz.filemanager.core.storage.StorageProviderRegistry
import com.zz.filemanager.core.storage.WritableStorageProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

class FileOperationEngine(
    private val store: OperationStore,
    private val providers: StorageProviderRegistry,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
    private val progressIntervalMillis: Long = DEFAULT_PROGRESS_INTERVAL_MILLIS,
) {
    private val executionMutex = Mutex()
    private val _events = MutableSharedFlow<OperationEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<OperationEvent> = _events.asSharedFlow()

    suspend fun runAvailable() = executionMutex.withLock {
        store.initialize()
        while (true) {
            val operation = store.nextRunnable() ?: break
            runOne(operation)
        }
        store.prune(now())
    }

    private suspend fun runOne(initial: FileOperation) {
        var operation = initial.copy(
            state = FileOperationState.PREPARING,
            startedAtMillis = initial.startedAtMillis ?: now(),
            updatedAtMillis = now(),
            failure = null,
        )
        save(operation)
        _events.tryEmit(OperationEvent.Started(operation.id))

        try {
            operation = when (operation.type) {
                FileOperationType.COPY, FileOperationType.MOVE, FileOperationType.DELETE -> prepareTreeOperation(operation)
                else -> operation.copy(prepared = true)
            }
            if (operation.state != FileOperationState.PREPARING) return
            checkControl(operation.id)

            operation = operation.copy(state = FileOperationState.RUNNING, updatedAtMillis = now())
            save(operation)

            operation = when (operation.type) {
                FileOperationType.COPY -> executeCopyOrMove(operation, move = false)
                FileOperationType.MOVE -> executeCopyOrMove(operation, move = true)
                FileOperationType.DELETE -> executeDelete(operation)
                FileOperationType.RENAME -> executeRename(operation)
                FileOperationType.BATCH_RENAME -> executeBatchRename(operation)
                FileOperationType.CREATE_DIRECTORY -> executeCreate(operation, directory = true)
                FileOperationType.CREATE_FILE -> executeCreate(operation, directory = false)
            }

            if (operation.state == FileOperationState.WAITING_FOR_USER || operation.state == FileOperationState.PAUSED || operation.state == FileOperationState.CANCELLED) return
            if (operation.state == FileOperationState.FAILED) return
            finishFromItems(operation)
        } catch (_: PauseSignal) {
            val current = store.get(operation.id) ?: operation
            val paused = current.copy(
                state = FileOperationState.PAUSED,
                currentItemName = null,
                updatedAtMillis = now(),
                items = current.items.map { if (it.state == OperationItemState.RUNNING) it.copy(state = OperationItemState.QUEUED, processedBytes = 0L, partialOutput = null) else it },
            )
            cleanupRecordedPartials(current)
            save(recalculate(paused))
            _events.tryEmit(OperationEvent.Paused(operation.id))
        } catch (_: CancelSignal) {
            val current = store.get(operation.id) ?: operation
            cleanupRecordedPartials(current)
            val cancelled = recalculate(current.copy(
                state = FileOperationState.CANCELLED,
                completedAtMillis = now(),
                updatedAtMillis = now(),
                currentItemName = null,
                pendingCollision = null,
                items = current.items.map {
                    if (it.state == OperationItemState.COMPLETED || it.state == OperationItemState.SKIPPED || it.state == OperationItemState.FAILED) it
                    else it.copy(state = OperationItemState.CANCELLED, partialOutput = null)
                },
            ))
            save(cancelled)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val failure = mapFailure(error, operation.currentItemName)
            failOperation(store.get(operation.id) ?: operation, failure)
        }
    }

    private suspend fun prepareTreeOperation(input: FileOperation): FileOperation {
        if (input.prepared) return input
        val destination = input.destination
        if ((input.type == FileOperationType.COPY || input.type == FileOperationType.MOVE) && destination == null) {
            return failOperation(input, OperationFailure(OperationFailureCode.DESTINATION_MISSING, "Destination is missing."))
        }
        if (destination != null && (input.type == FileOperationType.COPY || input.type == FileOperationType.MOVE)) {
            val writable = providers.writableProviderFor(destination.providerId)
                ?: return failOperation(input, OperationFailure(OperationFailureCode.DESTINATION_READ_ONLY, "This location is read-only."))
            val capabilities = writable.capabilities(destination)
            if (StorageCapability.WRITE !in capabilities || !destination.writable) {
                return failOperation(input, OperationFailure(OperationFailureCode.DESTINATION_READ_ONLY, "This location is read-only."))
            }
            for (root in input.items) {
                if (root.source.isDirectory && root.source.reference.providerId == destination.providerId && writable.isSameOrDescendant(root.source.scoped, destination)) {
                    return failOperation(input, OperationFailure(OperationFailureCode.DESCENDANT_TARGET, "A folder cannot be copied or moved into itself or one of its descendants.", root.source.name))
                }
            }
        }

        val expanded = mutableListOf<OperationItem>()
        var knownBytes = 0L
        var unknownFileSize = false
        for (root in input.items) {
            checkControl(input.id)
            val rootName = root.requestedName ?: root.source.name
            val rootId = root.id
            val normalizedRoot = root.copy(rootItemId = rootId, destinationRelativePath = rootName)
            expanded += normalizedRoot
            if (!root.source.isDirectory || root.source.isSymbolicLink) {
                if (root.source.sizeBytes == null) unknownFileSize = true else knownBytes = safeAdd(knownBytes, root.source.sizeBytes)
                continue
            }

            data class Pending(val source: OperationSource, val relativePath: String)
            val queue = ArrayDeque<Pending>()
            queue.addLast(Pending(root.source, rootName))
            var visited = 0L
            while (queue.isNotEmpty()) {
                if (visited++ % CONTROL_CHECK_ITEM_INTERVAL == 0L) checkControl(input.id)
                val pending = queue.removeFirst()
                val location = sourceLocation(pending.source)
                val children = providers.providerFor(pending.source.reference.providerId).listChildren(location)
                for (child in children) {
                    val relative = pending.relativePath + "/" + child.name
                    val childSource = child.toOperationSource(pending.source.rootReference, pending.source.storageId)
                    val item = OperationItem(
                        id = "$rootId|${child.reference.opaqueId}",
                        source = childSource,
                        rootItemId = rootId,
                        destinationRelativePath = relative,
                    )
                    expanded += item
                    if (child.isDirectory && !child.isSymbolicLink) queue.addLast(Pending(childSource, relative))
                    else if (child.sizeBytes == null) unknownFileSize = true
                    else knownBytes = safeAdd(knownBytes, child.sizeBytes)
                }
            }
        }

        var prepared = recalculate(input.copy(
            items = expanded,
            prepared = true,
            totalBytes = if (unknownFileSize) null else knownBytes,
            totalItems = expanded.size.toLong(),
            updatedAtMillis = now(),
        ))
        if (destination != null && prepared.totalBytes != null && (prepared.type == FileOperationType.COPY || prepared.type == FileOperationType.MOVE)) {
            val free = providers.writableProviderFor(destination.providerId)?.freeBytes(destination)
            if (free != null && prepared.totalBytes > free) {
                prepared = failOperation(prepared, OperationFailure(OperationFailureCode.INSUFFICIENT_SPACE, "Destination storage does not have enough free space."))
                return prepared
            }
        }
        save(prepared)
        return prepared
    }

    private suspend fun executeCopyOrMove(input: FileOperation, move: Boolean): FileOperation {
        var operation = input
        val destination = operation.destination ?: return failOperation(operation, OperationFailure(OperationFailureCode.DESTINATION_MISSING, "Destination is missing."))
        val destinationProvider = providers.writableProviderFor(destination.providerId)
            ?: return failOperation(operation, OperationFailure(OperationFailureCode.DESTINATION_READ_ONLY, "This location is read-only."))

        val directories = operation.items.filter { it.source.isDirectory && !it.source.isSymbolicLink }.sortedBy { depth(it.destinationRelativePath) }
        for (snapshot in directories) {
            operation = store.get(operation.id) ?: operation
            val item = operation.items.firstOrNull { it.id == snapshot.id } ?: continue
            if (item.state == OperationItemState.COMPLETED || item.state == OperationItemState.SKIPPED || item.state == OperationItemState.FAILED) continue
            checkControl(operation.id)
            operation = ensureDestinationDirectory(operation, item, destinationProvider, markComplete = !move)
            if (operation.state == FileOperationState.WAITING_FOR_USER) return operation
        }

        val files = operation.items.filter { !it.source.isDirectory || it.source.isSymbolicLink }.sortedBy { depth(it.destinationRelativePath) }
        for (snapshot in files) {
            operation = store.get(operation.id) ?: operation
            val item = operation.items.firstOrNull { it.id == snapshot.id } ?: continue
            if (item.state == OperationItemState.COMPLETED || item.state == OperationItemState.SKIPPED || item.state == OperationItemState.FAILED) continue
            checkControl(operation.id)
            operation = copyOneFile(operation, item, destinationProvider, move)
            if (operation.state == FileOperationState.WAITING_FOR_USER) return operation
        }

        if (move) {
            val moveDirectories = operation.items.filter { it.source.isDirectory && !it.source.isSymbolicLink }.sortedByDescending { depth(it.destinationRelativePath) }
            for (snapshot in moveDirectories) {
                operation = store.get(operation.id) ?: operation
                val item = operation.items.firstOrNull { it.id == snapshot.id } ?: continue
                if (item.state == OperationItemState.SKIPPED || item.state == OperationItemState.FAILED || item.state == OperationItemState.CANCELLED) continue
                checkControl(operation.id)
                val sourceProvider = providers.writableProviderFor(item.source.reference.providerId)
                if (sourceProvider == null) {
                    operation = markItemFailed(operation, item, OperationFailure(OperationFailureCode.PERMISSION_DENIED, "Source location cannot be modified.", item.source.name))
                    continue
                }
                try {
                    sourceProvider.delete(item.source.scoped)
                    operation = markItemCompleted(operation, item.copy(resultReference = item.resultReference))
                } catch (_: Throwable) {
                    operation = markItemWarning(operation, item, "Folder was copied, but the original folder could not be removed.")
                }
            }
        }
        return operation
    }

    private suspend fun ensureDestinationDirectory(
        input: FileOperation,
        originalItem: OperationItem,
        destinationProvider: WritableStorageProvider,
        markComplete: Boolean,
    ): FileOperation {
        var operation = input
        var item = originalItem
        val destination = operation.destination
            ?: return markItemFailed(operation, item, OperationFailure(OperationFailureCode.DESTINATION_MISSING, "Destination folder is missing.", item.source.name))
        item.resultReference?.let { existingRef ->
            if (providers.providerFor(existingRef.reference.providerId).exists(existingRef.reference)) {
                return if (markComplete) markItemCompleted(operation, item) else operation
            }
        }
        val parent = destinationParentLocation(operation, item)
            ?: return markItemFailed(operation, item, OperationFailure(OperationFailureCode.DESTINATION_MISSING, "Destination folder is missing.", item.source.name))
        val name = leafName(item.destinationRelativePath)
        val existing = destinationProvider.findChild(parent, name)
        if (existing != null) {
            val collision = collisionFor(operation, item, existing, name)
            val policy = collisionDecision(operation, collision)
            if (policy == null) return waitForCollision(operation, collision)
            when (policy) {
                CollisionPolicy.SKIP -> return skipSubtree(operation, item)
                CollisionPolicy.KEEP_BOTH -> {
                    val candidate = findKeepBothName(destinationProvider, parent, name, true)
                    operation = rewriteSubtreeDestination(operation, item, candidate)
                    item = operation.items.first { it.id == item.id }
                    return ensureDestinationDirectory(operation, item, destinationProvider, markComplete)
                }
                CollisionPolicy.MERGE -> {
                    if (!existing.isDirectory) return waitForCollision(operation, collision.copy(allowedPolicies = setOf(CollisionPolicy.SKIP, CollisionPolicy.KEEP_BOTH)))
                    val updated = item.copy(resultReference = ScopedFileReference(existing.reference, destination.rootReference, destination.storageId))
                    operation = replaceItem(operation, updated)
                    return if (markComplete) markItemCompleted(operation, updated) else saveAndReturn(operation)
                }
                CollisionPolicy.REPLACE -> return waitForCollision(operation, collision.copy(allowedPolicies = setOf(CollisionPolicy.SKIP, CollisionPolicy.KEEP_BOTH, CollisionPolicy.MERGE)))
            }
        }
        return try {
            val created = destinationProvider.createDirectory(parent, leafName(item.destinationRelativePath))
            val updated = item.copy(resultReference = ScopedFileReference(created.reference, destination.rootReference, destination.storageId))
            operation = replaceItem(operation, updated)
            if (markComplete) markItemCompleted(operation, updated) else saveAndReturn(operation)
        } catch (error: Throwable) {
            markItemFailed(operation, item, mapFailure(error, item.source.name))
        }
    }

    private suspend fun copyOneFile(
        input: FileOperation,
        originalItem: OperationItem,
        destinationProvider: WritableStorageProvider,
        move: Boolean,
    ): FileOperation {
        var operation = input
        var item = originalItem
        val destination = operation.destination
            ?: return markItemFailed(operation, item, OperationFailure(OperationFailureCode.DESTINATION_MISSING, "Destination folder is missing.", item.source.name))
        if (item.source.isSymbolicLink) {
            return markItemFailed(operation, item, OperationFailure(OperationFailureCode.SYMBOLIC_LINK_UNSUPPORTED, "Symbolic links are not followed during file operations.", item.source.name))
        }
        val sourceProvider = providers.providerFor(item.source.reference.providerId)
        val metadata = sourceProvider.getMetadata(item.source.reference)
            ?: return markItemFailed(operation, item, OperationFailure(OperationFailureCode.SOURCE_MISSING, "Source item no longer exists.", item.source.name))
        if (item.source.sizeBytes != null && metadata.sizeBytes != null && item.source.sizeBytes != metadata.sizeBytes) {
            return markItemFailed(operation, item, OperationFailure(OperationFailureCode.SOURCE_CHANGED, "Source item changed since the operation was created.", item.source.name))
        }
        if (item.source.modifiedAtMillis != null && metadata.modifiedAtMillis != null && item.source.modifiedAtMillis != metadata.modifiedAtMillis) {
            return markItemFailed(operation, item, OperationFailure(OperationFailureCode.SOURCE_CHANGED, "Source item changed since the operation was created.", item.source.name))
        }

        val parent = destinationParentLocation(operation, item)
            ?: return markItemFailed(operation, item, OperationFailure(OperationFailureCode.DESTINATION_MISSING, "Destination folder is missing.", item.source.name))
        var finalName = leafName(item.destinationRelativePath)
        var existing = destinationProvider.findChild(parent, finalName)
        if (existing != null) {
            val collision = collisionFor(operation, item, existing, finalName)
            val policy = collisionDecision(operation, collision)
            if (policy == null) return waitForCollision(operation, collision)
            when (policy) {
                CollisionPolicy.SKIP -> return markItemSkipped(operation, item)
                CollisionPolicy.KEEP_BOTH -> {
                    finalName = findKeepBothName(destinationProvider, parent, finalName, false)
                    item = item.copy(destinationRelativePath = replaceLeaf(item.destinationRelativePath, finalName))
                    operation = replaceItem(operation, item)
                    existing = null
                }
                CollisionPolicy.REPLACE -> {
                    if (existing.isDirectory) return waitForCollision(operation, collision.copy(allowedPolicies = setOf(CollisionPolicy.SKIP, CollisionPolicy.KEEP_BOTH)))
                    try { destinationProvider.delete(ScopedFileReference(existing.reference, destination.rootReference, destination.storageId)) }
                    catch (error: Throwable) { return markItemFailed(operation, item, mapFailure(error, item.source.name)) }
                    existing = null
                }
                CollisionPolicy.MERGE -> return waitForCollision(operation, collision.copy(allowedPolicies = setOf(CollisionPolicy.SKIP, CollisionPolicy.KEEP_BOTH, CollisionPolicy.REPLACE)))
            }
        }

        if (move && item.source.reference.providerId == destination.providerId && parent.identity == destination.identity && depth(item.destinationRelativePath) == 1) {
            val writableSource = providers.writableProviderFor(item.source.reference.providerId)
            if (writableSource != null && existing == null) {
                val native = runCatching { writableSource.moveNative(item.source.scoped, parent, finalName) }.getOrNull()
                if (native != null) {
                    return markItemCompleted(operation, item.copy(resultReference = ScopedFileReference(native.reference, destination.rootReference, destination.storageId), processedBytes = item.source.sizeBytes ?: 0L))
                }
            }
        }

        val capabilities = destinationProvider.capabilities(parent)
        val canFinalizeByRename = StorageCapability.RENAME in capabilities
        val outputName = if (canFinalizeByRename) uniqueTemporaryName(destinationProvider, parent, operation.id, item.id) else finalName
        val outputEntry = try { destinationProvider.createFile(parent, outputName, item.source.mimeType) }
        catch (error: Throwable) { return markItemFailed(operation, item, mapFailure(error, item.source.name)) }
        val outputRef = ScopedFileReference(outputEntry.reference, destination.rootReference, destination.storageId)
        item = item.copy(state = OperationItemState.RUNNING, partialOutput = outputRef, processedBytes = 0L)
        operation = replaceItem(operation.copy(currentItemName = item.source.name), item)
        save(recalculate(operation))

        try {
            var written = 0L
            var lastPersistAt = now()
            sourceProvider.openInputStream(item.source.reference).use { inputStream ->
                destinationProvider.openOutputStream(outputRef, truncate = true).use { outputStream ->
                    val buffer = ByteArray(bufferSize)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = inputStream.read(buffer)
                        if (read < 0) break
                        outputStream.write(buffer, 0, read)
                        written = safeAdd(written, read.toLong())
                        val tick = now()
                        if (tick - lastPersistAt >= progressIntervalMillis) {
                            checkControl(operation.id)
                            item = item.copy(processedBytes = written)
                            operation = recalculate(replaceItem(operation, item).copy(currentItemName = item.source.name, updatedAtMillis = tick))
                            save(operation)
                            _events.tryEmit(OperationEvent.ProgressUpdated(operation.id, operation.processedBytes, operation.totalBytes))
                            lastPersistAt = tick
                        }
                    }
                    outputStream.flush()
                }
            }
            checkControl(operation.id)
            if (item.source.sizeBytes != null && written != item.source.sizeBytes) throw IOException("Copied byte count does not match source size")

            val finalEntry = if (canFinalizeByRename) {
                val raced = destinationProvider.findChild(parent, finalName)
                if (raced != null) {
                    runCatching { destinationProvider.delete(outputRef) }
                    item = item.copy(state = OperationItemState.QUEUED, partialOutput = null, processedBytes = 0L)
                    operation = replaceItem(operation, item)
                    return waitForCollision(operation, collisionFor(operation, item, raced, finalName))
                }
                destinationProvider.rename(outputRef, finalName)
            } else {
                outputEntry
            }
            item = item.copy(
                processedBytes = written,
                state = OperationItemState.COMPLETED,
                resultReference = ScopedFileReference(finalEntry.reference, destination.rootReference, destination.storageId),
                partialOutput = null,
            )
            operation = replaceItem(operation, item)

            if (move) {
                val sourceWritable = providers.writableProviderFor(item.source.reference.providerId)
                if (sourceWritable == null) return markItemWarning(operation, item, "Item was copied, but the original could not be removed.")
                try { sourceWritable.delete(item.source.scoped) }
                catch (_: Throwable) { return markItemWarning(operation, item, "Item was copied, but the original could not be removed.") }
            }
            return markItemCompleted(operation, item)
        } catch (pause: PauseSignal) {
            runCatching { destinationProvider.delete(outputRef) }
            item = item.copy(state = OperationItemState.QUEUED, partialOutput = null, processedBytes = 0L)
            save(recalculate(replaceItem(operation, item)))
            throw pause
        } catch (cancel: CancelSignal) {
            runCatching { destinationProvider.delete(outputRef) }
            item = item.copy(state = OperationItemState.CANCELLED, partialOutput = null)
            save(recalculate(replaceItem(operation, item)))
            throw cancel
        } catch (error: Throwable) {
            runCatching { destinationProvider.delete(outputRef) }
            return markItemFailed(operation, item.copy(partialOutput = null), mapFailure(error, item.source.name))
        }
    }

    private suspend fun executeDelete(input: FileOperation): FileOperation {
        var operation = input
        val files = operation.items.filter { !it.source.isDirectory || it.source.isSymbolicLink }
        val directories = operation.items.filter { it.source.isDirectory && !it.source.isSymbolicLink }.sortedByDescending { depth(it.destinationRelativePath) }
        for (snapshot in files + directories) {
            operation = store.get(operation.id) ?: operation
            val item = operation.items.firstOrNull { it.id == snapshot.id } ?: continue
            if (item.state == OperationItemState.COMPLETED || item.state == OperationItemState.FAILED || item.state == OperationItemState.SKIPPED) continue
            checkControl(operation.id)
            val writable = providers.writableProviderFor(item.source.reference.providerId)
            if (writable == null) { operation = markItemFailed(operation, item, OperationFailure(OperationFailureCode.PERMISSION_DENIED, "This item cannot be deleted.", item.source.name)); continue }
            operation = try { writable.delete(item.source.scoped); markItemCompleted(operation, item) }
            catch (error: Throwable) { markItemFailed(operation, item, mapFailure(error, item.source.name)) }
        }
        return operation
    }

    private suspend fun executeRename(input: FileOperation): FileOperation {
        var operation = input
        val item = operation.items.firstOrNull() ?: return failOperation(operation, OperationFailure(OperationFailureCode.SOURCE_MISSING, "No item was selected."))
        val newName = operation.targetName ?: item.requestedName ?: return failOperation(operation, OperationFailure(OperationFailureCode.INVALID_NAME, "A new name is required."))
        FileNameRules.validateLeafName(newName)?.let { return failOperation(operation, it.copy(itemName = item.source.name)) }
        val parent = operation.destination ?: return failOperation(operation, OperationFailure(OperationFailureCode.DESTINATION_MISSING, "Parent folder is missing."))
        val writable = providers.writableProviderFor(item.source.reference.providerId) ?: return failOperation(operation, OperationFailure(OperationFailureCode.PERMISSION_DENIED, "This item cannot be renamed."))
        val existing = writable.findChild(parent, newName)
        if (existing != null && !sameReference(existing, item.source)) {
            val collision = collisionFor(operation, item, existing, newName)
            val policy = collisionDecision(operation, collision)
            if (policy == null) return waitForCollision(operation, collision.copy(allowedPolicies = setOf(CollisionPolicy.SKIP, CollisionPolicy.KEEP_BOTH)))
            if (policy == CollisionPolicy.SKIP) return markItemSkipped(operation, item)
            if (policy == CollisionPolicy.KEEP_BOTH) return executeRename(operation.copy(targetName = findKeepBothName(writable, parent, newName, item.source.isDirectory)))
        }
        return try { val renamed = writable.rename(item.source.scoped, newName); markItemCompleted(operation, item.copy(resultReference = ScopedFileReference(renamed.reference, item.source.rootReference, item.source.storageId))) }
        catch (error: Throwable) { markItemFailed(operation, item, mapFailure(error, item.source.name)) }
    }

    private suspend fun executeBatchRename(input: FileOperation): FileOperation {
        var operation = input
        val parent = operation.destination ?: return failOperation(operation, OperationFailure(OperationFailureCode.DESTINATION_MISSING, "Parent folder is missing."))
        val selectedIds = operation.items.map { it.source.reference.opaqueId }.toSet()
        val proposedKeys = mutableSetOf<String>()
        for (item in operation.items) {
            val target = item.requestedName ?: return failOperation(operation, OperationFailure(OperationFailureCode.INVALID_NAME, "Every item needs a target name.", item.source.name))
            FileNameRules.validateLeafName(target)?.let { return failOperation(operation, it.copy(itemName = item.source.name)) }
            if (!proposedKeys.add(FileNameRules.normalizedCollisionKey(target))) return failOperation(operation, OperationFailure(OperationFailureCode.NAME_CONFLICT, "Batch rename produces duplicate names."))
            val writable = providers.writableProviderFor(item.source.reference.providerId) ?: return failOperation(operation, OperationFailure(OperationFailureCode.PERMISSION_DENIED, "An item cannot be renamed.", item.source.name))
            val existing = writable.findChild(parent, target)
            if (existing != null && existing.reference.opaqueId !in selectedIds) return failOperation(operation, OperationFailure(OperationFailureCode.NAME_CONFLICT, "A target name already exists.", target))
        }

        val tempRenamed = mutableListOf<Pair<OperationItem, String>>()
        try {
            for (snapshot in operation.items) {
                checkControl(operation.id)
                var item = (store.get(operation.id) ?: operation).items.first { it.id == snapshot.id }
                if (item.state == OperationItemState.COMPLETED) continue
                val writable = providers.writableProviderFor(item.source.reference.providerId) ?: throw StorageAccessException.ReadOnly()
                if (item.resultReference == null || !providers.providerFor(item.resultReference.reference.providerId).exists(item.resultReference.reference)) {
                    val tempName = uniqueRenameTemp(writable, parent, operation.id, item.id)
                    val temp = writable.rename(item.source.scoped, tempName)
                    item = item.copy(resultReference = ScopedFileReference(temp.reference, item.source.rootReference, item.source.storageId))
                    operation = replaceItem(operation, item)
                    save(operation)
                    tempRenamed += item to item.source.name
                }
            }
            for (snapshot in operation.items) {
                checkControl(operation.id)
                var item = (store.get(operation.id) ?: operation).items.first { it.id == snapshot.id }
                if (item.state == OperationItemState.COMPLETED) continue
                val writable = providers.writableProviderFor(item.source.reference.providerId) ?: throw StorageAccessException.ReadOnly()
                val tempRef = item.resultReference ?: throw IOException("Missing batch rename temporary reference")
                val finalName = item.requestedName ?: throw IOException("Missing batch rename target")
                val finalEntry = writable.rename(tempRef, finalName)
                item = item.copy(resultReference = ScopedFileReference(finalEntry.reference, item.source.rootReference, item.source.storageId))
                operation = markItemCompleted(operation, item)
            }
            return operation
        } catch (error: Throwable) {
            for ((item, originalName) in tempRenamed.asReversed()) {
                val writable = providers.writableProviderFor(item.source.reference.providerId) ?: continue
                item.resultReference?.let { runCatching { writable.rename(it, originalName) } }
            }
            return failOperation(operation, mapFailure(error, operation.currentItemName))
        }
    }

    private suspend fun executeCreate(input: FileOperation, directory: Boolean): FileOperation {
        val destination = input.destination ?: return failOperation(input, OperationFailure(OperationFailureCode.DESTINATION_MISSING, "Destination is missing."))
        val name = input.targetName ?: return failOperation(input, OperationFailure(OperationFailureCode.INVALID_NAME, "A name is required."))
        FileNameRules.validateLeafName(name)?.let { return failOperation(input, it) }
        val writable = providers.writableProviderFor(destination.providerId) ?: return failOperation(input, OperationFailure(OperationFailureCode.DESTINATION_READ_ONLY, "This location is read-only."))
        val existing = writable.findChild(destination, name)
        if (existing != null) return failOperation(input, OperationFailure(OperationFailureCode.NAME_CONFLICT, "An item with this name already exists.", name))
        return try {
            val created = if (directory) writable.createDirectory(destination, name) else writable.createFile(destination, name, input.targetMimeType)
            val synthetic = input.items.firstOrNull() ?: OperationItem("create:${input.id}", created.toOperationSource(destination.rootReference, destination.storageId))
            markItemCompleted(input.copy(items = listOf(synthetic), totalItems = 1L), synthetic.copy(resultReference = ScopedFileReference(created.reference, destination.rootReference, destination.storageId)))
        } catch (error: Throwable) { failOperation(input, mapFailure(error, name)) }
    }

    private suspend fun destinationParentLocation(operation: FileOperation, item: OperationItem): BrowserLocation? {
        val root = operation.destination ?: return null
        val parentPath = parentPath(item.destinationRelativePath)
        if (parentPath.isEmpty()) return root
        val parentItem = operation.items.firstOrNull { it.source.isDirectory && it.destinationRelativePath == parentPath } ?: return null
        val ref = parentItem.resultReference ?: return null
        if (!providers.providerFor(ref.reference.providerId).exists(ref.reference)) return null
        return BrowserLocation(providerId = ref.reference.providerId, id = ref.reference.opaqueId, displayName = leafName(parentPath), reference = ref.reference.uri ?: ref.reference.path ?: ref.reference.opaqueId, rootReference = root.rootReference, storageId = root.storageId, readable = true, writable = true)
    }

    private fun collisionFor(operation: FileOperation, item: OperationItem, existing: FileEntry, targetName: String): PendingCollision {
        val same = sameReference(existing, item.source)
        val kind = when {
            same -> CollisionKind.SAME_RESOURCE
            item.source.isDirectory && existing.isDirectory -> CollisionKind.DIRECTORY_TO_DIRECTORY
            item.source.isDirectory && !existing.isDirectory -> CollisionKind.DIRECTORY_TO_FILE
            !item.source.isDirectory && existing.isDirectory -> CollisionKind.FILE_TO_DIRECTORY
            else -> CollisionKind.FILE_TO_FILE
        }
        val allowed = when (kind) {
            CollisionKind.FILE_TO_FILE -> setOf(CollisionPolicy.REPLACE, CollisionPolicy.SKIP, CollisionPolicy.KEEP_BOTH)
            CollisionKind.DIRECTORY_TO_DIRECTORY -> setOf(CollisionPolicy.MERGE, CollisionPolicy.SKIP, CollisionPolicy.KEEP_BOTH)
            CollisionKind.SAME_RESOURCE -> setOf(CollisionPolicy.SKIP, CollisionPolicy.KEEP_BOTH)
            CollisionKind.FILE_TO_DIRECTORY, CollisionKind.DIRECTORY_TO_FILE -> setOf(CollisionPolicy.SKIP, CollisionPolicy.KEEP_BOTH)
        }
        return PendingCollision(
            id = "${operation.id}|${item.id}|${parentPath(item.destinationRelativePath)}|$targetName",
            itemId = item.id,
            sourceName = item.source.name,
            destinationName = targetName,
            kind = kind,
            allowedPolicies = allowed,
        )
    }

    private fun collisionDecision(operation: FileOperation, collision: PendingCollision): CollisionPolicy? {
        val explicit = operation.collisionDecisions[collision.id]
        if (explicit != null && explicit in collision.allowedPolicies) return explicit
        val all = operation.applyToAllCollisionPolicy
        return all?.takeIf { it in collision.allowedPolicies }
    }

    private suspend fun waitForCollision(input: FileOperation, collision: PendingCollision): FileOperation {
        val operation = input.copy(state = FileOperationState.WAITING_FOR_USER, pendingCollision = collision, currentItemName = collision.sourceName, updatedAtMillis = now())
        save(operation)
        _events.tryEmit(OperationEvent.CollisionRequired(operation.id, collision))
        return operation
    }

    private suspend fun findKeepBothName(provider: WritableStorageProvider, parent: BrowserLocation, original: String, directory: Boolean): String {
        var index = 1
        while (true) {
            val candidate = FileNameRules.keepBothCandidate(original, index, directory)
            if (provider.findChild(parent, candidate) == null) return candidate
            index++
        }
    }

    private suspend fun uniqueTemporaryName(provider: WritableStorageProvider, parent: BrowserLocation, operationId: String, itemId: String): String {
        val token = abs(itemId.hashCode().toLong()).toString()
        var index = 0
        while (true) {
            val name = ".zzpart-${operationId.take(8)}-$token" + if (index == 0) "" else "-$index"
            if (provider.findChild(parent, name) == null) return name
            index++
        }
    }

    private suspend fun uniqueRenameTemp(provider: WritableStorageProvider, parent: BrowserLocation, operationId: String, itemId: String): String {
        val token = abs(itemId.hashCode().toLong()).toString()
        var index = 0
        while (true) {
            val name = ".zzrename-${operationId.take(8)}-$token" + if (index == 0) "" else "-$index"
            if (provider.findChild(parent, name) == null) return name
            index++
        }
    }

    private suspend fun skipSubtree(input: FileOperation, directoryItem: OperationItem): FileOperation {
        val prefix = directoryItem.destinationRelativePath
        val root = directoryItem.rootItemId
        val operation = recalculate(input.copy(items = input.items.map { item ->
            if (item.rootItemId == root && (item.destinationRelativePath == prefix || item.destinationRelativePath.startsWith("$prefix/"))) item.copy(state = OperationItemState.SKIPPED)
            else item
        }, updatedAtMillis = now()))
        save(operation)
        return operation
    }

    private fun rewriteSubtreeDestination(input: FileOperation, directoryItem: OperationItem, newLeaf: String): FileOperation {
        val oldPrefix = directoryItem.destinationRelativePath
        val parent = parentPath(oldPrefix)
        val newPrefix = if (parent.isEmpty()) newLeaf else "$parent/$newLeaf"
        val root = directoryItem.rootItemId
        return input.copy(items = input.items.map { item ->
            if (item.rootItemId == root && (item.destinationRelativePath == oldPrefix || item.destinationRelativePath.startsWith("$oldPrefix/"))) {
                item.copy(destinationRelativePath = newPrefix + item.destinationRelativePath.removePrefix(oldPrefix))
            } else item
        })
    }

    private suspend fun cleanupRecordedPartials(operation: FileOperation) {
        operation.items.mapNotNull { it.partialOutput }.forEach { partial ->
            providers.writableProviderFor(partial.reference.providerId)?.let { provider -> runCatching { provider.delete(partial) } }
        }
    }

    private suspend fun checkControl(operationId: String) {
        when (store.get(operationId)?.state) {
            FileOperationState.CANCELLING, FileOperationState.CANCELLED -> throw CancelSignal()
            FileOperationState.PAUSING, FileOperationState.PAUSED -> throw PauseSignal()
            else -> Unit
        }
    }

    private suspend fun markItemCompleted(input: FileOperation, item: OperationItem): FileOperation {
        val completed = item.copy(state = OperationItemState.COMPLETED, failure = null, partialOutput = null)
        val operation = recalculate(replaceItem(input, completed).copy(currentItemName = item.source.name, updatedAtMillis = now()))
        save(operation)
        return operation
    }

    private suspend fun markItemSkipped(input: FileOperation, item: OperationItem): FileOperation {
        val operation = recalculate(replaceItem(input, item.copy(state = OperationItemState.SKIPPED, partialOutput = null)).copy(updatedAtMillis = now()))
        save(operation); return operation
    }

    private suspend fun markItemFailed(input: FileOperation, item: OperationItem, failure: OperationFailure): FileOperation {
        val operation = recalculate(replaceItem(input, item.copy(state = OperationItemState.FAILED, failure = failure, partialOutput = null)).copy(updatedAtMillis = now()))
        save(operation); return operation
    }

    private suspend fun markItemWarning(input: FileOperation, item: OperationItem, message: String): FileOperation {
        val warning = OperationFailure(OperationFailureCode.IO_ERROR, message, item.source.name)
        val operation = recalculate(replaceItem(input, item.copy(state = OperationItemState.COMPLETED, failure = warning, partialOutput = null)).copy(warningCount = input.warningCount + 1L, updatedAtMillis = now()))
        save(operation); return operation
    }

    private suspend fun finishFromItems(input: FileOperation) {
        val failed = input.items.count { it.state == OperationItemState.FAILED }
        val warnings = input.warningCount + input.items.count { it.state == OperationItemState.SKIPPED }.toLong()
        val state = when {
            failed == input.items.size && input.items.isNotEmpty() -> FileOperationState.FAILED
            failed > 0 || warnings > 0L -> FileOperationState.COMPLETED_WITH_WARNINGS
            else -> FileOperationState.COMPLETED
        }
        val final = recalculate(input.copy(state = state, completedAtMillis = now(), updatedAtMillis = now(), currentItemName = null, pendingCollision = null))
        save(final)
        if (state == FileOperationState.FAILED) _events.tryEmit(OperationEvent.Failed(final.id, final.items.firstNotNullOfOrNull { it.failure } ?: OperationFailure(OperationFailureCode.UNKNOWN, "Operation failed.")))
        else _events.tryEmit(OperationEvent.Completed(final.id, state == FileOperationState.COMPLETED_WITH_WARNINGS))
    }

    private suspend fun failOperation(input: FileOperation, failure: OperationFailure): FileOperation {
        val failed = input.copy(state = FileOperationState.FAILED, failure = failure, completedAtMillis = now(), updatedAtMillis = now(), currentItemName = null)
        save(failed); _events.tryEmit(OperationEvent.Failed(failed.id, failure)); return failed
    }

    private suspend fun saveAndReturn(operation: FileOperation): FileOperation { val updated = recalculate(operation.copy(updatedAtMillis = now())); save(updated); return updated }
    private suspend fun save(operation: FileOperation) { store.save(operation) }
    private fun replaceItem(operation: FileOperation, updated: OperationItem): FileOperation = operation.copy(items = operation.items.map { if (it.id == updated.id) updated else it })

    private fun recalculate(operation: FileOperation): FileOperation {
        val processedBytes = operation.items.fold(0L) { total, item -> safeAdd(total, item.processedBytes) }
        val processedItems = operation.items.count { it.state == OperationItemState.COMPLETED || it.state == OperationItemState.SKIPPED || it.state == OperationItemState.FAILED || it.state == OperationItemState.CANCELLED }.toLong()
        return operation.copy(processedBytes = processedBytes, processedItems = processedItems)
    }

    private fun sourceLocation(source: OperationSource) = BrowserLocation(providerId = source.reference.providerId, id = source.reference.opaqueId, displayName = source.name, reference = source.reference.uri ?: source.reference.path ?: source.reference.opaqueId, rootReference = source.rootReference, storageId = source.storageId, readable = true, writable = true)
    private fun FileEntry.toOperationSource(rootReference: String, storageId: String) = OperationSource(reference, rootReference, storageId, name, isDirectory, sizeBytes, modifiedAtMillis, mimeType, isSymbolicLink)
    private fun sameReference(entry: FileEntry, source: OperationSource): Boolean = entry.reference.providerId == source.reference.providerId && (entry.reference.opaqueId == source.reference.opaqueId || (entry.reference.uri != null && entry.reference.uri == source.reference.uri) || (entry.reference.path != null && entry.reference.path == source.reference.path))
    private fun depth(path: String): Int = path.count { it == '/' } + if (path.isBlank()) 0 else 1
    private fun leafName(path: String): String = path.substringAfterLast('/')
    private fun parentPath(path: String): String = path.substringBeforeLast('/', "")
    private fun replaceLeaf(path: String, newLeaf: String): String = parentPath(path).let { if (it.isEmpty()) newLeaf else "$it/$newLeaf" }
    private fun safeAdd(left: Long, right: Long): Long = if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun mapFailure(error: Throwable, itemName: String?): OperationFailure = when (error) {
        is StorageAccessException.PermissionRequired -> OperationFailure(OperationFailureCode.PERMISSION_DENIED, "Permission was denied.", itemName)
        is StorageAccessException.ReadOnly -> OperationFailure(OperationFailureCode.DESTINATION_READ_ONLY, "This location is read-only.", itemName)
        is StorageAccessException.Unavailable -> OperationFailure(OperationFailureCode.PROVIDER_UNAVAILABLE, "Storage or provider is unavailable.", itemName)
        is StorageAccessException.Io, is IOException -> OperationFailure(OperationFailureCode.IO_ERROR, "The file operation could not be completed.", itemName)
        else -> OperationFailure(OperationFailureCode.UNKNOWN, "The file operation failed.", itemName)
    }

    private class PauseSignal : Exception()
    private class CancelSignal : Exception()

    companion object {
        const val DEFAULT_BUFFER_SIZE = 256 * 1024
        const val DEFAULT_PROGRESS_INTERVAL_MILLIS = 500L
        const val CONTROL_CHECK_ITEM_INTERVAL = 64L
    }
}
