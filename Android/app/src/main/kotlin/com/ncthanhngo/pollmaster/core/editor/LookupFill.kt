package com.ncthanhngo.pollmaster.core.editor

import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.isEmptyJson
import com.ncthanhngo.pollmaster.core.sites.Site
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Values a chosen site writes into other fields: [fresh] go to empty targets, [overwrites] would replace data. */
data class FillPlan(val fresh: Map<String, JsonElement>, val overwrites: Map<String, JsonElement>)

object LookupFill {
    /** Plans the writes of `field.lookup.fill` for [site]; unknown fill keys and missing site data are skipped. */
    fun plan(field: Field, site: Site, record: Record, now: String): FillPlan {
        val fresh = LinkedHashMap<String, JsonElement>()
        val overwrites = LinkedHashMap<String, JsonElement>()
        for ((target, what) in field.lookup?.fill.orEmpty()) {
            val value = valueFor(what, site, now) ?: continue
            val current = record.values[target]
            val empty = current == null || (current.v.isEmptyJson() && current.flag == null)
            (if (empty) fresh else overwrites)[target] = value
        }
        return FillPlan(fresh, overwrites)
    }

    private fun valueFor(what: String, site: Site, now: String): JsonElement? = when (what) {
        "code" -> JsonPrimitive(site.code)
        "address" -> site.address.takeIf { it.isNotBlank() }?.let(::JsonPrimitive)
        "lat" -> site.lat?.let(::JsonPrimitive)
        "lng" -> site.lng?.let(::JsonPrimitive)
        "gps" -> if (site.lat != null && site.lng != null) {
            JsonObject(mapOf("lat" to JsonPrimitive(site.lat), "lng" to JsonPrimitive(site.lng), "capturedAt" to JsonPrimitive(now)))
        } else null
        else -> null
    }
}
