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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.zz.filemanager.core.model.ThumbnailMode

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit, onClearThumbnailCache: () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var diagnostics by remember { mutableStateOf(false) }
    var cacheCleared by remember { mutableStateOf(false) }
    val legacyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refreshStorageStatus() }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.refreshStorageStatus() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshStorageStatus() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun grant() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            settingsLauncher.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
        } else {
            legacyLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { Header(stringResource(R.string.appearance)) }
            ThemeMode.entries.forEach { mode ->
                item {
                    ListItem(
                        headlineContent = { Text(themeLabel(mode)) },
                        trailingContent = { RadioButton(selected = state.theme == mode, onClick = null) },
                        modifier = Modifier.clickable { viewModel.setTheme(mode) },
                    )
                }
            }

            item { Header(stringResource(R.string.browser)) }
            ViewMode.entries.forEach { mode -> item(key = "settings-view:${mode.name}") {
                ListItem(headlineContent = { Text(viewModeLabel(mode)) }, trailingContent = { RadioButton(selected = state.viewMode == mode, onClick = null) }, modifier = Modifier.clickable { viewModel.setViewMode(mode) })
            } }
            item { ListItem(headlineContent = { Text(stringResource(R.string.show_thumbnails)) }, supportingContent = { Text(stringResource(R.string.thumbnail_behavior)) }, trailingContent = { Switch(checked = state.thumbnailMode == ThumbnailMode.SHOW, onCheckedChange = { viewModel.setThumbnailMode(if (it) ThumbnailMode.SHOW else ThumbnailMode.ICON_ONLY) }) }) }
            item { ListItem(headlineContent = { Text(stringResource(R.string.show_hidden)) }, trailingContent = { Switch(checked = state.showHidden, onCheckedChange = viewModel::setShowHidden) }) }
            item { ListItem(headlineContent = { Text(stringResource(R.string.folders_first)) }, trailingContent = { Switch(checked = state.foldersFirst, onCheckedChange = viewModel::setFoldersFirst) }) }

            item { Header("Network & transfers") }
            item {
                Text(
                    "Dangerous protocol state is always shown. These controls only tune transfer behavior and additional warnings.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            listOf(10_000, 15_000, 30_000, 60_000).forEach { millis ->
                item {
                    ListItem(
                        headlineContent = { Text("Connection timeout: ${millis / 1000}s") },
                        trailingContent = { RadioButton(selected = state.remote.connectTimeoutMillis == millis, onClick = { viewModel.setConnectTimeoutMillis(millis) }) },
                    )
                }
            }
            listOf(0, 1, 2, 3, 5).forEach { count ->
                item {
                    ListItem(
                        headlineContent = { Text("Transfer retries: $count") },
                        trailingContent = { RadioButton(selected = state.remote.retryCount == count, onClick = { viewModel.setRetryCount(count) }) },
                    )
                }
            }
            item { ListItem(headlineContent = { Text("Wi-Fi-only background transfers") }, trailingContent = { Switch(checked = state.remote.wifiOnlyBackgroundTransfers, onCheckedChange = viewModel::setWifiOnlyBackgroundTransfers) }) }
            item { ListItem(headlineContent = { Text("Warn on metered networks") }, trailingContent = { Switch(checked = state.remote.warnOnMeteredNetwork, onCheckedChange = viewModel::setWarnOnMeteredNetwork) }) }
            item { ListItem(headlineContent = { Text("Auto-resume interrupted transfers") }, supportingContent = { Text("Resume still requires source and staged-object identity proof.") }, trailingContent = { Switch(checked = state.remote.autoResumeInterruptedTransfers, onCheckedChange = viewModel::setAutoResumeInterruptedTransfers) }) }
            item { ListItem(headlineContent = { Text("Show hidden remote files") }, trailingContent = { Switch(checked = state.remote.showHiddenRemoteFiles, onCheckedChange = viewModel::setShowHiddenRemoteFiles) }) }
            item { ListItem(headlineContent = { Text("LAN discovery") }, supportingContent = { Text("Uses DNS-SD/mDNS only; no IP-range or port scanning.") }, trailingContent = { Switch(checked = state.remote.lanDiscoveryEnabled, onCheckedChange = viewModel::setLanDiscoveryEnabled) }) }
            item { ListItem(headlineContent = { Text("Extra insecure-protocol warnings") }, supportingContent = { Text("FTP and HTTP WebDAV remain visibly marked unencrypted even when this is off.") }, trailingContent = { Switch(checked = state.remote.warnOnInsecureProtocols, onCheckedChange = viewModel::setWarnOnInsecureProtocols) }) }

            item { Header(stringResource(R.string.recycle_retention)) }
            listOf(-1, 7, 30, 60, 90).forEach { days ->
                item { ListItem(headlineContent = { Text(retentionLabel(days)) }, trailingContent = { RadioButton(selected = state.trashRetentionDays == days, onClick = { viewModel.setTrashRetentionDays(days) }) }) }
            }

            item { Header(stringResource(R.string.storage)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.full_storage_access)) },
                    supportingContent = { Text(if (state.broadStorageAccess) stringResource(R.string.access_enabled) else stringResource(R.string.access_limited)) },
                    trailingContent = { if (!state.broadStorageAccess) androidx.compose.material3.TextButton(onClick = ::grant) { Text(stringResource(R.string.grant_access)) } },
                )
            }
            items(state.safLocations, key = { it.rootReference }) { location ->
                ListItem(
                    headlineContent = { Text(location.displayName) },
                    supportingContent = { Text(if (location.readable) stringResource(R.string.access_enabled) else stringResource(R.string.storage_unavailable)) },
                    trailingContent = { IconButton(onClick = { viewModel.removeSaf(location) }) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.remove_location)) } },
                )
            }
            item { Header(stringResource(R.string.advanced)) }
            item { ListItem(headlineContent = { Text(stringResource(R.string.diagnostics)) }, supportingContent = { Text(stringResource(R.string.diagnostics_summary)) }, modifier = Modifier.clickable { diagnostics = true }) }
            item { ListItem(headlineContent = { Text(stringResource(R.string.clear_thumbnail_cache)) }, supportingContent = { Text(stringResource(if (cacheCleared) R.string.cache_cleared else R.string.clear_thumbnail_cache_summary)) }, modifier = Modifier.clickable { onClearThumbnailCache(); cacheCleared = true }) }
        }
    }

    if (diagnostics) AlertDialog(
        onDismissRequest = { diagnostics = false },
        title = { Text(stringResource(R.string.diagnostics)) },
        text = { androidx.compose.foundation.layout.Column {
            Text("ZZ File Manager ${com.zz.filemanager.BuildConfig.VERSION_NAME}")
            Text("Android API ${Build.VERSION.SDK_INT}")
            Text("ABI ${Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown"}")
            Text("Storage access: ${if (state.broadStorageAccess) "Full" else "SAF / media only"}")
            Text("Authorized locations: ${state.safLocations.size}")
            Text("Saved remote locations are encrypted and are not displayed here.")
        } },
        confirmButton = { TextButton(onClick = { diagnostics = false }) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp))
}

@Composable
private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
    ThemeMode.LIGHT -> stringResource(R.string.theme_light)
    ThemeMode.DARK -> stringResource(R.string.theme_dark)
}

@Composable
private fun retentionLabel(days: Int): String = when (days) {
    -1 -> stringResource(R.string.retention_never)
    else -> stringResource(R.string.retention_days, days)
}

@Composable
private fun viewModeLabel(mode: ViewMode): String = stringResource(when (mode) {
    ViewMode.LIST -> R.string.list_view
    ViewMode.COMPACT_LIST -> R.string.compact_list
    ViewMode.GRID -> R.string.grid_view
    ViewMode.THUMBNAIL_GRID -> R.string.thumbnail_grid
    ViewMode.DETAILED_LIST -> R.string.detailed_list
})
