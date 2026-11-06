// The installer settings (package.json "build" and electron-builder.dev.cjs). Both apps are
// released, so their identity must not change by accident.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const pkg = require('../package.json');
const dev = require('../electron-builder.dev.cjs');

test('the real app keeps its name, app ID and installer ID', () => {
  assert.equal(pkg.productName, 'Time Tracker');
  assert.equal(pkg.build.productName, 'Time Tracker');
  assert.equal(pkg.build.appId, 'to.wim.timetracker');
  // From when the app ID was com.wimvandonck.notiontimer: lets the installer update an
  // installed "Time Tracker for Notion" in place instead of installing a second app.
  assert.equal(pkg.build.nsis.guid, 'd912b892-eaac-5f68-a366-61f0aee8954e');
});

test('the Dev build is a separate app that never replaces the real one', () => {
  assert.equal(dev.productName, 'Time Tracker Dev');
  assert.equal(dev.extraMetadata.productName, 'Time Tracker Dev');
  assert.equal(dev.appId, 'to.wim.timetracker.dev');
  assert.equal(dev.nsis.guid, 'cb90ab19-1fac-5181-98fc-a60d97a9f21d');
  assert.notEqual(dev.nsis.guid, pkg.build.nsis.guid);
  assert.equal(dev.nsis.perMachine, false);
});
