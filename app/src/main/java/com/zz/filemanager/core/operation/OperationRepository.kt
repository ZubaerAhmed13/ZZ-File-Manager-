package com.zz.filemanager.core.operation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface OperationStore {
    val operations: StateFlow<List<FileOperation>>
    suspend fun initialize()
    suspend fun enqueue(operation: FileOperation)
    suspend fun get(id: String): FileOperation?
    suspend fun save(operation: FileOperation)
    suspend fun nextRunnable(): FileOperation?
    suspend fun prune(nowMillis: Long)
}

class OperationRepository(
    private val journal: OperationJournal,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : OperationStore {
    private val mutex = Mutex()
    private val _operations = MutableStateFlow<List<FileOperation>>(emptyList())
    override val operations: StateFlow<List<FileOperation>> = _operations.asStateFlow()
    private var initialized = false

    override suspend fun initialize() = mutex.withLock {
        if (initialized) return
        val reconciled = withContext(ioDispatcher) { journal.readAll().map(::reconcileAfterProcessDeath) }
        withContext(ioDispatcher) { reconciled.forEach(journal::upsert) }
        _operations.value = reconciled.sortedWith(queueComparator)
        initialized = true
    }

    override suspend fun enqueue(operation: FileOperation) = mutex.withLock {
        ensureInitializedLocked()
        require(_operations.value.none { it.id == operation.id }) { "Duplicate operation id ${operation.id}" }
        withContext(ioDispatcher) { journal.upsert(operation) }
        _operations.value = (_operations.value + operation).sortedWith(queueComparator)
    }

    override suspend fun get(id: String): FileOperation? = mutex.withLock {
        ensureInitializedLocked()
        _operations.value.firstOrNull { it.id == id }
    }

    override suspend fun save(operation: FileOperation) = mutex.withLock {
        ensureInitializedLocked()
        withContext(ioDispatcher) { journal.upsert(operation) }
        val current = _operations.value
        val index = current.indexOfFirst { it.id == operation.id }
        _operations.value = if (index >= 0) current.toMutableList().also { it[index] = operation }
        else (current + operation).sortedWith(queueComparator)
    }

    override suspend fun nextRunnable(): FileOperation? = mutex.withLock {
        ensureInitializedLocked()
        _operations.value.firstOrNull { it.state == FileOperationState.QUEUED }
    }

    override suspend fun prune(nowMillis: Long) = mutex.withLock {
        ensureInitializedLocked()
        withContext(ioDispatcher) { journal.prune(nowMillis) }
        _operations.value = withContext(ioDispatcher) { journal.readAll() }.sortedWith(queueComparator)
    }

    private suspend fun ensureInitializedLocked() {
        if (initialized) return
        val reconciled = withContext(ioDispatcher) { journal.readAll().map(::reconcileAfterProcessDeath) }
        withContext(ioDispatcher) { reconciled.forEach(journal::upsert) }
        _operations.value = reconciled.sortedWith(queueComparator)
        initialized = true
    }

    internal fun reconcileAfterProcessDeath(operation: FileOperation): FileOperation {
        val unsafeState = operation.state in setOf(
            FileOperationState.PREPARING,
            FileOperationState.RUNNING,
            FileOperationState.PAUSING,
            FileOperationState.CANCELLING,
        )
        if (!unsafeState) return operation
        return operation.copy(
            state = FileOperationState.INTERRUPTED,
            items = operation.items.map { item ->
                if (item.state == OperationItemState.RUNNING) item.copy(state = OperationItemState.QUEUED, processedBytes = 0L)
                else item
            },
            currentItemName = null,
            pendingCollision = null,
        )
    }

    companion object {
        private val queueComparator = compareBy<FileOperation> { it.createdAtMillis }.thenBy { it.id }
    }
}
