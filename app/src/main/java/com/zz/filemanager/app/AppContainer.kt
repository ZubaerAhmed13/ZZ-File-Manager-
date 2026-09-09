package com.zz.filemanager.app

import android.content.Context
import com.zz.filemanager.core.operation.FileOperationController
import com.zz.filemanager.core.operation.FileOperationEngine
import com.zz.filemanager.core.operation.OperationClipboardRepository
import com.zz.filemanager.core.operation.OperationJournal
import com.zz.filemanager.core.operation.OperationRepository
import com.zz.filemanager.core.operation.android.AndroidOperationExecutionHost
import com.zz.filemanager.core.preferences.PreferencesRepository
import com.zz.filemanager.core.storage.StorageRepository
import com.zz.filemanager.core.util.ThumbnailRepository
import com.zz.filemanager.core.library.UserLibraryManager
import com.zz.filemanager.core.library.UserLibraryRepository
import com.zz.filemanager.core.library.OperationLibrarySynchronizer
import com.zz.filemanager.core.search.SearchCoordinator
import com.zz.filemanager.core.search.SearchRepository
import com.zz.filemanager.core.trash.MediaStoreTrashGateway
import com.zz.filemanager.core.trash.TrashManager

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val preferences = PreferencesRepository(appContext)
    val storage = StorageRepository(appContext, preferences)
    val thumbnails = ThumbnailRepository(appContext)

    val operationStore = OperationRepository(OperationJournal(appContext))
    val operationClipboard = OperationClipboardRepository(appContext)
    val operationEngine = FileOperationEngine(operationStore, storage)
    val operationExecutionHost = AndroidOperationExecutionHost(appContext)
    val operationController = FileOperationController(operationStore, operationExecutionHost)
    val userLibrary = UserLibraryRepository(appContext)
    val userLibraryManager = UserLibraryManager(userLibrary, storage)
    val searchRepository = SearchRepository(SearchCoordinator(storage, storage), userLibrary)
    val trashManager = TrashManager(storage, userLibrary, userLibraryManager)
    val mediaStoreTrash = MediaStoreTrashGateway(appContext.contentResolver)
    val operationLibrarySynchronizer = OperationLibrarySynchronizer(operationEngine, operationStore, userLibrary, userLibraryManager)
}
