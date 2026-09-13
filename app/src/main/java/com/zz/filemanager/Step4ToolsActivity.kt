@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.zz.filemanager

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ImageView
import android.graphics.drawable.Drawable
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zz.filemanager.app.AppContainer
import com.zz.filemanager.core.analyzer.AnalyzerSnapshot
import com.zz.filemanager.core.analyzer.DuplicateGroup
import com.zz.filemanager.core.analyzer.DuplicateMember
import com.zz.filemanager.core.analyzer.DuplicateProgress
import com.zz.filemanager.core.apk.ApkBackupMode
import com.zz.filemanager.core.apk.InstalledAppInfo
import com.zz.filemanager.core.archive.ArchiveCreateRequest
import com.zz.filemanager.core.archive.ArchiveFormat
import com.zz.filemanager.core.archive.ArchiveProgress
import com.zz.filemanager.core.archive.ArchiveSource
import com.zz.filemanager.core.archive.ZipCompressionLevel
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.StorageLocation
import com.zz.filemanager.core.step4.Step4OpenCodec
import com.zz.filemanager.core.trash.TrashResult
import com.zz.filemanager.core.util.Formatters
import com.zz.filemanager.core.security.SafeErrorMessage
import com.zz.filemanager.ui.theme.ZZFileManagerTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class Step4ToolsActivity : ComponentActivity() {
    private val container get() = (application as ZZFileManagerApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_APPS
        setContent {
            val theme by container.preferences.theme.collectAsStateWithLifecycle(initialValue = com.zz.filemanager.core.model.ThemeMode.SYSTEM)
            ZZFileManagerTheme(theme) {
                when (mode) {
                    MODE_ANALYZER -> AnalyzerTool(container, ::finish)
                    MODE_ARCHIVE_CREATE -> ArchiveCreatorTool(container, ::finish)
                    else -> AppsTool(container, ::finish)
                }
            }
        }
    }

    companion object {
        private const val EXTRA_MODE = "mode"
        const val MODE_APPS = "apps"
        const val MODE_ANALYZER = "analyzer"
        const val MODE_ARCHIVE_CREATE = "archive_create"

        fun intent(context: Context, mode: String): Intent = Intent(context, Step4ToolsActivity::class.java).putExtra(EXTRA_MODE, mode)
    }
}

