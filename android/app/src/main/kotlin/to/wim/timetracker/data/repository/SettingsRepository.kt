package to.wim.timetracker.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import to.wim.timetracker.data.models.ActiveTimer
import to.wim.timetracker.data.models.ThemeMode
import to.wim.timetracker.data.models.Workspace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = DataFiles.SETTINGS)

class SettingsRepository(private val context: Context) : TokenStore {

    // Applies @Serializable defaults for missing
    // fields and ignores unknown keys, so workspaces saved by older versions
    // (e.g. before `color`/`presets` existed) deserialize without crashing.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val WORKSPACES_KEY = stringPreferencesKey("workspaces_json")
    private val LAST_WORKSPACE_KEY = stringPreferencesKey("last_workspace_id")
    private val ACTIVE_TIMER_KEY = stringPreferencesKey("active_timer_json")
    private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
    private val DYNAMIC_COLOR_KEY = booleanPreferencesKey("dynamic_color")
    private val LOCALE_KEY = stringPreferencesKey("locale")
    private val DATE_FORMAT_KEY = stringPreferencesKey("date_format")
    private val TIME_FORMAT_KEY = stringPreferencesKey("time_format")
    private val SHOW_NOTION_LINK_KEY = booleanPreferencesKey("show_notion_link")
    private val SHOW_ARCHIVE_ACTION_KEY = booleanPreferencesKey("show_archive_action")
    // From when settings synced through a Notion page; only read to remove them.
    private val OLD_SYNC_PAGE_ID_KEY = stringPreferencesKey("sync_page_id")
    private val OLD_SYNC_PAGE_TITLE_KEY = stringPreferencesKey("sync_page_title")
    private val SYNC_ENABLED_KEY = booleanPreferencesKey("sync_enabled")
    private val SETTINGS_UPDATED_AT_KEY = stringPreferencesKey("settings_updated_at")
    private val LAST_SYNCED_UPDATED_AT_KEY = stringPreferencesKey("last_synced_updated_at")

