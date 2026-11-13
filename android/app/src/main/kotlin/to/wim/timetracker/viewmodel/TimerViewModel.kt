package to.wim.timetracker.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import to.wim.timetracker.TimeTrackerApp
import to.wim.timetracker.data.api.NotSignedInException
import to.wim.timetracker.data.api.arrayOrNull
import to.wim.timetracker.data.api.asObjectOrNull
import to.wim.timetracker.data.api.boolOrNull
import to.wim.timetracker.data.api.objOrNull
import to.wim.timetracker.data.api.stringOrNull
import to.wim.timetracker.data.models.*
import to.wim.timetracker.ShortcutHelper
import to.wim.timetracker.data.models.Preset
import to.wim.timetracker.service.TimerForegroundService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Every date format offered in Settings. */
private val DATE_FORMATS = listOf("DD/MM/YYYY", "MM/DD/YYYY", "YYYY-MM-DD")

/** How long a locally-stopped timer stays hidden from the running-timer list
 *  while Notion's query catches up. */
private const val RECENTLY_STOPPED_TTL_MS = 60_000L

/**
 * Keys for the messages the timer screen can show. The view model cannot
 * translate them itself: its Application context follows the *system*
 * language, not the one chosen in Settings, so it emits a key and the
 * composables resolve it against their (locale-wrapped) resources. A key may
 * carry Notion's own message after a `|`, which is appended as detail.
 */
object UiError {
    const val NOT_SIGNED_IN = "not_signed_in"
    const val START_FAILED = "start_failed"
    const val STOP_FAILED = "stop_failed"
    const val UPDATE_FAILED = "update_failed"
    const val DELETE_FAILED = "delete_failed"
    const val RUNNING_TIMERS_FAILED = "running_timers_failed"
}

private fun uiError(key: String, detail: String?): String =
    if (detail.isNullOrBlank()) key else "$key|$detail"

/** "Not signed in" (the sign-in expired or was removed) is its own message, whatever was being done. */
private fun uiError(key: String, cause: Throwable): String =
    if (cause is NotSignedInException) UiError.NOT_SIGNED_IN else uiError(key, cause.message)

class TimerViewModel(private val app: Application) : AndroidViewModel(app) {

    data class UiState(
        /** False until the stored settings have been read, so the setup screen doesn't flash at start. */
        val settingsLoaded: Boolean = false,
        val signedIn: Boolean = false,
        val workspaces: List<Workspace> = emptyList(),
        val selectedWorkspaceId: String? = null,
        val projects: List<NamedOption> = emptyList(),
        val selectedProjectId: String? = null,
        val description: String = "",
        val extraFieldDefs: List<ExtraFieldDef> = emptyList(),
        val extraValues: Map<String, String> = emptyMap(),
        val running: Boolean = false,
        val activeTimer: ActiveTimer? = null,
        val elapsedSeconds: Long = 0L,
        val error: String? = null,
        val stopError: String? = null,
        val isStarting: Boolean = false,
        val isStopping: Boolean = false,
        val extrasLoading: Boolean = false,
        val extrasError: String? = null,
        val runningTimers: List<RunningTimerInfo> = emptyList(),
        val runningTimersLoading: Boolean = false,
        val runningTimersError: String? = null,
        val recentEntries: List<RecentEntryInfo> = emptyList(),
        val recentEntriesLoading: Boolean = false,
        val deleteConfirmInfo: DeleteConfirmInfo? = null,
        val editTimerOpen: Boolean = false,
        val deletedPageDialogVisible: Boolean = false,
        val deletedPageDialogTitle: String? = null,
        val infoToast: String? = null,
        val showAlreadyRunningWarning: Boolean = false,
        val locale: String = "system",
        val dateFormat: String = "DD/MM/YYYY",
        val timeFormat: String = "24h",
        val showNotionLink: Boolean = true,
        val showArchiveAction: Boolean = true,
        val presetEditor: PresetEditor? = null
    )

