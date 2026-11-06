// Installer for pull request / test builds (npm run dist:dev): the normal build settings from
// package.json, but as a separate app, "Time Tracker Dev", with its own app ID,
// Start menu entry and settings folder. It installs next to the real app, so testing a
// pull request never touches your real settings, Notion sign-in or running timer.
const pkg = require('./package.json');

const productName = `${pkg.build.productName} Dev`;

module.exports = {
  ...pkg.build,
  appId: `${pkg.build.appId}.dev`,
  productName,
  // The installer's own ID (Windows' list of installed apps), kept from when the app was
  // called "Time Tracker for Notion" so the Dev installer replaces an older Dev build.
  // Never the real app's guid: then a Dev build would replace the real app.
  nsis: { ...pkg.build.nsis, guid: 'cb90ab19-1fac-5181-98fc-a60d97a9f21d' },
  // The packaged package.json decides the app's name at runtime, and with it the settings
  // folder (%APPDATA%\Time Tracker Dev) and the single-instance lock.
  extraMetadata: { name: `${pkg.name}-dev`, productName },
};
