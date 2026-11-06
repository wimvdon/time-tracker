// Timer notifications on the phone: when the active timer changes on Windows, the phones signed in
// to the same Notion user show (or remove) their running-timer notification.
//
//  PUT    /devices  { token }  → { registered: true }   a phone's FCM registration token
//  DELETE /devices  { token }  → { removed: true }
//  POST   /timer    { event: 'active', timer: {...}, previousPageId? } | { event: 'stopped', pageId }
//                              → { sent: <number of phones> }
//
// Like /settings, every request carries the app's Notion token, and the worker files the phones
// under that Notion user (sync.js, identify). Stored: only each phone's FCM token and when it
// last registered. Timer details are passed on to Firebase and not kept.

import { accessToken, readServiceAccount, sendToDevice } from './fcm.js';
import { identify } from './sync.js';

export const DEVICES_KEY = 'devices';
/** A user with more phones than this keeps the ones that registered most recently. */
export const MAX_DEVICES = 10;
const MAX_FIELD = 500;

const json = (status, body) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' },
  });

const isToken = (value) => typeof value === 'string' && value.length > 0 && value.length <= 4096 && !/\s/.test(value);

/** One request against one user's stored phones, inside their Durable Object. */
export async function deviceOperation(storage, method, body, now = Date.now()) {
  const devices = (await storage.get(DEVICES_KEY)) ?? [];
  if (method === 'GET') return json(200, { devices: devices.map((d) => d.token) });
  if (method === 'PUT') {
    if (!isToken(body?.token)) return json(400, { error: 'invalid_token' });
    const kept = devices.filter((d) => d.token !== body.token);
    kept.push({ token: body.token, platform: 'android', registeredAt: now });
    await storage.put(DEVICES_KEY, kept.slice(-MAX_DEVICES));
    return json(200, { registered: true });
  }
  if (method === 'DELETE') {
    const gone = new Set(Array.isArray(body?.tokens) ? body.tokens : [body?.token]);
    await storage.put(DEVICES_KEY, devices.filter((d) => !gone.has(d.token)));
    return json(200, { removed: true });
  }
  return json(405, { error: 'method_not_allowed' });
}

const text = (value) => (typeof value === 'string' ? value.slice(0, MAX_FIELD) : '');
const isIso = (value) => typeof value === 'string' && !Number.isNaN(Date.parse(value));

/**
 * The FCM data message for a timer event (string values only), or null when the event isn't
 * one the apps send. Android reads it in TimerPushService / RemoteTimer.
 */
export function timerMessage(body) {
  if (body?.event === 'stopped') {
    if (!text(body.pageId)) return null;
    return { type: 'timer', event: 'stopped', pageId: text(body.pageId) };
  }
  if (body?.event === 'active') {
    const t = body.timer;
    if (!t || !text(t.pageId) || !text(t.workspaceId) || !isIso(t.rawStart) || !isIso(t.roundedStart)) return null;
    return {
      type: 'timer',
      event: 'active',
      pageId: text(t.pageId),
      previousPageId: text(body.previousPageId),
      workspaceId: text(t.workspaceId),
      rawStart: t.rawStart,
      roundedStart: t.roundedStart,
      description: text(t.description),
      projectId: text(t.projectId),
    };
  }
  return null;
}

async function bearer(request, fetchImpl) {
  const token = (request.headers.get('Authorization') || '').replace(/^Bearer\s+/i, '');
  if (!token) return { status: 401, error: 'unauthorized' };
  return identify(token, fetchImpl);
}

async function readJson(request) {
  try {
    const body = await request.json();
    return body && typeof body === 'object' ? body : {};
  } catch {
    return {};
  }
}

function userStore(env, key) {
  const stub = env.SETTINGS.get(env.SETTINGS.idFromName(key));
  return (method, body) =>
    stub.fetch('https://settings/devices', {
      method,
      headers: { 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined,
    });
}

/** /devices: a phone registers or unregisters. */
export async function handleDevices(request, env, fetchImpl = fetch) {
  if (!['PUT', 'DELETE'].includes(request.method)) return json(405, { error: 'method_not_allowed' });
  const body = await readJson(request);
  const who = await bearer(request, fetchImpl);
  if (!who.key) return json(who.status, { error: who.error });
  if (!isToken(body.token)) return json(400, { error: 'invalid_token' });
  return userStore(env, who.key)(request.method, { token: body.token });
}

/** /timer: tells the user's phones about the active timer. Phones that no longer exist are forgotten. */
export async function handleTimer(request, env, fetchImpl = fetch) {
  if (request.method !== 'POST') return json(405, { error: 'method_not_allowed' });
  const message = timerMessage(await readJson(request));
  if (!message) return json(400, { error: 'invalid_event' });
  const who = await bearer(request, fetchImpl);
  if (!who.key) return json(who.status, { error: who.error });

  const store = userStore(env, who.key);
  const { devices = [] } = await (await store('GET')).json();
  const account = readServiceAccount(env.FCM_SERVICE_ACCOUNT);
  // Not set up yet (no Firebase key in Cloudflare): nothing to send to, not an app error.
  if (!account || devices.length === 0) return json(200, { sent: 0 });

  let token;
  try {
    token = await accessToken(account, fetchImpl);
  } catch {
    return json(502, { error: 'push_unavailable' });
  }
  // Showing the notification starts a foreground service in the background, which Android only
  // allows right after a high-priority message. Removing it needs no such exception.
  const priority = message.event === 'active' ? 'HIGH' : 'NORMAL';
  const results = await Promise.all(
    devices.map((deviceToken) =>
      sendToDevice({ account, accessToken: token, deviceToken, data: message, priority }, fetchImpl).catch(() => 'failed'),
    ),
  );
  const gone = devices.filter((_, i) => results[i] === 'gone');
  if (gone.length) await store('DELETE', { tokens: gone });
  return json(200, { sent: results.filter((r) => r === 'sent').length });
}