    /** A preset being created ([presetId] null) or edited in the preset editor. */
    data class PresetEditor(
        val presetId: String?,
        val workspaceId: String,
        val name: String = "",
        val pageTitle: String = "",
        val extraValues: Map<String, String> = emptyMap(),
        val fieldDefs: List<ExtraFieldDef> = emptyList(),
        val fieldsLoading: Boolean = false,
        val fieldsError: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val settingsRepo get() = (app as TimeTrackerApp).settingsRepository
    private val notionRepo get() = (app as TimeTrackerApp).notionRepository
    private val syncer get() = (app as TimeTrackerApp).settingsSyncer
    private val session get() = (app as TimeTrackerApp).notionSession

    private var tickJob: Job? = null
    private var tickStartMs: Long = 0L

    // Loading a workspace's extra fields hits the network several times (schema,
    // related-database entries, users). Switching workspace while one of those is
    // in flight used to let the older, slower load finish last and write another
    // workspace's fields into the UI, so every new load cancels the previous one.
    private var extrasJob: Job? = null

    // A preset launched from a home-screen / launcher shortcut before workspaces
    // finished loading (cold start). Applied once loadInitialState() completes.
    private var pendingPreset: Pair<String, String>? = null

    // pageId -> when it was stopped on this device. Notion's running-timer query
    // can keep returning a just-stopped entry for a moment (replication lag);
    // without this the very next refresh adopted it again as the active timer,
    // putting the notification back and re-starting a timer the user had
    // deliberately stopped.
    private val recentlyStopped = mutableMapOf<String, Long>()

    // pageId -> when it was started on this device. The same lag works the other
    // way: a page created a moment ago can be missing from the running-timer
    // query, which read as "stopped elsewhere" and dropped the timer that was
    // just started.
    private val recentlyStarted = mutableMapOf<String, Long>()

    private fun wasJustStarted(pageId: String): Boolean {
        val now = System.currentTimeMillis()
        recentlyStarted.entries.removeAll { now - it.value >= RECENTLY_STOPPED_TTL_MS }
        return recentlyStarted.containsKey(pageId)
    }

    private fun markStopped(pageId: String) {
        if (pageId.isBlank()) return
        recentlyStopped[pageId] = System.currentTimeMillis()
    }

    /** Drops just-stopped entries, and prunes the guard map as it goes. */
    private fun withoutRecentlyStopped(timers: List<RunningTimerInfo>): List<RunningTimerInfo> {
        val now = System.currentTimeMillis()
        recentlyStopped.entries.removeAll { now - it.value >= RECENTLY_STOPPED_TTL_MS }
        return timers.filterNot { recentlyStopped.containsKey(it.pageId) }
    }

    init {
        viewModelScope.launch { loadInitialState() }
        // Signed in or out in Settings, or the sign-in expired: the timer screen follows.
        viewModelScope.launch {
            session.account.collect { account ->
                _uiState.update { it.copy(signedIn = account != null) }
            }
        }
        // Auto-refresh every 60s
        viewModelScope.launch {
            while (true) {
                delay(60_000L)
                val state = _uiState.value
                if (state.signedIn && state.workspaces.isNotEmpty()) {
                    if (state.running) {
                        checkActiveTimerStillRunning()
                        if (_uiState.value.running) {
                            refreshActiveTimerFieldsIfRunning()
                            loadRunningTimers()
                            loadRecentEntries()
                        }
                    } else {
                        loadRunningTimers()
                        loadRecentEntries()
                    }
                }
                if (state.signedIn && settingsRepo.isSyncEnabled()) {
                    runCatching { syncer.syncNow() }.onSuccess { refreshSettings() }
                }
            }
        }
    }

    private suspend fun loadInitialState() {
        try {
            val signedIn = session.isSignedIn()
            val workspaces = settingsRepo.getWorkspaces()
            val lastId = settingsRepo.lastWorkspaceIdFlow.first()
            val activeTimer = settingsRepo.getActiveTimer()
            val locale = settingsRepo.localeFlow.first()
            val dateFormat = settingsRepo.dateFormatFlow.first()
            val timeFormat = settingsRepo.timeFormatFlow.first()
            val showNotionLink = settingsRepo.showNotionLinkFlow.first()
            val showArchiveAction = settingsRepo.showArchiveActionFlow.first()

            _uiState.update { state ->
                state.copy(
                    settingsLoaded = true,
                    signedIn = signedIn,
                    workspaces = workspaces,
                    locale = locale,
                    dateFormat = dateFormat,
                    timeFormat = timeFormat,
                    showNotionLink = showNotionLink,
                    showArchiveAction = showArchiveAction
                )
            }

            ShortcutHelper.updateShortcuts(app, workspaces)

            if (activeTimer != null) {
                val workspace = workspaces.firstOrNull { it.id == activeTimer.workspaceId }
                if (workspace != null) {
                    _uiState.update { state ->
                        state.copy(
                            running = true,
                            activeTimer = activeTimer,
                            selectedWorkspaceId = activeTimer.workspaceId,
                            selectedProjectId = activeTimer.projectId,
                            description = activeTimer.description,
                            extraValues = activeTimer.extraValues
                        )
                    }
                    loadExtraFieldDefsAndWait(workspace)
                    val rawStartMs = runCatching { parseIso(activeTimer.rawStart) }.getOrElse { System.currentTimeMillis() }
                    tickStartMs = rawStartMs
                    startTicking(rawStartMs)
                    runCatching {
                        app.startForegroundService(
                            TimerForegroundService.buildStartIntent(app, activeTimer, workspace.name, workspace.roundingMinutes, workspace.color)
                        )
                    }.onFailure { e ->
                        android.util.Log.e("TimerViewModel", "Failed to start foreground service on launch", e)
                    }
                }
                if (signedIn && workspaces.isNotEmpty()) {
                    viewModelScope.launch { checkActiveTimerStillRunning() }
                    loadRunningTimers()
                    loadRecentEntries()
                }
            } else {
                val firstId = (workspaces.firstOrNull { it.id == lastId } ?: workspaces.firstOrNull())?.id
                if (firstId != null) selectWorkspace(firstId)
                if (signedIn && workspaces.isNotEmpty()) { loadRunningTimers(); loadRecentEntries() }
            }

            val pending = pendingPreset
            if (pending != null) {
                pendingPreset = null
                applyPresetById(pending.first, pending.second)
            }

            if (signedIn && settingsRepo.isSyncEnabled()) {
                runCatching { syncer.syncNow() }.onSuccess { refreshSettings() }
            }
        } catch (e: Exception) {
            android.util.Log.e("TimerViewModel", "Failed to load initial state", e)
            _uiState.update { it.copy(settingsLoaded = true) }
        }
    }

    fun refreshSettings() {
        viewModelScope.launch {
            val previousWorkspace = _uiState.value.workspaces.firstOrNull { it.id == _uiState.value.selectedWorkspaceId }
            val signedIn = session.isSignedIn()
            val workspaces = settingsRepo.getWorkspaces()
            val locale = settingsRepo.localeFlow.first()
            val dateFormat = settingsRepo.dateFormatFlow.first()
            val timeFormat = settingsRepo.timeFormatFlow.first()
            val showNotionLink = settingsRepo.showNotionLinkFlow.first()
            val showArchiveAction = settingsRepo.showArchiveActionFlow.first()
            _uiState.update { it.copy(
                settingsLoaded = true,
                signedIn = signedIn,
                workspaces = workspaces,
                locale = locale,
                dateFormat = dateFormat,
                timeFormat = timeFormat,
                showNotionLink = showNotionLink,
                showArchiveAction = showArchiveAction
            ) }
            ShortcutHelper.updateShortcuts(app, workspaces)
            val current = _uiState.value.selectedWorkspaceId
            val currentWorkspace = workspaces.firstOrNull { it.id == current }
            if (currentWorkspace == null) {
                val firstId = workspaces.firstOrNull()?.id
                if (firstId != null) selectWorkspace(firstId)
            } else if (previousWorkspace?.extraProps != currentWorkspace.extraProps ||
                previousWorkspace.timeEntriesDbId != currentWorkspace.timeEntriesDbId
            ) {
                // The extra-field configuration changed (edited here or pulled in from
                // another device by the sync); the form on screen is describing the
                // old configuration, so rebuild it.
                extrasJob?.cancel()
                extrasJob = viewModelScope.launch { loadExtraFieldDefs(currentWorkspace) }
            }
            loadRunningTimers()
            loadRecentEntries()
        }
    }

    fun selectWorkspace(id: String) {
        val workspace = _uiState.value.workspaces.firstOrNull { it.id == id } ?: return
        val active = _uiState.value.activeTimer
        val currentDesc = if (active != null && active.workspaceId == id) active.description else ""
        val currentProj = if (active != null && active.workspaceId == id) active.projectId else null
        val currentExtras = if (active != null && active.workspaceId == id) active.extraValues else emptyMap()

        _uiState.update {
            it.copy(
                selectedWorkspaceId = id,
                description = currentDesc,
                selectedProjectId = currentProj,
                extraFieldDefs = emptyList(),
                extraValues = currentExtras,
                extrasLoading = workspace.extraProps.isNotEmpty(),
                extrasError = null
            )
        }
        extrasJob?.cancel()
        extrasJob = viewModelScope.launch {
            settingsRepo.saveLastWorkspaceId(id)
            loadExtraFieldDefs(workspace)
        }
    }

    /** True while [workspace] is still the one the UI is showing. Every async
     *  field load checks this before writing, so a load that was overtaken by a
     *  workspace switch can never put its fields or values on screen. */
    private fun isStillSelected(workspace: Workspace) =
        _uiState.value.selectedWorkspaceId == workspace.id

    /** Loads [workspace]'s extra fields as the current extras load (superseding any
     *  in flight) and waits for it, so callers that need the definitions before they
     *  can act (presets, shortcuts, "play" on a recent entry) get them. */
    private suspend fun loadExtraFieldDefsAndWait(workspace: Workspace) {
        extrasJob?.cancel()
        val job = viewModelScope.launch { loadExtraFieldDefs(workspace) }
        extrasJob = job
        job.join()
    }

    /**
     * Builds the input definitions for [workspace]'s extra properties: the live
     * Notion type of each configured property plus the options it can be set to.
     *
     * Two rules keep the result honest. Nothing is written unless [workspace] is
     * still the selected one, so a load overtaken by a workspace switch cannot
     * show another workspace's fields. And a failure never silently empties the
     * form: without a schema there is no way to tell a deleted property from an
     * unreachable API, so the current fields stay on screen and the failure is
     * reported instead.
     */
    private suspend fun loadExtraFieldDefs(workspace: Workspace) {
        if (workspace.extraProps.isEmpty() || workspace.timeEntriesDbId.isBlank()) {
            if (!isStillSelected(workspace)) return
            _uiState.update { it.copy(extraFieldDefs = emptyList(), extrasLoading = false, extrasError = null) }
            initExtraValues(workspace.extraProps, emptyList())
            return
        }

        val result = buildExtraFieldDefs(workspace)
        if (result == null) {
            if (!isStillSelected(workspace)) return
            _uiState.update { it.copy(extrasLoading = false, extrasError = "extras_load_failed") }
            return
        }

        if (!isStillSelected(workspace)) return
        _uiState.update { it.copy(
            extraFieldDefs = result.defs,
            extrasLoading = false,
            extrasError = result.error
        ) }
        initExtraValues(workspace.extraProps, result.defs)
    }

    /** [workspace]'s extra field definitions, and the problem to report with them (options that
     *  could not be loaded, configured properties missing from the database), if any. */
    private class ExtraDefsResult(val defs: List<ExtraFieldDef>, val error: String?)

    /** Builds [workspace]'s extra field definitions from Notion; null when its schema can't be
     *  read. Shared by the timer form and the preset editor. */
    private suspend fun buildExtraFieldDefs(workspace: Workspace): ExtraDefsResult? {
        if (workspace.extraProps.isEmpty() || workspace.timeEntriesDbId.isBlank()) return ExtraDefsResult(emptyList(), null)
        val schema = retryNotionCall { notionRepo.getDatabaseSchema(workspace.timeEntriesDbId) } ?: return null

        var optionsIncomplete = false
        val defs = workspace.extraProps.mapNotNull { prop ->
            val schemaProp = schema[prop.propName] ?: return@mapNotNull null
            val type = schemaProp.type
            // A property whose type the app cannot write (a formula, a rollup, …)
            // would render as a text box whose value is dropped on save, so it is
            // left out and reported together with the missing ones.
            if (type !in WRITABLE_EXTRA_TYPES) return@mapNotNull null

            val options: List<NamedOption> = when (type) {
                // getDatabaseSchema() only populates selectOptions for select/multi_select
                // (statusOptions is status-only); reading statusOptions here left every
                // select/multi_select field with no options at all, so nothing (default,
                // preset, or synced) could ever show as selected.
                "select", "multi_select" -> schemaProp.selectOptions.map { NamedOption(it.name, it.name) }
                "status" -> schemaProp.statusOptions.map { NamedOption(it.name, it.name) }
                // An empty option list hides the stored value (the dropdown can only
                // show a value it has an option for), so these are retried as well and
                // a failure is reported rather than passed off as "no options".
                "people" -> retryNotionCall { notionRepo.listUsers() }
                    ?: emptyList<NamedOption>().also { optionsIncomplete = true }
                "relation" -> {
                    val relDbId = schemaProp.relationDatabaseId ?: ""
                    if (relDbId.isNotBlank()) {
                        retryNotionCall { notionRepo.fetchDatabasePages(relDbId) }
                            ?: emptyList<NamedOption>().also { optionsIncomplete = true }
                    } else emptyList()
                }
                else -> emptyList()
            }
            ExtraFieldDef(
                propName = prop.propName,
                type = type,
                required = prop.required,
                defaultValue = prop.defaultValue,
                options = options,
                statusOptions = schemaProp.statusOptions
            )
        }

        // A configured property that isn't in the schema (renamed or deleted in
        // Notion, or a relation to a database not shared with the app, which Notion
        // leaves out) is dropped from the form; say so instead of just showing
        // fewer fields than the workspace is configured with. Same rule as Windows
        // (src/lib/schema.js, countMissingExtraProps).
        val missing = workspace.extraProps.count { prop ->
            prop.propName.isNotBlank() && (schema[prop.propName]?.type ?: "") !in WRITABLE_EXTRA_TYPES
        }

        return ExtraDefsResult(
            defs,
            when {
                optionsIncomplete -> "extras_load_failed"
                missing > 0 -> "extras_missing_props"
                else -> null
            }
        )
    }

    private fun initExtraValues(extraProps: List<ExtraProp>, defs: List<ExtraFieldDef>) {
        val active = _uiState.value.activeTimer
        if (active != null) {
            _uiState.update { it.copy(extraValues = active.extraValues) }
            return
        }
        val current = _uiState.value.extraValues
        val values = extraProps.associate { prop ->
            // Loading the definitions takes a network round trip; anything already
            // filled in (or applied from a preset) while it ran is kept rather than
            // reset to the configured default.
            val existing = current[prop.propName]?.takeIf { it.isNotBlank() }
            val def = defs.firstOrNull { it.propName == prop.propName }
            val value = when {
                existing != null -> existing
                prop.defaultValue != null -> prop.defaultValue
                def?.type == "checkbox" -> "false"
                else -> ""
            }
            prop.propName to value
        }
        _uiState.update { it.copy(extraValues = values) }
    }

    fun setSelectedProject(id: String?) {
        _uiState.update { it.copy(selectedProjectId = id) }
    }

    fun setDescription(text: String) {
        _uiState.update { it.copy(description = text) }
    }

    fun setExtraValue(propName: String, value: String) {
        _uiState.update { it.copy(extraValues = it.extraValues + (propName to value)) }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
    fun clearInfoToast() = _uiState.update { it.copy(infoToast = null) }

    fun openEditTimer() = _uiState.update { it.copy(editTimerOpen = true) }
    fun closeEditTimer() = _uiState.update { it.copy(editTimerOpen = false) }

    fun dismissDeletedPage() {
        val state = _uiState.value
        if (state.running) {
            tickJob?.cancel()
            stopService()
            viewModelScope.launch { settingsRepo.clearActiveTimer() }
            _uiState.update { it.copy(running = false, activeTimer = null, elapsedSeconds = 0L, deletedPageDialogVisible = false, deletedPageDialogTitle = null) }
        } else {
            _uiState.update { it.copy(deletedPageDialogVisible = false, deletedPageDialogTitle = null) }
        }
        loadRunningTimers()
        loadRecentEntries()
    }

    fun refreshAll() {
        val state = _uiState.value
        if (!state.signedIn || state.workspaces.isEmpty()) return
        if (state.running && state.activeTimer != null) {
            viewModelScope.launch {
                checkActiveTimerStillRunning()
                if (_uiState.value.running) refreshActiveTimerFieldsIfRunning()
            }
        }
        viewModelScope.launch {
            if (settingsRepo.isSyncEnabled()) {
                runCatching { syncer.syncNow() }.onSuccess { refreshSettings() }
            }
        }
        loadRunningTimers()
        loadRecentEntries()
    }

    private suspend fun checkActiveTimerStillRunning() {
        val timer = _uiState.value.activeTimer ?: return
        val workspace = _uiState.value.workspaces.firstOrNull { it.id == timer.workspaceId } ?: return
        val pm = workspace.propertyMap
        if (workspace.timeEntriesDbId.isBlank() || pm.timeEntryStatusProp.isBlank() || pm.timeEntryStatusRunning.isBlank()) return
        val result = runCatching {
            notionRepo.fetchRunningTimers(workspace.timeEntriesDbId, pm.timeEntryStatusProp, pm.timeEntryStatusRunning, pm.timeEntryDateProp)
        }
        if (result.isFailure) {
            // Keep timer running on network/temporary failure
            return
        }
        val running = result.getOrThrow()
        if (running.none { it.pageId == timer.notionPageId } && !wasJustStarted(timer.notionPageId)) {
            tickJob?.cancel()
            stopService()
            settingsRepo.clearActiveTimer()
            _uiState.update { it.copy(running = false, activeTimer = null, elapsedSeconds = 0L, infoToast = "timer_stopped_elsewhere") }
            
            // Same replication-lag guard as the list refresh: never promote an
            // entry this device has just stopped.
            val next = running.firstOrNull { it.pageId !in recentlyStopped }
            if (next != null) {
                val nextInfo = RunningTimerInfo(
                    pageId = next.pageId,
                    title = next.title,
                    startISO = next.startISO,
                    url = next.url,
                    workspaceId = workspace.id,
                    workspaceName = workspace.name,
                    propertyMap = pm
                )
                loadRunningTimer(nextInfo)
            }
        }
    }

    fun quickSaveDescription(description: String) {
        val timer = _uiState.value.activeTimer ?: return
        updateTimerDetails(timer.projectId, description, timer.extraValues)
    }

    fun updateTimerDetails(projectId: String?, description: String, extraValues: Map<String, String>) {
        val state = _uiState.value
        val timer = state.activeTimer ?: return
        viewModelScope.launch {
            val extraNotionValues =
                buildExtraNotionValues(state.extraFieldDefs, extraValues, clearEmpty = true)
            val properties = mutableMapOf<String, Any?>()
            val projectName = state.projects.firstOrNull { it.id == projectId }?.name ?: ""
            // The description may be cleared; the page itself never gets an empty
            // title (that reads as "Untitled" in Notion), so it falls back to the
            // entry's start date, same as when it was started without one.
            val newDescription = description.trim()
            val startMs = runCatching { parseIso(timer.rawStart) }.getOrDefault(System.currentTimeMillis())
            val newTitle = newDescription.ifBlank { fallbackDescription(startMs, state.dateFormat) }
            properties[timer.propertyMap.timeEntryTitleProp] = mapOf(
                "title" to listOf(mapOf("text" to mapOf("content" to newTitle)))
            )
            // The relation the workspace uses as its "project" link. It is only
            // written when there is an id to write: the timer screen has no project
            // picker, so a null id means "this device does not know the value", not
            // "the user cleared it"; writing an empty relation there wiped the link
            // on the Notion page on every description/extra-field save. When the same
            // property is also configured as an extra field, that field is the single
            // source of truth and this must not write it at all.
            val relationProp = timer.propertyMap.timeEntryRelationProp
            val relationIsExtraField = state.extraFieldDefs.any { it.propName == relationProp }
            if (relationProp.isNotBlank() && !relationIsExtraField && !projectId.isNullOrBlank()) {
                properties[relationProp] = mapOf("relation" to listOf(mapOf("id" to projectId)))
            }
            extraNotionValues.forEach { (k, v) -> properties[k] = v }
            runCatching { notionRepo.updatePageProperties(timer.notionPageId, properties) }
                .onSuccess {
                    val updated = timer.copy(description = newDescription, projectId = projectId, projectName = projectName, extraValues = extraValues)
                    _uiState.update { it.copy(activeTimer = updated, description = newDescription, selectedProjectId = projectId, extraValues = extraValues, editTimerOpen = false) }
                    settingsRepo.saveActiveTimer(updated)
                    // Update foreground service notification immediately
                    val ws = state.workspaces.firstOrNull { it.id == updated.workspaceId }
                    if (ws != null) {
                        runCatching {
                            app.startForegroundService(
                                TimerForegroundService.buildStartIntent(app, updated, ws.name, ws.roundingMinutes, ws.color)
                            )
                        }.onFailure { e ->
                            android.util.Log.e("TimerViewModel", "Failed to start foreground service on update", e)
                        }
                    }
                    // Refresh to ensure consistency
                    loadRunningTimers()
                    loadRecentEntries()
                }
                .onFailure { err ->
                    val msg = err.message ?: ""
                    if (isPageDeletedError(msg)) {
                        _uiState.update { it.copy(editTimerOpen = false, deletedPageDialogVisible = true, deletedPageDialogTitle = description.ifBlank { null }) }
                    } else {
                        _uiState.update { it.copy(error = uiError(UiError.UPDATE_FAILED, msg)) }
                    }
                }
        }
    }

    /**
     * Starts a timer now, or at [startAtMs] when the user picked a start time on the idle screen
     * (for a timer they forgot to start). A picked time later than now is taken as yesterday's.
     */
    fun startTimer(startAtMs: Long? = null) {
        val initial = _uiState.value
        val workspace = initial.workspaces.firstOrNull { it.id == initial.selectedWorkspaceId } ?: return
        if (!initial.signedIn) { _uiState.update { it.copy(error = UiError.NOT_SIGNED_IN) }; return }

        // No validation on start (validated on stop instead)

        _uiState.update { it.copy(isStarting = true, error = null) }

        viewModelScope.launch {
            // The field definitions decide which extra properties get written, so a
            // start fired while they are still loading (preset, launcher shortcut, or
            // a fast tap right after switching workspace) waits for them first,
            // otherwise every extra value, required ones included, is silently
            // dropped from the new entry.
            extrasJob?.join()
            val state = _uiState.value
            runCatching {
                val currentMs = System.currentTimeMillis()
                val nowMs = startAtMs?.let { if (it > currentMs) it - 24 * 3600 * 1000L else it } ?: currentMs
                val rawStartISO = toNotionDateTime(nowMs)

                val project = state.projects.firstOrNull { it.id == state.selectedProjectId }
                val projectName = project?.name ?: ""
                // An empty description becomes the start date on the Notion page;
                // the description input stays empty so nothing is pre-filled.
                val description = state.description.trim()
                val title = description.ifBlank { fallbackDescription(nowMs, state.dateFormat) }

                val extraNotionValues = buildExtraNotionValues(state.extraFieldDefs, state.extraValues)

                val pageId = notionRepo.createTimeEntry(
                    dbId = workspace.timeEntriesDbId,
                    title = title,
                    startISO = rawStartISO,
                    projectId = state.selectedProjectId,
                    propertyMap = workspace.propertyMap,
                    extra = extraNotionValues
                )

                val timer = ActiveTimer(
                    notionPageId = pageId,
                    roundedStart = rawStartISO,
                    rawStart = rawStartISO,
                    workspaceId = workspace.id,
                    projectId = state.selectedProjectId,
                    projectName = projectName,
                    description = description,
                    propertyMap = workspace.propertyMap,
                    extraValues = state.extraValues
                )
                settingsRepo.saveActiveTimer(timer)

                runCatching {
                    app.startForegroundService(
                        TimerForegroundService.buildStartIntent(app, timer, workspace.name, workspace.roundingMinutes, workspace.color)
                    )
                }.onFailure { e ->
                    android.util.Log.e("TimerViewModel", "Failed to start foreground service on startTimer", e)
                }

                timer to nowMs
            }.onSuccess { (timer, startMs) ->
                recentlyStarted[timer.notionPageId] = System.currentTimeMillis()
                tickStartMs = startMs
                _uiState.update { it.copy(running = true, activeTimer = timer, description = timer.description, isStarting = false, elapsedSeconds = 0L) }
                startTicking(startMs)
                loadRunningTimers()
                loadRecentEntries()
                syncServiceNotifications()
            }.onFailure { err ->
                _uiState.update { it.copy(isStarting = false, error = uiError(UiError.START_FAILED, err)) }
            }
        }
    }

    private sealed class StopResult {
        object Success : StopResult()
        object Deleted : StopResult()
        data class Failed(val message: String) : StopResult()
    }

    /**
     * Stops the currently active timer's Notion page and clears the local
     * "active" slot (tickJob cancelled, uiState reset to not-running). Does
     * not touch `runningTimers` or the foreground service; callers decide
     * what happens next (promote another running timer, leave idle, or
     * immediately start a new one).
     */
    private suspend fun stopActivePage(manualEndMs: Long? = null): StopResult {
        val timer = _uiState.value.activeTimer ?: return StopResult.Success
        val workspace = _uiState.value.workspaces.firstOrNull { it.id == timer.workspaceId }
        val rounding = workspace?.roundingMinutes ?: 15

        val outcome = runCatching {
            val rawStartMs = parseIso(timer.rawStart)
            val startMs = if (rounding == 0) rawStartMs else roundToNearest(rawStartMs, rounding)
            val nowMs = manualEndMs ?: System.currentTimeMillis()
            val endMs = if (rounding == 0) nowMs else {
                val minEnd = startMs + rounding * 60_000L
                val rounded = roundToNearest(nowMs, rounding)
                if (rounded < minEnd) minEnd else rounded
            }
            notionRepo.stopTimeEntry(
                pageId = timer.notionPageId,
                startISO = toNotionDateTime(startMs),
                endISO = toNotionDateTime(endMs),
                propertyMap = timer.propertyMap
            )
        }

        if (outcome.isSuccess) {
            markStopped(timer.notionPageId)
            settingsRepo.clearActiveTimer()
            tickJob?.cancel()
            _uiState.update { it.copy(running = false, activeTimer = null, elapsedSeconds = 0L) }
            return StopResult.Success
        }

        val msg = outcome.exceptionOrNull()?.message ?: ""
        if (isPageDeletedError(msg)) {
            markStopped(timer.notionPageId)
            settingsRepo.clearActiveTimer()
            tickJob?.cancel()
            _uiState.update { it.copy(running = false, activeTimer = null, elapsedSeconds = 0L) }
            return StopResult.Deleted
        }
        return StopResult.Failed(uiError(UiError.STOP_FAILED, msg))
    }

    /**
     * Writes a description that was typed in the running view but not saved
     * yet. Stopping used to ignore the draft entirely: the typed text was
     * dropped, and in a workspace that requires a description the stop was
     * even refused while the text was sitting there on screen.
     *
     * Returns false when the write failed. The draft is then kept on screen but
     * not recorded as saved; recording it anyway made a retried Stop skip the
     * write and close the entry with its old title.
     */
    private suspend fun commitDescription(timer: ActiveTimer, description: String): Boolean {
        val trimmed = description.trim()
        if (trimmed == timer.description.trim()) return true
        val titleProp = timer.propertyMap.timeEntryTitleProp
        if (titleProp.isNotBlank()) {
            val startMs = runCatching { parseIso(timer.rawStart) }.getOrDefault(System.currentTimeMillis())
            // Same fallback as everywhere else: never leave the page "Untitled".
            val title = trimmed.ifBlank { fallbackDescription(startMs, _uiState.value.dateFormat) }
            val written = runCatching {
                notionRepo.updatePageProperties(
                    timer.notionPageId,
                    mapOf(titleProp to mapOf("title" to listOf(mapOf("text" to mapOf("content" to title)))))
                )
            }
            // A deleted page is left to the stop itself, which reports it.
            if (written.isFailure && !isPageDeletedError(written.exceptionOrNull()?.message ?: "")) {
                _uiState.update { it.copy(description = trimmed) }
                return false
            }
        }
        val updated = timer.copy(description = trimmed)
        _uiState.update { it.copy(activeTimer = updated, description = trimmed) }
        settingsRepo.saveActiveTimer(updated)
        return true
    }

    /**
     * @param pendingDescription the description as it stands in the running
     *   view's input, which may not have been saved yet.
     */
    fun stopTimer(manualEndMs: Long? = null, pendingDescription: String? = null) {
        val state = _uiState.value
        val timer = state.activeTimer ?: return
        val description = pendingDescription?.trim() ?: state.description

        val workspace = state.workspaces.firstOrNull { it.id == timer.workspaceId }
        if (workspace?.descriptionRequired == true && description.isBlank()) {
            _uiState.update { it.copy(stopError = "description_before_stopping") }
            return
        }

        if (state.extraFieldDefs.any { def ->
                def.required && (state.extraValues[def.propName]?.isBlank() != false)
            }) {
            _uiState.update { it.copy(stopError = "required_fields_before_stopping") }
            return
        }

        _uiState.update { it.copy(isStopping = true, stopError = null) }

        viewModelScope.launch {
            val stoppedPageId = timer.notionPageId
            val stoppedTitle = description
            if (!commitDescription(timer, description)) {
                // The entry stays running so the description isn't lost; Retry
                // writes it again before stopping.
                _uiState.update { it.copy(isStopping = false, stopError = UiError.UPDATE_FAILED) }
                return@launch
            }
            when (val result = stopActivePage(manualEndMs)) {
                is StopResult.Success -> {
                    val remainingRunning = _uiState.value.runningTimers.filter { it.pageId != stoppedPageId }
                    _uiState.update { it.copy(isStopping = false, runningTimers = remainingRunning, stopError = null) }
                    if (remainingRunning.isNotEmpty()) {
                        loadRunningTimer(remainingRunning.first())
                    } else {
                        stopService()
                    }
                    loadRunningTimers()
                    loadRecentEntries()
                    syncServiceNotifications()
                }
                is StopResult.Deleted -> {
                    val remainingRunning = _uiState.value.runningTimers.filter { it.pageId != stoppedPageId }
                    _uiState.update { it.copy(isStopping = false, runningTimers = remainingRunning, deletedPageDialogVisible = true, deletedPageDialogTitle = stoppedTitle.ifBlank { null }) }
                    if (remainingRunning.isNotEmpty()) {
                        loadRunningTimer(remainingRunning.first())
                    } else {
                        stopService()
                    }
                }
                is StopResult.Failed -> {
                    _uiState.update { it.copy(isStopping = false, stopError = result.message) }
                }
            }
        }
    }

    /**
     * Stops the currently active timer, then runs [onStopped]. Used by the
     * "already running" confirmation flow ("+"/presets/shortcuts): the app
     * never keeps two timers running side by side, so starting a new one
     * while one is active always stops the old one first.
     */
    private fun stopActiveAndThen(onStopped: suspend () -> Unit) {
        if (!_uiState.value.running) {
            viewModelScope.launch { onStopped() }
            return
        }
        viewModelScope.launch {
            when (val result = stopActivePage()) {
                is StopResult.Success -> {
                    stopService()
                    loadRunningTimers()
                    loadRecentEntries()
                    onStopped()
                }
                is StopResult.Deleted -> {
                    stopService()
                    _uiState.update { it.copy(deletedPageDialogVisible = true, deletedPageDialogTitle = null) }
                }
                is StopResult.Failed -> {
                    _uiState.update { it.copy(error = result.message) }
                }
            }
        }
    }

    fun updateStartTime(newStartISO: String) {
        val timer = _uiState.value.activeTimer ?: return
        val (updated, parsedMs) = RunningClock.withNewStart(timer, newStartISO) ?: return
        tickStartMs = parsedMs
        _uiState.update { it.copy(activeTimer = updated, elapsedSeconds = RunningClock.elapsedSeconds(parsedMs, System.currentTimeMillis())) }
        // The on-screen counter and the notification count from the start they were given,
        // so restart the counter and tell the notification (its Stop writes the end time).
        startTicking(parsedMs)
        syncServiceNotifications()
        viewModelScope.launch {
            settingsRepo.saveActiveTimer(updated)
            if (timer.propertyMap.timeEntryDateProp.isBlank()) return@launch
            runCatching {
                notionRepo.updatePageProperties(
                    timer.notionPageId,
                    mapOf(timer.propertyMap.timeEntryDateProp to mapOf("date" to mapOf("start" to newStartISO, "end" to null)))
                )
            }.onFailure { err ->
                val msg = err.message ?: ""
                if (isPageDeletedError(msg)) {
                    _uiState.update { it.copy(deletedPageDialogVisible = true, deletedPageDialogTitle = timer.description.ifBlank { null }) }
                } else {
                    _uiState.update { it.copy(error = uiError(UiError.UPDATE_FAILED, msg)) }
                }
            }
        }
    }

    /**
     * Catches up with a Stop tapped on the notification while the app was in
     * the background. The service reports that with a broadcast only the
     * visible activity receives, so the screen kept showing the timer as
     * running, and stopping it again from there overwrote the end time the
     * notification had already written. The service clears the stored active
     * timer once Notion has the stop, so a running timer with nothing stored
     * behind it was stopped elsewhere.
     */
    fun reconcileActiveTimer() {
        viewModelScope.launch {
            if (_uiState.value.isStopping || _uiState.value.isStarting) return@launch
            val active = _uiState.value.activeTimer
            val stored = settingsRepo.getActiveTimer()
            if (active == null) {
                // Started on Windows while this screen was away (TimerPushService stored it).
                if (stored != null) showActiveTimerFromElsewhere(stored)
                return@launch
            }
            if (stored != null) return@launch
            if (_uiState.value.activeTimer?.notionPageId != active.notionPageId) return@launch
            handleTimerStoppedFromService(active.notionPageId)
        }
    }

    /**
     * The active timer was started or changed on another device while this screen is open
     * (TimerPushService has stored it and shown its notification): show it here too.
     */
    fun onActiveTimerChangedElsewhere() {
        viewModelScope.launch {
            if (_uiState.value.isStopping || _uiState.value.isStarting) return@launch
            val stored = settingsRepo.getActiveTimer() ?: return@launch
            if (stored != _uiState.value.activeTimer) showActiveTimerFromElsewhere(stored)
        }
    }

    private suspend fun showActiveTimerFromElsewhere(timer: ActiveTimer) {
        // Just stopped here: its stored copy is about to be cleared, never bring it back.
        if (recentlyStopped.containsKey(timer.notionPageId)) return
        val workspace = _uiState.value.workspaces.firstOrNull { it.id == timer.workspaceId } ?: return
        val startMs = RunningClock.withNewStart(timer, timer.rawStart)?.second ?: return
        tickStartMs = startMs
        _uiState.update { state ->
            state.copy(
                running = true,
                activeTimer = timer,
                selectedWorkspaceId = timer.workspaceId,
                selectedProjectId = timer.projectId,
                description = timer.description,
                extraValues = timer.extraValues,
                elapsedSeconds = RunningClock.elapsedSeconds(startMs, System.currentTimeMillis()),
            )
        }
        startTicking(startMs)
        loadExtraFieldDefsAndWait(workspace)
        loadRunningTimers()
    }

    fun handleTimerStoppedFromService(stoppedPageId: String? = null) {
        val state = _uiState.value
        tickJob?.cancel()

        val pageToFilter = stoppedPageId ?: state.activeTimer?.notionPageId
        pageToFilter?.let { markStopped(it) }
        val remainingRunning = if (pageToFilter != null) {
            state.runningTimers.filter { it.pageId != pageToFilter }
        } else {
            state.runningTimers
        }
        val wasActive = state.activeTimer?.notionPageId == pageToFilter

        if (remainingRunning.isNotEmpty() && wasActive) {
            _uiState.update { it.copy(
                running = false,
                activeTimer = null,
                elapsedSeconds = 0L,
                isStopping = false,
                runningTimers = remainingRunning
            ) }
            loadRunningTimer(remainingRunning.first())
        } else {
            if (remainingRunning.isEmpty()) {
                stopService()
            }
            _uiState.update { it.copy(
                running = false,
                activeTimer = null,
                elapsedSeconds = 0L,
                isStopping = false,
                runningTimers = remainingRunning
            ) }
        }
        loadRunningTimers()
        loadRecentEntries()
        syncServiceNotifications()
    }

    suspend fun loadRunningTimersSuspending() {
        val state = _uiState.value
        if (!state.signedIn || state.workspaces.isEmpty()) return

        _uiState.update { it.copy(runningTimersLoading = true) }

        // The flag is cleared in a finally: left set by an unexpected failure it
        // would turn every later refresh (manual and periodic alike) into an
        // immediate no-op, with nothing on screen to say why.
        try {
            val allRunning = mutableListOf<RunningTimerInfo>()
            var failure: Throwable? = null
            for (ws in state.workspaces) {
                val pm = ws.propertyMap
                if (ws.timeEntriesDbId.isBlank() || pm.timeEntryStatusProp.isBlank() ||
                    pm.timeEntryStatusRunning.isBlank()) continue
                val result = runCatching {
                    notionRepo.fetchRunningTimers(
                        ws.timeEntriesDbId, pm.timeEntryStatusProp,
                        pm.timeEntryStatusRunning, pm.timeEntryDateProp
                    )
                }
                if (result.isFailure) {
                    // Keep previously fetched running timers for this workspace, and
                    // report it: a query that failed used to be indistinguishable
                    // from a workspace with nothing running.
                    failure = result.exceptionOrNull()
                    android.util.Log.e(
                        "TimerViewModel", "Could not load running timers for ${ws.name}", failure
                    )
                    val prevForWs = state.runningTimers.filter { it.workspaceId == ws.id }
                    allRunning.addAll(prevForWs)
                } else {
                    result.getOrThrow().forEach { res ->
                        allRunning.add(RunningTimerInfo(
                            pageId = res.pageId,
                            title = res.title,
                            startISO = res.startISO,
                            url = res.url,
                            workspaceId = ws.id,
                            workspaceName = ws.name,
                            propertyMap = pm
                        ))
                    }
                }
            }
            allRunning.sortByDescending { it.startISO }
            val visibleRunning = withoutRecentlyStopped(allRunning)
            // Merge deltas into the existing list instead of replacing it wholesale, so
            // entries that are still running keep their identity/position (no flicker or
            // momentary disappearance while switching timers).
            _uiState.update { it.copy(
                runningTimers = mergeById(it.runningTimers, visibleRunning) { r -> r.pageId },
                runningTimersError = failure?.let { err -> uiError(UiError.RUNNING_TIMERS_FAILED, err) }
            ) }
            syncServiceNotifications()
            val updatedState = _uiState.value
            if (!updatedState.running && updatedState.activeTimer == null && visibleRunning.isNotEmpty()) {
                loadRunningTimer(visibleRunning.first())
            }
        } finally {
            _uiState.update { it.copy(runningTimersLoading = false) }
        }
    }

    fun loadRunningTimers() {
        if (_uiState.value.runningTimersLoading) return
        viewModelScope.launch {
            loadRunningTimersSuspending()
        }
    }

    fun loadRecentEntries() {
        val state = _uiState.value
        if (!state.signedIn ||
            state.workspaces.isEmpty() || state.recentEntriesLoading) return

        _uiState.update { it.copy(recentEntriesLoading = true) }

        viewModelScope.launch {
            val allRecent = mutableListOf<RecentEntryInfo>()
            for (ws in state.workspaces) {
                val pm = ws.propertyMap
                if (ws.timeEntriesDbId.isBlank() || pm.timeEntryStatusProp.isBlank() ||
                    pm.timeEntryStatusDone.isBlank()) continue
                runCatching {
                    notionRepo.fetchRecentEntries(
                        ws.timeEntriesDbId, pm.timeEntryStatusProp,
                        pm.timeEntryStatusDone, pm.timeEntryDateProp
                    )
                }.getOrDefault(emptyList()).forEach { result ->
                    allRecent.add(RecentEntryInfo(
                        pageId = result.pageId,
                        title = result.title,
                        startISO = result.startISO,
                        endISO = result.endISO,
                        url = result.url,
                        workspaceId = ws.id,
                        workspaceName = ws.name,
                        propertyMap = pm
                    ))
                }
            }
            allRecent.sortByDescending { it.endISO ?: it.startISO ?: "" }
            val top = allRecent.take(15)
            // Merge deltas (keeping existing entries' identity), then re-apply the
            // recent ordering so newly completed entries surface without a full reload.
            _uiState.update { state ->
                val topIds = top.map { it.pageId }.toSet()
                val merged = mergeById(state.recentEntries, top) { r -> r.pageId }
                    .filter { it.pageId in topIds }
                    .sortedByDescending { it.endISO ?: it.startISO ?: "" }
                state.copy(recentEntriesLoading = false, recentEntries = merged)
            }
        }
    }

    fun confirmDelete(pageId: String, title: String, isRunning: Boolean) {
        _uiState.update { it.copy(deleteConfirmInfo = DeleteConfirmInfo(pageId, title, isRunning)) }
    }

    fun cancelDelete() {
        _uiState.update { it.copy(deleteConfirmInfo = null) }
    }

    fun deleteEntry() {
        val info = _uiState.value.deleteConfirmInfo ?: return
        _uiState.update { it.copy(deleteConfirmInfo = null) }

        viewModelScope.launch {
            runCatching { notionRepo.deletePage(info.pageId) }
                .onSuccess {
                    markStopped(info.pageId)
                    val isActiveTimer = _uiState.value.activeTimer?.notionPageId == info.pageId
                    when {
                        isActiveTimer -> {
                            tickJob?.cancel()
                            stopService()
                            settingsRepo.clearActiveTimer()
                            _uiState.update { it.copy(
                                running = false, activeTimer = null, elapsedSeconds = 0L,
                                runningTimers = it.runningTimers.filter { r -> r.pageId != info.pageId }
                            )}
                        }
                        info.isRunning -> {
                            _uiState.update { it.copy(runningTimers = it.runningTimers.filter { r -> r.pageId != info.pageId }) }
                        }
                        else -> {
                            _uiState.update { it.copy(recentEntries = it.recentEntries.filter { r -> r.pageId != info.pageId }) }
                        }
                    }
                    loadRunningTimers()
                    loadRecentEntries()
                }
                .onFailure { err ->
                    _uiState.update { it.copy(error = uiError(UiError.DELETE_FAILED, err)) }
                }
        }
    }

    fun loadRunningTimer(rt: RunningTimerInfo) {
        val state = _uiState.value
        val workspace = state.workspaces.firstOrNull { it.id == rt.workspaceId } ?: return

        // If another timer was active in UI, put it back in the list (it still runs in Notion)
        val previouslyActive = if (state.running && state.activeTimer != null) {
            RunningTimerInfo(
                pageId = state.activeTimer.notionPageId,
                title = state.activeTimer.description,
                startISO = state.activeTimer.roundedStart,
                workspaceId = state.activeTimer.workspaceId,
                workspaceName = state.workspaces.firstOrNull { it.id == state.activeTimer.workspaceId }?.name ?: "",
                propertyMap = state.activeTimer.propertyMap
            )
        } else null

        tickJob?.cancel()

        val startMs = runCatching { parseIso(rt.startISO) }.getOrElse { System.currentTimeMillis() }
        // A title this app generated for an empty description reads back as an
        // empty description, so the input is never pre-filled with a date.
        val description = if (isFallbackDescription(rt.title, startMs, state.dateFormat)) "" else rt.title

        val timer = ActiveTimer(
            notionPageId = rt.pageId,
            roundedStart = rt.startISO,
            rawStart = rt.startISO,
            workspaceId = rt.workspaceId,
            projectId = null,
            projectName = "",
            description = description,
            propertyMap = rt.propertyMap
        )

        // Switch foreground service to show the loaded timer
        runCatching {
            app.startForegroundService(
                TimerForegroundService.buildStartIntent(app, timer, workspace.name, workspace.roundingMinutes, workspace.color)
            )
        }.onFailure { e ->
            android.util.Log.e("TimerViewModel", "Failed to start foreground service on loadRunningTimer", e)
        }

        tickStartMs = startMs

        val others = state.runningTimers.filter { it.pageId != rt.pageId }.toMutableList()
        if (previouslyActive != null) others.add(previouslyActive)

        _uiState.update { it.copy(
            running = true,
            activeTimer = timer,
            elapsedSeconds = 0L,
            runningTimers = others,
            description = description,
            selectedWorkspaceId = rt.workspaceId
        )}
        startTicking(startMs)
        viewModelScope.launch { settingsRepo.saveActiveTimer(timer) }
        selectWorkspace(rt.workspaceId)
        syncServiceNotifications()

        // The running-timers list only carries title/start/url, so fetch the page's
        // full properties to restore its connected project and extra field values.
        viewModelScope.launch { refreshActiveTimerFieldsFromNotion(rt.pageId, workspace, rt.propertyMap) }
    }

    /**
     * Retries a Notion call up to [attempts] times with a short delay between tries.
     * Adopting a timer fires several Notion calls at once (running timers, recent
     * entries, this device's own workspace schema fetch, and this call's page
     * properties + schema fetch), a single transient failure or momentary rate
     * limit among them shouldn't leave extra field values blank until the next
     * scheduled sync. Returns null once every attempt has failed.
     */
    private suspend fun <T> retryNotionCall(attempts: Int = 3, block: suspend () -> T): T? {
        repeat(attempts) { attempt ->
            val result = runCatching { block() }
            if (result.isSuccess) return result.getOrNull()
            // runCatching also catches cancellation; rethrow it so a superseded
            // load stops immediately instead of retrying into a stale write.
            (result.exceptionOrNull() as? kotlinx.coroutines.CancellationException)?.let { throw it }
            if (attempt < attempts - 1) delay(1000L * (attempt + 1))
        }
        return null
    }

    /**
     * Re-reads the active timer's connected project and extra field values straight
     * from its Notion page, so Notion stays the source of truth instead of whatever
     * was last cached locally (e.g. if the page was edited directly in Notion, or
     * from another device, while this device had it loaded). Called when a running
     * timer is first loaded, and on every subsequent sync (manual refresh, 60s
     * auto-refresh) while it's still the active one.
     */
    private suspend fun refreshActiveTimerFieldsFromNotion(pageId: String, workspace: Workspace, propertyMap: PropertyMap) {
        val props = retryNotionCall { notionRepo.fetchPageProperties(pageId) } ?: return
        if (_uiState.value.activeTimer?.notionPageId != pageId) return

        val relProp = propertyMap.timeEntryRelationProp
        val projectId = relProp.takeIf { it.isNotBlank() }
            ?.let { parsePropertyValue(props.objOrNull(it), "relation") }
            ?.split(",")?.firstOrNull { it.isNotBlank() }
        val projectName = if (projectId != null && workspace.projectsDbId.isNotBlank()) {
            runCatching { notionRepo.fetchDatabasePages(workspace.projectsDbId) }.getOrDefault(emptyList())
                .firstOrNull { it.id == projectId }?.name ?: ""
        } else ""

        // workspace.extraProps[].type is not a reliable source of the real Notion
        // property type (it's never populated on save); fetch the live schema,
        // same as loadExtraFieldDefs does, to know how to parse each value. If the
        // schema still can't be fetched after retrying, bail out entirely instead
        // of falling back to an empty schema: every extra value would then parse
        // as blank (prop.type is never populated either) and overwrite whatever
        // correct values were already showing; a stale-but-right value beats a
        // freshly-wrong one.
        val extraValues = if (workspace.extraProps.isEmpty() || workspace.timeEntriesDbId.isBlank()) {
            emptyMap()
        } else {
            val schema = retryNotionCall { notionRepo.getDatabaseSchema(workspace.timeEntriesDbId) } ?: return
            workspace.extraProps.associate { prop ->
                val type = schema[prop.propName]?.type ?: prop.type
                prop.propName to parsePropertyValue(props.objOrNull(prop.propName), type)
            }
        }

        val updatedTimer = _uiState.value.activeTimer?.takeIf { it.notionPageId == pageId }
            ?.copy(projectId = projectId, projectName = projectName, extraValues = extraValues) ?: return

        _uiState.update { it.copy(
            activeTimer = updatedTimer,
            selectedProjectId = projectId,
            extraValues = extraValues
        )}
        settingsRepo.saveActiveTimer(updatedTimer)
    }

    /** Refetches the active timer's fields from Notion, if there is one. Safe to call
     *  from any sync path (manual refresh, periodic auto-refresh). */
    private suspend fun refreshActiveTimerFieldsIfRunning() {
        val state = _uiState.value
        val timer = state.activeTimer ?: return
        val workspace = state.workspaces.firstOrNull { it.id == timer.workspaceId } ?: return
        refreshActiveTimerFieldsFromNotion(timer.notionPageId, workspace, timer.propertyMap)
    }

    fun stopOtherRunningTimer(rt: RunningTimerInfo) {
        val workspace = _uiState.value.workspaces.firstOrNull { it.id == rt.workspaceId }
        val rounding = workspace?.roundingMinutes ?: 15
        viewModelScope.launch {
            runCatching {
                val rawStartMs = parseIso(rt.startISO)
                val startMs = if (rounding == 0) rawStartMs else roundToNearest(rawStartMs, rounding)
                val nowMs = System.currentTimeMillis()
                val endMs = if (rounding == 0) nowMs else {
                    val minEnd = startMs + rounding * 60_000L
                    val rounded = roundToNearest(nowMs, rounding)
                    if (rounded < minEnd) minEnd else rounded
                }
                val startISO = toNotionDateTime(startMs)
                notionRepo.stopTimeEntry(
                    pageId = rt.pageId,
                    startISO = startISO,
                    endISO = toNotionDateTime(endMs),
                    propertyMap = rt.propertyMap
                )
            }.onSuccess {
                markStopped(rt.pageId)
                val remainingRunning = _uiState.value.runningTimers.filter { t -> t.pageId != rt.pageId }
                _uiState.update { it.copy(runningTimers = remainingRunning) }
                loadRunningTimers()
                loadRecentEntries()
            }.onFailure { err ->
                _uiState.update { it.copy(error = uiError(UiError.STOP_FAILED, err)) }
            }
        }
    }

    private var pendingActionAfterWarning: (() -> Unit)? = null

    fun onConfirmAlreadyRunning() {
        _uiState.update { it.copy(showAlreadyRunningWarning = false) }
        val action = pendingActionAfterWarning
        pendingActionAfterWarning = null
        action?.invoke()
    }

    fun onCancelAlreadyRunning() {
        _uiState.update { it.copy(showAlreadyRunningWarning = false) }
        pendingActionAfterWarning = null
    }

    // Only reachable while a timer is running; stops it (via the confirmation
    // dialog) and drops back to the idle form so a fresh timer can be composed.
    fun onPlusButtonClicked() {
        if (!_uiState.value.running) return
        pendingActionAfterWarning = {
            stopActiveAndThen {}
        }
        _uiState.update { it.copy(showAlreadyRunningWarning = true) }
    }

    fun applyPreset(workspaceId: String, preset: Preset) {
        val action: suspend () -> Unit = {
            val state = _uiState.value
            val workspace = state.workspaces.firstOrNull { it.id == workspaceId }
            if (workspace != null) {
                if (state.selectedWorkspaceId != workspaceId) {
                    _uiState.update { it.copy(
                        selectedWorkspaceId = workspaceId,
                        extraFieldDefs = emptyList(),
                        extrasLoading = workspace.extraProps.isNotEmpty()
                    )}
                    settingsRepo.saveLastWorkspaceId(workspaceId)
                }
                // startTimer() only writes a property if it has a loaded def for it, so
                // this must be awaited before merging the preset's values in; otherwise,
                // if defs for this workspace are still loading (e.g. a shortcut/preset
                // fired right after launch, before the workspace's own selectWorkspace()
                // load finished), the preset's defaults would silently be dropped.
                if (_uiState.value.extraFieldDefs.isEmpty() && workspace.extraProps.isNotEmpty()) {
                    loadExtraFieldDefsAndWait(workspace)
                }
            }
            _uiState.update { it.copy(
                description = preset.pageTitle,
                extraValues = it.extraValues.toMutableMap().also { map ->
                    preset.extraValues.forEach { (k, v) -> map[k] = v }
                }
            )}
            startTimer()
        }

        if (_uiState.value.running) {
            pendingActionAfterWarning = {
                stopActiveAndThen { action() }
            }
            _uiState.update { it.copy(showAlreadyRunningWarning = true) }
        } else {
            viewModelScope.launch { action() }
        }
    }

    fun applyPresetById(workspaceId: String, presetId: String) {
        val state = _uiState.value
        // Cold start: workspaces not loaded yet. Queue the request; loadInitialState()
        // applies it once the data is available (fixes home-screen shortcuts).
        if (state.workspaces.isEmpty()) {
            pendingPreset = workspaceId to presetId
            return
        }
        val workspace = state.workspaces.firstOrNull { it.id == workspaceId } ?: return
        val preset = workspace.presets.firstOrNull { it.id == presetId } ?: return
        
        viewModelScope.launch {
            loadRunningTimersSuspending()
            applyPreset(workspaceId, preset)
        }
    }

    /**
     * "Play" on a recent entry: starts a new timer in the same workspace, with
     * the same description, connected project and extra field values as that
     * entry, but starting now, with no end. Mirrors applyPreset's flow, except
     * the project/extra values are read from the source Notion page (its own
     * schema-backed source of truth) rather than from a saved preset.
     */
    fun startFromRecentEntry(entry: RecentEntryInfo) {
        val action: suspend () -> Unit = {
            val state = _uiState.value
            val workspace = state.workspaces.firstOrNull { it.id == entry.workspaceId }
            if (workspace != null) {
                if (state.selectedWorkspaceId != entry.workspaceId) {
                    _uiState.update { it.copy(
                        selectedWorkspaceId = entry.workspaceId,
                        extraFieldDefs = emptyList(),
                        extrasLoading = workspace.extraProps.isNotEmpty()
                    )}
                    settingsRepo.saveLastWorkspaceId(entry.workspaceId)
                }
                // Also (re)load when the workspace is already selected but its fields
                // never made it in: startTimer() only writes properties it has a
                // definition for, so the restored values would be dropped otherwise.
                if (workspace.extraProps.isNotEmpty() &&
                    (_uiState.value.selectedWorkspaceId != workspace.id || _uiState.value.extraFieldDefs.isEmpty())
                ) {
                    loadExtraFieldDefsAndWait(workspace)
                }

                val startMs = entry.startISO?.let { iso -> runCatching { parseIso(iso) }.getOrNull() }
                    ?: System.currentTimeMillis()
                // A title this app generated for an empty description reads back as an
                // empty description, so the input is never pre-filled with a date.
                val description = if (isFallbackDescription(entry.title, startMs, state.dateFormat)) "" else entry.title
                _uiState.update { it.copy(description = description) }

                // Restore the connected project and extra field values straight from the
                // source page, matching the current database schema (same approach as
                // refreshActiveTimerFieldsFromNotion for an already-running timer).
                val props = runCatching { notionRepo.fetchPageProperties(entry.pageId) }.getOrNull()
                if (props != null) {
                    val relProp = workspace.propertyMap.timeEntryRelationProp
                    val projectId = relProp.takeIf { it.isNotBlank() }
                        ?.let { parsePropertyValue(props.objOrNull(it), "relation") }
                        ?.split(",")?.firstOrNull { it.isNotBlank() }

                    // The stored extraProps[].type is not a reliable source of the real
                    // Notion type, so the live schema decides how each value is parsed.
                    // Without it every value would parse as blank and overwrite the
                    // defaults that were just loaded, so the values are left alone.
                    val schema = if (workspace.extraProps.isNotEmpty() && workspace.timeEntriesDbId.isNotBlank()) {
                        retryNotionCall { notionRepo.getDatabaseSchema(workspace.timeEntriesDbId) }
                    } else emptyMap()
                    if (schema != null) {
                        val extraValues = workspace.extraProps.associate { prop ->
                            val type = schema[prop.propName]?.type ?: prop.type
                            prop.propName to parsePropertyValue(props.objOrNull(prop.propName), type)
                        }
                        _uiState.update { it.copy(selectedProjectId = projectId, extraValues = extraValues) }
                    } else {
                        _uiState.update { it.copy(selectedProjectId = projectId) }
                    }
                }
                startTimer()
            }
        }

        if (_uiState.value.running) {
            pendingActionAfterWarning = {
                stopActiveAndThen { action() }
            }
            _uiState.update { it.copy(showAlreadyRunningWarning = true) }
        } else {
            viewModelScope.launch { action() }
        }
    }

    private fun startTicking(startMs: Long) {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (true) {
                val elapsed = RunningClock.elapsedSeconds(startMs, System.currentTimeMillis())
                _uiState.update { it.copy(elapsedSeconds = elapsed) }
                delay(1000L)
            }
        }
    }

    private fun stopService() {
        app.stopService(Intent(app, TimerForegroundService::class.java))
    }

    /**
     * Notion property values for the extra fields.
     *
     * On create, an empty field is left out so Notion applies whatever default
     * the database has. On update it has to be written as an explicit empty
     * value instead ([clearEmpty]); skipping it there meant a value could be
     * set but never cleared again, since the page kept the old one.
     */
    private fun buildExtraNotionValues(
        defs: List<ExtraFieldDef>,
        values: Map<String, String>,
        clearEmpty: Boolean = false
    ): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        for (def in defs) {
            val raw = values[def.propName] ?: continue
            val notionValue = buildPropertyValue(def.type, raw)
                ?: (if (clearEmpty) emptyPropertyValue(def.type) else null)
                ?: continue
            result[def.propName] = notionValue
        }
        return result
    }

