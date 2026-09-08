@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.zz.filemanager.feature.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zz.filemanager.R
import com.zz.filemanager.core.model.Breadcrumb
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.BrowserProblem
import com.zz.filemanager.core.model.BrowserUiState
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.FileEntryType
import com.zz.filemanager.core.model.OpenFileRequest
import com.zz.filemanager.core.model.SortField
import com.zz.filemanager.core.model.ViewMode
import com.zz.filemanager.core.operation.BatchRenameRule
import com.zz.filemanager.core.operation.ClipboardMode
import com.zz.filemanager.core.operation.CollisionPolicy
import com.zz.filemanager.core.operation.FileOperation
import com.zz.filemanager.core.operation.FileOperationState
import com.zz.filemanager.core.operation.FileOperationType
import com.zz.filemanager.core.operation.OperationFailureCode
import com.zz.filemanager.core.util.Formatters
import com.zz.filemanager.core.util.ThumbnailRepository

private enum class CreateKind { FOLDER, FILE }

@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    operationsViewModel: BrowserOperationsViewModel,
    initialLocation: BrowserLocation,
    thumbnails: ThumbnailRepository,
    onExitBrowser: () -> Unit,
    onRequestStorageAccess: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectedIds by operationsViewModel.selectedIds.collectAsStateWithLifecycle()
    val clipboard by operationsViewModel.clipboard.collectAsStateWithLifecycle()
    val operations by operationsViewModel.operations.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var menuExpanded by remember { mutableStateOf(false) }
    var selectionMenuExpanded by remember { mutableStateOf(false) }
    var deleteRequested by remember { mutableStateOf(false) }
    var renameRequested by remember { mutableStateOf(false) }
    var batchRenameRequested by remember { mutableStateOf(false) }
    var propertiesRequested by remember { mutableStateOf(false) }
    var createKind by remember { mutableStateOf<CreateKind?>(null) }
    var showOperations by remember { mutableStateOf(false) }
    var lastTerminalRefresh by remember { mutableLongStateOf(0L) }

    val content = state as? BrowserUiState.Content
    val empty = state as? BrowserUiState.Empty
    val currentLocation = content?.location ?: empty?.location
    val entries = content?.entries.orEmpty()
    val selectedEntries = operationsViewModel.selectedEntries(entries)
    val selectionActive = selectedIds.isNotEmpty()
    val canBack = content?.canGoBack ?: empty?.canGoBack ?: false
    val canUp = content?.canGoUp ?: empty?.canGoUp ?: false
    val canForward = content?.canGoForward ?: empty?.canGoForward ?: false
    val viewMode = content?.viewMode ?: empty?.viewMode ?: ViewMode.LIST
    val cutIds = clipboard?.takeIf { it.mode == ClipboardMode.CUT }?.sources?.mapTo(hashSetOf()) { it.reference.opaqueId }.orEmpty()
    val pendingCollisionOperation = operations.firstOrNull {
        it.state == FileOperationState.WAITING_FOR_USER && it.pendingCollision != null
    }

    BackHandler(enabled = selectionActive || canBack) {
        if (selectionActive) operationsViewModel.clearSelection() else viewModel.goBack()
    }

    LaunchedEffect(initialLocation.identity) { viewModel.start(initialLocation) }
    LaunchedEffect(entries.map { it.id }) { operationsViewModel.reconcileSelection(entries) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is BrowserEvent.OpenFile -> try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse(event.request.uri), event.request.mimeType ?: "*/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                    )
                } catch (_: ActivityNotFoundException) {
                    snackbar.showSnackbar(context.getString(R.string.no_viewer))
                }
                BrowserEvent.OpenFailed -> snackbar.showSnackbar(context.getString(R.string.unknown_error))
            }
        }
    }

    LaunchedEffect(operations) {
        val newest = operations.filter { it.state.isTerminal }.maxByOrNull { it.updatedAtMillis }
        if (newest != null && newest.updatedAtMillis > lastTerminalRefresh) {
            lastTerminalRefresh = newest.updatedAtMillis
            viewModel.refresh()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selectionActive) {
                SelectionTopBar(
                    selectedCount = selectedIds.size,
                    onClear = operationsViewModel::clearSelection,
                    onSelectAll = { operationsViewModel.selectAll(entries) },
                    onOpenOperations = { showOperations = true },
                )
            } else {
                BrowserTopBar(
                    title = currentLocation?.displayName ?: initialLocation.displayName,
                    canUp = canUp,
                    canBack = canBack,
                    canForward = canForward,
                    viewMode = viewMode,
                    operationCount = operations.count { !it.state.isTerminal },
                    onExit = onExitBrowser,
                    onBack = viewModel::goBack,
                    onForward = viewModel::goForward,
                    onUp = viewModel::goUp,
                    onRefresh = viewModel::refresh,
                    onToggleView = { mode -> viewModel.setViewMode(if (mode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST) },
                    onOpenOperations = { showOperations = true },
                    onMenu = { menuExpanded = true },
                )
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    SortField.entries.forEach { field ->
                        DropdownMenuItem(
                            text = { Text(sortLabel(field)) },
                            onClick = { viewModel.setSortField(field); menuExpanded = false },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ascending) + " / " + stringResource(R.string.descending)) },
                        onClick = { viewModel.toggleSortDirection(); menuExpanded = false },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.show_hidden)) },
                        onClick = { viewModel.toggleHidden(); menuExpanded = false },
                    )
                    if (currentLocation?.writable == true) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.new_folder)) },
                            leadingIcon = { Icon(Icons.Default.Folder, null) },
                            onClick = { createKind = CreateKind.FOLDER; menuExpanded = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.new_file)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null) },
                            onClick = { createKind = CreateKind.FILE; menuExpanded = false },
                        )
                    }
                }
            }
        },
        bottomBar = {
            when {
                selectionActive -> SelectionActionBar(
                    canDelete = selectedEntries.isNotEmpty() && selectedEntries.all { it.isWritable },
                    onCopy = { currentLocation?.let { operationsViewModel.copy(entries, it) } },
                    onMove = { currentLocation?.let { operationsViewModel.cut(entries, it) } },
                    onDelete = { deleteRequested = true },
                    onMore = { selectionMenuExpanded = true },
                )
                clipboard != null -> ClipboardBar(
                    clipboardMode = clipboard!!.mode,
                    count = clipboard!!.sources.size,
                    canPaste = currentLocation?.writable == true,
                    onPaste = {
                        if (currentLocation?.writable == true) operationsViewModel.paste(currentLocation)
                    },
                    onCancel = operationsViewModel::clearClipboard,
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                when (val current = state) {
                    BrowserUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    is BrowserUiState.Content -> {
                        BreadcrumbBar(current.breadcrumbs, viewModel::navigateTo)
                        if (current.viewMode == ViewMode.LIST) {
                            FileList(
                                entries = current.entries,
                                thumbnails = thumbnails,
                                selectedIds = selectedIds,
                                cutIds = cutIds,
                                onClick = { entry -> if (selectionActive) operationsViewModel.toggleSelection(entry) else viewModel.openEntry(entry) },
                                onLongClick = operationsViewModel::select,
                            )
                        } else {
                            FileGrid(
                                entries = current.entries,
                                thumbnails = thumbnails,
                                selectedIds = selectedIds,
                                cutIds = cutIds,
                                onClick = { entry -> if (selectionActive) operationsViewModel.toggleSelection(entry) else viewModel.openEntry(entry) },
                                onLongClick = operationsViewModel::select,
                            )
                        }
                    }
                    is BrowserUiState.Empty -> {
                        BreadcrumbBar(current.breadcrumbs, viewModel::navigateTo)
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.empty_folder)) }
                    }
                    is BrowserUiState.Problem -> ProblemState(current.problem, onExitBrowser, onRequestStorageAccess)
                }
            }

            DropdownMenu(
                expanded = selectionMenuExpanded,
                onDismissRequest = { selectionMenuExpanded = false },
                modifier = Modifier.align(Alignment.BottomEnd),
            ) {
                if (selectedEntries.size == 1 && selectedEntries.first().isWritable) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename)) },
                        onClick = { renameRequested = true; selectionMenuExpanded = false },
                    )
                }
                if (selectedEntries.size > 1 && selectedEntries.all { it.isWritable }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.batch_rename)) },
                        onClick = { batchRenameRequested = true; selectionMenuExpanded = false },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.share)) },
                    leadingIcon = { Icon(Icons.Default.Share, null) },
                    onClick = {
                        val requests = operationsViewModel.shareRequests(entries)
                        if (requests.isEmpty()) {
                            kotlinx.coroutines.MainScope().launch { snackbar.showSnackbar(context.getString(R.string.nothing_to_share)) }
                        } else {
                            share(context, requests)
                        }
                        selectionMenuExpanded = false
                    },
                )
                if (selectedEntries.size == 1) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.properties)) },
                        leadingIcon = { Icon(Icons.Default.Info, null) },
                        onClick = { propertiesRequested = true; selectionMenuExpanded = false },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.select_all)) },
                    leadingIcon = { Icon(Icons.Default.SelectAll, null) },
                    onClick = { operationsViewModel.selectAll(entries); selectionMenuExpanded = false },
                )
            }
        }
    }

    if (deleteRequested && currentLocation != null) {
        DeleteConfirmationDialog(
            selected = selectedEntries,
            onDismiss = { deleteRequested = false },
            onConfirm = {
                operationsViewModel.delete(entries, currentLocation)
                deleteRequested = false
            },
        )
    }

    if (renameRequested && selectedEntries.size == 1 && currentLocation != null) {
        RenameDialog(
            entry = selectedEntries.first(),
            onDismiss = { renameRequested = false },
            onConfirm = { newName ->
                operationsViewModel.rename(selectedEntries.first(), currentLocation, newName)
                renameRequested = false
            },
        )
    }

    if (batchRenameRequested && selectedEntries.size > 1 && currentLocation != null) {
        BatchRenameDialog(
            entries = entries,
            operationsViewModel = operationsViewModel,
            onDismiss = { batchRenameRequested = false },
            onApply = { previews ->
                operationsViewModel.batchRename(entries, currentLocation, previews)
                batchRenameRequested = false
            },
        )
    }

    if (propertiesRequested && selectedEntries.size == 1 && currentLocation != null) {
        PropertiesDialog(selectedEntries.first(), currentLocation) { propertiesRequested = false }
    }

    createKind?.let { kind ->
        currentLocation?.let { location ->
            CreateItemDialog(
                kind = kind,
                onDismiss = { createKind = null },
                onCreate = { name ->
                    if (kind == CreateKind.FOLDER) operationsViewModel.createDirectory(location, name)
                    else operationsViewModel.createFile(location, name)
                    createKind = null
                },
            )
        }
    }

    pendingCollisionOperation?.let { operation ->
        CollisionDialog(operation, operationsViewModel)
    }

    if (showOperations) {
        OperationsSheet(
            operations = operations,
            onDismiss = { showOperations = false },
            onPause = operationsViewModel::pause,
            onResume = operationsViewModel::resume,
            onCancel = operationsViewModel::cancel,
            onRetry = operationsViewModel::retry,
        )
    }
}

