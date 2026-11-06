package to.wim.timetracker

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import to.wim.timetracker.service.TimerForegroundService
import to.wim.timetracker.service.TimerPushService
import to.wim.timetracker.ui.screens.SettingsScreen
import to.wim.timetracker.ui.screens.TimerScreen
import to.wim.timetracker.ui.theme.Motion
import to.wim.timetracker.ui.theme.TimeTrackerTheme
import to.wim.timetracker.viewmodel.SettingsViewModel
import to.wim.timetracker.viewmodel.TimerViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val settingsRepo = to.wim.timetracker.data.repository.SettingsRepository(newBase)
        val localeCode = runBlocking {
            runCatching { settingsRepo.localeFlow.first() }.getOrDefault("system")
        }
        super.attachBaseContext(LocaleHelper.wrap(newBase, localeCode))
    }

    private lateinit var timerViewModel: TimerViewModel
    private lateinit var settingsViewModel: SettingsViewModel

    private val timerStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                TimerForegroundService.ACTION_TIMER_STOPPED -> {
                    val stoppedPageId = intent.getStringExtra(TimerForegroundService.EXTRA_PAGE_ID)
                    timerViewModel.handleTimerStoppedFromService(stoppedPageId)
                }
                TimerPushService.ACTION_ACTIVE_TIMER_CHANGED -> timerViewModel.onActiveTimerChangedElsewhere()
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* permission result handled silently */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        timerViewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[TimerViewModel::class.java]

        settingsViewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[SettingsViewModel::class.java]

        requestNotificationPermissionIfNeeded()
        handlePresetShortcut(intent)
        if (savedInstanceState == null) handleSignInLink(intent)

        setContent {
            val settingsUiState by settingsViewModel.uiState.collectAsState()
            TimeTrackerTheme(
                themeMode = settingsUiState.themeMode,
                dynamicColor = settingsUiState.dynamicColor
            ) {
                AppNavigation(timerViewModel, settingsViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePresetShortcut(intent)
        handleSignInLink(intent)
    }

    /**
     * Finishes "Sign in with Notion": the browser opens the app with
     * timetrackerfornotion://auth?code=…&state=… (…-debug:// for the Dev build). The app scope
     * lets it finish even if this screen is recreated meanwhile.
     */
    private fun handleSignInLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW || uri.host != "auth") return
        val app = application as TimeTrackerApp
        app.appScope.launch {
            app.notionSession.completeSignIn(
                code = uri.getQueryParameter("code"),
                state = uri.getQueryParameter("state"),
                error = uri.getQueryParameter("error"),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(TimerForegroundService.ACTION_TIMER_STOPPED).apply {
            addAction(TimerPushService.ACTION_ACTIVE_TIMER_CHANGED)
        }
        ContextCompat.registerReceiver(this, timerStoppedReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        timerViewModel.refreshSettings()
        timerViewModel.reconcileActiveTimer()
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(timerStoppedReceiver) }
    }

    private fun handlePresetShortcut(intent: Intent?) {
        val workspaceId = intent?.getStringExtra(ShortcutHelper.EXTRA_WORKSPACE_ID) ?: return
        val presetId = intent.getStringExtra(ShortcutHelper.EXTRA_PRESET_ID) ?: return
        timerViewModel.applyPresetById(workspaceId, presetId)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Composable
private fun AppNavigation(
    timerViewModel: TimerViewModel,
    settingsViewModel: SettingsViewModel
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val timerState by timerViewModel.uiState.collectAsState()
    val settingsState by settingsViewModel.uiState.collectAsState()
    val editState by settingsViewModel.editState.collectAsState()

    // Keep settingsViewModel aware of timer running state
    LaunchedEffect(timerState.running) {
        settingsViewModel.setTimerRunning(timerState.running)
    }

    // Material 3 shared-axis X: Settings comes in from the right and leaves the same way.
    val axisOffset = with(LocalDensity.current) { 30.dp.roundToPx() }
    NavHost(
        navController = navController,
        startDestination = "timer",
        enterTransition = { Motion.sharedAxisX(forward = true, offsetPx = axisOffset).targetContentEnter },
        exitTransition = { Motion.sharedAxisX(forward = true, offsetPx = axisOffset).initialContentExit },
        popEnterTransition = { Motion.sharedAxisX(forward = false, offsetPx = axisOffset).targetContentEnter },
        popExitTransition = { Motion.sharedAxisX(forward = false, offsetPx = axisOffset).initialContentExit }
    ) {
        composable("timer") {
            TimerScreen(
                uiState = timerState,
                onWorkspaceSelect = timerViewModel::selectWorkspace,
                onProjectSelect = timerViewModel::setSelectedProject,
                onDescriptionChange = timerViewModel::setDescription,
                onExtraValueChange = timerViewModel::setExtraValue,
                onStart = { timerViewModel.startTimer(it) },
                onStop = timerViewModel::stopTimer,
                onUpdateStartTime = timerViewModel::updateStartTime,
                onNavigateToSettings = { navController.navigate("settings") },
                onClearError = timerViewModel::clearError,
                onRetryStop = { timerViewModel.stopTimer() },
                onLoadRunningTimer = timerViewModel::loadRunningTimer,
                onStopOtherRunningTimer = timerViewModel::stopOtherRunningTimer,
                onPlusButtonClicked = timerViewModel::onPlusButtonClicked,
                onRefreshAll = timerViewModel::refreshAll,
                onOpenEditTimer = timerViewModel::openEditTimer,
                onCloseEditTimer = timerViewModel::closeEditTimer,
                onQuickSaveDescription = timerViewModel::quickSaveDescription,
                onUpdateTimerDetails = timerViewModel::updateTimerDetails,
                onConfirmDelete = { pageId, title, isRunning -> timerViewModel.confirmDelete(pageId, title, isRunning) },
                onCancelDelete = timerViewModel::cancelDelete,
                onDeleteEntry = timerViewModel::deleteEntry,
                onOpenUrl = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                onApplyPreset = timerViewModel::applyPreset,
                onStartFromRecentEntry = timerViewModel::startFromRecentEntry,
                onDismissDeletedPage = timerViewModel::dismissDeletedPage,
                onClearInfoToast = timerViewModel::clearInfoToast,
                onConfirmAlreadyRunning = timerViewModel::onConfirmAlreadyRunning,
                onCancelAlreadyRunning = timerViewModel::onCancelAlreadyRunning,
                onNewPreset = timerViewModel::openNewPreset,
                onEditPreset = timerViewModel::openEditPreset,
                onPresetEditorWorkspace = timerViewModel::setPresetEditorWorkspace,
                onPresetEditorName = { timerViewModel.updatePresetEditor(name = it) },
                onPresetEditorPageTitle = { timerViewModel.updatePresetEditor(pageTitle = it) },
                onPresetEditorExtra = timerViewModel::setPresetEditorExtra,
                onSavePresetEditor = timerViewModel::savePresetEditor,
                onClosePresetEditor = timerViewModel::closePresetEditor,
                onDeletePresetEditor = timerViewModel::deletePresetEditor,
                onMovePreset = timerViewModel::movePreset
            )
        }

        composable("settings") {
            // Reload workspaces/settings into the timer screen whenever Settings is left,
            // regardless of how (back arrow, system back or gesture) — no app restart needed.
            DisposableEffect(Unit) {
                onDispose { timerViewModel.refreshSettings() }
            }
            SettingsScreen(
                uiState = settingsState,
                editState = editState,
                isEditValid = editState?.let { settingsViewModel.isEditValid(it) } ?: false,
                onBack = {
                    navController.popBackStack()
                },
                onSignIn = {
                    // Notion's sign-in page, in the browser. It comes back through the app link
                    // (handleSignInLink).
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(settingsViewModel.signInUrl())))
                    }.onFailure { e -> android.util.Log.e("MainActivity", "No browser to sign in with", e) }
                },
                onSignOut = settingsViewModel::signOut,
                onMoveWorkspace = settingsViewModel::moveWorkspace,
                onEditWorkspace = settingsViewModel::openEditWorkspace,
                onDeleteWorkspace = settingsViewModel::deleteWorkspace,
                onAddWorkspace = settingsViewModel::openAddWorkspace,
                onCloseEdit = settingsViewModel::closeWorkspaceEdit,
                onSaveWorkspace = settingsViewModel::saveWorkspace,
                onWorkspaceNameChange = settingsViewModel::setWorkspaceName,
                onTimeEntriesDbChange = settingsViewModel::setTimeEntriesDb,
                onRefreshDatabases = settingsViewModel::loadDatabases,
                onDatePropChange = settingsViewModel::setDateProp,
                onStatusPropChange = settingsViewModel::setStatusProp,
                onStatusRunningChange = settingsViewModel::setStatusRunning,
                onStatusDoneChange = settingsViewModel::setStatusDone,
                onRoundingChange = settingsViewModel::setRounding,
                onDescriptionRequiredChange = settingsViewModel::setDescriptionRequired,
                onColorChange = settingsViewModel::setColor,
                onAddExtraProp = settingsViewModel::addExtraProp,
                onRemoveExtraProp = settingsViewModel::removeExtraProp,
                onExtraPropNameChange = settingsViewModel::setExtraPropName,
                onExtraPropRequiredChange = settingsViewModel::setExtraPropRequired,
                onExtraPropDefaultChange = settingsViewModel::setExtraPropDefault,
                onThemeModeChange = settingsViewModel::setThemeMode,
                onDynamicColorChange = settingsViewModel::setDynamicColor,
                onSnackbarDismiss = settingsViewModel::clearSnackbar,
                onLocaleChange = { locale ->
                    settingsViewModel.setLocale(locale) {
                        (context as? android.app.Activity)?.recreate()
                    }
                },
                onDateFormatChange = settingsViewModel::setDateFormat,
                onTimeFormatChange = settingsViewModel::setTimeFormat,
                onShowNotionLinkChange = settingsViewModel::setShowNotionLink,
                onShowArchiveActionChange = settingsViewModel::setShowArchiveAction,
                onSyncEnabledChange = settingsViewModel::setSyncEnabled,
                onDeleteSyncedSettings = settingsViewModel::deleteSyncedSettings
            )
        }
    }
}
