const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createTimerPush, timerChange } = require('./timerPush.cjs');

const SERVER = 'https://push.example.test';

const active = (over = {}) => ({
  notionPageId: 'page-1',
  roundedStart: '2026-11-06T09:00:00.000Z',
  rawStart: '2026-11-06T09:03:12.000Z',
  workspaceId: 'w1',
  projectId: 'p1',
  projectName: '',
  description: 'Meeting',
  propertyMap: { timeEntryDateProp: 'Date' },
  ...over,
});

const timer = (over = {}) => ({
  pageId: 'page-1',
  workspaceId: 'w1',
  rawStart: '2026-11-06T09:03:12.000Z',
  roundedStart: '2026-11-06T09:00:00.000Z',
  description: 'Meeting',
  projectId: 'p1',
  ...over,
});

test('starting a timer tells the phones about it', () => {
  assert.deepEqual(timerChange(null, active()), { event: 'active', timer: timer(), previousPageId: '' });
});

test('stopping it tells them it stopped', () => {
  assert.deepEqual(timerChange(active(), null), { event: 'stopped', pageId: 'page-1' });
});

test('a new start time or description is passed on', () => {
  const moved = timerChange(active(), active({ rawStart: '2026-11-06T08:30:00.000Z', roundedStart: '2026-11-06T08:30:00.000Z' }));
  assert.equal(moved.event, 'active');
  assert.equal(moved.timer.rawStart, '2026-11-06T08:30:00.000Z');
  assert.equal(timerChange(active(), active({ description: 'Call' })).timer.description, 'Call');
});

test('switching to another running timer names the one before', () => {
  assert.equal(timerChange(active(), active({ notionPageId: 'page-2' })).previousPageId, 'page-1');
});

test('saving the same timer again, or nothing, sends nothing', () => {
  assert.equal(timerChange(active(), active({ extraValues: { Tags: 'x' } })), null);
  assert.equal(timerChange(null, null), null);
  assert.equal(timerChange(undefined, null), null);
});

test('dates may be stored as Date objects or strings', () => {
  const asDates = active({ rawStart: new Date('2026-11-06T09:03:12.000Z'), roundedStart: new Date('2026-11-06T09:00:00.000Z') });
  assert.deepEqual(timerChange(null, asDates).timer, timer());
  assert.equal(timerChange(active(), asDates), null);
});

test('a timer without a readable start or workspace is not sent', () => {
  assert.equal(timerChange(null, active({ rawStart: 'soon' })), null);
  assert.equal(timerChange(null, active({ workspaceId: '' })), null);
});

function server(...statuses) {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url: String(url), method: init.method, auth: init.headers.Authorization, body: JSON.parse(init.body) });
    return new Response('{}', { status: statuses.shift() ?? 200 });
  };
  return { calls, fetchImpl };
}

const auth = (token, renewed = null) => ({ token: () => token, refresh: async () => renewed });

test('posts the change with the Notion token', async () => {
  const s = server(200);
  const push = createTimerPush({ auth: auth('tok'), enabled: () => true, serverUrl: SERVER, fetchImpl: s.fetchImpl });
  assert.equal(await push.send({ event: 'stopped', pageId: 'page-1' }), true);
  assert.deepEqual(s.calls, [{ url: `${SERVER}/timer`, method: 'POST', auth: 'Bearer tok', body: { event: 'stopped', pageId: 'page-1' } }]);
});

test('renews an expired token once', async () => {
  const s = server(401, 200);
  const push = createTimerPush({ auth: auth('old', 'new'), enabled: () => true, serverUrl: SERVER, fetchImpl: s.fetchImpl });
  assert.equal(await push.send({ event: 'stopped', pageId: 'page-1' }), true);
  assert.deepEqual(s.calls.map((c) => c.auth), ['Bearer old', 'Bearer new']);
});

test('sends nothing while sync is off, signed out, or when there is no change', async () => {
  const s = server();
  const off = createTimerPush({ auth: auth('tok'), enabled: () => false, serverUrl: SERVER, fetchImpl: s.fetchImpl });
  assert.equal(await off.send({ event: 'stopped', pageId: 'page-1' }), false);
  const signedOut = createTimerPush({ auth: auth(''), enabled: () => true, serverUrl: SERVER, fetchImpl: s.fetchImpl });
  assert.equal(await signedOut.send({ event: 'stopped', pageId: 'page-1' }), false);
  const on = createTimerPush({ auth: auth('tok'), enabled: () => true, serverUrl: SERVER, fetchImpl: s.fetchImpl });
  assert.equal(await on.send(null), false);
  assert.equal(s.calls.length, 0);
});

test('a network error is swallowed', async () => {
  const push = createTimerPush({
    auth: auth('tok'),
    enabled: () => true,
    serverUrl: SERVER,
    fetchImpl: async () => {
      throw new Error('offline');
    },
  });
  assert.equal(await push.send({ event: 'stopped', pageId: 'page-1' }), false);
});

test('changes go out one at a time, in order', async () => {
  const order = [];
  let release;
  const first = new Promise((resolve) => (release = resolve));
  const fetchImpl = async (_url, init) => {
    const body = JSON.parse(init.body);
    if (body.event === 'active') await first;
    order.push(body.event);
    return new Response('{}');
  };
  const push = createTimerPush({ auth: auth('tok'), enabled: () => true, serverUrl: SERVER, fetchImpl });
  const a = push.send({ event: 'active', timer: timer(), previousPageId: '' });
  const b = push.send({ event: 'stopped', pageId: 'page-1' });
  release();
  await Promise.all([a, b]);
  assert.deepEqual(order, ['active', 'stopped']);
});
