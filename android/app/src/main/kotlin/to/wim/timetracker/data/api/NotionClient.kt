package to.wim.timetracker.data.api

import to.wim.timetracker.data.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * The Notion token for a call, and a way to renew it. NotionSession implements it; every call
 * renews an expired token once (Notion answers 401) and retries.
 */
interface NotionAuth {
    /** The current access token, or "" when not signed in. */
    fun token(): String

    /** A new token after Notion refused [failedToken], or null when the user has to sign in again. */
    suspend fun refresh(failedToken: String): String?
}

/** Thrown when there is no (longer a) Notion sign-in; the UI asks to sign in. */
class NotSignedInException : Exception("Not signed in to Notion. Sign in in Settings.")

class NotionClient {
    private val BASE_URL = "https://api.notion.com/v1"
    private val NOTION_VERSION = "2022-06-28"

    private val JSON = "application/json".toMediaType()
    private val httpClient = OkHttpClient.Builder().build()

    /**
     * Notion allows roughly three requests per second per integration and answers
     * 429 once that is exceeded. Loading a workspace's fields fires several calls
     * at once (schema, related-database pages, users) on top of the running-timer
     * and recent-entry queries for every workspace, so a burst can easily hit that
     * ceiling. Retrying here — honouring Retry-After — keeps a throttled call from
     * surfacing as an empty schema or an empty option list further up.
     */
    private val maxAttempts = 4

    private suspend fun fetch(
        auth: NotionAuth,
        method: String,
        endpoint: String,
        body: Any? = null
    ): JsonObject = withContext(Dispatchers.IO) {
        var token = auth.token()
        if (token.isBlank()) throw NotSignedInException()

        val reqBody = body?.toJsonElement()?.toString()
            ?: if (method != "GET") "{}" else null

        fun request(token: String) = Request.Builder()
            .url("$BASE_URL$endpoint")
            .header("Authorization", "Bearer $token")
            .header("Notion-Version", NOTION_VERSION)
            .header("Content-Type", "application/json")
            .method(method, reqBody?.toRequestBody(JSON))
            .build()

        var renewed = false
        var lastError = Exception("Notion API error")
        var attempt = 0
        while (attempt < maxAttempts) {
            val response = httpClient.newCall(request(token)).await()
            val responseBody = response.use { it.body.string() }
            val data = runCatching { Json.parseToJsonElement(responseBody).jsonObject }
                .getOrDefault(JsonObject(emptyMap()))

            if (response.isSuccessful) return@withContext data

            if (response.code == 401 && !renewed) {
                renewed = true
                token = auth.refresh(token) ?: throw NotSignedInException()
                continue
            }

            lastError = Exception(
                data.stringOrNull("message") ?: "Notion API error (HTTP ${response.code})"
            )
            val retryable = response.code == 429 || response.code >= 500
            if (!retryable || attempt == maxAttempts - 1) throw lastError

            val retryAfterMs = response.header("Retry-After")?.toDoubleOrNull()?.times(1000)?.toLong()
            delay((retryAfterMs ?: (500L shl attempt)).coerceIn(300L, 10_000L))
            attempt++
        }
        throw lastError
    }

    suspend fun searchDatabases(auth: NotionAuth): List<NamedOption> {
        val results = mutableListOf<NamedOption>()
        var cursor: String? = null
        do {
            val body = buildMap<String, Any> {
                put("filter", mapOf("value" to "database", "property" to "object"))
                put("sort", mapOf("direction" to "descending", "timestamp" to "last_edited_time"))
                put("page_size", 100)
                if (cursor != null) put("start_cursor", cursor)
            }
            val data = fetch(auth, "POST", "/search", body)
            data.arrayOrNull("results")?.forEach { el ->
                val db = el.asObjectOrNull() ?: return@forEach
                val id = db.stringOrNull("id") ?: return@forEach
                val name = db.arrayOrNull("title")
                    ?.joinToString("") { it.asObjectOrNull()?.stringOrNull("plain_text") ?: "" }
                    ?.trim()
                    ?.ifBlank { "(Untitled)" } ?: "(Untitled)"
                results.add(NamedOption(id, name))
            }
            cursor = if (data.boolOrNull("has_more") == true)
                data.stringOrNull("next_cursor") else null
        } while (cursor != null)
        return results
    }

