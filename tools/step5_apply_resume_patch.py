from pathlib import Path

path = Path("app/src/main/java/com/zz/filemanager/core/operation/FileOperationEngine.kt")
text = path.read_text()

old = '''        // A non-transactional process/service interruption can leave a recorded staged output.
        // It is never the final visible filename and can be cleaned before restarting the copy.
        item.partialOutput?.let { stalePartial ->
            val staleProvider = providers.writableProviderFor(stalePartial.reference.providerId)
                ?: return markItemFailed(
                    operation,
                    item,
                    OperationFailure(
                        OperationFailureCode.PROVIDER_UNAVAILABLE,
                        "A previous staged output could not be cleaned because its storage provider is unavailable.",
                        item.source.name,
                    ),
                )
            if (!cleanupPartial(staleProvider, stalePartial)) {
                return markItemFailed(
                    operation,
                    item,
                    OperationFailure(
                        OperationFailureCode.PROVIDER_UNAVAILABLE,
                        "A previous staged output could not be cleaned. Reconnect the destination and retry.",
                        item.source.name,
                    ),
                )
            }
            item = item.copy(partialOutput = null, processedBytes = 0L)
            operation = recalculate(replaceItem(operation, item))
            save(operation)
        }

        val sourceProvider = providers.providerFor(item.source.reference.providerId)
'''
new = '''        val sourceProvider = providers.providerFor(item.source.reference.providerId)
        var resumeOffset = 0L
        var resumedOutputRef: ScopedFileReference? = null
        when (val decision = TransferResumeCoordinator.evaluate(item, sourceProvider, destinationProvider)) {
            TransferResumeCoordinator.Decision.None -> Unit
            is TransferResumeCoordinator.Decision.Resume -> {
                resumeOffset = decision.offset
                resumedOutputRef = decision.staged
            }
            is TransferResumeCoordinator.Decision.Restart -> {
                if (!cleanupPartial(destinationProvider, decision.staged)) {
                    return markItemFailed(
                        operation,
                        item,
                        OperationFailure(
                            OperationFailureCode.PROVIDER_UNAVAILABLE,
                            "A previous staged output could not be cleaned. Reconnect the destination and retry.",
                            item.source.name,
                        ),
                    )
                }
                item = TransferResumeCoordinator.clearCheckpoint(item)
                operation = recalculate(replaceItem(operation, item))
                save(operation)
            }
            is TransferResumeCoordinator.Decision.SourceChanged -> {
                if (!cleanupPartial(destinationProvider, decision.staged)) {
                    return markItemFailed(
                        operation,
                        item,
                        OperationFailure(
                            OperationFailureCode.PROVIDER_UNAVAILABLE,
                            "The changed source was detected, but its old staged output could not be cleaned.",
                            item.source.name,
                        ),
                    )
                }
                item = TransferResumeCoordinator.clearCheckpoint(item)
                operation = recalculate(replaceItem(operation, item))
                save(operation)
                return markItemFailed(
                    operation,
                    item,
                    OperationFailure(OperationFailureCode.SOURCE_CHANGED, decision.reason, item.source.name),
                )
            }
            is TransferResumeCoordinator.Decision.Blocked -> {
                return markItemFailed(operation, item, mapFailure(decision.failure, item.source.name))
            }
        }
'''
assert text.count(old) == 1, f"stale block count={text.count(old)}"
text = text.replace(old, new)

old = '''        val outputName = uniqueTemporaryName(destinationProvider, parent, operation.id, item.id)
        val outputEntry = try {
            destinationProvider.createFile(parent, outputName, item.source.mimeType)
        } catch (error: Throwable) {
            return markItemFailed(operation, item, mapFailure(error, item.source.name))
        }
        val outputRef = ScopedFileReference(outputEntry.reference, destination.rootReference, destination.storageId)
        item = item.copy(state = OperationItemState.RUNNING, partialOutput = outputRef, processedBytes = 0L)
        operation = replaceItem(operation.copy(currentItemName = item.source.name), item)
        save(recalculate(operation))

        try {
            var written = 0L
            var lastPersistAt = now()
            sourceProvider.openInputStream(item.source.reference).use { inputStream ->
                destinationProvider.openOutputStream(outputRef, truncate = true).use { outputStream ->
'''
new = '''        val outputRef: ScopedFileReference
        if (resumedOutputRef != null) {
            outputRef = resumedOutputRef!!
            item = item.copy(
                state = OperationItemState.RUNNING,
                partialOutput = outputRef,
                processedBytes = resumeOffset,
                resumeOffset = resumeOffset,
            )
        } else {
            val outputName = uniqueTemporaryName(destinationProvider, parent, operation.id, item.id)
            val outputEntry = try {
                destinationProvider.createFile(parent, outputName, item.source.mimeType)
            } catch (error: Throwable) {
                return markItemFailed(operation, item, mapFailure(error, item.source.name))
            }
            outputRef = ScopedFileReference(outputEntry.reference, destination.rootReference, destination.storageId)
            val proof = TransferResumeCoordinator.captureProof(sourceProvider, destinationProvider, item.source, outputRef)
            item = item.copy(
                state = OperationItemState.RUNNING,
                partialOutput = outputRef,
                processedBytes = 0L,
                resumeSourceIdentity = proof?.sourceIdentity,
                resumeStagedIdentity = proof?.stagedIdentity,
                resumeOffset = 0L,
            )
        }
        operation = replaceItem(operation.copy(currentItemName = item.source.name), item)
        save(recalculate(operation))

        try {
            var written = resumeOffset
            var lastPersistAt = now()
            TransferResumeCoordinator.openSource(sourceProvider, item.source, resumeOffset).use { inputStream ->
                TransferResumeCoordinator.openDestination(destinationProvider, outputRef, resumeOffset).use { outputStream ->
'''
assert text.count(old) == 1, f"stage block count={text.count(old)}"
text = text.replace(old, new)

