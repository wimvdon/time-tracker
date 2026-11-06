package to.wim.timetracker.data.repository

import to.wim.timetracker.data.api.AuthApi
import to.wim.timetracker.data.api.AuthResult
import to.wim.timetracker.data.api.AuthServer
import to.wim.timetracker.data.api.NotionAuth
import to.wim.timetracker.data.api.Pkce
import to.wim.timetracker.data.api.TokenResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The Notion sign-in as stored on this device (encrypted, never backed up). */
data class StoredSession(
    val accessToken: String,
    val refreshToken: String?,
    val workspaceName: String,
    val workspaceId: String?,
)

/** What the screens may know about the sign-in: not the token. */
data class NotionAccount(val workspaceName: String)

/** Where the sign-in is kept. SettingsRepository implements it; tests use a map. */
interface TokenStore {
    fun loadSession(): StoredSession?

    fun saveSession(session: StoredSession?)

    /** The verifier of a sign-in waiting for the browser, kept in case Android ends the app meanwhile. */
    fun loadPendingVerifier(): String?

    fun savePendingVerifier(verifier: String?)
}

sealed interface SessionEvent {
    data class SignedIn(val workspaceName: String) : SessionEvent

    data class SignInFailed(val message: String) : SessionEvent

    /** Notion no longer accepts the sign-in: the user has to sign in again. */
    data object Expired : SessionEvent
}

/**
 * "Sign in with Notion" and keeping the sign-in fresh. Plain Kotlin (the browser is opened by
 * the screen, storage and the sign-in server are passed in), so it is unit tested. Same flow
 * and rules as the Windows app's electron/notionAuth.cjs.
 */
class NotionSession(
    private val store: TokenStore,
    private val api: AuthApi,
) : NotionAuth {
    private val _account = MutableStateFlow(store.loadSession()?.toAccount())
    val account: StateFlow<NotionAccount?> = _account.asStateFlow()

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    private val refreshMutex = Mutex()

    fun isSignedIn(): Boolean = _account.value != null

    override fun token(): String = store.loadSession()?.accessToken.orEmpty()

    /** Starts a sign-in: remembers a new verifier and returns the page to open in the browser. */
    fun beginSignIn(platform: String, serverUrl: String = AuthServer.URL): String {
        val pkce = Pkce.create()
        store.savePendingVerifier(pkce.verifier)
        return AuthServer.startUrl(platform, pkce.challenge, serverUrl)
    }

    /**
     * Finishes a sign-in with what the browser sent back to the app link. Reports the outcome
     * on [events]; a sign-in the user cancelled on Notion's page is ended quietly.
     */
    suspend fun completeSignIn(code: String?, state: String?, error: String?) {
        val verifier = store.loadPendingVerifier()
        store.savePendingVerifier(null)
        when {
            error == "access_denied" -> return
            verifier == null -> return // Not started here, or already handled.
            error != null || code.isNullOrBlank() || state.isNullOrBlank() -> {
                _events.emit(SessionEvent.SignInFailed(error ?: "invalid_callback"))
                return
            }
        }
        when (val result = api.exchange(code!!, state!!, verifier!!)) {
            is AuthResult.Success -> {
                save(result.tokens, previous = null)
                _events.emit(SessionEvent.SignedIn(_account.value?.workspaceName.orEmpty()))
            }
            is AuthResult.Refused -> _events.emit(SessionEvent.SignInFailed(result.error))
            is AuthResult.Failed -> _events.emit(SessionEvent.SignInFailed(result.message))
        }
    }

    fun signOut() {
        store.saveSession(null)
        _account.value = null
    }

    /**
     * A new access token after Notion refused [failedToken], or null when the user has to sign
     * in again. Calls at the same time share one refresh; one that comes after another call
     * already renewed the token just gets the new token.
     */
    override suspend fun refresh(failedToken: String): String? = refreshMutex.withLock {
        val current = store.loadSession() ?: return@withLock null
        if (current.accessToken != failedToken) return@withLock current.accessToken
        val refreshToken = current.refreshToken
        if (refreshToken.isNullOrBlank()) {
            expire()
            return@withLock null
        }
        when (val result = api.refresh(refreshToken)) {
            is AuthResult.Success -> {
                save(result.tokens, previous = current)
                result.tokens.accessToken
            }
            is AuthResult.Refused -> {
                expire()
                null
            }
            // The server or the network had trouble: keep the sign-in, this call fails.
            is AuthResult.Failed -> throw Exception("Could not renew the Notion sign-in (${result.message})")
        }
    }

    private suspend fun expire() {
        signOut()
        _events.emit(SessionEvent.Expired)
    }

    private fun save(tokens: TokenResult, previous: StoredSession?) {
        val session = StoredSession(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken ?: previous?.refreshToken,
            workspaceName = tokens.workspaceName ?: previous?.workspaceName.orEmpty(),
            workspaceId = tokens.workspaceId ?: previous?.workspaceId,
        )
        store.saveSession(session)
        _account.value = session.toAccount()
    }

    private fun StoredSession.toAccount() = NotionAccount(workspaceName)
}
