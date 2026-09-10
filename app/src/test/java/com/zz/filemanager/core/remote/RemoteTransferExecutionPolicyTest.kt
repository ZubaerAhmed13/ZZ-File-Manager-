package com.zz.filemanager.core.remote

import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.operation.FileOperation
import com.zz.filemanager.core.operation.FileOperationState
import com.zz.filemanager.core.operation.FileOperationType
import com.zz.filemanager.core.operation.OperationItem
import com.zz.filemanager.core.operation.OperationSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTransferExecutionPolicyTest {
    @Test
    fun wifiOnlyBackgroundTransfersBlockRemoteExecutionOffWifi() {
        val decision = RemoteTransferExecutionPolicy.evaluate(
            remoteCopy(),
            RemoteTransferSettings(wifiOnlyBackgroundTransfers = true),
            RemoteNetworkSnapshot(connected = true, wifi = false, metered = true),
        )
        assertFalse(decision.allowed)
        assertTrue(decision.waitingForNetwork)
    }

    @Test
    fun wifiOnlyBackgroundTransfersAllowRemoteExecutionOnWifi() {
        val decision = RemoteTransferExecutionPolicy.evaluate(
            remoteCopy(),
            RemoteTransferSettings(wifiOnlyBackgroundTransfers = true),
            RemoteNetworkSnapshot(connected = true, wifi = true, metered = false),
        )
        assertTrue(decision.allowed)
        assertFalse(decision.waitingForNetwork)
    }

    @Test
    fun warnOnMeteredNetworksProducesVisibleWarningWithoutFalseBlock() {
        val decision = RemoteTransferExecutionPolicy.evaluate(
            remoteCopy(),
            RemoteTransferSettings(warnOnMeteredNetwork = true),
            RemoteNetworkSnapshot(connected = true, wifi = false, metered = true),
        )
        assertTrue(decision.allowed)
        assertTrue(decision.warnMetered)
    }

    @Test
    fun localOperationIsNeverBlockedByRemoteNetworkPolicy() {
        val operation = remoteCopy().copy(
            destination = remoteCopy().destination!!.copy(providerId = "local", storageId = "local"),
            items = remoteCopy().items.map { item ->
                item.copy(source = item.source.copy(reference = item.source.reference.copy(providerId = "local"), storageId = "local"))
            },
        )
        val decision = RemoteTransferExecutionPolicy.evaluate(
            operation,
            RemoteTransferSettings(wifiOnlyBackgroundTransfers = true),
            RemoteNetworkSnapshot(connected = false, wifi = false, metered = true),
        )
        assertTrue(decision.allowed)
        assertFalse(decision.warnMetered)
    }

    private fun remoteCopy(): FileOperation {
        val source = OperationSource(
            reference = FileReference(providerId = "sftp:source", opaqueId = "/input.bin"),
            rootReference = "/",
            storageId = "sftp:source",
            name = "input.bin",
            isDirectory = false,
            sizeBytes = 8L,
            modifiedAtMillis = 1L,
            mimeType = "application/octet-stream",
        )
        return FileOperation(
            id = "policy-op",
            type = FileOperationType.COPY,
            state = FileOperationState.QUEUED,
            items = listOf(OperationItem(id = "item", source = source)),
            destination = BrowserLocation(
                providerId = "webdav:destination",
                id = "remote-destination",
                displayName = "Remote",
                reference = "/",
                rootReference = "/",
                storageId = "webdav:destination",
                writable = true,
            ),
            createdAtMillis = 1L,
        )
    }
}
