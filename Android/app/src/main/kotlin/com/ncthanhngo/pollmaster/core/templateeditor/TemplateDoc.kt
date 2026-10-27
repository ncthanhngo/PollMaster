package com.ncthanhngo.pollmaster.core.templateeditor

import com.ncthanhngo.pollmaster.core.model.PollJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** One step of a path into the template tree: `docPath("sections", 0, "items")`. */
sealed interface PathKey {
    data class Key(val name: String) : PathKey
    data class Index(val i: Int) : PathKey
}

typealias DocPath = List<PathKey>

/** Steps from strings (keys) and ints (indices). */
fun docPath(vararg steps: Any): DocPath = steps.map { if (it is Int) PathKey.Index(it) else PathKey.Key(it as String) }

/** "sections[2].items[5].options" (the form validator and lint messages use). */
val DocPath.text: String
    get() = fold("") { acc, k ->
        when (k) {
            is PathKey.Index -> "$acc[${k.i}]"
            is PathKey.Key -> if (acc.isEmpty()) k.name else "$acc.${k.name}"
        }
    }

/** Inverse of [text]; null when [s] is not made of `name` and `[n]` steps. */
fun parseDocPath(s: String): DocPath? {
    val out = ArrayList<PathKey>()
    val name = StringBuilder()
    val digits = StringBuilder()
    var inIndex = false
    fun flushName() { if (name.isNotEmpty()) { out += PathKey.Key(name.toString()); name.clear() } }
    for (ch in s) {
        when {
            inIndex -> when {
                ch == ']' -> { out += PathKey.Index(digits.toString().toIntOrNull() ?: return null); digits.clear(); inIndex = false }
                ch.isDigit() -> digits.append(ch)
                else -> return null
            }
            ch == '[' -> { flushName(); inIndex = true }
            ch == '.' -> flushName()
            else -> name.append(ch)
        }
    }
    if (inIndex) return null
    flushName()
    return out
}

/** [code] is shared with `shared/template-edit-vectors.json`. */
class TemplateEditException(val code: String, message: String) : Exception(message) {
    companion object {
        fun outOfRange() = TemplateEditException("OUT_OF_RANGE", "Vị trí nằm ngoài danh sách.")
        fun badPath() = TemplateEditException("BAD_PATH", "Không tìm thấy phần tử cần sửa.")
        fun notObject() = TemplateEditException("NOT_OBJECT", "Nội dung phải là một đối tượng JSON { … }.")
        fun jsonParse(detail: String) = TemplateEditException("JSON_PARSE", "JSON không hợp lệ: $detail")
        fun notDuplicable() = TemplateEditException("NOT_DUPLICABLE", "Không nhân bản được phần tử này.")
        fun badKind() = TemplateEditException("BAD_KIND", "Không thêm được loại phần tử này vào đây.")
    }
}

/** A string value (not a number or boolean that happens to print like one). */
fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray

/** Deep equality like the server's: key order does not matter and `1` equals `1.0`. */
fun semanticEquals(a: JsonElement?, b: JsonElement?): Boolean = when {
    a is JsonObject && b is JsonObject -> a.keys == b.keys && a.all { (k, v) -> semanticEquals(v, b[k]) }
    a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { semanticEquals(a[it], b[it]) }
    a is JsonNull && b is JsonNull -> true
    a is JsonPrimitive && b is JsonPrimitive -> when {
        a.isString || b.isString -> a.isString && b.isString && a.content == b.content
        a.booleanOrNull != null || b.booleanOrNull != null -> a.booleanOrNull == b.booleanOrNull
        else -> a.doubleOrNull != null && a.doubleOrNull == b.doubleOrNull
    }
    else -> false
}

/** A template as a raw JSON tree. Edits never go through the typed `Template` model, so keys the app does not know survive. */
class TemplateDoc(val root: JsonElement) {
    val id: String? get() = root.obj()?.get("id").str()
    val version: String? get() = root.obj()?.get("version").str()

    fun semanticEquals(other: TemplateDoc) = semanticEquals(root, other.root)

    /** Text in the shared key order (`template-schema.md` 11.4). */
    fun pretty(): String = TemplateJsonWriter.write(root)

    fun node(path: DocPath): JsonElement? {
        var cur: JsonElement = root
        for (k in path) {
            cur = when {
                k is PathKey.Key && cur is JsonObject -> cur[k.name] ?: return null
                k is PathKey.Index && cur is JsonArray -> cur.getOrNull(k.i) ?: return null
                else -> return null
            }
        }
        return cur
    }

    /** Rebuilds the tree along [path], handing the node there (null when its last key is missing) to [transform]. */
    fun updating(path: DocPath, transform: (JsonElement?) -> JsonElement): TemplateDoc {
        fun go(v: JsonElement, rest: List<PathKey>): JsonElement {
            val head = rest.firstOrNull() ?: return transform(v)
            return when {
                head is PathKey.Key && v is JsonObject -> {
                    if (rest.size == 1) return JsonObject(v + (head.name to transform(v[head.name])))
                    val child = v[head.name] ?: throw TemplateEditException.badPath()
                    JsonObject(v + (head.name to go(child, rest.drop(1))))
                }
                head is PathKey.Index && v is JsonArray -> {
                    if (head.i !in v.indices) throw TemplateEditException.outOfRange()
                    JsonArray(v.toMutableList().also { it[head.i] = go(v[head.i], rest.drop(1)) })
                }
                else -> throw TemplateEditException.badPath()
            }
        }
        return TemplateDoc(go(root, path))
    }

    companion object {
        /** Parses [text], which must be a JSON object. */
        fun of(text: String): TemplateDoc {
            val v = parse(text)
            if (v !is JsonObject) throw TemplateEditException.notObject()
            return TemplateDoc(v)
        }

        /** Parses any JSON text (the Advanced JSON box reports its error with this). */
        fun parse(text: String): JsonElement = try {
            PollJson.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw TemplateEditException.jsonParse(e.message?.lineSequence()?.firstOrNull().orEmpty())
        } catch (e: IllegalArgumentException) {
            throw TemplateEditException.jsonParse(e.message?.lineSequence()?.firstOrNull().orEmpty())
        }
    }
}
