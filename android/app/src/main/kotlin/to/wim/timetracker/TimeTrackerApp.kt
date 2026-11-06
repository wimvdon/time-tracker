package to.wim.timetracker

import android.app.Application
import to.wim.timetracker.data.api.AuthClient
import to.wim.timetracker.data.api.DeviceClient
import to.wim.timetracker.data.api.SyncClient
import to.wim.timetracker.data.repository.NotionRepository
import to.wim.timetracker.data.repository.NotionSession
import to.wim.timetracker.data.repository.PushRegistrar
import to.wim.timetracker.data.repository.SessionEvent
import to.wim.timetracker.data.repository.SettingsRepository
import to.wim.timetracker.data.repository.SettingsSyncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import to.wim.timetracker.service.FirebasePushTokens
import to.wim.timetracker.service.PrefsRegisteredPushToken
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

class TimeTrackerApp : Application() {
    lateinit var settingsRepository: SettingsRepository
    lateinit var notionSession: NotionSession
    lateinit var notionRepository: NotionRepository
    lateinit var settingsSyncer: SettingsSyncer
    lateinit var pushRegistrar: PushRegistrar

    /** For work that must finish even when the screen that started it is gone (finishing a sign-in). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        settingsRepository.removeLegacyApiKey()
        notionSession = NotionSession(settingsRepository, AuthClient())
        notionRepository = NotionRepository(notionSession)
        settingsSyncer = SettingsSyncer(settingsRepository, notionRepository, SyncClient(notionSession))
        pushRegistrar = PushRegistrar(
            wanted = { notionSession.isSignedIn() && settingsRepository.isSyncEnabled() },
            notionToken = { notionSession.token() },
            tokens = FirebasePushTokens(),
            api = DeviceClient(notionSession),
            registered = PrefsRegisteredPushToken(this),
        )
        // Timers started on Windows show here: register this phone now and after each sign-in.
        updatePushRegistration()
        appScope.launch {
            notionSession.events.collect { if (it is SessionEvent.SignedIn) updatePushRegistration() }
        }
    }

    /** Registers this phone for timer notifications, or removes it, as the settings say. */
    fun updatePushRegistration() {
        appScope.launch { runCatching { pushRegistrar.update() } }
    }
}
