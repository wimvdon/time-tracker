package to.wim.timetracker.service

import android.content.Context
import android.content.Intent
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import to.wim.timetracker.TimeTrackerApp
import to.wim.timetracker.data.repository.DataFiles
import to.wim.timetracker.data.repository.PushTokens
import to.wim.timetracker.data.repository.RegisteredPushToken
import to.wim.timetracker.data.repository.RemoteTimer
import to.wim.timetracker.data.repository.RemoteTimerAction
import kotlin.coroutines.resume

/**
 * Push messages from the sign-in server (worker/src/push.js): a timer started, changed or stopped
 * on Windows shows, updates or removes the running-timer notification here (RemoteTimer decides).
 */
class TimerPushService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val event = RemoteTimer.parse(message.data) ?: return
        val app = application as TimeTrackerApp
        // Runs on Firebase's own background thread, which waits for this to return.
        runBlocking {
            withTimeoutOrNull(8_000) {
                if (!app.settingsRepository.isSyncEnabled()) return@withTimeoutOrNull
                val local = app.settingsRepository.getActiveTimer()
                val action = RemoteTimer.decide(local, event, app.settingsRepository.getWorkspaces())
                when (action) {
                    is RemoteTimerAction.Show -> {
                        app.settingsRepository.saveActiveTimer(action.timer)
                        runCatching {
                            startForegroundService(
                                TimerForegroundService.buildStartIntent(
                                    this@TimerPushService,
                                    action.timer,
                                    action.workspace.name,
                                    action.workspace.roundingMinutes,
                                    action.workspace.color,
                                ),
                            )
                        }.onFailure { e -> android.util.Log.w("TimerPushService", "Could not show the timer", e) }
                        sendBroadcast(Intent(ACTION_ACTIVE_TIMER_CHANGED).setPackage(packageName))
                    }
                    is RemoteTimerAction.Clear -> {
                        app.settingsRepository.clearActiveTimer()
                        stopService(Intent(this@TimerPushService, TimerForegroundService::class.java))
                        sendBroadcast(
                            Intent(TimerForegroundService.ACTION_TIMER_STOPPED)
                                .setPackage(packageName)
                                .putExtra(TimerForegroundService.EXTRA_PAGE_ID, action.pageId),
                        )
                    }
                    RemoteTimerAction.Ignore -> Unit
                }
            }
        }
    }

    override fun onNewToken(token: String) {
        val app = application as TimeTrackerApp
        app.appScope.launch { runCatching { app.pushRegistrar.onNewToken(token) } }
    }

    companion object {
        /** Tells a visible timer screen that the active timer was changed from another device. */
        const val ACTION_ACTIVE_TIMER_CHANGED = "to.wim.timetracker.ACTIVE_TIMER_CHANGED"
    }
}

/** Firebase's push token for this phone. Firebase only makes one once asked (auto-init is off in the manifest). */
class FirebasePushTokens : PushTokens {
    override suspend fun token(): String? = runCatching {
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = true
        suspendCancellableCoroutine { cont ->
            messaging.token.addOnCompleteListener { task -> cont.resume(if (task.isSuccessful) task.result else null) }
        }
    }.getOrNull()

    override suspend fun delete() {
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = false
        suspendCancellableCoroutine { cont -> messaging.deleteToken().addOnCompleteListener { cont.resume(Unit) } }
    }
}

/** The push token the server has, in a device-only file ([DataFiles.PUSH], never backed up). */
class PrefsRegisteredPushToken(context: Context) : RegisteredPushToken {
    private val prefs = context.getSharedPreferences(DataFiles.PUSH, Context.MODE_PRIVATE)

    override fun get(): String? = prefs.getString(KEY, null)

    override fun set(token: String?) {
        prefs.edit().apply { if (token == null) remove(KEY) else putString(KEY, token) }.apply()
    }

    private companion object {
        const val KEY = "registered_token"
    }
}
