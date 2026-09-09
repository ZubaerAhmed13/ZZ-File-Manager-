package com.zz.filemanager.core.storage

import android.content.Context
import android.net.Uri
import android.os.Build
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
import java.util.ArrayDeque
import kotlin.coroutines.coroutineContext

class SafStorageProvider(private val context: Context) : WritableStorageProvider {
    override val id: String = ID

    override suspend fun listChildren(location: BrowserLocation): List<FileEntry> = withContext(Dispatchers.IO) {
        try {
            val directory = documentFor(location) ?: throw StorageAccessException.Unavailable()
            if (!directory.exists()) throw StorageAccessException.Unavailable()
            if (!directory.isDirectory) throw StorageAccessException.Io()
            if (!directory.canRead()) throw StorageAccessException.PermissionRequired()

            childDocumentUris(location).map { childUri ->
                coroutineContext.ensureActive()
                documentForUri(childUri)?.let { toEntry(it, location.storageId) }
                    ?: throw StorageAccessException.Unavailable()
            }
        } catch (error: SecurityException) {
            throw StorageAccessException.PermissionRequired(error)
        }
    }

    override suspend fun getMetadata(item: FileReference): FileEntry? = withContext(Dispatchers.IO) {
        val uri = item.uri?.let(Uri::parse) ?: return@withContext null
        try {
            DocumentFile.fromSingleUri(context, uri)
                ?.takeIf { it.exists() }
                ?.let { toEntry(it, "saf") }
        } catch (_: SecurityException) {
            null
        }
    }

    override suspend fun openInputStream(item: FileReference): InputStream = withContext(Dispatchers.IO) {
        val uri = item.uri?.let(Uri::parse) ?: throw StorageAccessException.Unavailable()
        try {
            context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
        } catch (error: SecurityException) {
            throw StorageAccessException.PermissionRequired(error)
        }
    }

    override suspend fun exists(item: FileReference): Boolean = withContext(Dispatchers.IO) {
        val uri = item.uri?.let(Uri::parse) ?: return@withContext false
        try {
            DocumentFile.fromSingleUri(context, uri)?.exists() == true
        } catch (_: SecurityException) {
            false
        }
    }

    override suspend fun resolveParent(location: BrowserLocation): BrowserLocation? = withContext(Dispatchers.IO) {
        if (location.reference == location.rootReference) return@withContext null
        val tree = Uri.parse(location.rootReference)
        val path = documentPathIds(location) ?: return@withContext null
        if (path.size < 2) return@withContext null
        val parentDocId = path[path.lastIndex - 1]
        locationForDocumentId(location, tree, parentDocId)
    }

    override suspend fun breadcrumbs(location: BrowserLocation): List<Breadcrumb> = withContext(Dispatchers.IO) {
        val tree = Uri.parse(location.rootReference)
        val rootDocId = treeDocumentId(tree) ?: return@withContext listOf(Breadcrumb(rootName(location), location))
        val path = documentPathIds(location) ?: listOf(rootDocId)
        path.mapIndexed { index, documentId ->
            val crumbLocation = locationForDocumentId(location, tree, documentId)
            val label = if (index == 0) rootName(location) else crumbLocation.displayName
            Breadcrumb(label, crumbLocation.copy(displayName = label))
        }
    }

    override suspend fun capabilities(location: BrowserLocation): ProviderCapabilities = withContext(Dispatchers.IO) {
        try {
            val uri = documentUriFor(location)
            val document = documentForUri(uri) ?: return@withContext ProviderCapabilities.ReadOnly
            val values = mutableSetOf<StorageCapability>()
            if (document.canRead() && location.readable) values += StorageCapability.READ
            if (!location.writable) return@withContext ProviderCapabilities(values)

            val flags = documentFlags(uri)
            if (flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0) {
                values += setOf(
                    StorageCapability.WRITE,
                    StorageCapability.CREATE_FILE,
                    StorageCapability.CREATE_DIRECTORY,
                )
            }
            if (flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0) {
                values += StorageCapability.DELETE
            }
            if (flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0) {
                values += StorageCapability.RENAME
            }
            ProviderCapabilities(values)
        } catch (error: SecurityException) {
            throw StorageAccessException.PermissionRequired(error)
        }
    }

