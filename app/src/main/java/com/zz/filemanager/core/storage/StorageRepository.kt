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
import android.provider.DocumentsContract
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
import com.zz.filemanager.core.model.CategoryMetric
import com.zz.filemanager.core.preferences.PreferencesRepository
import com.zz.filemanager.core.search.SearchRootSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class StorageRepository(
    private val context: Context,
    private val preferences: PreferencesRepository,
) : BrowserStorage, StorageProviderRegistry, SearchRootSource {
    private val mediaStoreProvider = MediaStoreProvider(context)
    private val builtInProviders: Map<String, StorageProvider> = listOf(
        LocalStorageProvider(context), SafStorageProvider(context), mediaStoreProvider
    ).associateBy { it.id }

    private data class RegisteredLocation(
        val provider: StorageProvider,
        val location: StorageLocation,
        val includeInGlobalSearch: Boolean,
    )

    /** Runtime registrations are rebuilt from durable connection/account metadata at app start. */
    private val registeredLocations = ConcurrentHashMap<String, RegisteredLocation>()

    fun registerExternalProvider(
        provider: StorageProvider,
        location: StorageLocation,
        includeInGlobalSearch: Boolean = false,
    ) {
        require(provider.id == location.root.providerId) { "Provider/location identity mismatch" }
        registeredLocations[provider.id] = RegisteredLocation(provider, location, includeInGlobalSearch)
    }

    fun unregisterExternalProvider(providerId: String) {
        registeredLocations.remove(providerId)
    }

    fun retainExternalProviders(providerIds: Set<String>) {
        registeredLocations.keys.filterNot(providerIds::contains).forEach(registeredLocations::remove)
    }

    fun registeredStorageLocations(): List<StorageLocation> = registeredLocations.values.map { it.location }

    suspend fun discoverStorageLocations(): List<StorageLocation> = withContext(Dispatchers.IO) {
        val local = discoverLocalVolumes()
        val saf = validSafLocations().map { location ->
            StorageLocation(
                id = location.storageId,
                displayName = location.displayName,
                type = safStorageType(location),
                totalBytes = null,
                freeBytes = null,
                readable = location.readable,
                writable = location.readable && location.writable,
                available = location.readable,
                root = location,
            )
        }
        (local + saf + registeredStorageLocations()).distinctBy { it.id }
    }

    /**
     * Global search intentionally excludes saved network/cloud roots. Remote recursion must start
     * only from a user-selected remote scope; otherwise rendering Search could auto-connect every
     * account/server. Removable SAF roots remain normal explicitly-authorized local roots.
     */
    override suspend fun accessibleRoots(): List<BrowserLocation> {
        val storageRoots = discoverStorageLocations()
            .filter { it.available && it.readable && it.type !in setOf(StorageType.NETWORK, StorageType.CLOUD) }
            .map { it.root }
        val explicitExternalSearchRoots = registeredLocations.values
            .filter { it.includeInGlobalSearch && it.location.available && it.location.readable }
            .map { it.location.root }
        val mediaRoots = MediaCategory.entries.map(::mediaLocation)
        return (storageRoots + explicitExternalSearchRoots + mediaRoots).distinctBy { it.identity }
    }

    /** Includes saved SAF roots even when their grant is missing so Home can show/reconnect them. */
    suspend fun validSafLocations(): List<BrowserLocation> = withContext(Dispatchers.IO) {
        val persisted = context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .associateBy { it.uri.toString() }
        preferences.safLocations.first().map { location ->
            val grant = persisted[location.rootReference]
            if (grant != null) {
                val document = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(location.rootReference)) }.getOrNull()
                val readable = document?.canRead() == true
                location.copy(readable = readable, writable = readable && grant.isWritePermission && document?.canWrite() == true)
            } else {
                location.copy(readable = false, writable = false)
            }
        }
    }

    suspend fun registerSafLocation(uri: Uri): BrowserLocation = registerSafLocation(uri, SafLocationKind.GENERIC)

    suspend fun registerSafLocation(uri: Uri, kind: SafLocationKind): BrowserLocation = withContext(Dispatchers.IO) {
        val grant = takeTreeGrant(uri)
        val document = DocumentFile.fromTreeUri(context, uri)
            ?: throw StorageAccessException.Unavailable()
        val label = document.name?.takeIf { it.isNotBlank() } ?: context.getString(R.string.folder_location)
        val writable = grant.second && document.canWrite()
        val stableId = stableSafStorageId(uri, kind)
        val displayName = when (kind) {
            SafLocationKind.SD_CARD -> "SD Card · $label"
            SafLocationKind.USB -> "USB · $label"
            SafLocationKind.CLOUD -> "Cloud · $label"
            SafLocationKind.GENERIC -> label
        }
        val location = BrowserLocation(
            providerId = SafStorageProvider.ID,
            id = "saf:${uri}",
            displayName = displayName,
            reference = uri.toString(),
            rootReference = uri.toString(),
            storageId = stableId,
            readable = true,
            writable = writable,
        )
        preferences.addSafLocation(location)
        location
    }

    /** Re-authorize a missing removable root without silently mapping it to another volume. */
    suspend fun reconnectSafLocation(saved: BrowserLocation, uri: Uri): BrowserLocation = withContext(Dispatchers.IO) {
        require(saved.providerId == SafStorageProvider.ID)
        val kind = safKind(saved)
        val expectedToken = stableSafVolumeTokenFromStorageId(saved.storageId)
        val observedToken = stableSafVolumeToken(uri)
        if (kind in setOf(SafLocationKind.SD_CARD, SafLocationKind.USB) && expectedToken != null && observedToken != null && expectedToken != observedToken) {
            throw StorageAccessException.Unavailable(IllegalStateException("Selected removable storage does not match the saved volume identity"))
        }
        val grant = takeTreeGrant(uri)
        val document = DocumentFile.fromTreeUri(context, uri) ?: throw StorageAccessException.Unavailable()
        val replacement = saved.copy(
            id = "saf:${uri}",
            reference = uri.toString(),
            rootReference = uri.toString(),
            readable = document.canRead(),
            writable = document.canRead() && grant.second && document.canWrite(),
            storageId = if (expectedToken != null) saved.storageId else stableSafStorageId(uri, kind),
        )
        preferences.removeSafLocation(saved.rootReference)
        preferences.addSafLocation(replacement)
        replacement
    }

    suspend fun removableStatus(location: BrowserLocation): RemovableStorageStatus = withContext(Dispatchers.IO) {
        if (location.providerId != SafStorageProvider.ID || safKind(location) !in setOf(SafLocationKind.SD_CARD, SafLocationKind.USB)) {
            return@withContext RemovableStorageStatus.UNSUPPORTED
        }
        val persisted = context.contentResolver.persistedUriPermissions.any { it.uri.toString() == location.rootReference && it.isReadPermission }
        if (!persisted) return@withContext RemovableStorageStatus.PERMISSION_LOST
        val document = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(location.rootReference)) }.getOrNull()
            ?: return@withContext RemovableStorageStatus.REMOVED
        if (!document.exists() || !document.canRead()) return@withContext RemovableStorageStatus.REMOVED
        if (!document.canWrite()) RemovableStorageStatus.READ_ONLY else RemovableStorageStatus.AVAILABLE
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

    suspend fun categoryMetric(category: MediaCategory): CategoryMetric = mediaStoreProvider.categoryMetric(category)

    override suspend fun listChildren(location: BrowserLocation): List<FileEntry> = providerFor(location.providerId).listChildren(location)

    override suspend fun listChildrenIncrementally(
        location: BrowserLocation,
        pageSize: Int,
        onPage: suspend (List<FileEntry>) -> Unit,
    ) {
        val provider = providerFor(location.providerId)
        if (provider is IncrementalStorageProvider) {
            provider.listChildrenIncrementally(location, pageSize, onPage)
        } else {
            onPage(provider.listChildren(location))
        }
    }

    override suspend fun resolveParent(location: BrowserLocation): BrowserLocation? = providerFor(location.providerId).resolveParent(location)
    override suspend fun breadcrumbs(location: BrowserLocation): List<Breadcrumb> = providerFor(location.providerId).breadcrumbs(location)
    override suspend fun remember(location: BrowserLocation) { preferences.addRecent(location); preferences.setLastLocation(location) }

    override fun providerFor(providerId: String): StorageProvider = builtInProviders[providerId]
        ?: registeredLocations[providerId]?.provider
        ?: throw StorageAccessException.Unavailable()

    override fun writableProviderFor(providerId: String): WritableStorageProvider? = providerForOrNull(providerId) as? WritableStorageProvider

    fun providerForOrNull(providerId: String): StorageProvider? = builtInProviders[providerId] ?: registeredLocations[providerId]?.provider

    suspend fun capabilities(location: BrowserLocation): ProviderCapabilities =
        writableProviderFor(location.providerId)?.capabilities(location) ?: ProviderCapabilities.ReadOnly

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
                    location.copy(readable = true, writable = grant.writable).takeIf { providerFor(SafStorageProvider.ID).exists(item) }
                }
            }
            MediaStoreProvider.ID -> location.takeIf { runCatching { MediaCategory.valueOf(location.reference.substringBefore("::")) }.isSuccess }
            else -> location.takeIf { providerForOrNull(location.providerId) != null }
        }
    }

    fun broadStorageAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        else -> ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    override fun openRequest(entry: FileEntry): OpenFileRequest? {
        val uri = contentUri(entry) ?: return null
        return OpenFileRequest(uri.toString(), entry.mimeType ?: "*/*")
    }

    fun shareRequests(entries: List<FileEntry>): List<OpenFileRequest> = entries
        .filterNot { it.isDirectory }
        .mapNotNull { entry -> contentUri(entry)?.let { OpenFileRequest(it.toString(), entry.mimeType ?: "*/*") } }

    private fun contentUri(entry: FileEntry): Uri? = entry.reference.uri?.let(Uri::parse)
        ?: entry.reference.path?.let { path ->
            runCatching { FileProvider.getUriForFile(context, "${context.packageName}.files", File(path)) }.getOrNull()
        }

    private fun takeTreeGrant(uri: Uri): Pair<Boolean, Boolean> {
        val resolver = context.contentResolver
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val writeGranted = runCatching {
            resolver.takePersistableUriPermission(uri, readWrite)
            true
        }.recoverCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            false
        }.getOrElse { throw StorageAccessException.PermissionRequired(it) }
        return true to writeGranted
    }

    private fun safStorageType(location: BrowserLocation): StorageType = when (safKind(location)) {
        SafLocationKind.SD_CARD -> StorageType.SD_CARD
        SafLocationKind.USB -> StorageType.USB
        SafLocationKind.CLOUD -> StorageType.CLOUD
        SafLocationKind.GENERIC -> StorageType.SAF_TREE
    }

    private fun safKind(location: BrowserLocation): SafLocationKind = when {
        location.storageId.startsWith("sd:") -> SafLocationKind.SD_CARD
        location.storageId.startsWith("usb:") -> SafLocationKind.USB
        location.storageId.startsWith("cloud-saf:") -> SafLocationKind.CLOUD
        else -> SafLocationKind.GENERIC
    }

    private fun stableSafStorageId(uri: Uri, kind: SafLocationKind): String {
        val token = stableSafVolumeToken(uri) ?: shortHash(uri.toString())
        val authority = uri.authority?.takeIf { it.isNotBlank() } ?: "documents"
        return "${kind.storagePrefix}:$authority:$token"
    }

    private fun stableSafVolumeToken(uri: Uri): String? = runCatching {
        DocumentsContract.getTreeDocumentId(uri).substringBefore(':').takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun stableSafVolumeTokenFromStorageId(storageId: String): String? {
        val first = storageId.indexOf(':')
        if (first < 0) return null
        val second = storageId.indexOf(':', first + 1)
        if (second < 0 || second == storageId.lastIndex) return null
        return storageId.substring(second + 1).takeIf { it.isNotBlank() }
    }

    private fun shortHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { "%02x".format(it) }

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
