package com.ncthanhngo.pollmaster.core.templateeditor

import com.ncthanhngo.pollmaster.core.formula.FormulaException
import com.ncthanhngo.pollmaster.core.formula.FormulaParser
import com.ncthanhngo.pollmaster.core.formula.formulaReferences
import com.ncthanhngo.pollmaster.core.storage.TemplateParser
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One finding about a draft: `ERROR` blocks publishing, `WARNING` does not. [path] is in the form of validator messages ("sections[0].items[2].options"). */
data class TemplateIssue(val severity: Severity, val code: String, val path: String, val message: String) {
    enum class Severity { ERROR, WARNING }
}

/**
 * Validator problems (`INVALID`, schema 6.6) plus the checks only the editor needs (schema 11.5). Works on the raw tree, so it can still
 * point at things when the typed decode fails. [base] is the template the draft started from (null for a new template).
 */
object TemplateLint {
    private val idRegex = Regex("^[A-Z0-9-]{1,40}$")

    fun check(doc: TemplateDoc, base: TemplateDoc?, isNewTemplate: Boolean = base == null): List<TemplateIssue> {
        val out = ArrayList<TemplateIssue>()
        for (p in TemplateParser.problems(doc.pretty())) {
            val i = p.indexOf(": ")
            out += TemplateIssue(TemplateIssue.Severity.ERROR, "INVALID", if (i >= 0) p.substring(0, i) else "$", if (i >= 0) p.substring(i + 2) else p)
        }
        val c = Collector(doc.root)
        c.run(isNewTemplate)
        if (base != null && !isNewTemplate) c.removedIds(base.root)
        return out + c.issues
    }

    private class Collector(val root: JsonElement) {
        val issues = ArrayList<TemplateIssue>()
        private val known: Set<String> = TemplateIds.usedIds(root) + root.obj()?.get("sections").arr().orEmpty().flatMap { s ->
            s.obj()?.get("items").arr().orEmpty().flatMap { i -> i.obj()?.get("columns").arr().orEmpty().mapNotNull { it.obj()?.get("id").str() } }
        }

        fun err(code: String, path: String, msg: String) { issues += TemplateIssue(TemplateIssue.Severity.ERROR, code, path, msg) }
        fun warn(code: String, path: String, msg: String) { issues += TemplateIssue(TemplateIssue.Severity.WARNING, code, path, msg) }

        private fun blank(v: JsonElement?) = v.str().orEmpty().isBlank()

        private fun need(o: JsonObject, key: String, path: String, code: String) {
            if (blank(o[key])) err(code, if (path.isEmpty()) key else "$path.$key", when (code) {
                "EMPTY_ID" -> "Mã không được để trống."
                "EMPTY_LABEL" -> "Nhãn không được để trống."
                else -> "Tên không được để trống."
            })
        }

        fun run(isNewTemplate: Boolean) {
            val o = root.obj() ?: return
            val id = o["id"].str().orEmpty()
            if (blank(o["id"])) err("EMPTY_ID", "id", "Mã không được để trống.")
            else if (isNewTemplate && !idRegex.matches(id)) err("BAD_TEMPLATE_ID", "id", "Mã mẫu chỉ gồm chữ in hoa, số và dấu gạch ngang (tối đa 40 ký tự).")
            need(o, "title", "", "EMPTY_TITLE")
            if (o["sections"].arr().orEmpty().isEmpty()) err("NO_SECTIONS", "sections", "Mẫu cần ít nhất một mục.")
            val fields = ArrayList<Pair<String, JsonObject>>()
            o["header"].arr().orEmpty().forEachIndexed { i, f -> f.obj()?.let { fields += "header[$i]" to it; field(it, "header[$i]") } }
            o["sections"].arr().orEmpty().forEachIndexed { si, s ->
                val so = s.obj() ?: return@forEachIndexed
                val sp = "sections[$si]"
                need(so, "id", sp, "EMPTY_ID"); need(so, "title", sp, "EMPTY_TITLE")
                so["items"].arr().orEmpty().forEachIndexed { ii, item ->
                    val io = item.obj() ?: return@forEachIndexed
                    val ip = "$sp.items[$ii]"
                    when (io["kind"].str() ?: "field") {
                        "heading" -> {
                            need(io, "id", ip, "EMPTY_ID")
                            if (io["title"] != null) need(io, "title", ip, "EMPTY_TITLE")
                            ref(io["roundEntry"].obj()?.get("timeField"), "$ip.roundEntry.timeField")
                        }
                        "table" -> table(io, ip)
                        else -> { fields += ip to io; field(io, ip) }
                    }
                }
            }
            multi(fields)
            o["signatures"].arr().orEmpty().forEachIndexed { k, s ->
                val so = s.obj() ?: return@forEachIndexed
                need(so, "id", "signatures[$k]", "EMPTY_ID"); need(so, "label", "signatures[$k]", "EMPTY_LABEL")
            }
            val seen = HashSet<String>()
            o["sections"].arr().orEmpty().forEachIndexed { i, s ->
                val sid = s.obj()?.get("id").str()
                if (!sid.isNullOrEmpty() && !seen.add(sid)) warn("DUP_SECTION_ID", "sections[$i]", "Trùng mã mục \"$sid\".")
            }
        }

