@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.zz.filemanager.feature.browser

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zz.filemanager.R
import com.zz.filemanager.core.model.Breadcrumb
import com.zz.filemanager.core.model.BrowserProblem
import com.zz.filemanager.core.model.BrowserUiState
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.FileEntryType
import com.zz.filemanager.core.model.SortField
import com.zz.filemanager.core.model.ViewMode
import com.zz.filemanager.core.util.Formatters
import com.zz.filemanager.core.util.ThumbnailRepository

@Composable
fun BrowserScreen(viewModel: BrowserViewModel, initialLocation: com.zz.filemanager.core.model.BrowserLocation, thumbnails: ThumbnailRepository, onExitBrowser: () -> Unit, onRequestStorageAccess: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var sortMenu by remember { mutableStateOf(false) }
    val active = state as? BrowserUiState.Content
    val empty = state as? BrowserUiState.Empty
    val canBack = active?.canGoBack ?: empty?.canGoBack ?: false
    BackHandler(enabled = canBack) { viewModel.goBack() }
    LaunchedEffect(initialLocation.identity) { viewModel.start(initialLocation) }
    LaunchedEffect(viewModel) { viewModel.events.collect { event -> when (event) { is BrowserEvent.OpenFile -> try { context.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.parse(event.request.uri), event.request.mimeType ?: "*/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) } catch (_: ActivityNotFoundException) { snackbar.showSnackbar(context.getString(R.string.no_viewer)) }; BrowserEvent.OpenFailed -> snackbar.showSnackbar(context.getString(R.string.unknown_error)) } } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        Box {
            BrowserTopBar(active?.location?.displayName ?: empty?.location?.displayName ?: initialLocation.displayName, active?.canGoUp ?: empty?.canGoUp ?: false, canBack, active?.canGoForward ?: empty?.canGoForward ?: false, active?.viewMode ?: empty?.viewMode ?: ViewMode.LIST, onExitBrowser, viewModel::goBack, viewModel::goForward, viewModel::goUp, viewModel::refresh, { mode -> viewModel.setViewMode(if (mode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST) }, { sortMenu = true })
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) { SortField.entries.forEach { field -> DropdownMenuItem(text = { Text(sortLabel(field)) }, onClick = { viewModel.setSortField(field); sortMenu = false }) }; DropdownMenuItem(text = { Text(stringResource(R.string.ascending) + " / " + stringResource(R.string.descending)) }, onClick = { viewModel.toggleSortDirection(); sortMenu = false }); DropdownMenuItem(text = { Text(stringResource(R.string.show_hidden)) }, onClick = { viewModel.toggleHidden(); sortMenu = false }) }
        }
    }) { padding -> Column(Modifier.fillMaxSize().padding(padding)) { when (val current = state) { BrowserUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; is BrowserUiState.Content -> { BreadcrumbBar(current.breadcrumbs, viewModel::navigateTo); if (current.viewMode == ViewMode.LIST) FileList(current.entries, thumbnails, viewModel::openEntry) else FileGrid(current.entries, thumbnails, viewModel::openEntry) }; is BrowserUiState.Empty -> { BreadcrumbBar(current.breadcrumbs, viewModel::navigateTo); Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.empty_folder)) } }; is BrowserUiState.Problem -> ProblemState(current.problem, onExitBrowser, onRequestStorageAccess) } } }
}

