package com.zz.filemanager.app

import android.content.Context
import com.zz.filemanager.core.preferences.PreferencesRepository
import com.zz.filemanager.core.storage.StorageRepository
import com.zz.filemanager.core.util.ThumbnailRepository

class AppContainer(context: Context) {
    val preferences = PreferencesRepository(context.applicationContext)
    val storage = StorageRepository(context.applicationContext, preferences)
    val thumbnails = ThumbnailRepository(context.applicationContext)
}
