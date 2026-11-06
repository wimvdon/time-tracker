# Installation

## Prerequisites

| For | Requirement |
|---|---|
| Both apps | A Notion account and an internal connection (integration) token; see [usage.md](usage.md#1-prepare-notion) |
| Windows app | Windows 10 or 11 (Mica on Windows 11 22H2+). Development: Node.js 24 LTS (as in CI) and npm. |
| Android app | Phone with Android 8.0+ (API 26). Development: Android Studio with JDK 17 or newer (CI uses Temurin 21) and SDK Platform 37. |

No environment variables, database or backend are needed.

## Option A: install a release

1. Open the [latest release](https://github.com/wimvdon/time-tracker-for-notion/releases/latest).
2. **Windows:** download the `.exe` and run it. It's a one-click per-user NSIS installer. SmartScreen warns because the installer isn't code-signed; choose **More info › Run anyway**.
3. **Android:** download the `.apk` on the phone and open it from the **Files** app. Allow installing from that source when asked.
4. Continue with [usage.md](usage.md).

An APK built locally is signed with a debug key. Android refuses to install a release over it until the old copy is uninstalled.

## Option B: run from source

### 1. Clone the repo

```bash
git clone https://github.com/wimvdon/time-tracker-for-notion.git
cd time-tracker-for-notion
```

### 2. Windows app

Use Node.js 24 (`windows/.nvmrc`; with nvm: `nvm use` inside `windows/`).

```bash
cd windows
npm ci
npm run dev
```

`npm run dev` starts Vite on `http://localhost:5173` (strict port) and launches Electron against it with hot reload.

Other scripts:

| Command | Does |
|---|---|
| `npm test` | Unit tests (`node --test`, in `src/` and `electron/`) |
| `npm run lint` | ESLint |
| `npm run check` | Lint + unit tests + build, what CI checks first |
| `npm run build` | Production renderer build into `windows/dist/` |
| `npm run dist` | Build + NSIS installer into `release/` at the repo root |
| `npm run dist:dev` | Build + installer for *Time Tracker Dev*, which installs next to the real app with its own settings |
| `npm run dist:dir` | Build + unpacked app (no installer), faster for smoke tests |
| `npm run icons` | Regenerate all icons from the vector design, including Android launcher layers |

Settings are stored by electron-store in `%APPDATA%\Time Tracker\config.json` (obfuscated, with the Notion sign-in encrypted by Windows, see [decisions.md](decisions.md#api-key-handling)). The Dev app uses `%APPDATA%\Time Tracker Dev\`. Uninstalling keeps it (`deleteAppDataOnUninstall: false`). Up to v1.8 the app was called "Time Tracker for Notion" and used `%APPDATA%\Time Tracker for Notion\`; the first start after updating moves that folder to the new name (`electron/dataFolder.cjs`).

#### Optional: mock Notion server

`notionClient.cjs` reads `NOTION_API_BASE_URL`, so you can point the app at a local mock:

```bash
NOTION_API_BASE_URL=http://localhost:4010/v1 npm run dev
```

### 3. Android app

1. Android Studio › **File › Open** › choose the `android` folder.
2. Install SDK Platform 37 if prompted (**Tools › SDK Manager**).
3. Wait for Gradle sync, then **Run** on a device or emulator.

From a terminal, inside `android/`:

```bash
./gradlew assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug       # install on a connected device
./gradlew testDebugUnitTest lintDebug   # what CI checks
```

Debug builds have the application ID `to.wim.timetracker.debug` and the name **Time Tracker Dev**, so they install next to the released app with their own settings and Notion sign-in.

Use `gradlew.bat` on Windows. Android Studio writes `local.properties` with the SDK path; without it, create one with `sdk.dir=/path/to/Android/Sdk`.

## Verify it works

1. Open the app's **Settings** and click **Sign in with Notion**; allow access in the browser. Expect **Signed in to <workspace>**. (The sign-in server must be set up once: [notion-sign-in-setup.md](notion-sign-in-setup.md).)
2. Add a workspace and press **Start**. A new row with the *running* status appears in the Notion database.
3. Press **Stop**. The row gets an end time and the *done* status.

## Release builds (CI)

`.github/workflows/ci.yml` checks every pull request and every push to `main` (an outdated run of the same pull request is cancelled):
- **Windows checks** (Linux): lint, unit tests, renderer build, and an audit of the packages that ship in the app.
- **Windows build** (Windows): builds the Dev installer, installs it silently, starts it, checks its window, uninstalls it, and attaches the installer to the run (**Checks → CI → Artifacts**).
- **Android**: release-script tests, unit tests, lint (errors fail), debug and release builds, and `TimeTracker-<version>-debug.apk` attached to the run. It installs as *Time Tracker Dev* next to the real app. Test and lint reports are attached too.

`.github/workflows/release.yml` builds both apps on tag pushes (`v*`) and on **Run workflow**.

- **Version:** taken from the tag, or bumped from the latest tag (`patch`, `minor`, `major`). `none` builds without publishing. The Android version code is `major × 10000 + minor × 100 + patch`.
- **Refuses to publish** from outside `main`, when the tag or release already exists, when the version isn't higher than the previous release, or with no commits since it.
- **Windows job:** `npm ci`, sets the package version, lint, `npm test`, builds the installer (`dist`, or `dist:dev` for a build-only run), then installs, starts and uninstalls it with `windows/scripts/smoke-test-installer.ps1`.
- **Android job:** decodes the keystore secret (`android/scripts/decode_keystore.py`), checks the keystore password, alias and key password (`VerifySigningKey.java`) before building, writes `android/keystore.properties`, runs the unit tests and lint, and builds with `-PappVersionName` and `-PappVersionCode`. It builds a debug APK when the secrets are missing and nothing is published.
- **Publish job:** the only job with write access. Creates the tag and the GitHub Release with `gh release create`, attaching `Time Tracker Setup x.y.z.exe`, `TimeTracker-x.y.z.apk` and `TimeTracker-x.y.z-mapping.txt` (R8's map, to read crash stack traces from the release build).
- **Version maths** (next version, version code, "is newer") is in `android/scripts/release_version.py`, unit tested, for both apps.

Repository secrets for signing:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Base64 of the `.jks`. Linux/macOS: `base64 -w0 timetracker.jks`. PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("timetracker.jks")) \| Set-Clipboard` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Key password |

Create the keystore once and keep a backup. A lost key means users must uninstall before the next update:

```bash
keytool -genkeypair -v -keystore timetracker.jks -keyalg RSA -keysize 2048 -validity 10000 -alias timetracker
```

Release: **Actions › Release › Run workflow** on `main` and pick `patch`, `minor` or `major`, or push a tag:

```bash
git tag v1.2.0 && git push origin v1.2.0
```

Or use **Actions › Release › Run workflow** with **Release** = `patch`, `minor` or `major`.
