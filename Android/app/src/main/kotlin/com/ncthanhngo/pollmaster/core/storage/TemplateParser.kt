package com.ncthanhngo.pollmaster.core.storage

import com.ncthanhngo.pollmaster.core.formula.FormulaChecker
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.model.TableItem
import kotlinx.serialization.SerializationException

class TemplateException(message: String) : Exception(message)

object TemplateParser {
    private val fieldTypes = setOf(
        "text", "multiline", "number", "integer", "singleChoice", "multiChoice", "yesNo", "rating", "date", "time",
        "datetime", "sessionTime", "month", "gps", "compass", "photo", "video", "elapsed", "composite", "computed",
        "signature",
    )

    /** Decodes and semantically checks a template. Messages include the JSON path of the problem. */
    fun parse(text: String): Template {
        val template = try {
            PollJson.decodeFromString(Template.serializer(), text)
        } catch (e: SerializationException) {
            throw TemplateException("JSON không hợp lệ: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}")
        } catch (e: IllegalArgumentException) {
            throw TemplateException("JSON không hợp lệ: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}")
        }
        check(template)
        return template
    }

    private fun check(t: Template) {
        if (t.schemaVersion != 1) throw TemplateException("schemaVersion ${t.schemaVersion} chưa được hỗ trợ")
        val seen = HashSet<String>()
        val tableIds = t.sections.flatMap { s -> s.items.filterIsInstance<TableItem>().map { it.id } }.toSet()
        fun unique(id: String, path: String) {
            if (!seen.add(id)) throw TemplateException("Mã trùng \"$id\" tại $path")
        }
        t.header.forEachIndexed { i, f -> checkField(f, "header[$i]", tableIds) { unique(f.id, "header[$i]") } }
        t.sections.forEachIndexed { si, s ->
            s.items.forEachIndexed { ii, item ->
                val path = "sections[$si].items[$ii]"
                when (item) {
                    is Field -> checkField(item, path, tableIds) { unique(item.id, path) }
                    is TableItem -> {
                        unique(item.id, path)
                        val cols = HashSet<String>()
                        item.columns.forEachIndexed { ci, c ->
                            if (!cols.add(c.id)) throw TemplateException("Mã cột trùng \"${c.id}\" tại $path.columns[$ci]")
                            checkField(c, "$path.columns[$ci]", tableIds) {}
                        }
                    }
                    else -> unique(item.id, path)
                }
            }
        }
    }

    private fun checkField(f: Field, path: String, tableIds: Set<String>, register: () -> Unit) {
        register()
        if (f.type !in fieldTypes) throw TemplateException("Kiểu trường không hỗ trợ \"${f.type}\" tại $path (${f.id})")
        if (f.type == "computed") {
            val formula = f.formula ?: throw TemplateException("Thiếu formula tại $path.formula (${f.id})")
            FormulaChecker.check(formula, tableIds)?.let {
                throw TemplateException("Công thức lỗi tại $path.formula (${f.id}): $it")
            }
        }
        if ((f.type == "singleChoice" || f.type == "multiChoice") && f.options.isEmpty()) {
            throw TemplateException("Thiếu options tại $path.options (${f.id})")
        }
    }
}
