package com.ncthanhngo.pollmaster.core.sync

import com.ncthanhngo.pollmaster.core.format.ValueFormatter
import com.ncthanhngo.pollmaster.core.format.displayDateTime
import com.ncthanhngo.pollmaster.core.format.formatShortest
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Row
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.model.TableItem
import com.ncthanhngo.pollmaster.core.model.asBool
import com.ncthanhngo.pollmaster.core.model.asDouble
import com.ncthanhngo.pollmaster.core.model.asObject
import com.ncthanhngo.pollmaster.core.model.asText
import com.ncthanhngo.pollmaster.core.model.findField
import com.ncthanhngo.pollmaster.core.model.findTable
import com.ncthanhngo.pollmaster.core.model.isEmptyJson
import com.ncthanhngo.pollmaster.core.model.mediaItems
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shows a change-log entry (schema 10.3): the label of a `path` and the text of an old / new value. Pure; same rules on iOS and Android
 * (`shared/sync-test-vectors.json`, sections `labels` and `values`). Unknown template or id -> the raw `path`.
 */
object ChangeLabels {
    const val EMPTY = "(trống)"

    private val subLabels = mapOf("note" to "Ghi chú", "review" to "Kiểm tra", "flag" to "Cờ", "reason" to "Lý do", "other" to "Khác", "media" to "Tệp")
    private val rootLabels = mapOf("status" to "Trạng thái", "returned" to "Trả lại để sửa", "completedAt" to "Hoàn thành lúc")

    private enum class Kind { Root, Value, Table, Signature }

    /** Where a path points. [sub] = the key after the field / column id (`v` is the plain value, `parts.<id>` a sub-cell). */
    private class Target(val kind: Kind, val id: String, val row: String? = null, val column: String? = null, val sub: String? = null)

    private fun parse(path: String): Target? {
        if (path in rootLabels) return Target(Kind.Root, path)
        if (path.startsWith("signatures.")) return Target(Kind.Signature, path.removePrefix("signatures."))
        if (path.startsWith("values.")) {
            val rest = path.removePrefix("values.")
            val dot = rest.indexOf('.')
            return if (dot < 0) Target(Kind.Value, rest) else Target(Kind.Value, rest.substring(0, dot), sub = rest.substring(dot + 1))
        }
        if (!path.startsWith("tables.")) return null
        val open = path.indexOf('['); val close = path.indexOf(']')
        if (open < 0 || close < open) return null
        val id = path.substring("tables.".length, open)
        val row = path.substring(open + 1, close)
        val rest = path.substring(close + 1)
        if (rest.isEmpty()) return Target(Kind.Table, id, row)
        if (!rest.startsWith(".")) return null
        val cell = rest.substring(1)
        val dot = cell.indexOf('.')
        return if (dot < 0) Target(Kind.Table, id, row, cell) else Target(Kind.Table, id, row, cell.substring(0, dot), cell.substring(dot + 1))
    }

    /** The field (or table column) a target points at. */
    private fun field(t: Target, template: Template): Field? = when (t.kind) {
        Kind.Value -> template.findField(t.id)
        Kind.Table -> t.column?.let { c -> template.findTable(t.id)?.columns?.firstOrNull { it.id == c } }
        else -> null
    }

    /** Suffix for the key after the field id: "" for the plain value, null when the key is unknown. */
    private fun subLabel(sub: String?, f: Field): String? {
        if (sub == null || sub == "v") return ""
        subLabels[sub]?.let { return it }
        if (!sub.startsWith("parts.")) return null
        return f.parts.firstOrNull { it.id == sub.removePrefix("parts.") }?.label
    }

