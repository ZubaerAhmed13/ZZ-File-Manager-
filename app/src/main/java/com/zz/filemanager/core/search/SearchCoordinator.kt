package com.zz.filemanager.core.search

import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.FileEntryType
import com.zz.filemanager.core.storage.StorageProviderRegistry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.text.Normalizer
import java.util.Locale

fun interface SearchRootSource {
    suspend fun accessibleRoots(): List<BrowserLocation>
}

class SearchCoordinator(
    private val providers: StorageProviderRegistry,
    private val roots: SearchRootSource,
    private val batchSize: Int = 64,
    private val cancellationInterval: Int = 32,
) {
    fun search(query: FileSearchQuery, currentLocation: BrowserLocation?): Flow<SearchUpdate> = flow {
        require(query.minSizeBytes == null || query.minSizeBytes >= 0L)
        require(query.maxSizeBytes == null || query.maxSizeBytes >= 0L)
        require(query.minSizeBytes == null || query.maxSizeBytes == null || query.minSizeBytes <= query.maxSizeBytes)
        val searchRoots = resolveRoots(query.scope, currentLocation)
        val failures = mutableListOf<SearchFailure>()
        val batch = ArrayList<SearchResult>(batchSize)
        var scanned = 0L
        var totalMatches = 0L

        data class Pending(val location: BrowserLocation, val relativePrefix: String, val recursive: Boolean)
        val queue = ArrayDeque<Pending>()
        searchRoots.forEach { (location, recursive) -> queue.addLast(Pending(location, "", recursive)) }
        val visitedLocations = hashSetOf<String>()

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val pending = queue.removeFirst()
            if (!visitedLocations.add(pending.location.identity)) continue
            val entries = try {
                providers.providerFor(pending.location.providerId).listChildren(pending.location)
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                failures += SearchFailure(pending.location.displayName, error.message ?: error::class.java.simpleName)
                continue
            }
            for (entry in entries) {
                scanned++
                if (scanned % cancellationInterval == 0L) currentCoroutineContext().ensureActive()
                if (isReservedInternal(entry)) continue
                val relative = if (pending.relativePrefix.isEmpty()) entry.name else "${pending.relativePrefix}/${entry.name}"
                if (SearchMatcher.matches(query, entry, relative)) {
                    totalMatches++
                    batch += entry.toResult(pending.location, relative, SearchMatcher.relevance(query.text, entry.name, relative))
                    if (batch.size >= batchSize) {
                        emit(SearchUpdate.Batch(SearchResultSorter.sort(batch.toList(), query.sort), scanned, totalMatches))
                        batch.clear()
                    }
                }
                if (pending.recursive && entry.isDirectory && !entry.isSymbolicLink) {
                    queue.addLast(Pending(entry.asLocation(pending.location), relative, true))
                }
            }
        }
        if (batch.isNotEmpty()) emit(SearchUpdate.Batch(SearchResultSorter.sort(batch.toList(), query.sort), scanned, totalMatches))
        emit(SearchUpdate.Completed(scanned, totalMatches, failures))
    }

    private suspend fun resolveRoots(scope: SearchScope, current: BrowserLocation?): List<Pair<BrowserLocation, Boolean>> = when (scope) {
        SearchScope.CURRENT_FOLDER -> listOfNotNull(current?.let { it to false })
        SearchScope.CURRENT_FOLDER_RECURSIVE -> listOfNotNull(current?.let { it to true })
        SearchScope.CURRENT_STORAGE -> {
            val all = roots.accessibleRoots()
            val same = current?.let { target -> all.filter { it.storageId == target.storageId } }.orEmpty()
            (same.ifEmpty { listOfNotNull(current) }).map { it to true }
        }
        SearchScope.ALL_ACCESSIBLE_LOCATIONS -> roots.accessibleRoots().distinctBy { it.identity }.map { it to true }
    }

    private fun FileEntry.asLocation(parent: BrowserLocation) = BrowserLocation(
        providerId = reference.providerId,
        id = id,
        displayName = name,
        reference = reference.uri ?: reference.path ?: reference.opaqueId,
        rootReference = parent.rootReference,
        storageId = parent.storageId,
        readable = isReadable,
        writable = isWritable,
    )

    private fun FileEntry.toResult(parent: BrowserLocation, relative: String, score: Int) = SearchResult(
        id = "${reference.providerId}::${reference.opaqueId}", reference = reference, parentLocation = parent,
        name = name, relativePath = relative, type = type, sizeBytes = sizeBytes, modifiedAtMillis = modifiedAtMillis,
        mimeType = mimeType, storageId = storageId, readable = isReadable, writable = isWritable, relevance = score,
    )

    private fun isReservedInternal(entry: FileEntry): Boolean =
        entry.name.equals(RESERVED_RECYCLE_DIRECTORY, ignoreCase = true) ||
            entry.name.startsWith(".zztrash-", ignoreCase = true)

    companion object {
        const val RESERVED_RECYCLE_DIRECTORY = ".ZZFileManagerRecycle"
        internal fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    }
}

