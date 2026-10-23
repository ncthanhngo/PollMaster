package com.ncthanhngo.pollmaster.core.validation

import com.ncthanhngo.pollmaster.core.format.formatNumber
import com.ncthanhngo.pollmaster.core.format.formatShortest
import com.ncthanhngo.pollmaster.core.formula.rowViews
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.TableItem
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.model.asDouble
import com.ncthanhngo.pollmaster.core.model.asList
import com.ncthanhngo.pollmaster.core.model.asObject
import com.ncthanhngo.pollmaster.core.model.asText
import com.ncthanhngo.pollmaster.core.model.isEmpty
import com.ncthanhngo.pollmaster.core.sites.Site
import com.ncthanhngo.pollmaster.core.sites.distanceMeters

const val DEFAULT_MAX_ACCURACY_M = 15.0
const val SITE_DISTANCE_LIMIT_M = 100.0

enum class WarningKind { RANGE, GPS_ACCURACY, SITE_DISTANCE }

/** A soft problem (schema 7.5): never blocks completion and never counts in progress. */
data class Warning(
    val kind: WarningKind,
    val sectionId: String,
    val sectionTitle: String,
    val targetId: String,
    val label: String,
    val message: String,
    val rowId: String? = null,
    val columnId: String? = null,
) {
    /** Same key shape as [Issue.key], so cards can look up both. */
    val key: String get() = listOfNotNull(targetId, rowId, columnId).joinToString("/")
}

/** True when [value] lies outside the plausible range; either bound may be missing. */
fun outsideWarnRange(value: Double, warnMin: Double?, warnMax: Double?): Boolean =
    (warnMin != null && value < warnMin) || (warnMax != null && value > warnMax)

/** `Ngoài khoảng thường gặp 1000–2500 mm`; a single bound reads `≥ 5` / `≤ 80`. */
fun rangeWarningMessage(warnMin: Double?, warnMax: Double?, unit: String?): String {
    val range = when {
        warnMin != null && warnMax != null -> "${formatShortest(warnMin)}–${formatShortest(warnMax)}"
        warnMin != null -> "≥ ${formatShortest(warnMin)}"
        else -> "≤ ${formatShortest(warnMax ?: 0.0)}"
    }
    return "Ngoài khoảng thường gặp $range" + (unit?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "")
}

fun gpsAccuracyMessage(accuracy: Double) = "Độ chính xác GPS kém (±${formatNumber(accuracy, 0)} m)"

fun siteDistanceMessage(meters: Double) = "Cách điểm kế hoạch ${formatNumber(meters, 0)} m"

/** Warning rules for a template. [sites] is the imported list of planned points (may be empty). */
class WarningChecker(private val template: Template, private val sites: List<Site> = emptyList()) {

    fun check(record: Record): List<Warning> {
        val out = mutableListOf<Warning>()
        fun fields(sectionId: String, title: String, list: List<Field>) {
            for (f in list) fieldWarnings(f, record.values[f.id]).forEach { (kind, msg) ->
                out += Warning(kind, sectionId, title, f.id, f.label, msg)
            }
            for (f in list) siteDistance(record, f)?.takeIf { it > SITE_DISTANCE_LIMIT_M }?.let {
                out += Warning(WarningKind.SITE_DISTANCE, sectionId, title, f.id, f.label, siteDistanceMessage(it))
            }
        }
        fields(HEADER_SECTION_ID, HEADER_SECTION_TITLE, template.header)
        for (s in template.sections) {
            fields(s.id, s.title, s.items.filterIsInstance<Field>())
            for (t in s.items.filterIsInstance<TableItem>()) tableWarnings(s.id, s.title, t, record, out)
        }
        return out
    }

    private fun tableWarnings(sectionId: String, title: String, table: TableItem, record: Record, out: MutableList<Warning>) {
        for (view in table.rowViews(record).filter { !it.row.isEmpty() }) {
            for (col in table.columns) fieldWarnings(col, view.row.values[col.id]).forEach { (kind, msg) ->
                out += Warning(kind, sectionId, title, table.id, col.label, msg, rowId = view.row.id, columnId = col.id)
            }
        }
    }

    private fun fieldWarnings(f: Field, fv: FieldValue?): List<Pair<WarningKind, String>> {
        if (fv == null || fv.flag == FieldValue.FLAG_NOT_MEASURABLE) return emptyList()
        val out = mutableListOf<Pair<WarningKind, String>>()
        if (f.type == "number" || f.type == "integer") {
            val nums = if (f.samples != null) fv.v.asList().mapNotNull { it.asDouble() } else listOfNotNull(fv.v.asDouble())
            if (nums.any { outsideWarnRange(it, f.warnMin, f.warnMax) }) {
                out += WarningKind.RANGE to rangeWarningMessage(f.warnMin, f.warnMax, f.unit)
            }
        }
        for (p in f.parts) {
            val n = fv.parts[p.id].asDouble() ?: continue
            if ((p.type == "number" || p.type == "integer") && outsideWarnRange(n, p.warnMin, p.warnMax)) {
                out += WarningKind.RANGE to rangeWarningMessage(p.warnMin, p.warnMax, p.unit)
            }
        }
        if (f.type == "gps") {
            val acc = fv.v.asObject()?.get("accuracy").asDouble()
            if (acc != null && acc > (f.maxAccuracy ?: DEFAULT_MAX_ACCURACY_M)) out += WarningKind.GPS_ACCURACY to gpsAccuracyMessage(acc)
        }
        return out
    }

    /** Metres between the GPS reading in [field] and the planned point chosen in the field whose lookup fills it, if any. */
    fun siteDistance(record: Record, field: Field): Double? {
        if (field.type != "gps") return null
        val source = template.header.plus(template.sections.flatMap { s -> s.items.filterIsInstance<Field>() })
            .firstOrNull { f -> f.lookup?.gps == field.id } ?: return null
        val code = record.values[source.id]?.v.asText()?.trim() ?: return null
        val site = sites.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: return null
        val gps = record.values[field.id]?.v.asObject() ?: return null
        val lat = gps["lat"].asDouble() ?: return null
        val lng = gps["lng"].asDouble() ?: return null
        return distanceMeters(lat, lng, site.lat ?: return null, site.lng ?: return null)
    }
}

/** A reviewer's note still on a header/section field (schema 7.4; table cells take no notes). */
data class ReviewNote(
    val sectionId: String,
    val sectionTitle: String,
    val targetId: String,
    val label: String,
    val text: String,
)

fun reviewNotes(template: Template, record: Record): List<ReviewNote> {
    val out = mutableListOf<ReviewNote>()
    fun fields(sectionId: String, title: String, list: List<Field>) {
        for (f in list) record.values[f.id]?.review?.takeIf { it.isNotBlank() }?.let {
            out += ReviewNote(sectionId, title, f.id, f.label, it)
        }
    }
    fields(HEADER_SECTION_ID, HEADER_SECTION_TITLE, template.header)
    for (s in template.sections) {
        fields(s.id, s.title, s.items.filterIsInstance<Field>())
    }
    return out
}
