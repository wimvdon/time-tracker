# Interfaces

The project exposes no HTTP API of its own. These are the interfaces it depends on or offers:

1. [Notion REST API calls](#1-notion-rest-api-calls) — what both apps send to Notion
2. [Synced settings format](#2-synced-settings-format) — the contract between the Windows and Android apps
3. [Windows IPC bridge](#3-windows-ipc-bridge-windownotionapi) — renderer ↔ main process
4. [Windows command-line arguments](#4-windows-command-line-arguments)
5. [Android intents](#5-android-intents)

## 1. Notion REST API calls

- **Base URL:** `https://api.notion.com/v1`. On Windows it can be overridden with `NOTION_API_BASE_URL`.
- **Headers:** `Authorization: Bearer <token>`, `Notion-Version: 2022-06-28`, `Content-Type: application/json`.
- **Implementations:** `windows/electron/notionClient.cjs` and `android/…/data/api/NotionClient.kt`. They are equivalent.

| Function | Method & path | Body / query | Returns |
|---|---|---|---|
| `testConnection(key, dbId?)` | `POST /databases/{dbId}/query` or `GET /users/me` | `{ page_size: 1 }` | throws on failure |
| `searchDatabases` | `POST /search` | `filter.object = database`, paginated | `[{ id, name }]` |
| `getDatabaseSchema(dbId)` | `GET /databases/{dbId}` | — | raw `properties` map |
| `fetchDatabasePages(dbId)` | `POST /databases/{dbId}/query` | paginated | `[{ id, name }]` (relation options) |
| `listUsers` | `GET /users?page_size=100` | paginated | `[{ id, name }]` (people options) |
| `createTimeEntry` | `POST /pages` | see below | `{ pageId }` |
| `stopTimeEntry` | `PATCH /pages/{id}` | date `{start, end}`, status = done | — |
| `fetchRunningTimers` | `POST /databases/{dbId}/query` | `status equals <running>`, sorted by date desc, paginated | `[{ pageId, title, startISO, url }]` |
| `fetchRecentEntries` | `POST /databases/{dbId}/query` | `status equals <done>`, date desc, `page_size: 15` | `[{ pageId, title, startISO, endISO, url }]` |
| `updatePage` | `PATCH /pages/{id}` | `{ properties }` | — |
| `getPageProperties` | `GET /pages/{id}` | — | raw `properties` |
| `deletePage` | `PATCH /pages/{id}` | `{ archived: true }` (moves to Notion Trash) | — |

### Create a time entry

```json
POST /pages
{
  "parent": { "database_id": "<timeEntriesDbId>" },
  "properties": {
    "<extra prop>": { "...": "typed value" },
    "Name":   { "title": [{ "text": { "content": "Monthly report" } }] },
    "Time":   { "date": { "start": "2026-10-11T09:03:00.000+02:00", "end": null } },
    "Status": { "status": { "name": "In progress" } },
    "Project": { "relation": [{ "id": "<projectPageId>" }] }
  }
}
```

The property names come from the workspace's `propertyMap`. Extra fields support `rich_text`, `number`, `select`, `multi_select`, `status`, `date`, `checkbox`, `url`, `email`, `phone_number`, `people`, `relation` and `files` (external URL). See `windows/src/lib/notionValues.js`.

### Error handling

- **Retries:** 429 and 5xx responses are retried up to 4 attempts. The wait honours `Retry-After`, otherwise it is `500 ms × 2^attempt`, clamped between 300 ms and 10 s.
- **Other errors:** the error carries Notion's `message`, or `Notion API error (HTTP <code>)`.
- **Not signed in:** when there is no sign-in, or Notion refuses it and it can't be renewed, the call fails as *not signed in* and the app shows **Not signed in to Notion. Sign in in Settings.**
- **Expired token (401):** renewed once through the sign-in server, then the call is retried.
- **Network failures (Windows):** `Network error reaching Notion: …`.
- **Deleted entries:** messages containing `404`, `object_not_found` or "could not find page" are treated as a deleted page. The UI shows **Page not found in Notion** instead of an error toast.

### Authentication

"Sign in with Notion" (OAuth, public connection). The user picks the pages the app may use on Notion's own page. The connection needs **Read**, **Update** and **Insert content**, and user information without email. The client secret lives only in the sign-in server (`worker/`); see section 5 and [notion-sign-in-setup.md](notion-sign-in-setup.md).

## 2. Synced settings format

One JSON document per Notion user, stored by the sync server (section 5, `/settings`). Until 2026-10-31 the same document lived in a `code` block on a Notion page; that page is no longer read.

```json
{
  "updatedAt": "2026-10-11T07:15:42.120Z",
  "workspaces": [
    {
      "id": "…",
      "name": "Work",
      "timeEntriesDbId": "…",
      "projectsDbId": "",
      "roundingMinutes": 15,
      "color": "#3B82F6",
      "descriptionRequired": false,
      "propertyMap": {
        "timeEntryTitleProp": "Name",
        "timeEntryDateProp": "Time",
        "timeEntryStatusProp": "Status",
        "timeEntryStatusRunning": "In progress",
        "timeEntryStatusDone": "Done",
        "timeEntryRelationProp": ""
      },
      "extraProps": [{ "propName": "Client", "type": "select", "required": true }],
      "presets": [{ "id": "…", "name": "Stand-up", "pageTitle": "Daily stand-up", "extraValues": {} }]
    }
  ],
  "theme": "auto",
  "locale": "nl-BE",
  "dateFormat": "DD/MM/YYYY",
  "timeFormat": "24h",
  "showNotionLink": true,
  "showArchiveAction": false
}
```

Rules both apps follow:

- **Validity:** `updatedAt` (ISO string) and `workspaces` (array) are required; the server refuses anything else.
- **Optional fields:** a missing optional field leaves the local value untouched. Android therefore keeps `null`s out of the payload (`explicitNulls = false` in `SyncPayloadJson.kt`).
- **Locale tags differ per app:** Windows writes `en-GB` and `nl-BE`, Android writes `en` and `nl`. Both map any tag by its language subtag.
- **Preset values:** each app normalizes the other's value shapes when reading them.
- **Never included:** the Notion sign-in and device-only settings (including whether sync is on).

## 3. Windows IPC bridge (`window.notionAPI`)

Defined in `windows/electron/preload.cjs`; handlers live in `windows/electron/main.cjs`.

| Renderer call | Channel | Kind |
|---|---|---|
| `getSettings()` / `saveSettings(patch)` | `store:get` / `store:set` | invoke |
| `searchDatabases`, `getDatabaseSchema`, `fetchDatabasePages`, `listUsers` | `notion:*` | invoke |
| `createTimeEntry`, `stopTimeEntry`, `updatePage`, `getPageProperties`, `deletePage` | `notion:create`, `notion:stop`, `notion:updatePage`, … | invoke |
| `fetchRunningTimers`, `fetchRecentEntries` | `notion:*` | invoke |
| `getNotionAccount()`, `signIn()`, `cancelSignIn()`, `signOut()` | `auth:*` | invoke |
| `onAuthChanged(cb)` | `auth:changed` (signed in, signed out, sign-in expired) | main → renderer |
| `getSyncedSettings()`, `putSyncedSettings(payload)`, `deleteSyncedSettings()` | `sync:*` (payload checked in the main process) | invoke |
| `openExternal(url)` | `shell:openExternal` (only `http(s)://` URLs) | invoke |
| `setAlwaysOnTop(bool)`, `setStartWithWindows(bool)` | `window:*` | invoke |
| `setNativeTheme(theme)` | `window:setTheme` | send |
| `setTimerRunning(running, color)` | `timer:state` (tray icon + menu) | send |
| `notifySettingsChanged()` | `settings:changed` (rebuild tray menu) | send |
| `onTrayStopTimer(cb)`, `onTrayApplyPreset(cb)`, `onAccentColorChanged(cb)` | `tray:stop-timer`, `tray:apply-preset`, `appearance:accent` | main → renderer |

The main process adds the Notion token to every `notion:*` call. The renderer never receives it: `auth:get` only says whether it is signed in and to which workspace. On disk the sign-in is stored encrypted (`notionAuthEncrypted`); `store:get` leaves it out and `store:set` refuses to write it.

Every channel is registered through `handle()` / `on()` in `main.cjs`, which reject or ignore messages that don't come from the app's own page (`app://bundle/…`, or the Vite dev server during `npm run dev`).

## 4. Windows command-line arguments

| Argument | Effect |
|---|---|
| `--hidden` | Start in the tray without showing the window (used by **Start with Windows**) |
| `--preset=<presetId>` | Start that preset's timer. If the app is already running, the single-instance handler forwards it to the running instance. |

Example desktop shortcut target:

```bat
"%LOCALAPPDATA%\Programs\time-tracker\Time Tracker.exe" --preset=3f2c…
```

The installation folder is `time-tracker` for new installs, `notion-timer` when updated from v1.8 or older; right-click the Start menu entry → Open file location to see it. Preset ids are visible in `%APPDATA%\Time Tracker\config.json` (obfuscated; the API key inside is encrypted).

## 5. Android intents

| Intent | Target | Effect |
|---|---|---|
| `ACTION_VIEW` with extras `preset_workspace_id`, `preset_id` | `MainActivity` | Starts the preset (used by the dynamic launcher shortcuts, max 4) |
| `ACTION_STOP_TIMER` | `TimerForegroundService` (not exported) | Notification **Stop** button: stops the entry in Notion |
| Broadcast `to.wim.timetracker.TIMER_STOPPED` (extra: page id) | `MainActivity` receiver | Tells a visible UI the service stopped the timer |

## 5. Sign-in server

`worker/src/index.js`, at `https://timetracker-signin.wim.to`. Sign-in stores nothing: `state` is signed with `STATE_SECRET` and expires after 15 minutes. Settings sync stores one payload per user (`worker/src/sync.js`, a SQLite-backed Durable Object per user).

| Request | What it does |
|---|---|
| `GET /start?platform=windows&port=<n>&challenge=<c>` | Signs `{platform, port, challenge, expiry}` into `state` and redirects to Notion's consent page. `platform` is `windows` (needs `port`, 1024–65535), `android` or `android-debug`. `challenge` is base64url(sha256(verifier)), 43 characters. |
| `GET /callback?code&state` (or `error`) | Notion comes back here. Checks `state`, then sends the browser to `http://127.0.0.1:<port>/callback` (Windows) or shows a page that opens the app link `timetrackerfornotion://auth` (Android; the Dev build uses `timetrackerfornotion-debug://auth`). |
| `POST /token` `{code, state, verifier}` | Checks that sha256(verifier) matches the challenge in `state`, then asks Notion for the tokens with the client secret. Answers `{access_token, refresh_token, workspace_id, workspace_name, bot_id}`, or 400 `{error}`. |
| `POST /refresh` `{refresh_token}` | Renews the tokens. 400 `{error: "invalid_grant"}` means: sign in again; 502 means Notion had trouble (the app keeps its sign-in). |
| `GET /settings` | With `Authorization: Bearer <Notion token>`. Answers `{payload}` (`null` when nothing is stored). |
| `PUT /settings` `{payload}` | Stores the payload if its `updatedAt` is newer than the stored one: `{accepted: true, payload}`. Otherwise 409 `{accepted: false, payload: <stored>}`, which the app applies. 400 for an invalid payload, 413 above 256 KB. |
| `DELETE /settings` | Removes the stored payload. |

For `/settings` the server asks Notion `GET /v1/users/me` who the token belongs to (cached for 5 minutes per token hash) and uses `<workspace_id>:<owner user id>` as the key. 401 means the token is expired (the app renews it and retries); 403 means it is not a user's sign-in.
