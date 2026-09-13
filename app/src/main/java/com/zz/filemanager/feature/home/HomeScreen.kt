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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zz.filemanager.R
import com.zz.filemanager.Step4ToolsActivity
import com.zz.filemanager.core.model.*
import com.zz.filemanager.core.storage.SafLocationKind
import com.zz.filemanager.core.util.Formatters
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenLocation: (BrowserLocation) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenRecent: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenRemote: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var pendingCategory by remember { mutableStateOf<MediaCategory?>(null) }
    var pendingSafKind by remember { mutableStateOf(SafLocationKind.GENERIC) }
    var reconnectTarget by remember { mutableStateOf<BrowserLocation?>(null) }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val reconnect = reconnectTarget
        reconnectTarget = null
        if (uri != null) {
            if (reconnect != null) viewModel.reconnectSafLocation(reconnect, uri)
            else viewModel.addSafLocation(uri, pendingSafKind)
        }
        pendingSafKind = SafLocationKind.GENERIC
    }
    val mediaPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val category = pendingCategory
        pendingCategory = null
        if (granted && category != null) onOpenLocation(viewModel.mediaLocation(category))
    }
    val legacyStorageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refresh()
        pendingCategory?.takeIf { viewModel.hasBroadStorageAccess() }?.let { pendingCategory = null; onOpenLocation(viewModel.mediaLocation(it)) }
    }
    val broadSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refresh()
        pendingCategory?.takeIf { viewModel.hasBroadStorageAccess() }?.let { pendingCategory = null; onOpenLocation(viewModel.mediaLocation(it)) }
    }
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
        if (category in setOf(MediaCategory.DOCUMENTS, MediaCategory.DOWNLOADS, MediaCategory.APKS)) {
            if (viewModel.hasBroadStorageAccess()) onOpenLocation(viewModel.mediaLocation(category)) else { pendingCategory = category; requestBroadAccess() }
            return
        }
        val permission = when {
            Build.VERSION.SDK_INT >= 33 && category == MediaCategory.IMAGES -> Manifest.permission.READ_MEDIA_IMAGES
            Build.VERSION.SDK_INT >= 33 && category == MediaCategory.VIDEOS -> Manifest.permission.READ_MEDIA_VIDEO
            Build.VERSION.SDK_INT >= 33 -> Manifest.permission.READ_MEDIA_AUDIO
            else -> Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) onOpenLocation(viewModel.mediaLocation(category))
        else { pendingCategory = category; mediaPermissionLauncher.launch(permission) }
    }
    fun addSaf(kind: SafLocationKind) { pendingSafKind = kind; reconnectTarget = null; treeLauncher.launch(null) }
    fun closeDrawerThen(action: () -> Unit) { scope.launch { drawerState.close(); action() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            HomeDrawer(
                state,
                { scope.launch { drawerState.close() } },
                { closeDrawerThen { onOpenLocation(it) } },
                { closeDrawerThen { openCategory(it) } },
                { closeDrawerThen(onOpenFavorites) },
                { closeDrawerThen(onOpenRecent) },
                { closeDrawerThen(onOpenTrash) },
                { closeDrawerThen(onOpenRemote) },
                { closeDrawerThen { context.startActivity(Step4ToolsActivity.intent(context, Step4ToolsActivity.MODE_ANALYZER)) } },
                { closeDrawerThen { context.startActivity(Step4ToolsActivity.intent(context, Step4ToolsActivity.MODE_APPS)) } },
            )
        },
    ) {
        Scaffold(topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton({ scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, stringResource(R.string.navigation_drawer)) } },
                actions = {
                    IconButton(onOpenSearch) { Icon(Icons.Default.Search, stringResource(R.string.search)) }
                    IconButton(onOpenSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF202522), titleContentColor = Color.White, navigationIconContentColor = Color.White, actionIconContentColor = Color.White),
            )
        }) { padding ->
            HomeContent(
                state, Modifier.fillMaxSize().padding(padding), onOpenLocation, ::openCategory,
                onOpenFavorites, onOpenRecent, onOpenTrash, onOpenRemote, ::addSaf,
                { reconnectTarget = it; treeLauncher.launch(null) }, ::requestBroadAccess,
                { context.startActivity(Step4ToolsActivity.intent(context, Step4ToolsActivity.MODE_APPS)) },
                { context.startActivity(Step4ToolsActivity.intent(context, Step4ToolsActivity.MODE_ANALYZER)) },
            )
        }
    }
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    modifier: Modifier,
    onOpenLocation: (BrowserLocation) -> Unit,
    onOpenCategory: (MediaCategory) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenRecent: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenRemote: () -> Unit,
    onAddSaf: (SafLocationKind) -> Unit,
    onReconnect: (BrowserLocation) -> Unit,
    onRequestBroadAccess: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenAnalyzer: () -> Unit,
) {
    val local = state.storageLocations.filter { it.type !in setOf(StorageType.NETWORK, StorageType.CLOUD) }
    val remote = state.storageLocations.filter { it.type in setOf(StorageType.NETWORK, StorageType.CLOUD) }
    val primary = local.firstOrNull { it.type == StorageType.INTERNAL } ?: local.firstOrNull()
    val tiles = listOf(
        QuickTile(stringResource(R.string.main_storage), Icons.Default.Storage, storageMetric(primary), { primary?.root?.let(onOpenLocation) }, primary != null),
        QuickTile(stringResource(R.string.downloads), Icons.Default.Download, metricText(state.categoryMetrics[MediaCategory.DOWNLOADS]), { onOpenCategory(MediaCategory.DOWNLOADS) }),
        QuickTile(stringResource(R.string.storage_analysis), Icons.Default.Analytics, primary?.usedPercentLabel(), onOpenAnalyzer),
        QuickTile(stringResource(R.string.images), Icons.Default.Image, metricText(state.categoryMetrics[MediaCategory.IMAGES]), { onOpenCategory(MediaCategory.IMAGES) }),
        QuickTile(stringResource(R.string.audio), Icons.Default.AudioFile, metricText(state.categoryMetrics[MediaCategory.AUDIO]), { onOpenCategory(MediaCategory.AUDIO) }),
        QuickTile(stringResource(R.string.videos), Icons.Default.VideoFile, metricText(state.categoryMetrics[MediaCategory.VIDEOS]), { onOpenCategory(MediaCategory.VIDEOS) }),
        QuickTile(stringResource(R.string.documents), Icons.Default.Description, metricText(state.categoryMetrics[MediaCategory.DOCUMENTS]), { onOpenCategory(MediaCategory.DOCUMENTS) }),
        QuickTile(stringResource(R.string.apps), Icons.Default.Android, null, onOpenApps),
        QuickTile(stringResource(R.string.new_files), Icons.Default.Schedule, stringResource(R.string.recently_added), onOpenRecent),
        QuickTile(stringResource(R.string.cloud), Icons.Default.Cloud, remote.count { it.type == StorageType.CLOUD }.takeIf { it > 0 }?.let { stringResource(R.string.saved_count, it) }, { onAddSaf(SafLocationKind.CLOUD) }),
        QuickTile(stringResource(R.string.remote), Icons.Default.Storage, remote.count { it.type == StorageType.NETWORK }.takeIf { it > 0 }?.let { stringResource(R.string.saved_count, it) }, onOpenRemote),
        QuickTile(stringResource(R.string.access_from), Icons.Default.FolderOpen, stringResource(R.string.add_location), { onAddSaf(SafLocationKind.GENERIC) }),
        QuickTile(stringResource(R.string.recycle_bin), Icons.Default.DeleteOutline, null, onOpenTrash),
        QuickTile(stringResource(R.string.favorites), Icons.Default.Star, null, onOpenFavorites),
        QuickTile(stringResource(R.string.activity_history), Icons.Default.History, null, onOpenRecent),
    )
    LazyColumn(modifier) {
        if (state.loading) item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item("quick-title") { SectionTitle(stringResource(R.string.quick_access)) }
        tiles.chunked(3).forEachIndexed { index, row -> item("row-$index") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { QuickAccessTile(it, Modifier.weight(1f)) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        } }
        if (!state.broadStorageAccess) item("permission") { PermissionStrip(onRequestBroadAccess) }
        if (local.size > 1 || state.safLocations.isNotEmpty()) {
            item("storage-title") { SectionTitle(stringResource(R.string.storage)) }
            items(local.drop(1), key = { it.id }) { StorageRow(it, onOpenLocation) }
            state.safLocations.filterNot { it.readable }.forEach { location -> item("reconnect:${location.storageId}") { UnavailableStorageRow(location, onReconnect) } }
            item("add") { Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton({ onAddSaf(SafLocationKind.SD_CARD) }) { Text(stringResource(R.string.add_sd)) }
                TextButton({ onAddSaf(SafLocationKind.USB) }) { Text(stringResource(R.string.add_usb)) }
                TextButton({ onAddSaf(SafLocationKind.GENERIC) }) { Text(stringResource(R.string.add_location)) }
            } }
        }
        if (state.recentLocations.isNotEmpty()) {
            item("recent-title") { SectionTitle(stringResource(R.string.recent_locations)) }
            items(state.recentLocations.take(6), key = { it.identity }) { CompactLocationRow(it, onOpenLocation) }
        }
        item("end") { Spacer(Modifier.height(18.dp)) }
    }
}

