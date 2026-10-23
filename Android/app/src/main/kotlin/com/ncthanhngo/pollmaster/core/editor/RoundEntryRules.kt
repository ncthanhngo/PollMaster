package com.ncthanhngo.pollmaster.core.editor

import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.HeadingItem
import com.ncthanhngo.pollmaster.core.model.Item
import com.ncthanhngo.pollmaster.core.model.RoundEntry
import com.ncthanhngo.pollmaster.core.model.isEmptyJson
import com.ncthanhngo.pollmaster.core.model.sample
import com.ncthanhngo.pollmaster.core.model.withSample
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Round-by-round entry (schema 8 `roundEntry`): "Lần 1 · Lần 2 · Lần 3" instead of three boxes per field. */
object RoundEntryRules {

    /** Fields with `samples` after each heading that has `roundEntry`, up to the next heading, keyed by heading id. */
    fun groups(items: List<Item>): Map<String, List<Field>> {
        val out = LinkedHashMap<String, List<Field>>()
        var current: HeadingItem? = null
        val fields = mutableListOf<Field>()
        fun close() { current?.takeIf { it.roundEntry != null }?.let { out[it.id] = fields.toList() }; fields.clear() }
        for (item in items) {
            when (item) {
                is HeadingItem -> { close(); current = item }
                is Field -> if (current?.roundEntry != null && item.samples != null) fields += item
                else -> Unit
            }
        }
        close()
        return out
    }

    /** The heading whose round group contains [fieldId], or null. */
    fun headingOf(items: List<Item>, fieldId: String): HeadingItem? =
        groups(items).entries.firstOrNull { (_, fs) -> fs.any { it.id == fieldId } }
            ?.let { (id, _) -> items.filterIsInstance<HeadingItem>().first { it.id == id } }

    /**
     * New value of the time field when the first value of [round] is typed into [editedFieldId]: the current
     * time ([now], "HH:mm") if that round's time is still empty; null when nothing should change.
     */
    fun timeFill(entry: RoundEntry, round: Int, editedFieldId: String, newValue: JsonElement?, timeValue: FieldValue?, now: String): JsonElement? {
        val timeField = entry.timeField ?: return null
        if (editedFieldId == timeField || newValue.isEmptyJson()) return null
        if (!timeValue.sample(round).isEmptyJson()) return null
        return timeValue.withSample(round, entry.samples, JsonPrimitive(now))
    }

    /** First sample index of [value] that is empty (so a cue opens the round still to be measured); last round if all are filled. */
    fun firstEmptySample(value: FieldValue?, count: Int): Int =
        (0 until count).firstOrNull { value.sample(it).isEmptyJson() } ?: (count - 1).coerceAtLeast(0)
}
