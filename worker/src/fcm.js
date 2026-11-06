// Sends push messages to phones through Firebase Cloud Messaging (HTTP v1 API).
//
// FCM_SERVICE_ACCOUNT is a Cloudflare secret holding the Firebase service account key (JSON, from
// Firebase console → Project settings → Service accounts). Never in this repository. The worker
// signs a short-lived token request with that key (OAuth 2.0 JWT bearer flow), exchanges it at
// Google for an access token, and keeps the access token for most of its hour.

export const FCM_SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';
const DEFAULT_TOKEN_URI = 'https://oauth2.googleapis.com/token';

/** The service account from the secret, or null when it is missing or unreadable. */
export function readServiceAccount(secret) {
  if (!secret) return null;
  try {
    const account = typeof secret === 'string' ? JSON.parse(secret) : secret;
    if (!account.client_email || !account.private_key || !account.project_id) return null;
    return account;
  } catch {
    return null;
  }
}

const base64url = (bytes) =>
  btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const base64urlText = (text) => base64url(new TextEncoder().encode(text));

function pemToDer(pem) {
  const body = pem.replace(/-----(BEGIN|END) PRIVATE KEY-----/g, '').replace(/\s+/g, '');
  return Uint8Array.from(atob(body), (c) => c.charCodeAt(0));
}

/** The signed request for an access token (RS256 JWT), valid for an hour from `nowSeconds`. */
export async function signedAssertion(account, nowSeconds) {
  const header = base64urlText(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const claims = base64urlText(
    JSON.stringify({
      iss: account.client_email,
      scope: FCM_SCOPE,
      aud: account.token_uri || DEFAULT_TOKEN_URI,
      iat: nowSeconds,
      exp: nowSeconds + 3600,
    }),
  );
  const key = await crypto.subtle.importKey(
    'pkcs8',
    pemToDer(account.private_key),
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['sign'],
  );
  const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(`${header}.${claims}`));
  return `${header}.${claims}.${base64url(signature)}`;
}

// Per worker instance; may be empty, then a new one is fetched.
let cachedToken = null;

/** For tests. */
export function clearAccessToken() {
  cachedToken = null;
}

/** An access token for FCM, reused until five minutes before it expires. */
export async function accessToken(account, fetchImpl = fetch, now = Date.now()) {
  if (cachedToken && cachedToken.email === account.client_email && cachedToken.expires > now) {
    return cachedToken.token;
  }
  const assertion = await signedAssertion(account, Math.floor(now / 1000));
  const response = await fetchImpl(account.token_uri || DEFAULT_TOKEN_URI, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion }).toString(),
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok || typeof data.access_token !== 'string') throw new Error(`fcm_auth_failed_${response.status}`);
  const lifetimeMs = (Number(data.expires_in) || 3600) * 1000;
  cachedToken = { email: account.client_email, token: data.access_token, expires: now + lifetimeMs - 5 * 60 * 1000 };
  return data.access_token;
}

/**
 * Sends `data` (string values only) to one phone. Returns 'sent', 'gone' (the phone's
 * registration no longer exists: forget it) or 'failed'.
 */
export async function sendToDevice({ account, accessToken: token, deviceToken, data, priority }, fetchImpl = fetch) {
  const response = await fetchImpl(`https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      message: { token: deviceToken, data, android: { priority, ttl: '600s' } },
    }),
  });
  if (response.ok) return 'sent';
  const body = await response.json().catch(() => ({}));
  const codes = (body?.error?.details || []).map((d) => d?.errorCode);
  if (response.status === 404 || codes.includes('UNREGISTERED')) return 'gone';
  return 'failed';
}
