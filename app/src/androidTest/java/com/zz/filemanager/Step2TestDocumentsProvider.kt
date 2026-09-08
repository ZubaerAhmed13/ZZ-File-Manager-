package com.zz.filemanager

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * Test-APK-only DocumentsProvider used to certify the production SafStorageProvider.
 *
 * Document ids deliberately use slash-separated descendants (root/folder/file) because
 * production SAF ancestry/breadcrumb logic treats DocumentsContract ids as opaque except
 * for provider-defined descendant relationships inside one persisted tree.
 */
class Step2TestDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean {
        rootDirectory().mkdirs()
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection?.toTypedArray() ?: ROOT_PROJECTION)
        cursor.addValues(
            mapOf(
                DocumentsContract.Root.COLUMN_ROOT_ID to ROOT_ID,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID to ROOT_ID,
                DocumentsContract.Root.COLUMN_TITLE to "Step 2 SAF Test",
                DocumentsContract.Root.COLUMN_SUMMARY to "Deterministic instrumentation tree",
                DocumentsContract.Root.COLUMN_FLAGS to DocumentsContract.Root.FLAG_SUPPORTS_CREATE,
                DocumentsContract.Root.COLUMN_MIME_TYPES to "*/*",
                DocumentsContract.Root.COLUMN_AVAILABLE_BYTES to rootDirectory().usableSpace,
            ),
        )
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection?.toTypedArray() ?: DOCUMENT_PROJECTION)
        includeDocument(cursor, fileForDocumentId(documentId))
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val parent = fileForDocumentId(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("Not a directory: $parentDocumentId")
        val cursor = MatrixCursor(projection?.toTypedArray() ?: DOCUMENT_PROJECTION)
        parent.listFiles().orEmpty().sortedBy { it.name.lowercase() }.forEach { includeDocument(cursor, it) }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        val file = fileForDocumentId(documentId)
        if (!file.exists() || file.isDirectory) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        requireSafeName(displayName)
        val parent = fileForDocumentId(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException(parentDocumentId)
        val child = File(parent, displayName)
        if (child.exists()) throw FileNotFoundException("Already exists: $displayName")
        val created = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) child.mkdir() else child.createNewFile()
        if (!created) throw FileNotFoundException("Could not create: $displayName")
        context?.contentResolver?.notifyChange(
            DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId),
            null,
        )
        return documentIdFor(child)
    }

    override fun deleteDocument(documentId: String) {
        if (documentId == ROOT_ID) throw FileNotFoundException("Root cannot be deleted")
        val file = fileForDocumentId(documentId)
        if (!file.exists()) return
        val parentId = file.parentFile?.let(::documentIdFor)
        val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (!deleted) throw FileNotFoundException("Could not delete: $documentId")
        parentId?.let {
            context?.contentResolver?.notifyChange(DocumentsContract.buildChildDocumentsUri(AUTHORITY, it), null)
        }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        if (documentId == ROOT_ID) throw FileNotFoundException("Root cannot be renamed")
        requireSafeName(displayName)
        val file = fileForDocumentId(documentId)
        val parent = file.parentFile ?: throw FileNotFoundException(documentId)
        val renamed = File(parent, displayName)
        if (renamed.exists() || !file.renameTo(renamed)) {
            throw FileNotFoundException("Could not rename: $documentId")
        }
        context?.contentResolver?.notifyChange(
            DocumentsContract.buildChildDocumentsUri(AUTHORITY, documentIdFor(parent)),
            null,
        )
        return documentIdFor(renamed)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = fileForDocumentId(parentDocumentId).canonicalFile
        val child = fileForDocumentId(documentId).canonicalFile
        if (parent == child) return false
        return child.path.startsWith(parent.path + File.separator)
    }

    private fun includeDocument(cursor: MatrixCursor, file: File) {
        if (!file.exists()) throw FileNotFoundException(file.path)
        val directory = file.isDirectory
        var flags = DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_RENAME
        if (directory) flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        else flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_WRITE

        cursor.addValues(
            mapOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID to documentIdFor(file),
                DocumentsContract.Document.COLUMN_DISPLAY_NAME to if (file == rootDirectory()) "Step 2 SAF Test" else file.name,
                DocumentsContract.Document.COLUMN_MIME_TYPE to if (directory) DocumentsContract.Document.MIME_TYPE_DIR else mimeTypeFor(file),
                DocumentsContract.Document.COLUMN_FLAGS to flags,
                DocumentsContract.Document.COLUMN_SIZE to if (directory) null else file.length(),
                DocumentsContract.Document.COLUMN_LAST_MODIFIED to file.lastModified().takeIf { it > 0L },
            ),
        )
    }

    private fun MatrixCursor.addValues(values: Map<String, Any?>) {
        val row = newRow()
        columnNames.forEach { column -> row.add(values[column]) }
    }

    private fun rootDirectory(): File {
        val providerContext = context ?: throw IllegalStateException("Provider not attached")
        return File(providerContext.cacheDir, ROOT_DIRECTORY_NAME).canonicalFile
    }

    private fun fileForDocumentId(documentId: String): File {
        if (documentId != ROOT_ID && !documentId.startsWith("$ROOT_ID/")) {
            throw FileNotFoundException("Outside root: $documentId")
        }
        val root = rootDirectory()
        if (documentId == ROOT_ID) return root
        val relative = documentId.removePrefix("$ROOT_ID/")
        val candidate = File(root, relative).canonicalFile
        if (!candidate.path.startsWith(root.path + File.separator)) {
            throw FileNotFoundException("Outside root: $documentId")
        }
        return candidate
    }

    private fun documentIdFor(file: File): String {
        val root = rootDirectory()
        val candidate = file.canonicalFile
        if (candidate == root) return ROOT_ID
        if (!candidate.path.startsWith(root.path + File.separator)) {
            throw FileNotFoundException("Outside root: ${candidate.path}")
        }
        val relative = candidate.relativeTo(root).invariantSeparatorsPath
        return "$ROOT_ID/$relative"
    }

    private fun requireSafeName(name: String) {
        if (name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\') || '\u0000' in name) {
            throw FileNotFoundException("Invalid display name")
        }
    }

    private fun mimeTypeFor(file: File): String = when (file.extension.lowercase()) {
        "txt", "md", "csv", "log" -> "text/plain"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "pdf" -> "application/pdf"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    companion object {
        const val AUTHORITY = "com.zz.filemanager.test.documents"
        const val ROOT_ID = "root"
        const val ROOT_DIRECTORY_NAME = "step2-saf-documents"

        private val ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
        )

        private val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
