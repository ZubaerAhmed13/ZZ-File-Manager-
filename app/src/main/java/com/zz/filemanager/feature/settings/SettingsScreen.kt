@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.zz.filemanager.feature.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zz.filemanager.R
import com.zz.filemanager.core.model.ThemeMode
import com.zz.filemanager.core.model.ViewMode

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle(); val context = LocalContext.current; val lifecycleOwner = LocalLifecycleOwner.current
    val legacyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refreshStorageStatus() }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.refreshStorageStatus() }
    DisposableEffect(lifecycleOwner) { val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) viewModel.refreshStorageStatus() }; lifecycleOwner.lifecycle.addObserver(observer); onDispose { lifecycleOwner.lifecycle.removeObserver(observer) } }
    fun grant() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) settingsLauncher.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))) else legacyLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE) }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.home)) } }) }) { padding -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
        item { Header(stringResource(R.string.appearance)) }; ThemeMode.entries.forEach { mode -> item { ListItem(headlineContent = { Text(themeLabel(mode)) }, trailingContent = { RadioButton(selected = state.theme == mode, onClick = { viewModel.setTheme(mode) }) }) } }
        item { Header(stringResource(R.string.browser)) }; item { ListItem(headlineContent = { Text(stringResource(R.string.list_view)) }, trailingContent = { RadioButton(selected = state.viewMode == ViewMode.LIST, onClick = { viewModel.setViewMode(ViewMode.LIST) }) }) }; item { ListItem(headlineContent = { Text(stringResource(R.string.grid_view)) }, trailingContent = { RadioButton(selected = state.viewMode == ViewMode.GRID, onClick = { viewModel.setViewMode(ViewMode.GRID) }) }) }; item { ListItem(headlineContent = { Text(stringResource(R.string.show_hidden)) }, trailingContent = { Switch(checked = state.showHidden, onCheckedChange = viewModel::setShowHidden) }) }; item { ListItem(headlineContent = { Text(stringResource(R.string.folders_first)) }, trailingContent = { Switch(checked = state.foldersFirst, onCheckedChange = viewModel::setFoldersFirst) }) }
        item { Header(stringResource(R.string.recycle_retention)) }; listOf(-1, 7, 30, 60, 90).forEach { days -> item { ListItem(headlineContent = { Text(retentionLabel(days)) }, trailingContent = { RadioButton(selected = state.trashRetentionDays == days, onClick = { viewModel.setTrashRetentionDays(days) }) }) } }
        item { Header(stringResource(R.string.storage)) }; item { ListItem(headlineContent = { Text(stringResource(R.string.full_storage_access)) }, supportingContent = { Text(if (state.broadStorageAccess) stringResource(R.string.access_enabled) else stringResource(R.string.access_limited)) }, trailingContent = { if (!state.broadStorageAccess) androidx.compose.material3.TextButton(onClick = ::grant) { Text(stringResource(R.string.grant_access)) } }) }; items(state.safLocations, key = { it.rootReference }) { location -> ListItem(headlineContent = { Text(location.displayName) }, supportingContent = { Text(if (location.readable) stringResource(R.string.access_enabled) else stringResource(R.string.storage_unavailable)) }, trailingContent = { IconButton(onClick = { viewModel.removeSaf(location) }) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.remove_location)) } }) }
    } }
}
@Composable private fun Header(text: String) { Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)) }
@Composable private fun themeLabel(mode: ThemeMode): String = when (mode) { ThemeMode.SYSTEM -> stringResource(R.string.theme_system); ThemeMode.LIGHT -> stringResource(R.string.theme_light); ThemeMode.DARK -> stringResource(R.string.theme_dark) }
@Composable private fun retentionLabel(days: Int): String = when (days) { -1 -> stringResource(R.string.retention_never); else -> stringResource(R.string.retention_days, days) }
