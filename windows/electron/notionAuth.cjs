// "Sign in with Notion" on Windows, and keeping the sign-in fresh.
//
// The app never holds Notion's client secret: the sign-in server (a small Cloudflare Worker,
// worker/ in this repository) swaps the one-time code for tokens. Flow, see also worker/src/auth.js:
//  1. Make a random verifier and listen on a free port on 127.0.0.1 (this PC only).
//  2. Open <server>/start?platform=windows&port=…&challenge=sha256(verifier) in the browser.
//     The user picks which Notion pages to share and clicks Allow.
//  3. The browser comes back to http://127.0.0.1:<port>/callback?code=…&state=….
//  4. Post code, state and verifier to <server>/token and save the tokens (notionTokens.cjs).
//  5. When Notion answers 401, post the refresh token to <server>/refresh once and retry.
//     If Notion refuses the refresh token, the app is signed out and asks to sign in again.
//
// Electron-free (the browser and storage are passed in), so it's unit tested in notionAuth.test.cjs.

const crypto = require('crypto');
const http = require('http');

/** The sign-in server. The only address besides api.notion.com the app talks to. */
const AUTH_SERVER_URL = 'https://timetracker-signin.wim.to';

/** How long the app waits for the browser to come back before giving up. */
const SIGN_IN_TIMEOUT_MS = 10 * 60 * 1000;

/** Error messages the renderer recognises (src/lib/errors.js). */
const SIGN_IN_CANCELLED = 'SIGN_IN_CANCELLED';
const NOT_SIGNED_IN = 'NOT_SIGNED_IN';

function createPkce(randomBytes = crypto.randomBytes) {
  const verifier = randomBytes(32).toString('base64url');
  const challenge = crypto.createHash('sha256').update(verifier).digest('base64url');
  return { verifier, challenge };
}

// The page the browser shows after coming back to the app, in the app's languages.
const PAGE_STRINGS = {
  en: { done: 'Signed in to Notion. You can close this tab and go back to Time Tracker.', failed: 'Sign-in did not finish. Go back to Time Tracker and try again.' },
  nl: { done: 'Aangemeld bij Notion. Je kunt dit tabblad sluiten en teruggaan naar Time Tracker.', failed: 'Aanmelden is niet gelukt. Ga terug naar Time Tracker en probeer het opnieuw.' },
  fr: { done: 'Connecté à Notion. Vous pouvez fermer cet onglet et revenir à Time Tracker.', failed: 'La connexion n’a pas abouti. Revenez à Time Tracker et réessayez.' },
  de: { done: 'Bei Notion angemeldet. Du kannst diesen Tab schließen und zu Time Tracker zurückkehren.', failed: 'Die Anmeldung wurde nicht abgeschlossen. Kehre zu Time Tracker zurück und versuche es erneut.' },
  es: { done: 'Sesión iniciada en Notion. Puedes cerrar esta pestaña y volver a Time Tracker.', failed: 'No se completó el inicio de sesión. Vuelve a Time Tracker e inténtalo de nuevo.' },
  it: { done: 'Accesso a Notion effettuato. Puoi chiudere questa scheda e tornare a Time Tracker.', failed: 'L’accesso non è stato completato. Torna a Time Tracker e riprova.' },
};

function callbackPage(locale, ok) {
  const lang = String(locale || '').toLowerCase().split(/[-_]/)[0];
  const text = (PAGE_STRINGS[lang] || PAGE_STRINGS.en)[ok ? 'done' : 'failed'];
  return `<!DOCTYPE html><html><head><meta charset="utf-8"><title>Time Tracker</title>
<style>body{font-family:'Segoe UI',system-ui,sans-serif;margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#f3f3f3;color:#1b1b1b}p{max-width:24rem;text-align:center}@media (prefers-color-scheme:dark){body{background:#202020;color:#f3f3f3}}</style>
</head><body><p>${text}</p></body></html>`;
}

/**
 * Listens on a free 127.0.0.1 port for one GET /callback. `result` resolves with
 * { code, state } or { error }, and rejects on timeout or cancel().
 */
function listenForCallback({ locale = () => 'en', timeoutMs = SIGN_IN_TIMEOUT_MS } = {}) {
  let settle;
  const result = new Promise((resolve, reject) => {
    settle = { resolve, reject };
  });
  // Nobody awaits `result` once a sign-in was given up on; don't report that as unhandled.
  result.catch(() => {});
  let done = false;
  let timer;
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    if (req.method !== 'GET' || url.pathname !== '/callback' || done) {
      res.writeHead(404).end();
      return;
    }
    const code = url.searchParams.get('code');
    const state = url.searchParams.get('state');
    const ok = !!code && !!state;
    res.writeHead(200, {
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'no-store',
      'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'",
    });
    res.end(callbackPage(locale(), ok));
    finish(() => settle.resolve(ok ? { code, state } : { error: url.searchParams.get('error') || 'invalid_callback' }));
  });
  function finish(action) {
    if (done) return;
    done = true;
    clearTimeout(timer);
    server.close();
    // Let the page finish loading before the connection is dropped.
    server.closeIdleConnections?.();
    action();
  }
  const ready = new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => resolve(server.address().port));
  });
  timer = setTimeout(() => finish(() => settle.reject(new Error(SIGN_IN_CANCELLED))), timeoutMs);
  timer.unref?.();
  return {
    ready,
    result,
    cancel: () => finish(() => settle.reject(new Error(SIGN_IN_CANCELLED))),
  };
}