    suspend fun getDatabaseSchema(auth: NotionAuth, dbId: String): Map<String, SchemaProperty> {
        val data = fetch(auth, "GET", "/databases/$dbId")
        val props = data.objOrNull("properties") ?: return emptyMap()
        return props.mapNotNull { (name, el) ->
            val prop = el.asObjectOrNull() ?: return@mapNotNull null
            val type = prop.stringOrNull("type") ?: "rich_text"
            fun optionsOf(holder: String): List<StatusOption> =
                prop.objOrNull(holder)?.arrayOrNull("options")?.mapNotNull { opt ->
                    opt.asObjectOrNull()?.let { o ->
                        StatusOption(name = o.stringOrNull("name") ?: "", color = o.stringOrNull("color") ?: "")
                    }
                } ?: emptyList()
            val statusOptions = if (type == "status") optionsOf("status") else emptyList()
            val selectOptions = when (type) {
                "select" -> optionsOf("select")
                "multi_select" -> optionsOf("multi_select")
                else -> emptyList()
            }
            val relationDatabaseId = if (type == "relation")
                prop.objOrNull("relation")?.stringOrNull("database_id") else null
            name to SchemaProperty(name = name, type = type, statusOptions = statusOptions, selectOptions = selectOptions, relationDatabaseId = relationDatabaseId)
        }.toMap()
    }

    /** The plain text of a page's title property, or "Untitled" when it has none. */
    private fun titleOf(properties: JsonObject, fallback: String = "(Untitled)"): String =
        properties.values
            .mapNotNull { it.asObjectOrNull() }
            .firstOrNull { it.stringOrNull("type") == "title" }
            ?.arrayOrNull("title")
            ?.joinToString("") { it.asObjectOrNull()?.stringOrNull("plain_text") ?: "" }
            ?.trim()
            ?.ifBlank { fallback } ?: fallback

    suspend fun fetchDatabasePages(auth: NotionAuth, dbId: String): List<NamedOption> {
        val results = mutableListOf<NamedOption>()
        var cursor: String? = null
        do {
            val body = buildMap<String, Any> {
                put("page_size", 100)
                put("sorts", listOf(mapOf("timestamp" to "last_edited_time", "direction" to "descending")))
                if (cursor != null) put("start_cursor", cursor)
            }
            val data = fetch(auth, "POST", "/databases/$dbId/query", body)
            data.arrayOrNull("results")?.forEach { el ->
                val page = el.asObjectOrNull() ?: return@forEach
                val id = page.stringOrNull("id") ?: return@forEach
                val propsObj = page.objOrNull("properties") ?: return@forEach
                // Entries without a title still have to be listed: a relation value
                // pointing at one would otherwise have no matching option and the
                // field would read as empty even though the page is linked.
                val name = titleOf(propsObj)
                results.add(NamedOption(id, name))
            }
            cursor = if (data.boolOrNull("has_more") == true)
                data.stringOrNull("next_cursor") else null
        } while (cursor != null)
        return results
    }

    suspend fun listUsers(auth: NotionAuth): List<NamedOption> {
        val results = mutableListOf<NamedOption>()
        var cursor: String? = null
        do {
            val endpoint = "/users?page_size=100" + if (cursor != null) "&start_cursor=$cursor" else ""
            val data = fetch(auth, "GET", endpoint)
            data.arrayOrNull("results")?.forEach { el ->
                val user = el.asObjectOrNull() ?: return@forEach
                val id = user.stringOrNull("id") ?: return@forEach
                if (user.stringOrNull("type") == "person") {
                    val name = user.stringOrNull("name")
                        ?: user.objOrNull("person")?.stringOrNull("email")
                        ?: id
                    results.add(NamedOption(id, name))
                }
            }
            cursor = if (data.boolOrNull("has_more") == true)
                data.stringOrNull("next_cursor") else null
        } while (cursor != null)
        return results.sortedBy { it.name }
    }

    suspend fun createTimeEntry(
        auth: NotionAuth,
        dbId: String,
        title: String,
        startISO: String,
        projectId: String?,
        propertyMap: PropertyMap,
        extra: Map<String, Any?> = emptyMap()
    ): String {
        val properties = mutableMapOf<String, Any>()
        extra.forEach { (k, v) -> if (v != null) properties[k] = v }

        properties[propertyMap.timeEntryTitleProp] = mapOf(
            "title" to listOf(mapOf("text" to mapOf("content" to title)))
        )
        properties[propertyMap.timeEntryDateProp] = mapOf(
            "date" to mapOf("start" to startISO, "end" to null)
        )
        properties[propertyMap.timeEntryStatusProp] = mapOf(
            "status" to mapOf("name" to propertyMap.timeEntryStatusRunning)
        )
        if (!projectId.isNullOrBlank() && propertyMap.timeEntryRelationProp.isNotBlank()) {
            properties[propertyMap.timeEntryRelationProp] = mapOf(
                "relation" to listOf(mapOf("id" to projectId))
            )
        }

        val data = fetch(auth, "POST", "/pages", mapOf(
            "parent" to mapOf("database_id" to dbId),
            "properties" to properties
        ))
        return data.stringOrNull("id") ?: throw Exception("Notion did not return a page id")
    }

