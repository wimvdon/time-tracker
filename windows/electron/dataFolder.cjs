// The settings folder (%APPDATA%\<app name>). Up to v1.8.x the app was called
// "Time Tracker for Notion", so its settings and Notion sign-in are in
// %APPDATA%\Time Tracker for Notion (the Dev build: ...\Time Tracker for Notion Dev).
// On the first start after the rename, that folder is moved to the new name, so an update
// keeps the settings and the sign-in. No Electron imports, so it runs under node --test.
const path = require('path');

const OLD_PREFIX = 'Time Tracker for Notion';
const NEW_PREFIX = 'Time Tracker';

/** The folder name the app used before the rename, or null if it had no other name. */
function legacyFolderName(appName) {
  if (appName !== NEW_PREFIX && !appName.startsWith(`${NEW_PREFIX} `)) return null;
  return OLD_PREFIX + appName.slice(NEW_PREFIX.length);
}

/**
 * Returns the folder to keep the settings in. Moves the old folder to the new name when
 * only the old one exists; if it can't be moved (in use, no access), keeps using it there.
 * `fs` needs existsSync and renameSync.
 */
function resolveDataFolder({ appData, appName, fs }) {
  const current = path.join(appData, appName);
  const oldName = legacyFolderName(appName);
  if (!oldName || fs.existsSync(current)) return current;
  const old = path.join(appData, oldName);
  if (!fs.existsSync(old)) return current;
  try {
    fs.renameSync(old, current);
    return current;
  } catch {
    return old;
  }
}

module.exports = { legacyFolderName, resolveDataFolder };
