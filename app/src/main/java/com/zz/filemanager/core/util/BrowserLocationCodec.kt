package com.zz.filemanager.core.util

import com.zz.filemanager.core.model.BrowserLocation
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object BrowserLocationCodec {
    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun dec(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    fun encode(location: BrowserLocation): String = listOf(
        location.providerId,
        location.id,
        location.displayName,
        location.reference,
        location.rootReference,
        location.storageId,
        location.readable.toString(),
        location.writable.toString(),
    ).joinToString("|") { enc(it) }

    fun decode(value: String): BrowserLocation? = runCatching {
        val fields = value.split('|').map(::dec)
        if (fields.size != 8) return null
        BrowserLocation(
            providerId = fields[0],
            id = fields[1],
            displayName = fields[2],
            reference = fields[3],
            rootReference = fields[4],
            storageId = fields[5],
            readable = fields[6].toBooleanStrictOrNull() ?: true,
            writable = fields[7].toBooleanStrictOrNull() ?: false,
        )
    }.getOrNull()

    fun encodeList(locations: List<BrowserLocation>): String = locations.joinToString("\n", transform = ::encode)
    fun decodeList(value: String): List<BrowserLocation> = value.lineSequence().mapNotNull(::decode).toList()
}