    suspend fun fetchRunningTimers(
        auth: NotionAuth,
        dbId: String,
        statusProp: String,
        statusRunning: String,
        dateProp: String
    ): List<RunningTimerResult> {
        val results = mutableListOf<RunningTimerResult>()
        var cursor: String? = null
        do {
            val body = buildMap<String, Any> {
                put("filter", mapOf("property" to statusProp, "status" to mapOf("equals" to statusRunning)))
                if (dateProp.isNotBlank()) {
                    put("sorts", listOf(mapOf("property" to dateProp, "direction" to "descending")))
                }
                put("page_size", 100)
                if (cursor != null) put("start_cursor", cursor)
            }
            val data = fetch(auth, "POST", "/databases/$dbId/query", body)
            data.arrayOrNull("results")?.forEach { el ->
                val page = el.asObjectOrNull() ?: return@forEach
                val pageId = page.stringOrNull("id") ?: return@forEach
                val propsObj = page.objOrNull("properties") ?: return@forEach
                val title = titleOf(propsObj, fallback = "Untitled")
                // An entry with no start (its date was never set, or was cleared in
                // Notion) has no elapsed time to show, so it is skipped — but only
                // it: the rest of the query's entries are still returned.
                val startISO = if (dateProp.isNotBlank())
                    propsObj.objOrNull(dateProp)?.objOrNull("date")?.stringOrNull("start")
                else null
                startISO ?: return@forEach
                val url = page.stringOrNull("url") ?: ""
                results.add(RunningTimerResult(pageId = pageId, title = title, startISO = startISO, url = url))
            }
            cursor = if (data.boolOrNull("has_more") == true)
                data.stringOrNull("next_cursor") else null
        } while (cursor != null)
        return results
    }

    suspend fun fetchRecentEntries(
        auth: NotionAuth,
        dbId: String,
        statusProp: String,
        statusDone: String,
        dateProp: String
    ): List<RecentEntryResult> {
        val body = buildMap<String, Any> {
            put("filter", mapOf("property" to statusProp, "status" to mapOf("equals" to statusDone)))
            if (dateProp.isNotBlank()) {
                put("sorts", listOf(mapOf("property" to dateProp, "direction" to "descending")))
            }
            put("page_size", 15)
        }
        val data = fetch(auth, "POST", "/databases/$dbId/query", body)
        val results = mutableListOf<RecentEntryResult>()
        data.arrayOrNull("results")?.forEach { el ->
            val page = el.asObjectOrNull() ?: return@forEach
            val pageId = page.stringOrNull("id") ?: return@forEach
            val propsObj = page.objOrNull("properties") ?: return@forEach
            val title = titleOf(propsObj, fallback = "Untitled")
            val datePropObj = if (dateProp.isNotBlank()) propsObj.objOrNull(dateProp)?.objOrNull("date") else null
            val startISO = datePropObj?.stringOrNull("start")
            val endISO = datePropObj?.stringOrNull("end")
            val url = page.stringOrNull("url") ?: ""
            results.add(RecentEntryResult(pageId = pageId, title = title, startISO = startISO, endISO = endISO, url = url))
        }
        return results
    }

    suspend fun updatePageProperties(auth: NotionAuth, pageId: String, properties: Map<String, Any?>) {
        fetch(auth, "PATCH", "/pages/$pageId", mapOf("properties" to properties.filterValues { it != null }))
    }

    /** Fetches a page's raw `properties` object, used to restore extra-field
     *  values (e.g. connected project) when loading an already-running timer. */
    suspend fun fetchPageProperties(auth: NotionAuth, pageId: String): JsonObject {
        val data = fetch(auth, "GET", "/pages/$pageId")
        return data.objOrNull("properties") ?: JsonObject(emptyMap())
    }

    suspend fun deletePage(auth: NotionAuth, pageId: String) {
        fetch(auth, "PATCH", "/pages/$pageId", mapOf("archived" to true))
    }

    suspend fun stopTimeEntry(
        auth: NotionAuth,
        pageId: String,
        startISO: String,
        endISO: String,
        propertyMap: PropertyMap
    ) {
        fetch(auth, "PATCH", "/pages/$pageId", mapOf(
            "properties" to mapOf(
                propertyMap.timeEntryDateProp to mapOf(
                    "date" to mapOf("start" to startISO, "end" to endISO)
                ),
                propertyMap.timeEntryStatusProp to mapOf(
                    "status" to mapOf("name" to propertyMap.timeEntryStatusDone)
                )
            )
        ))
    }

}

internal suspend fun Call.await(): Response = suspendCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: java.io.IOException) =
            cont.resumeWithException(Exception("Network error: ${e.message}"))
        override fun onResponse(call: Call, response: Response) =
            cont.resume(response)
    })
}
