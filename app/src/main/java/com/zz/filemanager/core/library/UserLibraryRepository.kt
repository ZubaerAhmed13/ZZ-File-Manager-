package com.zz.filemanager.core.library

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntryType
import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.model.ScopedFileReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

class UserLibraryRepository(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : UserLibraryStore {
    private val database = UserLibraryDatabase(context.applicationContext)
    private val mutex = Mutex()
    private var initialized = false
    private val _favorites = MutableStateFlow<List<FavoriteItem>>(emptyList())
    override val favorites: StateFlow<List<FavoriteItem>> = _favorites.asStateFlow()
    private val _recentFiles = MutableStateFlow<List<RecentFile>>(emptyList())
    override val recentFiles: StateFlow<List<RecentFile>> = _recentFiles.asStateFlow()
    private val _searchHistory = MutableStateFlow<List<SearchHistoryItem>>(emptyList())
    override val searchHistory: StateFlow<List<SearchHistoryItem>> = _searchHistory.asStateFlow()
    private val _activityHistory = MutableStateFlow<List<ActivityEntry>>(emptyList())
    override val activityHistory: StateFlow<List<ActivityEntry>> = _activityHistory.asStateFlow()
    private val _trashRecords = MutableStateFlow<List<TrashRecord>>(emptyList())
    override val trashRecords: StateFlow<List<TrashRecord>> = _trashRecords.asStateFlow()

    override suspend fun initialize() = mutex.withLock { ensureInitializedLocked() }

    override suspend fun upsertFavorite(item: FavoriteItem) = mutate {
        database.upsert("favorites", item.id, LibraryCodec.favorite(item), item.addedAtMillis, item.status.name, item.reference.providerId, item.reference.opaqueId)
        database.trim("favorites", MAX_FAVORITES)
    }

    override suspend fun removeFavorite(id: String) = mutate { database.delete("favorites", id) }

    override suspend fun updateFavorite(item: FavoriteItem) = upsertFavorite(item)

    override suspend fun recordRecentFile(item: RecentFile) = mutate {
        database.upsert("recent_files", item.id, LibraryCodec.recent(item), item.openedAtMillis, item.status.name, item.reference.providerId, item.reference.opaqueId)
        database.trim("recent_files", MAX_RECENT_FILES)
    }
    override suspend fun removeRecentFile(id: String) = mutate { database.delete("recent_files", id) }

    override suspend fun recordSearch(query: String, usedAtMillis: Long) {
        val display = query.trim()
        if (display.isEmpty()) return
        val normalized = display.lowercase(Locale.ROOT)
        mutate {
            database.upsert("search_history", normalized, LibraryCodec.search(SearchHistoryItem(normalized, display, usedAtMillis)), usedAtMillis, null)
            database.trim("search_history", MAX_SEARCH_HISTORY)
        }
    }

    override suspend fun recordActivity(entry: ActivityEntry) = mutate {
        database.upsert("activity_history", entry.id, LibraryCodec.activity(entry), entry.occurredAtMillis, entry.kind.name)
        database.trim("activity_history", MAX_ACTIVITY_HISTORY)
    }

    override suspend fun upsertTrash(record: TrashRecord) = mutate {
        database.upsert("trash_records", record.id, LibraryCodec.trash(record), record.updatedAtMillis, record.state.name, record.originalReference.providerId, record.originalReference.opaqueId)
    }

    override suspend fun removeTrash(id: String) = mutate { database.delete("trash_records", id) }
    override suspend fun clearRecentFiles() = mutate { database.clear("recent_files") }
    override suspend fun clearSearchHistory() = mutate { database.clear("search_history") }
    override suspend fun clearActivityHistory() = mutate { database.clear("activity_history") }

    private suspend fun mutate(block: suspend () -> Unit) = mutex.withLock {
        ensureInitializedLocked()
        withContext(ioDispatcher) { block() }
        reloadLocked()
    }

    private suspend fun ensureInitializedLocked() {
        if (initialized) return
        reloadLocked()
        initialized = true
    }

    private suspend fun reloadLocked() = withContext(ioDispatcher) {
        _favorites.value = database.read("favorites", LibraryCodec::decodeFavorite).sortedByDescending { it.addedAtMillis }
        _recentFiles.value = database.read("recent_files", LibraryCodec::decodeRecent).sortedByDescending { it.openedAtMillis }
        _searchHistory.value = database.read("search_history", LibraryCodec::decodeSearch).sortedByDescending { it.usedAtMillis }
        _activityHistory.value = database.read("activity_history", LibraryCodec::decodeActivity).sortedByDescending { it.occurredAtMillis }
        _trashRecords.value = database.read("trash_records", LibraryCodec::decodeTrash)
            .filter { it.state != TrashState.DELETED }
            .sortedByDescending { it.trashedAtMillis }
    }

    companion object {
        const val MAX_FAVORITES = 10_000
        const val MAX_RECENT_FILES = 50
        const val MAX_SEARCH_HISTORY = 50
        const val MAX_ACTIVITY_HISTORY = 100
    }
}

private class UserLibraryDatabase(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        listOf("favorites", "recent_files", "search_history", "activity_history", "trash_records").forEach { table ->
            db.execSQL("CREATE TABLE $table (id TEXT PRIMARY KEY NOT NULL, timestamp INTEGER NOT NULL, state TEXT, provider_id TEXT, reference_id TEXT, payload TEXT NOT NULL)")
            db.execSQL("CREATE INDEX idx_${table}_timestamp ON $table(timestamp DESC)")
            db.execSQL("CREATE INDEX idx_${table}_state ON $table(state)")
            db.execSQL("CREATE INDEX idx_${table}_provider_reference ON $table(provider_id, reference_id)")
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var version = oldVersion
        if (version == 1) {
            listOf("favorites", "recent_files", "search_history", "activity_history", "trash_records").forEach { table ->
                db.execSQL("ALTER TABLE $table ADD COLUMN provider_id TEXT")
                db.execSQL("ALTER TABLE $table ADD COLUMN reference_id TEXT")
                db.execSQL("CREATE INDEX idx_${table}_provider_reference ON $table(provider_id, reference_id)")
            }
            version = 2
        }
        check(version == newVersion) { "No user-library migration from $oldVersion to $newVersion" }
    }

    fun upsert(table: String, id: String, payload: String, timestamp: Long, state: String?, providerId: String? = null, referenceId: String? = null) {
        val values = ContentValues().apply { put("id", id); put("timestamp", timestamp); put("state", state); put("provider_id", providerId); put("reference_id", referenceId); put("payload", payload) }
        writableDatabase.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun delete(table: String, id: String) { writableDatabase.delete(table, "id = ?", arrayOf(id)) }
    fun clear(table: String) { writableDatabase.delete(table, null, null) }
    fun trim(table: String, keep: Int) {
        writableDatabase.execSQL("DELETE FROM $table WHERE id NOT IN (SELECT id FROM $table ORDER BY timestamp DESC LIMIT ?)", arrayOf(keep))
    }

    fun <T> read(table: String, decode: (String) -> T?): List<T> = readableDatabase.query(
        table, arrayOf("payload"), null, null, null, null, "timestamp DESC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) decode(cursor.getString(0))?.let(::add) } }

    companion object { const val NAME = "step3_user_library.db"; const val VERSION = 2 }
}

internal object LibraryCodec {
    private fun reference(value: FileReference) = JSONObject().apply {
        put("providerId", value.providerId); put("opaqueId", value.opaqueId); put("uri", value.uri); put("path", value.path)
    }
    private fun decodeReference(value: JSONObject) = FileReference(
        value.getString("providerId"), value.getString("opaqueId"), value.optStringOrNull("uri"), value.optStringOrNull("path")
    )
    private fun location(value: BrowserLocation?) = value?.let { JSONObject().apply {
        put("providerId", it.providerId); put("id", it.id); put("displayName", it.displayName); put("reference", it.reference)
        put("rootReference", it.rootReference); put("storageId", it.storageId); put("readable", it.readable); put("writable", it.writable)
    } }
    private fun decodeLocation(value: JSONObject?) = value?.let { BrowserLocation(
        it.getString("providerId"), it.getString("id"), it.getString("displayName"), it.getString("reference"),
        it.getString("rootReference"), it.getString("storageId"), it.optBoolean("readable", true), it.optBoolean("writable", false)
    ) }
    private fun scoped(value: ScopedFileReference?) = value?.let { JSONObject().apply {
        put("reference", reference(it.reference)); put("rootReference", it.rootReference); put("storageId", it.storageId)
    } }
    private fun decodeScoped(value: JSONObject?) = value?.let {
        ScopedFileReference(decodeReference(it.getJSONObject("reference")), it.getString("rootReference"), it.getString("storageId"))
    }
    fun favorite(item: FavoriteItem) = JSONObject().apply {
        put("id", item.id); put("reference", reference(item.reference)); put("rootReference", item.rootReference); put("storageId", item.storageId)
        put("parent", location(item.parentLocation)); put("displayName", item.displayName); put("type", item.type.name); put("addedAt", item.addedAtMillis)
        put("lastOpenedAt", item.lastOpenedAtMillis); put("lastValidatedAt", item.lastValidatedAtMillis); put("status", item.status.name); put("trashId", item.trashId)
    }.toString()
    fun decodeFavorite(raw: String): FavoriteItem? = runCatching { JSONObject(raw).let { o -> FavoriteItem(
        o.getString("id"), decodeReference(o.getJSONObject("reference")), o.getString("rootReference"), o.getString("storageId"),
        decodeLocation(o.optJSONObject("parent")), o.getString("displayName"), enumValueOf(o.getString("type")), o.getLong("addedAt"),
        o.optLongOrNull("lastOpenedAt"), o.optLongOrNull("lastValidatedAt"), enumValueOf(o.optString("status", LibraryItemStatus.AVAILABLE.name)), o.optStringOrNull("trashId")
    ) } }.getOrNull()
    fun recent(item: RecentFile) = JSONObject().apply {
        put("id", item.id); put("reference", reference(item.reference)); put("rootReference", item.rootReference); put("storageId", item.storageId)
        put("parent", location(item.parentLocation)); put("displayName", item.displayName); put("type", item.type.name); put("openedAt", item.openedAtMillis)
        put("status", item.status.name); put("trashId", item.trashId)
    }.toString()
    fun decodeRecent(raw: String): RecentFile? = runCatching { JSONObject(raw).let { o -> RecentFile(
        o.getString("id"), decodeReference(o.getJSONObject("reference")), o.getString("rootReference"), o.getString("storageId"),
        decodeLocation(o.optJSONObject("parent")), o.getString("displayName"), enumValueOf(o.getString("type")), o.getLong("openedAt"),
        enumValueOf(o.optString("status", LibraryItemStatus.AVAILABLE.name)), o.optStringOrNull("trashId")
    ) } }.getOrNull()
    fun search(item: SearchHistoryItem) = JSONObject().apply { put("normalized", item.normalizedQuery); put("display", item.displayQuery); put("usedAt", item.usedAtMillis) }.toString()
    fun decodeSearch(raw: String): SearchHistoryItem? = runCatching { JSONObject(raw).let { SearchHistoryItem(it.getString("normalized"), it.getString("display"), it.getLong("usedAt")) } }.getOrNull()
    fun activity(item: ActivityEntry) = JSONObject().apply { put("id", item.id); put("kind", item.kind.name); put("summary", item.summary); put("count", item.itemCount); put("at", item.occurredAtMillis); put("operationId", item.operationId) }.toString()
    fun decodeActivity(raw: String): ActivityEntry? = runCatching { JSONObject(raw).let { ActivityEntry(it.getString("id"), enumValueOf(it.getString("kind")), it.getString("summary"), it.getLong("count"), it.getLong("at"), it.optStringOrNull("operationId")) } }.getOrNull()
    fun trash(item: TrashRecord) = JSONObject().apply {
        put("id", item.id); put("backend", item.backend.name); put("originalReference", reference(item.originalReference)); put("originalParent", location(item.originalParent))
        put("originalName", item.originalName); put("type", item.type.name); put("size", item.sizeBytes); put("modified", item.modifiedAtMillis)
        put("trashReference", scoped(item.trashReference)); put("containerReference", scoped(item.containerReference))
        put("trashedAt", item.trashedAtMillis); put("updatedAt", item.updatedAtMillis); put("state", item.state.name); put("operationId", item.operationId); put("failure", item.failureReason)
        put("restoreDestination", location(item.restoreDestination)); put("restoreName", item.restoreName); put("restoreReplace", item.restoreReplace)
        put("restoreReplacePhase", item.restoreReplacePhase.name); put("restoreStageName", item.restoreStageName)
        put("restoreCommitIdentity", item.restoreCommitIdentity); put("restoreCommittedReference", scoped(item.restoreCommittedReference))
    }.toString()
    fun decodeTrash(raw: String): TrashRecord? = runCatching { JSONObject(raw).let { o ->
        val trash = decodeScoped(o.optJSONObject("trashReference"))
        val container = decodeScoped(o.optJSONObject("containerReference"))
        TrashRecord(
            id = o.getString("id"),
            backend = enumValueOf(o.getString("backend")),
            originalReference = decodeReference(o.getJSONObject("originalReference")),
            originalParent = requireNotNull(decodeLocation(o.getJSONObject("originalParent"))),
            originalName = o.getString("originalName"),
            type = enumValueOf(o.getString("type")),
            sizeBytes = o.optLongOrNull("size"),
            modifiedAtMillis = o.optLongOrNull("modified"),
            trashReference = trash,
            containerReference = container,
            trashedAtMillis = o.getLong("trashedAt"),
            updatedAtMillis = o.getLong("updatedAt"),
            state = enumValueOf(o.getString("state")),
            operationId = o.optStringOrNull("operationId"),
            failureReason = o.optStringOrNull("failure"),
            restoreDestination = decodeLocation(o.optJSONObject("restoreDestination")),
            restoreName = o.optStringOrNull("restoreName"),
            restoreReplace = o.optBoolean("restoreReplace", false),
            restoreReplacePhase = runCatching { enumValueOf<RestoreReplacePhase>(o.optString("restoreReplacePhase", RestoreReplacePhase.NONE.name)) }.getOrDefault(RestoreReplacePhase.NONE),
            restoreStageName = o.optStringOrNull("restoreStageName"),
            restoreCommitIdentity = o.optStringOrNull("restoreCommitIdentity"),
            restoreCommittedReference = decodeScoped(o.optJSONObject("restoreCommittedReference")),
        )
    } }.getOrNull()
    private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }
    private fun JSONObject.optLongOrNull(name: String): Long? = if (isNull(name) || !has(name)) null else getLong(name)
}
