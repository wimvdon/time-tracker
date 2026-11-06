// Settings sync: one copy of the synced settings (workspaces, presets, display preferences) per
// Notion user and workspace, so the Windows and Android apps share them. Replaces the JSON on a
// Notion page that the apps used before.
//
//  GET    /settings  → { payload } (payload is null when nothing is stored yet)
//  PUT    /settings  { payload } → { accepted: true, payload } or, when the stored copy is as new or
//                    newer, 409 { accepted: false, payload: <stored> } so the app takes that instead
//  DELETE /settings  → { deleted: true }
//
// Every request carries the app's Notion token (Authorization: Bearer). The worker asks Notion
// who it belongs to (/v1/users/me) and files the settings under "<workspace id>:<user id>"; the
// token itself is never stored. An expired token gets 401, after which the app renews it.
//
// The same Durable Object also keeps the user's phones for timer notifications (push.js).
//
// Storage is a Durable Object per user (SettingsStore, SQLite-backed, set up in wrangler.toml),
// so two devices writing at the same moment are handled one after the other.

import { DEVICES_KEY, deviceOperation } from './push.js';

export const NOTION_ME_URL = 'https://api.notion.com/v1/users/me';
export const NOTION_VERSION = '2022-06-28';
/** Generous: a payload with dozens of workspaces and presets is a few tens of kilobytes. */
export const MAX_PAYLOAD_BYTES = 256 * 1024;
const STORAGE_KEY = 'settings';

const json = (status, body) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' },
  });

// Who a token belongs to, for a few minutes, so a sync every minute doesn't ask Notion every time.
// Keyed by a hash of the token, never the token itself. Per worker instance, so it may be empty.
const IDENTITY_TTL_MS = 5 * 60 * 1000;
const identityCache = new Map();

async function tokenHash(token) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(token));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('');
}

/** For tests. */
export function clearIdentityCache() {
  identityCache.clear();
}

/**
 * The storage key for a Notion token: { key } or { status, error } when Notion refuses it (401)
 * or it isn't a user's sign-in (403).
 */
export async function identify(token, fetchImpl = fetch, now = Date.now()) {
  const hash = await tokenHash(token);
  const cached = identityCache.get(hash);
  if (cached && cached.expires > now) return { key: cached.key };

  const response = await fetchImpl(NOTION_ME_URL, {
    headers: { Authorization: `Bearer ${token}`, 'Notion-Version': NOTION_VERSION },
  });
  if (response.status === 401) return { status: 401, error: 'unauthorized' };
  if (!response.ok) return { status: 502, error: 'notion_error' };
  const me = await response.json().catch(() => ({}));
  const userId = me?.bot?.owner?.type === 'user' ? me.bot.owner.user?.id : null;
  const workspaceId = me?.bot?.workspace_id;
  if (!userId || !workspaceId) return { status: 403, error: 'not_a_user_sign_in' };

  const key = `${workspaceId}:${userId}`;
  if (identityCache.size > 1000) identityCache.clear();
  identityCache.set(hash, { key, expires: now + IDENTITY_TTL_MS });
  return { key };
}

/** A payload worth storing: an object with an ISO `updatedAt` and a `workspaces` list. */
export function isValidPayload(payload) {
  return (
    !!payload &&
    typeof payload === 'object' &&
    !Array.isArray(payload) &&
    typeof payload.updatedAt === 'string' &&
    /^\d{4}-\d{2}-\d{2}T[\d:.]+Z$/.test(payload.updatedAt) &&
    Array.isArray(payload.workspaces)
  );
}

/**
 * One request against one user's stored settings. `storage` has get/put/delete (a Durable
 * Object's storage, or a Map-like fake in tests). Newer `updatedAt` wins, as in the apps.
 */
export async function settingsOperation(storage, method, body) {
  if (method === 'GET') {
    return json(200, { payload: (await storage.get(STORAGE_KEY)) ?? null });
  }
  if (method === 'DELETE') {
    // "Delete synced settings" removes everything kept for this user, the phones included.
    await storage.delete(STORAGE_KEY);
    await storage.delete(DEVICES_KEY);
    return json(200, { deleted: true });
  }
  if (method === 'PUT') {
    const payload = body?.payload;
    if (!isValidPayload(payload)) return json(400, { error: 'invalid_payload' });
    const stored = await storage.get(STORAGE_KEY);
    if (stored && stored.updatedAt >= payload.updatedAt) {
      return json(409, { accepted: false, payload: stored });
    }
    await storage.put(STORAGE_KEY, payload);
    return json(200, { accepted: true, payload });
  }
  return json(405, { error: 'method_not_allowed' });
}

/** /settings: checks the token, then hands the request to that user's SettingsStore. */
export async function handleSettings(request, env, fetchImpl = fetch) {
  if (!['GET', 'PUT', 'DELETE'].includes(request.method)) return json(405, { error: 'method_not_allowed' });
  const token = (request.headers.get('Authorization') || '').replace(/^Bearer\s+/i, '');
  if (!token) return json(401, { error: 'unauthorized' });

  let body = null;
  if (request.method === 'PUT') {
    const text = await request.text();
    if (new TextEncoder().encode(text).length > MAX_PAYLOAD_BYTES) return json(413, { error: 'too_large' });
    try {
      body = JSON.parse(text);
    } catch {
      return json(400, { error: 'invalid_payload' });
    }
  }

  const who = await identify(token, fetchImpl);
  if (!who.key) return json(who.status, { error: who.error });

  const stub = env.SETTINGS.get(env.SETTINGS.idFromName(who.key));
  return stub.fetch('https://settings/', {
    method: request.method,
    headers: { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
}

/** The Durable Object holding one user's settings. Each user gets their own instance. */
export class SettingsStore {
  constructor(ctx) {
    this.storage = ctx.storage;
  }

  async fetch(request) {
    const body = ['PUT', 'DELETE'].includes(request.method) ? await request.json().catch(() => null) : null;
    if (new URL(request.url).pathname === '/devices') return deviceOperation(this.storage, request.method, body);
    return settingsOperation(this.storage, request.method, body);
  }
}
