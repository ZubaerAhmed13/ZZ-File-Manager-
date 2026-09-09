package com.zz.filemanager.core.step4

import android.net.Uri
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.FileEntryType
import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.model.OpenFileRequest
import com.zz.filemanager.core.util.BrowserLocationCodec
import java.util.Base64

/**
 * Encodes an already-authorized provider reference plus its logical root into an app-private
 * ACTION_VIEW URI. The URI never grants new storage authority; Step4FileActivity reuses the
 * existing provider reference and root that the browser already proved accessible.
 */
object Step4OpenCodec {
    private const val SCHEME = "zzfm"
    private const val AUTHORITY = "open"
    const val MIME = "application/x-zz-file-manager-entry"

    fun supports(entry: FileEntry): Boolean = entry.type in setOf(
        FileEntryType.ARCHIVE,
        FileEntryType.IMAGE,
        FileEntryType.VIDEO,
        FileEntryType.AUDIO,
        FileEntryType.TEXT,
        FileEntryType.APK,
    )

    fun request(entry: FileEntry, parent: BrowserLocation): OpenFileRequest {
        val uri = Uri.Builder()
            .scheme(SCHEME)
            .authority(AUTHORITY)
            .appendQueryParameter("parent", encode(BrowserLocationCodec.encode(parent)))
            .appendQueryParameter("provider", entry.reference.providerId)
            .appendQueryParameter("opaque", encode(entry.reference.opaqueId))
            .appendQueryParameter("uri", entry.reference.uri?.let(::encode))
            .appendQueryParameter("path", entry.reference.path?.let(::encode))
            .appendQueryParameter("id", encode(entry.id))
            .appendQueryParameter("name", encode(entry.name))
            .appendQueryParameter("extension", entry.extension)
            .appendQueryParameter("mime", entry.mimeType?.let(::encode))
            .appendQueryParameter("type", entry.type.name)
            .appendQueryParameter("size", entry.sizeBytes?.toString())
            .appendQueryParameter("modified", entry.modifiedAtMillis?.toString())
            .appendQueryParameter("readable", entry.isReadable.toString())
            .appendQueryParameter("writable", entry.isWritable.toString())
            .appendQueryParameter("storage", encode(entry.storageId))
            .appendQueryParameter("symlink", entry.isSymbolicLink.toString())
            .build()
        return OpenFileRequest(uri.toString(), MIME)
    }

    fun decode(uri: Uri): Pair<FileEntry, BrowserLocation>? {
        if (uri.scheme != SCHEME || uri.authority != AUTHORITY) return null
        val parent = uri.getQueryParameter("parent")?.let(::decode)?.let(BrowserLocationCodec::decode) ?: return null
        val provider = uri.getQueryParameter("provider") ?: return null
        val opaque = uri.getQueryParameter("opaque")?.let(::decode) ?: return null
        val id = uri.getQueryParameter("id")?.let(::decode) ?: return null
        val name = uri.getQueryParameter("name")?.let(::decode) ?: return null
        val type = uri.getQueryParameter("type")?.let { runCatching { FileEntryType.valueOf(it) }.getOrNull() } ?: return null
        val reference = FileReference(
            providerId = provider,
            opaqueId = opaque,
            uri = uri.getQueryParameter("uri")?.let(::decode),
            path = uri.getQueryParameter("path")?.let(::decode),
        )
        val entry = FileEntry(
            id = id,
            reference = reference,
            name = name,
            extension = uri.getQueryParameter("extension"),
            mimeType = uri.getQueryParameter("mime")?.let(::decode),
            type = type,
            sizeBytes = uri.getQueryParameter("size")?.toLongOrNull(),
            modifiedAtMillis = uri.getQueryParameter("modified")?.toLongOrNull(),
            createdAtMillis = null,
            isHidden = name.startsWith('.'),
            isReadable = uri.getQueryParameter("readable")?.toBooleanStrictOrNull() ?: true,
            isWritable = uri.getQueryParameter("writable")?.toBooleanStrictOrNull() ?: false,
            childCount = null,
            storageId = uri.getQueryParameter("storage")?.let(::decode) ?: parent.storageId,
            thumbnailKey = null,
            isSymbolicLink = uri.getQueryParameter("symlink")?.toBooleanStrictOrNull() ?: false,
        )
        return entry to parent
    }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decode(value: String): String = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
}
