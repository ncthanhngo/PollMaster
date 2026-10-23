package com.ncthanhngo.pollmaster.core.sites

import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.storage.writeAtomically
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A planned survey point (schema 7.3). */
@Serializable
data class Site(val code: String, val address: String = "", val lat: Double? = null, val lng: Double? = null) {
    val hasCoordinates: Boolean get() = lat != null && lng != null
}

class SitesCsvException(message: String) : Exception(message)

/** Parses the sites CSV: UTF-8 with or without BOM, header `code,address,lat,lng`, extra columns ignored. */
object SitesCsv {
    fun parse(text: String): List<Site> {
        val rows = records(text.removePrefix("\uFEFF")).filter { r -> r.any { it.isNotBlank() } }
        val header = rows.firstOrNull()?.map { it.trim().lowercase() } ?: throw SitesCsvException("Tệp trống")
        val code = header.indexOf("code")
        if (code < 0) throw SitesCsvException("Thiếu cột \"code\" ở dòng tiêu đề (code,address,lat,lng)")
        val address = header.indexOf("address")
        val lat = header.indexOf("lat")
        val lng = header.indexOf("lng")
        fun List<String>.cell(i: Int) = if (i < 0) "" else getOrNull(i)?.trim().orEmpty()
        fun number(s: String) = s.replace(',', '.').toDoubleOrNull()
        return rows.drop(1).mapNotNull { r ->
            val c = r.cell(code)
            if (c.isEmpty()) null else Site(c, r.cell(address), number(r.cell(lat)), number(r.cell(lng)))
        }
    }

    /** RFC 4180 records: quoted fields may hold commas, quotes (doubled) and line breaks. */
    private fun records(text: String): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        fun endCell() { row.add(cell.toString()); cell.setLength(0) }
        fun endRow() { endCell(); out.add(row); row = mutableListOf() }
        while (i < text.length) {
            val ch = text[i]
            when {
                quoted && ch == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                quoted -> cell.append(ch)
                ch == ',' -> endCell()
                ch == '\r' -> if (text.getOrNull(i + 1) != '\n') endRow()
                ch == '\n' -> endRow()
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return out
    }
}

/** `<root>/sites.json`. */
class SitesStore(root: File) {
    private val file = File(root, "sites.json")

    fun load(): List<Site> = runCatching {
        if (file.isFile) PollJson.decodeFromString(ListSerializer(Site.serializer()), file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    fun save(sites: List<Site>) {
        writeAtomically(file, PollJson.encodeToString(ListSerializer(Site.serializer()), sites).toByteArray(Charsets.UTF_8))
    }

    fun clear() {
        file.delete()
    }
}

/** Great-circle distance in metres. */
fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}
