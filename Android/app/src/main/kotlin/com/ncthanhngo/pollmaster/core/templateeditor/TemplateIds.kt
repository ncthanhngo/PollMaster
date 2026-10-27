package com.ncthanhngo.pollmaster.core.templateeditor

import kotlinx.serialization.json.JsonElement
import java.text.Normalizer

/** Codes for new template parts (`template-schema.md` 11.3). Pure: the random part of a template id is passed in. */
object TemplateIds {
    private val marks = Regex("\\p{Mn}")

    internal fun stripDiacritics(s: String): String =
        marks.replace(Normalizer.normalize(s.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD), "")

    /** Lower-case ASCII words joined by `_` ("Giếng khoan" -> "gieng_khoan"); "" when nothing is left. */
    fun slug(s: String): String {
        val out = StringBuilder()
        var sep = false
        for (c in stripDiacritics(s)) {
            if (c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z') {
                if (sep && out.isNotEmpty()) out.append('_')
                sep = false
                out.append(c)
            } else sep = true
        }
        return out.toString().lowercase()
    }

    /** Slug of [label] not in [taken]: "gieng_khoan", then "gieng_khoan_2", "_3"… ("opt" when the label has no letters). */
    fun optionValue(label: String, taken: Set<String>): String {
        val base = slug(label).ifEmpty { "opt" }
        if (base !in taken) return base
        var n = 2
        while ("${base}_$n" in taken) n++
        return "${base}_$n"
    }

    private fun pad(n: Int, width: Int) = n.toString().padStart(width, '0')

    /** [prefix] + the smallest number >= 1 not in [taken] ("sig1", "c2", "HD-01" with [width] 2 and a "HD-" prefix). */
    fun firstUnused(prefix: String, taken: Set<String>, width: Int = 1): String {
        var n = 1
        while (prefix + pad(n, width) in taken) n++
        return prefix + pad(n, width)
    }

    /** Every item-level code: header fields, sections, section items (not columns). */
    internal fun usedIds(root: JsonElement): Set<String> {
        val out = HashSet<String>()
        root.obj()?.get("header").arr()?.forEach { f -> f.obj()?.get("id").str()?.let(out::add) }
        root.obj()?.get("sections").arr()?.forEach { s ->
            s.obj()?.get("id").str()?.let(out::add)
            s.obj()?.get("items").arr()?.forEach { i -> i.obj()?.get("id").str()?.let(out::add) }
        }
        return out
    }

    /** Code of a new item. [section] is the index of the section it goes in (null = header); [kind] is "field", "heading" or "table". */
    fun nextItemId(root: JsonElement, section: Int?, kind: String): String {
        val taken = usedIds(root)
        if (section == null) return firstUnused("HD-", taken, 2)
        val sec = root.obj()?.get("sections").arr()?.getOrNull(section).obj()
        val sid = sec?.get("id").str() ?: "S"
        return when (kind) {
            "heading" -> firstUnused(sid, taken)
            "table" -> firstUnused(sid + "T", taken)
            else -> {
                val fields = sec?.get("items").arr().orEmpty().mapNotNull { it.obj() }.filter { (it["kind"].str() ?: "field") == "field" }
                fields.lastOrNull()?.get("id").str()?.let { successor(it, taken) } ?: firstUnused("$sid-", taken, 2)
            }
        }
    }

    /** "A-07" -> "A-08", "A-9" -> "A-10", skipping codes in [taken]; null when [id] does not end in a number. */
    internal fun successor(id: String, taken: Set<String>): String? {
        val digits = id.takeLastWhile { it in '0'..'9' }
        var n = (digits.toIntOrNull() ?: return null) + 1
        val prefix = id.dropLast(digits.length)
        while (prefix + pad(n, digits.length) in taken) n++
        return prefix + pad(n, digits.length)
    }

    /** First free letter A-Z for a new section (taken by a section or item), else "S1", "S2"…. */
    fun nextSectionId(root: JsonElement): String {
        val taken = usedIds(root)
        return ('A'..'Z').map { it.toString() }.firstOrNull { it !in taken } ?: firstUnused("S", taken)
    }

    /** `{SLUG ≤ 8}-{4 random base36}` in capitals, not in [taken] (the library on this device); [random] returns the 4 characters. */
    fun templateId(shortName: String?, title: String, taken: Set<String>, random: () -> String = ::randomSuffix): String {
        fun slugOf(s: String) = stripDiacritics(s).uppercase().filter { it in '0'..'9' || it in 'A'..'Z' }.take(8)
        val short = shortName?.let(::slugOf).orEmpty()
        val base = (short.ifEmpty { slugOf(title) }).ifEmpty { "MAU" }
        var id = ""
        repeat(50) {
            id = base + "-" + random().uppercase()
            if (id !in taken) return id
        }
        return id
    }

    fun randomSuffix(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return String(CharArray(4) { chars.random() })
    }
}