    /** Label of a change. [serverLabel] (filled by the server when sure) wins; [record] supplies the row positions of tables. */
    fun label(path: String, serverLabel: String = "", template: Template?, record: Record?): String {
        if (serverLabel.isNotEmpty()) return serverLabel
        val t = parse(path) ?: return path
        if (template == null) return path
        return when (t.kind) {
            Kind.Root -> rootLabels[t.id] ?: path
            Kind.Signature -> template.signatures.firstOrNull { it.id == t.id }?.label ?: path
            Kind.Value -> {
                val f = field(t, template) ?: return path
                val sub = subLabel(t.sub, f) ?: return path
                listOf(f.label, sub).filter { it.isNotEmpty() }.joinToString(" › ")
            }
            Kind.Table -> {
                val table = template.findTable(t.id) ?: return path
                val n = rows(record, table).indexOfFirst { it.id == t.row }
                if (record == null || n < 0) return path
                val row = "${table.title} (${table.rowLabel ?: "Dòng"} ${n + 1})"
                if (t.column == null) return row
                val f = field(t, template) ?: return path
                val sub = subLabel(t.sub, f) ?: return path
                listOf(row, f.label, sub).filter { it.isNotEmpty() }.joinToString(" › ")
            }
        }
    }

    /** Rows as shown: fixed tables follow the template's rows, merged with what is stored. */
    private fun rows(record: Record?, table: TableItem): List<Row> {
        val stored = record?.tables?.get(table.id).orEmpty()
        val fixed = table.rows ?: return stored
        val byId = stored.associateBy { it.id }
        return fixed.map { byId[it.id] ?: Row(it.id) }
    }

    /** Display text of an old / new value of [path], built with the shared display function (6.5). Blank -> "(trống)". */
    fun valueText(path: String, value: JsonElement, template: Template?): String {
        if (path == "returned") return if (value.asBool() == true) "Có" else "Không"
        if (value.isEmptyJson()) return EMPTY
        return format(path, value, template).ifEmpty { EMPTY }
    }

    private fun format(path: String, value: JsonElement, template: Template?): String {
        val t = parse(path) ?: return raw(value)
        when (t.kind) {
            Kind.Root -> return when (t.id) {
                "status" -> if (value.asText() == "completed") "Hoàn thành" else "Nháp"
                "completedAt" -> value.asText()?.let { displayDateTime(it) } ?: raw(value)
                else -> raw(value)
            }
            Kind.Signature -> {
                val o = value.asObject()
                return o?.get("name").asText()?.takeIf { it.isNotEmpty() } ?: o?.get("file").asText()?.substringAfterLast('/') ?: raw(value)
            }
            else -> Unit
        }
        if (template == null) return raw(value)
        if (t.kind == Kind.Table && t.column == null) return rowText(value, template.findTable(t.id))
        val f = field(t, template) ?: return raw(value)
        return cell(t.sub ?: "v", value, f)
    }

    private fun cell(sub: String, value: JsonElement, f: Field): String = when (sub) {
        "v" -> ValueFormatter().display(f, FieldValue(v = value))
        "flag" -> when (value.asText()) { "notMeasurable" -> "KĐĐ"; "estimate" -> "Ước lượng"; else -> raw(value) }
        "other" -> (value.asObject() ?: JsonObject(emptyMap())).entries.sortedBy { it.key }.mapNotNull { (k, v) ->
            v.asText()?.takeIf { it.isNotEmpty() }?.let { "${f.options.firstOrNull { o -> o.value == k }?.label ?: k}: $it" }
        }.joinToString("; ")
        "media" -> FieldValue(v = value).mediaItems().joinToString(", ") { it.file.substringAfterLast('/') }
        "note", "reason", "review" -> value.asText() ?: raw(value)
        else -> {
            val part = if (sub.startsWith("parts.")) f.parts.firstOrNull { it.id == sub.removePrefix("parts.") } else null
            if (part == null) raw(value) else ValueFormatter().partText(part, value)
        }
    }

    /** A whole table row (added or removed): `column: value` of the cells that hold something. */
    private fun rowText(value: JsonElement, table: TableItem?): String {
        if (table == null) return raw(value)
        val row = try { PollJson.decodeFromJsonElement(Row.serializer(), value) } catch (_: Exception) { return raw(value) }
        return table.columns.mapNotNull { c ->
            ValueFormatter().display(c, row.values[c.id]).takeIf { it.isNotEmpty() }?.let { "${c.label}: $it" }
        }.joinToString("; ")
    }

    private fun raw(value: JsonElement): String {
        val p = value as? JsonPrimitive
        if (p != null) {
            if (p.isString) return p.content
            p.asBool()?.let { return if (it) "Có" else "Không" }
            p.asDouble()?.let { return formatShortest(it) }
        }
        return if (value is JsonArray || value is JsonObject) value.toString() else ""
    }
}
