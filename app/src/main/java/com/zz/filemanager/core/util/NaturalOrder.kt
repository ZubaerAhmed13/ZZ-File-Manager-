package com.zz.filemanager.core.util

import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.SortConfiguration
import com.zz.filemanager.core.model.SortDirection
import com.zz.filemanager.core.model.SortField
import java.util.Locale

object NaturalOrder {
    fun compare(a: String, b: String): Int {
        var ia = 0
        var ib = 0
        while (ia < a.length && ib < b.length) {
            val ca = a[ia]
            val cb = b[ib]
            if (ca.isDigit() && cb.isDigit()) {
                val sa = ia
                val sb = ib
                while (ia < a.length && a[ia].isDigit()) ia++
                while (ib < b.length && b[ib].isDigit()) ib++
                val ra = a.substring(sa, ia)
                val rb = b.substring(sb, ib)
                val na = ra.trimStart('0').ifEmpty { "0" }
                val nb = rb.trimStart('0').ifEmpty { "0" }
                val lengthCompare = na.length.compareTo(nb.length)
                if (lengthCompare != 0) return lengthCompare
                val numberCompare = na.compareTo(nb)
                if (numberCompare != 0) return numberCompare
                val zeroCompare = ra.length.compareTo(rb.length)
                if (zeroCompare != 0) return zeroCompare
            } else {
                val la = ca.lowercaseChar()
                val lb = cb.lowercaseChar()
                if (la != lb) return la.compareTo(lb)
                ia++
                ib++
            }
        }
        val length = (a.length - ia).compareTo(b.length - ib)
        if (length != 0) return length
        return a.compareTo(b)
    }
}

object FileSorter {
    fun sort(entries: List<FileEntry>, config: SortConfiguration): List<FileEntry> {
        val direction = if (config.direction == SortDirection.ASCENDING) 1 else -1
        val comparator = Comparator<FileEntry> { a, b ->
            if (config.foldersFirst && a.isDirectory != b.isDirectory) {
                if (a.isDirectory) -1 else 1
            } else {
                val base = when (config.field) {
                    SortField.NAME -> NaturalOrder.compare(a.name, b.name)
                    SortField.DATE_MODIFIED -> compareNullable(a.modifiedAtMillis, b.modifiedAtMillis)
                    SortField.SIZE -> compareNullable(a.sizeBytes, b.sizeBytes)
                    SortField.TYPE -> {
                        val type = a.type.name.lowercase(Locale.ROOT).compareTo(b.type.name.lowercase(Locale.ROOT))
                        if (type != 0) type else NaturalOrder.compare(a.name, b.name)
                    }
                }
                val directed = base * direction
                if (directed != 0) directed else NaturalOrder.compare(a.name, b.name) * direction
            }
        }
        return entries.sortedWith(comparator)
    }

    private fun <T : Comparable<T>> compareNullable(a: T?, b: T?): Int = when {
        a == null && b == null -> 0
        a == null -> 1
        b == null -> -1
        else -> a.compareTo(b)
    }
}
