package com.zz.filemanager

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zz.filemanager.app.AppContainer
import com.zz.filemanager.core.model.ThemeMode
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.util.BrowserLocationCodec
import com.zz.filemanager.feature.browser.BrowserOperationsViewModel
import com.zz.filemanager.feature.browser.BrowserScreen
import com.zz.filemanager.feature.browser.BrowserViewModel
import com.zz.filemanager.feature.home.HomeScreen
import com.zz.filemanager.feature.home.HomeViewModel
import com.zz.filemanager.feature.settings.SettingsScreen
import com.zz.filemanager.feature.settings.SettingsViewModel
import com.zz.filemanager.feature.search.SearchScreen
import com.zz.filemanager.feature.search.SearchViewModel
import com.zz.filemanager.feature.library.FavoritesScreen
import com.zz.filemanager.feature.library.RecentScreen
import com.zz.filemanager.feature.library.LibraryViewModel
import com.zz.filemanager.feature.remote.RemoteLocationsScreen
import com.zz.filemanager.feature.remote.RemoteLocationsViewModel
import com.zz.filemanager.feature.trash.RecycleBinScreen
import com.zz.filemanager.feature.trash.RecycleBinViewModel
import com.zz.filemanager.core.library.FavoriteItem
import com.zz.filemanager.core.library.RecentFile
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.FileEntryType
import com.zz.filemanager.core.search.SearchResult
import com.zz.filemanager.core.operation.ClipboardMode
import com.zz.filemanager.core.operation.OperationClipboard
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.zz.filemanager.ui.theme.ZZFileManagerTheme
import com.zz.filemanager.core.trash.TrashCleanupScheduler

