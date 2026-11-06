const { app, BrowserWindow, Tray, Menu, ipcMain, nativeImage, nativeTheme, net, protocol, safeStorage, screen, shell, systemPreferences } = require('electron');
const os = require('os');
const path = require('path');
const zlib = require('zlib');
const { pathToFileURL } = require('url');
const Store = require('electron-store');
const notion = require('./notionClient.cjs');
const { createTokenStore } = require('./notionTokens.cjs');
const { createNotionSession } = require('./notionAuth.cjs');
const { createSettingsSync, isSyncPayload } = require('./settingsSync.cjs');
const { createTimerPush, timerChange } = require('./timerPush.cjs');
const { APP_ORIGIN, APP_SCHEME, resolveBundlePath } = require('./bundlePath.cjs');
const { resolveDataFolder } = require('./dataFolder.cjs');

// Before anything reads or writes the settings folder: carry over the folder from when the
// app was called "Time Tracker for Notion" (dataFolder.cjs).
{
  const dataFolder = resolveDataFolder({ appData: app.getPath('appData'), appName: app.getName(), fs: require('fs') });
  if (dataFolder !== app.getPath('userData')) app.setPath('userData', dataFolder);
}

// ---- Minimal PNG encoder (for the coloured tray icon) ----

const CRC_TABLE = (() => {
  const table = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c;
  }
  return table;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (const byte of buf) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function pngChunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

function encodePNG(width, height, rgba) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; ihdr[9] = 6;
  const raw = Buffer.alloc(height * (1 + width * 4));
  for (let y = 0; y < height; y++) {
    rgba.copy(raw, y * (1 + width * 4) + 1, y * width * 4, (y + 1) * width * 4);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}

// Default running colour when the workspace has none: the app icon's red.
const DEFAULT_TIMER_COLOR = '#E53935';

/** The tray icon while a timer runs: the app's stopwatch with its dial in the
 *  running timer's workspace colour, shaded top to bottom like the app icon.
 *  tray-template.png has a black dial and white hand; each pixel's grey level
 *  says how far it sits toward white, so any colour can be painted in. */
function makeColoredTrayIcon(hexColor) {
  const rgb = [1, 3, 5].map((i) => parseInt(hexColor.slice(i, i + 2), 16));
  if (hexColor.length !== 7 || rgb.some(isNaN)) return null;
  const template = nativeImage.createFromPath(assetPath('tray-template.png'));
  if (template.isEmpty()) return null;
  const { width, height } = template.getSize();
  // Grey pixels read the same in BGRA and RGBA, so the bitmap's channel order
  // doesn't matter here.
  const src = template.toBitmap();
  const mix = (from, to, t) => from.map((v, i) => v + (to[i] - v) * t);
  const top = mix(rgb, [255, 255, 255], 0.2);
  const bottom = mix(rgb, [0, 0, 0], 0.15);
  const out = Buffer.alloc(width * height * 4);
  for (let y = 0; y < height; y++) {
    const dial = mix(top, bottom, y / (height - 1));
    for (let x = 0; x < width; x++) {
      const i = (y * width + x) * 4;
      const toWhite = src[i + 1] / 255;
      const [r, g, b] = mix(dial, [250, 250, 250], toWhite);
      out[i] = Math.round(r); out[i + 1] = Math.round(g); out[i + 2] = Math.round(b);
      out[i + 3] = src[i + 3];
    }
  }
  return nativeImage.createFromBuffer(encodePNG(width, height, out));
}

// encryptionKey obfuscates the config file at rest (it is not a hard security
// boundary — anyone with access to this source can derive it). The Notion sign-in itself is
// additionally encrypted with Windows' DPAPI, see notionTokens.cjs.
const store = new Store({
  encryptionKey: 'notion-timer-local-config-v1',
  defaults: {
    alwaysOnTop: true,
    startWithWindows: false,
    workspaces: [],
    lastUsedWorkspaceId: null,
    activeTimer: null,
    theme: 'auto',
    syncEnabled: true,
    settingsUpdatedAt: null,
    lastSyncedUpdatedAt: null,
  },
});

// The Notion sign-in, encrypted at rest (see notionTokens.cjs). Only notionSession reads it;
// the renderer is told whether it is signed in and to which workspace, never the token.
const notionTokens = createTokenStore(store, safeStorage);
const notionSession = createNotionSession({
  tokens: notionTokens,
  // Only ever the sign-in server's own https address (notionAuth.cjs).
  openBrowser: (url) => shell.openExternal(url),
  locale: () => {
    const stored = store.get('locale');
    return !stored || stored === 'system' ? app.getLocale() : stored;
  },
  // Signed in, signed out, or Notion no longer accepts the sign-in: the UI follows.
  onChange: (reason) => mainWindow?.webContents.send('auth:changed', { account: notionSession.account(), reason }),
});
const settingsSync = createSettingsSync({ auth: notionSession });
// Phones signed in to the same Notion user show the running timer (timerPush.cjs), only while sync is on.
const timerPush = createTimerPush({ auth: notionSession, enabled: () => store.get('syncEnabled') !== false });

// The UI is served from app://bundle/ instead of file:// (Electron security checklist).
// Must be registered before the app is ready.
protocol.registerSchemesAsPrivileged([
  { scheme: APP_SCHEME, privileges: { standard: true, secure: true, supportFetchAPI: true } },
]);

/** The window only ever shows the bundled UI or, during development, the Vite dev server. */
function isAppUrl(url) {
  if (typeof url !== 'string') return false;
  if (url.startsWith(`${APP_ORIGIN}/`)) return true;
  const devServer = process.env.VITE_DEV_SERVER_URL;
  return !app.isPackaged && !!devServer && url.startsWith(devServer);
}

// Every IPC message must come from the app's own page (Electron security checklist).
function isTrustedSender(event) {
  return !!event.senderFrame && isAppUrl(event.senderFrame.url);
}
function handle(channel, listener) {
  ipcMain.handle(channel, (event, ...args) => {
    if (!isTrustedSender(event)) throw new Error(`Blocked IPC from an untrusted page: ${channel}`);
    return listener(event, ...args);
  });
}
function on(channel, listener) {
  ipcMain.on(channel, (event, ...args) => {
    if (isTrustedSender(event)) listener(event, ...args);
  });
}

let mainWindow = null;
let tray = null;
let timerRunning = false;
let timerColor = null;
let isQuitting = false;
let cachedWorkspaces = [];

// Tray menu strings live in the main process, where the renderer's i18n is not
// available. Keyed by language subtag so both this app's tags ('nl-BE') and the
// Android app's ('nl') resolve to the same entry.
const TRAY_STRINGS = {
  en: { open: 'Open', stopTimer: 'Stop timer', quit: 'Quit' },
  nl: { open: 'Openen', stopTimer: 'Timer stoppen', quit: 'Afsluiten' },
  fr: { open: 'Ouvrir', stopTimer: 'Arrêter le minuteur', quit: 'Quitter' },
  de: { open: 'Öffnen', stopTimer: 'Timer stoppen', quit: 'Beenden' },
  es: { open: 'Abrir', stopTimer: 'Detener temporizador', quit: 'Salir' },
  it: { open: 'Apri', stopTimer: 'Ferma timer', quit: 'Esci' },
};

/** Picks a tray string set for a locale tag, falling back to English. */
function trayStringsFor(locale) {
  const lang = String(locale || '').toLowerCase().split(/[-_]/)[0];
  return TRAY_STRINGS[lang] || TRAY_STRINGS.en;
}

// Starts on the Windows display language; the renderer overrides it with the
// language chosen in Settings as soon as it has loaded them.
let trayStrings = TRAY_STRINGS.en;

const assetPath = (name) => path.join(__dirname, '..', 'assets', name);

// ---- Fluent appearance: Mica, light/dark and accent colour ----

// The app's theme setting maps onto Electron's native theme, which drives the
// Mica tint, the title bar and the renderer's prefers-color-scheme.
const THEME_SOURCES = { auto: 'system', light: 'light', dark: 'dark' };

function applyNativeTheme(theme) {
  nativeTheme.themeSource = THEME_SOURCES[theme] || 'system';
}

// Mica needs Windows 11 22H2 (build 22621) or later. Elsewhere the window gets
// an opaque Fluent background instead.
const useMica = (() => {
  if (process.platform !== 'win32') return false;
  const build = Number(os.release().split('.')[2]);
  return Number.isFinite(build) && build >= 22621;
})();

function fallbackBackground() {
  return nativeTheme.shouldUseDarkColors ? '#202020' : '#f3f3f3';
}

/** The Windows accent colour as '#rrggbb', or null when unavailable. */
function accentColor() {
  try {
    const rgba = systemPreferences.getAccentColor();
    return /^[0-9a-f]{6}/i.test(rgba) ? `#${rgba.slice(0, 6)}` : null;
  } catch {
    return null;
  }
}

function createWindow() {
  const startHidden = process.argv.includes('--hidden');
  applyNativeTheme(store.get('theme'));
  // Sized for the timer screen: the time, Start/Stop and the details card with a couple of extra
  // fields fit without scrolling (the lists below scroll). The size is the content area, so the
  // title bar doesn't eat into it; on a short screen it shrinks to fit.
  const workArea = screen.getPrimaryDisplay().workAreaSize;
  mainWindow = new BrowserWindow({
    width: 340,
    height: Math.min(620, workArea.height - 40),
    useContentSize: true,
    ...(useMica
      ? { backgroundMaterial: 'mica', backgroundColor: '#00000000' }
      : { backgroundColor: fallbackBackground() }),
    resizable: false,
    maximizable: false,
    fullscreenable: false,
    autoHideMenuBar: true,
    alwaysOnTop: store.get('alwaysOnTop'),
    show: !startHidden,
    // The .ico carries every size, so Windows picks a sharp one for the taskbar and title bar
    icon: assetPath(process.platform === 'win32' ? 'icon.ico' : 'icon-256.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      // The default since Electron 20, set explicitly so it can't be switched off by accident.
      sandbox: true,
    },
  });

  // The window only ever shows the bundled UI. Links go through
  // shell:openExternal, so block in-window navigation and new windows.
  mainWindow.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  mainWindow.webContents.on('will-navigate', (event) => event.preventDefault());
  mainWindow.webContents.on('will-attach-webview', (event) => event.preventDefault());
  // The app needs no camera, microphone, location, notifications etc. from the page.
  mainWindow.webContents.session.setPermissionRequestHandler((_wc, _permission, callback) => callback(false));
  mainWindow.webContents.session.setPermissionCheckHandler(() => false);

  // Appearance is passed in the URL so the first paint already uses it.
  const query = {};
  if (useMica) query.mica = '1';
  const accent = accentColor();
  if (accent) query.accent = accent;

  if (process.env.VITE_DEV_SERVER_URL) {
    const url = new URL(process.env.VITE_DEV_SERVER_URL);
    for (const [key, value] of Object.entries(query)) url.searchParams.set(key, value);
    mainWindow.loadURL(url.toString());
  } else {
    mainWindow.loadURL(`${APP_ORIGIN}/index.html?${new URLSearchParams(query)}`);
  }

  // Closing the window minimises to tray instead of quitting
  mainWindow.on('close', (event) => {
    if (!isQuitting) {
      event.preventDefault();
      mainWindow.hide();
    }
  });
}

function trayIcon() {
  if (!timerRunning) return nativeImage.createFromPath(assetPath('tray-idle.png'));
  return (
    (timerColor && makeColoredTrayIcon(timerColor)) ||
    makeColoredTrayIcon(DEFAULT_TIMER_COLOR) ||
    nativeImage.createFromPath(assetPath('tray-idle.png'))
  );
}

function rebuildTrayMenu() {
  const items = [
    { label: trayStrings.open, click: () => showWindow() },
  ];
  if (timerRunning) {
    items.push({
      label: trayStrings.stopTimer,
      click: () => {
        showWindow();
        mainWindow.webContents.send('tray:stop-timer');
      },
    });
  }
  // Preset quick-start items — always available, even while a timer is
  // running. If one is, the renderer shows the "stop & start new" dialog
  // instead of silently starting a second timer.
  const presetItems = [];
  for (const ws of cachedWorkspaces) {
    for (const preset of (ws.presets || [])) {
      presetItems.push({ workspace: ws, preset });
    }
  }
  if (presetItems.length > 0) {
    items.push({ type: 'separator' });
    for (const { workspace, preset } of presetItems.slice(0, 10)) {
      items.push({
        label: `▶ ${preset.name}`,
        click: () => {
          showWindow();
          mainWindow.webContents.send('tray:apply-preset', { workspaceId: workspace.id, preset });
        },
      });
    }
  }
  items.push({ type: 'separator' });
  items.push({
    label: trayStrings.quit,
    click: () => {
      isQuitting = true;
      app.quit();
    },
  });
  tray.setContextMenu(Menu.buildFromTemplate(items));
}

function createTray() {
  tray = new Tray(trayIcon());
  tray.setToolTip('Time Tracker');
  tray.on('click', () => showWindow());
  rebuildTrayMenu();
}

function showWindow() {
  if (!mainWindow) return;
  mainWindow.show();
  mainWindow.focus();
}

function applyPresetById(presetId) {
  for (const ws of cachedWorkspaces) {
    const preset = (ws.presets || []).find((p) => p.id === presetId);
    if (preset) {
      showWindow();
      mainWindow?.webContents.send('tray:apply-preset', { workspaceId: ws.id, preset });
      return;
    }
  }
}



// ---- IPC handlers ----

// The renderer never sees the Notion sign-in, only whether there is one (auth:get).
handle('store:get', () => notionTokens.forRenderer(store.store));

handle('store:set', (_event, data) => {
  const previousTimer = store.get('activeTimer');
  for (const [key, value] of Object.entries(data)) {
    if (notionTokens.PROTECTED_FIELDS.includes(key)) {
      // Only notionTokens may write the sign-in.
      continue;
    } else if (value === undefined) {
      // undefined means "remove this key" — used by schema migrations
      store.delete(key);
    } else {
      store.set(key, value);
    }
  }
  if ('activeTimer' in data) timerPush.send(timerChange(previousTimer, store.get('activeTimer')));
  return notionTokens.forRenderer(store.store);
});

// "Sign in with Notion": opens the browser and resolves once the user is back (notionAuth.cjs).
handle('auth:get', () => notionSession.account());
handle('auth:signIn', async () => {
  try {
    return await notionSession.signIn();
  } finally {
    // The browser had the focus; bring the app back.
    showWindow();
  }
});
handle('auth:cancelSignIn', () => notionSession.cancelSignIn());
handle('auth:signOut', () => notionSession.signOut());

handle('notion:searchDatabases', () =>
  notion.searchDatabases(notionSession)
);

handle('notion:getDatabaseSchema', (_event, dbId) =>
  notion.getDatabaseSchema(notionSession, dbId)
);

handle('notion:fetchDatabasePages', (_event, dbId) =>
  notion.fetchDatabasePages(notionSession, dbId)
);

handle('notion:listUsers', () => notion.listUsers(notionSession));

handle('notion:create', (_event, params) =>
  notion.createTimeEntry(notionSession, params)
);

handle('notion:stop', (_event, params) =>
  notion.stopTimeEntry(notionSession, params)
);

handle('notion:fetchRunningTimers', (_event, dbId, statusProp, statusRunning, dateProp) =>
  notion.fetchRunningTimers(notionSession, dbId, statusProp, statusRunning, dateProp)
);

handle('notion:fetchRecentEntries', (_event, dbId, statusProp, statusDone, dateProp) =>
  notion.fetchRecentEntries(notionSession, dbId, statusProp, statusDone, dateProp)
);

handle('notion:deletePage', (_event, pageId) =>
  notion.deletePage(notionSession, pageId)
);

handle('notion:updatePage', (_event, pageId, properties) =>
  notion.updatePageProperties(notionSession, pageId, properties)
);

handle('notion:getPageProperties', (_event, pageId) =>
  notion.getPageProperties(notionSession, pageId)
);

// Settings sync with the sign-in server (settingsSync.cjs). The renderer decides pull or push.
handle('sync:get', () => settingsSync.get());
handle('sync:put', (_event, payload) => {
  if (!isSyncPayload(payload)) throw new Error('Invalid settings payload');
  return settingsSync.put(payload);
});
handle('sync:delete', () => settingsSync.remove());

// Only web links (Notion page URLs) — never file:// or other protocol handlers.
handle('shell:openExternal', (_event, url) => {
  if (typeof url !== 'string' || !/^https?:\/\//i.test(url)) return;
  return shell.openExternal(url);
});

handle('window:alwaysOnTop', (_event, value) => {
  store.set('alwaysOnTop', value);
  if (mainWindow) mainWindow.setAlwaysOnTop(value);
  return value;
});

handle('window:startWithWindows', (_event, value) => {
  store.set('startWithWindows', value);
  if (app.isPackaged) {
    app.setLoginItemSettings({ openAtLogin: value, args: value ? ['--hidden'] : [] });
  }
  return value;
});

on('window:setTheme', (_event, theme) => {
  applyNativeTheme(theme);
  if (mainWindow && !useMica) mainWindow.setBackgroundColor(fallbackBackground());
});

// Renderer reports timer state so the tray icon/menu can reflect it
on('timer:state', (_event, data) => {
  timerRunning = typeof data === 'object' ? !!data.running : !!data;
  timerColor = typeof data === 'object' ? (data.color || null) : null;
  if (tray) {
    tray.setImage(trayIcon());
    rebuildTrayMenu();
  }
});

// Renderer notifies that settings changed (presets added, language switched,
// a newer configuration pulled in from the sync page, …)
on('settings:changed', () => {
  cachedWorkspaces = store.get('workspaces') || [];
  const storedLocale = store.get('locale');
  trayStrings = trayStringsFor(
    !storedLocale || storedLocale === 'system' ? app.getLocale() : storedLocale
  );
  if (tray) rebuildTrayMenu();
});

// ---- App lifecycle ----

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', (_event, argv) => {
    const presetArg = argv.find((a) => a.startsWith('--preset='));
    if (presetArg) {
      applyPresetById(presetArg.slice('--preset='.length));
    } else {
      showWindow();
    }
  });

  app.whenReady().then(() => {
    // API keys from before "Sign in with Notion" are no longer used.
    notionTokens.removeLegacyApiKey();
    const distDir = path.join(__dirname, '..', 'dist');
    protocol.handle(APP_SCHEME, (request) => {
      const file = resolveBundlePath(distDir, request.url);
      if (!file) return new Response('Not found', { status: 404 });
      return net.fetch(pathToFileURL(file).toString());
    });

    // The stored language wins; 'system' (or nothing stored) follows Windows.
    const storedLocale = store.get('locale');
    trayStrings = trayStringsFor(
      !storedLocale || storedLocale === 'system' ? app.getLocale() : storedLocale
    );
    cachedWorkspaces = store.get('workspaces') || [];

    if (app.isPackaged) {
      const startWithWindows = store.get('startWithWindows') === true;
      app.setLoginItemSettings({ openAtLogin: startWithWindows, args: startWithWindows ? ['--hidden'] : [] });
    }

    createWindow();
    createTray();

    nativeTheme.on('updated', () => {
      if (mainWindow && !useMica) mainWindow.setBackgroundColor(fallbackBackground());
    });
    if (process.platform === 'win32') {
      systemPreferences.on('accent-color-changed', () => {
        const accent = accentColor();
        if (mainWindow && accent) mainWindow.webContents.send('appearance:accent', accent);
      });
    }

    // Handle --preset arg when launched fresh (app was not already running)
    const presetArg = process.argv.find((a) => a.startsWith('--preset='));
    if (presetArg) {
      mainWindow.webContents.once('did-finish-load', () => {
        applyPresetById(presetArg.slice('--preset='.length));
      });
    }
  });
}

app.on('before-quit', () => {
  isQuitting = true;
});

// Keep running in the tray even when all windows are closed
app.on('window-all-closed', () => {
  if (isQuitting) app.quit();
});