@Composable
private fun BrowserTopBar(
    title: String,
    canUp: Boolean,
    canBack: Boolean,
    canForward: Boolean,
    viewMode: ViewMode,
    operationCount: Int,
    onExit: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onToggleView: (ViewMode) -> Unit,
    onOpenOperations: () -> Unit,
    onMenu: () -> Unit,
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = if (canBack) onBack else onExit) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.home))
            }
        },
        actions = {
            if (canUp) IconButton(onClick = onUp) { Icon(Icons.Default.ArrowUpward, contentDescription = stringResource(R.string.navigate_up)) }
            if (canForward) IconButton(onClick = onForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.navigate_forward)) }
            IconButton(onClick = { onToggleView(viewMode) }) {
                Icon(
                    if (viewMode == ViewMode.LIST) Icons.Default.GridView else Icons.AutoMirrored.Filled.List,
                    contentDescription = if (viewMode == ViewMode.LIST) stringResource(R.string.grid_view) else stringResource(R.string.list_view),
                )
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh)) }
            IconButton(onClick = onOpenOperations) {
                Box(contentAlignment = Alignment.TopEnd) {
                    Icon(Icons.Default.Sync, contentDescription = stringResource(R.string.file_operations))
                    if (operationCount > 0) {
                        Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.primary) {
                            Text(
                                operationCount.coerceAtMost(99).toString(),
                                modifier = Modifier.padding(horizontal = 3.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onMenu) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more)) }
        },
    )
}

