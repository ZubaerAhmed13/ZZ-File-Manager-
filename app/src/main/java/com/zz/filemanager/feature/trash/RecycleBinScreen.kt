@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.zz.filemanager.feature.trash

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zz.filemanager.R
import com.zz.filemanager.core.library.TrashRecord
import com.zz.filemanager.core.trash.RestoreCollisionPolicy
import com.zz.filemanager.core.util.Formatters
import java.text.DateFormat
import java.util.Date

@Composable
fun RecycleBinScreen(viewModel: RecycleBinViewModel, onBack: () -> Unit) {
    val records by viewModel.records.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var deleteConfirm by remember { mutableStateOf(false) }
    var emptyConfirm by remember { mutableStateOf(false) }
    var collisionId by remember { mutableStateOf<String?>(null) }
    val platformLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result -> viewModel.onPlatformResult(result.resultCode == Activity.RESULT_OK) }
    LaunchedEffect(viewModel) { viewModel.events.collect { event -> when (event) { is RecycleEvent.Message -> snackbar.showSnackbar(event.text); is RecycleEvent.Collision -> collisionId = event.recordId; is RecycleEvent.PlatformRequest -> platformLauncher.launch(IntentSenderRequest.Builder(event.pendingIntent.intentSender).build()) } } }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (selected.isEmpty()) stringResource(R.string.recycle_bin) else stringResource(R.string.selected_count, selected.size)) },
                navigationIcon = { IconButton(onClick = if (selected.isEmpty()) onBack else viewModel::clearSelection) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = { if (selected.isEmpty() && records.isNotEmpty()) TextButton(onClick = { emptyConfirm = true }) { Text(stringResource(R.string.empty_recycle_bin)) } },
            )
        },
        bottomBar = {
            if (selected.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly) {
                Button(onClick = { viewModel.restore() }, enabled = !busy) { Icon(Icons.Default.Restore, null); Text(stringResource(R.string.restore)) }
                TextButton(onClick = { deleteConfirm = true }, enabled = !busy) { Icon(Icons.Default.DeleteForever, null); Text(stringResource(R.string.delete_permanently)) }
            }
        },
    ) { padding ->
        when {
            busy -> Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(Modifier.padding(32.dp)) }
            records.isEmpty() -> Text(stringResource(R.string.recycle_empty), Modifier.padding(padding).padding(24.dp))
            else -> LazyColumn(Modifier.padding(padding)) { items(records, key = { it.id }) { record -> TrashRow(record, record.id in selected, viewModel::toggle) } }
        }
    }
    if (deleteConfirm) AlertDialog(
        onDismissRequest = { deleteConfirm = false },
        title = { Text(stringResource(R.string.delete_permanently)) },
        text = { Text(stringResource(R.string.permanent_delete_warning)) },
        confirmButton = { Button(onClick = { deleteConfirm = false; viewModel.deleteSelected() }) { Text(stringResource(R.string.delete_permanently)) } },
        dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (emptyConfirm) AlertDialog(
        onDismissRequest = { emptyConfirm = false },
        title = { Text(stringResource(R.string.empty_recycle_bin)) },
        text = { Text(stringResource(R.string.empty_recycle_bin_question, records.size) + " " + stringResource(R.string.permanent_delete_warning)) },
        confirmButton = { Button(onClick = { emptyConfirm = false; viewModel.empty() }) { Text(stringResource(R.string.empty_recycle_bin)) } },
        dismissButton = { TextButton(onClick = { emptyConfirm = false }) { Text(stringResource(R.string.cancel)) } },
    )
    collisionId?.let { id -> AlertDialog(
        onDismissRequest = { collisionId = null },
        title = { Text(stringResource(R.string.restore_collision)) },
        text = { Text(stringResource(R.string.file_conflict)) },
        confirmButton = { TextButton(onClick = { collisionId = null; viewModel.restore(RestoreCollisionPolicy.KEEP_BOTH, id) }) { Text(stringResource(R.string.keep_both)) } },
        dismissButton = { Row { TextButton(onClick = { collisionId = null; viewModel.restore(RestoreCollisionPolicy.REPLACE, id) }) { Text(stringResource(R.string.replace)) }; TextButton(onClick = { collisionId = null }) { Text(stringResource(R.string.cancel)) } } },
    ) }
}

@Composable
private fun TrashRow(record: TrashRecord, selected: Boolean, toggle: (TrashRecord) -> Unit) {
    ListItem(
        headlineContent = { Text(record.originalName, maxLines = 1) },
        supportingContent = { Column { Text(stringResource(R.string.original_location) + ": " + record.originalParent.displayName); Text(stringResource(R.string.deleted_date) + ": " + DateFormat.getDateTimeInstance().format(Date(record.trashedAtMillis))); record.sizeBytes?.let { Text(Formatters.bytes(it)) }; if (record.failureReason != null) Text(record.failureReason, color = MaterialTheme.colorScheme.error) } },
        leadingContent = { Icon(if (record.type == com.zz.filemanager.core.model.FileEntryType.DIRECTORY) Icons.Default.Folder else Icons.Default.InsertDriveFile, null) },
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { toggle(record) }, onLongClick = { toggle(record) }),
        tonalElevation = if (selected) 4.dp else 0.dp,
    )
}
