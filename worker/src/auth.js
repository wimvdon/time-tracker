// "Sign in with Notion" for Time Tracker (Windows and Android).
//
// Notion's OAuth needs a client secret to swap the one-time sign-in code for a token. A secret
// can't be kept inside an app people download, so this worker holds it and does only that swap
// (and the token refresh). Sign-in stores nothing: everything it needs travels in a signed `state`.
//
// Flow (see docs/architecture.md, "Sign in with Notion"):
//  1. The app makes a random `verifier` and opens  /start?platform=…&challenge=sha256(verifier)
//     in the browser. The worker signs {platform, port, challenge, expiry} into `state` and sends
//     the browser on to Notion's consent page.
//  2. Notion sends the browser back to /callback?code=…&state=…. The worker checks the
//     signature and passes code and state on to the app: a local address on Windows, the app's
//     own link scheme on Android.
//  3. The app posts {code, state, verifier} to /token. Only the app that made the verifier
//     can do this, so another app that catches the link can't use the code. The worker checks
//     sha256(verifier) against the signed challenge and asks Notion for the tokens.
//  4. When Notion answers 401 later, the app posts its refresh token to /refresh.
//
// Uses Web APIs only (fetch, crypto.subtle, URL), so the same code runs in Cloudflare Workers
// and in Node.js for the unit tests (auth.test.js).

export const NOTION_AUTHORIZE_URL = 'https://api.notion.com/v1/oauth/authorize';
export const NOTION_TOKEN_URL = 'https://api.notion.com/v1/oauth/token';

/** How long a sign-in may take from opening the browser to the app redeeming the code. */
export const STATE_TTL_MS = 15 * 60 * 1000;

/** Where each app wants the code delivered. `windows` uses a local port chosen by the app. */
export const APP_LINKS = {
  android: 'timetrackerfornotion://auth',
  'android-debug': 'timetrackerfornotion-debug://auth',
};
const PLATFORMS = ['windows', ...Object.keys(APP_LINKS)];

const encoder = new TextEncoder();

function base64url(bytes) {
  let binary = '';
  for (const b of new Uint8Array(bytes)) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function fromBase64url(text) {
  const binary = atob(text.replace(/-/g, '+').replace(/_/g, '/'));
  return Uint8Array.from(binary, (c) => c.charCodeAt(0));
}

/** sha256(verifier), base64url, as the app sends it in `challenge`. */
export async function challengeFor(verifier) {
  return base64url(await crypto.subtle.digest('SHA-256', encoder.encode(verifier)));
}

async function hmacKey(secret) {
  return crypto.subtle.importKey('raw', encoder.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, [
    'sign',
    'verify',
  ]);
}

export async function signState(data, secret) {
  const body = base64url(encoder.encode(JSON.stringify(data)));
  const signature = await crypto.subtle.sign('HMAC', await hmacKey(secret), encoder.encode(body));
  return `${body}.${base64url(signature)}`;
}

/** The data in a state this worker signed, or null if it was changed, forged or has expired. */
export async function verifyState(state, secret, now = Date.now()) {
  if (typeof state !== 'string') return null;
  const [body, signature, extra] = state.split('.');
  if (!body || !signature || extra !== undefined) return null;
  try {
    const valid = await crypto.subtle.verify(
      'HMAC',
      await hmacKey(secret),
      fromBase64url(signature),
      encoder.encode(body),
    );
    if (!valid) return null;
    const data = JSON.parse(new TextDecoder().decode(fromBase64url(body)));
    if (typeof data.exp !== 'number' || data.exp < now) return null;
    return data;
  } catch {
    return null;
  }
}

const isChallenge = (value) => typeof value === 'string' && /^[A-Za-z0-9_-]{43}$/.test(value);
const isPort = (value) => /^\d{4,5}$/.test(value ?? '') && Number(value) >= 1024 && Number(value) <= 65535;

/**
 * Checks the /start parameters. Returns the data to sign into `state`, or null.
 * Windows must name the local port it listens on; Android must not.
 */
export function parseStart(params, now = Date.now()) {
  const platform = params.get('platform');
  const challenge = params.get('challenge');
  const port = params.get('port');
  if (!PLATFORMS.includes(platform) || !isChallenge(challenge)) return null;
  if (platform === 'windows' ? !isPort(port) : port !== null) return null;
  return {
    p: platform,
    c: challenge,
    ...(platform === 'windows' ? { port: Number(port) } : {}),
    exp: now + STATE_TTL_MS,
  };
}

export function authorizeUrl({ clientId, redirectUri, state }) {
  const url = new URL(NOTION_AUTHORIZE_URL);
  url.searchParams.set('client_id', clientId);
  url.searchParams.set('redirect_uri', redirectUri);
  url.searchParams.set('response_type', 'code');
  url.searchParams.set('owner', 'user');
  url.searchParams.set('state', state);
  return url.toString();
}

/** Where /callback sends the browser: the app that started this sign-in. */
export function appRedirect(stateData, params) {
  const url =
    stateData.p === 'windows'
      ? new URL(`http://127.0.0.1:${stateData.port}/callback`)
      : new URL(APP_LINKS[stateData.p]);
  for (const name of ['code', 'state', 'error']) {
    const value = params.get(name);
    if (value) url.searchParams.set(name, value);
  }
  return url.toString();
}

/** What the apps need from Notion's token answer. The rest (owner details) is left out. */
export function tokenResult(data) {
  return {
    access_token: data.access_token,
    refresh_token: data.refresh_token ?? null,
    workspace_id: data.workspace_id ?? null,
    workspace_name: data.workspace_name ?? null,
    bot_id: data.bot_id ?? null,
  };
}

/** Calls Notion's token endpoint with the client secret. Returns { status, body }. */
export async function notionToken(env, body, fetchImpl = fetch) {
  const response = await fetchImpl(NOTION_TOKEN_URL, {
    method: 'POST',
    headers: {
      Authorization: `Basic ${btoa(`${env.NOTION_CLIENT_ID}:${env.NOTION_CLIENT_SECRET}`)}`,
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(body),
  });
  const data = await response.json().catch(() => ({}));
  if (response.ok && data.access_token) return { status: 200, body: tokenResult(data) };
  // 400 invalid_grant: the code was already used or the refresh token is no longer valid; the
  // app has to sign in again. Anything else is Notion having trouble.
  const error = typeof data.error === 'string' ? data.error : 'notion_error';
  return { status: response.status >= 500 ? 502 : 400, body: { error } };
}
