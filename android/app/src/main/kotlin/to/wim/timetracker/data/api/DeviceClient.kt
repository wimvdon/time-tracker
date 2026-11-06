package to.wim.timetracker.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import to.wim.timetracker.data.repository.DeviceApi

/**
 * Registers this phone for timer notifications with the sign-in server (worker/src/push.js,
 * /devices). Sends the Notion token so the server files the phone under the right user.
 */
class DeviceClient(
    private val auth: NotionAuth,
    private val baseUrl: String = AuthServer.URL,
) : DeviceApi {
    private val httpClient = OkHttpClient.Builder().build()

    override suspend fun register(pushToken: String) {
        var token = auth.token()
        if (token.isBlank()) throw NotSignedInException()
        var status = send("PUT", pushToken, token)
        if (status == 401) {
            token = auth.refresh(token) ?: throw NotSignedInException()
            status = send("PUT", pushToken, token)
        }
        if (status !in 200..299) throw Exception("Registering for timer notifications failed (HTTP $status)")
    }

    override suspend fun unregister(pushToken: String, notionToken: String) {
        send("DELETE", pushToken, notionToken)
    }

    private suspend fun send(method: String, pushToken: String, notionToken: String): Int = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/devices")
            .header("Authorization", "Bearer $notionToken")
            .method(method, body(pushToken).toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(request).await().use { it.code }
    }

    companion object {
        /** The request body. Plain, so it is unit tested. */
        fun body(pushToken: String): String =
            Json.encodeToString(buildJsonObject { put("token", JsonPrimitive(pushToken)) })
    }
}
