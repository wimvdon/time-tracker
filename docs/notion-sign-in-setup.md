# Setting up "Sign in with Notion"

The apps sign in to Notion through a small sign-in server: a Cloudflare Worker (`worker/` in this
repository) at **https://timetracker.wim.to**. It holds Notion's client secret, which can't
be kept safely inside an app, and only swaps Notion's one-time sign-in code for a token (and
renews tokens). It also keeps each user's synced settings, in Durable Objects that Cloudflare
creates by itself on deploy (nothing to set up). How it works: [architecture.md](architecture.md#sign-in-with-notion).

This is a one-time setup. Each value goes in exactly one place:

| Value | Where it comes from | Where it goes |
|---|---|---|
| OAuth client ID | Notion (your public connection) | `worker/wrangler.toml` (`[vars]`): it is public |
| OAuth client secret | Notion (your public connection) | Cloudflare, worker secret `NOTION_CLIENT_SECRET` |
| State secret | You make it up (a long random password) | Cloudflare, worker secret `STATE_SECRET` |
| Firebase service account key (JSON) | Firebase (section 4) | Cloudflare, worker secret `FCM_SERVICE_ACCOUNT` |

The three secrets never go in the repository or in an app.

## 1. Notion: the public connection

In Notion's developer portal ([app.notion.com/developers/connections](https://app.notion.com/developers/connections)),
open the public connection for Time Tracker:

1. **Redirect URIs:** `https://timetracker.wim.to/callback` (exactly this, no slash at the end).
   Keep the old `https://timetracker-signin.wim.to/callback` next to it until no device runs
   version 1.8 or older (they still sign in there); then remove it here and in `worker/wrangler.toml`.
2. **Capabilities:** Read content, Update content, Insert content, and Read user information
   **without** email addresses (for "person" fields).
3. **Icon:** the app icon (512×512 PNG).
4. Copy the **OAuth client ID** and **OAuth client secret** for step 3.

## 2. Cloudflare: deploying the worker from GitHub

Cloudflare builds and deploys the worker itself whenever `main` changes (Workers Builds). The
domain `wim.to` must be in the same Cloudflare account; the worker adds
`timetracker.wim.to` (and, for versions up to 1.8, `timetracker-signin.wim.to`) to it by itself on deploy.

1. Cloudflare → **Workers & Pages → Create application → Import a repository**.
2. **Connect GitHub** (first time only): allow the Cloudflare app on GitHub, for this repository
   only is enough. Pick `time-tracker-for-notion`.
3. Settings on the next screen:
   - **Project name:** `time-tracker-for-notion` (must match `name` in `worker/wrangler.toml`).
   - **Build command:** leave empty.
   - **Deploy command:** `npx wrangler deploy` (the default).
   - **Advanced settings → Path (root directory):** `worker`
4. **Deploy.** From now on every push to `main` deploys again. Under the worker's **Settings →
   Build**, turn off builds for other branches (non-production branch builds), so a pull request
   never changes the live sign-in server.

## 3. Cloudflare: the worker's secrets

After the first deploy: Cloudflare → **Workers & Pages → time-tracker-for-notion → Settings →
Variables and Secrets → Add**, type **Secret**, twice:

- `NOTION_CLIENT_SECRET`: the OAuth client secret from step 1.
- `STATE_SECRET`: a long random password (40+ characters, from a password manager). Changing it
  later only cancels sign-ins that are in progress at that moment.

**Deploy** after adding them. Check: opening https://timetracker.wim.to/ in a browser shows
`{"error":"not_found"}`. That means the worker runs.

## 4. Firebase: timer notifications on the phone

A timer started on Windows shows its notification on the phone through Firebase Cloud Messaging
(see [architecture.md](architecture.md#timer-notifications-from-windows-on-the-phone)).

1. [Firebase console](https://console.firebase.google.com) → project **Time Tracker** (Google
   Analytics off). It has two Android apps: `to.wim.timetracker` and `to.wim.timetracker.debug`.
   **Project settings → General → google-services.json** is `android/app/google-services.json`
   in this repository (download it again after adding an app; it holds no secrets).
2. **Project settings → Service accounts → Generate new private key**: a JSON file. Cloudflare →
   the worker → **Settings → Variables and Secrets → Add**, type **Secret**, name
   `FCM_SERVICE_ACCOUNT`, value: the whole file. Then delete the file. Without this secret
   everything else works; phones just get no timer notifications.

If the key leaks: Google Cloud console → IAM → Service accounts → that account → Keys → delete
it, then generate a new one as above.

## If the client secret leaks

Notion → your connection → regenerate the client secret, then replace `NOTION_CLIENT_SECRET` in
Cloudflare. People who are signed in stay signed in.
