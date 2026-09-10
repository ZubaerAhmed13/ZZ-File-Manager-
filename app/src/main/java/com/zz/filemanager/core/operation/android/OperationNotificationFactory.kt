package com.zz.filemanager.core.operation.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.zz.filemanager.R
import com.zz.filemanager.core.operation.FileOperation
import com.zz.filemanager.core.operation.FileOperationState
import com.zz.filemanager.core.operation.FileOperationType
import com.zz.filemanager.core.util.Formatters

class OperationNotificationFactory(private val context: Context) {
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.file_operations_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.file_operations_channel_description)
                    setShowBadge(false)
                },
            )
        }
    }

    /** statusPrefix keeps policy warnings visible without hiding normal transfer progress. */
    fun build(operation: FileOperation?, statusPrefix: String? = null): Notification {
        ensureChannel()
        val title = operation?.let(::titleFor) ?: context.getString(R.string.file_operations)
        val normalText = operation?.let(::textFor) ?: context.getString(R.string.preparing_operation)
        val text = if (statusPrefix.isNullOrBlank()) normalText else "$statusPrefix • $normalText"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(operation?.state?.isTerminal == false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (operation != null) {
            if (operation.totalBytes != null && operation.totalBytes > 0L) {
                val percentage = ((operation.processedBytes.toDouble() / operation.totalBytes.toDouble()) * 100.0).toInt().coerceIn(0, 100)
                builder.setProgress(100, percentage, false)
            } else if (operation.state in setOf(FileOperationState.RUNNING, FileOperationState.PREPARING)) {
                builder.setProgress(0, 0, true)
            }

            if (operation.state == FileOperationState.RUNNING || operation.state == FileOperationState.PREPARING) {
                builder.addAction(0, context.getString(R.string.pause), actionPendingIntent(OperationActionReceiver.ACTION_PAUSE, operation.id, 1))
            }
            if (!operation.state.isTerminal) {
                builder.addAction(0, context.getString(R.string.cancel), actionPendingIntent(OperationActionReceiver.ACTION_CANCEL, operation.id, 2))
            }
        }
        return builder.build()
    }

    private fun titleFor(operation: FileOperation): String = when (operation.type) {
        FileOperationType.COPY -> context.getString(R.string.copying_files)
        FileOperationType.MOVE -> context.getString(R.string.moving_files)
        FileOperationType.DELETE -> context.getString(R.string.deleting_files)
        FileOperationType.RENAME, FileOperationType.BATCH_RENAME -> context.getString(R.string.renaming_files)
        FileOperationType.CREATE_DIRECTORY, FileOperationType.CREATE_FILE -> context.getString(R.string.creating_item)
    }

    private fun textFor(operation: FileOperation): String {
        if (operation.state == FileOperationState.WAITING_FOR_USER) return context.getString(R.string.waiting_for_conflict_decision)
        if (operation.state == FileOperationState.PAUSED) return context.getString(R.string.operation_paused)
        val current = operation.currentItemName
        val progress = if (operation.totalBytes != null) {
            context.getString(R.string.transfer_progress_bytes, Formatters.bytes(operation.processedBytes), Formatters.bytes(operation.totalBytes))
        } else {
            context.getString(R.string.transfer_progress_items, operation.processedItems, operation.totalItems ?: operation.items.size.toLong())
        }
        return if (current.isNullOrBlank()) progress else "$current • $progress"
    }

    private fun actionPendingIntent(action: String, operationId: String, salt: Int): PendingIntent {
        val intent = Intent(context, OperationActionReceiver::class.java).apply {
            this.action = action
            putExtra(OperationActionReceiver.EXTRA_OPERATION_ID, operationId)
        }
        return PendingIntent.getBroadcast(
            context,
            operationId.hashCode() * 31 + salt,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_ID = "file_operations"
        const val NOTIFICATION_ID = 0x5A5A02
    }
}