    override suspend fun createDirectory(parent: BrowserLocation, name: String): FileEntry = withContext(Dispatchers.IO) {
        requireSafeLeafName(name)
        if (StorageCapability.CREATE_DIRECTORY !in capabilities(parent)) throw StorageAccessException.ReadOnly()
        if (findChildInternal(parent, name) != null) {
            throw StorageAccessException.Io(IllegalStateException("destination exists"))
        }
        try {
            val createdUri = DocumentsContract.createDocument(
                context.contentResolver,
                documentUriFor(parent),
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            ) ?: throw StorageAccessException.Io()
            val created = documentForUri(createdUri) ?: throw StorageAccessException.Unavailable()
            toEntry(created, parent.storageId)
        } catch (error: SecurityException) {
            throw StorageAccessException.PermissionRequired(error)
        }
    }

    override suspend fun createFile(parent: BrowserLocation, name: String, mimeType: String?): FileEntry = withContext(Dispatchers.IO) {
        requireSafeLeafName(name)
        if (StorageCapability.CREATE_FILE !in capabilities(parent)) throw StorageAccessException.ReadOnly()
        if (findChildInternal(parent, name) != null) {
            throw StorageAccessException.Io(IllegalStateException("destination exists"))
        }
        try {
            val createdUri = DocumentsContract.createDocument(
                context.contentResolver,
                documentUriFor(parent),
                mimeType ?: "application/octet-stream",
                name,
            ) ?: throw StorageAccessException.Io()
            val created = documentForUri(createdUri) ?: throw StorageAccessException.Unavailable()
            toEntry(created, parent.storageId)
        } catch (error: SecurityException) {
            throw StorageAccessException.PermissionRequired(error)
        }
    }

    override suspend fun delete(item: ScopedFileReference): Boolean = withContext(Dispatchers.IO) {
        val uri = validateScopedUri(item)
        if (documentFlags(uri) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE == 0) {
            throw StorageAccessException.ReadOnly()
        }
        val document = DocumentFile.fromSingleUri(context, uri) ?: throw StorageAccessException.Unavailable()
        try {
            if (!document.exists()) false else document.delete()
        } catch (error: SecurityException) {
            throw StorageAccessException.PermissionRequired(error)
        }
    }

    override suspend fun rename(item: ScopedFileReference, newName: String): FileEntry = withContext(Dispatchers.IO) {
        requireSafeLeafName(newName)
        val uri = validateScopedUri(item)
        if (documentFlags(uri) and DocumentsContract.Document.FLAG_SUPPORTS_RENAME == 0) {
            throw StorageAccessException.ReadOnly()
        }
        val document = DocumentFile.fromSingleUri(context, uri) ?: throw StorageAccessException.Unavailable()
        try {
            if (!document.renameTo(newName)) throw StorageAccessException.Io()
            toEntry(document, item.storageId)
        } catch (error: SecurityException) {
            throw StorageAccessException.PermissionRequired(error)
        }
    }

    override suspend fun openOutputStream(item: ScopedFileReference, truncate: Boolean): OutputStream = withContext(Dispatchers.IO) {
        val uri = validateScopedUri(item)
        if (documentFlags(uri) and DocumentsContract.Document.FLAG_SUPPORTS_WRITE == 0) {
            throw StorageAccessException.ReadOnly()
        }
        try {
            runCatching {
                context.contentResolver.openOutputStream(uri, if (truncate) "rwt" else "wa")
            }.getOrNull()
                ?: context.contentResolver.openOutputStream(uri, if (truncate) "w" else "wa")
                ?: throw FileNotFoundException(uri.toString())
        } catch (error: SecurityException) {
            throw StorageAccessException.PermissionRequired(error)
        }
    }

    override suspend fun findChild(parent: BrowserLocation, name: String): FileEntry? = withContext(Dispatchers.IO) {
        requireSafeLeafName(name)
        try {
            findChildInternal(parent, name)
        } catch (error: SecurityException) {
            throw StorageAccessException.PermissionRequired(error)
        }
    }

    override suspend fun freeBytes(location: BrowserLocation): Long? = null

    override suspend fun isSameOrDescendant(
        source: ScopedFileReference,
        destination: BrowserLocation,
    ): Boolean = withContext(Dispatchers.IO) {
        if (source.reference.providerId != ID || destination.providerId != ID) return@withContext false

        val sourceTree = Uri.parse(source.rootReference)
        val destinationTree = Uri.parse(destination.rootReference)
        if (sourceTree.authority == null || sourceTree.authority != destinationTree.authority) return@withContext false

        val sourceUri = validateScopedUri(source)
        val sourceDocId = DocumentsContract.getDocumentId(sourceUri)
        val destinationDocId = documentIdFor(destination)
        if (sourceDocId == destinationDocId) return@withContext true

        val destinationUri = DocumentsContract.buildDocumentUriUsingTree(destinationTree, destinationDocId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val platformResult = runCatching {
                DocumentsContract.isChildDocument(context.contentResolver, sourceUri, destinationUri)
            }.getOrNull()
            if (platformResult != null) return@withContext platformResult
        }

        findPathByTraversal(sourceTree, sourceDocId, destinationDocId) != null
    }

