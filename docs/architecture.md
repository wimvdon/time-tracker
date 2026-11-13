# Architecture

## General design

The repository holds two independent clients with the same feature set. Each client calls the Notion REST API directly with the user's own Notion sign-in. The only server of its own is a small Cloudflare Worker (`worker/`) that holds Notion's client secret ([Sign in with Notion](#sign-in-with-notion)) and keeps each user's synced settings ([Settings sync](#settings-sync)).

```
                     ┌────────────────────────────────────────────┐
                     │                Notion API                  │
                     │  Time Entries DB(s)  ·  Projects DB  ·     │
                     └───────────▲───────────────────▲────────────┘
                                 │ HTTPS             │ HTTPS
           ┌─────────────────────┴───┐   ┌───────────┴────────────────────┐
           │ Windows (Electron)      │   │ Android                        │
           │ main: notionClient.cjs  │   │ NotionClient → NotionRepository│
           │   ▲ IPC (preload)       │   │   ▲                            │
           │ renderer: React+Zustand │   │ TimerViewModel / Settings VM   │
           │ tray, window, store     │   │ Compose UI · Foreground service│
           └─────────────────────────┘   └────────────────────────────────┘
```

Notion plays two roles:

1. **System of record:** every timer is a page in a user-chosen *Time Entries* database.
2. **Shared runtime state:** a timer is "running" when its status property equals the workspace's *running* option. Any device can list, adopt or stop it.

The two codebases share no code. They agree on Notion page shapes and the sync payload format, and both read each other's variants (locale tags, preset value shapes).

## Domain model

| Concept | Meaning |
|---|---|
| **Workspace** | One Time Entries database plus its configuration. It holds a `propertyMap` (which title, date, status and relation properties to use, and which status options mean *running* and *done*), an optional projects database, rounding minutes, extra fields (`extraProps`), presets, colour and *description required*. |
| **Time entry** | A Notion page. **Start:** title = description, date = `{start}`, status = *running*, optional relation, extra properties. **Stop:** date = `{start, end}` and status = *done*. |
| **Active timer** | Locally persisted pointer to the running page: page id, rounded and raw start, workspace, property map and extra values. It lets the app survive restarts. |
| **Preset** | A named description plus extra values in a workspace. One click selects the workspace, fills the form and starts. |

## Windows app components

| Component | File | Responsibility |
|---|---|---|
| Main process | `windows/electron/main.cjs` | Window: 340×620 content area (less on a short screen), non-resizable, always on top optional, Mica on Win 11 22H2+. Tray icon tinted with the running workspace colour (in-process PNG encoder). Tray menu: open, stop, up to 10 presets, quit. Also: single-instance lock, `--preset=` / `--hidden` arguments, start with Windows, electron-store. Registers all IPC handlers. |
| Notion client | `windows/electron/notionClient.cjs` | Every REST call: retry with backoff on 429/5xx (honours `Retry-After`), pagination, sync payload encoding. |
| Preload bridge | `windows/electron/preload.cjs` | Exposes `window.notionAPI` through `contextBridge`. Queues preset requests that arrive before the renderer subscribes. |
| Store | `windows/src/store/useStore.js` | Zustand store with all app logic: init and migrations, start/stop/update timer, running and recent lists, presets, sync, theme, toasts, and the 60-second refresh loop. |
| Views | `windows/src/views/Timer.jsx`, `Settings.jsx` | The two screens. |
| Pure helpers | `windows/src/lib/*.js` | Rounding, date formatting, Notion value builders and parsers, colour variants, i18n, schema helpers, preset titles. All unit-tested. |

## Android app components

| Component | Package / file | Responsibility |
|---|---|---|
| `TimeTrackerApp` | root | Creates the singletons: `SettingsRepository`, `NotionRepository`, `SettingsSyncer` |
| `NotionClient` | `data/api` | OkHttp calls, same retry policy as Windows. Request JSON keeps `null`s, because Notion reads `null` as "clear". |
| `NotionRepository` | `data/repository` | Passes the sign-in (`NotionSession`) to every call. Caches schemas, related pages and users for 60 s, and serves stale data when a refetch fails. |
| `SettingsRepository` | `data/repository` | DataStore `notion_settings` for workspaces and the active timer (JSON via kotlinx.serialization) and display settings. The Notion sign-in is in EncryptedSharedPreferences. |
| `NotionSession` | `data/repository` | Sign in with Notion and token renewal (same rules as Windows `notionAuth.cjs`); `AuthClient` (`data/api`) talks to the sign-in server |
| `SettingsSyncer` | `data/repository` | Settings sync through the worker (`SyncClient` in `data/api`, rule in `SyncDecision`), shared by Settings and the timer refresh loop |
| `TimerViewModel` / `SettingsViewModel` | `viewmodel` | All screen logic: start/stop, rounding, presets, running and recent lists, 60 s refresh |
| `TimerForegroundService` | `service` | Ongoing notification with live elapsed time and a **Stop** action. Stops the entry in Notion itself, so it works with the app closed. |
| `ShortcutHelper` | root | Up to 4 presets as dynamic launcher shortcuts; each icon is the app's clock with the dial in its workspace colour (`ShortcutIconColor`, darkened so the white hand stays readable), or the app icon when the workspace has no colour. Refreshed whenever workspaces are saved, synced or loaded |
| UI | `ui/screens`, `ui/components`, `ui/theme` | Compose screens, dialogs, workspace colour theming |

## System flow

### Start a timer

1. The user picks a workspace and types a description, or clicks a preset, a recent entry's ▶, a tray item or a launcher shortcut.
2. The client waits until the workspace's extra field definitions have loaded. Otherwise extra values from a preset would be silently dropped.
3. If a timer already runs, the user confirms **Stop & start new**.
4. `POST /pages` creates the entry. The description becomes the title (empty → the date in the chosen format), the date gets the start, the status is *running*, plus the relation and extra fields.
5. The start time is the exact current time. Rounding applies at stop: if the workspace rounds, both start and end are rounded to the nearest N minutes, and the duration is at least N minutes.
6. The active timer is persisted locally, ticking starts, the tray icon is tinted, and on Android the foreground service is started.

### While running

- Until the user is signed in and at least one workspace is set, the timer screen shows a full-screen "setup needed" state instead of the timer ("Connect to Notion", then "Add a workspace"), with a button to Settings. The rule is shared: `src/lib/setupStep.js` and `viewmodel/SetupStep.kt`.
- Description and extra field edits are written with `PATCH /pages/{id}` (description 800 ms after the last keystroke and on Enter or blur, extra fields 600 ms after the last change). An emptied description is only written on Enter/Done, Stop or (Windows) blur; the rule is shared: `src/lib/descriptionAutoSave.js` and `viewmodel/DescriptionAutoSave.kt`.
- Changing **Start time** rewrites the date, and the running time (on screen and, on Android, in the notification, whose Stop button then uses the new start) counts from the new start right away (`viewmodel/RunningClock.kt`). Filling **End time** stops at that time.

### Stop

1. Pending edits are flushed first.
2. `PATCH /pages/{id}` sets the date `{start, end}` and the *done* status.
3. If the call fails, the timer is kept locally and the error is shown, so a timer is never lost offline. On Android the notification switches to a failed state and **Stop** can be retried.

### 60-second refresh loop (both apps)

Every minute, when signed in with workspaces:

1. Query each workspace's database for pages with the *running* status, and the last 15 *done* entries.
2. If the local active timer is no longer running in Notion, clear it: "Timer was stopped on another device."
3. If the active page no longer exists, show the "Page not found" dialog.
4. Re-read the active page's properties so edits made in Notion appear locally.
5. Run a settings sync, unless it is turned off on this device.

Lists are merged by page id to avoid flicker. Timers stopped locally within the last minute are filtered out of the running list, because Notion's query can still return them briefly.

### Sign in with Notion

Notion's sign-in (OAuth) needs a client secret, which can't be kept inside an app people download.
A small Cloudflare Worker (`worker/`, at `https://timetracker.wim.to`) holds it. It stores
nothing: what it needs travels in a signed `state`. Setup: [notion-sign-in-setup.md](notion-sign-in-setup.md).
Requests: [api.md](api.md#5-sign-in-server).

```
 App                          Browser                   Sign-in server          Notion
  │ random verifier             │                           │                     │
  │── open /start?challenge ───►│──────────────────────────►│ signs state         │
  │                             │◄── redirect to consent ───│                     │
  │                             │── user picks pages, Allow ─────────────────────►│
  │                             │◄────────────── /callback?code&state ────────────│
  │◄── code + state ────────────│◄── to the app ────────────│ checks state        │
  │── POST /token code, state, verifier ───────────────────►│ sha256(verifier)    │
  │                             │                           │   = challenge? ────►│ code + secret
  │◄────────────────────────── access + refresh token ──────│◄────────────────────│
```

- **Back to the app.** Windows listens on a free port on `127.0.0.1` for one `/callback`
  (`electron/notionAuth.cjs`). Android receives the app link `timetrackerfornotion://auth` in
  `MainActivity` (the Dev build has `timetrackerfornotion-debug://`, so it never opens the
  released app).
- **The verifier** binds the code to the app that started the sign-in: another app that catches
  the link (or a page that calls the local port) can't swap the code without it. Android keeps the
  verifier in the encrypted preferences while the browser is open, in case Android ends the app.
- **Renewal.** When Notion answers 401, the token is renewed once through `/refresh` and the call
  is retried; requests failing at the same time share one renewal. If Notion refuses the refresh
  token, the app is signed out and says *Your Notion sign-in has expired*. If the server or the
  network has trouble, the sign-in is kept and only that call fails.
- **Choose pages** signs in again: Notion's consent page is where the shared pages are picked.
- **Upgrade.** An API key saved by an older version is deleted on start; the user signs in once.
  Workspaces and presets are kept.

### Settings sync

Workspaces, presets and display preferences sync between devices through the worker
(`worker/src/sync.js`, `GET`/`PUT`/`DELETE /settings`). The app sends its Notion token; the worker
asks Notion who it belongs to and keeps one copy per Notion workspace and user, in a Durable
Object per user (so two devices writing at once are handled one after the other). It is on by
default; **Settings › Sync** turns it off per device, and **Delete synced settings** removes the
server copy and turns it off on that device.

Every sync (at start, after signing in, every 60 seconds, after a settings change) fetches the
server copy and applies one rule, shared by `src/lib/syncDecision.js` and `SyncDecision.kt`:

- **Server newer** than the local `settingsUpdatedAt`: apply it (workspaces, theme, locale, date
  and time format, link and archive toggles).
- **Server empty:** push this device's settings, unless it has none at all (a new install never
  wipes another device's settings with an empty list).
- **Local edits not yet pushed** (`settingsUpdatedAt ≠ lastSyncedUpdatedAt`): push.
- **Otherwise:** nothing.

The server only accepts a push that is newer than what it has; otherwise it answers with its
copy, which the app applies. It is last-writer-wins for the whole payload. The Notion sign-in
and device-only settings (always on top, start with Windows, Android dynamic colour, sync on/off)
are never synced. The Notion sync page of earlier versions is no longer read; its settings
(`syncPageId`, …) are deleted on start.

### Timer notifications from Windows on the phone

While settings sync is on, a timer started, edited or stopped on Windows shows, updates or removes
the running-timer notification on the phones signed in to the same Notion user, through Firebase
Cloud Messaging (requests and message: [api.md](api.md#5-sign-in-server)).

```
 Windows (store:set activeTimer)       Sign-in server                Firebase        Phone
  │── POST /timer {active|stopped} ──►│ who? (Notion)                  │               │
  │                                   │── per registered phone ──────►│── data msg ──►│ TimerPushService
  │                                   │   (Google access token from   │               │  RemoteTimer.decide
  │                                   │    FCM_SERVICE_ACCOUNT)       │               │  → notification
```

- **Windows** (`electron/timerPush.cjs`): every save of `activeTimer` goes through `store:set`;
  `timerChange` turns the old and new value into *active* (start, new start time, description,
  project, switching timers) or *stopped*, and sends it in order, fire and forget.
  Push messages can arrive out of order on the phone, so a timer started right after a stop names
  the stopped one as the timer it replaces (`previousPageId`), and stops go at high priority like
  starts (a dozing phone holds normal-priority messages back).
- **Phone registration** (`PushRegistrar`): signed in with sync on → Firebase is asked for a push
  token (auto-init is off, so none exists before) and `PUT /devices`; sync off or sign-out →
  `DELETE /devices` and the token is deleted. At every app start and after each sign-in.
- **Phone** (`TimerPushService`, `RemoteTimer`): follows the other device's timer while it has
  none of its own or was already showing that one (or the one Windows switched away from or stopped just before); a timer
  started on the phone is never replaced. *Active* stores it as the active timer and starts the
  notification service; *stopped* clears it and removes the notification without writing to
  Notion. Stopping it from the phone's notification works as for its own timers (same workspace
  properties). An open timer screen follows through a broadcast; one opened later reads the stored
  timer.
- **Not guaranteed**: needs Google Play services, and a phone that was off or force-stopped
  catches up through the normal refresh when the app opens.

## Patterns used

- **Thin client, small backend.** Each user's own sign-in talks to their own workspace; the worker only swaps codes, renews tokens and keeps the synced settings.
- **Process isolation (Windows).** `contextIsolation: true`, `nodeIntegration: false`, navigation and `window.open` blocked, strict CSP in production, UI served from `app://bundle/`, every IPC message checked to come from the app's page, Electron fuses on. Notion requests (and so the token) stay in the main process; the renderer only learns whether it is signed in and to which workspace.
- **Single store / MVVM.** Zustand on Windows and ViewModel + `StateFlow` on Android hold all logic; views are declarative.
- **Repository + cache (Android).** `NotionRepository` hides the token and caches slow-changing metadata.
- **Optimistic local state with Notion reconciliation.** UI updates immediately; the refresh loop corrects it from Notion.
- **Schema migrations on read.** Older stored shapes (property maps, active timer, untitled presets) are migrated when loaded instead of with versioned migrations.

## Data storage

| Data | Windows | Android |
|---|---|---|
| Notion sign-in (tokens, workspace name) | electron-store, encrypted with `safeStorage` (Windows DPAPI, tied to the Windows user) | EncryptedSharedPreferences (AES-256). Falls back to recreating it, then to plain prefs if the keystore fails. Never backed up. |
| Workspaces, presets, active timer, preferences | electron-store `config.json` | DataStore `notion_settings` (JSON strings for structured values). Backed up. |
| Time entries | Notion | Notion |
| Shared settings | Sync server (`worker/`), one copy per Notion user | same |
| Push token for timer notifications | none | Firebase's own files; which token the server has in SharedPreferences `push_device`. Never backed up. On the server: the token per Notion user (`devices`). |
| Sync on or off | electron-store `syncEnabled` | DataStore `sync_enabled` |

**Android backup.** Android's own backup (to the Google account) and phone-to-phone transfer copy
only `notion_settings`, so workspaces, presets and preferences come back on a new phone; you only
sign in with Notion again. The rules are an allow list (`res/xml/data_extraction_rules.xml` for
Android 12+, `res/xml/backup_rules.xml` for Android 8-11, guarded by `BackupRulesTest`), so a file
added later is never backed up by accident. The Notion sign-in is left out: it is secret, and it is
encrypted with an Android Keystore key that can't be moved to another phone. File names are in
`data/repository/DataFiles.kt`. Windows has no automatic backup.
