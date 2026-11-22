# Usage guide

This guide assumes the app is installed ([installation.md](installation.md)). Steps are the same on Windows and Android unless noted. On Android, **Settings** is the gear icon at the top-right of the timer screen.

## 1. Prepare Notion

### 1.1 The Time Entries database

The app needs a database with at least:

| Column | Type | Used for |
|---|---|---|
| **Name** | Title | What you worked on (empty description → today's date) |
| **Time** | Date | Start and end |
| **Status** | Status | One option for running (e.g. *In progress*), one for finished (e.g. *Done*) |

To create one:
1. Make a new page.
2. Type `/database` › **Database - Inline**.
3. Add a **Date** column `Time` and a **Status** column `Status`. A Status column comes with *Not started*, *In progress* and *Done*.

**Optional: link entries to projects.** Add a **Relation** column pointing at your projects or clients database. The app detects it, keeps the link while you edit a running timer, and copies it when you restart an old entry. It never removes a link set in Notion.

### 1.2 Create a connection and give it access

Only a **Workspace Owner** can create a connection.

1. Go to [app.notion.com/developers](https://app.notion.com/developers) › **Internal connections** › **Create a new connection**.
2. Name it (e.g. `Time Tracker`) and pick the workspace.
3. On **Configuration**, enable **Read content**, **Update content** and **Insert content**.
4. Copy the **Installation access token** (starts with `ntn_` or `secret_`). Treat it like a password.
5. Open the page that contains your database › **•••** › **Connections** › **Add connection** › `Time Tracker` › **Confirm**.

Everything inside that page is included.

## 2. Connect the app

Until you are signed in and a workspace is set, the timer screen shows **Connect to Notion** (or **Add a workspace**) with an **Open settings** button instead of the timer.

### 2.1 Sign in

**Settings** › **Sign in with Notion**. Your browser opens Notion's page: choose your workspace, tick the pages and databases the app may use (your Time Entries database, the projects database, and a page for settings sync), and click **Allow**. Back in the app you see **Signed in to <workspace>**.

To share more (or fewer) pages later: **Settings** › **Choose pages**. **Sign out** forgets the sign-in on this device.

### 2.2 Add a workspace

A *workspace* is one Time Entries database plus how you use it. Add several to track in several databases (e.g. work and a side project).

1. **Settings** › **Workspaces** › **+ Add workspace**.
2. Fill in:

| Field | Value |
|---|---|
| **Name** | Shown on the timer and presets, e.g. `Work` |
| **Time Entries database** | Your database. If the list is empty, click **Refresh**; if still empty, the connection lacks access (step 1.2.5). |
| **Date range field** | `Time` |
| **Status field** | `Status` |
| **Running status option** | *In progress* |
| **Completed status option** | *Done* |
| **Extra fields** *(optional)* | Up to five more columns shown on the timer (text, number, select, multi-select, status, date, checkbox, URL, email, phone, people, relation, file URL). Tick **Required** to block stopping until filled. |
| **Description required** | Block stopping until a description is entered |
| **Time rounding** | No rounding, 5, 10 or 15 minutes |
| **Workspace colour** | Colours the timer, preset chips, Windows tray icon and the clock on the Android launcher shortcuts (long-press the app icon) |

3. **Save**, then go back to the timer with the **←** arrow.

To change the order of your workspaces, drag one by its handle (Android) or use its up and down buttons (Windows). To remove one, open it and tap **Remove workspace** at the bottom (Android), or click its bin (Windows); both ask first. Its presets go with it; your time entries stay in Notion.

## 3. Track time

### Start

1. Choose a **Workspace**: tap or click its name above the timer.
2. Type what you are working on in **Description**. Leave it empty to title the entry with today's date.
3. Forgot to start the timer earlier? Pick a **Start time**. Empty means now; a time later than now counts as yesterday's.
4. Fill in extra fields.
5. Press **Start**.

A Notion row appears with the running status. On Windows the tray stopwatch turns the workspace colour (red without one). On Android a notification shows the running timer.

### While running

| Action | Effect in Notion |
|---|---|
| Edit the description | Title saved shortly after you stop typing (right away on **Enter** or when you click away) |
| Change an extra field | Saved shortly after you stop typing |
| Change **Start time** | Start corrected, e.g. if you forgot to press Start |
| Fill **End time** | Stops the timer at that time |

### Stop

Press **Stop**. Unsaved edits are flushed first. The row gets its end time and the *done* status. With rounding, start and end are rounded to the nearest step and the entry lasts at least one step.

Also possible:
- **Windows:** tray icon › right-click › **Stop timer**.
- **Android:** **Stop** in the notification, even with the app closed.

### Lists on the timer screen

- **Active timers:** entries running in Notion, e.g. started on your phone. Click one to continue it on this device.
- **Recent time entries:** the last finished entries. ▶ starts a new timer with the same details. If they couldn't be loaded (for example offline), the section says so with **Retry**.
- **Refresh:** both lists reload automatically every minute, or on demand with the refresh button (bottom on Windows, top on Android).

If the timer is stopped on another device, this one clears it within a minute: **Timer was stopped on another device.**

## 4. Presets

A preset remembers a workspace, a title, a description and extra field values, and starts a timer in one click.

**Create:** click the pen button after the preset chips (or **New preset** when you have none yet) › **New preset**. The editor starts from what is in the timer form: pick the workspace, enter a name (required, it's the chip label), the description and extra field values › **Save**.

**Edit, reorder, remove:** the pen button after the chips opens **Manage presets**. Each preset has a pen to edit it. To change the order, drag a preset by its handle on Android, or use its up and down buttons on Windows. **Remove preset** is at the bottom of the edit screen.

Required extra fields (marked *) may stay empty in a preset: they only have to be filled in before the timer stops.

**Use:** click a preset chip at the top of the timer screen. If a timer is running, choose **Stop & start new** or **Cancel**. Chips appear once a key is set.

**Without opening the app:**
- **Windows:** right-click the tray icon (first 10 presets), or launch the app with `--preset=<id>` (see [api.md](api.md#4-windows-command-line-arguments)).
- **Android:** long-press the app icon (up to 4 presets).

**Manage:** click the pencil after the chips. Reorder (drag handles on Android, arrows on Windows) or open a preset to edit or remove it, then **Close**.

## 5. Same settings on every device

Settings sync copies these between your devices, through Time Tracker's sign-in server:
- workspaces, extra fields, presets, rounding and colours;
- appearance, language and date/time format.

It does **not** copy your Notion sign-in or device-only options (**Keep window on top**, **Start with Windows**, Android dynamic colour).

Sync is on by default: sign in with the same Notion account in the same workspace on each device, and the settings follow. A new device with no workspaces takes them from the server. To stop it on one device: **Settings** › **Sync** › untick **Sync settings between my devices**. **Delete synced settings** removes the copy on the server and turns sync off on that device.

Changes reach other devices within about a minute. If the same settings changed on two devices, the most recent save wins as a whole.

## 6. Other settings

| Setting | Platform | Effect |
|---|---|---|
| Theme | both | Auto, light or dark |
| Dynamic colour | Android 12+ | Material You colours from the wallpaper |
| Language | both | English, Dutch, French, German, Spanish, Italian, or system |
| Date / time format | both | `DD/MM/YYYY`, `MM/DD/YYYY`, `YYYY-MM-DD`; 24h or 12h |
| Keep window on top | Windows | Mini window stays above others |
| Start with Windows | Windows | Starts hidden in the tray at login |
| Show "Open in Notion" link / archive action | both | Extra actions on active and recent entries (archive moves the page to Notion's Trash) |

## 7. Troubleshooting

| What you see | Fix |
|---|---|
| **Connection failed** on test | Re-copy the token without spaces around it |
| **No databases found** | The connection has no access to the page (step 1.2.5), then **Refresh** |
| **Fill in required fields before stopping.** | Fill the red fields, press **Stop** again |
| **Page not found in Notion** | The entry was deleted. **Close**, or restore it from Notion's **Trash** |
| Window closed but app won't reopen (Windows) | It's in the tray: **^** next to the clock › right-click › **Open** |
| **Windows protected your PC** | **More info** › **Run anyway** (unsigned installer) |
| **App not installed** (Android) | Allow installs from the Files app; uninstall a self-built copy first |
| No notification (Android) | Settings › Apps › Time Tracker › Notifications › on |
| No preset shortcuts (Android) | Create a preset and open the app once, then long-press the icon |
