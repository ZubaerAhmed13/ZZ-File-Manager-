package com.zz.filemanager.core.operation.android

import com.zz.filemanager.core.operation.FileOperation
import com.zz.filemanager.core.operation.FileOperationState

/** Selects the most useful operation to surface in the ongoing notification. */
internal fun List<FileOperation>.relevantOperation(): FileOperation? = firstOrNull {
    it.state in setOf(
        FileOperationState.RUNNING,
        FileOperationState.PREPARING,
        FileOperationState.PAUSING,
        FileOperationState.CANCELLING,
        FileOperationState.WAITING_FOR_USER,
    )
} ?: firstOrNull { it.state == FileOperationState.QUEUED }
