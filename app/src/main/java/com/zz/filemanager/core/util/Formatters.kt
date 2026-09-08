package com.zz.filemanager.core.util

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

object Formatters {
    private val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")

    fun bytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
        if (bytes < 1024L) return "$bytes B"
        val safe = bytes.coerceAtLeast(0L).toDouble()
        val group = (ln(safe) / ln(1024.0)).toInt().coerceIn(1, units.lastIndex)
        val value = safe / 1024.0.pow(group)
        val pattern = if (value >= 100) "%.0f %s" else if (value >= 10) "%.1f %s" else "%.2f %s"
        return String.format(locale, pattern, value, units[group])
    }

    fun dateTime(timestampMillis: Long, locale: Locale = Locale.getDefault()): String {
        val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
        return formatter.format(Date(timestampMillis))
    }
}
