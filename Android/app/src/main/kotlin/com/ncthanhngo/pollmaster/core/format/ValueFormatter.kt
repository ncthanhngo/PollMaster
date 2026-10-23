package com.ncthanhngo.pollmaster.core.format

import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.Option
import com.ncthanhngo.pollmaster.core.model.Part
import com.ncthanhngo.pollmaster.core.model.asBool
import com.ncthanhngo.pollmaster.core.model.asDouble
import com.ncthanhngo.pollmaster.core.model.asList
import com.ncthanhngo.pollmaster.core.model.asObject
import com.ncthanhngo.pollmaster.core.model.asText
import com.ncthanhngo.pollmaster.core.model.isEmptyJson
import com.ncthanhngo.pollmaster.core.model.isVisible
import com.ncthanhngo.pollmaster.core.model.mediaItems
import kotlinx.serialization.json.JsonElement
import java.util.Locale

const val KDD = "KĐĐ"

/**
 * Turns stored values into display text (used by UI summaries and all exports).
 * [lookup] resolves other fields for part showWhen conditions.
 */
class ValueFormatter(private val lookup: (String) -> FieldValue? = { null }) {

    /** Value text without unit, but with the ~ / KĐĐ markers. */
    fun display(field: Field, fv: FieldValue?, computed: Any? = null): String {
        if (fv?.flag == FieldValue.FLAG_NOT_MEASURABLE) {
            val reason = fv.reason?.takeIf { it.isNotBlank() }
            return if (reason != null) "$KDD ($reason)" else KDD
        }
        val body = body(field, fv, computed)
        return if (fv?.flag == FieldValue.FLAG_ESTIMATE && body.isNotEmpty()) "~$body" else body
    }

    /** Numeric value (rounded exactly as displayed) when the cell is a plain number (no flag, no parts), else null. */
    fun plainNumber(field: Field, fv: FieldValue?, computed: Any?): Double? {
        if (fv?.flag != null) return null
        if (field.type == "computed") return (computed as? Double)?.let { roundHalfAway(it, field.decimals ?: 2) }
        if (field.samples != null || field.parts.isNotEmpty()) return null
        if (field.type != "number" && field.type != "integer") return null
        return fv?.v.asDouble()?.let { v -> field.decimals?.let { roundHalfAway(v, it) } ?: v }
    }

    /** Display text of a number/integer column value stored outside a record (fixed row data). */
    fun numberText(field: Field, value: Double): String = field.decimals?.let { formatNumber(value, it) } ?: formatShortest(value)

    private fun body(field: Field, fv: FieldValue?, computed: Any?): String {
        val main = when {
            field.type == "computed" -> computedText(field, computed)
            field.samples != null && field.type != "computed" ->
                fv?.v.asList().joinToString(" | ") { single(field, it, fv).ifEmpty { "–" } }
                    .takeIf { fv?.v.asList().any { e -> !e.isEmptyJson() } }.orEmpty()
            field.type == "composite" -> ""
            else -> fv?.v.takeIf { !it.isEmptyJson() }?.let { single(field, it, fv) }.orEmpty()
        }
        val parts = partsText(field, fv)
        return listOf(main, parts).filter { it.isNotEmpty() }.joinToString("; ")
    }

    private fun computedText(field: Field, computed: Any?): String {
        val decimals = field.decimals ?: 2
        fun one(x: Any?) = when (x) {
            is Double -> formatNumber(x, decimals)
            is Boolean -> if (x) "Có" else "Không"
            null -> ""
            else -> x.toString()
        }
        return if (computed is List<*>) {
            if (computed.all { it == null }) "" else computed.joinToString(" | ") { one(it).ifEmpty { "–" } }
        } else {
            one(computed)
        }
    }

    private fun single(field: Field, e: JsonElement, fv: FieldValue?): String = when (field.type) {
        "number", "integer" -> e.asDouble()?.let { numberText(field, it) } ?: e.asText().orEmpty()
        "singleChoice" -> e.asText()?.let { v -> optionText(field.options, v, fv) }.orEmpty()
        "multiChoice" -> fv?.v.asList().mapNotNull { it.asText() }.joinToString(", ") { optionText(field.options, it, fv) }
        "yesNo" -> yesNo(field, e)
        "rating" -> e.asDouble()?.let { formatNumber(it, 0) + "/" + (field.max?.toInt() ?: 5) }.orEmpty()
        "date" -> e.asText()?.let(::displayDate).orEmpty()
        "month" -> e.asText()?.let(::displayMonth).orEmpty()
        "datetime" -> e.asText()?.let { displayDateTime(it) }.orEmpty()
        "sessionTime" -> sessionTime(e)
        "gps" -> gps(e)
        "compass" -> e.asDouble()?.let { formatNumber(it, 0) + "°" }.orEmpty()
        "elapsed" -> e.asDouble()?.let(::formatElapsed).orEmpty()
        "photo", "video" -> fv?.mediaItems().orEmpty().joinToString(", ") { it.file.substringAfterLast('/') }
        "signature" -> e.asObject()?.get("file").asText()?.substringAfterLast('/').orEmpty()
        else -> e.asText().orEmpty()
    }

    private fun optionText(options: List<Option>, value: String, fv: FieldValue?): String {
        val opt = options.firstOrNull { it.value == value }
        val label = opt?.label ?: value
        val extra = fv?.other?.get(value)?.takeIf { it.isNotBlank() }
        return if (extra != null) "$label: $extra" else label
    }

    private fun yesNo(field: Field, e: JsonElement): String {
        if (e.asText() == "unknown") return KDD
        val labels = field.labels
        return when (e.asBool()) {
            true -> labels?.getOrNull(0) ?: "Có"
            false -> labels?.getOrNull(1) ?: "Không"
            null -> ""
        }
    }

    private fun sessionTime(e: JsonElement): String {
        val o = e.asObject() ?: return ""
        val start = o["start"].asText()?.let { displayDateTime(it) }
        val end = o["end"].asText()?.let { displayDateTime(it) }
        return when {
            start == null && end == null -> ""
            else -> "${start.orEmpty()} – ${end ?: "…"}"
        }
    }

    private fun gps(e: JsonElement): String {
        val o = e.asObject() ?: return ""
        val lat = o["lat"].asDouble() ?: return ""
        val lng = o["lng"].asDouble() ?: return ""
        val acc = o["accuracy"].asDouble()?.let { " (±${formatNumber(it, 0)} m)" }.orEmpty()
        return String.format(Locale.ROOT, "%.6f, %.6f", lat, lng) + acc
    }

    private fun partsText(field: Field, fv: FieldValue?): String =
        field.parts.filter { it.isVisible(fv, lookup) }.mapNotNull { part ->
            val e = fv?.parts?.get(part.id)
            if (e.isEmptyJson()) null else "${part.label}: ${partValue(part, e!!)}"
        }.joinToString("; ")

    private fun partValue(part: Part, e: JsonElement): String {
        val text = when (part.type) {
            "number", "integer" -> e.asDouble()?.let(::formatShortest) ?: e.asText().orEmpty()
            "singleChoice" -> e.asText()?.let { v -> part.options.firstOrNull { it.value == v }?.label ?: v }.orEmpty()
            "yesNo" -> if (e.asText() == "unknown") KDD else if (e.asBool() == true) "Có" else "Không"
            "rating" -> e.asDouble()?.let { formatNumber(it, 0) }.orEmpty()
            else -> e.asText().orEmpty()
        }
        return if (part.unit.isNullOrBlank()) text else "$text ${part.unit}"
    }
}
