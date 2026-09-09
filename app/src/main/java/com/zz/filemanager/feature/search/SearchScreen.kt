@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.zz.filemanager.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zz.filemanager.R
import com.zz.filemanager.core.search.SearchMatchMode
import com.zz.filemanager.core.search.SearchResult
import com.zz.filemanager.core.search.SearchScope
import com.zz.filemanager.core.search.SearchSort
import com.zz.filemanager.core.search.SearchTypeFilter
import com.zz.filemanager.core.search.SearchUiState
import com.zz.filemanager.core.util.Formatters
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    onOpen: (SearchResult) -> Unit,
    onReveal: (SearchResult) -> Unit,
    onFavorite: (SearchResult) -> Unit,
    onClearHistory: () -> Unit,
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val nowMillis = remember { System.currentTimeMillis() }
    var advanced by remember { mutableStateOf(false) }
    val sevenDaysAgo = nowMillis - 7L * 24L * 60L * 60L * 1000L
    val thirtyDaysAgo = nowMillis - 30L * 24L * 60L * 60L * 1000L
    val results = when (state) {
        is SearchUiState.Searching -> (state as SearchUiState.Searching).results
        is SearchUiState.Results -> (state as SearchUiState.Results).results
        else -> emptyList()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query.text,
                onValueChange = viewModel::setText,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text(stringResource(R.string.search_files_folders)) },
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(SearchScope.entries) { scope ->
                    FilterChip(selected = query.scope == scope, onClick = { viewModel.setScope(scope) }, label = { Text(scopeLabel(scope)) })
                }
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(SearchSort.entries) { sort ->
                    FilterChip(selected = query.sort == sort, onClick = { viewModel.setSort(sort) }, label = { Text(searchSortLabel(sort)) })
                }
                item { FilterChip(selected = query.includeHidden, onClick = { viewModel.setHidden(!query.includeHidden) }, label = { Text(stringResource(R.string.include_hidden)) }) }
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { FilterChip(selected = query.minSizeBytes == null && query.maxSizeBytes == null, onClick = { viewModel.setSize(null, null) }, label = { Text(stringResource(R.string.size_any)) }) }
                item { FilterChip(selected = query.maxSizeBytes == 1_000_000L, onClick = { viewModel.setSize(null, 1_000_000L) }, label = { Text(stringResource(R.string.size_under_1_mb)) }) }
                item { FilterChip(selected = query.minSizeBytes == 1_000_000L && query.maxSizeBytes == 100_000_000L, onClick = { viewModel.setSize(1_000_000L, 100_000_000L) }, label = { Text(stringResource(R.string.size_1_to_100_mb)) }) }
                item { FilterChip(selected = query.minSizeBytes == 100_000_000L && query.maxSizeBytes == 1_000_000_000L, onClick = { viewModel.setSize(100_000_000L, 1_000_000_000L) }, label = { Text(stringResource(R.string.size_100_mb_to_1_gb)) }) }
                item { FilterChip(selected = query.minSizeBytes == 1_000_000_000L && query.maxSizeBytes == null, onClick = { viewModel.setSize(1_000_000_000L, null) }, label = { Text(stringResource(R.string.size_over_1_gb)) }) }
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { FilterChip(selected = query.modifiedAfter == null && query.modifiedBefore == null, onClick = { viewModel.setDates(null, null) }, label = { Text(stringResource(R.string.date_any)) }) }
                item { FilterChip(selected = query.modifiedAfter == sevenDaysAgo, onClick = { viewModel.setDates(sevenDaysAgo, nowMillis) }, label = { Text(stringResource(R.string.date_last_7_days)) }) }
                item { FilterChip(selected = query.modifiedAfter == thirtyDaysAgo, onClick = { viewModel.setDates(thirtyDaysAgo, nowMillis) }, label = { Text(stringResource(R.string.date_last_30_days)) }) }
                item { AssistChip(onClick = { advanced = true }, label = { Text(stringResource(R.string.advanced_filters)) }) }
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(SearchTypeFilter.entries) { type ->
                    FilterChip(selected = query.typeFilter == type, onClick = { viewModel.setType(type) }, label = { Text(typeLabel(type)) })
                }
            }
            when (val current = state) {
                SearchUiState.Idle -> LazyColumn {
                    if (history.isNotEmpty()) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.recent_searches), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                TextButton(onClick = onClearHistory) { Text(stringResource(R.string.clear_search_history)) }
                            }
                        }
                        items(history, key = { it.normalizedQuery }) { item ->
                            ListItem(
                                headlineContent = { Text(item.displayQuery) },
                                leadingContent = { Icon(Icons.Default.History, null) },
                                modifier = Modifier.clickable { viewModel.setText(item.displayQuery); viewModel.submit() },
                            )
                        }
                    }
                }
                is SearchUiState.Searching -> {
                    SearchStatus(current.totalMatches, current.scanned, true, 0)
                    ResultList(results, onOpen, onReveal, onFavorite)
                }
                is SearchUiState.Results -> {
                    SearchStatus(current.totalMatches, current.scanned, false, current.failures.size)
                    ResultList(results, onOpen, onReveal, onFavorite)
                }
                is SearchUiState.Empty -> EmptySearch(current.scanned, current.failures.size)
                is SearchUiState.Error -> Column(Modifier.padding(24.dp)) { Text(current.message, color = MaterialTheme.colorScheme.error) }
                SearchUiState.Cancelled -> Column(Modifier.padding(24.dp)) { Text(stringResource(R.string.adjust_search_filters)) }
            }
        }
    }
    if (advanced) AdvancedFiltersDialog(
        query = query,
        onDismiss = { advanced = false },
        onApply = { extensions, min, max, after, before, files, folders, hidden, match ->
            viewModel.setExtensions(extensions)
            viewModel.setSize(min, max)
            viewModel.setDates(after, before)
            viewModel.setKinds(files, folders)
            viewModel.setHidden(hidden)
            viewModel.setMatchMode(match)
            advanced = false
        },
    )
}