@Composable
private fun AppsTool(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<InstalledAppInfo?>(null) }
    var pendingBackup by remember { mutableStateOf<Pair<InstalledAppInfo, ApkBackupMode>?>(null) }
    var tab by remember { mutableStateOf(AppTab.DOWNLOADED) }
    var sort by remember { mutableStateOf(AppSort.NAME) }
    var sortMenu by remember { mutableStateOf(false) }
    val destinationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val pending = pendingBackup
        pendingBackup = null
        if (uri != null && pending != null) scope.launch {
            try {
                val destination = container.storage.registerSafLocation(uri)
                val result = container.apkManager.backup(pending.first, destination, pending.second)
                snackbar.showSnackbar(
                    if (result.reinstallableSetPreserved) "APK backup completed" else "Base APK exported. This app uses splits, so base-only backup is not a complete reinstallable package.",
                )
            } catch (failure: Throwable) {
                snackbar.showSnackbar(SafeErrorMessage.from(failure, "APK backup failed"))
            }
        }
    }

    suspend fun refresh() {
        loading = true
        apps = runCatching { container.apkManager.installedApps(query) }.getOrDefault(emptyList())
        loading = false
    }
    LaunchedEffect(query) { refresh() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Installed Apps") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.Sort, "Sort apps") }
                    DropdownMenu(sortMenu, { sortMenu = false }) { AppSort.entries.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = { sort = option; sortMenu = false }) } }
                    IconButton(onClick = { scope.launch { refresh() } }) { Icon(Icons.Default.Refresh, "Refresh") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                singleLine = true,
            )
            Text(
                "Android package visibility rules may limit the list unless the platform exposes an app to this file manager. No broad QUERY_ALL_PACKAGES permission is used.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            TabRow(selectedTabIndex = tab.ordinal) { AppTab.entries.forEach { option -> Tab(selected = tab == option, onClick = { tab = option }, text = { Text(option.label) }) } }
            if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else LazyColumn(Modifier.fillMaxSize()) {
                val visibleApps = apps.filter { tab == AppTab.ALL || !it.isSystemApp }.let { list ->
                    when (sort) { AppSort.NAME -> list.sortedBy { it.label.lowercase() }; AppSort.SIZE -> list.sortedByDescending { it.totalApkSizeBytes ?: -1L }; AppSort.INSTALL_DATE -> list.sortedByDescending { it.firstInstallTime ?: 0L }; AppSort.UPDATE_DATE -> list.sortedByDescending { it.lastUpdateTime ?: 0L } }
                }
                items(visibleApps, key = { it.packageName }) { app ->
                    ListItem(
                        headlineContent = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${app.packageName} • ${app.versionName.orEmpty()} • ${app.totalApkSizeBytes?.let(Formatters::bytes) ?: "Unknown"}${if (app.isSystemApp) " • System" else " • Downloaded"}") },
                        leadingContent = { InstalledAppIcon(container, app) },
                        modifier = Modifier.clickable { selected = app },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    selected?.let { app ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(app.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(app.packageName)
                    Text("Version ${app.versionName.orEmpty()} (${app.versionCode})")
                    app.firstInstallTime?.let { Text("Installed ${DateFormat.getDateTimeInstance().format(Date(it))}") }
                    app.lastUpdateTime?.let { Text("Updated ${DateFormat.getDateTimeInstance().format(Date(it))}") }
                    Text("Base APK: ${app.baseApkSizeBytes?.let(Formatters::bytes) ?: "Unknown"}")
                    Text(if (app.hasSplits) "Split APK package: ${app.splitApkPaths.size} split component(s)" else "Single APK package")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = {
                            val launch = context.packageManager.getLaunchIntentForPackage(app.packageName)
                            if (launch != null) context.startActivity(launch)
                            else scope.launch { snackbar.showSnackbar("This application has no launchable activity") }
                        }) { Icon(Icons.Default.PlayArrow, null); Text("Launch") }
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")))
                        }) { Icon(Icons.Default.Info, null); Text("App Info") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = {
                            pendingBackup = app to ApkBackupMode.BASE_ONLY
                            selected = null
                            destinationLauncher.launch(null)
                        }) { Icon(Icons.Default.SaveAlt, null); Text("Base APK") }
                        OutlinedButton(onClick = {
                            pendingBackup = app to ApkBackupMode.COMPLETE_SPLITS
                            selected = null
                            destinationLauncher.launch(null)
                        }) { Icon(Icons.Default.Archive, null); Text("All splits") }
                    }
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")))
                        selected = null
                    }) { Icon(Icons.Default.Delete, null); Text("Uninstall through Android") }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } },
        )
    }
}

private enum class AppTab(val label: String) { DOWNLOADED("Downloaded"), ALL("All") }
private enum class AppSort(val label: String) { NAME("Name"), SIZE("Size"), INSTALL_DATE("Install date"), UPDATE_DATE("Update date") }

@Composable
private fun InstalledAppIcon(container: AppContainer, app: InstalledAppInfo) {
    var drawable by remember(app.packageName) { mutableStateOf<Drawable?>(null) }
    LaunchedEffect(app.packageName) { drawable = container.apkManager.installedAppIcon(app.packageName) }
    drawable?.let { icon ->
        AndroidView(factory = { ctx -> ImageView(ctx).apply { contentDescription = app.label } }, update = { it.setImageDrawable(icon) }, modifier = Modifier.size(42.dp))
    } ?: Icon(Icons.Default.Android, null, modifier = Modifier.size(42.dp))
}

