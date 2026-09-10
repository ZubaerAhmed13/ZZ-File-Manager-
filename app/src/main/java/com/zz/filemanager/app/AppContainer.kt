package com.zz.filemanager.app

import android.content.Context
import com.zz.filemanager.core.analyzer.StorageAnalyzer
import com.zz.filemanager.core.apk.ApkManager
import com.zz.filemanager.core.archive.ArchiveManager
import com.zz.filemanager.core.library.OperationLibrarySynchronizer
import com.zz.filemanager.core.library.UserLibraryManager
import com.zz.filemanager.core.library.UserLibraryRepository
import com.zz.filemanager.core.media.MediaInspector
import com.zz.filemanager.core.operation.FileOperationController
import com.zz.filemanager.core.operation.FileOperationEngine
import com.zz.filemanager.core.operation.OperationClipboardRepository
import com.zz.filemanager.core.operation.OperationJournal
import com.zz.filemanager.core.operation.OperationRepository
import com.zz.filemanager.core.operation.android.AndroidOperationExecutionHost
import com.zz.filemanager.core.preferences.PreferencesRepository
import com.zz.filemanager.core.remote.AndroidKeystoreCredentialStore
import com.zz.filemanager.core.remote.FtpRemoteFileSystemFactory
import com.zz.filemanager.core.remote.NetworkConnectionManager
import com.zz.filemanager.core.remote.NetworkConnectionRepository
import com.zz.filemanager.core.remote.RemoteFileSystemFactoryRegistry
import com.zz.filemanager.core.remote.RemoteProviderCoordinator
import com.zz.filemanager.core.remote.SftpRemoteFileSystemFactory
import com.zz.filemanager.core.remote.SmbRemoteFileSystemFactory
import com.zz.filemanager.core.remote.WebDavRemoteFileSystemFactory
import com.zz.filemanager.core.search.SearchCoordinator
import com.zz.filemanager.core.search.SearchRepository
import com.zz.filemanager.core.step4.SafeOutputWriter
import com.zz.filemanager.core.step4.Step4WriteJournal
import com.zz.filemanager.core.storage.StorageRepository
import com.zz.filemanager.core.text.TextFileEngine
import com.zz.filemanager.core.trash.MediaStoreTrashGateway
import com.zz.filemanager.core.trash.TrashManager
import com.zz.filemanager.core.util.ThumbnailRepository

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val preferences = PreferencesRepository(appContext)

    // Step 5 connection metadata is intentionally separate from encrypted secret material.
    val networkConnections = NetworkConnectionRepository(appContext)
    val secureCredentials = AndroidKeystoreCredentialStore(appContext)
    val remoteFileSystems = RemoteFileSystemFactoryRegistry(
        listOf(
            SmbRemoteFileSystemFactory(),
            FtpRemoteFileSystemFactory(),
            SftpRemoteFileSystemFactory(),
            WebDavRemoteFileSystemFactory(),
        ),
    )

    val storage = StorageRepository(appContext, preferences)
    val remoteProviders = RemoteProviderCoordinator(storage, networkConnections, secureCredentials, remoteFileSystems)
    val networkConnectionManager = NetworkConnectionManager(networkConnections, secureCredentials)

    init {
        // Rebuild runtime provider registrations synchronously from non-secret metadata. No network
        // I/O or credential decryption occurs here, so application startup never auto-connects.
        remoteProviders.syncSavedConnections()
    }

    val thumbnails = ThumbnailRepository(appContext)

    val operationStore = OperationRepository(OperationJournal(appContext))
    val operationClipboard = OperationClipboardRepository(appContext)
    val operationEngine = FileOperationEngine(operationStore, storage)
    val operationExecutionHost = AndroidOperationExecutionHost(appContext)
    val operationController = FileOperationController(operationStore, operationExecutionHost)
    val userLibrary = UserLibraryRepository(appContext)
    val userLibraryManager = UserLibraryManager(userLibrary, storage)
    val searchRepository = SearchRepository(SearchCoordinator(storage, storage), userLibrary)
    val trashManager = TrashManager(
        storage,
        userLibrary,
        userLibraryManager,
        rootSource = storage,
        operationController = operationController,
        operationStore = operationStore,
    )
    val mediaStoreTrash = MediaStoreTrashGateway(appContext.contentResolver)
    val operationLibrarySynchronizer = OperationLibrarySynchronizer(
        operationEngine,
        operationStore,
        userLibrary,
        userLibraryManager,
        trashManager,
    )

    // Step 4 shared staged-write safety is used by archive extraction, text saves and APK
    // backup. It is separate from Step 2's operation journal because these are not queued
    // FileOperation jobs, but it preserves the same no-data-loss invariants.
    val step4WriteJournal = Step4WriteJournal(appContext)
    val safeOutputWriter = SafeOutputWriter(storage, step4WriteJournal)
    val archiveManager = ArchiveManager(appContext, storage, safeOutputWriter)
    val textFileEngine = TextFileEngine(storage, safeOutputWriter)
    val mediaInspector = MediaInspector(appContext, storage, storage)
    val apkManager = ApkManager(appContext, storage, safeOutputWriter)
    val storageAnalyzer = StorageAnalyzer(storage, storage)
}
