package com.zz.filemanager.feature.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zz.filemanager.core.library.TrashRecord
import com.zz.filemanager.core.library.UserLibraryStore
import com.zz.filemanager.core.trash.RestoreCollisionPolicy
import com.zz.filemanager.core.trash.TrashManager
import com.zz.filemanager.core.trash.TrashResult
import com.zz.filemanager.core.trash.MediaStoreTrashGateway
import com.zz.filemanager.core.library.TrashBackendType
import android.app.PendingIntent
import android.net.Uri
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface RecycleEvent {
    data class Message(val text: String) : RecycleEvent
    data class Collision(val recordId: String) : RecycleEvent
    data class PlatformRequest(val pendingIntent: PendingIntent) : RecycleEvent
}

class RecycleBinViewModel(
    private val store: UserLibraryStore,
    private val manager: TrashManager,
    private val mediaStoreTrash: MediaStoreTrashGateway,
) : ViewModel() {
    val records = store.trashRecords
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _events = MutableSharedFlow<RecycleEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<RecycleEvent> = _events.asSharedFlow()
    private enum class PlatformAction { RESTORE, DELETE }
    private var pendingPlatform: Pair<PlatformAction, List<TrashRecord>>? = null

    init { viewModelScope.launch { store.initialize(); manager.reconcile() } }
    fun toggle(record: TrashRecord) { _selected.value = _selected.value.toMutableSet().apply { if (!add(record.id)) remove(record.id) } }
    fun clearSelection() { _selected.value = emptySet() }
    fun restore(policy: RestoreCollisionPolicy = RestoreCollisionPolicy.CANCEL, recordId: String? = null) = act {
        val ids = recordId?.let(::listOf) ?: _selected.value.toList()
        val chosen = records.value.filter { it.id in ids }
        chosen.filter { it.backend != TrashBackendType.MEDIA_STORE }.forEach { record ->
            val id = record.id
            when (val result = manager.restore(id, policy)) {
                is TrashResult.Collision -> _events.emit(RecycleEvent.Collision(id))
                is TrashResult.Failed -> _events.emit(RecycleEvent.Message(result.reason))
                is TrashResult.Unsupported -> _events.emit(RecycleEvent.Message(result.reason))
                is TrashResult.Success -> Unit
                is TrashResult.Queued -> Unit
            }
        }
        val media = chosen.filter { it.backend == TrashBackendType.MEDIA_STORE && it.originalReference.uri != null }
        if (media.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            pendingPlatform = PlatformAction.RESTORE to media
            _events.emit(RecycleEvent.PlatformRequest(mediaStoreTrash.createRequest(media.map { Uri.parse(it.originalReference.uri) }, false)))
        }
        clearSelection()
    }
    fun deleteSelected() = act {
        val chosen = records.value.filter { it.id in _selected.value }
        val failed = chosen.filter { it.backend != TrashBackendType.MEDIA_STORE }.count { !manager.deletePermanently(it.id) }
        if (failed > 0) _events.emit(RecycleEvent.Message("$failed item(s) could not be deleted."))
        val media = chosen.filter { it.backend == TrashBackendType.MEDIA_STORE && it.originalReference.uri != null }
        if (media.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            pendingPlatform = PlatformAction.DELETE to media
            _events.emit(RecycleEvent.PlatformRequest(mediaStoreTrash.createDeleteRequest(media.map { Uri.parse(it.originalReference.uri) })))
        }
        clearSelection()
    }
    fun empty() = act {
        val result = manager.empty()
        if (result.failed > 0) _events.emit(RecycleEvent.Message("${result.deleted} deleted; ${result.failed} failed."))
    }
    fun onPlatformResult(confirmed: Boolean) {
        val pending = pendingPlatform ?: return
        pendingPlatform = null
        if (!confirmed) return
        viewModelScope.launch {
            when (pending.first) {
                PlatformAction.RESTORE -> manager.completePlatformRestore(pending.second)
                PlatformAction.DELETE -> manager.completePlatformDelete(pending.second)
            }
        }
    }
    private fun act(block: suspend () -> Unit) { if (_busy.value) return; viewModelScope.launch { _busy.value = true; try { block() } finally { _busy.value = false } } }

    class Factory(private val store: UserLibraryStore, private val manager: TrashManager, private val mediaStoreTrash: MediaStoreTrashGateway) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = RecycleBinViewModel(store, manager, mediaStoreTrash) as T
    }
}
