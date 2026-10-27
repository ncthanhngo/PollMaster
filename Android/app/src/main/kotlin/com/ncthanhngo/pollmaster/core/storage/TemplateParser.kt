package com.ncthanhngo.pollmaster.core.storage

import com.ncthanhngo.pollmaster.core.formula.FormulaChecker
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.model.TableItem
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class TemplateException(message: String) : Exception(message)

object TemplateParser {
    private val fieldTypes = setOf(
        "text", "multiline", "number", "integer", "singleChoice", "multiChoice", "yesNo", "rating", "date", "time",
        "datetime", "sessionTime", "month", "gps", "compass", "photo", "video", "elapsed", "composite", "computed",
        "signature", "audio",
    )

    private val itemKinds = setOf("field", "heading", "table")

    /**
     * Decodes and semantically checks a template. Throws [TemplateException] whose message is every problem, one per line,
     * each starting with its JSON path ("sections[2].items[5].options: ..."). Same rules and paths as iOS (`TemplateLoader.problems`).
     */
    fun parse(text: String): Template {
        val (template, problems) = inspect(text)
        if (template == null || problems.isNotEmpty()) throw TemplateException(problems.joinToString("\n"))
        return template
    }

    /** Every blocking problem of [text] (empty = installable). Decode-level problems (bad JSON, unknown kind/type, missing key) come alone, like iOS. */
    fun problems(text: String): List<String> = inspect(text).second

    /** Non-blocking findings of a valid template (rules too risky to reject on, schema 6.6). */
    fun warnings(t: Template): List<String> {
        val out = ArrayList<String>()
        val sectionIds = HashSet<String>()
        t.sections.forEachIndexed { si, s ->
            if (!sectionIds.add(s.id)) out += "sections[$si]: Trùng mã mục ${s.id}"
            s.items.forEachIndexed { ii, item ->
                if (item is TableItem) item.columns.forEachIndexed { ci, c ->
                    if (c.type == "computed" || c.type == "signature") out += "sections[$si].items[$ii].columns[$ci].type: cột không dùng được kiểu ${c.type}"
                }
            }
        }
        return out
    }

    private fun inspect(text: String): Pair<Template?, List<String>> {
        val root = try {
            PollJson.parseToJsonElement(text)
        } catch (e: SerializationException) {
            return null to listOf("$: JSON không hợp lệ: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}")
        } catch (e: IllegalArgumentException) {
            return null to listOf("$: JSON không hợp lệ: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}")
        }
        decodeProblem(root)?.let { return null to listOf(it) }
        val template = try {
            PollJson.decodeFromString(Template.serializer(), text)
        } catch (e: SerializationException) {
            return null to listOf(describe(e))
        } catch (e: IllegalArgumentException) {
            return null to listOf(describe(e))
        }
        return template to check(template)
    }

    /** "Field 'label' is required ... at path: $.header[0].parts[0]" -> "header[0].parts[0].label: thiếu khóa"; else "$: JSON không hợp lệ: ...". */
    private fun describe(e: Exception): String {
        val msg = e.message?.lineSequence()?.firstOrNull().orEmpty()
        val missing = Regex("Field '(\\w+)' is required.* at path: \\$\\.?(.*)").find(msg)
        if (missing != null) return "${join(missing.groupValues[2], missing.groupValues[1])}: thiếu khóa '${missing.groupValues[1]}'"
        return "$: JSON không hợp lệ: $msg"
    }

    private fun join(path: String, key: String) = if (path.isEmpty()) key else "$path.$key"

    /** First unknown item kind / field type / part type in decoding order (iOS fails decoding at the same place). */
    private fun decodeProblem(root: JsonElement): String? {
        val o = root as? JsonObject ?: return "$: gốc của mẫu phải là object"
        fun fieldProblem(f: JsonElement, path: String): String? {
            val fo = f as? JsonObject ?: return null
            val type = (fo["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (type != null && type !in fieldTypes) return "$path.type: kiểu trường không hỗ trợ \"$type\""
            ((fo["parts"] as? JsonArray) ?: return null).forEachIndexed { k, p ->
                val pt = ((p as? JsonObject)?.get("type") as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (pt != null && pt !in fieldTypes) return "$path.parts[$k].type: kiểu trường không hỗ trợ \"$pt\""
            }
            return null
        }
        (o["header"] as? JsonArray)?.forEachIndexed { i, f -> fieldProblem(f, "header[$i]")?.let { return it } }
        (o["sections"] as? JsonArray)?.forEachIndexed { si, s ->
            ((s as? JsonObject)?.get("items") as? JsonArray)?.forEachIndexed { ii, item ->
                val path = "sections[$si].items[$ii]"
                val io = item as? JsonObject ?: return@forEachIndexed
                val kind = (io["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (kind != null && kind !in itemKinds) return "$path.kind: kind không hợp lệ: $kind"
                if (kind == "heading") return@forEachIndexed
                if (kind == "table") {
                    (io["columns"] as? JsonArray)?.forEachIndexed { ci, c -> fieldProblem(c, "$path.columns[$ci]")?.let { return it } }
                } else fieldProblem(io, path)?.let { return it }
            }
        }
        return null
    }

    private fun check(t: Template): List<String> {
        val problems = ArrayList<String>()
        if (t.schemaVersion != 1) problems += "schemaVersion: schemaVersion ${t.schemaVersion} chưa được hỗ trợ"
        val seen = HashSet<String>()
        val tableIds = t.sections.flatMap { s -> s.items.filterIsInstance<TableItem>().map { it.id } }.toSet()
        fun unique(id: String, path: String) {
            if (!seen.add(id)) problems += "$path: Trùng mã: $id"
        }
        t.header.forEachIndexed { i, f -> unique(f.id, "header[$i]"); checkField(f, "header[$i]", tableIds, true, problems) }
        t.sections.forEachIndexed { si, s ->
            s.items.forEachIndexed { ii, item ->
                val path = "sections[$si].items[$ii]"
                unique(item.id, path)
                when (item) {
                    is Field -> checkField(item, path, tableIds, true, problems)
                    is TableItem -> {
                        item.pairedWith?.let { other ->
                            if (other !in tableIds || other == item.id) problems += "$path.pairedWith: Bảng ghép đôi không tồn tại: $other"
                        }
                        val cols = HashSet<String>()
                        item.columns.forEachIndexed { ci, c ->
                            if (!cols.add(c.id)) problems += "$path.columns[$ci]: Trùng cột ${c.id} trong bảng ${item.id}"
                            checkField(c, "$path.columns[$ci]", tableIds, false, problems)
                        }
                    }
                    else -> Unit
                }
            }
        }
        return problems
    }

    private fun checkField(f: Field, path: String, tableIds: Set<String>, requireFormula: Boolean, problems: MutableList<String>) {
        fun needsOptions(type: String, options: List<*>) = (type == "singleChoice" || type == "multiChoice") && options.isEmpty()
        if (needsOptions(f.type, f.options)) problems += "$path.options: ${f.id} cần danh sách lựa chọn"
        f.parts.forEachIndexed { k, p ->
            if (needsOptions(p.type, p.options)) problems += "$path.parts[$k].options: ${p.id} cần danh sách lựa chọn"
        }
        if (f.type != "computed" && f.formula == null) return
        val src = f.formula ?: run { if (requireFormula) problems += "$path.formula: ${f.id} thiếu formula"; return }
        FormulaChecker.check(src, tableIds)?.let { problems += "$path.formula: công thức không hợp lệ – $it" }
    }
}
