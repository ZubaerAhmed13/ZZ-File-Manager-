package com.zz.filemanager

import android.app.Application
import com.zz.filemanager.app.AppContainer

class ZZFileManagerApplication : Application() {
    val container: AppContainer by lazy { AppContainer(applicationContext) }
}
