package com.zz.filemanager.core.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.zz.filemanager.R
import com.zz.filemanager.core.model.Breadcrumb
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.model.ScopedFileReference
import com.zz.filemanager.core.util.FileClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

class SafStorageProvider(private val context: Context) : WritableStorageProvider {
    override val id: String = ID
    override suspend fun listChildren(location: BrowserLocation): List<FileEntry> = withContext(Dispatchers.IO) { try { val directory = documentFor(location) ?: throw StorageAccessException.Unavailable(); if (!directory.exists()) throw StorageAccessException.Unavailable(); if (!directory.isDirectory) throw StorageAccessException.Io(); if (!directory.canRead()) throw StorageAccessException.PermissionRequired(); directory.listFiles().map { child -> coroutineContext.ensureActive(); toEntry(child, location.storageId) } } catch (error: SecurityException) { throw StorageAccessException.PermissionRequired(error) } }
    override suspend fun getMetadata(item: FileReference): FileEntry? = withContext(Dispatchers.IO) { val uri = item.uri?.let(Uri::parse) ?: return@withContext null; try { DocumentFile.fromSingleUri(context, uri)?.takeIf { it.exists() }?.let { toEntry(it, "saf") } } catch (_: SecurityException) { null } }
    override suspend fun openInputStream(item: FileReference): InputStream = withContext(Dispatchers.IO) { val uri = item.uri?.let(Uri::parse) ?: throw StorageAccessException.Unavailable(); try { context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString()) } catch (error: SecurityException) { throw StorageAccessException.PermissionRequired(error) } }
    override suspend fun exists(item: FileReference): Boolean = withContext(Dispatchers.IO) { val uri = item.uri?.let(Uri::parse) ?: return@withContext false; try { DocumentFile.fromSingleUri(context, uri)?.exists() == true } catch (_: SecurityException) { false } }

    override suspend fun resolveParent(location: BrowserLocation): BrowserLocation? = withContext(Dispatchers.IO) {
        if (location.reference == location.rootReference) return@withContext null
        val tree = Uri.parse(location.rootReference); val current = Uri.parse(location.reference); val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return@withContext null; val currentDocId = runCatching { DocumentsContract.getDocumentId(current) }.getOrNull() ?: return@withContext null
        if (currentDocId == rootDocId || !currentDocId.startsWith("$rootDocId/")) return@withContext null; val parentDocId = currentDocId.substringBeforeLast('/'); if (parentDocId.length < rootDocId.length) return@withContext null
        if (parentDocId == rootDocId) return@withContext location.copy(id = "saf:$rootDocId", displayName = rootName(location), reference = location.rootReference)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentDocId); val label = parentDocId.substringAfterLast('/').substringAfterLast(':'); location.copy(id = "saf:$parentDocId", displayName = label, reference = parentUri.toString())
    }

    override suspend fun breadcrumbs(location: BrowserLocation): List<Breadcrumb> = withContext(Dispatchers.IO) {
        val tree = Uri.parse(location.rootReference); val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return@withContext listOf(Breadcrumb(rootName(location), location)); val rootLocation = location.copy(id = "saf:$rootDocId", displayName = rootName(location), reference = location.rootReference); val result = mutableListOf(Breadcrumb(rootName(location), rootLocation)); if (location.reference == location.rootReference) return@withContext result
        val currentDocId = runCatching { DocumentsContract.getDocumentId(Uri.parse(location.reference)) }.getOrNull() ?: return@withContext result; if (!currentDocId.startsWith("$rootDocId/")) return@withContext result; val relative = currentDocId.removePrefix("$rootDocId/"); var docId = rootDocId
        relative.split('/').filter { it.isNotBlank() }.forEach { segment -> docId += "/$segment"; val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId); result += Breadcrumb(segment, location.copy(id = "saf:$docId", displayName = segment, reference = uri.toString())) }
        result
    }

    override suspend fun capabilities(location: BrowserLocation): ProviderCapabilities { val values = mutableSetOf(StorageCapability.READ); if (location.writable) values += setOf(StorageCapability.WRITE, StorageCapability.CREATE_FILE, StorageCapability.CREATE_DIRECTORY, StorageCapability.DELETE, StorageCapability.RENAME); return ProviderCapabilities(values) }
    override suspend fun createDirectory(parent: BrowserLocation, name: String): FileEntry = withContext(Dispatchers.IO) { requireSafeLeafName(name); val directory = documentFor(parent) ?: throw StorageAccessException.Unavailable(); if (!directory.canWrite()) throw StorageAccessException.ReadOnly(); if (directory.findFile(name) != null) throw StorageAccessException.Io(IllegalStateException("destination exists")); val created = try { directory.createDirectory(name) ?: throw StorageAccessException.Io() } catch (error: SecurityException) { throw StorageAccessException.PermissionRequired(error) }; toEntry(created, parent.storageId) }
    override suspend fun createFile(parent: BrowserLocation, name: String, mimeType: String?): FileEntry = withContext(Dispatchers.IO) { requireSafeLeafName(name); val directory = documentFor(parent) ?: throw StorageAccessException.Unavailable(); if (!directory.canWrite()) throw StorageAccessException.ReadOnly(); if (directory.findFile(name) != null) throw StorageAccessException.Io(IllegalStateException("destination exists")); val created = try { directory.createFile(mimeType ?: "application/octet-stream", name) ?: throw StorageAccessException.Io() } catch (error: SecurityException) { throw StorageAccessException.PermissionRequired(error) }; toEntry(created, parent.storageId) }
    override suspend fun delete(item: ScopedFileReference): Boolean = withContext(Dispatchers.IO) { val document = DocumentFile.fromSingleUri(context, validateScopedUri(item)) ?: throw StorageAccessException.Unavailable(); try { if (!document.exists()) false else document.delete() } catch (error: SecurityException) { throw StorageAccessException.PermissionRequired(error) } }
    override suspend fun rename(item: ScopedFileReference, newName: String): FileEntry = withContext(Dispatchers.IO) { requireSafeLeafName(newName); val document = DocumentFile.fromSingleUri(context, validateScopedUri(item)) ?: throw StorageAccessException.Unavailable(); if (!document.canWrite()) throw StorageAccessException.ReadOnly(); try { if (!document.renameTo(newName)) throw StorageAccessException.Io(); toEntry(document, item.storageId) } catch (error: SecurityException) { throw StorageAccessException.PermissionRequired(error) } }
    override suspend fun openOutputStream(item: ScopedFileReference, truncate: Boolean): OutputStream = withContext(Dispatchers.IO) { val uri = validateScopedUri(item); try { runCatching { context.contentResolver.openOutputStream(uri, if (truncate) "rwt" else "wa") }.getOrNull() ?: context.contentResolver.openOutputStream(uri, if (truncate) "w" else "wa") ?: throw FileNotFoundException(uri.toString()) } catch (error: SecurityException) { throw StorageAccessException.PermissionRequired(error) } }
    override suspend fun findChild(parent: BrowserLocation, name: String): FileEntry? = withContext(Dispatchers.IO) { requireSafeLeafName(name); val directory = documentFor(parent) ?: return@withContext null; try { directory.findFile(name)?.let { toEntry(it, parent.storageId) } } catch (error: SecurityException) { throw StorageAccessException.PermissionRequired(error) } }
    override suspend fun freeBytes(location: BrowserLocation): Long? = null

    override suspend fun isSameOrDescendant(source: ScopedFileReference, destination: BrowserLocation): Boolean = withContext(Dispatchers.IO) {
        if (source.reference.providerId != ID || destination.providerId != ID) return@withContext false
        val tree = Uri.parse(source.rootReference); val sourceUri = validateScopedUri(source); val destinationUri = Uri.parse(destination.reference)
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return@withContext false
        val sourceDocId = runCatching { if (sourceUri == tree) rootDocId else DocumentsContract.getDocumentId(sourceUri) }.getOrNull() ?: return@withContext false
        val destinationDocId = runCatching { if (destinationUri == tree) rootDocId else DocumentsContract.getDocumentId(destinationUri) }.getOrNull() ?: return@withContext false
        destinationDocId == sourceDocId || destinationDocId.startsWith("$sourceDocId/")
    }

    private fun validateScopedUri(item: ScopedFileReference): Uri { val tree = Uri.parse(item.rootReference); val uri = item.reference.uri?.let(Uri::parse) ?: throw StorageAccessException.Unavailable(); val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrElse { throw StorageAccessException.PermissionRequired(it) }; val itemDocId = runCatching { if (uri == tree) rootDocId else DocumentsContract.getDocumentId(uri) }.getOrElse { throw StorageAccessException.PermissionRequired(it) }; if (itemDocId != rootDocId && !itemDocId.startsWith("$rootDocId/")) throw StorageAccessException.PermissionRequired(); return uri }
    private fun documentFor(location: BrowserLocation): DocumentFile? { val uri = Uri.parse(location.reference); return if (location.reference == location.rootReference) DocumentFile.fromTreeUri(context, uri) else DocumentFile.fromSingleUri(context, uri) }
    private fun toEntry(file: DocumentFile, storageId: String): FileEntry { val name = file.name ?: context.getString(R.string.unnamed_item); val mime = file.type; val type = FileClassifier.classify(name, mime, file.isDirectory); val modified = file.lastModified().takeIf { it > 0L }; val size = if (file.isFile) file.length().takeIf { it >= 0L } else null; return FileEntry(id = "saf:${file.uri}", reference = FileReference(ID, "saf:${file.uri}", uri = file.uri.toString()), name = name, extension = FileClassifier.extensionFor(name), mimeType = mime, type = type, sizeBytes = size, modifiedAtMillis = modified, createdAtMillis = null, isHidden = name.startsWith('.'), isReadable = file.canRead(), isWritable = file.canWrite(), childCount = null, storageId = storageId, thumbnailKey = if (file.isFile) "${file.uri}:${modified ?: 0L}:${size ?: -1L}" else null) }
    private fun requireSafeLeafName(name: String) { if (name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\') || name.indexOf('\u0000') >= 0) throw StorageAccessException.Io(IllegalArgumentException("invalid leaf name")) }
    private fun rootName(location: BrowserLocation): String = location.storageId.removePrefix("saf:").ifBlank { location.displayName }
    companion object { const val ID = "saf" }
}
