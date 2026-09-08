package com.zz.filemanager.core.operation

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class OperationClipboardRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _clipboard = MutableStateFlow(load())
    val clipboard: StateFlow<OperationClipboard?> = _clipboard.asStateFlow()

    fun set(value: OperationClipboard) {
        preferences.edit().putString(KEY_CLIPBOARD, OperationJsonCodec.encodeClipboard(value)).apply()
        _clipboard.value = value
    }

    fun clear() {
        preferences.edit().remove(KEY_CLIPBOARD).apply()
        _clipboard.value = null
    }

    private fun load(): OperationClipboard? = preferences.getString(KEY_CLIPBOARD, null)
        ?.let { raw -> runCatching { OperationJsonCodec.decodeClipboard(raw) }.getOrNull() }

    companion object {
        private const val PREFERENCES_NAME = "step2_file_operation_clipboard"
        private const val KEY_CLIPBOARD = "clipboard"
    }
}
