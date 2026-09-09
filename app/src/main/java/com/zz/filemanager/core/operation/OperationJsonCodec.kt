package com.zz.filemanager.core.operation

import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.model.ScopedFileReference
import org.json.JSONArray
import org.json.JSONObject

object OperationJsonCodec {
    fun encode(operation: FileOperation): String = operationToJson(operation).toString()
    fun decode(raw: String): FileOperation = operationFromJson(JSONObject(raw))

    fun encodeClipboard(clipboard: OperationClipboard): String = JSONObject().apply {
        put("mode", clipboard.mode.name)
        put("sources", JSONArray().apply { clipboard.sources.forEach { put(sourceToJson(it)) } })
        put("origin", locationToJson(clipboard.originatingLocation))
        put("createdAt", clipboard.createdAtMillis)
    }.toString()

    fun decodeClipboard(raw: String): OperationClipboard {
        val json = JSONObject(raw)
        return OperationClipboard(
            mode = ClipboardMode.valueOf(json.getString("mode")),
            sources = json.getJSONArray("sources").mapObjects(::sourceFromJson),
            originatingLocation = locationFromJson(json.getJSONObject("origin")),
            createdAtMillis = json.getLong("createdAt"),
        )
    }

    private fun operationToJson(operation: FileOperation) = JSONObject().apply {
        put("id", operation.id)
        put("type", operation.type.name)
        put("state", operation.state.name)
        put("items", JSONArray().apply { operation.items.forEach { put(itemToJson(it)) } })
        putNullable("destination", operation.destination?.let(::locationToJson))
        putNullable("targetName", operation.targetName)
        putNullable("targetMimeType", operation.targetMimeType)
        put("createdAt", operation.createdAtMillis)
        putNullable("startedAt", operation.startedAtMillis)
        putNullable("completedAt", operation.completedAtMillis)
        put("updatedAt", operation.updatedAtMillis)
        put("prepared", operation.prepared)
        putNullable("totalBytes", operation.totalBytes)
        put("processedBytes", operation.processedBytes)
        putNullable("totalItems", operation.totalItems)
        put("processedItems", operation.processedItems)
        putNullable("currentItemName", operation.currentItemName)
        putNullable("failure", operation.failure?.let(::failureToJson))
        put("warningCount", operation.warningCount)
        putNullable("pendingCollision", operation.pendingCollision?.let(::collisionToJson))
        put("collisionDecisions", JSONObject().apply {
            operation.collisionDecisions.forEach { (key, value) -> put(key, value.name) }
        })
        putNullable("applyToAllCollisionPolicy", operation.applyToAllCollisionPolicy?.name)
        putNullable("retryOfOperationId", operation.retryOfOperationId)
        put("batchRenameRollbackRequired", operation.batchRenameRollbackRequired)
    }

    private fun operationFromJson(json: JSONObject): FileOperation {
        val decisionsJson = json.optJSONObject("collisionDecisions") ?: JSONObject()
        val decisions = buildMap {
            decisionsJson.keys().forEach { key -> put(key, CollisionPolicy.valueOf(decisionsJson.getString(key))) }
        }
        return FileOperation(
            id = json.getString("id"),
            type = FileOperationType.valueOf(json.getString("type")),
            state = FileOperationState.valueOf(json.getString("state")),
            items = json.getJSONArray("items").mapObjects(::itemFromJson),
            destination = json.objectOrNull("destination")?.let(::locationFromJson),
            targetName = json.stringOrNull("targetName"),
            targetMimeType = json.stringOrNull("targetMimeType"),
            createdAtMillis = json.getLong("createdAt"),
            startedAtMillis = json.longOrNull("startedAt"),
            completedAtMillis = json.longOrNull("completedAt"),
            updatedAtMillis = json.optLong("updatedAt", json.getLong("createdAt")),
            prepared = json.optBoolean("prepared", false),
            totalBytes = json.longOrNull("totalBytes"),
            processedBytes = json.optLong("processedBytes", 0L),
            totalItems = json.longOrNull("totalItems"),
            processedItems = json.optLong("processedItems", 0L),
            currentItemName = json.stringOrNull("currentItemName"),
            failure = json.objectOrNull("failure")?.let(::failureFromJson),
            warningCount = json.optLong("warningCount", 0L),
            pendingCollision = json.objectOrNull("pendingCollision")?.let(::collisionFromJson),
            collisionDecisions = decisions,
            applyToAllCollisionPolicy = json.stringOrNull("applyToAllCollisionPolicy")?.let(CollisionPolicy::valueOf),
            retryOfOperationId = json.stringOrNull("retryOfOperationId"),
            batchRenameRollbackRequired = json.optBoolean("batchRenameRollbackRequired", false),
        )
    }

