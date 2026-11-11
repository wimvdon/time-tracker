import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { handleRequest } from './index.js';
import { SettingsStore, clearIdentityCache } from './sync.js';
import { MAX_DEVICES, timerMessage } from './push.js';
import { clearAccessToken, readServiceAccount, signedAssertion } from './fcm.js';

const ORIGIN = 'https://timetracker.example';

function memoryStorage() {
  const data = new Map();
  return {
    data,
    get: async (key) => (data.has(key) ? structuredClone(data.get(key)) : undefined),
    put: async (key, value) => void data.set(key, structuredClone(value)),
    delete: async (key) => data.delete(key),
  };
}

function fakeNamespace() {
  const stores = new Map();
  return {
    stores,
    idFromName: (name) => name,
    get(id) {
      if (!stores.has(id)) stores.set(id, new SettingsStore({ storage: memoryStorage() }));
      const store = stores.get(id);
      return { fetch: (url, init) => store.fetch(new Request(url, init)) };
    },
  };
}

const alice = { owner: { type: 'user', user: { id: 'alice' } }, workspace_id: 'ws-1' };
const bob = { owner: { type: 'user', user: { id: 'bob' } }, workspace_id: 'ws-1' };

async function makeServiceAccount() {
  const { privateKey } = await crypto.subtle.generateKey(
    { name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' },
    true,
    ['sign', 'verify'],
  );
  const der = Buffer.from(await crypto.subtle.exportKey('pkcs8', privateKey)).toString('base64');
  return {
    project_id: 'tt-project',
    client_email: 'push@tt-project.iam.gserviceaccount.com',
    private_key: `-----BEGIN PRIVATE KEY-----\n${der.match(/.{1,64}/g).join('\n')}\n-----END PRIVATE KEY-----\n`,
    token_uri: 'https://oauth2.googleapis.com/token',
  };
}

/** Notion (/users/me), Google's token endpoint and FCM, recording what was sent to FCM. */
function fakeServices({ goneTokens = [] } = {}) {
  const sent = [];
  let tokenRequests = 0;
  const fetchImpl = async (url, init = {}) => {
    const href = String(url);
    if (href.startsWith('https://api.notion.com/')) {
      const user = { 'tok-alice': alice, 'tok-bob': bob }[init.headers.Authorization.replace('Bearer ', '')];
      if (!user) return new Response('{}', { status: 401 });
      return new Response(JSON.stringify({ object: 'user', type: 'bot', bot: user }));
    }
    if (href === 'https://oauth2.googleapis.com/token') {
      tokenRequests += 1;
      const params = new URLSearchParams(init.body);
      assert.equal(params.get('grant_type'), 'urn:ietf:params:oauth:grant-type:jwt-bearer');
      assert.equal(params.get('assertion').split('.').length, 3);
      return new Response(JSON.stringify({ access_token: 'google-access', expires_in: 3600 }));
    }
    if (href === 'https://fcm.googleapis.com/v1/projects/tt-project/messages:send') {
      assert.equal(init.headers.Authorization, 'Bearer google-access');
      const { message } = JSON.parse(init.body);
      if (goneTokens.includes(message.token)) {
        return new Response(JSON.stringify({ error: { status: 'NOT_FOUND', details: [{ errorCode: 'UNREGISTERED' }] } }), { status: 404 });
      }
      sent.push(message);
      return new Response('{}');
    }
    throw new Error(`unexpected fetch ${href}`);
  };
  return { sent, fetchImpl, tokenRequests: () => tokenRequests };
}

function call(method, path, token, body, fetchImpl) {
  return handleRequest(
    new Request(`${ORIGIN}${path}`, {
      method,
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
    env,
    fetchImpl,
  );
}

const startEvent = {
  event: 'active',
  previousPageId: 'page-0',
  timer: {
    pageId: 'page-1',
    workspaceId: 'w1',
    rawStart: '2026-11-06T09:03:12.000Z',
    roundedStart: '2026-11-06T09:00:00.000Z',
    description: 'Meeting',
    projectId: 'p1',
  },
};

let env;
beforeEach(async () => {
  clearIdentityCache();
  clearAccessToken();
  env = { SETTINGS: fakeNamespace(), FCM_SERVICE_ACCOUNT: JSON.stringify(await makeServiceAccount()) };
});

test('a started timer reaches every phone of that Notion user, and only theirs', async () => {
  const services = fakeServices();
  assert.equal((await call('PUT', '/devices', 'tok-alice', { token: 'phone-a' }, services.fetchImpl)).status, 200);
  await call('PUT', '/devices', 'tok-alice', { token: 'phone-b' }, services.fetchImpl);
  await call('PUT', '/devices', 'tok-bob', { token: 'phone-bob' }, services.fetchImpl);

  const response = await call('POST', '/timer', 'tok-alice', startEvent, services.fetchImpl);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { sent: 2 });
  assert.deepEqual(services.sent.map((m) => m.token), ['phone-a', 'phone-b']);
  assert.deepEqual(services.sent[0].data, {
    type: 'timer',
    event: 'active',
    pageId: 'page-1',
    previousPageId: 'page-0',
    workspaceId: 'w1',
    rawStart: '2026-11-06T09:03:12.000Z',
    roundedStart: '2026-11-06T09:00:00.000Z',
    description: 'Meeting',
    projectId: 'p1',
  });
  assert.equal(services.sent[0].android.priority, 'HIGH');
});

test('a stopped timer is sent at high priority too, so it is not held back behind the next start', async () => {
  const services = fakeServices();
  await call('PUT', '/devices', 'tok-alice', { token: 'phone-a' }, services.fetchImpl);
  await call('POST', '/timer', 'tok-alice', { event: 'stopped', pageId: 'page-1' }, services.fetchImpl);
  assert.deepEqual(services.sent[0].data, { type: 'timer', event: 'stopped', pageId: 'page-1' });
  assert.equal(services.sent[0].android.priority, 'HIGH');
});

test('registering the same phone twice keeps it once', async () => {
  const services = fakeServices();
  await call('PUT', '/devices', 'tok-alice', { token: 'phone-a' }, services.fetchImpl);
  await call('PUT', '/devices', 'tok-alice', { token: 'phone-a' }, services.fetchImpl);
  await call('POST', '/timer', 'tok-alice', startEvent, services.fetchImpl);
  assert.equal(services.sent.length, 1);
});

test('keeps the most recently registered phones', async () => {
  const services = fakeServices();
  for (let i = 0; i <= MAX_DEVICES; i++) await call('PUT', '/devices', 'tok-alice', { token: `phone-${i}` }, services.fetchImpl);
  await call('POST', '/timer', 'tok-alice', startEvent, services.fetchImpl);
  assert.equal(services.sent.length, MAX_DEVICES);
  assert.ok(!services.sent.some((m) => m.token === 'phone-0'));
});

test('an unregistered phone gets nothing', async () => {
  const services = fakeServices();
  await call('PUT', '/devices', 'tok-alice', { token: 'phone-a' }, services.fetchImpl);
  assert.equal((await call('DELETE', '/devices', 'tok-alice', { token: 'phone-a' }, services.fetchImpl)).status, 200);
  assert.deepEqual(await (await call('POST', '/timer', 'tok-alice', startEvent, services.fetchImpl)).json(), { sent: 0 });
});

test('a phone Firebase no longer knows is forgotten', async () => {
  const services = fakeServices({ goneTokens: ['phone-old'] });
  await call('PUT', '/devices', 'tok-alice', { token: 'phone-old' }, services.fetchImpl);
  await call('PUT', '/devices', 'tok-alice', { token: 'phone-a' }, services.fetchImpl);
  assert.deepEqual(await (await call('POST', '/timer', 'tok-alice', startEvent, services.fetchImpl)).json(), { sent: 1 });
  const stored = await env.SETTINGS.stores.get('ws-1:alice').storage.get('devices');
  assert.deepEqual(stored.map((d) => d.token), ['phone-a']);
});

test('deleting synced settings also forgets the phones', async () => {
  const services = fakeServices();
  await call('PUT', '/devices', 'tok-alice', { token: 'phone-a' }, services.fetchImpl);
  assert.equal((await call('DELETE', '/settings', 'tok-alice', undefined, services.fetchImpl)).status, 200);
  assert.deepEqual(await (await call('POST', '/timer', 'tok-alice', startEvent, services.fetchImpl)).json(), { sent: 0 });
});

test('Google access tokens are reused', async () => {
  const services = fakeServices();
  await call('PUT', '/devices', 'tok-alice', { token: 'phone-a' }, services.fetchImpl);
  await call('POST', '/timer', 'tok-alice', startEvent, services.fetchImpl);
  await call('POST', '/timer', 'tok-alice', { event: 'stopped', pageId: 'page-1' }, services.fetchImpl);
  assert.equal(services.tokenRequests(), 1);
});

test('without the Firebase key nothing is sent, and the app is not told off', async () => {
  const services = fakeServices();
  delete env.FCM_SERVICE_ACCOUNT;
  await call('PUT', '/devices', 'tok-alice', { token: 'phone-a' }, services.fetchImpl);
  const response = await call('POST', '/timer', 'tok-alice', startEvent, services.fetchImpl);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { sent: 0 });
});

test('refuses requests without a valid Notion sign-in', async () => {
  const services = fakeServices();
  assert.equal((await call('PUT', '/devices', null, { token: 'phone-a' }, services.fetchImpl)).status, 401);
  assert.equal((await call('PUT', '/devices', 'tok-unknown', { token: 'phone-a' }, services.fetchImpl)).status, 401);
  assert.equal((await call('POST', '/timer', 'tok-unknown', startEvent, services.fetchImpl)).status, 401);
});

test('refuses bad input', async () => {
  const services = fakeServices();
  assert.equal((await call('PUT', '/devices', 'tok-alice', { token: '' }, services.fetchImpl)).status, 400);
  assert.equal((await call('PUT', '/devices', 'tok-alice', { token: 'a b' }, services.fetchImpl)).status, 400);
  assert.equal((await call('GET', '/devices', 'tok-alice', undefined, services.fetchImpl)).status, 405);
  assert.equal((await call('POST', '/timer', 'tok-alice', { event: 'other' }, services.fetchImpl)).status, 400);
  assert.equal((await call('GET', '/timer', 'tok-alice', undefined, services.fetchImpl)).status, 405);
});

test('timer messages only carry known fields, as text, cut to length', () => {
  assert.equal(timerMessage({ event: 'active', timer: { ...startEvent.timer, rawStart: 'yesterday' } }), null);
  assert.equal(timerMessage({ event: 'active', timer: { ...startEvent.timer, pageId: '' } }), null);
  assert.equal(timerMessage({ event: 'stopped' }), null);
  const message = timerMessage({ event: 'active', timer: { ...startEvent.timer, description: 'x'.repeat(2000), extra: 'no', projectId: null } });
  assert.equal(message.description.length, 500);
  assert.equal(message.projectId, '');
  assert.equal(message.previousPageId, '');
  assert.equal('extra' in message, false);
  assert.ok(Object.values(message).every((v) => typeof v === 'string'));
});

test('reads the service account key only when it is complete', async () => {
  assert.equal(readServiceAccount(undefined), null);
  assert.equal(readServiceAccount('not json'), null);
  assert.equal(readServiceAccount('{"client_email":"a"}'), null);
  assert.equal(readServiceAccount(env.FCM_SERVICE_ACCOUNT).project_id, 'tt-project');
});

test('the token request is a signed JWT for FCM that Google can check', async () => {
  const account = await makeServiceAccount();
  const assertion = await signedAssertion(account, 1_800_000_000);
  const [header, claims, signature] = assertion.split('.');
  const decode = (part) => JSON.parse(Buffer.from(part, 'base64url').toString());
  assert.deepEqual(decode(header), { alg: 'RS256', typ: 'JWT' });
  assert.deepEqual(decode(claims), {
    iss: account.client_email,
    scope: 'https://www.googleapis.com/auth/firebase.messaging',
    aud: 'https://oauth2.googleapis.com/token',
    iat: 1_800_000_000,
    exp: 1_800_003_600,
  });
  // Verify with the public half of the same key.
  const der = Buffer.from(account.private_key.replace(/-----[A-Z ]+-----|\s/g, ''), 'base64');
  const privateKey = await crypto.subtle.importKey('pkcs8', der, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, true, ['sign']);
  const jwk = await crypto.subtle.exportKey('jwk', privateKey);
  const publicKey = await crypto.subtle.importKey(
    'jwk',
    { kty: jwk.kty, n: jwk.n, e: jwk.e },
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['verify'],
  );
  const ok = await crypto.subtle.verify(
    'RSASSA-PKCS1-v1_5',
    publicKey,
    Buffer.from(signature, 'base64url'),
    new TextEncoder().encode(`${header}.${claims}`),
  );
  assert.equal(ok, true);
});
