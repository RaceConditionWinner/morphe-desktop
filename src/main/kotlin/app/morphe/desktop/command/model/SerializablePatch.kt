/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.desktop.command.model

import app.morphe.patcher.patch.Patch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@ExperimentalSerializationApi
@Serializable(with = PatchSerializer::class)
data class SerializablePatch(
    val name: String? = null,
    val index: Int? = null,
    val options: Map<String, JsonElement> = emptyMap()
)

@ExperimentalSerializationApi
fun Patch<*>.toSerializablePatch(): SerializablePatch {
    return SerializablePatch(
        name = this.name,
        options = this.options.mapValues { PatchSerializer.serializeValue(it.value.value) }
    )
}

@ExperimentalSerializationApi
object PatchSerializer : KSerializer<SerializablePatch> {
    fun serializeValue(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is JsonElement -> value
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is List<*> -> {
                buildJsonArray {
                    value.forEach { item ->
                        add(serializeValue(item))
                    }
                }
            }
            is Map<*, *> -> {
                buildJsonObject {
                    value.forEach {
                        require(it.key is String) {
                            "Map keys must be of type String for serialization, but found: ${it.key?.let { k -> k::class }}"
                        }
                        put(it.key as String, serializeValue(it.value))
                    }
                }
            }
            else -> JsonPrimitive(value.toString())
        }
    }

    override fun serialize(encoder: Encoder, value: SerializablePatch) {
        require(encoder is JsonEncoder)

        val jsonElement = buildJsonObject {
            require(value.name != null || value.index != null) {
                "Either name or index must be provided for a Patch."
            }

            if (value.name != null) {
                put("name", JsonPrimitive(value.name))
            } else {
                put("index", JsonPrimitive(value.index))
            }

            if (value.options.isNotEmpty()) {
                put("options", buildJsonArray {
                    value.options.forEach { (key, optionValue) ->
                        add(buildJsonObject {
                            put("key", JsonPrimitive(key))
                            put("value", optionValue)
                        })
                    }
                })
            }
        }
        encoder.encodeJsonElement(jsonElement)
    }

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Patch") {
        element<String?>("name")
        element<Int?>("index")
        element<Map<String, JsonElement>>("options")
    }

    override fun deserialize(decoder: Decoder): SerializablePatch {
        require(decoder is JsonDecoder) { "PatchSerializer can only deserialize JSON" }

        val obj = decoder.decodeJsonElement().jsonObject
        val name = obj["name"]?.jsonPrimitive?.contentOrNull
        val index = obj["index"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int
        require(name != null || index != null) {
            "Either name or index must be present for a Patch."
        }

        // Mirrors serialize's own wire format exactly: options is written as
        // [{"key": ..., "value": ...}, ...], not a plain JSON object, so that's
        // the shape read back here too.
        val options = obj["options"]?.jsonArray?.associate { entry ->
            val pair = entry.jsonObject
            val key = pair.getValue("key").jsonPrimitive.content
            key to pair.getValue("value")
        } ?: emptyMap()

        return SerializablePatch(name = name, index = index, options = options)
    }
}