    private fun itemToJson(item: OperationItem) = JSONObject().apply {
        put("id", item.id)
        put("source", sourceToJson(item.source))
        put("state", item.state.name)
        putNullable("rootItemId", item.rootItemId)
        putNullable("requestedName", item.requestedName)
        put("destinationRelativePath", item.destinationRelativePath)
        put("processedBytes", item.processedBytes)
        putNullable("failure", item.failure?.let(::failureToJson))
        putNullable("resultReference", item.resultReference?.let(::scopedToJson))
        putNullable("partialOutput", item.partialOutput?.let(::scopedToJson))
        putNullable("batchRenameTemporaryName", item.batchRenameTemporaryName)
        put("batchRenamePhase", item.batchRenamePhase.name)
        put("replacePhase", item.replacePhase.name)
        putNullable("replaceFinalName", item.replaceFinalName)
        putNullable("replaceOriginalReference", item.replaceOriginalReference?.let(::scopedToJson))
        putNullable("replaceOriginalSizeBytes", item.replaceOriginalSizeBytes)
        putNullable("replaceOriginalModifiedAtMillis", item.replaceOriginalModifiedAtMillis)
        putNullable("replaceBackupName", item.replaceBackupName)
        putNullable("replaceBackupReference", item.replaceBackupReference?.let(::scopedToJson))
    }

    private fun itemFromJson(json: JSONObject) = OperationItem(
        id = json.getString("id"),
        source = sourceFromJson(json.getJSONObject("source")),
        state = OperationItemState.valueOf(json.getString("state")),
        rootItemId = json.stringOrNull("rootItemId"),
        requestedName = json.stringOrNull("requestedName"),
        destinationRelativePath = json.optString("destinationRelativePath", ""),
        processedBytes = json.optLong("processedBytes", 0L),
        failure = json.objectOrNull("failure")?.let(::failureFromJson),
        resultReference = json.objectOrNull("resultReference")?.let(::scopedFromJson),
        partialOutput = json.objectOrNull("partialOutput")?.let(::scopedFromJson),
        batchRenameTemporaryName = json.stringOrNull("batchRenameTemporaryName"),
        batchRenamePhase = json.stringOrNull("batchRenamePhase")?.let(BatchRenamePhase::valueOf) ?: BatchRenamePhase.ORIGINAL,
        replacePhase = json.stringOrNull("replacePhase")?.let(ReplacePhase::valueOf) ?: ReplacePhase.NONE,
        replaceFinalName = json.stringOrNull("replaceFinalName"),
        replaceOriginalReference = json.objectOrNull("replaceOriginalReference")?.let(::scopedFromJson),
        replaceOriginalSizeBytes = json.longOrNull("replaceOriginalSizeBytes"),
        replaceOriginalModifiedAtMillis = json.longOrNull("replaceOriginalModifiedAtMillis"),
        replaceBackupName = json.stringOrNull("replaceBackupName"),
        replaceBackupReference = json.objectOrNull("replaceBackupReference")?.let(::scopedFromJson),
    )

    private fun sourceToJson(source: OperationSource) = JSONObject().apply {
        put("reference", referenceToJson(source.reference))
        put("rootReference", source.rootReference)
        put("storageId", source.storageId)
        put("name", source.name)
        put("isDirectory", source.isDirectory)
        putNullable("sizeBytes", source.sizeBytes)
        putNullable("modifiedAtMillis", source.modifiedAtMillis)
        putNullable("mimeType", source.mimeType)
        put("isSymbolicLink", source.isSymbolicLink)
    }

