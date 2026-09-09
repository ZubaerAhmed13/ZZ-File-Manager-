from pathlib import Path


def replace_between(text: str, start_marker: str, end_marker: str, replacement: str, label: str) -> str:
    if text.count(start_marker) != 1:
        raise SystemExit(f"{label} start marker count: {text.count(start_marker)}")
    start = text.index(start_marker)
    end = text.index(end_marker, start)
    return text[:start] + replacement + text[end:]


coordinator = Path("app/src/main/java/com/zz/filemanager/core/operation/ReplaceTransactionCoordinator.kt")
text = coordinator.read_text()

text = replace_between(
    text,
    "        val committed = try {\n",
    "        val backupDeleted = runCatching { provider.delete(backupRef) }.getOrDefault(false)\n",
    '''        val committed = try {
            provider.rename(staged, finalName)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return recoverAfterMutationError(currentOperation, currentItem, provider, parent, error)
        }
        // Persist proof of the destructive staged -> final mutation immediately, before
        // post-commit verification. A crash or verification failure at this boundary must
        // remain recoverable and must never be reported as a completed operation.
        val committedRef = ScopedFileReference(committed.reference, destination.rootReference, destination.storageId)
        currentItem = currentItem.copy(
            replacePhase = ReplacePhase.COMMITTING,
            resultReference = committedRef,
            partialOutput = null,
        )
        currentOperation = saveItem(currentOperation, currentItem)

        try {
            verifySize(committed, expectedBytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val interrupted = interrupt(
                currentOperation,
                currentItem,
                "Replacement reached the final filename, but post-commit verification did not complete. The original safety backup is preserved for recovery.",
            )
            throw ReplaceTransactionNeedsRecovery(interrupted.id, error)
        }

        currentItem = currentItem.copy(replacePhase = ReplacePhase.COMMITTED)
        currentOperation = saveItem(currentOperation, currentItem)

''',
    "coordinator commit",
)

text = replace_between(
    text,
    "            ReplacePhase.BACKED_UP, ReplacePhase.COMMITTING -> when {\n",
    "    private suspend fun recoverAfterMutationError(\n",
    '''            ReplacePhase.BACKED_UP, ReplacePhase.COMMITTING -> when {
                backup != null && final == null -> {
                    val updated = currentItem.copy(replaceBackupReference = scoped(backup, currentItem))
                    restoreOriginal(saveItem(currentOperation, updated), updated, provider, parent)
                }

                backup != null && final != null && (
                    currentItem.resultReference?.let { sameReference(final, it) } == true ||
                        (stagedRef != null && stagedExists == false)
                    ) -> recoverCommittedCandidate(currentOperation, currentItem, provider, backup, final)

                backup == null && final != null && matchesOriginalSnapshot(currentItem, final) ->
                    resetUncommitted(currentOperation, currentItem, provider)

                else -> unresolved(
                    currentOperation,
                    currentItem,
                    "Replace recovery cannot prove whether finalization completed. The safety backup is preserved and the operation remains interrupted.",
                )
            }

            ReplacePhase.COMMITTED -> when {
                final != null -> recoverCommittedCandidate(currentOperation, currentItem, provider, backup, final)
                backup != null -> restoreOriginal(currentOperation, currentItem.copy(replaceBackupReference = scoped(backup, currentItem)), provider, parent)
                else -> unresolved(
                    currentOperation,
                    currentItem,
                    "Neither the committed replacement nor its safety backup can be located.",
                )
            }
        }
    }

''',
    "coordinator recovery",
)

helper_marker = "    private suspend fun finishCommitted(\n"
if text.count(helper_marker) != 1:
    raise SystemExit(f"coordinator helper marker count: {text.count(helper_marker)}")