@Composable
fun ZZFileManagerApp(container: AppContainer) {
    val theme by container.preferences.theme.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    ZZFileManagerTheme(theme) {
        val nav = rememberNavController()
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        fun openLocation(location: BrowserLocation) {
            nav.navigate("browser?location=${Uri.encode(BrowserLocationCodec.encode(location))}")
        }
        fun requestBroadAccess() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
        }
        fun openRequest(entry: FileEntry) {
            val request = container.storage.openRequest(entry) ?: return
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(request.uri), request.mimeType ?: "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            }
        }
        fun shareEntry(entry: FileEntry) {
            val request = container.storage.openRequest(entry) ?: return
            val uri = Uri.parse(request.uri)
            runCatching {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = request.mimeType ?: "*/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = android.content.ClipData.newUri(context.contentResolver, entry.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, context.getString(R.string.share)))
            }
        }
        fun searchResultEntry(result: SearchResult) = FileEntry(
            result.id, result.reference, result.name, result.name.substringAfterLast('.', "").takeIf { it.isNotEmpty() },
            result.mimeType, result.type, result.sizeBytes, result.modifiedAtMillis, null, result.name.startsWith('.'),
            result.readable, result.writable, null, result.storageId, null,
        )

        LaunchedEffect(Unit) {
            TrashCleanupScheduler.schedule(context)
            container.operationController.initialize()
            container.userLibrary.initialize()
            launch { container.operationLibrarySynchronizer.run() }
            container.trashManager.reconcile()
            val retentionDays = container.preferences.trashRetentionDays.first()
            container.trashManager.cleanupExpired(if (retentionDays < 0) Long.MAX_VALUE else retentionDays.toLong() * 24L * 60L * 60L * 1000L)
            container.storage.restorableLastLocation()?.let(::openLocation)
        }

        NavHost(navController = nav, startDestination = "home") {
            composable("home") {
                val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory(container.storage, container.preferences))
                HomeScreen(
                    vm, ::openLocation,
                    onOpenSettings = { nav.navigate("settings") },
                    onOpenSearch = { nav.navigate("search?location=") },
                    onOpenFavorites = { nav.navigate("favorites") },
                    onOpenRecent = { nav.navigate("recent") },
                    onOpenTrash = { nav.navigate("recycle") },
                    onOpenRemote = { nav.navigate("remote") },
                )
            }
            composable(
                route = "browser?location={location}",
                arguments = listOf(navArgument("location") { type = NavType.StringType }),
            ) { backStack ->
                val encoded = backStack.arguments?.getString("location").orEmpty()
                val location = BrowserLocationCodec.decode(encoded) ?: return@composable
                val vm: BrowserViewModel = viewModel(
                    key = "browser:${location.identity}",
                    factory = BrowserViewModel.Factory(container.storage, container.preferences, container.userLibraryManager),
                )
                val operationsVm: BrowserOperationsViewModel = viewModel(
                    key = "operations:${location.identity}",
                    factory = BrowserOperationsViewModel.Factory(
                        container.operationController,
                        container.operationClipboard,
                        container.storage,
                        container.userLibraryManager,
                        container.trashManager,
                        container.mediaStoreTrash,
                    ),
                )
                BrowserScreen(
                    viewModel = vm,
                    operationsViewModel = operationsVm,
                    initialLocation = location,
                    thumbnails = container.thumbnails,
                    onExitBrowser = { nav.popBackStack() },
                    onRequestStorageAccess = ::requestBroadAccess,
                    onOpenSearch = { searchLocation -> nav.navigate("search?location=${Uri.encode(BrowserLocationCodec.encode(searchLocation))}") },
                )
            }
            composable(
                route = "search?location={location}",
                arguments = listOf(navArgument("location") { type = NavType.StringType; defaultValue = "" }),
            ) { backStack ->
                val current = BrowserLocationCodec.decode(backStack.arguments?.getString("location").orEmpty())
                val vm: SearchViewModel = viewModel(factory = SearchViewModel.Factory(container.searchRepository, container.userLibrary, current, container.preferences.showHidden))
                SearchScreen(
                    vm,
                    onBack = { nav.popBackStack() },
                    onOpen = { result ->
                        if (result.isDirectory) openLocation(
                            result.parentLocation.copy(
                                id = result.id, displayName = result.name,
                                reference = result.reference.uri ?: result.reference.path ?: result.reference.opaqueId,
                                readable = result.readable, writable = result.writable,
                            ),
                        ) else {
                            val entry = searchResultEntry(result)
                            scope.launch { container.userLibraryManager.recordOpened(entry, result.parentLocation) }
                            openRequest(entry)
                        }
                    },
                    onReveal = { openLocation(it.parentLocation) },
                    onFavorite = { result -> scope.launch { container.userLibraryManager.toggleFavorite(searchResultEntry(result), result.parentLocation) } },
                    onCopy = { result ->
                        val entry = searchResultEntry(result)
                        container.operationClipboard.set(OperationClipboard(ClipboardMode.COPY, listOf(container.operationController.source(entry, result.parentLocation)), result.parentLocation, System.currentTimeMillis()))
                    },
                    onMove = { result ->
                        val entry = searchResultEntry(result)
                        container.operationClipboard.set(OperationClipboard(ClipboardMode.CUT, listOf(container.operationController.source(entry, result.parentLocation)), result.parentLocation, System.currentTimeMillis()))
                    },
                    onShare = { result -> shareEntry(searchResultEntry(result)) },
                    onTrash = { result ->
                        val entry = searchResultEntry(result)
                        if (entry.reference.providerId == "media" && entry.reference.uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            container.mediaStoreTrash.createRequest(listOf(Uri.parse(entry.reference.uri)), true)
                        } else {
                            scope.launch {
                                when (container.trashManager.trash(entry, result.parentLocation)) {
                                    is com.zz.filemanager.core.trash.TrashResult.Success,
                                    is com.zz.filemanager.core.trash.TrashResult.Queued -> vm.dismissResult(result.id)
                                    else -> Unit
                                }
                            }
                            null
                        }
                    },
                    onPlatformTrashConfirmed = { result -> scope.launch { container.trashManager.recordPlatformTrash(listOf(searchResultEntry(result)), result.parentLocation); vm.dismissResult(result.id) } },
                    onDeletePermanently = { result -> scope.launch { container.operationController.enqueueDelete(listOf(container.operationController.source(searchResultEntry(result), result.parentLocation))); vm.dismissResult(result.id) } },
                    onClearHistory = { scope.launch { container.userLibrary.clearSearchHistory() } },
                )
            }
            composable("favorites") {
                val vm: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory(container.userLibrary, container.userLibraryManager, container.preferences))
                FavoritesScreen(vm, onBack = { nav.popBackStack() }) { item: FavoriteItem ->
                    if (item.type == FileEntryType.DIRECTORY) {
                        openLocation(BrowserLocation(
                            item.reference.providerId, item.id, item.displayName,
                            item.reference.uri ?: item.reference.path ?: item.reference.opaqueId,
                            item.rootReference, item.storageId, true, true,
                        ))
                    } else {
                        val entry = FileEntry(item.id, item.reference, item.displayName, item.displayName.substringAfterLast('.', "").takeIf { it.isNotEmpty() }, null, item.type, null, null, null, false, true, false, null, item.storageId, null)
                        val parent = item.parentLocation ?: BrowserLocation(item.reference.providerId, "${item.reference.providerId}:${item.rootReference}", item.storageId, item.rootReference, item.rootReference, item.storageId, true, false)
                        scope.launch { container.userLibraryManager.recordOpened(entry, parent) }
                        openRequest(entry)
                    }
                }
            }
            composable("recent") {
                val vm: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory(container.userLibrary, container.userLibraryManager, container.preferences))
                RecentScreen(
                    vm, onBack = { nav.popBackStack() },
                    onOpenFile = { item: RecentFile ->
                        val entry = FileEntry(item.id, item.reference, item.displayName, item.displayName.substringAfterLast('.', "").takeIf { it.isNotEmpty() }, null, item.type, null, null, null, false, true, false, null, item.storageId, null)
                        val parent = item.parentLocation ?: BrowserLocation(item.reference.providerId, "${item.reference.providerId}:${item.rootReference}", item.storageId, item.rootReference, item.rootReference, item.storageId, true, false)
                        scope.launch { container.userLibraryManager.recordOpened(entry, parent) }
                        openRequest(entry)
                    },
                    onOpenLocation = ::openLocation,
                )
            }
            composable("recycle") {
                val vm: RecycleBinViewModel = viewModel(factory = RecycleBinViewModel.Factory(container.userLibrary, container.trashManager, container.mediaStoreTrash, container.storage))
                RecycleBinScreen(vm, onBack = { nav.popBackStack() })
            }
            composable("remote") {
                val vm: RemoteLocationsViewModel = viewModel(factory = RemoteLocationsViewModel.Factory(container.remoteConnectionService))
                RemoteLocationsScreen(
                    viewModel = vm,
                    onBack = { nav.popBackStack() },
                    onOpenLocation = ::openLocation,
                )
            }
            composable("settings") {
                val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(container.storage, container.preferences))
                SettingsScreen(vm, onBack = { nav.popBackStack() })
            }
        }
    }
}
