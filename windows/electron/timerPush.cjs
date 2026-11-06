// Tells the sign-in server (worker/src/push.js) when the active timer starts, changes or stops,
// so the phones signed in to the same Notion user show or remove their running-timer
// notification. Only while settings sync is on. Fire and forget: a failure only means the phone
// catches up the next time its app opens.
// Electron-free, so it's unit tested (timerPush.test.cjs).

const { AUTH_SERVER_URL } = require('./notionAuth.cjs');

const iso = (value) => {
  if (typeof value !== 'string' && !(value instanceof Date)) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
};

/** What the phones need to hear about one save of `activeTimer`, or null for nothing. */
function timerChange(previous, next) {
  const prev = previous?.notionPageId ? previous : null;
  if (!next?.notionPageId) return prev ? { event: 'stopped', pageId: prev.notionPageId } : null;
  const timer = {
    pageId: next.notionPageId,
    workspaceId: next.workspaceId || '',
    rawStart: iso(next.rawStart),
    roundedStart: iso(next.roundedStart),
    description: typeof next.description === 'string' ? next.description : '',
    projectId: typeof next.projectId === 'string' ? next.projectId : '',
  };
  if (!timer.workspaceId || !timer.rawStart || !timer.roundedStart) return null;
  const same =
    prev &&
    prev.notionPageId === next.notionPageId &&
    iso(prev.rawStart) === timer.rawStart &&
    iso(prev.roundedStart) === timer.roundedStart &&
    (prev.description || '') === timer.description &&
    (prev.projectId || '') === timer.projectId;
  if (same) return null;
  const previousPageId = prev && prev.notionPageId !== next.notionPageId ? prev.notionPageId : '';
  return { event: 'active', timer, previousPageId };
}

function createTimerPush({ auth, enabled, serverUrl = AUTH_SERVER_URL, fetchImpl = (...args) => fetch(...args) }) {
  async function post(change) {
    let token = auth.token();
    if (!token) return false;
    for (let renewed = false; ; renewed = true) {
      const response = await fetchImpl(new URL('/timer', serverUrl), {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify(change),
      });
      if (response.status === 401 && !renewed) {
        token = await auth.refresh(token);
        if (!token) return false;
        continue;
      }
      return response.ok;
    }
  }

  // One at a time and in order, so a quick start-then-stop never reaches the phone reversed.
  let queue = Promise.resolve();

  return {
    /** Passes on one change; resolves to whether the server took it (never rejects). */
    send(change) {
      if (!change || !enabled()) return Promise.resolve(false);
      queue = queue.then(() => post(change).catch(() => false));
      return queue;
    },
  };
}

module.exports = { createTimerPush, timerChange };
