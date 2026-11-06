# Rules for Claude Code sessions in this repository

Time Tracker (published in stores as "Time Tracker for Notion"): a Windows desktop app (Electron, `windows/`), an Android app
(`android/`) and the "Sign in with Notion" Cloudflare Worker (`worker/`) in one repository,
built from the same rules as the `windows-electron-app` and `android-app` templates (private repositories). The owner is **not a developer
and does not review code**. CI, tests, lint and these rules are the safety net. Follow them every
time.

## Workflow

1. **One focused change per branch and pull request.** Start a new branch from an up-to-date
   `main` (`git fetch origin main && git checkout -b <branch> origin/main`). Never reuse an old
   or merged branch for a new change.
2. **Before finishing, run and fix everything** for the app(s) you touched:
   ```
   cd windows && npm ci && npm run check          # lint, unit tests, UI build (Node.js version in windows/.nvmrc)
   cd android && ./gradlew testDebugUnitTest lintDebug
   ```
   CI builds the debug and release APKs on every push, so don't build them here unless a step below asks for it.
   If you touched `android/scripts/` or the workflows, also run
   `cd android && python3 -m unittest discover -s scripts/tests`.
   If you touched `worker/`, run `cd worker && npm test`.
   Then push and make sure **all** CI jobs on the pull request are green ("Windows checks",
   "Windows build", "Android", "Worker"). Only CI can build and install the Windows installer; a Linux
   session cannot.
3. **Write the pull request description in plain language** (fill in
   `.github/pull_request_template.md`): what changed and why, in words a non-developer
   understands, and **exactly what to click on the PC / tap on the phone to test it**, step by
   step, with what they should see. The owner tests with the files from the pull request's CI
   run (Checks → CI → Artifacts): the Dev installer installs as "Time Tracker Dev",
   the debug APK as "Time Tracker Dev", each next to the real app with its own settings.
4. **Testing on the phone or PC does not block merging.** The owner doesn't review code, so green CI is
   what a pull request needs to be merged. The owner tries the change afterwards with the test steps
   from the description (the debug APK or Dev installer stays on the pull request's CI run, and every push to
   `main` builds a new one) and always before a release. A problem found then is fixed in a new
   pull request.

## Never do these without asking first (and saying why)

- Delete, skip (`.skip`, `.only`, `@Ignore`), or weaken tests, lint rules, CI steps or workflows
  to make something pass. No `// eslint-disable`, lint baseline or disabled lint check: fix the
  cause. **Never remove the release workflow or the installer smoke test.**
- Weaken the Windows security setup: `contextIsolation`, `sandbox`, `nodeIntegration: false`, the
  Content Security Policy (`windows/vite.config.js`), navigation/permission blocking and the
  `isTrustedSender` IPC check in `windows/electron/main.cjs`, or the Electron fuses in
  `windows/package.json`.
- Add network access beyond the Notion API (`api.notion.com`) and the sign-in server
  (`timetracker-signin.wim.to`, `worker/`), telemetry, analytics, crash
  reporting or ads, or Android permissions beyond the ones in `PRIVACY.md`.
- Add dependencies (npm packages, libraries or Gradle plugins). Prefer what is already there.
  If one is needed, say why and check it is widely used and maintained.
- Change `appId`/`name` in `windows/package.json` or `appId` in `android/app/build.gradle.kts`.
  Both apps are released: Windows would install a second app, phones would refuse to update.
- Change how the Notion sign-in is stored (Windows `safeStorage`, Android `EncryptedSharedPreferences`)
  or the format of the synced settings payload (both apps read it; `docs/api.md`).
- Make the worker (`worker/`) store anything beyond the synced settings, or change its addresses:
  both apps and the redirect URL in Notion's connection settings use them.
- Run anything with administrator rights, or make the installer per-machine.

## Never commit

Secrets, keystores (`*.jks`, `*.keystore`), `keystore.properties`, certificates (`*.pfx`),
passwords, Notion API keys or `.env` files.

## Code rules

- **Windows** (`windows/`): React + Vite UI in `src/`, Electron main process and preload in
  `electron/`. The UI reaches the system only through `window.notionAPI` (`electron/preload.cjs`);
  every IPC handler goes through `handle()` (sender check) and validates its input. Logic lives in
  plain modules (`src/lib/`, `electron/*.cjs` without Electron imports) with `*.test.js(x)` /
  `*.test.cjs` next to them (`node --test`).
- **Android** (`android/`): Kotlin + Jetpack Compose + Material 3, single `app` module, ViewModels
  in `viewmodel/`, Notion calls only in `data/api/`. Logic in plain Kotlin classes without Android
  types so it runs on the JVM.
- **Every logic change gets new or updated unit tests**, in the app it touches.
- **Android stored data and backup:** settings go in the DataStore `notion_settings` (Android backs
  it up); the Notion sign-in and device-only state go in another file (never backed up). See
  `docs/architecture.md`, "Data storage".
- **All user-visible text is translated.** Windows: `windows/src/locales/` (every language file).
  Android: `values/strings.xml` and **every** `values-xx/strings.xml`. Never hard-code text.
  In Compose, read strings with `stringResource` or `LocalResources`, not `LocalContext` (lint fails).
- A feature both apps have must behave the same in both (see `docs/architecture.md`).
- Store Windows user data in `app.getPath('userData')`, never next to the app.
- Versions are automatic (git tags / release workflow). Don't edit `version` in
  `windows/package.json` or `versionName`/`versionCode` in Gradle.

## Keep docs in sync

- Behaviour change → update `docs/` and, if it touches data, files or the network, `PRIVACY.md`.
- Notable decision (new dependency, architecture choice, Windows/Android version support, …) → add
  a dated entry to `docs/decisions.md`.
- Structure: `docs/structure.md`. Design and flow: `docs/architecture.md`. What is tested and how:
  `docs/testing.md`. Look and feel: `DESIGN.md`.
- `DESIGN.md`: only its "This app" section belongs to this repository. The other sections are
  copied from the master copy in the android-app template (`wimvdon/android-app`): change a
  shared rule there first, then copy it unchanged to every app (Watchlist, Time Tracker
  for Notion, Snoozer, RSS Reader, Noter for Notion).

## Releasing (only when the owner asks)

Actions → Release → Run workflow on `main` → choose `patch`, `minor` or `major`. One release
holds both the Windows installer and the Android APK. See `docs/installation.md`.
