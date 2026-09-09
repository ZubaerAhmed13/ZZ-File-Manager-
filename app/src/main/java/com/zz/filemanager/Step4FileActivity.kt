@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.zz.filemanager

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Close
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import com.zz.filemanager.app.AppContainer
import com.zz.filemanager.core.apk.ApkMetadata
import com.zz.filemanager.core.archive.ArchiveEntryInfo
import com.zz.filemanager.core.archive.ArchiveExtractRequest
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
import com.zz.filemanager.core.text.TextSource
import com.zz.filemanager.core.text.TextUndoRedoBuffer
import com.zz.filemanager.core.util.Formatters
import com.zz.filemanager.ui.theme.ZZFileManagerTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

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
    entry: FileEntry,
    parent: BrowserLocation,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showInfo by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

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
            TopAppBar(
                title = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
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
                FileEntryType.IMAGE -> ImageViewer(container, entry)
                FileEntryType.VIDEO -> MediaPlayer(container, entry, video = true)
                FileEntryType.AUDIO -> MediaPlayer(container, entry, video = false)
                FileEntryType.TEXT -> TextViewerEditor(container, entry, parent, snackbar)
                FileEntryType.ARCHIVE -> ArchiveViewer(container, entry, parent, snackbar)
                FileEntryType.APK -> ApkViewer(container, entry, snackbar)
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = ::externalView) { Text("Open with another app") }
                }
            }
        }
    }

    if (showInfo) {
        GenericProperties(entry, parent) { showInfo = false }
    }
}