    /**
     * Merges [incoming] into [current] keyed by [id]: existing entries keep their
     * position and are updated in place, entries no longer present are dropped, and
     * genuinely new entries are appended. Avoids clearing/reloading the whole list.
     */
    private fun <T> mergeById(current: List<T>, incoming: List<T>, id: (T) -> String): List<T> {
        val incomingById = incoming.associateBy(id)
        val merged = current.filter { incomingById.containsKey(id(it)) }
            .map { incomingById.getValue(id(it)) }
        val existingIds = merged.map(id).toSet()
        return merged + incoming.filter { id(it) !in existingIds }
    }

    private fun isPageDeletedError(msg: String): Boolean {
        val lower = msg.lowercase()
        return lower.contains("404") || lower.contains("not_found") ||
               lower.contains("object_not_found") || lower.contains("could not find page") ||
               lower.contains("page not found")
    }

    // --- Utilities (ported from JS) ---

    private fun roundToNearest(ms: Long, minutes: Int): Long {
        val intervalMs = minutes * 60_000L
        return Math.round(ms.toDouble() / intervalMs) * intervalMs
    }

    // Seconds are dropped so an entry's start/end read as whole minutes, the
    // same as the desktop app writes them (it always emits ":00").
    private fun toNotionDateTime(ms: Long): String {
        val odt = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
            .withSecond(0).withNano(0)
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(odt)
    }

