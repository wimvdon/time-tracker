package to.wim.timetracker.data.api

import to.wim.timetracker.data.models.SyncPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** What a push returned: stored, or refused because the server has newer settings ([payload]). */
data class PushResult(val accepted: Boolean, val payload: SyncPayload?)

/** The sync server's calls. An interface so SettingsSyncer can be tested without a network. */
interface SyncApi {
    /** The stored settings, or null when there are none yet. */
    suspend fun get(): SyncPayload?

    suspend fun put(payload: SyncPayload): PushResult

    suspend fun delete()
}

/**
 * Settings sync with the sign-in server (worker/src/sync.js), same as the Windows app's
 * electron/settingsSync.cjs. Sends the Notion token so the server knows whose settings they are,
 * and renews it once when the server answers 401.
 */
class SyncClient(
    private val auth: NotionAuth,
    private val baseUrl: String = AuthServer.URL,
) : SyncApi {
    private val httpClient = OkHttpClient.Builder().build()
    private val jsonType = "application/json".toMediaType()

    override suspend fun get(): SyncPayload? = payloadOf(request("GET", null).second)

    override suspend fun put(payload: SyncPayload): PushResult {
        val (status, data) = request("PUT", "{\"payload\":${encodeSyncPayload(payload)}}")
        return parsePush(status, data)
    }

    override suspend fun delete() {
        request("DELETE", null)
    }

    private suspend fun request(method: String, body: String?): Pair<Int, JsonObject> = withContext(Dispatchers.IO) {
        var token = auth.token()
        if (token.isBlank()) throw NotSignedInException()
        var renewed = false
        // At most two rounds: the second only after renewing the token.
        repeat(2) {
            val request = Request.Builder()
                .url("$baseUrl/settings")
                .header("Authorization", "Bearer $token")
                .method(method, body?.toRequestBody(jsonType))
                .build()
            val response = try {
                httpClient.newCall(request).await()
            } catch (e: java.io.IOException) {
                throw Exception("Network error reaching the sync server: ${e.message}", e)
            }
            val text = response.use { it.body.string() }
            val data = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap()))
            if (response.code == 401 && !renewed) {
                renewed = true
                token = auth.refresh(token) ?: throw NotSignedInException()
                return@repeat
            }
            // 409: the server's copy is newer; it comes back in "payload".
            if (response.isSuccessful || response.code == 409) return@withContext response.code to data
            throw Exception("Settings sync failed (${data.stringOrNull("error") ?: "HTTP ${response.code}"})")
        }
        throw NotSignedInException()
    }

    companion object {
        /** The payload in a server answer, or null. Plain, so it is unit tested. */
        fun payloadOf(data: JsonObject): SyncPayload? =
            (data["payload"] as? JsonObject)?.let { decodeSyncPayload(it.toString()) }

        fun parsePush(status: Int, data: JsonObject): PushResult =
            PushResult(accepted = status == 200 && data.stringOrNull("accepted") == "true", payload = payloadOf(data))
    }
}
