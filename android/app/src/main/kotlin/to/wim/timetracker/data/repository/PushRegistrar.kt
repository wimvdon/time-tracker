package to.wim.timetracker.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** This phone's push address (Firebase Cloud Messaging registration token). */
interface PushTokens {
    /** The current token, created when needed; null when Firebase can't give one (no Play services). */
    suspend fun token(): String?

    /** Forgets the token, so Firebase has no address for this phone until asked again. */
    suspend fun delete()
}

/** The sign-in server's /devices (worker/src/push.js). */
interface DeviceApi {
    suspend fun register(pushToken: String)

    suspend fun unregister(pushToken: String, notionToken: String)
}

/** Which push token the server has for this phone (device-only, never backed up). */
interface RegisteredPushToken {
    fun get(): String?

    fun set(token: String?)
}

/**
 * Keeps the sign-in server's list of this user's phones in line with this phone: registered while
 * signed in with settings sync on (then timers started on Windows show their notification here),
 * removed when sync is turned off or on sign-out. Failures are left for the next app start.
 */
class PushRegistrar(
    private val wanted: suspend () -> Boolean,
    private val notionToken: () -> String,
    private val tokens: PushTokens,
    private val api: DeviceApi,
    private val registered: RegisteredPushToken,
) {
    private val mutex = Mutex()

    /** Registers or removes this phone, whichever the current settings call for. */
    suspend fun update() = mutex.withLock {
        if (wanted()) {
            val token = tokens.token() ?: return@withLock
            api.register(token)
            registered.set(token)
        } else {
            removeLocked(notionToken())
        }
    }

    /** Firebase gave this phone a new address. */
    suspend fun onNewToken(token: String) = mutex.withLock {
        if (!wanted()) return@withLock
        api.register(token)
        registered.set(token)
    }

    /** On sign-out, with the sign-in that was just removed. */
    suspend fun signedOut(previousNotionToken: String) = mutex.withLock { removeLocked(previousNotionToken) }

    private suspend fun removeLocked(notionToken: String) {
        val token = registered.get() ?: return
        // The server also forgets addresses Firebase reports gone, so a failed removal is harmless.
        if (notionToken.isNotBlank()) runCatching { api.unregister(token, notionToken) }
        registered.set(null)
        runCatching { tokens.delete() }
    }
}
