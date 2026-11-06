package to.wim.timetracker

import android.app.Application
import to.wim.timetracker.data.api.AuthClient
import to.wim.timetracker.data.api.SyncClient
import to.wim.timetracker.data.repository.NotionRepository
import to.wim.timetracker.data.repository.NotionSession
import to.wim.timetracker.data.repository.SettingsRepository
import to.wim.timetracker.data.repository.SettingsSyncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

class TimeTrackerApp : Application() {
    lateinit var settingsRepository: SettingsRepository
    lateinit var notionSession: NotionSession
    lateinit var notionRepository: NotionRepository
    lateinit var settingsSyncer: SettingsSyncer

    /** For work that must finish even when the screen that started it is gone (finishing a sign-in). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        settingsRepository.removeLegacyApiKey()
        notionSession = NotionSession(settingsRepository, AuthClient())
        notionRepository = NotionRepository(notionSession)
        settingsSyncer = SettingsSyncer(settingsRepository, notionRepository, SyncClient(notionSession))
    }
}
