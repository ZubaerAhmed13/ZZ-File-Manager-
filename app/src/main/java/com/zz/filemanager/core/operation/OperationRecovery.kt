package com.zz.filemanager.core.operation

suspend fun OperationStore.markHostExecutionInterrupted(nowMillis: Long = System.currentTimeMillis()) {
    initialize()
    operations.value
        .filter { it.state in setOf(FileOperationState.RUNNING, FileOperationState.PREPARING, FileOperationState.PAUSING, FileOperationState.CANCELLING) }
        .forEach { operation ->
            val wasCancelling = operation.state == FileOperationState.CANCELLING
            save(
                operation.copy(
                    state = if (wasCancelling) FileOperationState.CANCELLED else FileOperationState.INTERRUPTED,
                    completedAtMillis = if (wasCancelling) nowMillis else operation.completedAtMillis,
                    updatedAtMillis = nowMillis,
                    currentItemName = null,
                    pendingCollision = null,
                    items = operation.items.map { item ->
                        if (item.state == OperationItemState.RUNNING) {
                            if (wasCancelling) item.copy(state = OperationItemState.CANCELLED, partialOutput = null)
                            else item.copy(state = OperationItemState.QUEUED, processedBytes = 0L, partialOutput = null)
                        } else item
                    },
                ),
            )
        }
}
