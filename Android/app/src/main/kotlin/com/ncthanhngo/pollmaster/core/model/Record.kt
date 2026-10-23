package com.ncthanhngo.pollmaster.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonTransformingSerializer

/**
 * Record as stored in record.json. Reading is tolerant (only `id` is required); writing always emits the
 * keys other platforms rely on (schemaVersion, status, values, tables, signatures) even when they hold defaults.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Record(
    @EncodeDefault val schemaVersion: Int = 1,
    val id: String,
    val templateId: String = "",
    val templateVersion: String = "",
    @EncodeDefault val status: String = STATUS_DRAFT,
    val createdAt: String = "",
    val updatedAt: String = "",
    val completedAt: String? = null,
    @EncodeDefault val values: Map<String, FieldValue> = emptyMap(),
    @EncodeDefault val tables: Map<String, List<Row>> = emptyMap(),
    @EncodeDefault val signatures: Map<String, SignatureValue> = emptyMap(),
) {
    val isCompleted: Boolean get() = status == STATUS_COMPLETED

    companion object {
        const val STATUS_DRAFT = "draft"
        const val STATUS_COMPLETED = "completed"
    }
}

@Serializable
data class FieldValue(
    val v: JsonElement? = null,
    val flag: String? = null,
    val reason: String? = null,
    val note: String? = null,
    val other: Map<String, String> = emptyMap(),
    val parts: Map<String, JsonElement> = emptyMap(),
    @Serializable(with = TolerantMediaListSerializer::class) val media: List<Media> = emptyList(),
) {
    companion object {
        const val FLAG_ESTIMATE = "estimate"
        const val FLAG_NOT_MEASURABLE = "notMeasurable"
    }
}

@Serializable
data class Media(
    val file: String,
    val takenAt: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
)

/** Reads a media list dropping entries that cannot be decoded, so one bad item never loses the record. */
object TolerantMediaListSerializer : JsonTransformingSerializer<List<Media>>(ListSerializer(Media.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val items = element as? JsonArray ?: return JsonArray(emptyList())
        return JsonArray(items.filter { runCatching { PollJson.decodeFromJsonElement(Media.serializer(), it) }.isSuccess })
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Row(
    val id: String,
    @EncodeDefault val values: Map<String, FieldValue> = emptyMap(),
    val stopwatchStart: String? = null,
    /** Only used when a row carries its own fixed data (e.g. test vectors); normally fixed data comes from the template. */
    val fixed: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class SignatureValue(
    val file: String,
    val name: String? = null,
    val signedAt: String? = null,
)