old = '''                            item = item.copy(processedBytes = written)
'''
new = '''                            item = TransferResumeCoordinator.withProgress(item, written)
'''
assert text.count(old) == 1, f"progress update count={text.count(old)}"
text = text.replace(old, new)

# Clear resume proof in both collision/race restart branches.
old = '''                        item = item.copy(
                            state = OperationItemState.QUEUED,
                            partialOutput = if (cleaned) null else outputRef,
                            processedBytes = 0L,
                        )
'''
new = '''                        item = TransferResumeCoordinator.clearCheckpoint(
                            item.copy(
                                state = OperationItemState.QUEUED,
                                partialOutput = if (cleaned) null else outputRef,
                            ),
                            keepPartial = !cleaned,
                        )
'''
count = text.count(old)
assert count == 2, f"collision restart block count={count}"
text = text.replace(old, new)

old = '''            item = item.copy(
                processedBytes = written,
                state = OperationItemState.COMPLETED,
                resultReference = ScopedFileReference(commitResult.entry.reference, destination.rootReference, destination.storageId),
                partialOutput = null,
            )
'''
new = '''            val identityBeforeProof = destinationProvider.mutationIdentity(commitResult.entry.reference)
            val committedMetadata = destinationProvider.getMetadata(commitResult.entry.reference)
                ?: throw IOException("Committed destination disappeared before destination proof completed")
            if (item.source.sizeBytes != null && committedMetadata.sizeBytes != item.source.sizeBytes) {
                throw IOException("Committed destination size does not match the source")
            }
            val identityAfterProof = destinationProvider.mutationIdentity(commitResult.entry.reference)
            if (identityBeforeProof != null && identityAfterProof != identityBeforeProof) {
                throw IOException("Committed destination identity changed during destination proof")
            }

            item = item.copy(
                processedBytes = written,
                state = OperationItemState.COMPLETED,
                resultReference = ScopedFileReference(commitResult.entry.reference, destination.rootReference, destination.storageId),
                partialOutput = null,
                resumeSourceIdentity = null,
                resumeStagedIdentity = null,
                resumeOffset = 0L,
            )
'''
assert text.count(old) == 1, f"completion block count={text.count(old)}"
text = text.replace(old, new)

old = '''        } catch (pause: PauseSignal) {
            val cleaned = cleanupPartial(destinationProvider, outputRef)
            item = item.copy(state = OperationItemState.QUEUED, partialOutput = if (cleaned) null else outputRef, processedBytes = 0L)
            save(recalculate(replaceItem(operation, item)))
            throw pause
        } catch (cancel: CancelSignal) {
            val cleaned = cleanupPartial(destinationProvider, outputRef)
            item = item.copy(state = OperationItemState.CANCELLED, partialOutput = if (cleaned) null else outputRef)
            save(recalculate(replaceItem(operation, item)))
            throw cancel
'''
new = '''        } catch (pause: PauseSignal) {
            val cleaned = cleanupPartial(destinationProvider, outputRef)
            item = TransferResumeCoordinator.clearCheckpoint(
                item.copy(state = OperationItemState.QUEUED, partialOutput = if (cleaned) null else outputRef),
                keepPartial = !cleaned,
            )
            save(recalculate(replaceItem(operation, item)))
            throw pause
        } catch (cancel: CancelSignal) {
            val cleaned = cleanupPartial(destinationProvider, outputRef)
            item = TransferResumeCoordinator.clearCheckpoint(
                item.copy(state = OperationItemState.CANCELLED, partialOutput = if (cleaned) null else outputRef),
                keepPartial = !cleaned,
            )
            save(recalculate(replaceItem(operation, item)))
            throw cancel
'''
assert text.count(old) == 1, f"pause/cancel block count={text.count(old)}"
text = text.replace(old, new)

old = '''            val cleaned = cleanupPartial(destinationProvider, latestItem.partialOutput ?: outputRef)
            return markItemFailed(
                latest,
                latestItem.copy(partialOutput = if (cleaned) null else (latestItem.partialOutput ?: outputRef)),
                mapFailure(error, latestItem.source.name),
            )
'''
new = '''            val recordedPartial = latestItem.partialOutput ?: outputRef
            val cleaned = cleanupPartial(destinationProvider, recordedPartial)
            val failedItem = if (cleaned) {
                TransferResumeCoordinator.clearCheckpoint(latestItem)
            } else {
                latestItem.copy(partialOutput = recordedPartial)
            }
            return markItemFailed(
                latest,
                failedItem,
                mapFailure(error, latestItem.source.name),
            )
'''
assert text.count(old) == 1, f"generic failure block count={text.count(old)}"
text = text.replace(old, new)

path.write_text(text)
print("Step 5 safe resume patch applied")
