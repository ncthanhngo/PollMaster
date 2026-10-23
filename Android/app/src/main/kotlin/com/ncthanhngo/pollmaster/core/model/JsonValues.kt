package com.ncthanhngo.pollmaster.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** True for missing, null, blank string, empty array/object and arrays that only hold empty items. */
fun JsonElement?.isEmptyJson(): Boolean = when (this) {
    null, JsonNull -> true
    is JsonPrimitive -> isString && content.isBlank()
    is JsonArray -> all { it.isEmptyJson() }
    is JsonObject -> isEmpty()
}

fun JsonElement?.asDouble(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
fun JsonElement?.asBool(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
fun JsonElement?.asText(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
fun JsonElement?.asList(): List<JsonElement> = (this as? JsonArray)?.toList() ?: emptyList()

fun jsonOf(value: Double): JsonPrimitive =
    if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)

/** Loose equality used by showWhen: numbers compare numerically, everything else by type and content. */
fun jsonEquals(a: JsonElement?, b: JsonElement?): Boolean {
    if (a.isNullLike() || b.isNullLike()) return a.isNullLike() && b.isNullLike()
    val da = a.asDouble()
    val db = b.asDouble()
    if (da != null && db != null) return da == db
    val ba = a.asBool()
    val bb = b.asBool()
    if (ba != null && bb != null) return ba == bb
    return a == b
}

private fun JsonElement?.isNullLike() = this == null || this == JsonNull

/** Decodes a photo/video value (or attachPhoto media list) into [Media]. */
fun FieldValue.mediaItems(): List<Media> = v.asList().mapNotNull {
    runCatching { PollJson.decodeFromJsonElement(Media.serializer(), it) }.getOrNull()
}

fun mediaToJson(items: List<Media>): JsonElement? =
    if (items.isEmpty()) null else JsonArray(items.map { PollJson.encodeToJsonElement(Media.serializer(), it) })
