package com.zz.filemanager.core.trash

import android.app.PendingIntent
import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

class MediaStoreTrashGateway(private val resolver: ContentResolver) {
    fun createRequest(uris: Collection<Uri>, trash: Boolean): PendingIntent {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { "MediaStore trash requires Android 11 or newer" }
        require(uris.isNotEmpty())
        return MediaStore.createTrashRequest(resolver, uris.toList(), trash)
    }

    fun createDeleteRequest(uris: Collection<Uri>): PendingIntent {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { "MediaStore delete requires Android 11 or newer" }
        require(uris.isNotEmpty())
        return MediaStore.createDeleteRequest(resolver, uris.toList())
    }
}
