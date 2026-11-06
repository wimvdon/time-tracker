# Testing

## Running the tests

Windows app (Node's built-in test runner, no extra framework):

```bash
cd windows
npm ci
npm test            # node --test, unit tests in src/ and electron/
npm run lint        # ESLint
npm run check       # lint + unit tests + build
```

The tests import only pure modules and the Zustand store with a stubbed `window.notionAPI`, so they run without Electron. On a fresh checkout, `npm install --ignore-scripts` is enough and skips downloading the Electron binary.

CI (`.github/workflows/ci.yml`) runs lint and `npm test` on every pull request and push to `main`, and the release workflow runs them again before building the installer. A failing test blocks the merge and the release.

**Installer smoke test (Windows, in CI).** On every pull request and before every release, `windows/scripts/smoke-test-installer.ps1` installs the installer silently on a Windows machine, checks the Start menu shortcut, starts the installed app with all Electron fuses on, waits for its window (titled *Time Tracker*), checks it doesn't crash, and uninstalls it. Pull requests use the *Dev* installer, which is attached to the run so it can be tried by hand without touching the real app.

Android, inside `android/`:

```bash
./gradlew testDebugUnitTest                      # JVM unit tests
./gradlew lintDebug                              # Android lint; errors fail the build (no baseline)
python3 -m unittest discover -s scripts/tests    # release scripts (version maths, keystore secret)
```

The unit tests cover the sync payload: round trips, the shapes the Windows app writes, rejecting invalid payloads, and the JSON helpers. CI runs the script tests, unit tests and lint, then builds the debug and release APKs, so compile and R8 errors fail the check too; the release workflow runs the unit tests and lint again before the signed build. Test and lint reports are attached to every CI run (`android-reports`).

## What is covered

All tests are unit tests. Windows: 45 tests across 8 files:

| File | Covers |
|---|---|
| `src/lib/rounding.test.js` | Round to nearest 5/10/15 at, below and above the midpoint; exact boundaries; minimum duration at stop |
| `src/lib/format.test.js` | Fallback title per date format; recognising generated titles in any format; leaving real descriptions alone |
| `src/lib/notionValues.test.js` | Building Notion values for every writable type; empty values omitted; number parsing; reading preset values saved by the Android app |
| `src/lib/color.test.js` | Light/dark workspace colour variants reach ~3:1 contrast; extremes (white, black, yellow) stay usable and recognisable |
| `src/lib/presets.test.js` | Untitled presets get their description or the workspace name; workspaces without presets are untouched |
| `src/lib/descriptionAutoSave.test.js` | A running timer's description is auto-saved only when it really changed; spaces and an emptied field don't trigger a save |
| `src/lib/setupStep.test.js` | Which setup screen the timer shows: sign in when not signed in, add a workspace without one, nothing once both are set or while settings load |
| `src/lib/schema.test.js` | Which configured extra fields count as missing (not in the schema, or not writable), for the warning on the timer screen (same rule as Android) |
| `src/lib/syncDecision.test.js` | Settings sync: pull newer server settings, push unsynced local edits, fill an empty server, never push from an empty new install |
| `electron/settingsSync.test.cjs` | Sync server calls with the Notion token; a refused push returns the newer copy; 401 renews the token once; errors; payload check for IPC |
| `electron/timerPush.test.cjs` | Which saves of the active timer reach the phones (start, new start time or description, switching timers, stop; nothing for an unchanged save); sends with the Notion token, renews it once on 401, nothing while sync is off or signed out, in order, a network error is swallowed |
| `src/lib/errors.test.js` | IPC noise is stripped; "not signed in" becomes a readable message; a cancelled sign-in is recognised |
| `src/store/useStore.test.js` | `init()` is idempotent under React StrictMode (no double interval); a failed running-timer query keeps the active timer |
| `electron/notionTokens.test.cjs` | The sign-in is saved encrypted, plain fallback without encryption, signing out clears it, an undecryptable one reads as signed out, an old API key is deleted, the renderer never sees a token |
| `electron/notionAuth.test.cjs` | Sign-in opens the server with a local port and swaps the code with the matching verifier; Cancel on Notion's page, cancel from the app, a refused code and the listener timeout; one refresh for several failing requests; a refused refresh signs out, a server problem keeps the sign-in |
| `electron/notionClient.test.cjs` | A 401 renews the token once and retries; no renewal means "not signed in"; no loop on a second 401; nothing is sent without a sign-in |
| `electron/bundlePath.test.cjs` | `app://bundle/` maps only to files inside `dist/`; `../` and encoded tricks, other schemes and hosts are refused |

Android: tests in `SyncDecisionTest` (same sync rule as Windows, reading the server's answers), `NotionSessionTest` (the same sign-in and refresh rules as Windows, the challenge, the Dev build's own app link, reading the server's answers), `SyncPayloadJsonTest`, `DescriptionAutoSaveTest` (same auto-save rule as Windows), `SetupStepTest` (same setup-screen rule as Windows), `RemoteTimerTest` (which timer from Windows the phone shows, follows or clears; its own timer is never replaced), `PushRegistrarTest` (registered while signed in with sync on, removed when sync is off or on sign-out), `RunningClockTest` (editing the start of a running timer moves its running time on screen and in the notification, as on Windows), `ShortcutIconColorTest` (launcher shortcut colour: no colour keeps the app icon, light colours are darkened just enough for the white hand, hue kept), `BackupRulesTest`, plus the release-script tests in `android/scripts/tests/` (version parsing and bumping, version codes, keystore-secret decoding).

Worker (`worker/`): `src/index.test.js`, `src/sync.test.js` and `src/push.test.js` run with `node --test` in the "Worker" CI job: start refuses bad parameters, callback goes only to the app that started (local port on Windows, the matching app link on Android) and refuses forged or expired states, the code is only swapped with the right verifier, refresh passes `invalid_grant` on, pages follow the browser language; settings are kept per Notion workspace and user, an older push is refused with the stored copy, delete works, expired tokens get 401 and API keys 403, bad and oversized payloads are refused, Notion is asked once per token. Timer notifications (`push.test.js`): a timer reaches only the phones of that Notion user, at high priority for a start and normal for a stop; phones are kept once, at most 10, removed on unregister, when Firebase reports them gone, and with the synced settings; the Google access token is reused; without the Firebase key nothing is sent; bad input and missing sign-ins are refused; the JWT is checked against the key.

## Strategy

- **Pure logic lives in `src/lib/` and gets unit tests.** Rounding, formatting and value conversion are where silent data errors would end up in the user's Notion database. They are kept free of I/O so `node --test` can cover them.
- **Cross-app compatibility is tested where it can break silently.** For example, preset values synced from Android must parse in the Windows app.
- **Regression tests for the store** pin down bugs that lost data or duplicated work: a cleared timer after a failed query, and double initialisation.
- **Security-relevant main-process logic is split into Electron-free modules** (`apiKey.cjs`, `bundlePath.cjs`) so it can be unit tested.
- **Not covered by unit tests:** the rest of the Electron main process (the installer smoke test only checks that it starts), `notionClient.cjs`, React views, and the Android app beyond the sync payload (Android lint checks API use, translations and accessibility). These are verified manually, and the `NOTION_API_BASE_URL` override exists to point the desktop app at a mock Notion server for that purpose.

No coverage tool is configured.

## Manual checks

For each pull request (before or after it is merged: merging only waits for green CI), install the Dev installer and/or the debug APK from the CI run (both install next to the real apps, as *Time Tracker Dev* and *Time Tracker Dev*) and follow the steps in the pull request. Before a release, also check:

- [ ] Start and stop a timer on each platform; the Notion row gets its start, end and status.
- [ ] Dark mode on and off: text stays readable.
- [ ] Another language (Windows: Settings › Language; Android: Settings › System › Languages › App languages): all text is translated.
- [ ] After a release: install over the previous version. Settings and a running timer are kept; you are asked to sign in with Notion once (older versions used an API key).

## Reading a crash from a release APK

Release APKs are minified. Download `TimeTracker-<version>-mapping.txt` from the matching GitHub Release and decode the trace with R8's `retrace` (Android SDK `cmdline-tools/latest/bin/retrace`):

```bash
retrace TimeTracker-<version>-mapping.txt stacktrace.txt
```
