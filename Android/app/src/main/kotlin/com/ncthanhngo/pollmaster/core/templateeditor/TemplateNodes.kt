package com.ncthanhngo.pollmaster.core.templateeditor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun s(v: String) = JsonPrimitive(v)

/** Minimal default nodes for "add" and the new code of a copy. */
internal object TemplateNodes {
    val choiceTypes = setOf("singleChoice", "multiChoice")
    val defaultOptions: List<JsonElement> = listOf(
        JsonObject(mapOf("value" to s("lua_chon_1"), "label" to s("Lựa chọn 1"))),
        JsonObject(mapOf("value" to s("lua_chon_2"), "label" to s("Lựa chọn 2"))),
    )

    /** What kind of list a path names, with the section index for `items`. */
    private sealed interface Slot {
        data object Sections : Slot
        data object Header : Slot
        data class Items(val section: Int) : Slot
        data object Columns : Slot
        data object Options : Slot
        data object Signatures : Slot
        data object Rows : Slot
    }

    private fun slot(path: DocPath): Slot {
        val last = (path.lastOrNull() as? PathKey.Key)?.name ?: throw TemplateEditException.badPath()
        return when (last) {
            "sections" -> Slot.Sections
            "header" -> Slot.Header
            "columns" -> Slot.Columns
            "options" -> Slot.Options
            "signatures" -> Slot.Signatures
            "rows" -> Slot.Rows
            "items" -> Slot.Items((path.getOrNull(path.size - 2) as? PathKey.Index)?.i ?: throw TemplateEditException.badPath())
            else -> throw TemplateEditException.badPath()
        }
    }

    private fun strings(root: JsonElement, path: DocPath, key: String): Set<String> =
        TemplateDoc(root).node(path).arr().orEmpty().mapNotNull { it.obj()?.get(key).str() }.toSet()

    private fun field(id: String, type: String): JsonObject {
        val o = mutableMapOf<String, JsonElement>("id" to s(id), "label" to s("Trường mới"), "type" to s(type))
        if (type in choiceTypes) o["options"] = JsonArray(defaultOptions)
        return JsonObject(o)
    }

    fun make(root: JsonElement, path: DocPath, kind: String, type: String?): JsonElement = when (val slot = slot(path)) {
        Slot.Sections -> JsonObject(mapOf("id" to s(TemplateIds.nextSectionId(root)), "title" to s("Mục mới"), "items" to JsonArray(emptyList())))
        Slot.Header -> {
            if (kind != "field") throw TemplateEditException.badKind()
            field(TemplateIds.nextItemId(root, null, "field"), type ?: "text")
        }
        is Slot.Items -> {
            val id = TemplateIds.nextItemId(root, slot.section, kind)
            when (kind) {
                "field" -> field(id, type ?: "text")
                "heading" -> JsonObject(mapOf("kind" to s("heading"), "id" to s(id), "title" to s("Tiêu đề mới")))
                "table" -> JsonObject(mapOf(
                    "kind" to s("table"), "id" to s(id), "title" to s("Bảng mới"),
                    "columns" to JsonArray(listOf(JsonObject(mapOf("id" to s("c1"), "label" to s("Cột 1"), "type" to s("text"))))),
                ))
                else -> throw TemplateEditException.badKind()
            }
        }
        Slot.Columns -> JsonObject(field(TemplateIds.firstUnused("c", strings(root, path, "id")), type ?: "text") + ("label" to s("Cột mới")))
        Slot.Options -> JsonObject(mapOf("value" to s(TemplateIds.optionValue("Lựa chọn mới", strings(root, path, "value"))), "label" to s("Lựa chọn mới")))
        Slot.Signatures -> JsonObject(mapOf("id" to s(TemplateIds.firstUnused("sig", strings(root, path, "id"))), "label" to s("Chữ ký mới")))
        Slot.Rows -> JsonObject(mapOf("id" to s(TemplateIds.firstUnused("r", strings(root, path, "id"))), "fixed" to JsonObject(emptyMap())))
    }

    /** [node] (an element of the list at [path]) with a fresh code. Sections are not copied (their items would repeat every code). */
    fun copy(node: JsonElement, root: JsonElement, path: DocPath): JsonElement {
        val o = (node as? JsonObject)?.toMutableMap() ?: throw TemplateEditException.badPath()
        when (val slot = slot(path)) {
            Slot.Sections -> throw TemplateEditException.notDuplicable()
            Slot.Header -> o["id"] = s(TemplateIds.nextItemId(root, null, "field"))
            is Slot.Items -> {
                val kind = o["kind"].str() ?: "field"
                o["id"] = s(TemplateIds.nextItemId(root, slot.section, if (kind == "heading" || kind == "table") kind else "field"))
            }
            Slot.Columns -> o["id"] = s(TemplateIds.firstUnused("c", strings(root, path, "id")))
            Slot.Options -> o["value"] = s(TemplateIds.optionValue(o["label"].str().orEmpty(), strings(root, path, "value")))
            Slot.Signatures -> o["id"] = s(TemplateIds.firstUnused("sig", strings(root, path, "id")))
            Slot.Rows -> o["id"] = s(TemplateIds.firstUnused("r", strings(root, path, "id")))
        }
        return JsonObject(o)
    }
}
