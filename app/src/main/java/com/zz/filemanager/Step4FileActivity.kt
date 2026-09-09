@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.zz.filemanager

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.zz.filemanager.app.AppContainer
import com.zz.filemanager.core.apk.ApkMetadata
import com.zz.filemanager.core.archive.ArchiveEntryInfo
import com.zz.filemanager.core.archive.ArchiveExtractRequest
import com.zz.filemanager.core.archive.ArchiveFailure
import com.zz.filemanager.core.archive.ArchiveListing
import com.zz.filemanager.core.archive.ArchiveProgress
import com.zz.filemanager.core.archive.ArchiveSource
import com.zz.filemanager.core.media.AudioMetadata
import com.zz.filemanager.core.media.ImageMetadata
import com.zz.filemanager.core.media.VideoMetadata
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.FileEntryType
import com.zz.filemanager.core.operation.CollisionPolicy
import com.zz.filemanager.core.step4.Step4OpenCodec
import com.zz.filemanager.core.text.TextDocument
import com.zz.filemanager.core.text.TextFailure
import com.zz.filemanager.core.text.TextOpenMode
import com.zz.filemanager.core.text.TextSearchMatch
import com.zz.filemanager.core.text.TextSource
import com.zz.filemanager.core.text.TextUndoRedoBuffer
import com.zz.filemanager.core.util.Formatters
import com.zz.filemanager.ui.theme.ZZFileManagerTheme
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Step4FileActivity : ComponentActivity() {
    private val container get() = (application as ZZFileManagerApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val decoded = intent.data?.let(Step4OpenCodec::decode)
        if (decoded == null) {
            finish()
            return
        }
        val (entry, parent) = decoded
        setContent {
            ZZFileManagerTheme(com.zz.filemanager.core.model.ThemeMode.SYSTEM) {
                Step4FileScreen(container, entry, parent, onBack = ::finish)
            }
        }
    }
}

@Composable
private fun Step4FileScreen(
    container: AppContainer,
    initialEntry: FileEntry,
    parent: BrowserLocation,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val snackbar = remember { SnackbarHostState() }
    var entry by remember(initialEntry.id) { mutableStateOf(initialEntry) }
    var showInfo by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var immersive by remember { mutableStateOf(false) }

    fun externalView() {
        val request = container.storage.openRequest(entry) ?: return
        val uri = Uri.parse(request.uri)
        runCatching {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, request.mimeType ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(context.contentResolver, entry.name, uri)
            }, "Open with"))
        }.onFailure { scope.launch { snackbar.showSnackbar("No compatible external app is installed") } }
    }

    fun share() {
        val request = container.storage.openRequest(entry) ?: return
        val uri = Uri.parse(request.uri)
        runCatching {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = request.mimeType ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newUri(context.contentResolver, entry.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share"))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (!immersive) TopAppBar(
                title = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { backDispatcher?.onBackPressed() ?: onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = share) { Icon(Icons.Default.Share, contentDescription = "Share") }
                    IconButton(onClick = { showInfo = true }) { Icon(Icons.Default.Info, contentDescription = "Properties") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.OpenInNew, contentDescription = "More actions") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Open with…") }, onClick = { menu = false; externalView() })
                        DropdownMenuItem(
                            text = { Text("Add to favorites") },
                            leadingIcon = { Icon(Icons.Default.StarBorder, null) },
                            onClick = {
                                menu = false
                                scope.launch { container.userLibraryManager.toggleFavorite(entry, parent) }
                            },
                        )
                        if (entry.isWritable) DropdownMenuItem(
                            text = { Text("Move to Recycle Bin") },
                            leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                            onClick = {
                                menu = false
                                scope.launch {
                                    val result = runCatching { container.trashManager.trash(entry, parent) }
                                    if (result.isSuccess) {
                                        snackbar.showSnackbar("Moved to Recycle Bin")
                                        onBack()
                                    } else snackbar.showSnackbar("Could not recycle this file")
                                }
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (entry.type) {
                FileEntryType.IMAGE -> ImageViewer(container, entry, parent) { next ->
                    entry = next
                    scope.launch { container.userLibraryManager.recordOpened(next, parent) }
                }
                FileEntryType.VIDEO -> MediaPlayer(container, entry, video = true, onFullscreenChanged = { immersive = it })
                FileEntryType.AUDIO -> MediaPlayer(container, entry, video = false, onFullscreenChanged = {})
                FileEntryType.TEXT -> TextViewerEditor(container, entry, parent, snackbar)
                FileEntryType.ARCHIVE -> ArchiveViewer(container, entry, parent, snackbar)
                FileEntryType.APK -> ApkViewer(container, entry, snackbar)
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = ::externalView) { Text("Open with another app") }
                }
            }
        }
    }

    if (showInfo) GenericProperties(entry, parent) { showInfo = false }
}

