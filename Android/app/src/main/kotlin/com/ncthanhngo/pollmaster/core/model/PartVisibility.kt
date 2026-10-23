package com.ncthanhngo.pollmaster.core.model

import kotlinx.serialization.json.JsonArray

const val SELF = "\$self"

/**
 * A part is visible when it has no showWhen or the condition holds.
 * [lookup] resolves other fields (outside of the current scope).
 */
fun Part.isVisible(own: FieldValue?, lookup: (String) -> FieldValue?): Boolean {
    val cond = showWhen ?: return true
    val target = if (cond.field == SELF) own else lookup(cond.field)
    val v = target?.v
    cond.equals?.let { return jsonEquals(v, it) }
    cond.contains?.let { c ->
        // contains on a non-array value falls back to equality
        return if (v is JsonArray) v.any { jsonEquals(it, c) } else jsonEquals(v, c)
    }
    return true
}

fun Field.visibleParts(own: FieldValue?, lookup: (String) -> FieldValue?): List<Part> =
    parts.filter { it.isVisible(own, lookup) }