        private fun table(t: JsonObject, path: String) {
            need(t, "id", path, "EMPTY_ID"); need(t, "title", path, "EMPTY_TITLE")
            val cols = t["columns"].arr().orEmpty().map { it.obj() ?: JsonObject(emptyMap()) }
            cols.forEachIndexed { k, c ->
                val cp = "$path.columns[$k]"
                field(c, cp)
                val ty = c["type"].str()
                if (ty == "computed" || ty == "signature") warn("COLUMN_TYPE", "$cp.type", "Cột không dùng được kiểu $ty.")
            }
            t["rows"].arr().orEmpty().forEachIndexed { k, r -> r.obj()?.let { need(it, "id", "$path.rows[$k]", "EMPTY_ID") } }
            val sw = t["stopwatch"].obj()
            sw?.get("columns").arr().orEmpty().forEachIndexed { k, c ->
                val id = c.str().orEmpty()
                if (cols.firstOrNull { it["id"].str() == id }?.get("type").str() != "elapsed") {
                    err("STOPWATCH_COLUMN", "$path.stopwatch.columns[$k]", "Cột bấm giờ \"$id\" không tồn tại hoặc không phải kiểu elapsed.")
                }
            }
            sw?.get("cues").obj()?.forEach { (key, cue) -> ref(cue.obj()?.get("field"), "$path.stopwatch.cues.$key.field") }
        }

        /** A field, column or part: codes, labels, options, ranges, references. */
        private fun field(f: JsonObject, path: String) {
            need(f, "id", path, "EMPTY_ID"); need(f, "label", path, "EMPTY_LABEL")
            val values = HashSet<String>()
            f["options"].arr().orEmpty().forEachIndexed { k, op ->
                val oo = op.obj() ?: return@forEachIndexed
                val p = "$path.options[$k]"
                need(oo, "value", p, "EMPTY_ID"); need(oo, "label", p, "EMPTY_LABEL")
                val v = oo["value"].str()
                if (!v.isNullOrEmpty() && !values.add(v)) err("DUP_OPTION", p, "Trùng giá trị lựa chọn \"$v\".")
            }
            for ((lo, hi) in listOf("min" to "max", "warnMin" to "warnMax")) {
                val a = f[lo].num()
                val b = f[hi].num()
                if (a != null && b != null && a > b) err("MIN_GT_MAX", "$path.$lo", "Giá trị nhỏ nhất lớn hơn giá trị lớn nhất.")
            }
            f["formula"].str()?.let { src ->
                val refs = try { formulaReferences(FormulaParser.parse(src)) } catch (_: FormulaException) { emptyList() }
                refs.forEach { ref(JsonPrimitive(it), "$path.formula") }
            }
            ref(f["photoRef"], "$path.photoRef")
            f["showWhen"].obj()?.get("field").str()?.let { if (it != "\$self") ref(JsonPrimitive(it), "$path.showWhen.field") }
            f["lookup"].obj()?.let { l ->
                l["fill"].obj()?.keys?.forEach { ref(JsonPrimitive(it), "$path.lookup.fill.$it") }
                ref(l["gps"], "$path.lookup.gps")
            }
            f["chipsRange"].obj()?.get("maxRef").str()?.let { ref(JsonPrimitive(it.substringBefore('.')), "$path.chipsRange.maxRef") }
            f["parts"].arr().orEmpty().forEachIndexed { k, p -> p.obj()?.let { field(it, "$path.parts[$k]") } }
        }

        private fun ref(v: JsonElement?, path: String) {
            val id = v.str()
            if (id.isNullOrEmpty() || id in known) return
            warn("DANGLING_REF", path, "\"$id\" không tồn tại (công thức, điều kiện hiện, ảnh tham chiếu…).")
        }

        private fun multi(fields: List<Pair<String, JsonObject>>) {
            for ((code, key, value) in listOf(Triple("MULTI_RECORD_CODE", "role", "recordCode"), Triple("MULTI_SESSION_TIME", "type", "sessionTime"))) {
                fields.filter { it.second[key].str() == value }.drop(1).forEach { (p, _) -> warn(code, "$p.$key", "Có nhiều hơn một trường $value.") }
            }
        }

        private class Codes(val header: Set<String>, val items: Set<String>, val cols: Map<String, Set<String>>)

        private fun codes(r: JsonElement): Codes {
            val h = r.obj()?.get("header").arr().orEmpty().mapNotNull { it.obj()?.get("id").str() }.toSet()
            val items = HashSet<String>()
            val cols = HashMap<String, Set<String>>()
            r.obj()?.get("sections").arr().orEmpty().forEach { s ->
                s.obj()?.get("items").arr().orEmpty().forEach { i ->
                    val o = i.obj() ?: return@forEach
                    val id = o["id"].str() ?: return@forEach
                    if (o["kind"].str() == "heading") return@forEach
                    items += id
                    if (o["kind"].str() == "table") cols[id] = o["columns"].arr().orEmpty().mapNotNull { it.obj()?.get("id").str() }.toSet()
                }
            }
            return Codes(h, items, cols)
        }

        /** Fields and tables the draft's base had that are gone; columns of tables that are still there. */
        fun removedIds(base: JsonElement) {
            val old = codes(base)
            val now = codes(root)
            fun msg(id: String) = "Mã \"$id\" có ở bản gốc nhưng không còn. Phiếu cũ giữ dữ liệu; xuất tổng hợp sẽ có cột riêng."
            old.header.sorted().filter { it !in now.header && it !in now.items }.forEach { warn("ID_REMOVED", "header", msg(it)) }
            old.items.sorted().filter { it !in now.items && it !in now.header }.forEach { warn("ID_REMOVED", "sections", msg(it)) }
            for ((t, cols) in old.cols.toSortedMap()) {
                val kept = now.cols[t] ?: continue
                var tablePath = "sections"
                root.obj()?.get("sections").arr().orEmpty().forEachIndexed { si, s ->
                    s.obj()?.get("items").arr().orEmpty().forEachIndexed { ii, i -> if (i.obj()?.get("id").str() == t) tablePath = "sections[$si].items[$ii].columns" }
                }
                cols.sorted().filter { it !in kept }.forEach { warn("ID_REMOVED", tablePath, msg(it)) }
            }
        }
    }
}
