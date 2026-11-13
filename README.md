# Time Tracker for Notion

## Documentation
| | |
|---|---|
| [Architecture](docs/architecture.md) | System design, components and flow |
| [Structure](docs/structure.md) | Project organization and responsibilities |
| [Installation](docs/installation.md) | Requirements and steps to run the project |
| [Technical decisions](docs/decisions.md) | Trade-offs and design justifications |
| [Usage guide](docs/usage.md) | Notion setup, tracking time, presets, sync |
| [API](docs/api.md) | Notion API calls, synced settings format, IPC bridge, launch arguments, sign-in and sync server |
| [Testing](docs/testing.md) | How to run tests and what they cover |

---

## Description

Press **Start** when you begin working and **Stop** when you're done. Time Tracker writes each block of time as a row in your own Notion database, from a small always-on-top window on Windows or from an Android app.

- **What it does:**
  - Creates a Notion page with status *In progress* when a timer starts.
  - Fills in the end time and sets *Done* when it stops.
  - Optional time rounding, extra database fields, presets and one-click restarts of recent entries.
- **What problem it solves:** Notion has no built-in timer. Tracking time there means typing dates by hand or wiring up buttons and formulas. This app keeps the data in Notion, where you already report on it, and makes recording it a single click.
- **Real use case:** a freelancer tracks client work in a *Time Entries* database related to a *Projects* database. They start timers from the Windows tray during the day and from the phone notification on the go. Both devices share workspaces and presets through settings sync.

## Quick start

1. Download the installer (`.exe`) and/or the Android APK from the [latest release](https://github.com/wimvdon/time-tracker-for-notion/releases/latest).
2. Create a Notion connection, copy its token, and connect it to the page that holds your Time Entries database.
3. In the app: **Settings** › paste the key › **Test connection & save** › **+ Add workspace**, then press **Start**.

Full walkthrough: [docs/usage.md](docs/usage.md).

To run the desktop app from source:

```bash
git clone https://github.com/wimvdon/time-tracker-for-notion.git
cd time-tracker-for-notion/windows
npm install
npm run dev
```

## Technologies used

| Area | Windows app (`windows/`) | Android app (`android/`) |
|---|---|---|
| Language | JavaScript (ES modules, CommonJS in Electron main) | Kotlin 2.4 |
| UI | React 19, Tailwind CSS 4, Fluent UI icons | Jetpack Compose, Material 3 |
| State | Zustand | ViewModel + `StateFlow` |
| Shell / runtime | Electron 44 (tray, Mica, single instance) | Foreground service for the running timer |
| Storage | electron-store (Notion sign-in encrypted with Windows DPAPI) | DataStore + EncryptedSharedPreferences |
| HTTP | Node `fetch` in the main process | OkHttp + kotlinx.serialization |
| Build | Vite 6, electron-builder (NSIS), Node.js 24 (`windows/.nvmrc`) | Gradle 9.8, AGP 9.4, R8 |
| Tests | `node:test`, ESLint, installer smoke test on Windows | JUnit 4 unit tests, Android lint |

Shared:
- **External service:** Notion REST API (`2022-06-28`).
- **CI/CD:** GitHub Actions checks every pull request (with a Dev installer and a debug APK to try), builds both apps and publishes one release; Dependabot keeps dependencies current.
- **Rules for Claude Code sessions:** [`CLAUDE.md`](CLAUDE.md). **Privacy:** [`PRIVACY.md`](PRIVACY.md).
- **Licence:** Source available for reference; all rights reserved. See [LICENSE](LICENSE).

## Quick installation

- **Use it:** download from [Releases](https://github.com/wimvdon/time-tracker-for-notion/releases) and install.
- **Develop on Windows:** Node.js 24 (`windows/.nvmrc`), then `cd windows && npm ci && npm run dev`.
- **Develop on Android:** open `android/` in Android Studio and run.

Details: [docs/installation.md](docs/installation.md).

## Architecture (summary)

Two independent clients talk directly to the Notion API with the user's own Notion sign-in. A small server (`worker/`, a Cloudflare Worker) holds Notion's client secret for "Sign in with Notion" and keeps each user's synced settings. Notion is both the data store for time entries and the shared state between devices:
- **Running timers:** a page whose status is the "running" option.
- **Settings:** one JSON copy per Notion user on the worker, merged by "newest `updatedAt` wins".

On Windows the Notion token and all HTTP calls stay in the Electron main process; the React renderer reaches them only through a narrow preload bridge. Details: [docs/architecture.md](docs/architecture.md).

## Project structure

```
time-tracker-for-notion/
├── .github/                        CI, release (both apps), PR template, Dependabot
├── CLAUDE.md, PRIVACY.md           Rules for Claude Code sessions; privacy policy
├── windows/                        Electron + React desktop app
│   ├── electron/                   Main process, preload bridge, Notion client
│   ├── src/                        React UI, Zustand store, pure helpers + tests
│   └── scripts/                    Icon generator (also writes Android launcher icons)
└── android/                        Kotlin + Compose phone app
    ├── app/src/main/kotlin/…/      data (api, models, repository), service, ui, viewmodel
    └── scripts/                    Release helpers (versioning, keystore secret) with tests
```

Full tree: [docs/structure.md](docs/structure.md).
