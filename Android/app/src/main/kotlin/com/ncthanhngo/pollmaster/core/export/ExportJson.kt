package com.ncthanhngo.pollmaster.core.export

import com.ncthanhngo.pollmaster.core.formula.FormulaEngine
import com.ncthanhngo.pollmaster.core.formula.RecordSource
import com.ncthanhngo.pollmaster.core.formula.rowViews
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.TableItem
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.model.allFields
import com.ncthanhngo.pollmaster.core.model.isEmptyJson
import com.ncthanhngo.pollmaster.core.model.mediaItems
import com.ncthanhngo.pollmaster.core.model.tables
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Builds `export.json` (schema section 5.1). */
object ExportJson {

    fun build(template: Template, record: Record): JsonObject {
        val engine = FormulaEngine(RecordSource(template, record))
        val fields = LinkedHashMap<String, JsonElement>()
        val computed = LinkedHashMap<String, JsonElement>()
        for (f in template.allFields()) {
            if (f.type == "computed") {
                computed[f.id] = computedJson(engine.computeField(f))
            } else {
                fields[f.id] = fieldJson(f, record.values[f.id])
            }
        }
        val tables = LinkedHashMap<String, JsonElement>()
        for (t in template.tables()) tables[t.id] = JsonArray(tableRows(t, record))
        return JsonObject(
            mapOf(
                "template" to obj("id" to str(template.id), "version" to str(template.version), "title" to str(template.title)),
                "record" to obj(
                    "id" to str(record.id), "status" to str(record.status), "createdAt" to str(record.createdAt),
                    "updatedAt" to str(record.updatedAt), "completedAt" to (record.completedAt?.let(::str) ?: JsonNull),
                ),
                "fields" to JsonObject(fields),
                "computed" to JsonObject(computed),
                "tables" to JsonObject(tables),
            ),
        )
    }

    private fun fieldJson(f: Field, fv: FieldValue?): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        m["label"] = str(f.label)
        f.unit?.let { m["unit"] = str(it) }
        m["value"] = rawValue(f, fv)
        fv?.flag?.let { m["flag"] = str(it) }
        fv?.reason?.let { m["reason"] = str(it) }
        fv?.note?.let { m["note"] = str(it) }
        if (fv?.other?.isNotEmpty() == true) m["other"] = JsonObject(fv.other.mapValues { str(it.value) })
        if (fv?.parts?.isNotEmpty() == true) m["parts"] = JsonObject(fv.parts)
        if (fv?.media?.isNotEmpty() == true) m["media"] = JsonArray(fv.media.map { str(it.file.substringAfterLast('/')) })
        return JsonObject(m)
    }

    /** `v` as stored; media values become file names. Empty values (including no media) are `null`. */
    private fun rawValue(f: Field, fv: FieldValue?): JsonElement = when {
        f.isMedia -> fv?.mediaItems().orEmpty().takeIf { it.isNotEmpty() }
            ?.let { items -> JsonArray(items.map { str(it.file.substringAfterLast('/')) }) } ?: JsonNull
        else -> fv?.v?.takeUnless { it.isEmptyJson() } ?: JsonNull
    }

    private fun tableRows(t: TableItem, record: Record): List<JsonObject> = t.rowViews(record).map { (row, fixed) ->
        val m = LinkedHashMap<String, JsonElement>()
        m["id"] = str(row.id)
        for (c in t.columns) {
            val fv = row.values[c.id]
            val value = if (fv == null) fixed[c.id] else rawValue(c, fv)
            if (value != null) m[c.id] = value
        }
        JsonObject(m)
    }

    private fun computedJson(x: Any?): JsonElement = when (x) {
        is Double -> JsonPrimitive(x)
        is Boolean -> JsonPrimitive(x)
        is String -> JsonPrimitive(x)
        is List<*> -> JsonArray(x.map(::computedJson))
        else -> JsonNull
    }

    private fun str(s: String) = JsonPrimitive(s)
    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(pairs.toMap())

    fun encode(template: Template, record: Record): String = PollJson.encodeToString(JsonObject.serializer(), build(template, record))
}