@Composable
private fun ImageViewer(
    container: AppContainer,
    entry: FileEntry,
    parent: BrowserLocation,
    onEntryChanged: (FileEntry) -> Unit,
) {
    var bitmap by remember(entry.id) { mutableStateOf<Bitmap?>(null) }
    var metadata by remember(entry.id) { mutableStateOf<ImageMetadata?>(null) }
    var siblings by remember(parent.identity) { mutableStateOf<List<FileEntry>>(emptyList()) }
    var error by remember(entry.id) { mutableStateOf<String?>(null) }
    var scale by remember(entry.id) { mutableFloatStateOf(1f) }
    var offset by remember(entry.id) { mutableStateOf(Offset.Zero) }
    var showMetadata by remember { mutableStateOf(false) }
    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 8f)
        offset = if (scale <= 1.01f) Offset.Zero else offset + panChange
    }

    LaunchedEffect(parent.identity) {
        siblings = runCatching {
            container.storage.providerFor(parent.providerId).listChildren(parent)
                .filter { it.type == FileEntryType.IMAGE && it.isReadable }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
        }.getOrDefault(emptyList())
    }
    LaunchedEffect(entry.id) {
        error = null
        bitmap = null
        runCatching {
            metadata = container.mediaInspector.image(entry)
            bitmap = withContext(Dispatchers.IO) { decodeSampled(container, entry, 4096, 4096) }
        }.onFailure { error = it.message ?: "Image could not be decoded" }
    }

    val index = siblings.indexOfFirst { it.id == entry.id }
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(entry.id) {
                    detectTapGestures(onDoubleTap = {
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2f
                    })
                }
                .transformable(transform),
            contentAlignment = Alignment.Center,
        ) {
            val current = bitmap
            when {
                current != null -> Image(
                    current.asImageBitmap(),
                    contentDescription = entry.name,
                    modifier = Modifier.fillMaxSize().graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
                )
                error != null -> Text(error!!, modifier = Modifier.padding(24.dp))
                else -> CircularProgressIndicator()
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(enabled = index > 0, onClick = { onEntryChanged(siblings[index - 1]) }) { Text("Previous") }
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
            OutlinedButton(enabled = index >= 0 && index < siblings.lastIndex, onClick = { onEntryChanged(siblings[index + 1]) }) { Text("Next") }
        }
        metadata?.let { meta ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${meta.width ?: "?"} × ${meta.height ?: "?"}${meta.cameraModel?.let { " • $it" }.orEmpty()}${if (meta.hasGps) " • GPS present" else ""}")
                    TextButton(onClick = { showMetadata = true }) { Text("Image info") }
                }
            }
        }
    }

    if (showMetadata) metadata?.let { meta ->
        AlertDialog(
            onDismissRequest = { showMetadata = false },
            title = { Text("Image information") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Property("Filename", entry.name)
                    Property("MIME", meta.mimeType ?: entry.mimeType ?: "Unknown")
                    Property("Dimensions", "${meta.width ?: "?"} × ${meta.height ?: "?"}")
                    meta.exifDate?.let { Property("EXIF date", it) }
                    meta.cameraMake?.let { Property("Camera make", it) }
                    meta.cameraModel?.let { Property("Camera model", it) }
                    meta.orientation?.let { Property("Orientation", it.toString()) }
                    meta.exposure?.let { Property("Exposure", it) }
                    meta.aperture?.let { Property("Aperture", it) }
                    meta.iso?.let { Property("ISO", it) }
                    meta.focalLength?.let { Property("Focal length", it) }
                    Property("GPS", if (meta.hasGps) "Metadata present" else "Not present")
                }
            },
            confirmButton = { TextButton(onClick = { showMetadata = false }) { Text("Close") } },
        )
    }
}

