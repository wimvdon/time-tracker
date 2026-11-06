# Project structure

```
time-tracker-for-notion/
├── .editorconfig                     Editor rules (LF, 4 spaces; Kotlin 140 columns)
├── .gitattributes                    LF everywhere; *.bat, *.ps1 → CRLF; binaries
├── .github/
│   ├── dependabot.yml                Weekly npm (windows/), Gradle (android/) and Actions updates
│   ├── pull_request_template.md      Plain-language PR description, PC and phone test steps, checklist
│   └── workflows/
│       ├── ci.yml                    Windows checks, Windows installer smoke test, Android checks
│       └── release.yml               Version → Windows installer + Android APK → GitHub Release
├── .gitignore                        Build outputs, release/, keystores, .env
├── CLAUDE.md                         Rules for Claude Code sessions
├── DESIGN.md                         Design language shared with the sibling apps
├── LICENSE                           Copyright notice: all rights reserved, no open-source licence
├── PRIVACY.md                        Privacy policy (both apps)
├── README.md
├── docs/
├── worker/                           "Sign in with Notion" and settings sync server (Cloudflare Worker, no dependencies)
│   ├── wrangler.toml                 Worker name and the timetracker-signin.wim.to domain (secrets live in Cloudflare)
│   └── src/                          index.js (routes), auth.js (signed state, token swap), sync.js (settings per user), page.js (browser pages), tests
├── windows/                          Electron desktop app
│   ├── .nvmrc                        Node.js version (24), used by CI and for development
│   ├── package.json                  Scripts, dependencies, electron-builder config (NSIS → ../release)
│   ├── package-lock.json
│   ├── vite.config.js                React + Tailwind plugins, production CSP, relative base
│   ├── index.html                    Renderer entry HTML
│   ├── assets/                       icon.svg (source), icon.ico/png, tray icons
│   ├── scripts/generate-icons.cjs    Renders all icons from one vector design (desktop + Android)
│   ├── electron-builder.dev.cjs      Dev installer: same build, installs as "Time Tracker Dev"
│   ├── eslint.config.js              Lint rules (the UI may not import Node.js/Electron)
│   ├── scripts/smoke-test-installer.ps1  CI: install, start and uninstall the installer on Windows
│   ├── electron/
│   │   ├── main.cjs                  Window, tray, IPC handlers (sender-checked), app:// protocol, lifecycle
│   │   ├── notionAuth.cjs            "Sign in with Notion" (browser + local callback) and token renewal, unit tested
│   │   ├── settingsSync.cjs          Settings sync with the worker (token renewal on 401), unit tested
│   │   ├── notionTokens.cjs          The Notion sign-in encrypted at rest with safeStorage (DPAPI), unit tested
│   │   ├── bundlePath.cjs            Safe app://bundle/ → dist/ file mapping, unit tested
│   │   ├── preload.cjs               contextBridge → window.notionAPI
│   │   └── notionClient.cjs          All Notion REST calls (main process only)
│   └── src/
│       ├── main.jsx                  React root
│       ├── App.jsx                   Init, theme/accent, view switch, tray event wiring
│       ├── index.css                 Tailwind + Fluent-style tokens
│       ├── views/                    Timer.jsx, Settings.jsx
│       ├── components/               ExtraFieldInput, SearchableSelect, Toasts
│       ├── hooks/useIsDarkTheme.js
│       ├── store/
│       │   ├── useStore.js           All application state and actions (Zustand)
│       │   └── useStore.test.js
│       ├── lib/                      Pure helpers, each with a *.test.js where it has logic
│       │   ├── rounding.js           Round to nearest N, minimum duration
│       │   ├── format.js             Durations, dates, fallback titles, Notion datetimes
│       │   ├── notionValues.js       Build/parse property values per Notion type
│       │   ├── schema.js             Pick properties/options from a database schema
│       │   ├── presets.js            Guarantee every preset has a title
│       │   ├── color.js              Workspace colour light/dark variants with contrast
│       │   ├── errors.js             Strip IPC noise from error messages
│       │   └── i18n.js               Locale resolution and t()
│       └── locales/                  en-GB, nl-BE, fr, de, es, it
└── android/                          Android app (Gradle project root)
    ├── build.gradle.kts              Plugin declarations (versions come from the catalog)
    ├── settings.gradle.kts
    ├── gradle.properties
    ├── gradle/
    │   ├── libs.versions.toml        Version catalog: AGP 9.4, Kotlin 2.4, all libraries
    │   └── wrapper/                  Gradle 9.8.1
    ├── gradlew, gradlew.bat
    ├── scripts/                      Release helpers, shared with the android-app template
    │   ├── release_version.py        Tag parsing, next version, versionCode, "is newer" (also used for the Windows version)
    │   ├── decode_keystore.py        Decodes the keystore secret tolerantly, explains failures
    │   ├── VerifySigningKey.java     Checks the key password before the build
    │   └── tests/                    unittest suites for both Python scripts
    └── app/
        ├── build.gradle.kts          App ID, versioning, signing, lint, dependencies, printApkBaseName
        ├── proguard-rules.pro        Notes on R8 (no extra keep rules needed)
        ├── src/debug/res/values*/    Debug build only: the "Time Tracker Dev" name, in every language
        ├── src/test/kotlin/…/        JVM unit tests (NotionSessionTest, SyncPayloadJsonTest, …)
        └── src/main/
            ├── AndroidManifest.xml
            ├── kotlin/to/wim/timetracker/
            │   ├── TimeTrackerApp.kt   Creates repositories and syncer
            │   ├── MainActivity.kt           Compose host, navigation, shortcut + service intents
            │   ├── ShortcutHelper.kt         Preset launcher shortcuts
            │   ├── LocaleHelper.kt           In-app language override
            │   ├── data/
            │   │   ├── api/                  NotionClient (HTTP), AuthClient (sign-in server, PKCE), SyncClient (settings sync), NotionJson (JSON helpers), SyncPayloadJson (sync payload)
            │   │   ├── models/Models.kt      Workspace, PropertyMap, Preset, ActiveTimer, SyncPayload, … (plus ShortcutIconColor: launcher shortcut colour)
            │   │   └── repository/           SettingsRepository, NotionRepository, NotionSession (sign-in, token renewal), SettingsSyncer + SyncDecision (settings sync)
            │   ├── service/
            │   │   └── TimerForegroundService.kt
            │   ├── ui/
            │   │   ├── screens/              TimerScreen, SettingsScreen
            │   │   ├── components/           WorkspaceModal, EditTimerModal, ExtraFieldInput, SearchableDropdown
            │   │   └── theme/                Material 3 theme, workspace colours, shared design kit (Motion, Shapes, SystemBars, SettingsKit, EmptyState)
            │   └── viewmodel/                TimerViewModel, SettingsViewModel
            └── res/
                ├── drawable/, mipmap-anydpi-v26/   Generated launcher icon layers
                ├── values*/                        Strings (en + nl, fr, de, es, it), colours, themes
                └── xml/                            Backup rules: only the settings file
```

