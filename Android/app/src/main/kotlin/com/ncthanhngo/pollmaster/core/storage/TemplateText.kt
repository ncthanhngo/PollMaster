package com.ncthanhngo.pollmaster.core.storage

import com.ncthanhngo.pollmaster.core.model.Template
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

object TemplateText {
    private val fence = Regex("```[ \\t]*([A-Za-z]*)[ \\t]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

    /** Extracts the JSON object from pasted text: prefers the first ```json fence, else the first fence containing `{`, else the raw text. */
    fun extractJson(text: String): String? {
        val fences = fence.findAll(text).toList()
        val block = fences.firstOrNull { it.groupValues[1].equals("json", ignoreCase = true) }
            ?: fences.firstOrNull { '{' in it.groupValues[2] }
        return outermostObject(block?.groupValues?.get(2) ?: text)
    }

    private fun outermostObject(s: String): String? {
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        return if (start in 0 until end) s.substring(start, end + 1).trim() else null
    }

    /**
     * The same template JSON with only `title`, `shortName` and `color` replaced (edited in the library: id and version stay, so
     * records and projects keep pointing at it). An empty short name or colour removes the key (the defaults apply).
     */
    fun edited(json: String, title: String, shortName: String?, color: String?): String {
        val name = title.trim()
        if (name.isEmpty()) throw TemplateException("Tên mẫu không được để trống.")
        val short = shortName?.trim()?.takeIf { it.isNotEmpty() }
        val hex = color?.takeIf { it.isNotEmpty() }
        if (hex != null && !Template.isHexColor(hex)) throw TemplateException("Màu phải có dạng #RRGGBB.")
        val obj = Json.parseToJsonElement(json).jsonObject.toMutableMap()
        obj["title"] = JsonPrimitive(name)
        if (short != null) obj["shortName"] = JsonPrimitive(short) else obj.remove("shortName")
        if (hex != null) obj["color"] = JsonPrimitive(hex) else obj.remove("color")
        return JsonObject(obj).toString()
    }

    /** Message the user can send back to the AI after an import failure. */
    fun errorReport(prefix: String, errors: String): String =
        prefix + "\n" + errors.lines().filter { it.isNotBlank() }.joinToString("\n") { "- ${it.trim()}" }
}
