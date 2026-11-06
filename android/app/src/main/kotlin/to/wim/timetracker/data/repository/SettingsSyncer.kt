package to.wim.timetracker.data.repository

import to.wim.timetracker.data.api.SyncApi
import to.wim.timetracker.data.models.SyncPayload
import to.wim.timetracker.data.models.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Syncs workspaces, presets and display preferences through the sign-in server
 * (worker/src/sync.js). Shared by the Settings screen and TimerViewModel's periodic refresh, so
 * the pull-or-push rule ([SyncDecision], same as Windows) only lives in one place.
 */
class SettingsSyncer(
    private val settingsRepo: SettingsRepository,
    private val notionRepo: NotionRepository,
    private val syncApi: SyncApi,
) {
    private fun themeModeToSyncString(mode: ThemeMode) = when (mode) {
        ThemeMode.LIGHT -> "light"
        ThemeMode.DARK -> "dark"
        ThemeMode.AUTO -> "auto"
    }

    private fun syncStringToThemeMode(value: String) = when (value.lowercase()) {
        "light" -> ThemeMode.LIGHT
        "dark" -> ThemeMode.DARK
        else -> ThemeMode.AUTO
    }

    suspend fun buildPayload(updatedAt: String): SyncPayload = SyncPayload(
        updatedAt = updatedAt,
        workspaces = settingsRepo.getWorkspaces(),
        theme = themeModeToSyncString(settingsRepo.themeModeFlow.first()),
        locale = settingsRepo.localeFlow.first(),
        dateFormat = settingsRepo.dateFormatFlow.first(),
        timeFormat = settingsRepo.timeFormatFlow.first(),
        showNotionLink = settingsRepo.showNotionLinkFlow.first(),
        showArchiveAction = settingsRepo.showArchiveActionFlow.first()
    )

    private suspend fun applyPayload(payload: SyncPayload) {
        settingsRepo.saveWorkspaces(payload.workspaces)
        payload.theme?.let { settingsRepo.saveThemeMode(syncStringToThemeMode(it)) }
        payload.locale?.let { settingsRepo.saveLocale(it) }
        payload.dateFormat?.let { settingsRepo.saveDateFormat(it) }
        payload.timeFormat?.let { settingsRepo.saveTimeFormat(it) }
        payload.showNotionLink?.let { settingsRepo.saveShowNotionLink(it) }
        payload.showArchiveAction?.let { settingsRepo.saveShowArchiveAction(it) }
        settingsRepo.saveSettingsUpdatedAt(payload.updatedAt)
        settingsRepo.saveLastSyncedUpdatedAt(payload.updatedAt)
        // A pulled payload can point workspaces at other databases; anything cached
        // describes the previous configuration.
        notionRepo.invalidateMetadataCache()
    }

    /** Pull or push, whichever side is newer. Does nothing while sync is off on this device. */
    suspend fun syncNow() = syncMutex.withLock { syncLocked() }

    /** Deletes the copy on the server and turns sync off on this device. */
    suspend fun deleteFromServer() = syncMutex.withLock {
        syncApi.delete()
        settingsRepo.saveSyncEnabled(false)
        settingsRepo.clearLastSyncedUpdatedAt()
    }

    // The Settings screen and the timer screen's refresh both sync, and could
    // do so at the same moment; one pass at a time keeps a pull and a push
    // from interleaving.
    private val syncMutex = Mutex()

    private suspend fun syncLocked() {
        settingsRepo.removeOldSyncPage()
        if (!settingsRepo.isSyncEnabled()) return
        val remote = syncApi.get()
        val localUpdatedAt = settingsRepo.getSettingsUpdatedAt()
        val decision = SyncDecision.of(
            settingsUpdatedAt = localUpdatedAt,
            lastSyncedUpdatedAt = settingsRepo.getLastSyncedUpdatedAt(),
            hasSettings = settingsRepo.getWorkspaces().isNotEmpty(),
            remoteUpdatedAt = remote?.updatedAt,
        )
        when (decision) {
            SyncDecision.Pull -> applyPayload(remote!!)
            SyncDecision.Push -> {
                // Settings from before sync existed have no timestamp yet.
                val updatedAt = localUpdatedAt ?: settingsRepo.syncTimestamp()
                val result = syncApi.put(buildPayload(updatedAt))
                if (result.accepted) {
                    settingsRepo.saveSettingsUpdatedAt(updatedAt)
                    settingsRepo.saveLastSyncedUpdatedAt(updatedAt)
                } else {
                    // Another device was faster with newer settings.
                    result.payload?.let { applyPayload(it) }
                }
            }
            SyncDecision.None -> Unit
        }
    }
}
