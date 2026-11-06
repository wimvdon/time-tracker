package to.wim.timetracker.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * "Sign in with Notion" goes through a small sign-in server (a Cloudflare Worker, `worker/` in
 * this repository) that holds Notion's client secret, which can't be kept inside an app.
 * Flow (same as Windows, see docs/architecture.md):
 *  1. Make a random verifier and open [startUrl] with sha256(verifier) in the browser.
 *  2. Notion's page comes back to the app as `timetrackerfornotion://auth?code=…&state=…`
 *     (`timetrackerfornotion-debug://` for the Dev build).
 *  3. [exchange] code, state and verifier for tokens. Another app that catches the link can't
 *     do this: it doesn't have the verifier.
 *  4. When Notion answers 401, [refresh] the token once.
 */
object AuthServer {
    /** The only address besides api.notion.com the app talks to. Same as Windows (notionAuth.cjs). */
    const val URL = "https://timetracker-signin.wim.to"

    /** Which app link the server sends the browser back to: the Dev build has its own. */
    fun platform(packageName: String): String =
        if (packageName.endsWith(".debug")) "android-debug" else "android"

    fun startUrl(platform: String, challenge: String, base: String = URL): String =
        "$base/start?platform=${enc(platform)}&challenge=${enc(challenge)}"

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}

/** A random verifier and its sha256 challenge, both base64url without padding. */
data class Pkce(val verifier: String, val challenge: String) {
    companion object {
        private val random = SecureRandom()
        private val encoder = Base64.getUrlEncoder().withoutPadding()

        fun create(): Pkce {
            val bytes = ByteArray(32).also(random::nextBytes)
            val verifier = encoder.encodeToString(bytes)
            return Pkce(verifier, challengeFor(verifier))
        }

        fun challengeFor(verifier: String): String =
            encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    }
}

/** The tokens and workspace a successful sign-in or refresh gave. */
data class TokenResult(
    val accessToken: String,
    val refreshToken: String?,
    val workspaceName: String?,
    val workspaceId: String?,
)

sealed interface AuthResult {
    data class Success(val tokens: TokenResult) : AuthResult

    /** Notion refused (code already used, refresh token revoked): sign in again. */
    data class Refused(val error: String) : AuthResult

    /** The server or the network had trouble: keep what we have and try later. */
    data class Failed(val message: String) : AuthResult
}

/** The sign-in server's two calls. An interface so NotionSession can be tested without a network. */
interface AuthApi {
    suspend fun exchange(code: String, state: String, verifier: String): AuthResult

    suspend fun refresh(refreshToken: String): AuthResult
}

class AuthClient(private val baseUrl: String = AuthServer.URL) : AuthApi {
    private val httpClient = OkHttpClient.Builder().build()
    private val jsonType = "application/json".toMediaType()

    override suspend fun exchange(code: String, state: String, verifier: String): AuthResult =
        post("/token", mapOf("code" to code, "state" to state, "verifier" to verifier))

    override suspend fun refresh(refreshToken: String): AuthResult =
        post("/refresh", mapOf("refresh_token" to refreshToken))

    private suspend fun post(path: String, body: Map<String, String>): AuthResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .post(body.toJsonElement().toString().toRequestBody(jsonType))
            .build()
        try {
            val response = httpClient.newCall(request).await()
            val text = response.use { it.body.string() }
            parseAuthResponse(response.code, text)
        } catch (e: java.io.IOException) {
            AuthResult.Failed("Network error reaching the sign-in server: ${e.message}")
        }
    }

    companion object {
        /** Turns the server's answer into an [AuthResult]. Plain, so it is unit tested. */
        fun parseAuthResponse(status: Int, body: String): AuthResult {
            val data = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrDefault(JsonObject(emptyMap()))
            val access = data.stringOrNull("access_token")
            if (status == 200 && !access.isNullOrBlank()) {
                return AuthResult.Success(
                    TokenResult(
                        accessToken = access,
                        refreshToken = data.stringOrNull("refresh_token"),
                        workspaceName = data.stringOrNull("workspace_name"),
                        workspaceId = data.stringOrNull("workspace_id"),
                    )
                )
            }
            val error = data.stringOrNull("error") ?: "HTTP $status"
            return if (status == 400) AuthResult.Refused(error) else AuthResult.Failed(error)
        }
    }
}