    private fun findChildInternal(parent: BrowserLocation, name: String): FileEntry? {
        return childDocumentUris(parent).firstNotNullOfOrNull { childUri ->
            val document = documentForUri(childUri) ?: return@firstNotNullOfOrNull null
            if (document.name == name) toEntry(document, parent.storageId) else null
        }
    }

    private fun childDocumentUris(location: BrowserLocation): List<Uri> {
        val tree = Uri.parse(location.rootReference)
        return childDocumentUris(tree, documentIdFor(location))
    }

    private fun childDocumentUris(tree: Uri, parentDocId: String): List<Uri> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val result = mutableListOf<Uri>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val documentIdColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            while (cursor.moveToNext()) {
                val childDocId = cursor.getString(documentIdColumn)
                result += DocumentsContract.buildDocumentUriUsingTree(tree, childDocId)
            }
        } ?: throw StorageAccessException.Unavailable()
        return result
    }

    private fun documentFlags(uri: Uri): Int {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_FLAGS)
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) throw StorageAccessException.Unavailable()
                val flagsColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS)
                cursor.getInt(flagsColumn)
            } ?: throw StorageAccessException.Unavailable()
        } catch (error: SecurityException) {
            throw StorageAccessException.PermissionRequired(error)
        }
    }

    private fun documentFor(location: BrowserLocation): DocumentFile? =
        documentForUri(documentUriFor(location))

    private fun documentForUri(uri: Uri): DocumentFile? =
        DocumentFile.fromSingleUri(context, uri)

    private fun documentUriFor(location: BrowserLocation): Uri {
        val tree = Uri.parse(location.rootReference)
        return DocumentsContract.buildDocumentUriUsingTree(tree, documentIdFor(location))
    }

    private fun documentIdFor(location: BrowserLocation): String {
        val tree = Uri.parse(location.rootReference)
        val rootDocId = treeDocumentId(tree) ?: throw StorageAccessException.PermissionRequired()
        val referenceUri = Uri.parse(location.reference)
        requireMatchingAuthority(tree, referenceUri)
        val documentId = runCatching {
            if (location.reference == location.rootReference) rootDocId
            else DocumentsContract.getDocumentId(referenceUri)
        }.getOrElse { throw StorageAccessException.PermissionRequired(it) }
        if (!isWithinTree(tree, rootDocId, documentId)) throw StorageAccessException.PermissionRequired()
        return documentId
    }

    private fun validateScopedUri(item: ScopedFileReference): Uri {
        val tree = Uri.parse(item.rootReference)
        val uri = item.reference.uri?.let(Uri::parse) ?: throw StorageAccessException.Unavailable()
        val rootDocId = treeDocumentId(tree) ?: throw StorageAccessException.PermissionRequired()
        requireMatchingAuthority(tree, uri)
        val itemDocId = runCatching {
            if (uri == tree) rootDocId else DocumentsContract.getDocumentId(uri)
        }.getOrElse { throw StorageAccessException.PermissionRequired(it) }
        if (!isWithinTree(tree, rootDocId, itemDocId)) throw StorageAccessException.PermissionRequired()
        return DocumentsContract.buildDocumentUriUsingTree(tree, itemDocId)
    }

    private fun isWithinTree(tree: Uri, rootDocId: String, documentId: String): Boolean {
        if (documentId == rootDocId) return true
        val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(tree, rootDocId)
        val candidateUri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val platformResult = runCatching {
                DocumentsContract.isChildDocument(context.contentResolver, rootDocumentUri, candidateUri)
            }.getOrNull()
            if (platformResult != null) return platformResult
        }

        val platformPath = runCatching {
            DocumentsContract.findDocumentPath(context.contentResolver, candidateUri)?.path
        }.getOrNull()
        if (platformPath != null) {
            return platformPath.firstOrNull() == rootDocId && platformPath.lastOrNull() == documentId
        }

        return runCatching { findPathByTraversal(tree, rootDocId, documentId) != null }.getOrDefault(false)
    }

    private fun documentPathIds(location: BrowserLocation): List<String>? {
        val tree = Uri.parse(location.rootReference)
        val rootDocId = treeDocumentId(tree) ?: return null
        val currentDocId = documentIdFor(location)
        if (currentDocId == rootDocId) return listOf(rootDocId)

        val currentUri = DocumentsContract.buildDocumentUriUsingTree(tree, currentDocId)
        val platformPath = runCatching {
            DocumentsContract.findDocumentPath(context.contentResolver, currentUri)?.path
        }.getOrNull()
        if (
            platformPath != null &&
            platformPath.firstOrNull() == rootDocId &&
            platformPath.lastOrNull() == currentDocId
        ) {
            return platformPath
        }

        return findPathByTraversal(tree, rootDocId, currentDocId)
    }

    private fun findPathByTraversal(tree: Uri, startDocId: String, targetDocId: String): List<String>? {
        if (startDocId == targetDocId) return listOf(startDocId)

        val parents = mutableMapOf<String, String?>(startDocId to null)
        val queue = ArrayDeque<String>()
        queue.add(startDocId)

        while (queue.isNotEmpty()) {
            val parentDocId = queue.removeFirst()
            val children = childDocumentUris(tree, parentDocId)
            for (childUri in children) {
                val childDocId = runCatching { DocumentsContract.getDocumentId(childUri) }.getOrNull() ?: continue
                if (childDocId in parents) continue
                parents[childDocId] = parentDocId
                if (childDocId == targetDocId) return reconstructPath(parents, targetDocId)

                val childDocument = documentForUri(childUri)
                if (childDocument?.isDirectory == true) queue.add(childDocId)
            }
        }
        return null
    }

    private fun reconstructPath(parents: Map<String, String?>, targetDocId: String): List<String> {
        val reversePath = mutableListOf<String>()
        var current: String? = targetDocId
        while (current != null) {
            reversePath += current
            current = parents[current]
        }
        reversePath.reverse()
        return reversePath
    }

    private fun locationForDocumentId(location: BrowserLocation, tree: Uri, documentId: String): BrowserLocation {
        val rootDocId = treeDocumentId(tree) ?: throw StorageAccessException.PermissionRequired()
        val reference = if (documentId == rootDocId) {
            location.rootReference
        } else {
            DocumentsContract.buildDocumentUriUsingTree(tree, documentId).toString()
        }
        val label = if (documentId == rootDocId) {
            rootName(location)
        } else {
            documentForUri(Uri.parse(reference))?.name ?: context.getString(R.string.unnamed_item)
        }
        return location.copy(
            id = "saf:$documentId",
            displayName = label,
            reference = reference,
        )
    }

    private fun treeDocumentId(tree: Uri): String? =
        runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()

    private fun requireMatchingAuthority(tree: Uri, document: Uri) {
        if (
            tree.scheme != "content" ||
            document.scheme != "content" ||
            tree.authority.isNullOrBlank() ||
            tree.authority != document.authority
        ) {
            throw StorageAccessException.PermissionRequired()
        }
    }

    private fun toEntry(file: DocumentFile, storageId: String): FileEntry {
        val name = file.name ?: context.getString(R.string.unnamed_item)
        val mime = file.type
        val type = FileClassifier.classify(name, mime, file.isDirectory)
        val modified = file.lastModified().takeIf { it > 0L }
        val size = if (file.isFile) file.length().takeIf { it >= 0L } else null
        return FileEntry(
            id = "saf:${file.uri}",
            reference = FileReference(ID, "saf:${file.uri}", uri = file.uri.toString()),
            name = name,
            extension = FileClassifier.extensionFor(name),
            mimeType = mime,
            type = type,
            sizeBytes = size,
            modifiedAtMillis = modified,
            createdAtMillis = null,
            isHidden = name.startsWith('.'),
            isReadable = file.canRead(),
            isWritable = file.canWrite(),
            childCount = null,
            storageId = storageId,
            thumbnailKey = if (file.isFile) "${file.uri}:${modified ?: 0L}:${size ?: -1L}" else null,
        )
    }

    private fun requireSafeLeafName(name: String) {
        if (
            name.isBlank() ||
            name == "." ||
            name == ".." ||
            name.contains('/') ||
            name.contains('\\') ||
            name.indexOf('\u0000') >= 0
        ) {
            throw StorageAccessException.Io(IllegalArgumentException("invalid leaf name"))
        }
    }

    private fun rootName(location: BrowserLocation): String =
        location.storageId.removePrefix("saf:").ifBlank { location.displayName }

    companion object {
        const val ID = "saf"
    }
}