private suspend fun decodeSampled(container: AppContainer, entry: FileEntry, reqWidth: Int, reqHeight: Int): Bitmap? {
    val provider = container.storage.providerFor(entry.reference.providerId)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    provider.openInputStream(entry.reference).use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > reqWidth * 2 || bounds.outHeight / sample > reqHeight * 2) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
    return provider.openInputStream(entry.reference).use { BitmapFactory.decodeStream(it, null, options) }
}

@Composable
private fun MediaPlayer(
    container: AppContainer,
    entry: FileEntry,
    video: Boolean,
    onFullscreenChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? Activity
    val request = remember(entry.id) { container.storage.openRequest(entry) }
    val player = remember(entry.id) {
        ExoPlayer.Builder(context).build().apply {
            request?.let { setMediaItem(MediaItem.fromUri(it.uri)) }
            prepare()
        }
    }
    var videoMetadata by remember { mutableStateOf<VideoMetadata?>(null) }
    var audioMetadata by remember { mutableStateOf<AudioMetadata?>(null) }
    var resumeAfterLifecycle by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    resumeAfterLifecycle = player.isPlaying
                    player.pause()
                }
                Lifecycle.Event.ON_RESUME -> if (resumeAfterLifecycle) player.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.release()
            if (fullscreen) {
                onFullscreenChanged(false)
                activity?.let { restoreSystemUi(it) }
            }
        }
    }
    LaunchedEffect(entry.id) {
        if (video) videoMetadata = runCatching { container.mediaInspector.video(entry) }.getOrNull()
        else audioMetadata = runCatching { container.mediaInspector.audio(entry) }.getOrNull()
    }

    fun setFullscreen(enabled: Boolean) {
        fullscreen = enabled
        onFullscreenChanged(enabled)
        activity?.let { host ->
            if (enabled) {
                WindowInsetsControllerCompat(host.window, host.window.decorView).hide(WindowInsetsCompat.Type.systemBars())
                host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else restoreSystemUi(host)
        }
    }

    BackHandler(enabled = video && fullscreen) { setFullscreen(false) }

    Column(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
            update = { it.player = player },
            modifier = if (video) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(220.dp),
        )
        if (video) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { setFullscreen(!fullscreen) }) { Text(if (fullscreen) "Exit fullscreen" else "Fullscreen") }
            }
        }
        val details = if (video) {
            videoMetadata?.let { meta ->
                buildList {
                    add("${meta.width ?: "?"} × ${meta.height ?: "?"}")
                    meta.durationMs?.let { add(formatDuration(it)) }
                    meta.rotationDegrees?.let { add("rotation $it°") }
                    meta.frameRate?.let { add(String.format(Locale.US, "%.2f fps", it)) }
                    meta.videoMime?.let(::add)
                    meta.audioMime?.let { add("audio $it") }
                    meta.bitrate?.let { add("${it / 1000L} kbps") }
                }.joinToString(" • ")
            }
        } else {
            audioMetadata?.let { meta ->
                buildList {
                    meta.title?.let(::add)
                    meta.artist?.let(::add)
                    meta.album?.let(::add)
                    meta.durationMs?.let { add(formatDuration(it)) }
                    meta.mimeType?.let(::add)
                    meta.bitrate?.let { add("${it / 1000L} kbps") }
                }.joinToString(" • ")
            }
        }
        if (!details.isNullOrBlank()) Card(Modifier.fillMaxWidth().padding(12.dp)) { Text(details, Modifier.padding(12.dp)) }
    }
}

private fun restoreSystemUi(activity: Activity) {
    WindowInsetsControllerCompat(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars())
    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}

