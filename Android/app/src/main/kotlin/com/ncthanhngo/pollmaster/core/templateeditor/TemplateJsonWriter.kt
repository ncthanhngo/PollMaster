package com.ncthanhngo.pollmaster.core.templateeditor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Prints JSON with keys in the shared order (`template-schema.md` 11.4): known keys first, the rest a-z. Whole numbers print without `.0`. */
object TemplateJsonWriter {
    private val priority = listOf(
        "schemaVersion", "id", "version", "value", "kind", "title", "label", "type", "required", "shortName", "color", "subtitle",
        "instructions", "recordTitle", "mediaNamePattern", "note", "unit", "hint", "method", "min", "max", "decimals", "warnMin",
        "warnMax", "options", "parts", "formula", "columns", "rows", "items", "header", "sections", "signatures",
    )
    private val rank = priority.withIndex().associate { it.value to it.index }
    private val keyOrder = Comparator<String> { a, b ->
        val x = rank[a]
        val y = rank[b]
        when {
            x != null && y != null -> x.compareTo(y)
            x != null -> -1
            y != null -> 1
            else -> a.compareTo(b)
        }
    }

    fun write(v: JsonElement): String = StringBuilder().also { emit(v, 0, it) }.toString()

    private fun emit(v: JsonElement, depth: Int, out: StringBuilder) {
        when (v) {
            is JsonNull -> out.append("null")
            is JsonPrimitive -> out.append(primitive(v))
            is JsonArray -> {
                if (v.isEmpty()) { out.append("[]"); return }
                val pad = " ".repeat((depth + 1) * 2)
                out.append("[\n")
                v.forEachIndexed { i, e ->
                    out.append(pad)
                    emit(e, depth + 1, out)
                    out.append(if (i < v.size - 1) ",\n" else "\n")
                }
                out.append(" ".repeat(depth * 2)).append("]")
            }
            is JsonObject -> {
                if (v.isEmpty()) { out.append("{}"); return }
                val pad = " ".repeat((depth + 1) * 2)
                val keys = v.keys.sortedWith(keyOrder)
                out.append("{\n")
                keys.forEachIndexed { i, k ->
                    out.append(pad).append(quote(k)).append(": ")
                    emit(v.getValue(k), depth + 1, out)
                    out.append(if (i < keys.size - 1) ",\n" else "\n")
                }
                out.append(" ".repeat(depth * 2)).append("}")
            }
        }
    }

    private fun primitive(p: JsonPrimitive): String {
        if (p.isString) return quote(p.content)
        if (p.booleanOrNull != null) return p.content
        val d = p.doubleOrNull ?: return p.content
        return if (d == Math.rint(d) && Math.abs(d) < 1e15) d.toLong().toString() else p.content
    }

    private fun quote(s: String): String {
        val out = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c.code < 0x20 -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }
}
