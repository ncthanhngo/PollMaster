package com.ncthanhngo.pollmaster.core.editor

import com.ncthanhngo.pollmaster.core.model.ChipsRange
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.Part
import com.ncthanhngo.pollmaster.core.model.TableItem
import com.ncthanhngo.pollmaster.core.model.asDouble
import kotlin.math.floor

/** Layout decisions for field cards and tables that do not depend on Compose (schema 8, ux-spec L). */
object FieldLayoutRules {
    private const val INLINE_MAX_OPTIONS = 4
    private const val MAX_CHIPS = 40

    /** Compact (label and input on one row): number/integer without parts or samples, and `short` text only. */
    fun isCompactable(f: Field): Boolean =
        (f.type == "number" || f.type == "integer" || (f.type == "text" && f.short)) &&
            f.parts.isEmpty() && f.samples == null && !f.attachPhoto && !f.readonly && f.lookup == null

    /** Largest chip of [range]: the integer > 0 found at `maxRef` ("B-06.rows" = part `rows` of B-06), else the fallback. */
    fun chipsMax(range: ChipsRange, valueOf: (fieldId: String) -> FieldValue?): Int {
        val ref = range.maxRef ?: return range.fallbackMax
        val fv = valueOf(ref.substringBefore('.'))
        val part = ref.substringAfter('.', "")
        val n = (if (part.isEmpty()) fv?.v else fv?.parts?.get(part)).asDouble()
        val max = n?.takeIf { it > 0 && it == floor(it) }?.toInt() ?: return range.fallbackMax
        return if (max < range.min) range.fallbackMax else minOf(max, range.min + MAX_CHIPS)
    }

    /** Columns for the visible parts: three short number parts (L × W × H) share one row, otherwise two (three on wide screens). */
    fun partsColumns(parts: List<Part>, wide: Boolean): Int = when {
        parts.size >= 3 && parts.all { it.type == "number" || it.type == "integer" } -> 3
        parts.size >= 3 && wide -> 3
        else -> 2
    }

    /** The column answered directly in an `inline` fixed table: first yesNo / singleChoice with at most 4 options. */
    fun inlineChoiceColumn(table: TableItem): Field? {
        if (!table.inline || !table.isFixed) return null
        return table.columns.firstOrNull {
            !it.readonly && (it.type == "yesNo" || (it.type == "singleChoice" && it.options.size in 1..INLINE_MAX_OPTIONS))
        }
    }

    /** Empty-list text that names the button actually shown under it. */
    fun emptyTableMessage(table: TableItem, pairedQuick: Boolean): String {
        val noun = if (table.quickEntry) table.rowLabel?.lowercase() ?: "dòng" else "dòng"
        return "Chưa có $noun nào — bấm ${addButtonLabel(pairedQuick)}"
    }

    fun addButtonLabel(pairedQuick: Boolean) = if (pairedQuick) "Ghi lượt mới" else "Thêm dòng"
}