@Composable
private fun TextViewerEditor(
    container: AppContainer,
    entry: FileEntry,
    parent: BrowserLocation,
    snackbar: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    var document by remember(entry.id) { mutableStateOf<TextDocument?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var wrap by remember { mutableStateOf(true) }
    var lineNumbers by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var searchMatches by remember { mutableStateOf<List<TextSearchMatch>>(emptyList()) }
    var searchIndex by remember { mutableIntStateOf(-1) }
    var searchBusy by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    var buffer by remember { mutableStateOf<TextUndoRedoBuffer?>(null) }
    var editorText by remember { mutableStateOf("") }
    var replaceWith by remember { mutableStateOf("") }
    var saveAsDialog by remember { mutableStateOf(false) }
    var externalConflict by remember { mutableStateOf(false) }
    var discardDialog by remember { mutableStateOf(false) }

    val dirty = document?.mode == TextOpenMode.EDITABLE && editorText != document?.text
    BackHandler(enabled = dirty) { discardDialog = true }

    suspend fun reload() {
        loading = true
        error = null
        runCatching { container.textFileEngine.open(TextSource(entry, parent)) }
            .onSuccess {
                document = it
                editorText = it.text
                buffer = TextUndoRedoBuffer(it.text)
                searchMatches = emptyList()
                searchIndex = -1
            }
            .onFailure { error = it.message ?: "Could not open text file" }
        loading = false
    }

    fun applyEditorText(next: String) {
        buffer?.update(next)
        editorText = next
    }

    suspend fun save(force: Boolean = false) {
        val current = document ?: return
        try {
            val saved = container.textFileEngine.save(current, editorText, forceOverwriteExternalChange = force)
            document = saved
            editorText = saved.text
            buffer = TextUndoRedoBuffer(saved.text)
            snackbar.showSnackbar("Saved")
        } catch (_: TextFailure.ExternalModification) {
            externalConflict = true
        } catch (_: TextFailure.SaveAsRequired) {
            saveAsDialog = true
            snackbar.showSnackbar("This storage provider cannot guarantee safe overwrite. Use Save As.")
        } catch (failure: Throwable) {
            if (failure.message?.contains("safely replace", true) == true) {
                saveAsDialog = true
                snackbar.showSnackbar("Safe overwrite is unavailable here. Use Save As.")
            } else snackbar.showSnackbar(failure.message ?: "Save failed")
        }
    }

    suspend fun navigateMatch(delta: Int) {
        if (searchMatches.isEmpty()) return
        searchIndex = if (searchIndex < 0) 0 else (searchIndex + delta + searchMatches.size) % searchMatches.size
        val match = searchMatches[searchIndex]
        val current = document ?: return
        if (current.mode == TextOpenMode.LARGE_READ_ONLY) {
            document = container.textFileEngine.readWindow(current, (match.lineNumber - 20L).coerceAtLeast(1L), 400)
        }
    }

    LaunchedEffect(entry.id) { reload() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Wrap")
            Switch(checked = wrap, onCheckedChange = { wrap = it })
            Text("Lines")
            Switch(checked = lineNumbers, onCheckedChange = { lineNumbers = it })
            val doc = document
            if (doc?.mode == TextOpenMode.EDITABLE) FilledTonalButton(onClick = { edit = !edit }) { Text(if (edit) "View" else "Edit") }
            if (edit) {
                IconButton(enabled = buffer?.canUndo == true, onClick = { editorText = buffer?.undo() ?: editorText }) { Icon(Icons.Default.Undo, "Undo") }
                IconButton(enabled = buffer?.canRedo == true, onClick = { editorText = buffer?.redo() ?: editorText }) { Icon(Icons.Default.Redo, "Redo") }
                IconButton(enabled = dirty, onClick = { scope.launch { save() } }) { Icon(Icons.Default.Save, "Save") }
                TextButton(onClick = { saveAsDialog = true }) { Text("Save As") }
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(search, { search = it }, label = { Text("Search in file") }, singleLine = true, modifier = Modifier.weight(1f))
            IconButton(enabled = search.isNotBlank() && !searchBusy, onClick = {
                val doc = document ?: return@IconButton
                scope.launch {
                    searchBusy = true
                    try {
                        searchMatches = container.textFileEngine.search(doc, search, maxMatches = 10_000)
                        searchIndex = if (searchMatches.isEmpty()) -1 else 0
                        if (searchIndex >= 0 && doc.mode == TextOpenMode.LARGE_READ_ONLY) navigateMatch(0)
                    } finally { searchBusy = false }
                }
            }) { if (searchBusy) CircularProgressIndicator(Modifier.size(22.dp)) else Icon(Icons.Default.Search, "Search") }
            TextButton(enabled = searchMatches.isNotEmpty(), onClick = { scope.launch { navigateMatch(-1) } }) { Text("Previous") }
            TextButton(enabled = searchMatches.isNotEmpty(), onClick = { scope.launch { navigateMatch(1) } }) { Text("Next") }
        }
        if (searchMatches.isNotEmpty()) {
            val match = searchMatches[searchIndex.coerceIn(0, searchMatches.lastIndex)]
            Text(
                "Match ${searchIndex + 1} of ${searchMatches.size} • line ${match.lineNumber}, column ${match.column + 1}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
            )
        } else if (search.isNotBlank() && !searchBusy) {
            Text("No matches yet", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp))
        }

        if (edit) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(replaceWith, { replaceWith = it }, label = { Text("Replace with") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(enabled = search.isNotBlank(), onClick = {
                    val start = if (searchMatches.isNotEmpty() && searchIndex >= 0) {
                        val match = searchMatches[searchIndex]
                        editorText.lineSequence().take((match.lineNumber - 1L).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).sumOf { it.length + 1 } + match.column
                    } else 0
                    container.textFileEngine.replaceOne(editorText, search, replaceWith, startIndex = start, caseSensitive = false)?.let { result ->
                        applyEditorText(result.first)
                        searchMatches = emptyList()
                        searchIndex = -1
                    }
                }) { Text("Replace") }
                TextButton(enabled = search.isNotBlank(), onClick = {
                    val result = container.textFileEngine.replaceAll(editorText, search, replaceWith, caseSensitive = false)
                    applyEditorText(result.first)
                    searchMatches = emptyList()
                    searchIndex = -1
                    scope.launch { snackbar.showSnackbar("Replaced ${result.second} occurrence(s)") }
                }) { Text("Replace all") }
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(error!!, Modifier.padding(24.dp)) }
            document != null -> {
                val doc = document!!
                if (doc.mode == TextOpenMode.LARGE_READ_ONLY) {
                    Text("Large file • read-only window • ${doc.encoding.displayName}", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = doc.windowStartLine > 1L, onClick = {
                            scope.launch { document = container.textFileEngine.readWindow(doc, (doc.windowStartLine - 400L).coerceAtLeast(1L)) }
                        }) { Text("Previous window") }
                        OutlinedButton(enabled = doc.windowLineCount > 0, onClick = {
                            scope.launch { document = container.textFileEngine.readWindow(doc, doc.windowStartLine + doc.windowLineCount.toLong()) }
                        }) { Text("Next window") }
                        Text("Lines ${doc.windowStartLine}–${doc.windowStartLine + doc.windowLineCount - 1}", modifier = Modifier.align(Alignment.CenterVertically))
                    }
                    TextArea(doc.text, wrap, lineNumbers, doc.windowStartLine, Modifier.weight(1f))
                } else if (edit) {
                    OutlinedTextField(
                        value = editorText,
                        onValueChange = ::applyEditorText,
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                    )
                } else {
                    Text("${doc.encoding.displayName} • ${doc.lineEnding.name} • ${Formatters.bytes(entry.sizeBytes ?: 0L)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                    TextArea(doc.text, wrap, lineNumbers, 1L, Modifier.weight(1f))
                }
            }
        }
    }

    if (externalConflict) AlertDialog(
        onDismissRequest = { externalConflict = false },
        title = { Text("File changed outside the editor") },
        text = { Text("Reload the current file, save your edits under another name, or explicitly overwrite only after reviewing the conflict.") },
        confirmButton = {
            TextButton(onClick = { externalConflict = false; scope.launch { reload() } }) { Text("Reload") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { externalConflict = false; saveAsDialog = true }) { Text("Save As") }
                TextButton(onClick = { externalConflict = false; scope.launch { save(force = true) } }) { Text("Overwrite") }
            }
        },
    )

    if (discardDialog) AlertDialog(
        onDismissRequest = { discardDialog = false },
        title = { Text("Discard unsaved changes?") },
        text = { Text("Your edits have not been written to the source file.") },
        confirmButton = {
            TextButton(onClick = {
                discardDialog = false
                editorText = document?.text.orEmpty()
                buffer = TextUndoRedoBuffer(editorText)
                edit = false
                LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher?.onBackPressed()
            }) { Text("Discard") }
        },
        dismissButton = { TextButton(onClick = { discardDialog = false }) { Text("Keep editing") } },
    )

    if (saveAsDialog) {
        var newName by remember { mutableStateOf(entry.name.substringBeforeLast('.', entry.name) + "-copy" + entry.extension?.let { ".$it" }.orEmpty()) }
        AlertDialog(
            onDismissRequest = { saveAsDialog = false },
            title = { Text("Save As") },
            text = { OutlinedTextField(newName, { newName = it }, label = { Text("Filename") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val doc = document ?: return@TextButton
                    saveAsDialog = false
                    scope.launch {
                        runCatching { container.textFileEngine.saveAs(doc, editorText, newName) }
                            .onSuccess { saved ->
                                document = saved
                                editorText = saved.text
                                buffer = TextUndoRedoBuffer(saved.text)
                                snackbar.showSnackbar("Saved as ${saved.source.entry.name}")
                            }
                            .onFailure { snackbar.showSnackbar(it.message ?: "Save As failed") }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saveAsDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TextArea(text: String, wrap: Boolean, lineNumbers: Boolean, firstLineNumber: Long, modifier: Modifier = Modifier) {
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val shown = remember(text, lineNumbers, firstLineNumber) {
        if (!lineNumbers) text else text.lineSequence().mapIndexed { index, line -> "${firstLineNumber + index}  $line" }.joinToString("\n")
    }
    Box(modifier.verticalScroll(vertical).then(if (wrap) Modifier else Modifier.horizontalScroll(horizontal)).padding(12.dp)) {
        SelectionContainer { Text(shown, fontFamily = FontFamily.Monospace, softWrap = wrap) }
    }
}

@Composable
private fun ArchiveViewer(container: AppContainer, entry: FileEntry, parent: BrowserLocation, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var listing by remember(entry.id) { mutableStateOf<ArchiveListing?>(null) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var archivePath by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<ArchiveProgress?>(null) }
    var extractionJob by remember { mutableStateOf<Job?>(null) }
    var collision by remember { mutableStateOf(CollisionPolicy.KEEP_BOTH) }
    var collisionMenu by remember { mutableStateOf(false) }
    var propertiesEntry by remember { mutableStateOf<ArchiveEntryInfo?>(null) }
    var suspiciousDestination by remember { mutableStateOf<BrowserLocation?>(null) }

    fun parentArchivePath(path: String): String = path.trimEnd('/').substringBeforeLast('/', "")
    BackHandler(enabled = archivePath.isNotBlank()) { archivePath = parentArchivePath(archivePath) }

    fun startExtraction(destination: BrowserLocation, allowSuspicious: Boolean) {
        extractionJob?.cancel()
        extractionJob = scope.launch {
            try {
                val request = ArchiveExtractRequest(
                    source = ArchiveSource(entry, parent),
                    destination = destination,
                    selectedPaths = selected,
                    password = password.takeIf { it.isNotBlank() }?.toCharArray(),
                    collisionPolicy = collision,
                    allowSuspiciousExpansion = allowSuspicious,
                )
                container.archiveManager.extract(request) { progress = it }
                snackbar.showSnackbar("Extraction completed")
            } catch (_: CancellationException) {
                snackbar.showSnackbar("Extraction cancelled; tracked partial outputs were cleaned")
            } catch (failure: ArchiveFailure.SuspiciousExpansion) {
                suspiciousDestination = destination
                snackbar.showSnackbar(failure.message ?: "Archive expansion requires confirmation")
            } catch (failure: Throwable) {
                snackbar.showSnackbar(failure.message ?: "Extraction failed")
            } finally { extractionJob = null }
        }
    }

    val destinationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            runCatching { container.storage.registerSafLocation(uri) }
                .onSuccess { startExtraction(it, allowSuspicious = false) }
                .onFailure { snackbar.showSnackbar(it.message ?: "Could not access destination") }
        }
    }

    suspend fun load() {
        error = null
        runCatching { container.archiveManager.list(ArchiveSource(entry, parent), password.takeIf { it.isNotBlank() }?.toCharArray()) }
            .onSuccess {
                listing = it
                archivePath = ""
                selected = emptySet()
            }
            .onFailure { error = it.message ?: "Could not open archive" }
    }

    LaunchedEffect(entry.id) { load() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(password, { password = it }, label = { Text("Password (if required)") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = { scope.launch { load() } }) { Text("Open") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                OutlinedButton(onClick = { collisionMenu = true }) { Text("File conflict: ${collision.name.replace('_', ' ')}") }
                DropdownMenu(collisionMenu, { collisionMenu = false }) {
                    listOf(CollisionPolicy.KEEP_BOTH, CollisionPolicy.REPLACE, CollisionPolicy.SKIP).forEach { policy ->
                        DropdownMenuItem(text = { Text(policy.name.replace('_', ' ')) }, onClick = { collision = policy; collisionMenu = false })
                    }
                }
            }
            Button(enabled = listing != null && extractionJob == null, onClick = { destinationLauncher.launch(null) }) {
                Text(if (selected.isEmpty()) "Extract all" else "Extract selected (${selected.size})")
            }
            if (extractionJob != null) OutlinedButton(onClick = { extractionJob?.cancel() }) { Text("Cancel") }
        }
        progress?.let { current ->
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("${current.phase}: ${current.currentPath.orEmpty()} • ${Formatters.bytes(current.processedBytes)}", style = MaterialTheme.typography.bodySmall)
            }
        }
        when {
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            listing == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> {
                val current = listing!!
                Text("${current.format} • ${current.entries.size} entries${if (current.encrypted) " • encrypted" else ""}${if (current.stagedForRandomAccess) " • staged index" else ""}", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                ArchiveBreadcrumb(archivePath) { archivePath = it }
                val children = archiveChildren(current.entries, archivePath)
                LazyColumn(Modifier.fillMaxSize()) {
                    items(children, key = { it.path }) { archiveEntry ->
                        ArchiveEntryRow(
                            entry = archiveEntry,
                            selected = archiveEntry.path in selected,
                            onOpen = { if (archiveEntry.isDirectory) archivePath = archiveEntry.path.trimEnd('/') },
                            onSelect = { selected = if (archiveEntry.path in selected) selected - archiveEntry.path else selected + archiveEntry.path },
                            onProperties = { propertiesEntry = archiveEntry },
                        )
                    }
                }
            }
        }
    }

    propertiesEntry?.let { item ->
        AlertDialog(
            onDismissRequest = { propertiesEntry = null },
            title = { Text(item.name) },
            text = {
                Column {
                    Property("Archive path", item.path)
                    Property("Kind", if (item.isDirectory) "Folder" else "File")
                    item.uncompressedSize?.let { Property("Uncompressed", Formatters.bytes(it)) }
                    item.compressedSize?.let { Property("Compressed", Formatters.bytes(it)) }
                    item.compressionRatio?.let { Property("Compression ratio", String.format(Locale.US, "%.2f:1", it)) }
                    item.modifiedAtMillis?.let { Property("Modified", java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))) }
                    Property("Encrypted", if (item.encrypted) "Yes" else "No")
                }
            },
            confirmButton = { TextButton(onClick = { propertiesEntry = null }) { Text("Close") } },
        )
    }

    suspiciousDestination?.let { destination ->
        AlertDialog(
            onDismissRequest = { suspiciousDestination = null },
            title = { Text("Suspicious archive expansion") },
            text = { Text("The archive has an unusually high expansion ratio or structure. Continuing can consume a large amount of storage. The operation remains cancellable and free-space checks stay active.") },
            confirmButton = {
                TextButton(onClick = { suspiciousDestination = null; startExtraction(destination, allowSuspicious = true) }) { Text("Continue once") }
            },
            dismissButton = { TextButton(onClick = { suspiciousDestination = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ArchiveBreadcrumb(path: String, onNavigate: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onNavigate("") }) { Text("Archive") }
        var accumulated = ""
        path.split('/').filter { it.isNotBlank() }.forEach { segment ->
            accumulated = if (accumulated.isBlank()) segment else "$accumulated/$segment"
            val target = accumulated
            Text("/")
            TextButton(onClick = { onNavigate(target) }) { Text(segment) }
        }
    }
}

private fun archiveChildren(entries: List<ArchiveEntryInfo>, path: String): List<ArchiveEntryInfo> {
    val prefix = path.trim('/').let { if (it.isBlank()) "" else "$it/" }
    val result = linkedMapOf<String, ArchiveEntryInfo>()
    entries.forEach { entry ->
        if (!entry.path.startsWith(prefix)) return@forEach
        val relative = entry.path.removePrefix(prefix).trimStart('/')
        if (relative.isBlank()) return@forEach
        val first = relative.substringBefore('/')
        if (relative.contains('/')) {
            val childPath = prefix + first
            result.putIfAbsent(childPath, ArchiveEntryInfo(childPath, first, true, null, null))
        } else {
            result[entry.path.trimEnd('/')] = entry.copy(path = entry.path.trimEnd('/'), name = first)
        }
    }
    return result.values.sortedWith(compareByDescending<ArchiveEntryInfo> { it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })
}

@Composable
private fun ArchiveEntryRow(
    entry: ArchiveEntryInfo,
    selected: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    onProperties: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onProperties).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(selected, onCheckedChange = { onSelect() })
        Column(Modifier.weight(1f)) {
            Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (entry.isDirectory) "Folder" else "${Formatters.bytes(entry.uncompressedSize ?: 0L)}${entry.compressedSize?.let { " • compressed ${Formatters.bytes(it)}" }.orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = onProperties) { Text("Info") }
    }
}

@Composable
private fun ApkViewer(container: AppContainer, entry: FileEntry, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var metadata by remember(entry.id) { mutableStateOf<ApkMetadata?>(null) }
    var error by remember(entry.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(entry.id) {
        runCatching { container.apkManager.inspect(entry) }
            .onSuccess { metadata = it }
            .onFailure { error = it.message ?: "Could not inspect APK" }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
            metadata == null -> CircularProgressIndicator()
            else -> {
                val meta = metadata!!
                meta.icon?.let { drawable ->
                    AndroidView(
                        factory = { ctx -> ImageView(ctx).apply { setImageDrawable(drawable); contentDescription = "App icon" } },
                        update = { it.setImageDrawable(drawable) },
                        modifier = Modifier.size(72.dp),
                    )
                }
                Text(meta.appLabel, style = MaterialTheme.typography.headlineSmall)
                Property("Package", meta.packageName)
                Property("Version", "${meta.versionName.orEmpty()} (${meta.versionCode})")
                Property("SDK", "min ${meta.minSdk ?: "?"} • target ${meta.targetSdk}")
                Property("Size", Formatters.bytes(meta.apkSizeBytes ?: 0L))
                Property("Installed", if (meta.installed) "Yes" else "No")
                HorizontalDivider()
                Text("Certificates", style = MaterialTheme.typography.titleMedium)
                meta.certificates.forEach { cert ->
                    Property("SHA-256", cert.sha256)
                    cert.subject?.let { Property("Subject", it) }
                    cert.issuer?.let { Property("Issuer", it) }
                }
                HorizontalDivider()
                Text("Declared permissions (${meta.permissions.size})", style = MaterialTheme.typography.titleMedium)
                meta.permissions.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                        scope.launch { snackbar.showSnackbar("Allow installs from this app in Android settings, then choose Install again") }
                        return@Button
                    }
                    val request = container.storage.openRequest(entry)
                    if (request == null) {
                        scope.launch { snackbar.showSnackbar("APK cannot be exposed to Android Package Installer") }
                        return@Button
                    }
                    val uri = Uri.parse(request.uri)
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            clipData = android.content.ClipData.newUri(context.contentResolver, entry.name, uri)
                        })
                    } catch (_: ActivityNotFoundException) {
                        scope.launch { snackbar.showSnackbar("Android Package Installer is unavailable") }
                    }
                }) { Text("Install with Android Package Installer") }
                Text("Installation is always explicit and confirmed by Android. APK inspection never executes application code.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun GenericProperties(entry: FileEntry, parent: BrowserLocation, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Properties") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Property("Name", entry.name)
                Property("Type", entry.type.name)
                Property("MIME", entry.mimeType ?: "Unknown")
                Property("Size", entry.sizeBytes?.let(Formatters::bytes) ?: "Unknown")
                Property("Location", entry.reference.path ?: entry.reference.uri ?: parent.displayName)
                entry.modifiedAtMillis?.let { Property("Modified", java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun Property(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value) }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