@Composable
private fun AnalyzerTool(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var roots by remember { mutableStateOf<List<StorageLocation>>(emptyList()) }
    var selectedRoot by remember { mutableStateOf<BrowserLocation?>(null) }
    var snapshot by remember { mutableStateOf<AnalyzerSnapshot?>(null) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var duplicateJob by remember { mutableStateOf<Job?>(null) }
    var duplicateProgress by remember { mutableStateOf<DuplicateProgress?>(null) }
    var duplicates by remember { mutableStateOf<List<DuplicateGroup>>(emptyList()) }
    var duplicateProperties by remember { mutableStateOf<DuplicateMember?>(null) }
    var rootMenu by remember { mutableStateOf(false) }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            runCatching { container.storage.registerSafLocation(uri) }
                .onSuccess { selectedRoot = it }
                .onFailure { snackbar.showSnackbar(it.message ?: "Could not access selected folder") }
        }
    }
    LaunchedEffect(Unit) {
        roots = runCatching { container.storage.discoverStorageLocations().filter { it.available && it.readable } }.getOrDefault(emptyList())
        if (selectedRoot == null) selectedRoot = roots.firstOrNull()?.root
    }

    fun startScan() {
        val root = selectedRoot ?: return
        scanJob?.cancel()
        duplicates = emptyList()
        snapshot = null
        scanJob = scope.launch {
            try {
                snapshot = container.storageAnalyzer.scan(root) { partial -> snapshot = partial }
            } catch (_: CancellationException) {
                snackbar.showSnackbar("Storage scan cancelled")
            } catch (failure: Throwable) {
                snackbar.showSnackbar(failure.message ?: "Storage scan failed")
            } finally { scanJob = null }
        }
    }

    fun startDuplicates() {
        val root = selectedRoot ?: return
        duplicateJob?.cancel()
        duplicates = emptyList()
        duplicateProgress = null
        duplicateJob = scope.launch {
            try {
                duplicates = container.storageAnalyzer.findDuplicates(root) { duplicateProgress = it }
                snackbar.showSnackbar("Duplicate verification completed")
            } catch (_: CancellationException) {
                snackbar.showSnackbar("Duplicate scan cancelled")
            } catch (failure: Throwable) {
                snackbar.showSnackbar(failure.message ?: "Duplicate scan failed")
            } finally { duplicateJob = null }
        }
    }

    fun openMember(member: DuplicateMember) {
        val internal = Step4OpenCodec.supports(member.entry)
        val request = if (internal) Step4OpenCodec.request(member.entry, member.parent) else container.storage.openRequest(member.entry)
        if (request == null) {
            scope.launch { snackbar.showSnackbar("This file cannot be opened") }
            return
        }
        runCatching {
            if (internal) {
                context.startActivity(Intent(context, Step4FileActivity::class.java).setData(Uri.parse(request.uri)))
            } else {
                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(request.uri), request.mimeType ?: "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            }
        }.onFailure { scope.launch { snackbar.showSnackbar("No compatible viewer is available") } }
    }

    fun revealMember(member: DuplicateMember) {
        scope.launch {
            container.storage.remember(member.parent)
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        }
    }

    fun favoriteMember(member: DuplicateMember) {
        scope.launch {
            container.userLibraryManager.toggleFavorite(member.entry, member.parent)
            snackbar.showSnackbar("Favorite updated")
        }
    }

    fun recycleMember(member: DuplicateMember) {
        scope.launch {
            when (container.trashManager.trash(member.entry, member.parent)) {
                is TrashResult.Success, is TrashResult.Queued -> {
                    duplicates = duplicates.mapNotNull { group ->
                        if (group.sha256 != member.sha256 || group.sizeBytes != member.entry.sizeBytes) group
                        else group.copy(members = group.members.filterNot { it.entry.id == member.entry.id }).takeIf { it.members.size >= 2 }
                    }
                    snackbar.showSnackbar("Moved to Recycle Bin")
                }
                else -> snackbar.showSnackbar("Could not move this duplicate to Recycle Bin")
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("Analyze Storage") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    OutlinedButton(onClick = { rootMenu = true }) { Icon(Icons.Default.Storage, null); Text(selectedRoot?.displayName ?: "Choose storage") }
                    DropdownMenu(rootMenu, { rootMenu = false }) {
                        roots.forEach { storage -> DropdownMenuItem(text = { Text(storage.displayName) }, onClick = { selectedRoot = storage.root; rootMenu = false }) }
                    }
                }
                OutlinedButton(onClick = { treeLauncher.launch(null) }) { Text("Choose folder") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = selectedRoot != null && scanJob == null, onClick = ::startScan) { Text("Scan") }
                if (scanJob != null) OutlinedButton(onClick = { scanJob?.cancel() }) { Icon(Icons.Default.Cancel, null); Text("Cancel") }
                Button(enabled = selectedRoot != null && duplicateJob == null, onClick = ::startDuplicates) { Text("Find duplicates") }
                if (duplicateJob != null) OutlinedButton(onClick = { duplicateJob?.cancel() }) { Text("Cancel duplicates") }
            }
            snapshot?.let { result ->
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp))
                Text(
                    "${result.progress.filesScanned} files • ${result.progress.directoriesScanned} folders • ${Formatters.bytes(result.progress.bytesAccounted)}${if (result.complete) " • Complete" else " • Scanning ${result.progress.currentLocation.orEmpty()}"}",
                    modifier = Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            duplicateProgress?.let { progress ->
                Text("Duplicate phase ${progress.phase}/3 • ${progress.candidates} candidates • ${Formatters.bytes(progress.bytesHashed)} hashed", modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                snapshot?.let { result ->
                    item { AnalyzerOverview(result) }
                    item { Section("Categories") }
                    result.categoryBytes.toList().sortedByDescending { it.second }.forEach { (category, bytes) -> item { ResultRow(category.name.replace('_', ' '), Formatters.bytes(bytes)) } }
                    item { Section("Largest files") }
                    items(result.largestFiles.take(50), key = { it.entry.id }) { file -> ResultRow(file.entry.name, Formatters.bytes(file.sizeBytes)) }
                    item { Section("Largest folders") }
                    items(result.largestFolders.take(50), key = { it.location.identity }) { folder -> ResultRow(folder.location.displayName, Formatters.bytes(folder.sizeBytes)) }
                    item { Section("Empty items") }
                    item { ResultRow("Empty files", result.totalEmptyFiles.toString()); ResultRow("Empty folders", result.totalEmptyDirectories.toString()) }
                    item { Section("Top extensions") }
                    result.extensionCounts.entries.take(30).forEach { (extension, count) -> item { ResultRow(extension, count.toString()) } }
                }
                if (duplicates.isNotEmpty()) {
                    item { Section("Verified duplicates") }
                    items(duplicates, key = { "${it.sizeBytes}:${it.sha256}" }) { group ->
                        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text("${group.members.size} exact copies • ${Formatters.bytes(group.sizeBytes)} each", style = MaterialTheme.typography.titleSmall)
                                Text("SHA-256 ${group.sha256.take(20)}…", style = MaterialTheme.typography.bodySmall)
                                group.members.forEach { member ->
                                    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                        Text(member.entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(member.parent.displayName, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                            TextButton(onClick = { openMember(member) }) { Text("Open") }
                                            TextButton(onClick = { revealMember(member) }) { Text("Reveal") }
                                            TextButton(onClick = { favoriteMember(member) }) { Icon(Icons.Default.StarBorder, null); Text("Favorite") }
                                            TextButton(onClick = { duplicateProperties = member }) { Text("Info") }
                                            TextButton(onClick = { recycleMember(member) }) { Text("Recycle") }
                                        }
                                    }
                                }
                                Text("Every group above passed full SHA-256 verification. Nothing is deleted automatically.", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }

    duplicateProperties?.let { member ->
        AlertDialog(
            onDismissRequest = { duplicateProperties = null },
            title = { Text("Duplicate properties") },
            text = {
                Column {
                    ResultRow("Name", member.entry.name)
                    ResultRow("Size", Formatters.bytes(member.entry.sizeBytes ?: 0L))
                    ResultRow("Folder", member.parent.displayName)
                    ResultRow("SHA-256", member.sha256)
                    member.entry.modifiedAtMillis?.let { ResultRow("Modified", DateFormat.getDateTimeInstance().format(Date(it))) }
                }
            },
            confirmButton = { TextButton(onClick = { duplicateProperties = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun AnalyzerOverview(result: AnalyzerSnapshot) {
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Overview", style = MaterialTheme.typography.titleMedium)
            ResultRow("Analyzed", Formatters.bytes(result.progress.bytesAccounted))
            ResultRow("Files", result.progress.filesScanned.toString())
            ResultRow("Folders", result.progress.directoriesScanned.toString())
            result.accessibleCapacityBytes?.let { ResultRow("Accessible capacity", Formatters.bytes(it)) }
            result.freeBytes?.let { ResultRow("Free space", Formatters.bytes(it)) }
            val total = result.accessibleCapacityBytes
            val free = result.freeBytes
            if (total != null && free != null && total > 0L) {
                val used = (total - free).coerceAtLeast(0L)
                ResultRow("Used", Formatters.bytes(used))
                Text("${((used.toDouble() / total) * 100).toInt().coerceIn(0, 100)}% used", style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(progress = { (used.toDouble() / total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ArchiveCreatorTool(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var sourceRoot by remember { mutableStateOf<BrowserLocation?>(null) }
    var sourceEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var destination by remember { mutableStateOf<BrowserLocation?>(null) }
    var archiveName by remember { mutableStateOf("Archive.zip") }
    var password by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(ArchiveFormat.ZIP) }
    var formatMenu by remember { mutableStateOf(false) }
    var compression by remember { mutableStateOf(ZipCompressionLevel.NORMAL) }
    var compressionMenu by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<ArchiveProgress?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    val sourceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val root = container.storage.registerSafLocation(uri)
                val entries = container.storage.listChildren(root)
                root to entries
            }.onSuccess { (root, entries) ->
                sourceRoot = root
                sourceEntries = entries
                selected = entries.map { it.id }.toSet()
            }.onFailure { snackbar.showSnackbar(it.message ?: "Could not read source folder") }
        }
    }
    val destinationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            runCatching { container.storage.registerSafLocation(uri) }
                .onSuccess { destination = it }
                .onFailure { snackbar.showSnackbar(it.message ?: "Could not access destination") }
        }
    }

    fun create() {
        val root = sourceRoot ?: return
        val output = destination ?: return
        val sources = sourceEntries.filter { it.id in selected }.map { ArchiveSource(it, root) }
        if (sources.isEmpty()) return
        job = scope.launch {
            try {
                val requestedName = normalizeArchiveName(archiveName, format)
                val request = ArchiveCreateRequest(
                    sources = sources,
                    destination = output,
                    archiveName = requestedName,
                    format = format,
                    zipCompressionLevel = compression,
                    password = password.takeIf { it.isNotBlank() && format == ArchiveFormat.ZIP }?.toCharArray(),
                )
                val result = container.archiveManager.create(request) { progress = it }
                snackbar.showSnackbar("Created ${result.name}")
            } catch (_: CancellationException) {
                snackbar.showSnackbar("Archive creation cancelled; partial output was cleaned")
            } catch (failure: Throwable) {
                snackbar.showSnackbar(failure.message ?: "Archive creation failed")
            } finally { job = null }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("Create Archive") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { sourceLauncher.launch(null) }) { Text(sourceRoot?.displayName ?: "Choose source folder") }
                OutlinedButton(onClick = { destinationLauncher.launch(null) }) { Text(destination?.displayName ?: "Choose destination") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(archiveName, { archiveName = it }, label = { Text("Archive name") }, singleLine = true, modifier = Modifier.weight(1f))
                Box {
                    OutlinedButton(onClick = { formatMenu = true }) { Text(format.name.replace('_', ' ')) }
                    DropdownMenu(formatMenu, { formatMenu = false }) {
                        listOf(ArchiveFormat.ZIP, ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2, ArchiveFormat.TAR_XZ).forEach { value ->
                            DropdownMenuItem(text = { Text(value.name.replace('_', ' ')) }, onClick = { format = value; formatMenu = false })
                        }
                    }
                }
            }
            if (format == ArchiveFormat.ZIP) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        OutlinedButton(onClick = { compressionMenu = true }) { Text("Compression: ${compression.name}") }
                        DropdownMenu(compressionMenu, { compressionMenu = false }) {
                            ZipCompressionLevel.entries.forEach { level -> DropdownMenuItem(text = { Text(level.name) }, onClick = { compression = level; compressionMenu = false }) }
                        }
                    }
                    OutlinedTextField(password, { password = it }, label = { Text("AES password (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = sourceRoot != null && destination != null && selected.isNotEmpty() && job == null, onClick = ::create) { Text("Create archive (${selected.size})") }
                if (job != null) OutlinedButton(onClick = { job?.cancel() }) { Text("Cancel") }
            }
            progress?.let {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
                Text("${it.phase}: ${it.currentPath.orEmpty()} • ${Formatters.bytes(it.processedBytes)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
            }
            Text("Selected source items", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(12.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(sourceEntries, key = { it.id }) { entry ->
                    Row(Modifier.fillMaxWidth().clickable { selected = toggle(selected, entry.id) }.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(entry.id in selected, onCheckedChange = { selected = toggle(selected, entry.id) })
                        Column(Modifier.weight(1f)) {
                            Text(entry.name)
                            Text(if (entry.isDirectory) "Folder" else entry.sizeBytes?.let(Formatters::bytes) ?: "File", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private fun toggle(values: Set<String>, value: String): Set<String> = if (value in values) values - value else values + value

private fun normalizeArchiveName(name: String, format: ArchiveFormat): String {
    val extension = when (format) {
        ArchiveFormat.ZIP -> ".zip"
        ArchiveFormat.TAR -> ".tar"
        ArchiveFormat.TAR_GZ -> ".tar.gz"
        ArchiveFormat.TAR_BZ2 -> ".tar.bz2"
        ArchiveFormat.TAR_XZ -> ".tar.xz"
        else -> ""
    }
    val clean = name.trim().ifBlank { "Archive" }
    return if (clean.lowercase().endsWith(extension)) clean else clean.substringBeforeLast('.', clean) + extension
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 14.dp, top = 16.dp, bottom = 6.dp))
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
