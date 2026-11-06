package to.wim.timetracker.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.*
import androidx.core.app.NotificationCompat
import to.wim.timetracker.LocaleHelper
import to.wim.timetracker.MainActivity
import to.wim.timetracker.TimeTrackerApp
import to.wim.timetracker.R
import to.wim.timetracker.data.models.ActiveTimer
import to.wim.timetracker.data.models.PropertyMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class TimerForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "notion_timer_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START_TIMER"
        const val ACTION_STOP = "ACTION_STOP_TIMER"
        const val ACTION_SYNC_TIMERS = "ACTION_SYNC_TIMERS"
        const val ACTION_TIMER_STOPPED = "to.wim.timetracker.TIMER_STOPPED"
        const val EXTRA_PAGE_ID = "extra_page_id"
        const val EXTRA_ROUNDED_START = "extra_rounded_start"
        const val EXTRA_RAW_START = "extra_raw_start"
        const val EXTRA_WORKSPACE_NAME = "extra_workspace_name"
        const val EXTRA_DESCRIPTION = "extra_description"
        const val EXTRA_ROUNDING_MINUTES = "extra_rounding_minutes"
        const val EXTRA_TITLE_PROP = "extra_title_prop"
        const val EXTRA_DATE_PROP = "extra_date_prop"
        const val EXTRA_STATUS_PROP = "extra_status_prop"
        const val EXTRA_STATUS_DONE = "extra_status_done"
        const val EXTRA_WORKSPACE_COLOR = "extra_workspace_color"

        fun buildStartIntent(
            context: Context,
            timer: ActiveTimer,
            workspaceName: String,
            roundingMinutes: Int,
            workspaceColor: String? = null
        ) = Intent(context, TimerForegroundService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_PAGE_ID, timer.notionPageId)
            putExtra(EXTRA_ROUNDED_START, timer.roundedStart)
            putExtra(EXTRA_RAW_START, timer.rawStart)
            putExtra(EXTRA_WORKSPACE_NAME, workspaceName)
            putExtra(EXTRA_DESCRIPTION, timer.description)
            putExtra(EXTRA_ROUNDING_MINUTES, roundingMinutes)
            putExtra(EXTRA_TITLE_PROP, timer.propertyMap.timeEntryTitleProp)
            putExtra(EXTRA_DATE_PROP, timer.propertyMap.timeEntryDateProp)
            putExtra(EXTRA_STATUS_PROP, timer.propertyMap.timeEntryStatusProp)
            putExtra(EXTRA_STATUS_DONE, timer.propertyMap.timeEntryStatusDone)
            putExtra(EXTRA_WORKSPACE_COLOR, workspaceColor)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var pageId: String = ""
    private var roundedStart: String = ""
    private var workspaceName: String = ""
    private var description: String = ""
    private var roundingMinutes: Int = 15
    private var workspaceColor: String? = null
    private var propertyMap: PropertyMap = PropertyMap()
    private var startMillis: Long = 0L
    /** Set when a Stop from the notification could not reach Notion. */
    private var stopFailed: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateNotification()
            handler.postDelayed(this, 1000L)
        }
    }

    /**
     * Resources in the language chosen in Settings. A Service's own context
     * follows the *system* language — only the Activity is locale-wrapped —
     * so the notification's title and Stop button used to stay in the phone's
     * language even when the app was set to another one.
     */
    private var localised: Context = this
    private var localisedFor: String? = null
    private fun refreshLocalisedContext() {
        val repo = (application as? TimeTrackerApp)?.settingsRepository ?: return
        val code = runCatching { runBlocking { repo.localeFlow.first() } }.getOrDefault("system")
        if (code == localisedFor) return
        localised = LocaleHelper.wrap(this, code)
        localisedFor = code
    }

    override fun onCreate() {
        super.onCreate()
        refreshLocalisedContext()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Picks up a language switch made while the timer was already running.
        refreshLocalisedContext()
        when (intent?.action) {
            ACTION_START -> {
                pageId = intent.getStringExtra(EXTRA_PAGE_ID) ?: ""
                roundedStart = intent.getStringExtra(EXTRA_ROUNDED_START) ?: ""
                workspaceName = intent.getStringExtra(EXTRA_WORKSPACE_NAME) ?: ""
                description = intent.getStringExtra(EXTRA_DESCRIPTION) ?: ""
                roundingMinutes = intent.getIntExtra(EXTRA_ROUNDING_MINUTES, 15)
                workspaceColor = intent.getStringExtra(EXTRA_WORKSPACE_COLOR)
                propertyMap = PropertyMap(
                    timeEntryTitleProp = intent.getStringExtra(EXTRA_TITLE_PROP) ?: "",
                    timeEntryDateProp = intent.getStringExtra(EXTRA_DATE_PROP) ?: "",
                    timeEntryStatusProp = intent.getStringExtra(EXTRA_STATUS_PROP) ?: "",
                    timeEntryStatusDone = intent.getStringExtra(EXTRA_STATUS_DONE) ?: ""
                )

                val rawStart = intent.getStringExtra(EXTRA_RAW_START) ?: ""
                startMillis = parseIso(rawStart)

                val elapsed = (System.currentTimeMillis() - startMillis) / 1000L
                startForegroundCompat(buildNotification(elapsed.coerceAtLeast(0L)))
                handler.removeCallbacks(ticker)
                handler.post(ticker)
            }
            ACTION_STOP -> {
                handleStop()
            }
            ACTION_SYNC_TIMERS -> {
                // Only the timer currently shown in the running view (the active timer)
                // gets a permanent notification. Background running timers do not.
                val activePageId = intent.getStringExtra("active_page_id")
                if (activePageId != null) {
                    pageId = activePageId
                    roundedStart = intent.getStringExtra("active_start_iso") ?: ""
                    startMillis = runCatching { parseIso(roundedStart) }.getOrDefault(0L)
                    workspaceName = intent.getStringExtra("active_workspace_name") ?: ""
                    description = intent.getStringExtra("active_title") ?: ""
                    workspaceColor = intent.getStringExtra("active_workspace_color")
                    roundingMinutes = intent.getIntExtra("active_rounding_minutes", 15)
                } else {
                    pageId = ""
                    startMillis = 0L
                }

                if (pageId.isBlank()) {
                    // This may have been launched via startForegroundService(); we must
                    // promote to the foreground before stopping, otherwise the system
                    // throws ForegroundServiceDidNotStartInTimeException and crashes the app.
                    startForegroundCompat(buildNotification(0L))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION") stopForeground(true)
                    }
                    stopSelf()
                } else {
                    val elapsed = (System.currentTimeMillis() - startMillis) / 1000L
                    startForegroundCompat(buildNotification(elapsed.coerceAtLeast(0L)))
                    handler.removeCallbacks(ticker)
                    handler.post(ticker)
                }
            }
        }
        return START_STICKY
    }

    private fun handleStop() {
        handler.removeCallbacks(ticker)
        stopFailed = false
        serviceScope.launch {
            val app = application as TimeTrackerApp
            if (pageId.isNotBlank()) {
                val rounding = roundingMinutes
                val rawStartMs = startMillis
                val startMs = if (rounding == 0) rawStartMs else roundToNearest(rawStartMs, rounding)
                val nowMs = System.currentTimeMillis()
                val endMs = if (rounding == 0) nowMs
                else {
                    val minEnd = startMs + rounding * 60_000L
                    val rounded = roundToNearest(nowMs, rounding)
                    if (rounded < minEnd) minEnd else rounded
                }
                val startISO = toNotionDateTime(startMs)
                val endISO = toNotionDateTime(endMs)

                val stopped = runCatching {
                    app.notionRepository.stopTimeEntry(
                        pageId = pageId,
                        startISO = startISO,
                        endISO = endISO,
                        propertyMap = propertyMap
                    )
                }
                if (stopped.isFailure) {
                    // The entry is still running in Notion. Clearing the local
                    // timer here would lose it silently — offline, that meant
                    // one tap on Stop and the entry ran on forever with nobody
                    // tracking it. Keep the timer and say so on the
                    // notification instead, so it can be stopped again.
                    android.util.Log.e("TimerForegroundService", "Stop failed", stopped.exceptionOrNull())
                    stopFailed = true
                    val elapsed = (System.currentTimeMillis() - startMillis) / 1000L
                    startForegroundCompat(buildNotification(elapsed.coerceAtLeast(0L)))
                    handler.removeCallbacks(ticker)
                    handler.post(ticker)
                    return@launch
                }
                val activeTimer = app.settingsRepository.getActiveTimer()
                if (activeTimer != null && activeTimer.notionPageId == pageId) {
                    app.settingsRepository.clearActiveTimer()
                }
                // Explicit package: only this app's own receiver (MainActivity) gets the page ID.
                sendBroadcast(Intent(ACTION_TIMER_STOPPED).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_PAGE_ID, pageId)
                })
            }
            stopSelf()
        }
    }

    private fun updateNotification() {
        if (pageId.isBlank()) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val elapsed = (System.currentTimeMillis() - startMillis) / 1000L
        nm.notify(NOTIFICATION_ID, buildNotification(elapsed.coerceAtLeast(0L)))
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(elapsedSecs: Long): Notification {
        val contentTitle = if (description.isNotBlank())
            "⏱ $workspaceName – $description" else "⏱ $workspaceName"
        val elapsedStr = formatElapsed(elapsedSecs)
        val contentText = if (stopFailed) {
            "$elapsedStr — ${localised.getString(R.string.error_stop_from_notification_failed)}"
        } else {
            elapsedStr
        }

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TimerForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            localised.getString(R.string.notification_action_stop),
            stopIntent
        ).build()

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(stopAction)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        workspaceColor?.ifBlank { null }?.let { colorStr ->
            runCatching {
                val parsedColor = Color.parseColor(if (colorStr.startsWith("#")) colorStr else "#$colorStr")
                builder.setColor(parsedColor)
                builder.setColorized(true)
            }
        }

        // No progress bar: a timer has no target time, so there is nothing to fill.
        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            localised.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = localised.getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }



    override fun onBind(intent: Intent?) = null

    private fun formatElapsed(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }



    private fun roundToNearest(ms: Long, minutes: Int): Long {
        val intervalMs = minutes * 60_000L
        return Math.round(ms.toDouble() / intervalMs) * intervalMs
    }

    private fun parseIso(iso: String): Long {
        return java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    }

    // Seconds are dropped to match the rest of the app (and the desktop one).
    private fun toNotionDateTime(ms: Long): String {
        val odt = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
            .withSecond(0).withNano(0)
        return java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(odt)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}