@Composable
private fun SelectionTopBar(selectedCount: Int, onClear: () -> Unit, onSelectAll: () -> Unit, onOpenOperations: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.selected_count, selectedCount)) },
        navigationIcon = { IconButton(onClick = onClear) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_selection)) } },
        actions = {
            IconButton(onClick = onSelectAll) { Icon(Icons.Default.SelectAll, contentDescription = stringResource(R.string.select_all)) }
            IconButton(onClick = onOpenOperations) { Icon(Icons.Default.Sync, contentDescription = stringResource(R.string.file_operations)) }
        },
    )
}

@Composable
private fun SelectionActionBar(canDelete: Boolean, onCopy: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit, onMore: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ActionButton(Icons.Default.ContentCopy, stringResource(R.string.copy), onCopy)
            ActionButton(Icons.Default.ContentCut, stringResource(R.string.move), onMove)
            ActionButton(Icons.Default.Delete, stringResource(R.string.delete), onDelete, enabled = canDelete)
            ActionButton(Icons.Default.MoreVert, stringResource(R.string.more), onMore)
        }
    }
}

@Composable
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ClipboardBar(clipboardMode: ClipboardMode, count: Int, canPaste: Boolean, onPaste: () -> Unit, onCancel: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (clipboardMode == ClipboardMode.COPY) Icons.Default.ContentCopy else Icons.Default.ContentCut, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                if (clipboardMode == ClipboardMode.COPY) stringResource(R.string.copy_ready, count) else stringResource(R.string.move_ready, count),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            Button(onClick = onPaste, enabled = canPaste) { Text(stringResource(R.string.paste)) }
        }
    }
}