@Composable
private fun ImageViewer(container: AppContainer, entry: FileEntry) {
    var bitmap by remember(entry.id) { mutableStateOf<Bitmap?>(null) }
    var metadata by remember(entry.id) { mutableStateOf<ImageMetadata?>(null) }
    var error by remember(entry.id) { mutableStateOf<String?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 8f)
        offset = if (scale <= 1.01f) Offset.Zero else offset + panChange
    }

    LaunchedEffect(entry.id) {
        runCatching {
            metadata = container.mediaInspector.image(entry)
            bitmap = withContext(Dispatchers.IO) { decodeSampled(container, entry, 4096, 4096) }
        }.onFailure { error = it.message ?: "Image could not be decoded" }
    }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(Unit) {
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
        metadata?.let { meta ->
            Card(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(
                    buildString {
                        append(meta.width ?: "?"); append(" × "); append(meta.height ?: "?")
                        meta.cameraModel?.let { append("  •  "); append(it) }
                        if (meta.hasGps) append("  •  GPS metadata present")
                    },
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

private fun decodeSampled(container: AppContainer, entry: FileEntry, reqWidth: Int, reqHeight: Int): Bitmap? {
    val provider = container.storage.providerFor(entry.reference.providerId)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    provider.openInputStream(entry.reference).use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > reqWidth * 2 || bounds.outHeight / sample > reqHeight * 2) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
    return provider.openInputStream(entry.reference).use { BitmapFactory.decodeStream(it, null, options) }
}

@Composable
private fun MediaPlayer(container: AppContainer, entry: FileEntry, video: Boolean) {
    val context = LocalContext.current
    val request = remember(entry.id) { container.storage.openRequest(entry) }
    val player = remember(entry.id) {
        ExoPlayer.Builder(context).build().apply {
            request?.let { setMediaItem(MediaItem.fromUri(it.uri)) }
            prepare()
        }
    }
    var videoMetadata by remember { mutableStateOf<VideoMetadata?>(null) }
    var audioMetadata by remember { mutableStateOf<AudioMetadata?>(null) }

    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(entry.id) {
        if (video) videoMetadata = runCatching { container.mediaInspector.video(entry) }.getOrNull()
        else audioMetadata = runCatching { container.mediaInspector.audio(entry) }.getOrNull()
    }

    Column(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
            update = { it.player = player },
            modifier = if (video) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(220.dp),
        )
        val text = if (video) {
            videoMetadata?.let { "${it.width ?: "?"} × ${it.height ?: "?"}  •  ${it.durationMs?.let(::formatDuration) ?: "?"}  •  ${it.videoMime ?: entry.mimeType.orEmpty()}" }
        } else {
            audioMetadata?.let { meta -> listOfNotNull(meta.title, meta.artist, meta.album, meta.durationMs?.let(::formatDuration)).joinToString("  •  ") }
        }
        if (!text.isNullOrBlank()) Card(Modifier.fillMaxWidth().padding(12.dp)) { Text(text, Modifier.padding(12.dp)) }
    }
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
    var searchResult by remember { mutableStateOf("") }
    var edit by remember { mutableStateOf(false) }
    var buffer by remember { mutableStateOf<TextUndoRedoBuffer?>(null) }
    var editorText by remember { mutableStateOf("") }
    var saveAsDialog by remember { mutableStateOf(false) }
    var externalConflict by remember { mutableStateOf(false) }

    suspend fun reload() {
        loading = true
        error = null
        runCatching { container.textFileEngine.open(TextSource(entry, parent)) }
            .onSuccess {
                document = it
                editorText = it.text
                buffer = TextUndoRedoBuffer(it.text)
            }
            .onFailure { error = it.message ?: "Could not open text file" }
        loading = false
    }

    LaunchedEffect(entry.id) { reload() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Wrap")
            Switch(checked = wrap, onCheckedChange = { wrap = it })
            Text("Lines")
            Switch(checked = lineNumbers, onCheckedChange = { lineNumbers = it })
            val doc = document
            if (doc?.mode == TextOpenMode.EDITABLE) {
                FilledTonalButton(onClick = { edit = !edit }) { Text(if (edit) "View" else "Edit") }
            }
            if (edit) {
                IconButton(enabled = buffer?.canUndo == true, onClick = {
                    editorText = buffer?.undo() ?: editorText
                }) { Icon(Icons.Default.Undo, contentDescription = "Undo") }
                IconButton(enabled = buffer?.canRedo == true, onClick = {
                    editorText = buffer?.redo() ?: editorText
                }) { Icon(Icons.Default.Redo, contentDescription = "Redo") }
                IconButton(onClick = {
                    val current = document ?: return@IconButton
                    scope.launch {
                        try {
                            document = container.textFileEngine.save(current, editorText)
                            buffer = TextUndoRedoBuffer(editorText)
                            snackbar.showSnackbar("Saved")
                        } catch (_: TextFailure.ExternalModification) {
                            externalConflict = true
                        } catch (failure: Throwable) {
                            snackbar.showSnackbar(failure.message ?: "Save failed")
                        }
                    }
                }) { Icon(Icons.Default.Save, contentDescription = "Save") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(search, { search = it }, label = { Text("Search in file") }, singleLine = true, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                val doc = document ?: return@IconButton
                scope.launch {
                    val matches = runCatching { container.textFileEngine.search(doc, search, maxMatches = 1000) }.getOrElse { emptyList() }
                    searchResult = if (matches.isEmpty()) "No matches" else "${matches.size} matches • first at line ${matches.first().lineNumber}"
                }
            }) { Icon(Icons.Default.Search, contentDescription = "Search") }
        }
        if (searchResult.isNotBlank()) Text(searchResult, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))

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
                        }) { Text("Previous") }
                        OutlinedButton(onClick = {
                            scope.launch { document = container.textFileEngine.readWindow(doc, doc.windowStartLine + doc.windowLineCount.toLong()) }
                        }) { Text("Next") }
                        Text("Lines ${doc.windowStartLine}–${doc.windowStartLine + doc.windowLineCount - 1}", modifier = Modifier.align(Alignment.CenterVertically))
                    }
                    TextArea(doc.text, wrap, lineNumbers, Modifier.weight(1f))
                } else if (edit) {
                    androidx.compose.material3.OutlinedTextField(
                        value = editorText,
                        onValueChange = { next ->
                            buffer?.update(next)
                            editorText = next
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                    )
                } else {
                    Text("${doc.encoding.displayName} • ${doc.lineEnding.name} • ${Formatters.bytes(entry.sizeBytes ?: 0L)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                    TextArea(doc.text, wrap, lineNumbers, Modifier.weight(1f))
                }
            }
        }
    }

    if (externalConflict) AlertDialog(
        onDismissRequest = { externalConflict = false },
        title = { Text("File changed outside the editor") },
        text = { Text("Reload the current file, save your edits under another name, or explicitly overwrite after reviewing the conflict.") },
        confirmButton = {
            TextButton(onClick = {
                externalConflict = false
                scope.launch { reload() }
            }) { Text("Reload") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { externalConflict = false; saveAsDialog = true }) { Text("Save As") }
                TextButton(onClick = {
                    externalConflict = false
                    val doc = document ?: return@TextButton
                    scope.launch {
                        runCatching { container.textFileEngine.save(doc, editorText, forceOverwriteExternalChange = true) }
                            .onSuccess { document = it; snackbar.showSnackbar("Saved") }
                            .onFailure { snackbar.showSnackbar(it.message ?: "Save failed") }
                    }
                }) { Text("Overwrite") }
            }
        },
    )

    if (saveAsDialog) {
        var newName by remember { mutableStateOf(entry.name.substringBeforeLast('.', entry.name) + "-copy" + entry.extension?.let { ".$it" }.orEmpty()) }
        AlertDialog(
            onDismissRequest = { saveAsDialog = false },
            title = { Text("Save As") },
            text = { OutlinedTextField(newName, { newName = it }, label = { Text("Filename") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    saveAsDialog = false
                    val doc = document ?: return@TextButton
                    scope.launch {
                        runCatching { container.textFileEngine.saveAs(doc, editorText, newName) }
                            .onSuccess { document = it; snackbar.showSnackbar("Saved as ${it.source.entry.name}") }
                            .onFailure { snackbar.showSnackbar(it.message ?: "Save As failed") }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saveAsDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TextArea(text: String, wrap: Boolean, lineNumbers: Boolean, modifier: Modifier = Modifier) {
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val shown = remember(text, lineNumbers) {
        if (!lineNumbers) text else text.lineSequence().mapIndexed { index, line -> "${index + 1}  $line" }.joinToString("\n")
    }
    Box(modifier.verticalScroll(vertical).then(if (wrap) Modifier else Modifier.horizontalScroll(horizontal)).padding(12.dp)) {
        Text(shown, fontFamily = FontFamily.Monospace, softWrap = wrap)
    }
}

@Composable
private fun ArchiveViewer(container: AppContainer, entry: FileEntry, parent: BrowserLocation, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var listing by remember(entry.id) { mutableStateOf<ArchiveListing?>(null) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var progress by remember { mutableStateOf<ArchiveProgress?>(null) }
    var extractionJob by remember { mutableStateOf<Job?>(null) }
    var collision by remember { mutableStateOf(CollisionPolicy.KEEP_BOTH) }
    var collisionMenu by remember { mutableStateOf(false) }
    val destinationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            extractionJob = scope.launch {
                try {
                    val destination = container.storage.registerSafLocation(uri)
                    val request = ArchiveExtractRequest(
                        source = ArchiveSource(entry, parent),
                        destination = destination,
                        selectedPaths = selected,
                        password = password.takeIf { it.isNotBlank() }?.toCharArray(),
                        collisionPolicy = collision,
                    )
                    container.archiveManager.extract(request) { progress = it }
                    snackbar.showSnackbar("Extraction completed")
                } catch (cancelled: CancellationException) {
                    snackbar.showSnackbar("Extraction cancelled")
                } catch (failure: Throwable) {
                    snackbar.showSnackbar(failure.message ?: "Extraction failed")
                } finally {
                    extractionJob = null
                }
            }
        }
    }

    suspend fun load() {
        error = null
        runCatching { container.archiveManager.list(ArchiveSource(entry, parent), password.takeIf { it.isNotBlank() }?.toCharArray()) }
            .onSuccess { listing = it }
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
                OutlinedButton(onClick = { collisionMenu = true }) { Text("On conflict: ${collision.name.replace('_', ' ')}") }
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
                Text("${current.format} • ${current.entries.size} entries${if (current.encrypted) " • encrypted" else ""}", modifier = Modifier.padding(8.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    items(current.entries, key = { it.path }) { archiveEntry ->
                        ArchiveEntryRow(archiveEntry, archiveEntry.path in selected) {
                            selected = if (archiveEntry.path in selected) selected - archiveEntry.path else selected + archiveEntry.path
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchiveEntryRow(entry: ArchiveEntryInfo, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onSelect, onLongClick = onSelect).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(selected, onCheckedChange = { onSelect() })
        Column(Modifier.weight(1f)) {
            Text(entry.path, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (entry.isDirectory) "Folder" else "${Formatters.bytes(entry.uncompressedSize ?: 0L)}${entry.compressedSize?.let { " • compressed ${Formatters.bytes(it)}" }.orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ApkViewer(container: AppContainer, entry: FileEntry, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var metadata by remember(entry.id) { mutableStateOf<ApkMetadata?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
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
                }
                HorizontalDivider()
                Text("Declared permissions (${meta.permissions.size})", style = MaterialTheme.typography.titleMedium)
                meta.permissions.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
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
                Text("Installation always requires Android's confirmation. This app does not silently install APKs.", style = MaterialTheme.typography.bodySmall)
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
        Text(value)
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
