import test from 'node:test';
import assert from 'node:assert/strict';

test('init is idempotent when React StrictMode invokes its effect twice', async () => {
  let settingsReads = 0;
  let intervalCount = 0;

  globalThis.window = {
    notionAPI: {
      getSettings: async () => {
        settingsReads += 1;
        return {};
      },
    },
  };
  globalThis.document = {
    documentElement: {
      classList: { add() {}, remove() {} },
    },
  };

  const originalSetInterval = globalThis.setInterval;
  globalThis.setInterval = () => {
    intervalCount += 1;
    return intervalCount;
  };

  try {
    const { useStore } = await import('./useStore.js');
    await Promise.all([useStore.getState().init(), useStore.getState().init()]);

    assert.equal(settingsReads, 1);
    assert.equal(intervalCount, 1);
  } finally {
    globalThis.setInterval = originalSetInterval;
  }
});

test('a failed running-timer query keeps the active timer instead of clearing it', async () => {
  const { useStore } = await import('./useStore.js');
  const api = globalThis.window.notionAPI;
  const saved = [];
  api.saveSettings = async (data) => { saved.push(data); };
  api.setTimerRunning = () => {};
  api.getPageProperties = async () => ({});

  const ws = {
    id: 'ws1',
    name: 'Work',
    timeEntriesDbId: 'db1',
    propertyMap: { timeEntryStatusProp: 'Status', timeEntryStatusRunning: 'Running', timeEntryDateProp: 'Date' },
  };
  const runningTimer = () => ({
    running: true,
    notionPageId: 'p1',
    workspaceId: 'ws1',
    rawStart: new Date(),
    tickInterval: null,
    propertyMap: ws.propertyMap,
  });

  api.fetchRunningTimers = async () => { throw new Error('Network error reaching Notion'); };
  useStore.setState({ notionAccount: { workspaceName: 'Team' }, workspaces: [ws], timer: runningTimer(), runningTimers: [], runningTimersLoading: false });
  await useStore.getState().loadRunningTimers();
  assert.equal(useStore.getState().timer.running, true);
  assert.ok(!saved.some((d) => 'activeTimer' in d && d.activeTimer === null));

  // A query that succeeds without the entry still means it was stopped elsewhere.
  api.fetchRunningTimers = async () => [];
  await useStore.getState().loadRunningTimers();
  assert.equal(useStore.getState().timer.running, false);
});

test('startTimer starts at a picked time, yesterday when it is later than now, else now', async () => {
  const { useStore } = await import('./useStore.js');
  const api = globalThis.window.notionAPI;
  const starts = [];
  api.createTimeEntry = async ({ startISO }) => { starts.push(new Date(startISO)); return { pageId: `p${starts.length}` }; };
  api.saveSettings = async () => {};
  api.setTimerRunning = () => {};
  api.fetchRunningTimers = async () => [];
  api.fetchRecentEntries = async () => [];

  const ws = { id: 'ws1', name: 'Work', timeEntriesDbId: 'db1', propertyMap: {} };
  const idle = () => useStore.setState({
    notionAccount: { workspaceName: 'Team' }, workspaces: [ws], selectedWorkspaceId: 'ws1', description: '',
    extraFieldDefs: [], extraValues: {}, extrasLoading: false, isStarting: false,
    timer: { ...useStore.getState().timer, running: false, tickInterval: null },
  });

  const originalSetInterval = globalThis.setInterval;
  globalThis.setInterval = () => 0;
  try {
    const hourAgo = new Date(Date.now() - 3600 * 1000);
    idle();
    await useStore.getState().startTimer(hourAgo);
    assert.equal(starts[0].getTime(), Math.floor(hourAgo.getTime() / 60000) * 60000);

    const inAnHour = new Date(Date.now() + 3600 * 1000);
    idle();
    await useStore.getState().startTimer(inAnHour);
    assert.ok(starts[1].getTime() < Date.now());
    assert.ok(Date.now() - starts[1].getTime() > 22 * 3600 * 1000);

    // A click event (Start bound straight to onClick) is not a start time.
    idle();
    const before = Date.now();
    await useStore.getState().startTimer({ type: 'click' });
    assert.ok(Math.abs(starts[2].getTime() - before) < 120 * 1000);
  } finally {
    globalThis.setInterval = originalSetInterval;
  }
});

test('savePreset adds a new preset at the end and edits one in place', async () => {
  const { useStore } = await import('./useStore.js');
  const api = globalThis.window.notionAPI;
  api.saveSettings = async () => {};
  api.notifySettingsChanged = () => {};
  const ws = { id: 'ws1', name: 'Work', presets: [{ id: 'a', name: 'A' }, { id: 'b', name: 'B' }] };
  useStore.setState({ workspaces: [ws, { id: 'ws2', name: 'Other', presets: [] }] });

  await useStore.getState().savePreset({ workspaceId: 'ws1', name: ' C ', pageTitle: 'Standup', extraValues: { Billable: true } });
  let presets = useStore.getState().workspaces[0].presets;
  assert.deepEqual(presets.map((p) => p.name), ['A', 'B', 'C']);
  assert.equal(presets[2].pageTitle, 'Standup');
  assert.deepEqual(presets[2].extraValues, { Billable: true });

  await useStore.getState().savePreset({ workspaceId: 'ws1', presetId: 'a', name: 'A2', pageTitle: '', extraValues: {} });
  presets = useStore.getState().workspaces[0].presets;
  assert.deepEqual(presets.map((p) => [p.id, p.name]), [['a', 'A2'], ['b', 'B'], [presets[2].id, 'C']]);
  assert.equal(useStore.getState().workspaces[1].presets.length, 0);

  // A preset needs a name.
  await useStore.getState().savePreset({ workspaceId: 'ws1', name: '  ' });
  assert.equal(useStore.getState().workspaces[0].presets.length, 3);
});

test('recent entries say they could not load only when a query failed and nothing is left', async () => {
  const { useStore } = await import('./useStore.js');
  const api = globalThis.window.notionAPI;
  const ws = (id) => ({
    id,
    name: id,
    timeEntriesDbId: 'db-' + id,
    propertyMap: { timeEntryStatusProp: 'Status', timeEntryStatusDone: 'Done', timeEntryDateProp: 'Date' },
  });
  const entry = { pageId: 'e1', title: 'Entry', startISO: '2026-11-13T09:00:00Z', endISO: '2026-11-13T10:00:00Z' };

  api.fetchRecentEntries = async () => { throw new Error('Network error reaching Notion'); };
  useStore.setState({ notionAccount: { workspaceName: 'Team' }, workspaces: [ws('a')], recentEntries: [], recentEntriesLoading: false });
  await useStore.getState().loadRecentEntries();
  assert.equal(useStore.getState().recentEntriesFailed, true);

  // One workspace failing while another loads shows the entries, not the error.
  api.fetchRecentEntries = async (dbId) => { if (dbId === 'db-a') throw new Error('down'); return [entry]; };
  useStore.setState({ workspaces: [ws('a'), ws('b')], recentEntries: [] });
  await useStore.getState().loadRecentEntries();
  assert.equal(useStore.getState().recentEntriesFailed, false);
  assert.equal(useStore.getState().recentEntries.length, 1);

  // An empty list that loaded fine is not an error.
  api.fetchRecentEntries = async () => [];
  useStore.setState({ workspaces: [ws('a')], recentEntries: [] });
  await useStore.getState().loadRecentEntries();
  assert.equal(useStore.getState().recentEntriesFailed, false);
});
