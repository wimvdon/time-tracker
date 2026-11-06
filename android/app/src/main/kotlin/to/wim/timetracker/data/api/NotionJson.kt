package to.wim.timetracker.data.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Null-safe reads for Notion's JSON.
 *
 * Notion sends JSON `null` for every empty property (`"date": null`,
 * `"select": null`, `"status": null`), and kotlinx.serialization represents
 * that as [JsonNull], which is itself a [JsonPrimitive]. These read "present,
 * and of the kind asked for, or null", so an empty property never reads as
 * the text "null" and never aborts the rest of the response.
 */
internal fun JsonObject.objOrNull(name: String): JsonObject? = get(name) as? JsonObject

internal fun JsonObject.arrayOrNull(name: String): JsonArray? = get(name) as? JsonArray

internal fun JsonObject.stringOrNull(name: String): String? = get(name).contentOrNull()

internal fun JsonObject.boolOrNull(name: String): Boolean? =
    (get(name) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

/** The element as an object, or null when it is `null`/a primitive/an array. */
internal fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

/** A string, number or boolean as its text; null for `null`, objects and arrays. */
internal fun JsonElement?.contentOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

/**
 * A request body built from maps, lists and plain values as JSON. Nulls are
 * kept: Notion reads an explicit `null` as "clear this" (`{"select": null}`
 * empties a select, `{"end": null}` removes a date's end).
 */
internal fun Any?.toJsonElement(): JsonElement =
    when (this) {
        null -> JsonNull
        is JsonElement -> this
        is String -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJsonElement() })
        is Iterable<*> -> JsonArray(map { it.toJsonElement() })
        is Array<*> -> JsonArray(map { it.toJsonElement() })
        else -> throw IllegalArgumentException("Cannot write ${this::class.java.simpleName} as JSON")
    }
