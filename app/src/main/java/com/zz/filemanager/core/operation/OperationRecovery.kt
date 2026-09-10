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
                            // The recovery layer deliberately never assumes that a staged object is
                            // reusable merely because a byte count was journaled. A checkpoint is
                            // carried across process death only when it has all three persisted proof
                            // elements. The engine revalidates those values against the live providers
                            // before opening either stream at a non-zero offset.
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