## Responsibilities and layers

### Windows

| Layer | Location | Rule |
|---|---|---|
| Privileged | `electron/` | Only place with Node APIs, the Notion token, the file system and network |
| Bridge | `electron/preload.cjs` | One function per IPC channel, no logic beyond queueing early events |
| State and logic | `src/store/useStore.js` | All async flows, persistence calls and Notion orchestration |
| Pure logic | `src/lib/` | No I/O, no React, no `window`. Safe to unit-test with `node --test`. |
| Presentation | `src/views/`, `src/components/` | Render state and call store actions |

### Android

| Layer | Location | Rule |
|---|---|---|
| Data | `data/api`, `data/repository`, `data/models` | HTTP, persistence, caching, sync. No Compose. |
| Logic | `viewmodel/` | Screen state as `StateFlow`; calls repositories |
| Background | `service/` | The foreground timer notification and its Stop action |
| UI | `ui/` | Stateless Compose functions driven by ViewModel state |

## What should NOT go where

- **Windows `src/`:** no `fetch` to Notion and no access to the Notion token. Add an IPC channel in `main.cjs` + `preload.cjs` + `notionClient.cjs` instead.
- **Windows `src/lib/`:** no imports of the store, `window.notionAPI` or React. Keep it pure so tests run without Electron.
- **Android `ui/`:** no repository calls. Go through a ViewModel.
- **Both apps:** changes to the sync payload or to how preset values are stored must be made in **both** apps. Each reads the other's payloads.
- **`release/`** (created by `npm run dist`) and keystores are build outputs and are git-ignored.
