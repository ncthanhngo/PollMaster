package com.ncthanhngo.pollmaster.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

fun FieldValue.isEmpty(): Boolean =
    v.isEmptyJson() && flag == null && reason.isNullOrBlank() && note.isNullOrBlank() &&
        other.isEmpty() && parts.values.all { it.isEmptyJson() } && media.isEmpty()

/** Drops empty keys so records stay compact; returns null when nothing is left. */
fun FieldValue.normalized(): FieldValue? {
    val cleaned = copy(
        v = v.takeUnless { it.isEmptyJson() },
        reason = reason?.takeIf { it.isNotBlank() },
        note = note?.takeIf { it.isNotBlank() },
        other = other.filterValues { it.isNotBlank() },
        parts = parts.filterValues { !it.isEmptyJson() },
    )
    return cleaned.takeUnless { it.isEmpty() }
}

/** Sample i of a samples value (array of length n). */
fun FieldValue?.sample(index: Int): JsonElement? = (this?.v as? JsonArray)?.getOrNull(index)

fun FieldValue?.withSample(index: Int, count: Int, element: JsonElement?): JsonElement {
    val current = (this?.v as? JsonArray)?.toMutableList() ?: mutableListOf()
    while (current.size < count) current.add(kotlinx.serialization.json.JsonNull)
    current[index] = element ?: kotlinx.serialization.json.JsonNull
    return JsonArray(current)
}

fun Row.isEmpty(): Boolean = values.values.all { it.isEmpty() }