private data class QuickTile(val label: String, val icon: ImageVector, val metric: String?, val onClick: () -> Unit, val enabled: Boolean = true)

@Composable private fun QuickAccessTile(tile: QuickTile, modifier: Modifier = Modifier) {
    Surface(modifier.padding(vertical = 3.dp).height(82.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxSize().clickable(enabled = tile.enabled, role = Role.Button, onClick = tile.onClick).testTag("home-quick-${tile.label}").padding(8.dp, 9.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(tile.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(25.dp))
            Column { Text(tile.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(tile.metric ?: stringResource(R.string.metric_unavailable), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
    }
}

@Composable private fun HomeDrawer(state: HomeUiState, onHome: () -> Unit, onOpenLocation: (BrowserLocation) -> Unit, onOpenCategory: (MediaCategory) -> Unit, onFavorites: () -> Unit, onRecent: () -> Unit, onRecycle: () -> Unit, onRemote: () -> Unit, onAnalyzer: () -> Unit, onApps: () -> Unit) {
    val categories = listOf(Triple(MediaCategory.IMAGES, stringResource(R.string.images), Icons.Default.Image), Triple(MediaCategory.VIDEOS, stringResource(R.string.videos), Icons.Default.VideoFile), Triple(MediaCategory.AUDIO, stringResource(R.string.audio), Icons.Default.AudioFile), Triple(MediaCategory.DOCUMENTS, stringResource(R.string.documents), Icons.Default.Description), Triple(MediaCategory.APKS, stringResource(R.string.apks), Icons.Default.Android))
    ModalDrawerSheet {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(18.dp)); HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize()) {
            item { DrawerSection(stringResource(R.string.storage)) }; item { DrawerItem(stringResource(R.string.home), Icons.Default.Storage, true, onHome) }
            items(state.storageLocations, key = { it.id }) { DrawerItem(it.displayName, if (it.type == StorageType.CLOUD) Icons.Default.Cloud else Icons.Default.Storage, false, { onOpenLocation(it.root) }, if (!it.available) stringResource(R.string.offline) else it.usedPercentLabel()) }
            item { DrawerItem(stringResource(R.string.recycle_bin), Icons.Default.DeleteOutline, false, onRecycle) }
            item { DrawerSection(stringResource(R.string.categories)) }; items(categories, key = { it.first.name }) { (category, label, icon) -> DrawerItem(label, icon, false, { onOpenCategory(category) }) }
            item { DrawerSection(stringResource(R.string.accounts_remote)) }; item { DrawerItem(stringResource(R.string.remote), Icons.Default.Cloud, false, onRemote) }
            item { DrawerSection(stringResource(R.string.tools)) }; item { DrawerItem(stringResource(R.string.storage_analysis), Icons.Default.Analytics, false, onAnalyzer) }; item { DrawerItem(stringResource(R.string.apps), Icons.Default.Android, false, onApps) }; item { DrawerItem(stringResource(R.string.favorites), Icons.Default.Star, false, onFavorites) }; item { DrawerItem(stringResource(R.string.recent), Icons.Default.History, false, onRecent) }
        }
    }
}

@Composable private fun DrawerSection(text: String) { Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 28.dp, top = 16.dp, bottom = 4.dp).semantics { heading() }) }
@Composable private fun DrawerItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit, supporting: String? = null) { NavigationDrawerItem(label = { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)); supporting?.let { Text(it, style = MaterialTheme.typography.labelSmall) } } }, icon = { Icon(icon, null) }, selected = selected, onClick = onClick, modifier = Modifier.padding(horizontal = 10.dp)) }
@Composable private fun PermissionStrip(onGrant: () -> Unit) { Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth().padding(8.dp)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Storage, null); Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(stringResource(R.string.full_storage_access), style = MaterialTheme.typography.titleSmall); Text(stringResource(R.string.full_storage_access_explanation), style = MaterialTheme.typography.bodySmall, maxLines = 2) }; Button(onGrant) { Text(stringResource(R.string.grant_access)) } } } }
@Composable private fun StorageRow(storage: StorageLocation, onOpen: (BrowserLocation) -> Unit) { Column(Modifier.fillMaxWidth().clickable(enabled = storage.available && storage.readable) { onOpen(storage.root) }.padding(horizontal = 14.dp, vertical = 9.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.primary); Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(storage.displayName); Text(storageMetric(storage) ?: stringResource(R.string.metric_unavailable), style = MaterialTheme.typography.bodySmall) }; storage.usedPercentLabel()?.let { Text(it) } }; val total = storage.totalBytes; val used = storage.usedBytes; if (total != null && used != null && total > 0) LinearProgressIndicator(progress = { (used.toDouble() / total).toFloat() }, modifier = Modifier.fillMaxWidth().padding(start = 40.dp, top = 5.dp)) }; HorizontalDivider(Modifier.padding(start = 54.dp)) }
@Composable private fun UnavailableStorageRow(location: BrowserLocation, reconnect: (BrowserLocation) -> Unit) { Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.error); Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(location.displayName); Text(stringResource(R.string.storage_unavailable), color = MaterialTheme.colorScheme.error) }; TextButton({ reconnect(location) }) { Text(stringResource(R.string.reconnect)) } } }
@Composable private fun CompactLocationRow(location: BrowserLocation, open: (BrowserLocation) -> Unit) { Row(Modifier.fillMaxWidth().clickable { open(location) }.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.FolderOpen, null); Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(location.displayName); Text(location.providerId, style = MaterialTheme.typography.bodySmall) } } }
@Composable private fun SectionTitle(text: String) { Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 5.dp).semantics { heading() }) }
private fun storageMetric(storage: StorageLocation?): String? = storage?.let { value -> value.usedBytes?.let { used -> value.totalBytes?.let { total -> "${Formatters.bytes(used)} / ${Formatters.bytes(total)}" } } }
private fun StorageLocation.usedPercentLabel(): String? { val used = usedBytes ?: return null; val total = totalBytes?.takeIf { it > 0 } ?: return null; return "${((used.toDouble() / total) * 100).toInt().coerceIn(0, 100)}% USED" }
private fun metricText(metric: CategoryMetric?): String? = when { metric == null || metric.refreshing -> null; metric.totalBytes != null && metric.itemCount != null -> "${Formatters.bytes(metric.totalBytes)} · ${metric.itemCount}"; metric.itemCount != null -> metric.itemCount.toString(); else -> null }