@Composable
private fun BreadcrumbBar(crumbs: List<Breadcrumb>, onClick: (BrowserLocation) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { index, crumb ->
            TextButton(onClick = { onClick(crumb.location) }) { Text(crumb.label, maxLines = 1) }
            if (index < crumbs.lastIndex) Text("›")
        }
    }
}

@Composable
private fun FileList(
    entries: List<FileEntry>,
    thumbnails: ThumbnailRepository,
    selectedIds: Set<String>,
    cutIds: Set<String>,
    onClick: (FileEntry) -> Unit,
    onLongClick: (FileEntry) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(entries, key = { it.id }) { entry ->
            val selected = entry.id in selectedIds
            Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .alpha(if (entry.reference.opaqueId in cutIds) 0.55f else 1f)
                        .combinedClickable(onClick = { onClick(entry) }, onLongClick = { onLongClick(entry) })
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        FileThumbnail(entry, thumbnails, 52)
                        if (selected) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(metadata(entry), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun FileGrid(
    entries: List<FileEntry>,
    thumbnails: ThumbnailRepository,
    selectedIds: Set<String>,
    cutIds: Set<String>,
    onClick: (FileEntry) -> Unit,
    onLongClick: (FileEntry) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
    ) {
        items(entries, key = { it.id }) { entry ->
            val selected = entry.id in selectedIds
            Surface(
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.padding(4.dp).alpha(if (entry.reference.opaqueId in cutIds) 0.55f else 1f),
            ) {
                Column(
                    Modifier.combinedClickable(onClick = { onClick(entry) }, onLongClick = { onLongClick(entry) }).padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                        FileThumbnail(entry, thumbnails, 88)
                        if (selected) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.BottomEnd).size(22.dp))
                    }
                    Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun FileThumbnail(entry: FileEntry, repository: ThumbnailRepository, size: Int) {
    var bitmap by remember(entry.thumbnailKey, size) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(entry.thumbnailKey, size) { bitmap = repository.load(entry, size * 2, size * 2) }
    val modifier = Modifier.size(size.dp).clip(MaterialTheme.shapes.small)
    if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    else Icon(fileIcon(entry.type), null, modifier = modifier.padding(10.dp))
}

@Composable
private fun DeleteConfirmationDialog(selected: List<FileEntry>, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val message = if (selected.size == 1) stringResource(R.string.delete_one_permanently, selected.first().name)
    else stringResource(R.string.delete_many_permanently, selected.size)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_permanently)) },
        text = { Column { Text(message); Spacer(Modifier.height(8.dp)); Text(stringResource(R.string.delete_permanent_warning), color = MaterialTheme.colorScheme.error) } },
        confirmButton = { Button(onClick = onConfirm) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun RenameDialog(entry: FileEntry, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember(entry.id) { mutableStateOf(entry.name) }
    val valid = name.isNotBlank() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\')
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename)) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true) },
        confirmButton = { Button(onClick = { onConfirm(name) }, enabled = valid && name != entry.name) { Text(stringResource(R.string.rename)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CreateItemDialog(kind: CreateKind, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember(kind) { mutableStateOf("") }
    val valid = name.isNotBlank() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\')
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (kind == CreateKind.FOLDER) R.string.new_folder else R.string.new_file)) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true) },
        confirmButton = { Button(onClick = { onCreate(name) }, enabled = valid) { Text(stringResource(R.string.create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun BatchRenameDialog(
    entries: List<FileEntry>,
    operationsViewModel: BrowserOperationsViewModel,
    onDismiss: () -> Unit,
    onApply: (List<com.zz.filemanager.core.operation.BatchRenamePreview>) -> Unit,
) {
    var rule by remember { mutableStateOf(BatchRenameRule()) }
    val preview = operationsViewModel.previewBatchRename(entries, rule)
    val valid = preview.isNotEmpty() && preview.all { it.valid }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.batch_rename)) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(rule.find, { rule = rule.copy(find = it) }, label = { Text(stringResource(R.string.batch_find)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(rule.replaceWith, { rule = rule.copy(replaceWith = it) }, label = { Text(stringResource(R.string.batch_replace)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(rule.prefix, { rule = rule.copy(prefix = it) }, label = { Text(stringResource(R.string.batch_prefix)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(rule.suffix, { rule = rule.copy(suffix = it) }, label = { Text(stringResource(R.string.batch_suffix)) }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(rule.sequenceEnabled, { rule = rule.copy(sequenceEnabled = it) })
                    Text(stringResource(R.string.batch_numbering))
                }
                if (rule.sequenceEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            rule.sequenceStart.toString(),
                            { value -> value.toLongOrNull()?.let { rule = rule.copy(sequenceStart = it) } },
                            label = { Text(stringResource(R.string.batch_start)) },
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            rule.sequencePadding.toString(),
                            { value -> value.toIntOrNull()?.let { rule = rule.copy(sequencePadding = it.coerceIn(1, 12)) } },
                            label = { Text(stringResource(R.string.batch_padding)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.batch_preview), fontWeight = FontWeight.SemiBold)
                preview.forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(row.originalName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(row.proposedName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (row.valid) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        else Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                    }
                }
                if (!valid) Text(stringResource(R.string.batch_invalid_preview), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = { onApply(preview) }, enabled = valid) { Text(stringResource(R.string.rename)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PropertiesDialog(entry: FileEntry, location: BrowserLocation, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.properties)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PropertyRow(stringResource(R.string.name), entry.name)
                PropertyRow(stringResource(R.string.type), fileTypeLabel(entry.type))
                entry.sizeBytes?.let { PropertyRow(stringResource(R.string.size), Formatters.bytes(it)) }
                entry.modifiedAtMillis?.let { PropertyRow(stringResource(R.string.modified), Formatters.dateTime(it)) }
                PropertyRow(stringResource(R.string.location), location.displayName)
                PropertyRow(stringResource(R.string.readable), stringResource(if (entry.isReadable) R.string.yes else R.string.no))
                PropertyRow(stringResource(R.string.writable), stringResource(if (entry.isWritable) R.string.yes else R.string.no))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Column { Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value) }
}

@Composable
private fun CollisionDialog(operation: FileOperation, operationsViewModel: BrowserOperationsViewModel) {
    val collision = operation.pendingCollision ?: return
    var applyAll by remember(operation.id, collision.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.file_conflict)) },
        text = {
            Column {
                Text(stringResource(R.string.file_conflict_message, collision.sourceName, collision.destinationName))
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(applyAll, { applyAll = it })
                    Text(stringResource(R.string.apply_to_all))
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (CollisionPolicy.REPLACE in collision.allowedPolicies) TextButton(onClick = { operationsViewModel.resolveCollision(operation.id, CollisionPolicy.REPLACE, applyAll) }) { Text(stringResource(R.string.replace)) }
                if (CollisionPolicy.MERGE in collision.allowedPolicies) TextButton(onClick = { operationsViewModel.resolveCollision(operation.id, CollisionPolicy.MERGE, applyAll) }) { Text(stringResource(R.string.merge)) }
                if (CollisionPolicy.KEEP_BOTH in collision.allowedPolicies) TextButton(onClick = { operationsViewModel.resolveCollision(operation.id, CollisionPolicy.KEEP_BOTH, applyAll) }) { Text(stringResource(R.string.keep_both)) }
                if (CollisionPolicy.SKIP in collision.allowedPolicies) TextButton(onClick = { operationsViewModel.resolveCollision(operation.id, CollisionPolicy.SKIP, applyAll) }) { Text(stringResource(R.string.skip)) }
            }
        },
        dismissButton = { TextButton(onClick = { operationsViewModel.cancel(operation.id) }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun OperationsSheet(
    operations: List<FileOperation>,
    onDismiss: () -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.file_operations), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        if (operations.isEmpty()) {
            Text(stringResource(R.string.no_operations), modifier = Modifier.padding(24.dp))
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(operations.sortedByDescending { it.createdAtMillis }, key = { it.id }) { operation ->
                    OperationCard(operation, onPause, onResume, onCancel, onRetry)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun OperationCard(operation: FileOperation, onPause: (String) -> Unit, onResume: (String) -> Unit, onCancel: (String) -> Unit, onRetry: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(operationTypeLabel(operation.type), fontWeight = FontWeight.SemiBold)
                Text(operationStateLabel(operation.state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            operation.currentItemName?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(8.dp))
        if (operation.totalBytes != null && operation.totalBytes > 0L) {
            val ratio = (operation.processedBytes.toDouble() / operation.totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
            LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.transfer_progress_bytes, Formatters.bytes(operation.processedBytes), Formatters.bytes(operation.totalBytes)), style = MaterialTheme.typography.bodySmall)
        } else if (!operation.state.isTerminal && operation.state != FileOperationState.PAUSED && operation.state != FileOperationState.WAITING_FOR_USER) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        operation.totalItems?.let { total ->
            Text(stringResource(R.string.transfer_progress_items, operation.processedItems, total), style = MaterialTheme.typography.bodySmall)
        }
        if (operation.failedItems > 0L || operation.skippedItems > 0L) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (operation.completedItems > 0L) Text(stringResource(R.string.completed_items, operation.completedItems), style = MaterialTheme.typography.labelSmall)
                if (operation.skippedItems > 0L) Text(stringResource(R.string.skipped_items, operation.skippedItems), style = MaterialTheme.typography.labelSmall)
                if (operation.failedItems > 0L) Text(stringResource(R.string.failed_items, operation.failedItems), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
        operation.failure?.let { Text(operationFailureLabel(it.code), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when (operation.state) {
                FileOperationState.RUNNING, FileOperationState.PREPARING -> OutlinedButton(onClick = { onPause(operation.id) }) { Text(stringResource(R.string.pause)) }
                FileOperationState.PAUSED, FileOperationState.INTERRUPTED -> Button(onClick = { onResume(operation.id) }) { Text(stringResource(R.string.resume)) }
                FileOperationState.FAILED, FileOperationState.COMPLETED_WITH_WARNINGS -> OutlinedButton(onClick = { onRetry(operation.id) }) { Text(stringResource(R.string.retry)) }
                else -> Unit
            }
            if (!operation.state.isTerminal) OutlinedButton(onClick = { onCancel(operation.id) }) { Text(stringResource(R.string.cancel)) }
        }
    }
}

private fun share(context: Context, requests: List<OpenFileRequest>) {
    val intent = if (requests.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = requests.first().mimeType ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, Uri.parse(requests.first().uri))
        }
    } else {
        val types = requests.mapNotNull { it.mimeType }.distinct()
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (types.size == 1) types.first() else "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(requests.map { Uri.parse(it.uri) }))
        }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { context.startActivity(Intent.createChooser(intent, context.getString(R.string.share))) } catch (_: ActivityNotFoundException) { }
}

@Composable
private fun ProblemState(problem: BrowserProblem, onExit: () -> Unit, onRequestStorageAccess: () -> Unit) {
    val text = when (problem) {
        BrowserProblem.PERMISSION_REQUIRED -> stringResource(R.string.permission_required)
        BrowserProblem.UNAVAILABLE -> stringResource(R.string.storage_unavailable)
        BrowserProblem.IO_ERROR -> stringResource(R.string.io_error)
        BrowserProblem.UNKNOWN -> stringResource(R.string.unknown_error)
    }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        if (problem == BrowserProblem.PERMISSION_REQUIRED) Button(onClick = onRequestStorageAccess) { Text(stringResource(R.string.grant_access)) }
        TextButton(onClick = onExit) { Text(stringResource(R.string.home)) }
    }
}

private fun fileIcon(type: FileEntryType) = when (type) {
    FileEntryType.DIRECTORY -> Icons.Default.Folder
    FileEntryType.IMAGE -> Icons.Default.Image
    FileEntryType.VIDEO -> Icons.Default.VideoFile
    FileEntryType.AUDIO -> Icons.Default.AudioFile
    FileEntryType.PDF -> Icons.Default.PictureAsPdf
    FileEntryType.TEXT -> Icons.AutoMirrored.Filled.TextSnippet
    FileEntryType.DOCUMENT -> Icons.Default.Description
    FileEntryType.SPREADSHEET -> Icons.Default.TableChart
    FileEntryType.PRESENTATION -> Icons.Default.Slideshow
    FileEntryType.ARCHIVE -> Icons.Default.Archive
    FileEntryType.APK -> Icons.Default.Android
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

@Composable
private fun sortLabel(field: SortField): String = when (field) {
    SortField.NAME -> stringResource(R.string.sort_name)
    SortField.DATE_MODIFIED -> stringResource(R.string.sort_date)
    SortField.SIZE -> stringResource(R.string.sort_size)
    SortField.TYPE -> stringResource(R.string.sort_type)
}

@Composable
private fun metadata(entry: FileEntry): String = when {
    entry.isDirectory -> stringResource(R.string.folder)
    entry.sizeBytes != null && entry.modifiedAtMillis != null -> "${Formatters.bytes(entry.sizeBytes)} • ${Formatters.dateTime(entry.modifiedAtMillis)}"
    entry.sizeBytes != null -> Formatters.bytes(entry.sizeBytes)
    entry.modifiedAtMillis != null -> Formatters.dateTime(entry.modifiedAtMillis)
    else -> fileTypeLabel(entry.type)
}

@Composable
private fun fileTypeLabel(type: FileEntryType): String = when (type) {
    FileEntryType.DIRECTORY -> stringResource(R.string.folder)
    FileEntryType.IMAGE -> stringResource(R.string.file_type_image)
    FileEntryType.VIDEO -> stringResource(R.string.file_type_video)
    FileEntryType.AUDIO -> stringResource(R.string.file_type_audio)
    FileEntryType.PDF -> stringResource(R.string.file_type_pdf)
    FileEntryType.TEXT -> stringResource(R.string.file_type_text)
    FileEntryType.DOCUMENT -> stringResource(R.string.file_type_document)
    FileEntryType.SPREADSHEET -> stringResource(R.string.file_type_spreadsheet)
    FileEntryType.PRESENTATION -> stringResource(R.string.file_type_presentation)
    FileEntryType.ARCHIVE -> stringResource(R.string.file_type_archive)
    FileEntryType.APK -> stringResource(R.string.file_type_apk)
    FileEntryType.GENERIC, FileEntryType.UNKNOWN -> stringResource(R.string.file_type_unknown)
}

@Composable
private fun operationTypeLabel(type: FileOperationType): String = when (type) {
    FileOperationType.COPY -> stringResource(R.string.copying_files)
    FileOperationType.MOVE -> stringResource(R.string.moving_files)
    FileOperationType.DELETE -> stringResource(R.string.deleting_files)
    FileOperationType.RENAME, FileOperationType.BATCH_RENAME -> stringResource(R.string.renaming_files)
    FileOperationType.CREATE_DIRECTORY, FileOperationType.CREATE_FILE -> stringResource(R.string.creating_item)
}

@Composable
private fun operationStateLabel(state: FileOperationState): String = when (state) {
    FileOperationState.QUEUED -> stringResource(R.string.queued)
    FileOperationState.PREPARING -> stringResource(R.string.preparing_operation)
    FileOperationState.WAITING_FOR_USER -> stringResource(R.string.waiting_for_conflict_decision)
    FileOperationState.RUNNING, FileOperationState.PAUSING, FileOperationState.CANCELLING -> stringResource(R.string.running)
    FileOperationState.PAUSED -> stringResource(R.string.operation_paused)
    FileOperationState.CANCELLED -> stringResource(R.string.operation_cancelled)
    FileOperationState.COMPLETED -> stringResource(R.string.operation_complete)
    FileOperationState.COMPLETED_WITH_WARNINGS -> stringResource(R.string.operation_complete_warnings)
    FileOperationState.FAILED -> stringResource(R.string.operation_failed)
    FileOperationState.INTERRUPTED -> stringResource(R.string.operation_interrupted)
}

@Composable
private fun operationFailureLabel(code: OperationFailureCode): String = when (code) {
    OperationFailureCode.PERMISSION_DENIED -> stringResource(R.string.permission_denied_operation)
    OperationFailureCode.SOURCE_MISSING -> stringResource(R.string.source_missing_operation)
    OperationFailureCode.DESTINATION_MISSING -> stringResource(R.string.destination_missing_operation)
    OperationFailureCode.DESTINATION_READ_ONLY -> stringResource(R.string.destination_read_only_operation)
    OperationFailureCode.INSUFFICIENT_SPACE -> stringResource(R.string.insufficient_space_operation)
    OperationFailureCode.NAME_CONFLICT -> stringResource(R.string.name_conflict_operation)
    OperationFailureCode.INVALID_NAME -> stringResource(R.string.invalid_name_operation)
    OperationFailureCode.SELF_COPY -> stringResource(R.string.self_copy_operation)
    OperationFailureCode.DESCENDANT_TARGET -> stringResource(R.string.descendant_target_operation)
    OperationFailureCode.STORAGE_REMOVED, OperationFailureCode.PROVIDER_UNAVAILABLE -> stringResource(R.string.storage_removed_operation)
    OperationFailureCode.SOURCE_CHANGED -> stringResource(R.string.source_changed_operation)
    OperationFailureCode.SYMBOLIC_LINK_UNSUPPORTED -> stringResource(R.string.symbolic_link_operation)
    OperationFailureCode.IO_ERROR, OperationFailureCode.CANCELLED, OperationFailureCode.UNKNOWN -> stringResource(R.string.generic_operation_error)
}
