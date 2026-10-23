package com.ncthanhngo.pollmaster.core.sites

import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.model.allFields
import com.ncthanhngo.pollmaster.core.model.asDouble
import com.ncthanhngo.pollmaster.core.model.asObject
import com.ncthanhngo.pollmaster.core.model.asText

/** Pin colour group of a record on the map (ux-spec L18): Nháp gray, Hoàn thành green, Cần sửa orange. */
enum class PinStatus { DRAFT, COMPLETED, RETURNED }

fun Record.pinStatus(): PinStatus = when {
    isCompleted -> PinStatus.COMPLETED
    returned -> PinStatus.RETURNED
    else -> PinStatus.DRAFT
}

data class LatLng(val lat: Double, val lng: Double)

object MapPins {
    /** Position of the first `gps` field (template order) that holds a value, or null. */
    fun position(template: Template, record: Record): LatLng? =
        template.allFields().filter { it.type == "gps" }.firstNotNullOfOrNull { f ->
            val o = record.values[f.id]?.v.asObject() ?: return@firstNotNullOfOrNull null
            val lat = o["lat"].asDouble() ?: return@firstNotNullOfOrNull null
            val lng = o["lng"].asDouble() ?: return@firstNotNullOfOrNull null
            LatLng(lat, lng)
        }

    /** Codes of the sites that already have a record: the value of a `lookup` field of any record. */
    fun visitedCodes(entries: List<Pair<Template, Record>>): Set<String> =
        entries.flatMap { (t, r) ->
            t.allFields().filter { it.lookup != null }.mapNotNull { r.values[it.id]?.v.asText()?.trim() }
        }.toSet()

    /** Planned sites with coordinates and no record yet (drawn as empty pins). */
    fun unvisited(sites: List<Site>, visited: Set<String>): List<Site> =
        sites.filter { it.hasCoordinates && it.code !in visited }
}
