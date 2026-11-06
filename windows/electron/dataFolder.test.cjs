const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('path');
const { legacyFolderName, resolveDataFolder } = require('./dataFolder.cjs');

const appData = path.join(path.sep, 'Users', 'me', 'AppData', 'Roaming');
const at = (name) => path.join(appData, name);

/** A pretend file system holding the given folders. */
function fakeFs(folders, { renameFails = false } = {}) {
  const present = new Set(folders);
  const renames = [];
  return {
    renames,
    existsSync: (p) => present.has(p),
    renameSync: (from, to) => {
      if (renameFails) throw new Error('EBUSY');
      renames.push([from, to]);
      present.delete(from);
      present.add(to);
    },
  };
}

test('knows the names from before the rename', () => {
  assert.equal(legacyFolderName('Time Tracker'), 'Time Tracker for Notion');
  assert.equal(legacyFolderName('Time Tracker Dev'), 'Time Tracker for Notion Dev');
  assert.equal(legacyFolderName('Something else'), null);
  assert.equal(legacyFolderName('Time Trackers'), null);
});

test('moves the old settings folder to the new name', () => {
  const fs = fakeFs([at('Time Tracker for Notion')]);
  assert.equal(resolveDataFolder({ appData, appName: 'Time Tracker', fs }), at('Time Tracker'));
  assert.deepEqual(fs.renames, [[at('Time Tracker for Notion'), at('Time Tracker')]]);
});

test('the Dev build moves its own folder, never the real one', () => {
  const fs = fakeFs([at('Time Tracker for Notion'), at('Time Tracker for Notion Dev')]);
  assert.equal(resolveDataFolder({ appData, appName: 'Time Tracker Dev', fs }), at('Time Tracker Dev'));
  assert.deepEqual(fs.renames, [[at('Time Tracker for Notion Dev'), at('Time Tracker Dev')]]);
});

test('keeps the new folder once it exists', () => {
  const fs = fakeFs([at('Time Tracker'), at('Time Tracker for Notion')]);
  assert.equal(resolveDataFolder({ appData, appName: 'Time Tracker', fs }), at('Time Tracker'));
  assert.deepEqual(fs.renames, []);
});

test('a new install just uses the new folder', () => {
  const fs = fakeFs([]);
  assert.equal(resolveDataFolder({ appData, appName: 'Time Tracker', fs }), at('Time Tracker'));
  assert.deepEqual(fs.renames, []);
});

test('keeps using the old folder when it cannot be moved', () => {
  const fs = fakeFs([at('Time Tracker for Notion')], { renameFails: true });
  assert.equal(resolveDataFolder({ appData, appName: 'Time Tracker', fs }), at('Time Tracker for Notion'));
});
