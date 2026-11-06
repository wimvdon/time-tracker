package to.wim.timetracker.data.api

import to.wim.timetracker.data.models.SyncPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The sync payload is written without unset optional fields, because the desktop app treats an
 * absent key and a null one differently, and read leniently: unknown keys are ignored and a null
 * or missing field takes its default.
 */
private val syncJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}

/** The payload as the JSON text stored in the sync page's code block. */
internal fun encodeSyncPayload(payload: SyncPayload): String = syncJson.encodeToString(SyncPayload.serializer(), payload)

/**
 * The payload in a sync code block, or null when the text is not one. A
 * payload needs a string `updatedAt` and a `workspaces` array; everything
 * else falls back to its default, so a payload from the desktop app or an
 * older version still loads.
 */
internal fun decodeSyncPayload(text: String): SyncPayload? {
    val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
    if (root.stringOrNull("updatedAt").isNullOrBlank() || root["workspaces"] !is JsonArray) return null
    return runCatching { syncJson.decodeFromJsonElement(SyncPayload.serializer(), normalizeSyncPayload(root)) }
        .getOrNull()
}

/**
 * Brings the desktop app's shapes in line with this app's model before decoding:
 * - a preset's extra values are kept as strings here, where the desktop app
 *   stores a multi-select as an array of names and a checkbox as a boolean;
 * - older desktop versions stored an extra field as just its property name;
 * - a workspace or preset without an id gets one, and missing names become "".
 */
private fun normalizeSyncPayload(root: JsonObject): JsonObject {
    fun JsonObject.plusEntries(vararg pairs: Pair<String, JsonElement>) = JsonObject(this + pairs)
    fun JsonObject.orBlank(key: String): Array<Pair<String, JsonElement>> =
        if (stringOrNull(key) == null) arrayOf(key to JsonPrimitive("")) else emptyArray()
    fun JsonObject.orNewId(): Array<Pair<String, JsonElement>> =
        if (stringOrNull("id").isNullOrBlank()) arrayOf("id" to JsonPrimitive(java.util.UUID.randomUUID().toString())) else emptyArray()

    val workspaces = root.arrayOrNull("workspaces")!!.mapNotNull { wsEl ->
        val ws = wsEl.asObjectOrNull() ?: return@mapNotNull null
        val extraProps = ws.arrayOrNull("extraProps")?.mapNotNull { epEl ->
            when (epEl) {
                is JsonObject -> epEl.plusEntries(*epEl.orBlank("propName"))
                is JsonPrimitive -> epEl.contentOrNull()?.let { JsonObject(mapOf("propName" to JsonPrimitive(it))) }
                else -> null
            }
        }
        val presets = ws.arrayOrNull("presets")?.mapNotNull { pEl ->
            val preset = pEl.asObjectOrNull() ?: return@mapNotNull null
            val values = preset.objOrNull("extraValues")?.mapValues { (_, value) ->
                JsonPrimitive(
                    when (value) {
                        is JsonArray -> value.mapNotNull { it.contentOrNull() }.joinToString(",")
                        else -> value.contentOrNull() ?: ""
                    }
                )
            }
            preset.plusEntries(
                *preset.orNewId(),
                *preset.orBlank("name"),
                *(values?.let { arrayOf<Pair<String, JsonElement>>("extraValues" to JsonObject(it)) } ?: emptyArray()),
            )
        }
        ws.plusEntries(
            *ws.orNewId(),
            *ws.orBlank("name"),
            *(extraProps?.let { arrayOf<Pair<String, JsonElement>>("extraProps" to JsonArray(it)) } ?: emptyArray()),
            *(presets?.let { arrayOf<Pair<String, JsonElement>>("presets" to JsonArray(it)) } ?: emptyArray()),
        )
    }
    return root.plusEntries("workspaces" to JsonArray(workspaces))
}
