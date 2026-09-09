package com.zz.filemanager.core.operation

import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.ScopedFileReference
import com.zz.filemanager.core.storage.StorageCapability
import com.zz.filemanager.core.storage.StorageProviderRegistry
import com.zz.filemanager.core.storage.WritableStorageProvider
import java.io.IOException
import kotlin.math.abs

/**
 * Durable transaction coordinator for Replace on providers that cannot offer an atomic replace.
 *
 * Invariants:
 * - the existing destination is never deleted before the staged replacement is complete;
 * - every destructive rename boundary is journaled before/after provider mutation;
 * - process death can never cause the old destination backup to be forgotten;
 * - ambiguous recovery keeps the operation INTERRUPTED rather than guessing and deleting data.
 */
internal class ReplaceTransactionCoordinator(
    private val store: OperationStore,
    private val providers: StorageProviderRegistry,
    private val now: () -> Long,
) {
    data class RecoveryResult(
        val operation: FileOperation,
        val item: OperationItem,
        val committedEntry: FileEntry? = null,
        val canContinue: Boolean,
    )

    suspend fun commit(
        operation: FileOperation,
        item: OperationItem,
        provider: WritableStorageProvider,
        parent: BrowserLocation,
        staged: ScopedFileReference,
        existing: FileEntry,
        finalName: String,
        expectedBytes: Long?,
    ): FileEntry {
        val destination = operation.destination ?: throw IOException("Destination disappeared during Replace")
        val capabilities = provider.capabilities(parent)
        require(StorageCapability.RENAME in capabilities && StorageCapability.DELETE in capabilities) {
            "Provider cannot safely perform reversible Replace"
        }

        val existingRef = ScopedFileReference(existing.reference, destination.rootReference, destination.storageId)
        val backupName = uniqueBackupName(provider, parent, operation.id, item.id)
        var currentOperation = latest(operation)
        var currentItem = currentOperation.items.firstOrNull { it.id == item.id } ?: item
        currentItem = currentItem.copy(
            replacePhase = ReplacePhase.BACKUP_PLANNED,
            replaceFinalName = finalName,
            replaceOriginalReference = existingRef,
            replaceOriginalSizeBytes = existing.sizeBytes,
            replaceOriginalModifiedAtMillis = existing.modifiedAtMillis,
            replaceBackupName = backupName,
            replaceBackupReference = null,
            partialOutput = staged,
        )
        currentOperation = saveItem(currentOperation, currentItem)

        val backup = try {
            provider.rename(existingRef, backupName)
        } catch (error: Throwable) {
            return recoverAfterMutationError(currentOperation, currentItem, provider, parent, error)
        }
        val backupRef = ScopedFileReference(backup.reference, destination.rootReference, destination.storageId)
        currentItem = currentItem.copy(
            replacePhase = ReplacePhase.BACKED_UP,
            replaceBackupReference = backupRef,
        )
        currentOperation = saveItem(currentOperation, currentItem)

        currentItem = currentItem.copy(replacePhase = ReplacePhase.COMMITTING)
        currentOperation = saveItem(currentOperation, currentItem)

        val committed = try {
            provider.rename(staged, finalName)
        } catch (error: Throwable) {
            return recoverAfterMutationError(currentOperation, currentItem, provider, parent, error)
        }
        verifySize(committed, expectedBytes)
        val committedRef = ScopedFileReference(committed.reference, destination.rootReference, destination.storageId)
        currentItem = currentItem.copy(
            replacePhase = ReplacePhase.COMMITTED,
            resultReference = committedRef,
            partialOutput = null,
        )
        currentOperation = saveItem(currentOperation, currentItem)

        val backupDeleted = runCatching { provider.delete(backupRef) }.getOrDefault(false)
        if (!backupDeleted) {
            val interrupted = interrupt(
                currentOperation,
                currentItem,
                "Replacement committed, but the original safety backup could not be removed. Resume after storage is available to finish cleanup.",
            )
            throw ReplaceTransactionNeedsRecovery(interrupted.id)
        }

        currentItem = clearLedger(currentItem).copy(resultReference = committedRef, partialOutput = null)
        saveItem(currentOperation, currentItem)
        return committed
    }

    suspend fun recover(
        operation: FileOperation,
        item: OperationItem,
        provider: WritableStorageProvider,
        parent: BrowserLocation,
    ): RecoveryResult {
        if (item.replacePhase == ReplacePhase.NONE) {
            return RecoveryResult(operation, item, canContinue = true)
        }
        val currentOperation = latest(operation)
        val currentItem = currentOperation.items.firstOrNull { it.id == item.id } ?: item
        val finalName = currentItem.replaceFinalName
            ?: return unresolved(currentOperation, currentItem, "Replace journal is missing the final filename.")
        val backupName = currentItem.replaceBackupName
            ?: return unresolved(currentOperation, currentItem, "Replace journal is missing the safety backup filename.")

        val final = try {
            provider.findChild(parent, finalName)
        } catch (_: Throwable) {
            return unresolved(currentOperation, currentItem, "Destination storage is unavailable while recovering Replace.")
        }
        val backup = try {
            provider.findChild(parent, backupName)
        } catch (_: Throwable) {
            return unresolved(currentOperation, currentItem, "Destination storage is unavailable while locating the Replace safety backup.")
        }
        val stagedRef = currentItem.partialOutput
        val stagedExists = stagedRef?.let { ref ->
            runCatching { providers.providerFor(ref.reference.providerId).exists(ref.reference) }.getOrNull()
        }

        return when (currentItem.replacePhase) {
            ReplacePhase.NONE -> RecoveryResult(currentOperation, currentItem, canContinue = true)
            ReplacePhase.BACKUP_PLANNED -> when {
                backup == null && final != null && matchesOriginalSnapshot(currentItem, final) ->
                    resetUncommitted(currentOperation, currentItem, provider)

                backup != null && final == null -> {
                    val updated = currentItem.copy(
                        replacePhase = ReplacePhase.BACKED_UP,
                        replaceBackupReference = scoped(backup, currentItem),
                    )
                    val saved = saveItem(currentOperation, updated)
                    restoreOriginal(saved, updated, provider, parent)
                }

                backup != null && final != null && stagedRef != null && stagedExists == false && sameReference(final, stagedRef) -> {
                    val updated = currentItem.copy(
                        replacePhase = ReplacePhase.COMMITTED,
                        replaceBackupReference = scoped(backup, currentItem),
                        resultReference = scoped(final, currentItem),
                        partialOutput = null,
                    )
                    finishCommitted(saveItem(currentOperation, updated), updated, provider, backup, final)
                }

                else -> unresolved(
                    currentOperation,
                    currentItem,
                    "Replace recovery is ambiguous. The original safety backup is preserved; no destination data was deleted.",
                )
            }

            ReplacePhase.BACKED_UP, ReplacePhase.COMMITTING -> when {
                backup != null && final == null -> {
                    val updated = currentItem.copy(replaceBackupReference = scoped(backup, currentItem))
                    restoreOriginal(saveItem(currentOperation, updated), updated, provider, parent)
                }

                backup != null && final != null && stagedRef != null && stagedExists == false && sameReference(final, stagedRef) -> {
                    val updated = currentItem.copy(
                        replacePhase = ReplacePhase.COMMITTED,
                        replaceBackupReference = scoped(backup, currentItem),
                        resultReference = scoped(final, currentItem),
                        partialOutput = null,
                    )
                    finishCommitted(saveItem(currentOperation, updated), updated, provider, backup, final)
                }

                backup == null && final != null && matchesOriginalSnapshot(currentItem, final) ->
                    resetUncommitted(currentOperation, currentItem, provider)

                backup == null && final != null && stagedRef != null && stagedExists == false && sameReference(final, stagedRef) -> {
                    val updated = currentItem.copy(
                        replacePhase = ReplacePhase.COMMITTED,
                        resultReference = scoped(final, currentItem),
                        partialOutput = null,
                    )
                    finishCommitted(saveItem(currentOperation, updated), updated, provider, null, final)
                }

                else -> unresolved(
                    currentOperation,
                    currentItem,
                    "Replace recovery cannot prove whether finalization completed. The safety backup is preserved and the operation remains interrupted.",
                )
            }

            ReplacePhase.COMMITTED -> when {
                final != null -> finishCommitted(currentOperation, currentItem, provider, backup, final)
                backup != null -> restoreOriginal(currentOperation, currentItem.copy(replaceBackupReference = scoped(backup, currentItem)), provider, parent)
                else -> unresolved(
                    currentOperation,
                    currentItem,
                    "Neither the committed replacement nor its safety backup can be located.",
                )
            }
        }
    }

    private suspend fun recoverAfterMutationError(
        operation: FileOperation,
        item: OperationItem,
        provider: WritableStorageProvider,
        parent: BrowserLocation,
        originalError: Throwable,
    ): FileEntry {
        val recovery = recover(operation, item, provider, parent)
        recovery.committedEntry?.let { return it }
        if (recovery.canContinue) throw originalError
        throw ReplaceTransactionNeedsRecovery(recovery.operation.id, originalError)
    }

    private suspend fun restoreOriginal(
        operation: FileOperation,
        item: OperationItem,
        provider: WritableStorageProvider,
        parent: BrowserLocation,
    ): RecoveryResult {
        val finalName = item.replaceFinalName
            ?: return unresolved(operation, item, "Replace rollback is missing its final filename.")
        val backupName = item.replaceBackupName
            ?: return unresolved(operation, item, "Replace rollback is missing its safety backup filename.")
        val final = runCatching { provider.findChild(parent, finalName) }.getOrNull()
        if (final != null) {
            return unresolved(
                operation,
                item,
                "Replace rollback found an unexpected final-name entry. The safety backup remains untouched.",
            )
        }
        val backupEntry = runCatching { provider.findChild(parent, backupName) }.getOrNull()
            ?: return unresolved(operation, item, "Replace safety backup cannot currently be located.")
        val backupRef = scoped(backupEntry, item)
        val restored = try {
            provider.rename(backupRef, finalName)
        } catch (_: Throwable) {
            return unresolved(
                operation,
                item.copy(replaceBackupReference = backupRef),
                "The original destination remains in its safety backup but could not be restored yet.",
            )
        }

        val staged = item.partialOutput
        val cleaned = staged == null || cleanupPartial(provider, staged)
        val reset = clearLedger(item).copy(
            state = OperationItemState.QUEUED,
            resultReference = null,
            partialOutput = if (cleaned) null else staged,
            processedBytes = 0L,
        )
        val saved = saveItem(operation, reset)
        return RecoveryResult(saved, reset, canContinue = true)
    }

    private suspend fun resetUncommitted(
        operation: FileOperation,
        item: OperationItem,
        provider: WritableStorageProvider,
    ): RecoveryResult {
        val staged = item.partialOutput
        val cleaned = staged == null || cleanupPartial(provider, staged)
        val reset = clearLedger(item).copy(
            state = OperationItemState.QUEUED,
            resultReference = null,
            partialOutput = if (cleaned) null else staged,
            processedBytes = 0L,
        )
        val saved = saveItem(operation, reset)
        return RecoveryResult(saved, reset, canContinue = true)
    }

    private suspend fun finishCommitted(
        operation: FileOperation,
        item: OperationItem,
        provider: WritableStorageProvider,
        backup: FileEntry?,
        final: FileEntry,
    ): RecoveryResult {
        if (backup != null) {
            val deleted = runCatching { provider.delete(scoped(backup, item)) }.getOrDefault(false)
            if (!deleted) {
                return unresolved(
                    operation,
                    item.copy(
                        replacePhase = ReplacePhase.COMMITTED,
                        replaceBackupReference = scoped(backup, item),
                        resultReference = scoped(final, item),
                        partialOutput = null,
                    ),
                    "Replacement is committed, but its safety backup cannot be removed yet.",
                )
            }
        }
        val completedLedger = clearLedger(item).copy(
            state = OperationItemState.RUNNING,
            resultReference = scoped(final, item),
            partialOutput = null,
            processedBytes = item.source.sizeBytes ?: item.processedBytes,
        )
        val saved = saveItem(operation, completedLedger)
        return RecoveryResult(saved, completedLedger, committedEntry = final, canContinue = true)
    }

    private suspend fun unresolved(
        operation: FileOperation,
        item: OperationItem,
        message: String,
    ): RecoveryResult {
        val interrupted = interrupt(operation, item, message)
        val savedItem = interrupted.items.first { it.id == item.id }
        return RecoveryResult(interrupted, savedItem, canContinue = false)
    }

    private suspend fun interrupt(operation: FileOperation, item: OperationItem, message: String): FileOperation {
        val normalizedItem = item.copy(
            state = OperationItemState.QUEUED,
            failure = OperationFailure(OperationFailureCode.TRANSACTION_ROLLBACK_FAILED, message, item.source.name),
        )
        val interrupted = replaceItem(operation, normalizedItem).copy(
            state = FileOperationState.INTERRUPTED,
            currentItemName = null,
            failure = normalizedItem.failure,
            updatedAtMillis = now(),
        )
        store.save(interrupted)
        return interrupted
    }

    private suspend fun saveItem(operation: FileOperation, item: OperationItem): FileOperation {
        val updated = replaceItem(operation, item).copy(updatedAtMillis = now())
        store.save(updated)
        return updated
    }

    private suspend fun latest(fallback: FileOperation): FileOperation = store.get(fallback.id) ?: fallback

    private fun replaceItem(operation: FileOperation, updated: OperationItem): FileOperation =
        operation.copy(items = operation.items.map { if (it.id == updated.id) updated else it })

    private fun clearLedger(item: OperationItem): OperationItem = item.copy(
        replacePhase = ReplacePhase.NONE,
        replaceFinalName = null,
        replaceOriginalReference = null,
        replaceOriginalSizeBytes = null,
        replaceOriginalModifiedAtMillis = null,
        replaceBackupName = null,
        replaceBackupReference = null,
    )

    private fun scoped(entry: FileEntry, item: OperationItem): ScopedFileReference = ScopedFileReference(
        entry.reference,
        item.source.rootReference,
        item.source.storageId,
    )

    private fun sameReference(entry: FileEntry, reference: ScopedFileReference): Boolean =
        entry.reference.providerId == reference.reference.providerId &&
            (
                entry.reference.opaqueId == reference.reference.opaqueId ||
                    (entry.reference.uri != null && entry.reference.uri == reference.reference.uri) ||
                    (entry.reference.path != null && entry.reference.path == reference.reference.path)
                )

    private fun matchesOriginalSnapshot(item: OperationItem, current: FileEntry): Boolean {
        val originalRef = item.replaceOriginalReference ?: return false
        if (!sameReference(current, originalRef)) return false
        val expectedSize = item.replaceOriginalSizeBytes
        if (expectedSize != null && current.sizeBytes != null && expectedSize != current.sizeBytes) return false
        val expectedModified = item.replaceOriginalModifiedAtMillis
        if (expectedModified != null && current.modifiedAtMillis != null && expectedModified != current.modifiedAtMillis) return false
        return true
    }

    private fun verifySize(entry: FileEntry, expectedBytes: Long?) {
        if (expectedBytes != null && entry.sizeBytes != null && expectedBytes != entry.sizeBytes) {
            throw IOException("Committed replacement byte count does not match source size")
        }
    }

    private suspend fun cleanupPartial(provider: WritableStorageProvider, partial: ScopedFileReference): Boolean = runCatching {
        provider.delete(partial) || !providers.providerFor(partial.reference.providerId).exists(partial.reference)
    }.getOrDefault(false)

    private suspend fun uniqueBackupName(
        provider: WritableStorageProvider,
        parent: BrowserLocation,
        operationId: String,
        itemId: String,
    ): String {
        val token = abs(itemId.hashCode().toLong()).toString()
        var index = 0
        while (true) {
            val candidate = ".zzreplace-backup-${operationId.take(8)}-$token" + if (index == 0) "" else "-$index"
            if (provider.findChild(parent, candidate) == null) return candidate
            index++
        }
    }
}

internal class ReplaceTransactionNeedsRecovery(
    val operationId: String,
    cause: Throwable? = null,
) : IOException("Replace transaction requires recovery before it can continue.", cause)
