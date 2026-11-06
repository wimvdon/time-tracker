// Cloudflare Worker entry point. Sign-in logic is in auth.js, settings sync in sync.js; this file
// only routes requests.
// NOTION_CLIENT_ID is in wrangler.toml (public). Secrets, set in the Cloudflare dashboard and never
// in this repository: NOTION_CLIENT_SECRET, STATE_SECRET and FCM_SERVICE_ACCOUNT (timer notifications). See docs/notion-sign-in-setup.md.

import {
  appRedirect,
  authorizeUrl,
  challengeFor,
  notionToken,
  parseStart,
  signState,
  verifyState,
} from './auth.js';
import { messagePage } from './page.js';
import { handleDevices, handleTimer } from './push.js';
import { handleSettings, SettingsStore } from './sync.js';

export { SettingsStore };

const json = (status, body) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' },
  });

async function readJson(request) {
  try {
    const body = await request.json();
    return body && typeof body === 'object' ? body : {};
  } catch {
    return {};
  }
}

export async function handleRequest(request, env, fetchImpl = fetch) {
  const url = new URL(request.url);
  const language = request.headers.get('Accept-Language');

  if (request.method === 'GET' && url.pathname === '/start') {
    const data = parseStart(url.searchParams);
    if (!data) return messagePage(400, 'badRequest', language);
    const state = await signState(data, env.STATE_SECRET);
    const redirectUri = `${url.origin}/callback`;
    return Response.redirect(authorizeUrl({ clientId: env.NOTION_CLIENT_ID, redirectUri, state }), 302);
  }

  if (request.method === 'GET' && url.pathname === '/callback') {
    const data = await verifyState(url.searchParams.get('state'), env.STATE_SECRET);
    if (!data) return messagePage(400, 'expired', language);
    const target = appRedirect(data, url.searchParams);
    // Windows listens on a local address and a plain redirect is enough. A browser may block
    // an automatic jump to an app link, so Android gets a page with a button as well.
    if (data.p === 'windows') return Response.redirect(target, 302);
    return messagePage(200, 'openApp', language, target);
  }

  if (request.method === 'POST' && url.pathname === '/token') {
    const { code, state, verifier } = await readJson(request);
    const data = await verifyState(state, env.STATE_SECRET);
    if (!data || typeof code !== 'string' || typeof verifier !== 'string') {
      return json(400, { error: 'invalid_request' });
    }
    if ((await challengeFor(verifier)) !== data.c) return json(400, { error: 'invalid_grant' });
    const result = await notionToken(
      env,
      { grant_type: 'authorization_code', code, redirect_uri: `${url.origin}/callback` },
      fetchImpl,
    );
    return json(result.status, result.body);
  }

  if (request.method === 'POST' && url.pathname === '/refresh') {
    const { refresh_token: refreshToken } = await readJson(request);
    if (typeof refreshToken !== 'string' || !refreshToken) return json(400, { error: 'invalid_request' });
    const result = await notionToken(env, { grant_type: 'refresh_token', refresh_token: refreshToken }, fetchImpl);
    return json(result.status, result.body);
  }

  if (url.pathname === '/settings') return handleSettings(request, env, fetchImpl);
  if (url.pathname === '/devices') return handleDevices(request, env, fetchImpl);
  if (url.pathname === '/timer') return handleTimer(request, env, fetchImpl);

  return json(404, { error: 'not_found' });
}

export default {
  fetch: (request, env) => handleRequest(request, env),
};