helper = '''    private suspend fun recoverCommittedCandidate(
        operation: FileOperation,
        item: OperationItem,
        provider: WritableStorageProvider,
        backup: FileEntry?,
        final: FileEntry,
    ): RecoveryResult {
        val candidateItem = item.copy(
            replaceBackupReference = backup?.let { scoped(it, item) } ?: item.replaceBackupReference,
            resultReference = scoped(final, item),
            partialOutput = null,
        )
        val candidateOperation = saveItem(operation, candidateItem)

        try {
            verifySize(final, candidateItem.source.sizeBytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (backup != null) {
                return rollbackCommittedCandidate(candidateOperation, candidateItem, provider, backup, final)
            }
            return unresolved(
                candidateOperation,
                candidateItem,
                "The replacement final exists but cannot be verified and its safety backup is unavailable. The operation remains interrupted.",
            )
        }

        val committedItem = candidateItem.copy(replacePhase = ReplacePhase.COMMITTED)
        val committedOperation = if (candidateItem.replacePhase == ReplacePhase.COMMITTED) {
            candidateOperation
        } else {
            saveItem(candidateOperation, committedItem)
        }
        return finishCommitted(committedOperation, committedItem, provider, backup, final)
    }

    private suspend fun rollbackCommittedCandidate(
        operation: FileOperation,
        item: OperationItem,
        provider: WritableStorageProvider,
        backup: FileEntry,
        final: FileEntry,
    ): RecoveryResult {
        val finalName = item.replaceFinalName
            ?: return unresolved(operation, item, "Replace rollback is missing its final filename.")
        val backupRef = scoped(backup, item)
        val finalRef = scoped(final, item)

        val removed = try {
            provider.delete(finalRef)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }
        if (!removed) {
            return unresolved(
                operation,
                item.copy(replaceBackupReference = backupRef, resultReference = finalRef, partialOutput = null),
                "The unverified replacement final could not be removed. The original safety backup is preserved.",
            )
        }

        try {
            provider.rename(backupRef, finalName)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return unresolved(
                operation,
                item.copy(replaceBackupReference = backupRef, resultReference = null, partialOutput = null),
                "The unverified replacement was removed, but the original safety backup could not yet be restored.",
            )
        }

        val reset = clearLedger(item).copy(
            state = OperationItemState.QUEUED,
            failure = null,
            resultReference = null,
            partialOutput = null,
            processedBytes = 0L,
        )
        val saved = saveItem(operation, reset)
        return RecoveryResult(saved, reset, canContinue = true)
    }

'''
text = text.replace(helper_marker, helper + helper_marker)
coordinator.write_text(text)

engine = Path("app/src/main/java/com/zz/filemanager/core/operation/FileOperationEngine.kt")
text = engine.read_text()
text = replace_between(
    text,
    "    private suspend fun finishFromItems(input: FileOperation) {\n",
    "    private suspend fun cancelOperation(\n",
    '''    internal suspend fun finishFromItems(input: FileOperation) {
        val activeItem = input.items.firstOrNull {
            it.state == OperationItemState.QUEUED || it.state == OperationItemState.RUNNING
        }
        val unresolvedReplace = input.items.firstOrNull { it.replacePhase != ReplacePhase.NONE }
        val unresolvedBatch = input.batchRenameRollbackRequired ||
            (input.type == FileOperationType.BATCH_RENAME && input.items.any {
                it.batchRenamePhase !in setOf(BatchRenamePhase.FINAL, BatchRenamePhase.ROLLED_BACK)
            })

        if (activeItem != null || unresolvedReplace != null || unresolvedBatch) {
            val itemName = unresolvedReplace?.source?.name ?: activeItem?.source?.name ?: input.currentItemName
            val failure = OperationFailure(
                OperationFailureCode.TRANSACTION_ROLLBACK_FAILED,
                "Operation completion is not proven because an item or transaction is still unresolved. Recovery must finish before success can be reported.",
                itemName,
            )
            val interrupted = recalculate(
                input.copy(
                    state = FileOperationState.INTERRUPTED,
                    completedAtMillis = null,
                    updatedAtMillis = now(),
                    currentItemName = null,
                    pendingCollision = null,
                    failure = failure,
                ),
            )
            save(interrupted)
            return
        }

        val failed = input.items.count { it.state == OperationItemState.FAILED }
        val warnings = input.warningCount + input.items.count { it.state == OperationItemState.SKIPPED }.toLong()
        val state = when {
            failed == input.items.size && input.items.isNotEmpty() -> FileOperationState.FAILED
            failed > 0 || warnings > 0L -> FileOperationState.COMPLETED_WITH_WARNINGS
            else -> FileOperationState.COMPLETED
        }
        val final = recalculate(
            input.copy(
                state = state,
                completedAtMillis = now(),
                updatedAtMillis = now(),
                currentItemName = null,
                pendingCollision = null,
            ),
        )
        save(final)
        if (state == FileOperationState.FAILED) {
            _events.tryEmit(
                OperationEvent.Failed(
                    final.id,
                    final.items.firstNotNullOfOrNull { it.failure }
                        ?: final.failure
                        ?: OperationFailure(OperationFailureCode.UNKNOWN, "Operation failed."),
                ),
            )
        } else {
            _events.tryEmit(OperationEvent.Completed(final.id, state == FileOperationState.COMPLETED_WITH_WARNINGS))
        }
    }

''',
    "engine finish",
)
engine.write_text(text)
