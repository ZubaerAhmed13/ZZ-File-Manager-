package com.zz.filemanager.core.library

import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntryType
import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.model.ScopedFileReference

enum class LibraryItemStatus { AVAILABLE, UNAVAILABLE, TRASHED }

data class FavoriteItem(
    val id: String,
    val reference: FileReference,
    val rootReference: String,
    val storageId: String,
    val parentLocation: BrowserLocation?,
    val displayName: String,
    val type: FileEntryType,
    val addedAtMillis: Long,
    val lastOpenedAtMillis: Long? = null,
    val lastValidatedAtMillis: Long? = null,
    val status: LibraryItemStatus = LibraryItemStatus.AVAILABLE,
    val trashId: String? = null,
)

data class RecentFile(
    val id: String,
    val reference: FileReference,
    val rootReference: String,
    val storageId: String,
    val parentLocation: BrowserLocation?,
    val displayName: String,
    val type: FileEntryType,
    val openedAtMillis: Long,
    val status: LibraryItemStatus = LibraryItemStatus.AVAILABLE,
    val trashId: String? = null,
)

data class SearchHistoryItem(
    val normalizedQuery: String,
    val displayQuery: String,
    val usedAtMillis: Long,
)

enum class ActivityKind { COPIED, MOVED, RENAMED, DELETED_PERMANENTLY, TRASHED, RESTORED, TRASH_EMPTIED, CREATED }

data class ActivityEntry(
    val id: String,
    val kind: ActivityKind,
    val summary: String,
    val itemCount: Long,
    val occurredAtMillis: Long,
    val operationId: String? = null,
)

enum class TrashBackendType { APP_MANAGED, MEDIA_STORE }
enum class TrashState { PREPARING, MOVING, COPYING, TRASHED, RESTORING, DELETE_PENDING, DELETED, INTERRUPTED, FAILED, CORRUPTED }

/**
 * Durable phases for the only-good-copy Restore + Replace path.
 *
 * STAGING is persisted before the recycle payload leaves its container. STAGED proves that the
 * payload is at the hidden destination sibling. COMMITTING is persisted before the atomic replace
 * boundary together with a provider mutation identity. COMMITTED is persisted immediately after
 * the final entry is proven to be the staged payload. NONE is used for every non-Replace restore.
 */
enum class RestoreReplacePhase { NONE, STAGING, STAGED, COMMITTING, COMMITTED }

data class TrashRecord(
    val id: String,
    val backend: TrashBackendType,
    val originalReference: FileReference,
    val originalParent: BrowserLocation,
    val originalName: String,
    val type: FileEntryType,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
    val trashReference: ScopedFileReference?,
    val containerReference: ScopedFileReference?,
    val trashedAtMillis: Long,
    val updatedAtMillis: Long,
    val state: TrashState,
    val operationId: String? = null,
    val failureReason: String? = null,
    val restoreDestination: BrowserLocation? = null,
    val restoreName: String? = null,
    val restoreReplace: Boolean = false,
    val restoreReplacePhase: RestoreReplacePhase = RestoreReplacePhase.NONE,
    val restoreStageName: String? = null,
    val restoreCommitIdentity: String? = null,
    val restoreCommittedReference: ScopedFileReference? = null,
)

fun FileReference.stableIdentity(rootReference: String, storageId: String): String =
    "$providerId::$storageId::$rootReference::$opaqueId"
