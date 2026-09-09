@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.zz.filemanager.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zz.filemanager.R
import com.zz.filemanager.core.library.ActivityEntry
import com.zz.filemanager.core.library.FavoriteItem
import com.zz.filemanager.core.library.LibraryItemStatus
import com.zz.filemanager.core.library.RecentFile
import com.zz.filemanager.core.model.BrowserLocation

@Composable
fun FavoritesScreen(viewModel: LibraryViewModel, onBack: () -> Unit, onOpen: (FavoriteItem) -> Unit) {
    val items by viewModel.favorites.collectAsStateWithLifecycle()
    val sort by viewModel.favoriteSort.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.favorites)) }, navigationIcon = { Back(onBack) }) }) { padding ->
        if (items.isEmpty()) Text(stringResource(R.string.no_favorites), Modifier.padding(padding).padding(24.dp))
        else LazyColumn(Modifier.padding(padding)) {
            item {
                LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    items(FavoriteSort.entries) { option ->
                        FilterChip(selected = sort == option, onClick = { viewModel.setFavoriteSort(option) }, label = { Text(favoriteSortLabel(option)) })
                    }
                }
            }
            items(items, key = { it.id }) { item ->
                ListItem(
                    headlineContent = { Text(item.displayName, maxLines = 1) },
                    supportingContent = { StatusAndLocation(item.status, item.parentLocation?.displayName) },
                    leadingContent = { Icon(if (item.type == com.zz.filemanager.core.model.FileEntryType.DIRECTORY) Icons.Default.Folder else Icons.Default.Star, null) },
                    trailingContent = { TextButton(onClick = { viewModel.removeFavorite(item) }) { Text(stringResource(R.string.remove_from_favorites)) } },
                    modifier = Modifier.clickable(enabled = item.status == LibraryItemStatus.AVAILABLE) { onOpen(item) },
                )
            }
        }
    }
}

@Composable
fun RecentScreen(
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
    onOpenFile: (RecentFile) -> Unit,
    onOpenLocation: (BrowserLocation) -> Unit,
) {
    val files by viewModel.recentFiles.collectAsStateWithLifecycle()
    val locations by viewModel.recentLocations.collectAsStateWithLifecycle()
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.recent)) }, navigationIcon = { Back(onBack) }) }) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item { SectionHeader(R.string.recent_files, if (files.isEmpty()) null else R.string.clear_recent_files, viewModel::clearRecentFiles) }
            if (files.isEmpty()) item { Text(stringResource(R.string.no_recent_files), Modifier.padding(16.dp)) }
            items(files, key = { it.id }) { item ->
                ListItem(
                    headlineContent = { Text(item.displayName, maxLines = 1) },
                    supportingContent = { StatusAndLocation(item.status, item.parentLocation?.displayName) },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null) },
                    modifier = Modifier.clickable(enabled = item.status == LibraryItemStatus.AVAILABLE) { onOpenFile(item) },
                )
            }
            item { SectionHeader(R.string.recent_folders, if (locations.isEmpty()) null else R.string.clear_recent_folders, viewModel::clearRecentLocations) }
            items(locations, key = { it.identity }) { location ->
                ListItem(headlineContent = { Text(location.displayName) }, supportingContent = { Text(location.storageId) }, leadingContent = { Icon(Icons.Default.Folder, null) }, modifier = Modifier.clickable { onOpenLocation(location) })
            }
            item { SectionHeader(R.string.activity_history, if (activity.isEmpty()) null else R.string.clear_activity_history, viewModel::clearActivity) }
            items(activity, key = { it.id }) { entry -> ActivityRow(entry) }
        }
    }
}

@Composable private fun Back(onBack: () -> Unit) = IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
@Composable private fun StatusAndLocation(status: LibraryItemStatus, location: String?) { Column { location?.let { Text(it, maxLines = 1) }; if (status != LibraryItemStatus.AVAILABLE) Text(stringResource(if (status == LibraryItemStatus.TRASHED) R.string.in_recycle_bin else R.string.unavailable), color = MaterialTheme.colorScheme.error) } }
@Composable private fun SectionHeader(title: Int, clear: Int?, onClear: () -> Unit) { Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); if (clear != null) TextButton(onClick = onClear) { Text(stringResource(clear)) } } }
@Composable private fun ActivityRow(entry: ActivityEntry) { ListItem(headlineContent = { Text(entry.summary) }, supportingContent = { Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.occurredAtMillis))) }, leadingContent = { Icon(Icons.Default.History, null) }) }
@Composable private fun favoriteSortLabel(sort: FavoriteSort) = stringResource(when (sort) {
    FavoriteSort.NAME -> R.string.sort_name
    FavoriteSort.RECENTLY_ADDED -> R.string.recently_added
    FavoriteSort.RECENTLY_OPENED -> R.string.recently_opened
    FavoriteSort.TYPE -> R.string.sort_type
})