async function postJson(fetchImpl, url, body) {
  let response;
  try {
    response = await fetchImpl(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  } catch (err) {
    throw new Error(`Network error reaching the sign-in server: ${err.message}`, { cause: err });
  }
  const data = await response.json().catch(() => ({}));
  return { status: response.status, data };
}

const sessionFrom = (data, previous = {}) => ({
  accessToken: data.access_token,
  refreshToken: data.refresh_token || previous.refreshToken || null,
  workspaceName: data.workspace_name || previous.workspaceName || '',
  workspaceId: data.workspace_id || previous.workspaceId || null,
});

/**
 * The signed-in Notion session.
 *  - tokens: notionTokens.cjs store
 *  - openBrowser(url): opens the sign-in page
 *  - onChange(reason): 'signedIn' | 'signedOut' | 'expired', so the UI can follow
 */
function createNotionSession({
  tokens,
  openBrowser,
  onChange = () => {},
  locale = () => 'en',
  authUrl = AUTH_SERVER_URL,
  fetchImpl = (...args) => fetch(...args),
  timeoutMs = SIGN_IN_TIMEOUT_MS,
}) {
  let pending = null;
  let refreshing = null;

  const account = () => {
    const session = tokens.get();
    return session ? { workspaceName: session.workspaceName || '' } : null;
  };

  async function signIn() {
    pending?.cancel();
    const { verifier, challenge } = createPkce();
    const listener = listenForCallback({ locale, timeoutMs });
    pending = listener;
    try {
      const port = await listener.ready;
      const start = new URL('/start', authUrl);
      start.searchParams.set('platform', 'windows');
      start.searchParams.set('port', String(port));
      start.searchParams.set('challenge', challenge);
      await openBrowser(start.toString());
      const callback = await listener.result;
      if (callback.error) {
        // access_denied: the user clicked Cancel on Notion's page.
        throw new Error(callback.error === 'access_denied' ? SIGN_IN_CANCELLED : `Notion: ${callback.error}`);
      }
      const { status, data } = await postJson(fetchImpl, new URL('/token', authUrl), {
        code: callback.code,
        state: callback.state,
        verifier,
      });
      if (status !== 200 || !data.access_token) {
        throw new Error(`Notion sign-in failed (${data.error || `HTTP ${status}`})`);
      }
      tokens.set(sessionFrom(data));
      onChange('signedIn');
      return account();
    } finally {
      listener.cancel();
      if (pending === listener) pending = null;
    }
  }

  function cancelSignIn() {
    pending?.cancel();
  }

  function signOut() {
    tokens.set(null);
    onChange('signedOut');
  }

  async function doRefresh() {
    const current = tokens.get();
    if (!current?.refreshToken) {
      if (current) {
        tokens.set(null);
        onChange('expired');
      }
      return null;
    }
    const { status, data } = await postJson(fetchImpl, new URL('/refresh', authUrl), {
      refresh_token: current.refreshToken,
    });
    if (status === 200 && data.access_token) {
      tokens.set(sessionFrom(data, current));
      return data.access_token;
    }
    if (status === 400) {
      // Notion no longer accepts this sign-in (revoked, or replaced by a newer one).
      tokens.set(null);
      onChange('expired');
      return null;
    }
    // The sign-in server or Notion is having trouble: keep the sign-in and try again later.
    throw new Error(`Could not renew the Notion sign-in (${data.error || `HTTP ${status}`})`);
  }

  /**
   * A new access token after Notion refused `failedToken`, or null when the user has to sign
   * in again. Concurrent calls share one refresh; a token that was already renewed by another
   * call is returned as is.
   */
  async function refresh(failedToken) {
    const current = tokens.get();
    if (current && current.accessToken !== failedToken) return current.accessToken;
    if (!refreshing) {
      refreshing = doRefresh().finally(() => {
        refreshing = null;
      });
    }
    return refreshing;
  }

  return {
    token: () => tokens.get()?.accessToken || '',
    refresh,
    account,
    signIn,
    cancelSignIn,
    signOut,
  };
}

module.exports = {
  AUTH_SERVER_URL,
  NOT_SIGNED_IN,
  SIGN_IN_CANCELLED,
  createNotionSession,
  createPkce,
  listenForCallback,
};