    private fun parseIso(iso: String): Long =
        java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private fun formatDate(ms: Long, dateFormat: String): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val month = cal.get(java.util.Calendar.MONTH) + 1
        val year = cal.get(java.util.Calendar.YEAR)
        return when (dateFormat) {
            "MM/DD/YYYY" -> "%02d/%02d/%04d".format(month, day, year)
            "YYYY-MM-DD" -> "%04d-%02d-%02d".format(year, month, day)
            else -> "%02d/%02d/%04d".format(day, month, year)
        }
    }

    /**
     * The page title written to Notion when the description is left empty: the
     * entry's own start date, in the format chosen in Settings. The description
     * input itself is never pre-filled with it; the fallback only ever exists
     * on the Notion page.
     */
    private fun fallbackDescription(startMs: Long, dateFormat: String): String =
        formatDate(startMs, dateFormat)

    /**
     * True when [title] is a title this app generated for an empty description
     * on [startMs]. Every date format is checked, not just the current one, so a
     * title written before the setting was changed is still recognised.
     */
    private fun isFallbackDescription(title: String, startMs: Long, dateFormat: String): Boolean {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return false
        return (DATE_FORMATS + dateFormat).any { formatDate(startMs, it) == trimmed }
    }

    private var presetFieldsJob: Job? = null

    /** Opens the preset editor on a new preset, starting from the timer form: its workspace,
     *  description and field values, so "save what I'm doing as a preset" stays one step. */
    fun openNewPreset() {
        val state = _uiState.value
        val workspaceId = state.selectedWorkspaceId ?: state.workspaces.firstOrNull()?.id ?: return
        val fromForm = workspaceId == state.selectedWorkspaceId
        openPresetEditor(
            PresetEditor(
                presetId = null,
                workspaceId = workspaceId,
                pageTitle = if (fromForm) state.description else "",
                extraValues = if (fromForm) state.extraValues else emptyMap()
            )
        )
    }

    fun openEditPreset(workspaceId: String, presetId: String) {
        val preset = _uiState.value.workspaces.firstOrNull { it.id == workspaceId }
            ?.presets?.firstOrNull { it.id == presetId } ?: return
        openPresetEditor(
            PresetEditor(
                presetId = presetId,
                workspaceId = workspaceId,
                name = preset.name,
                pageTitle = preset.pageTitle,
                extraValues = preset.extraValues
            )
        )
    }

    /** Picks the workspace of a new preset; its fields replace the previous workspace's. */
    fun setPresetEditorWorkspace(workspaceId: String) {
        val editor = _uiState.value.presetEditor ?: return
        if (editor.presetId != null || editor.workspaceId == workspaceId) return
        openPresetEditor(editor.copy(workspaceId = workspaceId, extraValues = emptyMap()))
    }

    fun updatePresetEditor(name: String? = null, pageTitle: String? = null) {
        _uiState.update { s ->
            s.copy(presetEditor = s.presetEditor?.let { e ->
                e.copy(name = name ?: e.name, pageTitle = pageTitle ?: e.pageTitle)
            })
        }
    }

    fun setPresetEditorExtra(propName: String, value: String) {
        _uiState.update { s ->
            s.copy(presetEditor = s.presetEditor?.let { e -> e.copy(extraValues = e.extraValues + (propName to value)) })
        }
    }

    /** Removes the preset open in the editor and closes it. */
    fun deletePresetEditor() {
        val editor = _uiState.value.presetEditor ?: return
        val presetId = editor.presetId ?: return
        deletePreset(editor.workspaceId, presetId)
        closePresetEditor()
    }

    fun closePresetEditor() {
        presetFieldsJob?.cancel()
        _uiState.update { it.copy(presetEditor = null) }
    }

    /** Saves the editor's preset: a new one goes at the end of its workspace's list, an edited
     *  one keeps its place. The name is what the preset chip shows, so it's required. */
    fun savePresetEditor() {
        val editor = _uiState.value.presetEditor ?: return
        val title = editor.name.trim()
        if (title.isBlank()) return
        if (_uiState.value.workspaces.none { it.id == editor.workspaceId }) return
        val preset = Preset(
            id = editor.presetId ?: java.util.UUID.randomUUID().toString(),
            name = title,
            pageTitle = editor.pageTitle.trim(),
            extraValues = editor.extraValues
        )
        editPresets(editor.workspaceId) { presets ->
            if (editor.presetId == null) presets + preset
            else presets.map { if (it.id == editor.presetId) preset else it }
        }
        closePresetEditor()
    }

    private fun openPresetEditor(editor: PresetEditor) {
        val workspace = _uiState.value.workspaces.firstOrNull { it.id == editor.workspaceId } ?: return
        presetFieldsJob?.cancel()
        _uiState.update {
            it.copy(presetEditor = editor.copy(
                fieldDefs = emptyList(),
                fieldsLoading = workspace.extraProps.isNotEmpty(),
                fieldsError = null
            ))
        }
        presetFieldsJob = viewModelScope.launch {
            val result = buildExtraFieldDefs(workspace)
            _uiState.update { s ->
                val e = s.presetEditor
                if (e == null || e.workspaceId != workspace.id) s
                else s.copy(presetEditor = e.copy(
                    fieldDefs = result?.defs ?: emptyList(),
                    fieldsLoading = false,
                    fieldsError = result?.error ?: if (result == null) "extras_load_failed" else null
                ))
            }
        }
    }

    fun deletePreset(workspaceId: String, presetId: String) {
        editPresets(workspaceId) { presets -> presets.filter { it.id != presetId } }
    }

    /** Moves a workspace's preset from place [from] to [to] (dragged in Manage presets). */
    fun movePreset(workspaceId: String, from: Int, to: Int) {
        editPresets(workspaceId) { presets -> presets.moved(from, to) }
    }

    /**
     * Applies a preset edit made from the timer screen. The change is shown at
     * once, then applied to the *stored* workspaces rather than to this
     * screen's copy: writing the copy back undid anything a sync had pulled in
     * since it was loaded, and, with the timestamp bumped, pushed that older
     * list over the newer one on every other device. Bumping the settings
     * timestamp is what tells the next sync this device has edits to push.
     * (Same as SettingsViewModel.saveWorkspaces.)
     */
    private fun editPresets(workspaceId: String, transform: (List<Preset>) -> List<Preset>) {
        val apply = { list: List<Workspace> ->
            list.map { ws -> if (ws.id == workspaceId) ws.copy(presets = transform(ws.presets)) else ws }
        }
        _uiState.update { it.copy(workspaces = apply(it.workspaces)) }
        viewModelScope.launch {
            val stored = settingsRepo.updateWorkspaces(apply)
            settingsRepo.touchSettingsUpdatedAt()
            _uiState.update { it.copy(workspaces = stored) }
            ShortcutHelper.updateShortcuts(app, stored)
        }
    }

    companion object {
        fun buildPropertyValue(type: String, value: String): Any? {
            if (value.isBlank() && type != "checkbox") return null
            return when (type) {
                "rich_text" -> mapOf("rich_text" to listOf(mapOf("text" to mapOf("content" to value.trim()))))
                "number" -> value.toDoubleOrNull()?.let { mapOf("number" to it) }
                "checkbox" -> mapOf("checkbox" to (value == "true"))
                "select" -> if (value.isBlank()) null else mapOf("select" to mapOf("name" to value.trim()))
                "status" -> if (value.isBlank()) null else mapOf("status" to mapOf("name" to value.trim()))
                "multi_select" -> {
                    val names = value.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    if (names.isEmpty()) null
                    else mapOf("multi_select" to names.map { mapOf("name" to it) })
                }
                "date" -> if (value.isBlank()) null else mapOf("date" to mapOf("start" to value))
                "url" -> mapOf("url" to value.trim())
                "email" -> mapOf("email" to value.trim())
                "phone_number" -> mapOf("phone_number" to value.trim())
                "people" -> {
                    val ids = value.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    if (ids.isEmpty()) null else mapOf("people" to ids.map { mapOf("id" to it) })
                }
                "relation" -> {
                    val ids = value.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    if (ids.isEmpty()) null else mapOf("relation" to ids.map { mapOf("id" to it) })
                }
                "files" -> if (value.isBlank()) null
                else mapOf("files" to listOf(mapOf("name" to "file", "external" to mapOf("url" to value.trim()))))
                else -> null
            }
        }

        /**
         * The Notion value that clears a property. `checkbox` is absent on
         * purpose: false is already a real value that [buildPropertyValue]
         * writes, so there is nothing to clear.
         */
        fun emptyPropertyValue(type: String): Any? = when (type) {
            "rich_text" -> mapOf("rich_text" to emptyList<Any>())
            "number" -> mapOf("number" to null)
            "select" -> mapOf("select" to null)
            "status" -> mapOf("status" to null)
            "multi_select" -> mapOf("multi_select" to emptyList<Any>())
            "date" -> mapOf("date" to null)
            "url" -> mapOf("url" to null)
            "email" -> mapOf("email" to null)
            "phone_number" -> mapOf("phone_number" to null)
            "people" -> mapOf("people" to emptyList<Any>())
            "relation" -> mapOf("relation" to emptyList<Any>())
            "files" -> mapOf("files" to emptyList<Any>())
            else -> null
        }

        /** Reverse of [buildPropertyValue]: reads a Notion page property's raw JSON
         *  value back into the string format used by extraValues/ExtraFieldInput. */
        fun parsePropertyValue(prop: kotlinx.serialization.json.JsonObject?, type: String): String {
            prop ?: return ""
            // Every read here goes through the null-safe accessors: an empty
            // select/status/date arrives as JSON `null` and has to read as
            // "no value", not as the text "null".
            return when (type) {
                "rich_text" -> prop.arrayOrNull("rich_text")
                    ?.joinToString("") { it.asObjectOrNull()?.stringOrNull("plain_text") ?: "" } ?: ""
                "number" -> prop.stringOrNull("number") ?: ""
                "checkbox" -> if (prop.boolOrNull("checkbox") == true) "true" else "false"
                "select" -> prop.objOrNull("select")?.stringOrNull("name") ?: ""
                "status" -> prop.objOrNull("status")?.stringOrNull("name") ?: ""
                "multi_select" -> prop.arrayOrNull("multi_select")
                    ?.joinToString(",") { it.asObjectOrNull()?.stringOrNull("name") ?: "" } ?: ""
                "date" -> prop.objOrNull("date")?.stringOrNull("start") ?: ""
                "url" -> prop.stringOrNull("url") ?: ""
                "email" -> prop.stringOrNull("email") ?: ""
                "phone_number" -> prop.stringOrNull("phone_number") ?: ""
                "people" -> prop.arrayOrNull("people")
                    ?.joinToString(",") { it.asObjectOrNull()?.stringOrNull("id") ?: "" } ?: ""
                "relation" -> prop.arrayOrNull("relation")
                    ?.joinToString(",") { it.asObjectOrNull()?.stringOrNull("id") ?: "" } ?: ""
                "files" -> prop.arrayOrNull("files")?.firstOrNull()?.asObjectOrNull()
                    ?.objOrNull("external")?.stringOrNull("url") ?: ""
                else -> ""
            }
        }
    }

    fun syncServiceNotifications() {
        val state = _uiState.value
        // Only the active timer (the one shown in the running view) has a permanent
        // notification. When there is no active timer there is nothing to surface, so
        // make sure any existing service is stopped instead. Starting a service only to
        // immediately stopSelf() risks a ForegroundServiceDidNotStartInTimeException.
        val active = state.activeTimer
        if (active == null) {
            stopService()
            return
        }
        val intent = Intent(app, TimerForegroundService::class.java).apply {
            action = TimerForegroundService.ACTION_SYNC_TIMERS
            putExtra("active_page_id", active.notionPageId)
            putExtra("active_title", active.description)
            putExtra("active_start_iso", active.rawStart)
            val ws = state.workspaces.firstOrNull { it.id == active.workspaceId }
            putExtra("active_workspace_name", ws?.name ?: "")
            putExtra("active_workspace_color", ws?.color)
            putExtra("active_rounding_minutes", ws?.roundingMinutes ?: 15)
        }
        runCatching {
            app.startForegroundService(intent)
        }.onFailure { e ->
            android.util.Log.e("TimerViewModel", "Failed to sync service notifications", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        tickJob?.cancel()
    }
}
