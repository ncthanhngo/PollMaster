package com.ncthanhngo.pollmaster.core.templateeditor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One edit of the template tree. Lists are named by their path (`docPath("sections", 0, "items")`, `docPath("header")`, …). */
sealed interface EditOp {
    /** Sets or removes one key of the object at [path] (null/JsonNull removes; "" removes a key that may be absent). */
    data class Set(val path: DocPath, val key: String, val value: JsonElement?) : EditOp
    data class Insert(val path: DocPath, val index: Int, val node: JsonElement) : EditOp
    data class Delete(val path: DocPath, val index: Int) : EditOp
    data class Move(val path: DocPath, val from: Int, val to: Int) : EditOp

    /** Moves an item to the end of another section. */
    data class MoveItem(val fromSection: Int, val index: Int, val toSection: Int) : EditOp

    /** Copies the node after itself with a new code. */
    data class Duplicate(val path: DocPath, val index: Int) : EditOp

    /** Replaces the object at [path] (empty = the whole template) — Advanced JSON. */
    data class Replace(val path: DocPath, val node: JsonElement) : EditOp

    /** Same, from text typed by the user: bad JSON -> JSON_PARSE, not an object -> NOT_OBJECT. */
    data class ReplaceText(val path: DocPath, val text: String) : EditOp

    /** Inserts a minimal new node of [kind] ("field", "heading", "table"; ignored outside `items`) and [type] (fields, columns); [index] null = end. */
    data class NewItem(val path: DocPath, val index: Int?, val kind: String, val type: String?) : EditOp
}

/** Pure edits: `(doc, op) -> doc`. Keys not touched stay as they are. */
object TemplateEdit {
    /** Keys that keep an empty string (lint reports them) instead of disappearing. */
    private val requiredKeys = setOf("id", "label", "title", "type", "kind", "value", "version")

    fun apply(doc: TemplateDoc, ops: List<EditOp>): TemplateDoc = ops.fold(doc) { d, op -> apply(d, op) }

    fun apply(doc: TemplateDoc, op: EditOp): TemplateDoc = when (op) {
        is EditOp.Set -> set(doc, op)
        is EditOp.Insert -> {
            if (op.node !is JsonObject) throw TemplateEditException.notObject()
            list(doc, op.path) { if (op.index !in 0..it.size) throw TemplateEditException.outOfRange(); it.add(op.index, op.node) }
        }
        is EditOp.Delete -> list(doc, op.path) { if (op.index !in it.indices) throw TemplateEditException.outOfRange(); it.removeAt(op.index) }
        is EditOp.Move -> list(doc, op.path) {
            if (op.from !in it.indices || op.to !in it.indices) throw TemplateEditException.outOfRange()
            it.add(op.to, it.removeAt(op.from))
        }
        is EditOp.MoveItem -> moveItem(doc, op)
        is EditOp.Duplicate -> duplicate(doc, op)
        is EditOp.Replace -> replace(doc, op.path, op.node)
        is EditOp.ReplaceText -> replace(doc, op.path, TemplateDoc.parse(op.text))
        is EditOp.NewItem -> {
            val node = TemplateNodes.make(doc.root, op.path, op.kind, op.type)
            list(doc, op.path) {
                val at = op.index ?: it.size
                if (at !in 0..it.size) throw TemplateEditException.outOfRange()
                it.add(at, node)
            }
        }
    }

    /** Edits the array at [path] (a missing array counts as empty). */
    private fun list(doc: TemplateDoc, path: DocPath, body: (MutableList<JsonElement>) -> Unit): TemplateDoc = doc.updating(path) { v ->
        val a = when (v) {
            null -> mutableListOf()
            is JsonArray -> v.toMutableList()
            else -> throw TemplateEditException.badPath()
        }
        body(a)
        JsonArray(a)
    }

    private fun set(doc: TemplateDoc, op: EditOp.Set): TemplateDoc = doc.updating(op.path) { v ->
        val o = (v as? JsonObject)?.toMutableMap() ?: throw TemplateEditException.badPath()
        val value = op.value
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        when {
            value == null || value is JsonNull -> o.remove(op.key)
            text != null && text.isEmpty() && op.key !in requiredKeys -> o.remove(op.key)
            else -> o[op.key] = value
        }
        val type = o["type"].str()
        if (op.key == "type" && type != null && type in TemplateNodes.choiceTypes && (o["options"].arr()?.isEmpty() != false)) {
            o["options"] = JsonArray(TemplateNodes.defaultOptions)
        }
        JsonObject(o)
    }

    private fun replace(doc: TemplateDoc, path: DocPath, node: JsonElement): TemplateDoc {
        if (node !is JsonObject) throw TemplateEditException.notObject()
        if (path.isEmpty()) return TemplateDoc(node)
        return doc.updating(path) { v -> if (v == null) throw TemplateEditException.badPath() else node }
    }

    private fun moveItem(doc: TemplateDoc, op: EditOp.MoveItem): TemplateDoc {
        val sections = doc.root.obj()?.get("sections").arr().orEmpty()
        if (op.fromSection !in sections.indices || op.toSection !in sections.indices) throw TemplateEditException.outOfRange()
        val item = doc.node(docPath("sections", op.fromSection, "items", op.index)) ?: throw TemplateEditException.outOfRange()
        val removed = apply(doc, EditOp.Delete(docPath("sections", op.fromSection, "items"), op.index))
        return list(removed, docPath("sections", op.toSection, "items")) { it.add(item) }
    }

    private fun duplicate(doc: TemplateDoc, op: EditOp.Duplicate): TemplateDoc {
        val a = doc.node(op.path) as? JsonArray ?: throw TemplateEditException.badPath()
        if (op.index !in a.indices) throw TemplateEditException.outOfRange()
        val copy = TemplateNodes.copy(a[op.index], doc.root, op.path)
        return list(doc, op.path) { it.add(op.index + 1, copy) }
    }
}
