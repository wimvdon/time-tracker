# Privacy policy

_Last updated: 2026-11-06_

Time Tracker (Windows and Android; published as "Time Tracker for Notion") has no analytics, advertising or crash reporting. Its only server of its own is the sign-in and sync server described below.

## What the apps send, and to whom

- **Notion (api.notion.com).** When you sign in with Notion, you choose on Notion's own page which pages and databases the app may use. The apps then read those, and create and update time entries in them.
- **The sign-in and sync server (timetracker-signin.wim.to)**, a small Cloudflare Worker run for this app.
  - *Signing in:* it receives Notion's one-time sign-in code, or the app's renewal token when Notion asks the app to renew its sign-in, passes it to Notion together with the app's own secret, and returns Notion's answer (your new sign-in and the name of your Notion workspace). It does not keep these.
  - *Settings sync* (on unless you turn it off in Settings): it stores one copy of your synced settings: your workspaces (names, colours, Notion database IDs, property names, extra fields, presets with their names and default values), theme, language, date and time format, and the two Notion action switches. Not your time entries, not your Notion sign-in. To know whose settings they are, it asks Notion who your sign-in belongs to and files them under your Notion workspace ID and user ID. **Delete synced settings** in Settings removes the copy from the server and turns sync off on that device.
  - It keeps no logs of its own. Cloudflare, which runs it, sees the requests like any web host does (for example your IP address), under [Cloudflare's privacy policy](https://www.cloudflare.com/privacypolicy/).
- Nothing is sent anywhere else.
- Links you open from the app (for example a time entry in Notion) open in your browser or the Notion app, under Notion's privacy policy.

## What the apps store on your device

- **Your Notion sign-in** (the tokens Notion gave the app, and your workspace's name), encrypted: on Windows with Windows' own data protection (only your Windows user on that PC can read it); on Android with the Android Keystore (`EncryptedSharedPreferences`). If encryption is not available on a device, it is kept unencrypted in the app's private storage so the app keeps working. **Sign out** in Settings deletes it. An API key saved by an older version is deleted when you update.
- **Your settings**: workspaces (database IDs, property names, presets), the running timer, theme, language, window options, and whether settings sync is on.

Windows keeps this in `%APPDATA%\Time Tracker` (the Dev test build in `%APPDATA%\Time Tracker Dev`; versions up to 1.8 used `%APPDATA%\Time Tracker for Notion`, which is moved there on the first start after updating). Android keeps it in the app's private storage. Your Notion sign-in is excluded from Android's cloud backup and device-to-device transfer; your other settings can be included in Android's backup if you turned that on.

Uninstalling the Android app deletes everything. The Windows uninstaller keeps your settings folder, so a reinstall picks up where you left off; delete the folder above to remove them.

## Permissions (Android)

- **Internet**: to talk to Notion and, while signing in, the sign-in server.
- **Notifications** and a **foreground service**: to show the running timer with a Stop button while it runs.

If this changes, this file will be updated **before** the change is released.

Questions: open an issue at https://github.com/wimvdon/time-tracker-for-notion/issues.
