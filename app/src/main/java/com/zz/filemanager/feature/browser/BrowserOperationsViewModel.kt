package com.zz.filemanager.feature.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.OpenFileRequest
import com.zz.filemanager.core.operation.BatchRenamePreview
import com.zz.filemanager.core.operation.BatchRenameRule
import com.zz.filemanager.core.operation.BatchRenamePlanner
import com.zz.filemanager.core.operation.ClipboardMode
import com.zz.filemanager.core.operation.CollisionPolicy
import com.zz.filemanager.core.operation.FileOperation
import com.zz.filemanager.core.operation.FileOperationController
import com.zz.filemanager.core.operation.OperationClipboard
import com.zz.filemanager.core.operation.OperationClipboardRepository
import com.zz.filemanager.core.storage.StorageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BrowserOperationsViewModel(
    private val controller: FileOperationController,
    private val clipboardRepository: OperationClipboardRepository,
    private val storage: StorageRepository,
) : ViewModel() {
    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()
    val clipboard: StateFlow<OperationClipboard?> = clipboardRepository.clipboard
    val operations: StateFlow<List<FileOperation>> = controller.operations

    init {
        viewModelScope.launch { controller.initialize() }
    }

    fun toggleSelection(entry: FileEntry) {
        _selectedIds.value = _selectedIds.value.toMutableSet().apply {
            if (!add(entry.id)) remove(entry.id)
        }
    }

    fun select(entry: FileEntry) {
        _selectedIds.value = _selectedIds.value + entry.id
    }

    fun selectAll(entries: List<FileEntry>) {
        _selectedIds.value = entries.mapTo(linkedSetOf()) { it.id }
    }

    fun selectRange(entries: List<FileEntry>, anchorId: String, targetId: String) {
        val anchor = entries.indexOfFirst { it.id == anchorId }
        val target = entries.indexOfFirst { it.id == targetId }
        if (anchor < 0 || target < 0) return
        val range = if (anchor <= target) anchor..target else target..anchor
        _selectedIds.value = _selectedIds.value + range.map { entries[it].id }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun reconcileSelection(entries: List<FileEntry>) {
        val visible = entries.asSequence().map { it.id }.toHashSet()
        val next = _selectedIds.value.filterTo(linkedSetOf()) { it in visible }
        if (next != _selectedIds.value) _selectedIds.value = next
    }

    fun selectedEntries(entries: List<FileEntry>): List<FileEntry> {
        val selected = _selectedIds.value
        return entries.filter { it.id in selected }
    }

    fun copy(entries: List<FileEntry>, location: BrowserLocation) {
        putClipboard(entries, location, ClipboardMode.COPY)
    }

    fun cut(entries: List<FileEntry>, location: BrowserLocation) {
        putClipboard(entries, location, ClipboardMode.CUT)
    }

    private fun putClipboard(entries: List<FileEntry>, location: BrowserLocation, mode: ClipboardMode) {
        val chosen = selectedEntries(entries)
        if (chosen.isEmpty()) return
        val sources = chosen.map { controller.source(it, location) }
        clipboardRepository.set(OperationClipboard(mode, sources, location, System.currentTimeMillis()))
        clearSelection()
    }

    fun clearClipboard() = clipboardRepository.clear()

    fun paste(destination: BrowserLocation) {
        val value = clipboardRepository.clipboard.value ?: return
        viewModelScope.launch {
            when (value.mode) {
                ClipboardMode.COPY -> controller.enqueueCopy(value.sources, destination)
                ClipboardMode.CUT -> {
                    controller.enqueueMove(value.sources, destination)
                    clipboardRepository.clear()
                }
            }
        }
    }

    fun delete(entries: List<FileEntry>, location: BrowserLocation) {
        val chosen = selectedEntries(entries)
        if (chosen.isEmpty()) return
        val sources = chosen.map { controller.source(it, location) }
        clearSelection()
        viewModelScope.launch { controller.enqueueDelete(sources) }
    }

    fun rename(entry: FileEntry, location: BrowserLocation, newName: String) {
        clearSelection()
        viewModelScope.launch {
            controller.enqueueRename(controller.source(entry, location), location, newName)
        }
    }

    fun previewBatchRename(entries: List<FileEntry>, rule: BatchRenameRule): List<BatchRenamePreview> =
        BatchRenamePlanner.preview(selectedEntries(entries), rule)

    fun batchRename(entries: List<FileEntry>, location: BrowserLocation, previews: List<BatchRenamePreview>) {
        val chosen = selectedEntries(entries)
        if (chosen.isEmpty() || previews.any { !it.valid }) return
        val namesByEntryId = previews.associateBy { it.itemId }
        val proposedByOpaqueId = chosen.associate { entry ->
            entry.reference.opaqueId to (namesByEntryId[entry.id]?.proposedName ?: entry.name)
        }
        val sources = chosen.map { controller.source(it, location) }
        clearSelection()
        viewModelScope.launch { controller.enqueueBatchRename(sources, location, proposedByOpaqueId) }
    }

    fun createDirectory(location: BrowserLocation, name: String) {
        viewModelScope.launch { controller.enqueueCreateDirectory(location, name) }
    }

    fun createFile(location: BrowserLocation, name: String) {
        viewModelScope.launch { controller.enqueueCreateFile(location, name) }
    }

    fun resolveCollision(operationId: String, policy: CollisionPolicy, applyToAll: Boolean) {
        viewModelScope.launch { controller.resolveCollision(operationId, policy, applyToAll) }
    }

    fun pause(operationId: String) {
        viewModelScope.launch { controller.pause(operationId) }
    }

    fun resume(operationId: String) {
        viewModelScope.launch { controller.resume(operationId) }
    }

    fun cancel(operationId: String) {
        viewModelScope.launch { controller.cancel(operationId) }
    }

    fun retry(operationId: String) {
        viewModelScope.launch { controller.retry(operationId) }
    }

    fun shareRequests(entries: List<FileEntry>): List<OpenFileRequest> =
        selectedEntries(entries).filterNot { it.isDirectory }.mapNotNull(storage::openRequest)

    class Factory(
        private val controller: FileOperationController,
        private val clipboard: OperationClipboardRepository,
        private val storage: StorageRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BrowserOperationsViewModel(controller, clipboard, storage) as T
    }
}