    private fun sourceFromJson(json: JSONObject) = OperationSource(
        reference = referenceFromJson(json.getJSONObject("reference")),
        rootReference = json.getString("rootReference"),
        storageId = json.getString("storageId"),
        name = json.getString("name"),
        isDirectory = json.getBoolean("isDirectory"),
        sizeBytes = json.longOrNull("sizeBytes"),
        modifiedAtMillis = json.longOrNull("modifiedAtMillis"),
        mimeType = json.stringOrNull("mimeType"),
        isSymbolicLink = json.optBoolean("isSymbolicLink", false),
    )

    private fun collisionToJson(collision: PendingCollision) = JSONObject().apply {
        put("id", collision.id)
        put("itemId", collision.itemId)
        put("sourceName", collision.sourceName)
        put("destinationName", collision.destinationName)
        put("kind", collision.kind.name)
        put("allowedPolicies", JSONArray().apply { collision.allowedPolicies.forEach { put(it.name) } })
    }

    private fun collisionFromJson(json: JSONObject) = PendingCollision(
        id = json.getString("id"),
        itemId = json.getString("itemId"),
        sourceName = json.getString("sourceName"),
        destinationName = json.getString("destinationName"),
        kind = CollisionKind.valueOf(json.getString("kind")),
        allowedPolicies = json.getJSONArray("allowedPolicies").mapStrings { CollisionPolicy.valueOf(it) }.toSet(),
    )

    private fun failureToJson(failure: OperationFailure) = JSONObject().apply {
        put("code", failure.code.name)
        put("message", failure.message)
        putNullable("itemName", failure.itemName)
    }

    private fun failureFromJson(json: JSONObject) = OperationFailure(
        code = OperationFailureCode.valueOf(json.getString("code")),
        message = json.getString("message"),
        itemName = json.stringOrNull("itemName"),
    )

    private fun scopedToJson(scoped: ScopedFileReference) = JSONObject().apply {
        put("reference", referenceToJson(scoped.reference))
        put("rootReference", scoped.rootReference)
        put("storageId", scoped.storageId)
    }

    private fun scopedFromJson(json: JSONObject) = ScopedFileReference(
        reference = referenceFromJson(json.getJSONObject("reference")),
        rootReference = json.getString("rootReference"),
        storageId = json.getString("storageId"),
    )

    private fun referenceToJson(reference: FileReference) = JSONObject().apply {
        put("providerId", reference.providerId)
        put("opaqueId", reference.opaqueId)
        putNullable("uri", reference.uri)
        putNullable("path", reference.path)
    }

    private fun referenceFromJson(json: JSONObject) = FileReference(
        providerId = json.getString("providerId"),
        opaqueId = json.getString("opaqueId"),
        uri = json.stringOrNull("uri"),
        path = json.stringOrNull("path"),
    )

    private fun locationToJson(location: BrowserLocation) = JSONObject().apply {
        put("providerId", location.providerId)
        put("id", location.id)
        put("displayName", location.displayName)
        put("reference", location.reference)
        put("rootReference", location.rootReference)
        put("storageId", location.storageId)
        put("readable", location.readable)
        put("writable", location.writable)
    }

    private fun locationFromJson(json: JSONObject) = BrowserLocation(
        providerId = json.getString("providerId"),
        id = json.getString("id"),
        displayName = json.getString("displayName"),
        reference = json.getString("reference"),
        rootReference = json.getString("rootReference"),
        storageId = json.getString("storageId"),
        readable = json.optBoolean("readable", true),
        writable = json.optBoolean("writable", false),
    )

    private fun JSONObject.putNullable(key: String, value: Any?) { put(key, value ?: JSONObject.NULL) }
    private fun JSONObject.stringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
    private fun JSONObject.longOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)
    private fun JSONObject.objectOrNull(key: String): JSONObject? = if (!has(key) || isNull(key)) null else getJSONObject(key)
    private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> = List(length()) { transform(getJSONObject(it)) }
    private fun <T> JSONArray.mapStrings(transform: (String) -> T): List<T> = List(length()) { transform(getString(it)) }
}
