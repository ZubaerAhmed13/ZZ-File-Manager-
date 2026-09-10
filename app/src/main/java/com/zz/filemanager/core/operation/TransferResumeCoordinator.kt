package com.zz.filemanager.core.operation

import com.zz.filemanager.core.model.ScopedFileReference
import com.zz.filemanager.core.storage.ResumableStorageProvider
import com.zz.filemanager.core.storage.ResumableWritableStorageProvider
import com.zz.filemanager.core.storage.StorageProvider
import com.zz.filemanager.core.storage.WritableStorageProvider

/**
 * Decides whether a hidden staged transfer can be reopened at a non-zero offset.
 *
 * A byte count alone is never trusted. Resume requires a provider that explicitly supports
 * seekable reads/writes plus live equality for source revision, staged-object mutation identity,
 * and staged byte length. This keeps process-death recovery from concatenating unrelated data.
 */
object TransferResumeCoordinator {
    data class Proof(
        val sourceIdentity: String,
        val stagedIdentity: String,
    )

    sealed interface Decision {
        data object None : Decision
        data class Resume(val offset: Long, val staged: ScopedFileReference) : Decision
        data class Restart(val staged: ScopedFileReference, val reason: String) : Decision
        data class SourceChanged(val staged: ScopedFileReference, val reason: String) : Decision
        data class Blocked(val failure: Throwable) : Decision
    }

    suspend fun captureProof(
        sourceProvider: StorageProvider,
        destinationProvider: WritableStorageProvider,
        source: OperationSource,
        staged: ScopedFileReference,
    ): Proof? {
        val resumableSource = sourceProvider as? ResumableStorageProvider ?: return null
        val resumableDestination = destinationProvider as? ResumableWritableStorageProvider ?: return null
        return try {
            val sourceIdentity = resumableSource.resumeIdentity(source.reference)?.takeIf { it.isNotBlank() } ?: return null
            val stagedIdentity = resumableDestination.mutationIdentity(staged.reference)?.takeIf { it.isNotBlank() } ?: return null
            Proof(sourceIdentity, stagedIdentity)
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun evaluate(
        item: OperationItem,
        sourceProvider: StorageProvider,
        destinationProvider: WritableStorageProvider,
    ): Decision {
        val staged = item.partialOutput ?: return Decision.None
        if (
            item.resumeOffset <= 0L ||
            item.processedBytes != item.resumeOffset ||
            item.resumeSourceIdentity.isNullOrBlank() ||
            item.resumeStagedIdentity.isNullOrBlank()
        ) {
            return Decision.Restart(staged, "Persisted resume proof is incomplete or internally inconsistent.")
        }

        val resumableSource = sourceProvider as? ResumableStorageProvider
            ?: return Decision.Restart(staged, "Source provider cannot reopen a stream at a verified offset.")
        val resumableDestination = destinationProvider as? ResumableWritableStorageProvider
            ?: return Decision.Restart(staged, "Destination provider cannot reopen a staged stream at a verified offset.")

        return try {
            val liveSourceIdentity = resumableSource.resumeIdentity(item.source.reference)
            if (liveSourceIdentity.isNullOrBlank() || liveSourceIdentity != item.resumeSourceIdentity) {
                return Decision.SourceChanged(staged, "Source revision changed since the checkpoint was written.")
            }
            if (!resumableDestination.exists(staged.reference)) {
                return Decision.Restart(staged, "The hidden staged object no longer exists.")
            }
            val liveStagedIdentity = resumableDestination.mutationIdentity(staged.reference)
            if (liveStagedIdentity.isNullOrBlank() || liveStagedIdentity != item.resumeStagedIdentity) {
                return Decision.Restart(staged, "The hidden staged object identity changed.")
            }
            val stagedSize = resumableDestination.getMetadata(staged.reference)?.sizeBytes
            if (stagedSize == null || stagedSize != item.resumeOffset) {
                return Decision.Restart(staged, "The hidden staged object length does not match the persisted offset.")
            }
            Decision.Resume(item.resumeOffset, staged)
        } catch (error: Throwable) {
            Decision.Blocked(error)
        }
    }

    fun clearCheckpoint(item: OperationItem, keepPartial: Boolean = false): OperationItem = item.copy(
        partialOutput = if (keepPartial) item.partialOutput else null,
        processedBytes = 0L,
        resumeSourceIdentity = null,
        resumeStagedIdentity = null,
        resumeOffset = 0L,
    )
}
