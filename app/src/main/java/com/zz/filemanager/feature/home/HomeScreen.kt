@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.zz.filemanager.feature.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zz.filemanager.R
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.MediaCategory
import com.zz.filemanager.core.model.StorageLocation
import com.zz.filemanager.core.util.Formatters

@Composable
fun HomeScreen(viewModel: HomeViewModel, onOpenLocation: (BrowserLocation) -> Unit, onOpenSettings: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var pendingCategory by remember { mutableStateOf<MediaCategory?>(null) }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) viewModel.addSafLocation(uri) }
    val mediaPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val category = pendingCategory
        pendingCategory = null
        if (granted && category != null) onOpenLocation(viewModel.mediaLocation(category))
    }
    val legacyStorageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refresh() }
    val broadSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.refresh() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun requestBroadAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) broadSettingsLauncher.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
        else legacyStorageLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    fun openCategory(category: MediaCategory) {
        val permission = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && category == MediaCategory.IMAGES -> Manifest.permission.READ_MEDIA_IMAGES
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && category == MediaCategory.VIDEOS -> Manifest.permission.READ_MEDIA_VIDEO
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_AUDIO
            else -> Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) onOpenLocation(viewModel.mediaLocation(category))
        else { pendingCategory = category; mediaPermissionLauncher.launch(permission) }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }, actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings)) } }) }) { padding ->
        if (state.loading) Column(Modifier.padding(padding).fillMaxWidth()) { CircularProgressIndicator(Modifier.padding(32.dp)) }
        else LazyColumn(Modifier.padding(padding)) {
            if (!state.broadStorageAccess) item { PermissionCard(::requestBroadAccess) }
            item { SectionTitle(stringResource(R.string.storage)) }
            items(state.storageLocations, key = { it.id }) { StorageCard(it, onOpenLocation) }
            item { Button(onClick = { treeLauncher.launch(null) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.padding(4.dp)); Text(stringResource(R.string.add_location)) } }
            item { SectionTitle(stringResource(R.string.categories)) }
            item { Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                CategoryButton(MediaCategory.IMAGES, R.string.images, Icons.Default.Image, ::openCategory)
                CategoryButton(MediaCategory.VIDEOS, R.string.videos, Icons.Default.VideoFile, ::openCategory)
                CategoryButton(MediaCategory.AUDIO, R.string.audio, Icons.Default.AudioFile, ::openCategory)
            } }
            if (state.recentLocations.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.recent_locations)) }
                items(state.recentLocations, key = { it.identity }) { location ->
                    ListItem(headlineContent = { Text(location.displayName, maxLines = 1) }, supportingContent = { Text(location.providerId) }, leadingContent = { Icon(Icons.Default.FolderOpen, null) }, modifier = Modifier.fillMaxWidth().clickable { onOpenLocation(location) })
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable private fun PermissionCard(onGrant: () -> Unit) { Card(Modifier.fillMaxWidth().padding(16.dp)) { Column(Modifier.padding(16.dp)) { Row { Icon(Icons.Default.Storage, null); Spacer(Modifier.padding(4.dp)); Text(stringResource(R.string.full_storage_access), style = MaterialTheme.typography.titleMedium) }; Text(stringResource(R.string.full_storage_access_explanation), modifier = Modifier.padding(vertical = 8.dp)); Button(onClick = onGrant) { Text(stringResource(R.string.grant_access)) } } } }
@Composable private fun StorageCard(storage: StorageLocation, onOpen: (BrowserLocation) -> Unit) { Card(onClick = { onOpen(storage.root) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) { Column(Modifier.padding(16.dp)) { Row { Icon(Icons.Default.Storage, null); Spacer(Modifier.padding(4.dp)); Text(storage.displayName, style = MaterialTheme.typography.titleMedium) }; val total = storage.totalBytes; val used = storage.usedBytes; val free = storage.freeBytes; if (total != null && used != null && total > 0L) { Text(stringResource(R.string.used_of_total, Formatters.bytes(used), Formatters.bytes(total)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)); LinearProgressIndicator(progress = { (used.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)); if (free != null) Text(stringResource(R.string.available_space, Formatters.bytes(free)), style = MaterialTheme.typography.bodySmall) } } } }
@Composable private fun SectionTitle(text: String) { Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 8.dp)) }
@Composable private fun CategoryButton(category: MediaCategory, labelRes: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: (MediaCategory) -> Unit) { TextButton(onClick = { onClick(category) }) { Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) { Icon(icon, null); Text(stringResource(labelRes)) } } }