object SearchMatcher {
    fun matches(query: FileSearchQuery, entry: FileEntry, relativePath: String): Boolean {
        if (entry.isHidden && !query.includeHidden) return false
        if (entry.isDirectory && !query.includeDirectories) return false
        if (!entry.isDirectory && !query.includeFiles) return false
        if (!matchesType(query.typeFilter, entry.type)) return false
        val extensions = query.extensions.mapTo(hashSetOf()) { it.trim().removePrefix(".").lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
        if (extensions.isNotEmpty() && entry.extension?.lowercase(Locale.ROOT) !in extensions) return false
        if (query.minSizeBytes != null && (entry.sizeBytes == null || entry.sizeBytes < query.minSizeBytes)) return false
        if (query.maxSizeBytes != null && (entry.sizeBytes == null || entry.sizeBytes > query.maxSizeBytes)) return false
        if (query.modifiedAfter != null && (entry.modifiedAtMillis == null || entry.modifiedAtMillis < query.modifiedAfter)) return false
        if (query.modifiedBefore != null && (entry.modifiedAtMillis == null || entry.modifiedAtMillis > query.modifiedBefore)) return false
        val needle = SearchCoordinator.normalize(query.text.trim())
        if (needle.isEmpty()) return true
        val name = SearchCoordinator.normalize(entry.name)
        val path = SearchCoordinator.normalize(relativePath)
        return when (query.matchMode) {
            SearchMatchMode.EXACT -> name == needle
            SearchMatchMode.STARTS_WITH -> name.startsWith(needle)
            SearchMatchMode.CONTAINS -> needle in name || needle in path
        }
    }

    fun relevance(text: String, nameValue: String, pathValue: String): Int {
        val query = SearchCoordinator.normalize(text.trim())
        if (query.isEmpty()) return 0
        val name = SearchCoordinator.normalize(nameValue)
        val path = SearchCoordinator.normalize(pathValue)
        return when { name == query -> 400; name.startsWith(query) -> 300; query in name -> 200; query in path -> 100; else -> 0 }
    }

    private fun matchesType(filter: SearchTypeFilter, type: FileEntryType): Boolean = when (filter) {
        SearchTypeFilter.ALL -> true
        SearchTypeFilter.FOLDERS -> type == FileEntryType.DIRECTORY
        SearchTypeFilter.IMAGES -> type == FileEntryType.IMAGE
        SearchTypeFilter.VIDEOS -> type == FileEntryType.VIDEO
        SearchTypeFilter.AUDIO -> type == FileEntryType.AUDIO
        SearchTypeFilter.DOCUMENTS -> type in setOf(FileEntryType.PDF, FileEntryType.TEXT, FileEntryType.DOCUMENT, FileEntryType.SPREADSHEET, FileEntryType.PRESENTATION)
        SearchTypeFilter.ARCHIVES -> type == FileEntryType.ARCHIVE
        SearchTypeFilter.APKS -> type == FileEntryType.APK
        SearchTypeFilter.OTHER -> type in setOf(FileEntryType.GENERIC, FileEntryType.UNKNOWN)
    }
}
