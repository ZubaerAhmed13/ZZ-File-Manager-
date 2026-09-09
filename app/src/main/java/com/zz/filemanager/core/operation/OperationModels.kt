package com.zz.filemanager.core.operation

import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.model.ScopedFileReference

enum class FileOperationType {
    COPY,
    MOVE,
    DELETE,
    RENAME,
    BATCH_RENAME,
    CREATE_DIRECTORY,
    CREATE_FILE,
}

enum class FileOperationState {
    QUEUED,
    PREPARING,
    WAITING_FOR_USER,
    RUNNING,
    PAUSING,
    PAUSED,
    CANCELLING,
    CANCELLED,
    COMPLETED,
    COMPLETED_WITH_WARNINGS,
    FAILED,
    INTERRUPTED,
    ;

    val isTerminal: Boolean
        get() = this == CANCELLED || this == COMPLETED || this == COMPLETED_WITH_WARNINGS || this == FAILED
}

enum class OperationItemState {
    QUEUED,
    RUNNING,
    SKIPPED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

enum class CollisionPolicy {
    REPLACE,
    SKIP,
    KEEP_BOTH,
    MERGE,
}

enum class CollisionKind {
    FILE_TO_FILE,
    DIRECTORY_TO_DIRECTORY,
    FILE_TO_DIRECTORY,
    DIRECTORY_TO_FILE,
    SAME_RESOURCE,
}

enum class BatchRenamePhase {
    ORIGINAL,
    TEMPORARY_PLANNED,
    TEMPORARY,
    FINALIZING,
    FINAL,
    ROLLBACK_TO_TEMP,
    ROLLBACK_TO_ORIGINAL,
    ROLLED_BACK,
}

enum class OperationFailureCode {
    PERMISSION_DENIED,
    SOURCE_MISSING,
    DESTINATION_MISSING,
    DESTINATION_READ_ONLY,
    INSUFFICIENT_SPACE,
    NAME_CONFLICT,
    INVALID_NAME,
    SELF_COPY,
    DESCENDANT_TARGET,
    IO_ERROR,
    STORAGE_REMOVED,
    PROVIDER_UNAVAILABLE,
    CANCELLED,
    SOURCE_CHANGED,
    SYMBOLIC_LINK_UNSUPPORTED,
    UNKNOWN,
    ;

    companion object {
        // Safety-specific call-site aliases intentionally map to the existing persisted/UI-safe
        // IO_ERROR enum value. This avoids breaking the exhaustive release UI and old journals
        // while retaining precise safety messages at the failure object level.
        val SAFE_FINALIZATION_UNSUPPORTED: OperationFailureCode = IO_ERROR
        val TRANSACTION_ROLLBACK_FAILED: OperationFailureCode = IO_ERROR
    }
}

data class OperationFailure(
    val code: OperationFailureCode,
    val message: String,
    val itemName: String? = null,
)

data class OperationSource(
    val reference: FileReference,
    val rootReference: String,
    val storageId: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
    val mimeType: String?,
    val isSymbolicLink: Boolean = false,
) {
    val scoped: ScopedFileReference
        get() = ScopedFileReference(reference, rootReference, storageId)
}

data class OperationItem(
    val id: String,
    val source: OperationSource,
    val state: OperationItemState = OperationItemState.QUEUED,
    val rootItemId: String? = null,
    val requestedName: String? = null,
    val destinationRelativePath: String = "",
    val processedBytes: Long = 0L,
    val failure: OperationFailure? = null,
    val resultReference: ScopedFileReference? = null,
    val partialOutput: ScopedFileReference? = null,
    // Batch-rename transaction ledger. source.scoped/source.name are the immutable original
    // reference/name, resultReference is the live current reference, requestedName is the target.
    val batchRenameTemporaryName: String? = null,
    val batchRenamePhase: BatchRenamePhase = BatchRenamePhase.ORIGINAL,
)

data class PendingCollision(
    val id: String,
    val itemId: String,
    val sourceName: String,
    val destinationName: String,
    val kind: CollisionKind,
    val allowedPolicies: Set<CollisionPolicy>,
)

data class FileOperation(
    val id: String,
    val type: FileOperationType,
    val state: FileOperationState,
    val items: List<OperationItem>,
    val destination: BrowserLocation? = null,
    val targetName: String? = null,
    val targetMimeType: String? = null,
    val createdAtMillis: Long,
    val startedAtMillis: Long? = null,
    val completedAtMillis: Long? = null,
    val updatedAtMillis: Long = createdAtMillis,
    val prepared: Boolean = false,
    val totalBytes: Long? = null,
    val processedBytes: Long = 0L,
    val totalItems: Long? = items.size.toLong(),
    val processedItems: Long = 0L,
    val currentItemName: String? = null,
    val failure: OperationFailure? = null,
    val warningCount: Long = 0L,
    val pendingCollision: PendingCollision? = null,
    val collisionDecisions: Map<String, CollisionPolicy> = emptyMap(),
    val applyToAllCollisionPolicy: CollisionPolicy? = null,
    val retryOfOperationId: String? = null,
    // Set before a batch-rename rollback begins. If Android/process interruption occurs during
    // rollback, resume continues rollback instead of accidentally continuing the forward rename.
    val batchRenameRollbackRequired: Boolean = false,
) {
    val failedItems: Long get() = items.count { it.state == OperationItemState.FAILED }.toLong()
    val skippedItems: Long get() = items.count { it.state == OperationItemState.SKIPPED }.toLong()
    val completedItems: Long get() = items.count { it.state == OperationItemState.COMPLETED }.toLong()
}

enum class ClipboardMode { COPY, CUT }

data class OperationClipboard(
    val mode: ClipboardMode,
    val sources: List<OperationSource>,
    val originatingLocation: BrowserLocation,
    val createdAtMillis: Long,
)

sealed interface OperationEvent {
    data class Started(val operationId: String) : OperationEvent
    data class ProgressUpdated(val operationId: String, val processedBytes: Long, val totalBytes: Long?) : OperationEvent
    data class CollisionRequired(val operationId: String, val collision: PendingCollision) : OperationEvent
    data class Paused(val operationId: String) : OperationEvent
    data class Completed(val operationId: String, val withWarnings: Boolean) : OperationEvent
    data class Failed(val operationId: String, val failure: OperationFailure) : OperationEvent
}
