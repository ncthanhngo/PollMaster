package com.ncthanhngo.pollmaster.core.model

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A section item: [Field], [HeadingItem] or [TableItem], selected by the `kind` key. */
@Serializable(with = ItemSerializer::class)
sealed interface Item {
    val id: String
}

object ItemSerializer : JsonContentPolymorphicSerializer<Item>(Item::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<Item> {
        val kind = (element as? JsonObject)?.get("kind")?.jsonPrimitive?.content
        return when (kind) {
            "heading" -> HeadingItem.serializer()
            "table" -> TableItem.serializer()
            else -> Field.serializer()
        }
    }
}