@Composable
private fun AdvancedFiltersDialog(
    query: com.zz.filemanager.core.search.FileSearchQuery,
    onDismiss: () -> Unit,
    onApply: (Set<String>, Long?, Long?, Long?, Long?, Boolean, Boolean, Boolean, SearchMatchMode) -> Unit,
) {
    var extensions by remember(query) { mutableStateOf(query.extensions.joinToString(", ")) }
    var minMb by remember(query) { mutableStateOf(query.minSizeBytes?.div(1_000_000L)?.toString().orEmpty()) }
    var maxMb by remember(query) { mutableStateOf(query.maxSizeBytes?.div(1_000_000L)?.toString().orEmpty()) }
    var after by remember(query) { mutableStateOf(query.modifiedAfter?.let(::formatDate).orEmpty()) }
    var before by remember(query) { mutableStateOf(query.modifiedBefore?.let(::formatDate).orEmpty()) }
    var files by remember(query) { mutableStateOf(query.includeFiles) }
    var folders by remember(query) { mutableStateOf(query.includeDirectories) }
    var hidden by remember(query) { mutableStateOf(query.includeHidden) }
    var match by remember(query) { mutableStateOf(query.matchMode) }
    val parsedAfter = parseDate(after)
    val parsedBefore = parseDate(before)
    val valid = (files || folders) && parsedAfter !== InvalidDate && parsedBefore !== InvalidDate
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.advanced_filters)) },
        text = {
            LazyColumn {
                item { OutlinedTextField(extensions, { extensions = it }, label = { Text(stringResource(R.string.extensions_hint)) }) }
                item { OutlinedTextField(minMb, { minMb = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.minimum_size_mb)) }) }
                item { OutlinedTextField(maxMb, { maxMb = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.maximum_size_mb)) }) }
                item { OutlinedTextField(after, { after = it }, label = { Text(stringResource(R.string.modified_after_date)) }) }
                item { OutlinedTextField(before, { before = it }, label = { Text(stringResource(R.string.modified_before_date)) }) }
                item { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(files, { files = it }); Text(stringResource(R.string.include_files)) } }
                item { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(folders, { folders = it }); Text(stringResource(R.string.include_folders)) } }
                item { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(hidden, { hidden = it }); Text(stringResource(R.string.include_hidden)) } }
                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(SearchMatchMode.entries) { mode -> FilterChip(selected = match == mode, onClick = { match = mode }, label = { Text(matchLabel(mode)) }) } } }
            }
        },
        confirmButton = { Button(onClick = {
            val extensionSet = extensions.split(',', ' ', ';').map { it.trim().removePrefix(".") }.filter { it.isNotEmpty() }.toSet()
            onApply(
                extensionSet,
                minMb.toLongOrNull()?.times(1_000_000L),
                maxMb.toLongOrNull()?.times(1_000_000L),
                parsedAfter as? Long,
                parsedBefore as? Long,
                files, folders, hidden, match,
            )
        }, enabled = valid) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private object InvalidDate
private fun parseDate(value: String): Any? = if (value.isBlank()) null else runCatching {
    LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}.getOrElse { InvalidDate }
private fun formatDate(value: Long): String = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toLocalDate().toString()

@Composable
private fun SearchStatus(total: Long, scanned: Long, active: Boolean, failures: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (active) CircularProgressIndicator(Modifier.padding(end = 12.dp))
        Column {
            Text(stringResource(R.string.search_results_count, total), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.search_scanned_count, scanned), style = MaterialTheme.typography.bodySmall)
            if (failures > 0) Text(
                stringResource(if (failures == 1) R.string.search_partial_failure else R.string.search_partial_failures, failures),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ResultList(results: List<SearchResult>, onOpen: (SearchResult) -> Unit, onReveal: (SearchResult) -> Unit, onFavorite: (SearchResult) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(results, key = { it.id }) { result ->
            ListItem(
                headlineContent = { Text(result.name, maxLines = 1) },
                supportingContent = {
                    Column {
                        Text(result.relativePath, maxLines = 1)
                        result.sizeBytes?.let { Text(Formatters.bytes(it), style = MaterialTheme.typography.bodySmall) }
                    }
                },
                leadingContent = { Icon(if (result.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile, null) },
                trailingContent = {
                    Row {
                        IconButton(onClick = { onFavorite(result) }) { Icon(Icons.Default.StarBorder, stringResource(R.string.add_to_favorites)) }
                        AssistChip(onClick = { onReveal(result) }, label = { Text(stringResource(R.string.show_in_folder)) })
                    }
                },
                modifier = Modifier.clickable { onOpen(result) },
            )
        }
    }
}

@Composable private fun EmptySearch(scanned: Long, failures: Int) { Column(Modifier.padding(24.dp)) { Text(stringResource(R.string.no_search_results), style = MaterialTheme.typography.titleMedium); Text(stringResource(R.string.adjust_search_filters)); Text(stringResource(R.string.search_scanned_count, scanned)); if (failures > 0) Text(stringResource(if (failures == 1) R.string.search_partial_failure else R.string.search_partial_failures, failures)) } }
@Composable private fun scopeLabel(value: SearchScope) = stringResource(when (value) { SearchScope.CURRENT_FOLDER -> R.string.search_this_folder; SearchScope.CURRENT_FOLDER_RECURSIVE -> R.string.search_subfolders; SearchScope.CURRENT_STORAGE -> R.string.search_this_storage; SearchScope.ALL_ACCESSIBLE_LOCATIONS -> R.string.search_all_locations })
@Composable private fun typeLabel(value: SearchTypeFilter) = stringResource(when (value) { SearchTypeFilter.ALL -> R.string.search_type_all; SearchTypeFilter.FOLDERS -> R.string.search_type_folders; SearchTypeFilter.IMAGES -> R.string.search_type_images; SearchTypeFilter.VIDEOS -> R.string.search_type_videos; SearchTypeFilter.AUDIO -> R.string.search_type_audio; SearchTypeFilter.DOCUMENTS -> R.string.search_type_documents; SearchTypeFilter.ARCHIVES -> R.string.search_type_archives; SearchTypeFilter.APKS -> R.string.search_type_apks; SearchTypeFilter.OTHER -> R.string.search_type_other })
@Composable private fun searchSortLabel(value: SearchSort) = stringResource(when (value) { SearchSort.RELEVANCE -> R.string.sort_relevance; SearchSort.NAME -> R.string.sort_name; SearchSort.DATE -> R.string.sort_date; SearchSort.SIZE -> R.string.sort_size; SearchSort.TYPE -> R.string.sort_type; SearchSort.LOCATION -> R.string.sort_location })
@Composable private fun matchLabel(value: SearchMatchMode) = stringResource(when (value) { SearchMatchMode.CONTAINS -> R.string.match_contains; SearchMatchMode.STARTS_WITH -> R.string.match_starts_with; SearchMatchMode.EXACT -> R.string.match_exact })
