## What changed

<!-- Plain language, for someone who doesn't read code. What does the app do differently now, and why?
     Say which app: Windows, Android, or both. -->

## How to test

<!-- Only the parts for the app(s) this change touches. Give exact steps: what to click or tap,
     and what you should see. -->

### On the PC

<!-- Install the Dev installer: Checks → CI → Artifacts → "Time Tracker Dev Setup <version>.exe"
     (SmartScreen: More info → Run anyway). It installs as "Time Tracker Dev" next to the real app. -->

1. Open **Time Tracker Dev** from the Start menu.
2. …
3. You should see …

### On the phone

<!-- Install the debug APK: Checks → CI → Artifacts → TimeTracker-<version>-debug.apk.
     It installs as "Time Tracker Dev" next to the real app. -->

1. Open **Time Tracker Dev**.
2. …
3. You should see …

## Risks

<!-- What could go wrong or behave differently? Anything not covered by automatic tests? "None" is fine if true. -->

## Checklist

- [ ] Windows: `npm run check` passes in `windows/`; Android: `./gradlew testDebugUnitTest lintDebug` passes in `android/`; all CI jobs are green
- [ ] Unit tests added or updated for every logic change
- [ ] No tests, lint rules, CI steps or workflows removed or weakened
- [ ] Windows security setup unchanged (sandbox, context isolation, CSP, fuses, IPC sender checks)
- [ ] All new text translated in every language (Windows: `windows/src/locales/`; Android: every `values-xx/strings.xml`)
- [ ] `docs/`, `PRIVACY.md` and `docs/decisions.md` updated where needed
- [ ] No new permissions, network access, telemetry or dependencies (or: asked first, explained below)
- [ ] No secrets, keystores, certificates, `keystore.properties` or `.env` files committed