    private val encryptedPrefs: SharedPreferences by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                DataFiles.API_KEY,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse { e ->
            android.util.Log.e("SettingsRepository", "EncryptedSharedPreferences creation failed, falling back", e)
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    context.deleteSharedPreferences(DataFiles.API_KEY)
                } else {
                    context.getSharedPreferences(DataFiles.API_KEY, Context.MODE_PRIVATE).edit().clear().apply()
                }
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    DataFiles.API_KEY,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }.getOrElse { e2 ->
                android.util.Log.e("SettingsRepository", "Recreation of EncryptedSharedPreferences failed, using plain fallback", e2)
                context.getSharedPreferences(DataFiles.API_KEY_FALLBACK, Context.MODE_PRIVATE)
            }
        }
    }

    // ---- Notion sign-in (TokenStore for NotionSession) ----
    // In the encrypted, never backed up file. "api_key" is from versions before "Sign in with
    // Notion": it is deleted, the user signs in once instead.

    override fun loadSession(): StoredSession? {
        val access = encryptedPrefs.getString(ACCESS_TOKEN, null)
        if (access.isNullOrBlank()) return null
        return StoredSession(
            accessToken = access,
            refreshToken = encryptedPrefs.getString(REFRESH_TOKEN, null),
            workspaceName = encryptedPrefs.getString(WORKSPACE_NAME, null).orEmpty(),
            workspaceId = encryptedPrefs.getString(WORKSPACE_ID, null),
        )
    }

    override fun saveSession(session: StoredSession?) {
        encryptedPrefs.edit().apply {
            remove(LEGACY_API_KEY)
            if (session == null) {
                remove(ACCESS_TOKEN)
                remove(REFRESH_TOKEN)
                remove(WORKSPACE_NAME)
                remove(WORKSPACE_ID)
            } else {
                putString(ACCESS_TOKEN, session.accessToken)
                putString(REFRESH_TOKEN, session.refreshToken)
                putString(WORKSPACE_NAME, session.workspaceName)
                putString(WORKSPACE_ID, session.workspaceId)
            }
        }.apply()
    }

    override fun loadPendingVerifier(): String? = encryptedPrefs.getString(PENDING_VERIFIER, null)

    override fun savePendingVerifier(verifier: String?) {
        encryptedPrefs.edit().putString(PENDING_VERIFIER, verifier).apply()
    }

    /** Deletes an API key saved by a version before "Sign in with Notion". */
    fun removeLegacyApiKey() {
        if (encryptedPrefs.contains(LEGACY_API_KEY)) encryptedPrefs.edit().remove(LEGACY_API_KEY).apply()
    }

    val workspacesFlow: Flow<List<Workspace>> = context.dataStore.data.map { prefs ->
        val stored = prefs[WORKSPACES_KEY] ?: return@map emptyList()
        runCatching {
            json.decodeFromString<List<Workspace>>(stored).map { it.withPresetTitles() }
        }.getOrDefault(emptyList())
    }

    suspend fun getWorkspaces(): List<Workspace> = workspacesFlow.first()

    suspend fun saveWorkspaces(workspaces: List<Workspace>) {
        context.dataStore.edit { prefs ->
            prefs[WORKSPACES_KEY] = json.encodeToString(workspaces)
        }
    }

    /** Applies [transform] to the stored workspaces in one atomic edit and
     *  returns the result. Edits made from a screen's own copy of the list
     *  wrote that whole copy back, undoing anything the sync (or another
     *  screen) had stored since that copy was taken. */
    suspend fun updateWorkspaces(transform: (List<Workspace>) -> List<Workspace>): List<Workspace> {
        var updated: List<Workspace> = emptyList()
        context.dataStore.edit { prefs ->
            val current = prefs[WORKSPACES_KEY]
                ?.let { runCatching { json.decodeFromString<List<Workspace>>(it) }.getOrNull() }
                ?.map { it.withPresetTitles() }
                ?: emptyList()
            updated = transform(current)
            prefs[WORKSPACES_KEY] = json.encodeToString(updated)
        }
        return updated
    }

    val lastWorkspaceIdFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[LAST_WORKSPACE_KEY]
    }

    suspend fun saveLastWorkspaceId(id: String) {
        context.dataStore.edit { prefs ->
            prefs[LAST_WORKSPACE_KEY] = id
        }
    }

    val activeTimerFlow: Flow<ActiveTimer?> = context.dataStore.data.map { prefs ->
        val stored = prefs[ACTIVE_TIMER_KEY]?.takeIf { it.isNotBlank() } ?: return@map null
        runCatching { json.decodeFromString<ActiveTimer>(stored) }.getOrNull()
    }

    suspend fun getActiveTimer(): ActiveTimer? = activeTimerFlow.first()

    suspend fun saveActiveTimer(timer: ActiveTimer) {
        context.dataStore.edit { prefs ->
            prefs[ACTIVE_TIMER_KEY] = json.encodeToString(timer)
        }
    }

    suspend fun clearActiveTimer() {
        context.dataStore.edit { prefs ->
            prefs[ACTIVE_TIMER_KEY] = ""
        }
    }

    val themeModeFlow: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        when (prefs[THEME_MODE_KEY]) {
            "LIGHT" -> ThemeMode.LIGHT
            "DARK" -> ThemeMode.DARK
            else -> ThemeMode.AUTO
        }
    }

    val dynamicColorFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[DYNAMIC_COLOR_KEY] ?: false
    }

    suspend fun saveDynamicColor(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[DYNAMIC_COLOR_KEY] = enabled }
    }

    suspend fun saveThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs -> prefs[THEME_MODE_KEY] = mode.name }
    }

    val localeFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[LOCALE_KEY] ?: "system"
    }

    suspend fun saveLocale(locale: String) {
        context.dataStore.edit { prefs -> prefs[LOCALE_KEY] = locale }
    }

    val dateFormatFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[DATE_FORMAT_KEY] ?: "DD/MM/YYYY"
    }

    suspend fun saveDateFormat(format: String) {
        context.dataStore.edit { prefs -> prefs[DATE_FORMAT_KEY] = format }
    }

    val timeFormatFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[TIME_FORMAT_KEY] ?: "24h"
    }

    suspend fun saveTimeFormat(format: String) {
        context.dataStore.edit { prefs -> prefs[TIME_FORMAT_KEY] = format }
    }

    // Global toggles for the per-entry Notion actions on active/recent timers.
    val showNotionLinkFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SHOW_NOTION_LINK_KEY] ?: true
    }

    suspend fun saveShowNotionLink(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[SHOW_NOTION_LINK_KEY] = enabled }
    }

    val showArchiveActionFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SHOW_ARCHIVE_ACTION_KEY] ?: true
    }

    suspend fun saveShowArchiveAction(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[SHOW_ARCHIVE_ACTION_KEY] = enabled }
    }

    // ---- Settings sync (sign-in server) ----

    /** Whether this device syncs its settings. On unless turned off in Settings. */
    val syncEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs -> prefs[SYNC_ENABLED_KEY] ?: true }
    suspend fun isSyncEnabled(): Boolean = syncEnabledFlow.first()

    suspend fun saveSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[SYNC_ENABLED_KEY] = enabled }
    }

    val settingsUpdatedAtFlow: Flow<String?> = context.dataStore.data.map { prefs -> prefs[SETTINGS_UPDATED_AT_KEY] }
    suspend fun getSettingsUpdatedAt(): String? = settingsUpdatedAtFlow.first()

    val lastSyncedUpdatedAtFlow: Flow<String?> = context.dataStore.data.map { prefs -> prefs[LAST_SYNCED_UPDATED_AT_KEY] }
    suspend fun getLastSyncedUpdatedAt(): String? = lastSyncedUpdatedAtFlow.first()

    suspend fun clearLastSyncedUpdatedAt() {
        context.dataStore.edit { prefs -> prefs.remove(LAST_SYNCED_UPDATED_AT_KEY) }
    }

    /**
     * Settings used to sync through a Notion page; they now sync through the sign-in server. The
     * page is no longer read, and "last synced" referred to it, so both are forgotten.
     */
    suspend fun removeOldSyncPage() {
        context.dataStore.edit { prefs ->
            if (prefs.contains(OLD_SYNC_PAGE_ID_KEY)) {
                prefs.remove(OLD_SYNC_PAGE_ID_KEY)
                prefs.remove(OLD_SYNC_PAGE_TITLE_KEY)
                prefs.remove(LAST_SYNCED_UPDATED_AT_KEY)
            }
        }
    }

    /** Bumped whenever a synced field changes locally, so sync knows this
     * device has edits that need pushing. */
    suspend fun touchSettingsUpdatedAt(): String {
        val now = syncTimestamp()
        context.dataStore.edit { prefs -> prefs[SETTINGS_UPDATED_AT_KEY] = now }
        return now
    }

    /**
     * "Now" in the exact shape the desktop app writes (`toISOString()`: UTC,
     * always three fraction digits). Both apps decide which side is newer by
     * comparing these strings, and `Instant.toString()` varies its precision,
     * it drops the fraction entirely on a whole second, and "…:05Z" sorts
     * after "…:05.300Z" although it is earlier.
     */
    fun syncTimestamp(): String = SYNC_TIMESTAMP_FORMAT.format(java.time.Instant.now())

    suspend fun saveSettingsUpdatedAt(value: String) {
        context.dataStore.edit { prefs -> prefs[SETTINGS_UPDATED_AT_KEY] = value }
    }

    suspend fun saveLastSyncedUpdatedAt(value: String) {
        context.dataStore.edit { prefs -> prefs[LAST_SYNCED_UPDATED_AT_KEY] = value }
    }

    private companion object {
        val SYNC_TIMESTAMP_FORMAT: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(java.time.ZoneOffset.UTC)

        const val LEGACY_API_KEY = "api_key"
        const val ACCESS_TOKEN = "notion_access_token"
        const val REFRESH_TOKEN = "notion_refresh_token"
        const val WORKSPACE_NAME = "notion_workspace_name"
        const val WORKSPACE_ID = "notion_workspace_id"
        const val PENDING_VERIFIER = "notion_pending_verifier"
    }
}
