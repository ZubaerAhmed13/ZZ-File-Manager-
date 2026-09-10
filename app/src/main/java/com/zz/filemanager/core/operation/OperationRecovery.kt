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
                            val hasResumeProof =
                                item.partialOutput != null &&
                                    item.resumeOffset > 0L &&
                                    item.processedBytes == item.resumeOffset &&
                                    !item.resumeSourceIdentity.isNullOrBlank() &&
                                    !item.resumeStagedIdentity.isNullOrBlank()
                            if (wasCancelling) {
                                item.copy(state = OperationItemState.CANCELLED)
                            } else if (hasResumeProof) {
                                item.copy(state = OperationItemState.QUEUED)
                            } else {
                                item.copy(
                                    state = OperationItemState.QUEUED,
                                    processedBytes = 0L,
                                    resumeSourceIdentity = null,
                                    resumeStagedIdentity = null,
                                    resumeOffset = 0L,
                                )
                            }
                        } else item
                    },
                ),
            )
        }
}

/**
 * Implements the Step 5 auto-resume preference. Only interrupted COPY/MOVE transfers are made
 * runnable automatically; destructive metadata operations are never silently replayed. Existing
 * staged-object/source identity proofs are preserved and FileOperationEngine revalidates them
 * before any non-zero-offset resume.
 */
suspend fun OperationStore.autoResumeInterruptedTransfers(
    enabled: Boolean,
    nowMillis: Long = System.currentTimeMillis(),
): Int {
    initialize()
    if (!enabled) return 0
    var resumed = 0
    operations.value
        .filter {
            it.state == FileOperationState.INTERRUPTED &&
                it.type in setOf(FileOperationType.COPY, FileOperationType.MOVE)
        }
        .forEach { operation ->
            save(
                operation.copy(
                    state = FileOperationState.QUEUED,
                    failure = if (operation.batchRenameRollbackRequired) operation.failure else null,
                    updatedAtMillis = nowMillis,
                ),
            )
            resumed += 1
        }
    return resumed
}