@Composable private fun BrowserTopBar(title: String, canUp: Boolean, canBack: Boolean, canForward: Boolean, viewMode: ViewMode, onExit: () -> Unit, onBack: () -> Unit, onForward: () -> Unit, onUp: () -> Unit, onRefresh: () -> Unit, onToggleView: (ViewMode) -> Unit, onSort: () -> Unit) { TopAppBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = { IconButton(onClick = if (canBack) onBack else onExit) { Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.home)) } }, actions = { if (canUp) IconButton(onClick = onUp) { Icon(Icons.Default.ArrowUpward, contentDescription = "Up") }; if (canForward) IconButton(onClick = onForward) { Icon(Icons.Default.ArrowForward, contentDescription = "Forward") }; IconButton(onClick = { onToggleView(viewMode) }) { Icon(if (viewMode == ViewMode.LIST) Icons.Default.GridView else Icons.Default.List, contentDescription = if (viewMode == ViewMode.LIST) stringResource(R.string.grid_view) else stringResource(R.string.list_view)) }; IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh)) }; IconButton(onClick = onSort) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.sort)) } }) }
@Composable private fun BreadcrumbBar(crumbs: List<Breadcrumb>, onClick: (com.zz.filemanager.core.model.BrowserLocation) -> Unit) { Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { crumbs.forEachIndexed { index, crumb -> TextButton(onClick = { onClick(crumb.location) }) { Text(crumb.label, maxLines = 1) }; if (index < crumbs.lastIndex) Text("›") } } }
@Composable private fun FileList(entries: List<FileEntry>, thumbnails: ThumbnailRepository, onClick: (FileEntry) -> Unit) { LazyColumn(Modifier.fillMaxSize()) { items(entries, key = { it.id }) { entry -> Row(Modifier.fillMaxWidth().clickable { onClick(entry) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { FileThumbnail(entry, thumbnails, 52); Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(metadata(entry), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) } } } } }
@Composable private fun FileGrid(entries: List<FileEntry>, thumbnails: ThumbnailRepository, onClick: (FileEntry) -> Unit) { LazyVerticalGrid(columns = GridCells.Adaptive(112.dp), modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)) { items(entries, key = { it.id }) { entry -> Column(Modifier.padding(6.dp).clickable { onClick(entry) }, horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) { FileThumbnail(entry, thumbnails, 88) }; Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) } } } }
@Composable private fun FileThumbnail(entry: FileEntry, repository: ThumbnailRepository, size: Int) { val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, entry.thumbnailKey, size) { value = repository.load(entry, size * 2, size * 2) }; val modifier = Modifier.size(size.dp).clip(MaterialTheme.shapes.small); if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop) else Icon(fileIcon(entry.type), null, modifier = modifier.padding(10.dp)) }
private fun fileIcon(type: FileEntryType) = when (type) { FileEntryType.DIRECTORY -> Icons.Default.Folder; FileEntryType.IMAGE -> Icons.Default.Image; FileEntryType.VIDEO -> Icons.Default.VideoFile; FileEntryType.AUDIO -> Icons.Default.AudioFile; FileEntryType.PDF -> Icons.Default.PictureAsPdf; FileEntryType.TEXT -> Icons.Default.TextSnippet; FileEntryType.DOCUMENT -> Icons.Default.Description; FileEntryType.SPREADSHEET -> Icons.Default.TableChart; FileEntryType.PRESENTATION -> Icons.Default.Slideshow; FileEntryType.ARCHIVE -> Icons.Default.Archive; FileEntryType.APK -> Icons.Default.Android; else -> Icons.Default.InsertDriveFile }
@Composable private fun ProblemState(problem: BrowserProblem, onExit: () -> Unit, onRequestStorageAccess: () -> Unit) { val text = when (problem) { BrowserProblem.PERMISSION_REQUIRED -> stringResource(R.string.permission_required); BrowserProblem.UNAVAILABLE -> stringResource(R.string.storage_unavailable); BrowserProblem.IO_ERROR -> stringResource(R.string.io_error); BrowserProblem.UNKNOWN -> stringResource(R.string.unknown_error) }; Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(text, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(16.dp)); if (problem == BrowserProblem.PERMISSION_REQUIRED) Button(onClick = onRequestStorageAccess) { Text(stringResource(R.string.grant_access)) }; TextButton(onClick = onExit) { Text(stringResource(R.string.home)) } } }
@Composable private fun sortLabel(field: SortField): String = when (field) { SortField.NAME -> stringResource(R.string.sort_name); SortField.DATE_MODIFIED -> stringResource(R.string.sort_date); SortField.SIZE -> stringResource(R.string.sort_size); SortField.TYPE -> stringResource(R.string.sort_type) }
private fun metadata(entry: FileEntry): String = when { entry.isDirectory -> "Folder"; entry.sizeBytes != null && entry.modifiedAtMillis != null -> "${Formatters.bytes(entry.sizeBytes)} • ${Formatters.dateTime(entry.modifiedAtMillis)}"; entry.sizeBytes != null -> Formatters.bytes(entry.sizeBytes); entry.modifiedAtMillis != null -> Formatters.dateTime(entry.modifiedAtMillis); else -> entry.type.name.lowercase().replaceFirstChar { it.titlecase() } }
