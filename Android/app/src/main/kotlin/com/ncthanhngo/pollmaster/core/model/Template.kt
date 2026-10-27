package com.ncthanhngo.pollmaster.core.model

import com.ncthanhngo.pollmaster.core.project.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Template(
    val schemaVersion: Int = 1,
    val id: String,
    val version: String,
    val title: String,
    /** Short label on the cards of records made from this template (schema §1); falls back to [id]. */
    val shortName: String? = null,
    /** "#RRGGBB" colour of those cards; falls back to a palette colour picked from [id]. */
    val color: String? = null,
    val subtitle: String? = null,
    val instructions: String? = null,
    val recordTitle: String? = null,
    val mediaNamePattern: String? = null,
    val header: List<Field> = emptyList(),
    val sections: List<Section> = emptyList(),
    val signatures: List<SignatureDef> = emptyList(),
) {
    /** Label on record cards: [shortName], else the template id. */
    val badge: String get() = shortName?.trim()?.takeIf { it.isNotEmpty() } ?: id

    /** Card colour: [color] when it is "#RRGGBB", else a [Project.COLORS] colour picked from the id (the same on every device, and as on iOS). */
    val badgeColor: String get() = color?.takeIf { isHexColor(it) }?.uppercase()
        ?: Project.COLORS[id.toByteArray(Charsets.UTF_8).fold(0) { h, b -> (h * 31 + (b.toInt() and 0xFF)) % 1_000_003 } % Project.COLORS.size]

    companion object {
        fun isHexColor(s: String): Boolean = s.length == 7 && s[0] == '#' && s.drop(1).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }
}

@Serializable
data class Section(
    val id: String,
    val title: String,
    val note: String? = null,
    val items: List<Item> = emptyList(),
)

@Serializable
data class SignatureDef(val id: String, val label: String)

@Serializable
data class Option(val value: String, val label: String, val withText: Boolean = false)

@Serializable
data class ShowWhen(
    val field: String,
    val equals: JsonElement? = null,
    val contains: JsonElement? = null,
)

@Serializable
data class Part(
    val id: String,
    val label: String,
    val type: String = "text",
    val unit: String? = null,
    val options: List<Option> = emptyList(),
    val showWhen: ShowWhen? = null,
    val warnMin: Double? = null,
    val warnMax: Double? = null,
)

/** Integer shown as one-tap chips `min…max`; `max` comes from the numeric value at [maxRef] ("B-06.rows"), else [fallbackMax]. */
@Serializable
data class ChipsRange(val min: Int = 1, val maxRef: String? = null, val fallbackMax: Int = 10)

/** Heading option: the `samples` fields after it are entered round by round; [timeField] gets the time of a round's first value. */
@Serializable
data class RoundEntry(val samples: Int, val timeField: String? = null)

/** Reminder shown when a stopwatch mark is recorded: [text] plus a shortcut to [field]. */
@Serializable
data class Cue(val text: String, val field: String? = null)

/**
 * `lookup` of a field: choose from a list of [source] (only "sites") and copy parts of the choice into [fill] targets.
 * [gps] names the GPS field compared against the chosen site's planned coordinate (never filled from it).
 */
@Serializable
data class Lookup(val source: String = "sites", val fill: Map<String, String> = emptyMap(), val gps: String? = null)

@Serializable
data class Field(
    override val id: String,
    val label: String = "",
    val type: String,
    val kind: String? = null,
    val required: Boolean = false,
    val unit: String? = null,
    val method: String? = null,
    val hint: String? = null,
    val flags: Boolean? = null,
    val samples: Int? = null,
    val parts: List<Part> = emptyList(),
    val default: JsonElement? = null,
    val readonly: Boolean = false,
    val role: String? = null,
    val prefix: String? = null,
    val attachPhoto: Boolean = false,
    val min: Double? = null,
    val max: Double? = null,
    val decimals: Int? = null,
    val options: List<Option> = emptyList(),
    val labels: List<String>? = null,
    val allowUnknown: Boolean = false,
    val minLabel: String? = null,
    val maxLabel: String? = null,
    val minCount: Int? = null,
    val maxCount: Int? = null,
    val formula: String? = null,
    val photoRef: String? = null,
    val warnMin: Double? = null,
    val warnMax: Double? = null,
    val maxAccuracy: Double? = null,
    val lookup: Lookup? = null,
    val carryOver: Boolean = false,
    val short: Boolean = false,
    val stepper: Boolean = false,
    val chipsRange: ChipsRange? = null,
) : Item {
    /** Whether the estimate (~) / not-measurable flags are offered for this field. */
    val flagsEnabled: Boolean
        get() = flags ?: (type == "number" || type == "composite" || type == "text")

    val isMedia: Boolean get() = type == "photo" || type == "video" || type == "audio"
}

@Serializable
data class HeadingItem(
    override val id: String,
    val title: String = "",
    val note: String? = null,
    val kind: String? = null,
    val roundEntry: RoundEntry? = null,
) : Item

@Serializable
data class StopwatchDef(val columns: List<String>, val cues: Map<String, Cue> = emptyMap())

@Serializable
data class RowDef(val id: String, val fixed: Map<String, JsonElement> = emptyMap())

@Serializable
data class TableItem(
    override val id: String,
    val title: String = "",
    val note: String? = null,
    val kind: String? = null,
    val rowLabel: String? = null,
    val minRows: Int = 0,
    val required: Boolean = false,
    val rows: List<RowDef>? = null,
    val stopwatch: StopwatchDef? = null,
    val columns: List<Field> = emptyList(),
    val pairedWith: String? = null,
    val quickEntry: Boolean = false,
    val inline: Boolean = false,
) : Item {
    val isFixed: Boolean get() = rows != null
}
