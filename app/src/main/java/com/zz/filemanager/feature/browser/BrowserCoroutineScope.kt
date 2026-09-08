package com.zz.filemanager.feature.browser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch as coroutineLaunch

/**
 * Keeps BrowserScreen's UI feedback work bound to the coroutine scope it explicitly creates.
 * Browser ViewModels import kotlinx.coroutines.launch directly and are unaffected by this package helper.
 */
internal fun CoroutineScope.launch(block: suspend CoroutineScope.() -> Unit): Job =
    this.coroutineLaunch(block = block)
