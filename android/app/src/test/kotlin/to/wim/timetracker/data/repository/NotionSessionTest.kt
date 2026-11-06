package to.wim.timetracker.data.repository

import to.wim.timetracker.data.api.AuthApi
import to.wim.timetracker.data.api.AuthClient
import to.wim.timetracker.data.api.AuthResult
import to.wim.timetracker.data.api.AuthServer
import to.wim.timetracker.data.api.Pkce
import to.wim.timetracker.data.api.TokenResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class NotionSessionTest {
    private class MemoryStore(var session: StoredSession? = null) : TokenStore {
        var verifier: String? = null

        override fun loadSession() = session

        override fun saveSession(session: StoredSession?) {
            this.session = session
        }

        override fun loadPendingVerifier() = verifier

        override fun savePendingVerifier(verifier: String?) {
            this.verifier = verifier
        }
    }

    private class FakeApi(
        val onExchange: suspend (String, String, String) -> AuthResult = { _, _, _ -> error("not expected") },
        val onRefresh: suspend (String) -> AuthResult = { error("not expected") },
    ) : AuthApi {
        val refreshCalls = mutableListOf<String>()
        val exchangeCalls = mutableListOf<Triple<String, String, String>>()

        override suspend fun exchange(code: String, state: String, verifier: String): AuthResult {
            exchangeCalls += Triple(code, state, verifier)
            return onExchange(code, state, verifier)
        }

        override suspend fun refresh(refreshToken: String): AuthResult {
            refreshCalls += refreshToken
            return onRefresh(refreshToken)
        }
    }

    private val signedIn = StoredSession("old", "nrt_old", "Team", "w1")

    private fun query(url: String): Map<String, String> =
        URI(url).rawQuery.split("&").associate {
            val (k, v) = it.split("=", limit = 2)
            k to URLDecoder.decode(v, "UTF-8")
        }

    @Test
    fun `the challenge is the sha256 of the verifier, both URL-safe`() {
        val pkce = Pkce.create()
        assertTrue(pkce.verifier.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertEquals(Pkce.challengeFor(pkce.verifier), pkce.challenge)
        // RFC 7636 appendix B example.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `the Dev build signs in through its own app link`() {
        assertEquals("android", AuthServer.platform("to.wim.timetracker"))
        assertEquals("android-debug", AuthServer.platform("to.wim.timetracker.debug"))
    }

    @Test
    fun `sign-in sends the challenge, then swaps the code with the matching verifier`() = runBlocking {
        val store = MemoryStore()
        val api = FakeApi(onExchange = { _, _, _ -> AuthResult.Success(TokenResult("ntn", "nrt", "Team", "w1")) })
        val session = NotionSession(store, api)

        val url = session.beginSignIn("android", serverUrl = "https://auth.test")
        val params = query(url)
        assertTrue(url.startsWith("https://auth.test/start?"))
        assertEquals("android", params["platform"])
        val events = async(start = CoroutineStart.UNDISPATCHED) { session.events.first() }

        session.completeSignIn(code = "the-code", state = "the-state", error = null)

        val (code, state, verifier) = api.exchangeCalls.single()
        assertEquals("the-code", code)
        assertEquals("the-state", state)
        assertEquals(params["challenge"], Pkce.challengeFor(verifier))
        assertEquals(StoredSession("ntn", "nrt", "Team", "w1"), store.session)
        assertEquals(NotionAccount("Team"), session.account.value)
        assertEquals("ntn", session.token())
        assertNull(store.verifier)
        assertEquals(SessionEvent.SignedIn("Team"), events.await())
    }

    @Test
    fun `cancelling on Notion's page or an unexpected link does nothing`() = runBlocking {
        val store = MemoryStore()
        val api = FakeApi()
        val session = NotionSession(store, api)
        session.beginSignIn("android")
        session.completeSignIn(code = null, state = "s", error = "access_denied")
        // No sign-in waiting any more: a second link (or one from elsewhere) is ignored.
        session.completeSignIn(code = "c", state = "s", error = null)
        assertTrue(api.exchangeCalls.isEmpty())
        assertNull(session.account.value)
    }

    @Test
    fun `a refused code is reported and nothing is saved`() = runBlocking {
        val store = MemoryStore()
        val session = NotionSession(store, FakeApi(onExchange = { _, _, _ -> AuthResult.Refused("invalid_grant") }))
        session.beginSignIn("android")
        val events = async(start = CoroutineStart.UNDISPATCHED) { session.events.first() }
        session.completeSignIn("c", "s", null)
        assertEquals(SessionEvent.SignInFailed("invalid_grant"), events.await())
        assertNull(store.session)
    }

    @Test
    fun `requests failing at the same time share one refresh`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val store = MemoryStore(signedIn)
        val api = FakeApi(onRefresh = {
            gate.await()
            AuthResult.Success(TokenResult("new", "nrt_new", null, null))
        })
        val session = NotionSession(store, api)

        val results = List(3) { async { session.refresh("old") } }
        gate.complete(Unit)
        assertEquals(listOf("new", "new", "new"), results.awaitAll())
        assertEquals(listOf("nrt_old"), api.refreshCalls)
        // The workspace is kept when the refresh answer leaves it out.
        assertEquals(StoredSession("new", "nrt_new", "Team", "w1"), store.session)
    }

    @Test
    fun `a refused refresh token signs out and says so`() = runBlocking {
        val store = MemoryStore(signedIn)
        val session = NotionSession(store, FakeApi(onRefresh = { AuthResult.Refused("invalid_grant") }))
        val events = async(start = CoroutineStart.UNDISPATCHED) { session.events.first() }
        assertNull(session.refresh("old"))
        assertNull(store.session)
        assertNull(session.account.value)
        assertEquals(SessionEvent.Expired, events.await())
    }

    @Test
    fun `a server problem during refresh keeps the sign-in`() = runBlocking {
        val store = MemoryStore(signedIn)
        val session = NotionSession(store, FakeApi(onRefresh = { AuthResult.Failed("HTTP 502") }))
        val failure = runCatching { session.refresh("old") }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("renew"))
        assertEquals(signedIn, store.session)
    }

    @Test
    fun `the server's answers are read as success, refused or failed`() {
        val ok = AuthClient.parseAuthResponse(200, """{"access_token":"a","refresh_token":"r","workspace_name":"W","workspace_id":"w"}""")
        assertEquals(AuthResult.Success(TokenResult("a", "r", "W", "w")), ok)
        assertEquals(AuthResult.Refused("invalid_grant"), AuthClient.parseAuthResponse(400, """{"error":"invalid_grant"}"""))
        assertEquals(AuthResult.Failed("notion_error"), AuthClient.parseAuthResponse(502, """{"error":"notion_error"}"""))
        assertEquals(AuthResult.Failed("HTTP 500"), AuthClient.parseAuthResponse(500, "not json"))
    }
}

