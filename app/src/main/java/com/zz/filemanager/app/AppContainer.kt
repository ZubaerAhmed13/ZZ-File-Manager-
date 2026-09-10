package com.zz.filemanager.app

import android.content.Context
import com.zz.filemanager.core.analyzer.StorageAnalyzer
import com.zz.filemanager.core.apk.ApkManager
import com.zz.filemanager.core.archive.ArchiveManager
import com.zz.filemanager.core.cloud.DirectCloudAccountManager
import com.zz.filemanager.core.cloud.DirectCloudAdapterRegistry
import com.zz.filemanager.core.cloud.DirectCloudOAuthDriverRegistry
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
import com.zz.filemanager.core.remote.LanDiscoveryService
import com.zz.filemanager.core.remote.NetworkConnectionManager
import com.zz.filemanager.core.remote.NetworkConnectionRepository
import com.zz.filemanager.core.remote.RemoteConnectionService
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

    // Repository-safe Step 5 ships no vendor OAuth credentials. Vendor adapters/drivers are
    // registered here by a distribution that supplies its own OAuth client registration.
    val directCloudAdapters = DirectCloudAdapterRegistry(emptyList())
    val directCloudOAuthDrivers = DirectCloudOAuthDriverRegistry(emptyList())

    val storage = StorageRepository(appContext, preferences)
    val remoteProviders = RemoteProviderCoordinator(
        storage,
        networkConnections,
        secureCredentials,
        remoteFileSystems,
        directCloudAdapters,
    )
    val directCloudAccounts = DirectCloudAccountManager(
        networkConnections,
        secureCredentials,
        directCloudAdapters,
        directCloudOAuthDrivers,
        onAccountsChanged = remoteProviders::syncSavedConnections,
    )
    val networkConnectionManager = NetworkConnectionManager(networkConnections, secureCredentials)
    val remoteConnectionService = RemoteConnectionService(
        networkConnections,
        networkConnectionManager,
        secureCredentials,
        remoteFileSystems,
        remoteProviders,
    )
    val lanDiscovery = LanDiscoveryService(appContext)

    init {
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

    val step4WriteJournal = Step4WriteJournal(appContext)
    val safeOutputWriter = SafeOutputWriter(storage, step4WriteJournal)
    val archiveManager = ArchiveManager(appContext, storage, safeOutputWriter)
    val textFileEngine = TextFileEngine(storage, safeOutputWriter)
    val mediaInspector = MediaInspector(appContext, storage, storage)
    val apkManager = ApkManager(appContext, storage, safeOutputWriter)
    val storageAnalyzer = StorageAnalyzer(storage, storage)
}
