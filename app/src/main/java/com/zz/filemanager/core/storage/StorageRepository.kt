package com.zz.filemanager.core.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.zz.filemanager.R
import com.zz.filemanager.core.model.Breadcrumb
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.model.MediaCategory
import com.zz.filemanager.core.model.OpenFileRequest
import com.zz.filemanager.core.model.StorageLocation
import com.zz.filemanager.core.model.StorageType
import com.zz.filemanager.core.preferences.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

class StorageRepository(
    private val context: Context,
    private val preferences: PreferencesRepository,
) : BrowserStorage {
    private val providers: Map<String, StorageProvider> = listOf(
        LocalStorageProvider(context), SafStorageProvider(context), MediaStoreProvider(context)
    ).associateBy { it.id }

    suspend fun discoverStorageLocations(): List<StorageLocation> = withContext(Dispatchers.IO) {
        val local = discoverLocalVolumes()
        val saf = validSafLocations().map { location ->
            StorageLocation(
                id = location.storageId,
                displayName = location.displayName,
                type = StorageType.SAF_TREE,
                totalBytes = null,
                freeBytes = null,
                readable = location.readable,
                writable = location.readable && location.writable,
                available = location.readable,
                root = location,
            )
        }
        local + saf
    }

    suspend fun validSafLocations(): List<BrowserLocation> = withContext(Dispatchers.IO) {
        val persisted = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        preferences.safLocations.first().map { location ->
            if (location.rootReference in persisted) location.copy(readable = true) else location.copy(readable = false, writable = false)
        }
    }

    suspend fun registerSafLocation(uri: Uri): BrowserLocation = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(uri, readWrite) }
            .recoverCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .getOrElse { throw StorageAccessException.PermissionRequired(it) }
        val document = DocumentFile.fromTreeUri(context, uri)
        val label = document?.name?.takeIf { it.isNotBlank() } ?: "Folder location"
        val writable = document?.canWrite() == true
        val location = BrowserLocation(
            providerId = SafStorageProvider.ID,
            id = "saf:${uri}",
            displayName = label,
            reference = uri.toString(),
            rootReference = uri.toString(),
            storageId = "saf:$label",
            readable = true,
            writable = writable,
        )
        preferences.addSafLocation(location)
        location
    }

    suspend fun removeSafLocation(location: BrowserLocation) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(location.rootReference)
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.recoverCatching {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        preferences.removeSafLocation(location.rootReference)
    }

    fun mediaLocation(category: MediaCategory): BrowserLocation {
        val label = when (category) {
            MediaCategory.IMAGES -> context.getString(R.string.images)
            MediaCategory.VIDEOS -> context.getString(R.string.videos)
            MediaCategory.AUDIO -> context.getString(R.string.audio)
            MediaCategory.DOCUMENTS -> context.getString(R.string.documents)
            MediaCategory.DOWNLOADS -> context.getString(R.string.downloads)
            MediaCategory.APKS -> context.getString(R.string.apks)
        }
        return BrowserLocation(
            providerId = MediaStoreProvider.ID,
            id = "media:${category.name}",
            displayName = label,
            reference = category.name,
            rootReference = category.name,
            storageId = "media:${category.name.lowercase()}",
            readable = true,
            writable = false,
        )
    }

    override suspend fun listChildren(location: BrowserLocation): List<FileEntry> = provider(location).listChildren(location)
    override suspend fun resolveParent(location: BrowserLocation): BrowserLocation? = provider(location).resolveParent(location)
    override suspend fun breadcrumbs(location: BrowserLocation): List<Breadcrumb> = provider(location).breadcrumbs(location)
    override suspend fun remember(location: BrowserLocation) { preferences.addRecent(location); preferences.setLastLocation(location) }

    /**
     * Returns the last browsed location only when its provider/resource is still valid enough to reopen.
     * A revoked SAF grant or removed local directory is intentionally rejected instead of restoring a dead screen.
     */
    suspend fun restorableLastLocation(): BrowserLocation? = withContext(Dispatchers.IO) {
        val location = preferences.lastLocation.first() ?: return@withContext null
        when (location.providerId) {
            LocalStorageProvider.ID -> {
                val root = runCatching { File(location.rootReference).canonicalFile }.getOrNull() ?: return@withContext null
                val current = runCatching { File(location.reference).canonicalFile }.getOrNull() ?: return@withContext null
                val insideRoot = current == root || current.path.startsWith(root.path + File.separator)
                location.takeIf { insideRoot && current.exists() && current.isDirectory && current.canRead() }
            }
            SafStorageProvider.ID -> {
                val grant = validSafLocations().firstOrNull { it.rootReference == location.rootReference && it.readable }
                    ?: return@withContext null
                if (location.reference == location.rootReference) {
                    location.copy(readable = true, writable = grant.writable)
                } else {
                    val item = FileReference(
                        providerId = SafStorageProvider.ID,
                        opaqueId = location.id,
                        uri = location.reference,
                    )
                    location.copy(readable = true, writable = grant.writable).takeIf { providers.getValue(SafStorageProvider.ID).exists(item) }
                }
            }
            MediaStoreProvider.ID -> location.takeIf { runCatching { MediaCategory.valueOf(location.reference) }.isSuccess }
            else -> null
        }
    }

    fun broadStorageAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        else -> ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    override fun openRequest(entry: FileEntry): OpenFileRequest? {
        val uri = entry.reference.uri?.let(Uri::parse) ?: entry.reference.path?.let { path ->
            runCatching { FileProvider.getUriForFile(context, "${context.packageName}.files", File(path)) }.getOrNull()
        } ?: return null
        return OpenFileRequest(uri.toString(), entry.mimeType ?: "*/*")
    }

    private fun provider(location: BrowserLocation): StorageProvider = providers[location.providerId]
        ?: throw StorageAccessException.Unavailable()

    @Suppress("DEPRECATION")
    private fun discoverLocalVolumes(): List<StorageLocation> {
        val manager = context.getSystemService(StorageManager::class.java)
        val legacyRoots = context.getExternalFilesDirs(null).mapNotNull(::deriveSharedRoot)
        val results = linkedMapOf<String, StorageLocation>()
        manager.storageVolumes.forEachIndexed { index, volume ->
            val directory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) volume.directory else legacyRoots.getOrNull(index)
            if (directory != null) {
                val canonical = runCatching { directory.canonicalFile }.getOrNull() ?: return@forEachIndexed
                val id = volume.uuid ?: if (volume.isPrimary) "primary" else canonical.path
                val label = if (volume.isPrimary) context.getString(R.string.internal_storage) else volume.getDescription(context)
                val stats = capacity(canonical)
                val root = BrowserLocation(
                    providerId = LocalStorageProvider.ID,
                    id = "local:${canonical.path}",
                    displayName = label,
                    reference = canonical.path,
                    rootReference = canonical.path,
                    storageId = id,
                    readable = canonical.canRead(),
                    writable = canonical.canWrite(),
                )
                results[canonical.path] = StorageLocation(
                    id = id,
                    displayName = label,
                    type = when { volume.isPrimary -> StorageType.INTERNAL; volume.isRemovable -> StorageType.SD_CARD; else -> StorageType.EXTERNAL_VOLUME },
                    totalBytes = stats?.first,
                    freeBytes = stats?.second,
                    readable = canonical.canRead(),
                    writable = canonical.canWrite(),
                    available = canonical.exists(),
                    root = root,
                )
            }
        }
        if (results.isEmpty()) {
            val fallback = Environment.getExternalStorageDirectory()
            if (fallback != null) {
                val canonical = runCatching { fallback.canonicalFile }.getOrNull()
                if (canonical != null) {
                    val stats = capacity(canonical)
                    val root = BrowserLocation(LocalStorageProvider.ID, "local:${canonical.path}", context.getString(R.string.internal_storage), canonical.path, canonical.path, "primary", canonical.canRead(), canonical.canWrite())
                    results[canonical.path] = StorageLocation("primary", context.getString(R.string.internal_storage), StorageType.INTERNAL, stats?.first, stats?.second, canonical.canRead(), canonical.canWrite(), canonical.exists(), root)
                }
            }
        }
        return results.values.toList()
    }

    private fun deriveSharedRoot(appExternal: File?): File? {
        val path = appExternal?.absolutePath ?: return null
        val marker = "/Android/data/"
        if (!path.contains(marker)) return null
        return File(path.substringBefore(marker))
    }

    private fun capacity(directory: File): Pair<Long, Long>? = runCatching {
        val stat = StatFs(directory.path)
        stat.totalBytes to stat.availableBytes
    }.getOrNull()
}
