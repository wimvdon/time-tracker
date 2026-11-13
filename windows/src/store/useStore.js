import { create } from 'zustand';
import { computeStartTime, computeEndTime } from '../lib/rounding.js';
import { toNotionDateTime, fallbackDescription, isFallbackDescription } from '../lib/format.js';
import { t, setLocale } from '../lib/i18n.js';
import { errorMessage, isSignInCancelled } from '../lib/errors.js';
import { withPresetTitles, needsPresetTitles } from '../lib/presets.js';
import { syncDecision } from '../lib/syncDecision.js';
import { countMissingExtraProps } from '../lib/schema.js';
import { WRITABLE_EXTRA_TYPES, emptyValueFor, buildPropertyValue, parsePropertyValue, coerceInputValue } from '../lib/notionValues.js';

const api = window.notionAPI;

// Set once applyTheme has subscribed to OS light/dark changes.
let followingSystemTheme = false;

const idleTimer = {
  running: false,
  notionPageId: null,
  roundedStart: null,
  startISO: null,
  rawStart: null,
  propertyMap: null,
  elapsedSeconds: 0,
  tickInterval: null,
};

// ---- Migration from the pre-workspace schema ----

const migratePropertyMap = (old) => {
  if (!old) return {};
  if (old.timeEntryTitleProp !== undefined) return old;
  return {
    timeEntryTitleProp: old.tijdsTitleProp ?? '',
    timeEntryDateProp: old.tijdsTijdProp ?? '',
    timeEntryStatusProp: old.tijdsStatusProp ?? '',
    timeEntryStatusRunning: old.tijdsStatusRunning ?? '',
    timeEntryStatusDone: old.tijdsStatusDone ?? '',
    timeEntryRelationProp: old.tijdsRelationProp ?? '',
  };
};

const migrateActiveTimer = (at) => {
  if (!at) return null;
  if (at.workspaceId !== undefined) return at;
  return {
    notionPageId: at.notionPageId,
    roundedStart: at.roundedStart,
    rawStart: at.rawStart,
    workspaceId: at.projectId ?? null,
    projectId: at.deelprojectId ?? null,
    projectName: at.deelprojectName ?? '',
    description: at.description ?? '',
    propertyMap: migratePropertyMap(at.propertyMap),
  };
};

// How long a locally-stopped timer is hidden from the running-timer list while
// Notion's query catches up.
const RECENTLY_STOPPED_TTL_MS = 60_000;

// pageId -> when it was started on this device. The same lag works the other
// way: a page created a moment ago can be missing from the running-timer query,
// which read as "stopped elsewhere" and cleared the timer that was just started.
const recentlyStarted = new Map();
const wasJustStarted = (pageId) => {
  const ts = recentlyStarted.get(pageId);
  if (ts === undefined) return false;
  if (Date.now() - ts < RECENTLY_STOPPED_TTL_MS) return true;
  recentlyStarted.delete(pageId);
  return false;
};

let toastSeq = 0;
// React StrictMode deliberately runs mount effects twice in development. Keep
// initialization process-wide so that this does not register duplicate tray
// listeners or start multiple auto-refresh intervals.
let initialized = false;
// The in-flight loadExtraFields() of the selected workspace, so a start can
// wait for its field definitions.
let extrasLoadPromise = null;

/**
 * The input definitions for a workspace's extra properties: the live Notion type of each
 * configured property plus the options it can be set to. Used by the timer form and the
 * preset editor. `missing` counts configured fields the app can't show (lib/schema.js).
 * Throws when Notion can't be reached.
 */
export async function fetchExtraFieldDefs(workspace) {
  if (!(workspace?.extraProps?.length)) return { defs: [], missing: 0 };
  const schema = await api.getDatabaseSchema(workspace.timeEntriesDbId);
  let users = null;
  const defs = [];
  for (const { propName: name, required } of workspace.extraProps) {
    const prop = schema?.[name];
    if (!prop || !WRITABLE_EXTRA_TYPES.includes(prop.type)) continue;
    const def = { name, type: prop.type, options: [], required: !!required };
    if (prop.type === 'select' || prop.type === 'multi_select' || prop.type === 'status') {
      def.options = (prop[prop.type]?.options ?? []).map((o) => ({ name: o.name }));
    } else if (prop.type === 'people') {
      users = users ?? (await api.listUsers());
      def.options = users;
    } else if (prop.type === 'relation' && prop.relation?.database_id) {
      def.options = await api.fetchDatabasePages(prop.relation.database_id);
    }
    defs.push(def);
  }
  return { defs, missing: countMissingExtraProps(workspace.extraProps, schema, WRITABLE_EXTRA_TYPES) };
}

export const useStore = create((set, get) => ({
  // Settings
  /** False until the stored settings have been read, so the setup screen doesn't flash at start. */
  settingsLoaded: false,
  /** The signed-in Notion workspace ({ workspaceName }), or null. The token stays in the main process. */
  notionAccount: null,
  /** True while the browser is open for "Sign in with Notion". */
  signingIn: false,
  workspaces: [],
  alwaysOnTop: true,
  startWithWindows: false,
  // Global toggles for the per-entry Notion actions on active/recent timers.
  showNotionLink: true,
  showArchiveAction: true,

  // Settings sync through the sign-in server. Device-only switch; on by default.
  syncEnabled: true,
  settingsUpdatedAt: null,
  lastSyncedUpdatedAt: null,
  syncing: false,
  syncError: null,

  // UI
  view: 'timer',
  toasts: [],
  isStarting: false,
  isStopping: false,
  theme: 'auto',
  showAlreadyRunningWarning: false,
  pendingStartAction: null,

  // Session state
  selectedWorkspaceId: null,
  selectedProjectId: null,
  description: '',
  extraFieldDefs: [],
  extrasLoading: false,
  /** Configured extra fields the app can't show (missing in the schema or not writable). */
  extrasMissing: 0,
  extraValues: {},

  // Timer state
  timer: { ...idleTimer },

  // Running timers from Notion
  runningTimers: [],
  runningTimersLoading: false,
  // pageId -> timestamp of timers stopped locally. Notion's running-timer query
  // can briefly still return a just-stopped entry (replication lag); we ignore
  // those so the app doesn't re-adopt them (which would re-colour the tray icon).
  recentlyStopped: {},
  recentEntries: [],
  recentEntriesLoading: false,
  deleteConfirm: null,

  // Deleted-page dialog: { notionPageId, title, startISO }
  deletedPageInfo: null,

  setView: (view) => set({ view }),
  setDescription: (description) => set({ description }),
  setExtraValue: (name, value) =>
    set((s) => ({ extraValues: { ...s.extraValues, [name]: value } })),

  getRoundingMinutes: () => {
    const ws = get().workspaces.find((w) => w.id === get().selectedWorkspaceId);
    return ws?.roundingMinutes ?? 15;
  },

  addToast: (toast) => {
    const id = ++toastSeq;
    set((state) => ({ toasts: [...state.toasts, { id, ...toast }] }));
    if (!toast.sticky) {
      setTimeout(() => get().dismissToast(id), 6000);
    }
    return id;
  },
  dismissToast: (id) =>
    set((state) => ({ toasts: state.toasts.filter((t) => t.id !== id) })),

  // ---- Init / settings ----

  init: async () => {
    if (initialized) return;
    initialized = true;

    const settings = await api.getSettings();
    const notionAccount = (await api.getNotionAccount?.()) ?? null;

    const hasLegacy =
      (settings.projects?.length ?? 0) > 0 && !(settings.workspaces?.length);
    const rawWorkspaces = hasLegacy
      ? settings.projects.map((p) => ({
          id: p.id,
          name: p.name,
          timeEntriesDbId: p.tijdsregistratiesDbId,
          projectsDbId: p.deelprojectenDbId,
          roundingEnabled: settings.roundingEnabled ?? true,
          propertyMap: migratePropertyMap(p.propertyMap),
        }))
      : settings.workspaces ?? [];

    const workspaces = rawWorkspaces.map((ws) => {
      const extraProps = (ws.extraProps || []).map((ep) =>
        typeof ep === 'string' ? { propName: ep, defaultValue: null } : ep
      );
      const roundingMinutes =
        ws.roundingMinutes !== undefined
          ? ws.roundingMinutes
          : ws.roundingEnabled === false
          ? 0
          : 15;
      return withPresetTitles({ ...ws, extraProps, roundingMinutes });
    });

    const lastUsedWorkspaceId =
      settings.lastUsedWorkspaceId ?? settings.lastUsedProjectId ?? null;
    const activeTimer = migrateActiveTimer(settings.activeTimer);

    const needsMigrationSave =
      hasLegacy ||
      settings.projects !== undefined ||
      rawWorkspaces.some(
        (ws) =>
          ws.roundingMinutes === undefined ||
          (ws.extraProps || []).some((ep) => typeof ep === 'string') ||
          needsPresetTitles(ws)
      );
    if (needsMigrationSave) {
      // The tray menu lists preset titles, so it's rebuilt once they're on disk.
      Promise.resolve(api.saveSettings({
        workspaces,
        lastUsedWorkspaceId,
        activeTimer,
        projects: undefined,
        lastUsedProjectId: undefined,
        roundingEnabled: undefined,
      })).then(() => api.notifySettingsChanged?.());
    }

    // Settings used to sync through a Notion page; they now sync through the sign-in server.
    // The page is no longer read. "Last synced" referred to that page, so it is forgotten too.
    const oldSyncPage = settings.syncPageId !== undefined;
    if (oldSyncPage) {
      api.saveSettings({
        syncPageId: undefined,
        syncPageTitle: undefined,
        syncPageUrl: undefined,
        lastSyncedUpdatedAt: undefined,
      });
    }

    const activeLocale = settings.locale || 'system';
    setLocale(activeLocale);

    set({
      settingsLoaded: true,
      notionAccount,
      workspaces,
      alwaysOnTop: settings.alwaysOnTop !== false,
      startWithWindows: settings.startWithWindows === true,
      showNotionLink: settings.showNotionLink !== false,
      showArchiveAction: settings.showArchiveAction !== false,
      theme: settings.theme || 'auto',
      locale: activeLocale,
      dateFormat: settings.dateFormat || 'DD/MM/YYYY',
      timeFormat: settings.timeFormat || '24h',
      syncEnabled: settings.syncEnabled !== false,
      settingsUpdatedAt: settings.settingsUpdatedAt || null,
      lastSyncedUpdatedAt: oldSyncPage ? null : settings.lastSyncedUpdatedAt || null,
    });

    if (!notionAccount || !workspaces.length) {
      set({ view: 'settings' });
    }

    if (activeTimer && activeTimer.notionPageId) {
      set({ description: activeTimer.description || '', view: 'timer' });
      if (activeTimer.workspaceId) {
        await get().selectWorkspace(activeTimer.workspaceId, activeTimer);
        set({ selectedProjectId: activeTimer.projectId || null });
      }
      get().beginTicking({
        pageId: activeTimer.notionPageId,
        roundedStart: new Date(activeTimer.roundedStart),
        startISO: activeTimer.roundedStart,
        rawStart: new Date(activeTimer.rawStart || activeTimer.roundedStart),
        propertyMap: activeTimer.propertyMap,
      });
    } else if (lastUsedWorkspaceId) {
      const exists = workspaces.some((w) => w.id === lastUsedWorkspaceId);
      if (exists) get().selectWorkspace(lastUsedWorkspaceId);
    }

    if (notionAccount && workspaces.length) {
      get().loadRunningTimers();
      get().loadRecentEntries();
    }

    if (notionAccount && settings.syncEnabled !== false) {
      get().syncNow();
    }

    get().applyTheme();

    // Signed out elsewhere (Settings) or Notion no longer accepts the sign-in.
    api.onAuthChanged?.(({ account, reason }) => {
      set({ notionAccount: account ?? null });
      if (reason === 'expired') {
        get().addToast({ type: 'error', message: t('toastSignInExpired'), sticky: true });
      }
    });

    // Tray preset shortcuts
    if (api.onTrayApplyPreset) {
      api.onTrayApplyPreset(({ workspaceId, preset }) => {
        get().applyPreset(workspaceId, preset);
      });
    }

    // 60s auto-refresh
    setInterval(() => {
      const s = get();
      if (s.notionAccount && s.workspaces.length) {
        get().loadRunningTimers();
        get().loadRecentEntries();
      }
      if (s.notionAccount && s.syncEnabled) {
        get().syncNow();
      }
    }, 60_000);
  },

  /** "Sign in with Notion": opens the browser and waits until the user is back. */
  signIn: async () => {
    if (get().signingIn) return;
    set({ signingIn: true });
    try {
      const notionAccount = await api.signIn();
      set({ notionAccount });
      get().addToast({ type: 'success', message: t('toastSignedIn').replace('{workspace}', notionAccount?.workspaceName || 'Notion') });
      get().refreshAll();
    } catch (err) {
      if (!isSignInCancelled(err)) {
        get().addToast({ type: 'error', message: `${t('toastSignInFailed')}: ${errorMessage(err)}` });
      }
    } finally {
      set({ signingIn: false });
    }
  },

  cancelSignIn: () => api.cancelSignIn(),

  signOut: async () => {
    await api.signOut();
    set({ notionAccount: null });
    get().addToast({ type: 'success', message: t('toastSignedOut') });
  },

  // Bumped whenever a synced field (workspaces/presets, theme, locale, formats,
  // Notion-action toggles) changes, so syncNow() knows this device has local
  // edits that need pushing.
  touchSettingsUpdatedAt: () => {
    const ts = new Date().toISOString();
    set({ settingsUpdatedAt: ts });
    api.saveSettings({ settingsUpdatedAt: ts });
  },

  addWorkspace: async (workspace) => {
    const workspaces = [...get().workspaces, workspace];
    await api.saveSettings({ workspaces });
    set({ workspaces });
    api.notifySettingsChanged?.();
    get().touchSettingsUpdatedAt();
  },

  updateWorkspace: async (updatedWorkspace) => {
    const workspaces = get().workspaces.map((w) =>
      w.id === updatedWorkspace.id ? updatedWorkspace : w
    );
    await api.saveSettings({ workspaces });
    set({ workspaces });
    api.notifySettingsChanged?.();
    get().touchSettingsUpdatedAt();
    if (get().selectedWorkspaceId === updatedWorkspace.id) {
      get().selectWorkspace(updatedWorkspace.id);
    }
  },

  moveWorkspace: async (id, direction) => {
    const workspaces = [...get().workspaces];
    const idx = workspaces.findIndex((w) => w.id === id);
    const swapIdx = direction === 'up' ? idx - 1 : idx + 1;
    if (swapIdx < 0 || swapIdx >= workspaces.length) return;
    [workspaces[idx], workspaces[swapIdx]] = [workspaces[swapIdx], workspaces[idx]];
    await api.saveSettings({ workspaces });
    set({ workspaces });
    get().touchSettingsUpdatedAt();
  },

  deleteWorkspace: async (workspaceId) => {
    const workspaces = get().workspaces.filter((w) => w.id !== workspaceId);
    await api.saveSettings({ workspaces });
    const patch = { workspaces };
    if (get().selectedWorkspaceId === workspaceId) {
      Object.assign(patch, {
        selectedWorkspaceId: null,
        selectedProjectId: null,
      });
      await api.saveSettings({ lastUsedWorkspaceId: null });
    }
    set(patch);
    api.notifySettingsChanged?.();
    get().touchSettingsUpdatedAt();
  },

  /**
   * Creates a preset (no `presetId`) at the end of the workspace's list, or replaces the
   * preset with that id in place, keeping its position. From the preset editor.
   */
  savePreset: async ({ workspaceId, presetId, name, pageTitle, extraValues }) => {
    const state = get();
    // The title is what the preset chip shows, so it's required.
    if (!workspaceId || !name?.trim()) return;
    const preset = {
      id: presetId || Math.random().toString(36).substring(2, 9),
      name: name.trim(),
      pageTitle: (pageTitle || '').trim(),
      extraValues: { ...extraValues },
    };
    const workspaces = state.workspaces.map((w) => {
      if (w.id !== workspaceId) return w;
      const presets = w.presets || [];
      return {
        ...w,
        presets: presetId
          ? presets.map((p) => (p.id === presetId ? preset : p))
          : [...presets, preset],
      };
    });
    await api.saveSettings({ workspaces });
    set({ workspaces });
    api.notifySettingsChanged?.();
    get().touchSettingsUpdatedAt();
  },

  deletePreset: async (workspaceId, presetId) => {
    const state = get();
    const workspaces = state.workspaces.map((w) => {
      if (w.id === workspaceId) {
        return {
          ...w,
          presets: (w.presets || []).filter((p) => p.id !== presetId)
        };
      }
      return w;
    });
    await api.saveSettings({ workspaces });
    set({ workspaces });
    api.notifySettingsChanged?.();
    get().touchSettingsUpdatedAt();
  },

  movePresetUp: async (workspaceId, presetId) => {
    const state = get();
    const workspaces = state.workspaces.map((w) => {
      if (w.id === workspaceId) {
        const presets = [...(w.presets || [])];
        const idx = presets.findIndex((p) => p.id === presetId);
        if (idx > 0) {
          [presets[idx], presets[idx - 1]] = [presets[idx - 1], presets[idx]];
          return { ...w, presets };
        }
      }
      return w;
    });
    // Update state first so the reorder is visible immediately, then persist.
    set({ workspaces });
    api.notifySettingsChanged?.();
    await api.saveSettings({ workspaces });
    get().touchSettingsUpdatedAt();
  },

  movePresetDown: async (workspaceId, presetId) => {
    const state = get();
    const workspaces = state.workspaces.map((w) => {
      if (w.id === workspaceId) {
        const presets = [...(w.presets || [])];
        const idx = presets.findIndex((p) => p.id === presetId);
        if (idx >= 0 && idx < presets.length - 1) {
          [presets[idx], presets[idx + 1]] = [presets[idx + 1], presets[idx]];
          return { ...w, presets };
        }
      }
      return w;
    });
    // Update state first so the reorder is visible immediately, then persist.
    set({ workspaces });
    api.notifySettingsChanged?.();
    await api.saveSettings({ workspaces });
    get().touchSettingsUpdatedAt();
  },

  toggleAlwaysOnTop: async (value) => {
    await api.setAlwaysOnTop(value);
    set({ alwaysOnTop: value });
  },

  toggleStartWithWindows: async (value) => {
    await api.setStartWithWindows(value);
    set({ startWithWindows: value });
  },

  setShowNotionLink: async (value) => {
    await api.saveSettings({ showNotionLink: value });
    set({ showNotionLink: value });
    get().touchSettingsUpdatedAt();
  },

  setShowArchiveAction: async (value) => {
    await api.saveSettings({ showArchiveAction: value });
    set({ showArchiveAction: value });
    get().touchSettingsUpdatedAt();
  },

  setLocale: async (locale) => {
    await api.saveSettings({ locale });
    setLocale(locale);
    set({ locale });
    // The tray menu is built in the main process and has its own copy of the
    // strings, so it has to be told the language changed.
    api.notifySettingsChanged?.();
    get().touchSettingsUpdatedAt();
  },

  setDateFormat: async (dateFormat) => {
    await api.saveSettings({ dateFormat });
    set({ dateFormat });
    get().touchSettingsUpdatedAt();
  },

  setTimeFormat: async (timeFormat) => {
    await api.saveSettings({ timeFormat });
    set({ timeFormat });
    get().touchSettingsUpdatedAt();
  },

  // ---- Workspace / project selection ----

  selectWorkspace: async (workspaceId, activeOverride = null) => {
    const state = get();
    const workspace = state.workspaces.find((w) => w.id === workspaceId);

    const active = activeOverride || (state.timer.running && state.timer.workspaceId === workspaceId
      ? { description: state.description, projectId: state.selectedProjectId, extraValues: state.extraValues }
      : null);

    const description = active ? (active.description || '') : '';
    const selectedProjectId = active ? (active.projectId || null) : null;
    const extraValues = active ? (active.extraValues || {}) : {};

    set({
      selectedWorkspaceId: workspaceId,
      selectedProjectId,
      description,
      extraFieldDefs: [],
      extraValues,
      extrasLoading: !!workspace && (workspace.extraProps?.length ?? 0) > 0,
      extrasMissing: 0,
    });
    api.saveSettings({ lastUsedWorkspaceId: workspaceId });
    if (!workspace) return;
    const promise = get().loadExtraFields(workspace, active);
    extrasLoadPromise = promise;
    await promise;
  },

  loadExtraFields: async (workspace, active = null) => {
    if (!(workspace.extraProps?.length)) return;
    try {
      const { defs, missing } = await fetchExtraFieldDefs(workspace);
      if (get().selectedWorkspaceId !== workspace.id) return;
      const extraValues = { ...active?.extraValues };
      for (const def of defs) {
        if (extraValues[def.name] === undefined) {
          extraValues[def.name] = emptyValueFor(def.type);
        }
      }
      set({ extraFieldDefs: defs, extraValues, extrasLoading: false, extrasMissing: missing });
    } catch (err) {
      if (get().selectedWorkspaceId !== workspace.id) return;
      set({ extrasLoading: false });
      get().addToast({ type: 'error', message: `${t('errorLoadingExtras')}: ${errorMessage(err)}` });
    }
  },

  // ---- Timer ----

  /**
   * Starts a timer now, or at `startAt` (a Date) when the user picked a start time on the
   * idle screen for a timer they forgot to start. A time later than now is taken as
   * yesterday's. Anything else (a click event, no argument) starts now.
   */
  startTimer: async (startAt) => {
    if (get().timer.running || get().isStarting) return;
    set({ isStarting: true });

    // The field definitions decide which extra properties get written, so a
    // start fired while they are still loading (preset, tray/shortcut, recent
    // entry "play") waits for them; otherwise every extra value is silently
    // dropped from the new entry.
    await waitForExtraFields(get);
    const state = get();

    const workspace = state.workspaces.find((w) => w.id === state.selectedWorkspaceId);
    if (!workspace) {
      set({ isStarting: false });
      return;
    }

    const now = new Date();
    let rawStart = startAt instanceof Date && !isNaN(startAt) ? startAt : now;
    if (rawStart > now) rawStart = new Date(rawStart.getTime() - 24 * 3600 * 1000);
    const startISO = toNotionDateTime(rawStart);
    // An empty description becomes the start date on the Notion page; the
    // description input stays empty so nothing is pre-filled for the user.
    const description = state.description.trim();
    const title = description || fallbackDescription(rawStart, state.dateFormat);

    const extra = {};
    for (const def of state.extraFieldDefs) {
      const value = buildPropertyValue(def.type, state.extraValues[def.name]);
      if (value) extra[def.name] = value;
    }

    let pageId;
    try {
      ({ pageId } = await api.createTimeEntry({
        dbId: workspace.timeEntriesDbId,
        title,
        startISO,
        projectId: state.selectedProjectId,
        props: workspace.propertyMap,
        extra,
      }));
    } catch (err) {
      set({ isStarting: false });
      state.addToast({ type: 'error', message: `${t('toastStartError')}: ${errorMessage(err)}` });
      return;
    }
    recentlyStarted.set(pageId, Date.now());

    api.saveSettings({
      activeTimer: {
        notionPageId: pageId,
        roundedStart: startISO,
        rawStart: rawStart.toISOString(),
        workspaceId: workspace.id,
        projectId: state.selectedProjectId,
        projectName: '',
        description,
        propertyMap: workspace.propertyMap,
        extraValues: state.extraValues,
      },
    });
    get().beginTicking({
      pageId,
      roundedStart: rawStart,
      startISO,
      rawStart,
      propertyMap: workspace.propertyMap,
    });
    set({ isStarting: false, description });
    get().loadRunningTimers();
    get().loadRecentEntries();
  },

  /**
   * Applies a preset: sets workspace, description, and extra values, then starts
   * the timer immediately.
   */
  applyPreset: async (workspaceId, preset) => {
    const action = async () => {
      const state = get();
      if (state.selectedWorkspaceId !== workspaceId) {
        await get().selectWorkspace(workspaceId);
      }
      // A field load still in flight would reset extraValues when it lands,
      // wiping the preset's values merged in below.
      await waitForExtraFields(get);
      set({ description: preset.pageTitle || '' });
      if (preset.extraValues && Object.keys(preset.extraValues).length > 0) {
        const types = Object.fromEntries(get().extraFieldDefs.map((def) => [def.name, def.type]));
        const values = Object.fromEntries(
          Object.entries(preset.extraValues).map(([name, value]) => [
            name,
            types[name] ? coerceInputValue(types[name], value) : value,
          ])
        );
        set({ extraValues: { ...get().extraValues, ...values } });
      }
      await get().startTimer();
    };

    if (get().timer.running) {
      set({
        showAlreadyRunningWarning: true,
        pendingStartAction: async () => {
          const stopped = await get()._stopActivePage();
          if (!stopped) return;
          await action();
        }
      });
    } else {
      await action();
    }
  },

  /**
   * "Play" on a recent entry: starts a new timer in the same workspace, with
   * the same description, connected project and extra field values as that
   * entry, but starting now, with no end. Mirrors applyPreset's flow, except
   * the project/extra values are read from the source Notion page (its own
   * schema-backed source of truth) rather than from a saved preset.
   */
  startFromRecentEntry: async (entry) => {
    const action = async () => {
      const state = get();
      if (state.selectedWorkspaceId !== entry.workspaceId) {
        await get().selectWorkspace(entry.workspaceId);
      }
      await waitForExtraFields(get);

      const workspace = get().workspaces.find((w) => w.id === entry.workspaceId);
      // Bail out rather than starting an unrelated timer in whatever workspace
      // happens to still be selected.
      if (!workspace) return;

      const description = isFallbackDescription(entry.title, new Date(entry.startISO), get().dateFormat)
        ? ''
        : entry.title;
      set({ description });

      try {
        const props = await api.getPageProperties(entry.pageId);
        const pm = workspace.propertyMap;
        const relProp = pm?.timeEntryRelationProp;
        const projectId = relProp ? parsePropertyValue('relation', props[relProp]) || null : null;

        const extraValues = {};
        if (workspace.extraProps?.length && workspace.timeEntriesDbId) {
          const schema = await api.getDatabaseSchema(workspace.timeEntriesDbId).catch(() => ({}));
          for (const { propName: name } of workspace.extraProps) {
            const type = schema?.[name]?.type;
            if (!type || !WRITABLE_EXTRA_TYPES.includes(type)) continue;
            extraValues[name] = parsePropertyValue(type, props[name]);
          }
        }
        set({ selectedProjectId: projectId, extraValues });
      } catch {
        // Fall back to whatever selectWorkspace() already set (empty/active values),
        // the timer can still be started, just without the restored project/extras.
      }

      await get().startTimer();
    };

    if (get().timer.running) {
      set({
        showAlreadyRunningWarning: true,
        pendingStartAction: async () => {
          const stopped = await get()._stopActivePage();
          if (!stopped) return;
          await action();
        }
      });
    } else {
      await action();
    }
  },

  onConfirmAlreadyRunning: () => {
    set({ showAlreadyRunningWarning: false });
    const action = get().pendingStartAction;
    set({ pendingStartAction: null });
    if (action) action();
  },

  onCancelAlreadyRunning: () => {
    set({ showAlreadyRunningWarning: false, pendingStartAction: null });
  },

  // Only reachable while a timer is running; stops it (via the confirmation
  // dialog) and drops back to the idle form so a fresh timer can be composed.
  onPlusButtonClicked: () => {
    if (!get().timer.running) return;
    set({
      showAlreadyRunningWarning: true,
      pendingStartAction: async () => {
        const stopped = await get()._stopActivePage();
        if (!stopped) return;
        get().loadRunningTimers();
        get().loadRecentEntries();
      }
    });
  },

  beginTicking: ({ pageId, roundedStart, startISO, rawStart, propertyMap }) => {
    // Starting/restoring a timer replaces the existing ticker. This is also a
    // safety net for callers switching directly between running entries.
    const previousInterval = get().timer.tickInterval;
    if (previousInterval) clearInterval(previousInterval);
    const tickInterval = setInterval(() => {
      set((s) => ({
        timer: {
          ...s.timer,
          elapsedSeconds: Math.floor((Date.now() - s.timer.rawStart.getTime()) / 1000),
        },
      }));
    }, 1000);
    set({
      timer: {
        running: true,
        notionPageId: pageId,
        roundedStart,
        startISO,
        rawStart,
        propertyMap,
        elapsedSeconds: Math.floor((Date.now() - rawStart.getTime()) / 1000),
        tickInterval,
        workspaceId: get().selectedWorkspaceId,
      },
    });
    const ws = get().workspaces.find((w) => w.id === get().selectedWorkspaceId);
    api.setTimerRunning(true, ws?.color?.trim() || null);
  },

  updateStartTime: async (newStart) => {
    const state = get();
    if (!state.timer.running) return;
    const startISO = toNotionDateTime(newStart);
    const pm = state.timer.propertyMap;
    set((s) => ({
      timer: {
        ...s.timer,
        roundedStart: newStart,
        startISO,
        rawStart: newStart,
        elapsedSeconds: Math.max(0, Math.floor((Date.now() - newStart.getTime()) / 1000)),
      },
    }));
    api.saveSettings({
      activeTimer: {
        notionPageId: state.timer.notionPageId,
        roundedStart: startISO,
        rawStart: newStart.toISOString(),
        workspaceId: state.selectedWorkspaceId,
        projectId: state.selectedProjectId,
        projectName: '',
        description: state.description,
        propertyMap: pm,
      },
    });

    if (!pm?.timeEntryDateProp) return;
    try {
      await api.updatePage(state.timer.notionPageId, {
        [pm.timeEntryDateProp]: { date: { start: startISO, end: null } },
      });
    } catch (err) {
      const msg = errorMessage(err);
      if (isPageDeletedError(msg)) {
        get()._showDeletedPageDialog(state.timer);
        return;
      }
      get().addToast({ type: 'error', message: `${t('toastUpdateError')}: ${msg}` });
    }
  },

  /**
   * Stops the currently active timer's Notion page and clears the local
   * "active" slot (tray reset to idle). Does not touch `runningTimers` or
   * decide what happens next; callers do that (promote another running
   * timer, leave idle, or immediately start a new one). Returns true on
   * success, false if the write failed (a toast/dialog is shown either way).
   */
  _stopActivePage: async (manualEnd = null) => {
    const { timer, addToast } = get();
    if (!timer.running) return true;

    // The timer's own workspace decides the rounding, not whichever one the
    // picker happens to be showing.
    const timerWorkspace = get().workspaces.find((w) => w.id === timer.workspaceId);
    const rm = timerWorkspace?.roundingMinutes ?? get().getRoundingMinutes();
    const rounding = rm > 0;
    const rawStart = new Date(timer.rawStart);
    if (manualEnd && manualEnd.getTime() < rawStart.getTime()) {
      addToast({ type: 'error', message: t('toastEndBeforeStart') });
      return false;
    }
    const roundedStart = rounding ? computeStartTime(rawStart, rm) : rawStart;
    const endTime = rounding
      ? computeEndTime(roundedStart, manualEnd ?? new Date(), rm)
      : manualEnd ?? new Date();

    try {
      await api.stopTimeEntry({
        pageId: timer.notionPageId,
        startISO: toNotionDateTime(roundedStart),
        endISO: toNotionDateTime(endTime),
        props: timer.propertyMap,
      });
    } catch (err) {
      const msg = errorMessage(err);
      if (isPageDeletedError(msg)) {
        get()._showDeletedPageDialog(timer);
        return false;
      }
      addToast({
        type: 'error',
        sticky: true,
        message: `${t('toastStopError')}: ${msg}`,
        action: { label: t('toastStopRetry'), run: () => get().stopTimer(manualEnd) },
      });
      return false;
    }

    clearInterval(timer.tickInterval);
    get()._markStopped(timer.notionPageId);
    api.saveSettings({ activeTimer: null });
    api.setTimerRunning(false);

    const extraValues = {};
    for (const def of get().extraFieldDefs) {
      extraValues[def.name] = emptyValueFor(def.type);
    }
    set({ timer: { ...idleTimer }, description: '', extraValues });
    return true;
  },

  stopTimer: async (manualEnd = null) => {
    const { timer, isStopping } = get();
    if (isStopping || !timer.running) return;
    set({ isStopping: true });

    const stoppedPageId = timer.notionPageId;
    const ok = await get()._stopActivePage(manualEnd);
    set({ isStopping: false });
    if (!ok) return;

    const remainingRunning = get().runningTimers.filter((x) => x.pageId !== stoppedPageId);
    set({ runningTimers: remainingRunning });
    if (remainingRunning.length > 0) {
      get().loadRunningTimer(remainingRunning[0]);
    }
    get().loadRunningTimers();
    get().loadRecentEntries();
  },

  // ---- Running timers loaded from Notion ----

  loadRunningTimers: async () => {
    const { workspaces, notionAccount, runningTimersLoading } = get();
    if (!notionAccount || !workspaces.length || runningTimersLoading) return;

    set({ runningTimersLoading: true });
    const allRunning = [];
    // Workspaces whose query failed (offline, waking from sleep, rate limited).
    // Their result is unknown, not empty: a failed query used to read as
    // "nothing running", which cleared the active timer as "stopped elsewhere"
    // on every network blip.
    const failedWorkspaceIds = new Set();

    for (const ws of workspaces) {
      const pm = ws.propertyMap;
      if (!ws.timeEntriesDbId || !pm?.timeEntryStatusProp || !pm?.timeEntryStatusRunning) continue;
      try {
        const pages = await api.fetchRunningTimers(
          ws.timeEntriesDbId,
          pm.timeEntryStatusProp,
          pm.timeEntryStatusRunning,
          pm.timeEntryDateProp,
        );
        for (const page of pages) {
          allRunning.push({ ...page, workspaceId: ws.id, workspaceName: ws.name, propertyMap: pm });
        }
      } catch {
        // Keep what was last known for this workspace (same as the Android app).
        failedWorkspaceIds.add(ws.id);
        allRunning.push(...get().runningTimers.filter((rt) => rt.workspaceId === ws.id));
      }
    }

    allRunning.sort((a, b) => new Date(b.startISO) - new Date(a.startISO));

    // Drop entries that were just stopped locally (Notion replication lag), and
    // prune the guard map of anything older than its TTL.
    const now = Date.now();
    const recentlyStopped = {};
    for (const [pid, ts] of Object.entries(get().recentlyStopped)) {
      if (now - ts < RECENTLY_STOPPED_TTL_MS) recentlyStopped[pid] = ts;
    }
    const visibleRunning = allRunning.filter((rt) => !recentlyStopped[rt.pageId]);

    // Merge deltas into the existing list instead of replacing it wholesale, so
    // entries that are still running keep their identity/position (no flicker or
    // momentary disappearance while switching timers).
    set({ runningTimersLoading: false, runningTimers: mergeById(get().runningTimers, visibleRunning), recentlyStopped });

    // If the active timer is no longer running in Notion (stopped elsewhere), clear it
    const activeTimer = get().timer;
    if (activeTimer.running && activeTimer.notionPageId) {
      const stillRunning = visibleRunning.some((rt) => rt.pageId === activeTimer.notionPageId);
      if (failedWorkspaceIds.has(activeTimer.workspaceId) || wasJustStarted(activeTimer.notionPageId)) {
        // Couldn't check, or Notion's query hasn't caught up with a timer
        // started here yet, keep it running until the next refresh.
      } else if (!stillRunning) {
        clearInterval(activeTimer.tickInterval);
        api.saveSettings({ activeTimer: null });
        api.setTimerRunning(false);
        set({ timer: { ...idleTimer }, description: '', selectedProjectId: null });
        get().addToast({ type: 'info', message: t('timerStoppedElsewhere') });
      } else {
        get().refreshActiveTimerFieldsFromNotion(activeTimer.notionPageId, get().selectedWorkspaceId, activeTimer.propertyMap);
      }
    } else if (!activeTimer.running && visibleRunning.length > 0) {
      get().loadRunningTimer(visibleRunning[0]);
    }
  },

  _markStopped: (pageId) => {
    if (!pageId) return;
    set((s) => ({ recentlyStopped: { ...s.recentlyStopped, [pageId]: Date.now() } }));
  },

  loadRecentEntries: async () => {
    const { workspaces, notionAccount, recentEntriesLoading } = get();
    if (!notionAccount || !workspaces.length || recentEntriesLoading) return;

    set({ recentEntriesLoading: true });
    const allRecent = [];
    for (const ws of workspaces) {
      const pm = ws.propertyMap;
      if (!ws.timeEntriesDbId || !pm?.timeEntryStatusProp || !pm?.timeEntryStatusDone) continue;
      try {
        const pages = await api.fetchRecentEntries(
          ws.timeEntriesDbId,
          pm.timeEntryStatusProp,
          pm.timeEntryStatusDone,
          pm.timeEntryDateProp,
        );
        for (const page of pages) {
          allRecent.push({ ...page, workspaceId: ws.id, workspaceName: ws.name, propertyMap: pm });
        }
      } catch {
        // silently ignore per-workspace errors
      }
    }
    allRecent.sort((a, b) => (b.endISO || b.startISO || '').localeCompare(a.endISO || a.startISO || ''));
    const top = allRecent.slice(0, 15);
    // Merge deltas (keeping existing entries' identity), then re-apply the recent
    // ordering so newly completed entries surface without a full list reload.
    const merged = mergeById(get().recentEntries, top)
      .filter((r) => top.some((x) => x.pageId === r.pageId))
      .sort((a, b) => (b.endISO || b.startISO || '').localeCompare(a.endISO || a.startISO || ''));
    set({ recentEntriesLoading: false, recentEntries: merged });
  },

  confirmDelete: (entry, context) => set({ deleteConfirm: { pageId: entry.pageId, title: entry.title, context } }),
  cancelDelete: () => set({ deleteConfirm: null }),

  deleteEntry: async () => {
    const { deleteConfirm } = get();
    if (!deleteConfirm) return;
    const { pageId, context } = deleteConfirm;
    set({ deleteConfirm: null });
    try {
      await api.deletePage(pageId);
      get()._markStopped(pageId);
      if (context === 'active') {
        const { timer } = get();
        if (timer.tickInterval) clearInterval(timer.tickInterval);
        api.saveSettings({ activeTimer: null });
        api.setTimerRunning(false);
        set((s) => ({
          timer: { ...idleTimer },
          runningTimers: s.runningTimers.filter((r) => r.pageId !== pageId),
        }));
      } else if (context === 'running') {
        set((s) => ({ runningTimers: s.runningTimers.filter((r) => r.pageId !== pageId) }));
      } else {
        set((s) => ({ recentEntries: s.recentEntries.filter((r) => r.pageId !== pageId) }));
      }
      get().loadRunningTimers();
      get().loadRecentEntries();
    } catch (err) {
      get().addToast({ type: 'error', message: `${t('toastDeleteError')}: ${errorMessage(err)}` });
    }
  },

  openNotionPage: (url) => { api.openExternal(url); },

  loadRunningTimer: async (rt) => {
    const state = get();

    if (state.timer.running && state.timer.tickInterval) {
      clearInterval(state.timer.tickInterval);
    }

    // Only switch workspace when actually needed; this avoids resetting extraFieldDefs/extraValues
    if (state.selectedWorkspaceId !== rt.workspaceId) {
      await get().selectWorkspace(rt.workspaceId);
    }

    // A title this app generated for an empty description reads back as an
    // empty description, so the input is never pre-filled with a date.
    const description = isFallbackDescription(rt.title, new Date(rt.startISO), get().dateFormat)
      ? ''
      : rt.title;

    api.saveSettings({
      activeTimer: {
        notionPageId: rt.pageId,
        roundedStart: rt.startISO,
        rawStart: rt.startISO,
        workspaceId: rt.workspaceId,
        projectId: null,
        projectName: '',
        description,
        propertyMap: rt.propertyMap,
      },
    });

    // Only update description; leave runningTimers untouched so the list stays stable.
    // The active/inactive styling is derived from timer.notionPageId, not the list order.
    set({ description });

    get().beginTicking({
      pageId: rt.pageId,
      roundedStart: new Date(rt.startISO),
      startISO: rt.startISO,
      rawStart: new Date(rt.startISO),
      propertyMap: rt.propertyMap,
    });

    // The running-timers list only carries title/start/url, so fetch the page's
    // full properties to restore its connected project and extra field values.
    await get().refreshActiveTimerFieldsFromNotion(rt.pageId, rt.workspaceId, rt.propertyMap);
  },

  /**
   * Re-reads the active timer's connected project and extra field values straight
   * from its Notion page, so Notion stays the source of truth instead of whatever
   * was last cached locally (e.g. if the page was edited directly in Notion, or
   * from another device, while this device had it loaded). Called when a running
   * timer is first loaded, and on every subsequent sync (manual refresh, 60s
   * auto-refresh) while it's still the active one.
   */
  refreshActiveTimerFieldsFromNotion: async (pageId, workspaceId, propertyMap) => {
    const workspace = get().workspaces.find((w) => w.id === workspaceId);
    if (!workspace) return;
    try {
      const props = await api.getPageProperties(pageId);
      if (get().timer.notionPageId !== pageId) return;

      const relProp = propertyMap?.timeEntryRelationProp;
      const projectId = relProp ? parsePropertyValue('relation', props[relProp]) || null : null;
      let projectName = '';
      if (projectId && workspace.projectsDbId) {
        const pages = await api.fetchDatabasePages(workspace.projectsDbId).catch(() => []);
        projectName = pages.find((p) => p.id === projectId)?.name ?? '';
      }

      // Don't rely on the store's extraFieldDefs; it may still belong to a
      // different workspace or not have finished loading yet. Fetch the live
      // schema directly, same as loadExtraFields does, to get the real type
      // for each configured extra field.
      const extraValues = {};
      if (workspace.extraProps?.length && workspace.timeEntriesDbId) {
        const schema = await api.getDatabaseSchema(workspace.timeEntriesDbId).catch(() => ({}));
        for (const { propName: name } of workspace.extraProps) {
          const type = schema?.[name]?.type;
          if (!type || !WRITABLE_EXTRA_TYPES.includes(type)) continue;
          extraValues[name] = parsePropertyValue(type, props[name]);
        }
      }

      if (get().timer.notionPageId !== pageId) return;
      set({ selectedProjectId: projectId, extraValues });
      const { timer: current } = get();
      api.saveSettings({
        activeTimer: {
          notionPageId: pageId,
          roundedStart: current.startISO,
          rawStart: current.rawStart?.toISOString?.() ?? current.startISO,
          workspaceId,
          projectId,
          projectName,
          description: get().description,
          propertyMap,
          extraValues,
        },
      });
    } catch {
      // Keep whatever values were already loaded on failure; the timer itself
      // is already usable.
    }
  },

  stopOtherRunningTimer: async (rt) => {
    const ws = get().workspaces.find((w) => w.id === rt.workspaceId);
    const rm = ws?.roundingMinutes ?? 15;
    const rounding = rm > 0;
    const rawStart = new Date(rt.startISO);
    const roundedStart = rounding ? computeStartTime(rawStart, rm) : rawStart;
    const endTime = rounding ? computeEndTime(roundedStart, new Date(), rm) : new Date();
    try {
      await api.stopTimeEntry({
        pageId: rt.pageId,
        startISO: toNotionDateTime(roundedStart),
        endISO: toNotionDateTime(endTime),
        props: rt.propertyMap,
      });
      get()._markStopped(rt.pageId);
      set((s) => ({ runningTimers: s.runningTimers.filter((x) => x.pageId !== rt.pageId) }));
      get().loadRunningTimers();
      get().loadRecentEntries();
    } catch (err) {
      get().addToast({ type: 'error', message: `${t('toastStopError')}: ${errorMessage(err)}` });
    }
  },

  // ---- Theme ----

  setTheme: async (theme) => {
    await api.saveSettings({ theme });
    set({ theme });
    get().applyTheme();
    get().touchSettingsUpdatedAt();
  },

  applyTheme: () => {
    const { theme } = get();
    // Keeps the Mica backdrop and native controls in the same mode as the UI.
    api.setNativeTheme?.(theme);
    // "auto" resolves to the system mode here, so the CSS only has to know
    // about the explicit light/dark classes.
    const mq = typeof window.matchMedia === 'function'
      ? window.matchMedia('(prefers-color-scheme: dark)')
      : null;
    if (mq && !followingSystemTheme) {
      followingSystemTheme = true;
      mq.addEventListener('change', () => get().applyTheme());
    }
    const dark = theme === 'dark' || (theme !== 'light' && (mq ? mq.matches : true));
    const root = document.documentElement;
    root.classList.add(dark ? 'dark' : 'light');
    root.classList.remove(dark ? 'light' : 'dark');
  },

  // ---- Refresh ----

  refreshAll: () => {
    const s = get();
    if (s.notionAccount && s.syncEnabled) {
      get().syncNow();
    }
    get().loadRunningTimers();
    get().loadRecentEntries();
  },

  // ---- Settings sync (sign-in server) ----

  /** Turns syncing on or off on this device. Turning it on syncs straight away. */
  setSyncEnabled: async (syncEnabled) => {
    await api.saveSettings({ syncEnabled });
    set({ syncEnabled, syncError: null });
    if (syncEnabled) get().syncNow();
  },

  /** Deletes the copy on the server and stops syncing on this device. */
  deleteSyncedSettings: async () => {
    try {
      await api.deleteSyncedSettings();
      await api.saveSettings({ syncEnabled: false, lastSyncedUpdatedAt: undefined });
      set({ syncEnabled: false, lastSyncedUpdatedAt: null, syncError: null });
      get().addToast({ type: 'success', message: t('toastSyncDeleted') });
    } catch (err) {
      get().addToast({ type: 'error', message: errorMessage(err) });
    }
  },

  /** Pull or push, whichever side is newer (lib/syncDecision.js, same rule as Android). */
  syncNow: async () => {
    const state = get();
    if (!state.notionAccount || !state.syncEnabled || state.syncing) return;
    set({ syncing: true, syncError: null });
    try {
      const remote = await api.getSyncedSettings();
      const local = get();
      const decision = syncDecision(
        {
          settingsUpdatedAt: local.settingsUpdatedAt,
          lastSyncedUpdatedAt: local.lastSyncedUpdatedAt,
          hasSettings: local.workspaces.length > 0,
        },
        remote
      );
      if (decision === 'pull') {
        await pullSyncPayload(set, get, remote);
      } else if (decision === 'push') {
        // Settings from before sync existed have no timestamp yet.
        const updatedAt = local.settingsUpdatedAt || new Date().toISOString();
        const result = await api.putSyncedSettings(buildSyncPayload(local, updatedAt));
        if (result.accepted) {
          await api.saveSettings({ settingsUpdatedAt: updatedAt, lastSyncedUpdatedAt: updatedAt });
          set({ settingsUpdatedAt: updatedAt, lastSyncedUpdatedAt: updatedAt });
        } else if (result.payload) {
          // Another device was faster with newer settings.
          await pullSyncPayload(set, get, result.payload);
        }
      }
      set({ syncing: false });
    } catch (err) {
      set({ syncing: false, syncError: errorMessage(err) });
    }
  },

  // ---- Edit running timer ----

  /** Writes the running entry's details. Resolves true once Notion has them. */
  updateTimerDetails: async (projectId, description, extraValues) => {
    const state = get();
    if (!state.timer.running) return false;
    const pm = state.timer.propertyMap;
    // The description may be cleared; the page itself never gets an empty title
    // (that shows up as "Untitled" in Notion), so it falls back to the entry's
    // start date, same as when the timer was started without a description.
    const newDescription = description.trim();
    const startDate = state.timer.rawStart
      ? new Date(state.timer.rawStart)
      : new Date(state.timer.startISO || Date.now());
    const newTitle = newDescription || fallbackDescription(startDate, state.dateFormat);

    const properties = {};
    if (pm?.timeEntryTitleProp) {
      properties[pm.timeEntryTitleProp] = {
        title: [{ text: { content: newTitle } }],
      };
    }
    // The timer view has no project picker, so a null projectId means "not
    // known on this device", not "cleared"; writing an empty relation here
    // wiped the page's project link on every description/extra-field save.
    // When the same property is configured as an extra field, that field is
    // the single source of truth. (Same rules as the Android app.)
    const relProp = pm?.timeEntryRelationProp;
    const relationIsExtraField = state.extraFieldDefs.some((def) => def.name === relProp);
    if (relProp && !relationIsExtraField && projectId) {
      properties[relProp] = { relation: [{ id: projectId }] };
    }
    for (const def of state.extraFieldDefs) {
      const value = buildPropertyValue(def.type, extraValues[def.name]);
      if (value) properties[def.name] = value;
      else if (value === null && def.type !== 'checkbox') {
        // Explicitly clear optional fields when set to empty
        properties[def.name] = buildEmptyNotionValue(def.type);
      }
    }

    try {
      await api.updatePage(state.timer.notionPageId, properties);
    } catch (err) {
      const msg = errorMessage(err);
      if (isPageDeletedError(msg)) {
        get()._showDeletedPageDialog(state.timer);
        return false;
      }
      get().addToast({ type: 'error', message: `${t('toastUpdateError')}: ${msg}` });
      return false;
    }

    // The timer may have been stopped or switched while the write was in
    // flight; don't put its values back into the (now idle/other) form.
    if (get().timer.notionPageId !== state.timer.notionPageId) return true;

    set({
      description: newDescription,
      selectedProjectId: projectId,
      extraValues,
    });
    const updatedTimer = {
      notionPageId: state.timer.notionPageId,
      roundedStart: state.timer.startISO,
      rawStart: state.timer.rawStart?.toISOString?.() ?? state.timer.startISO,
      workspaceId: state.selectedWorkspaceId,
      projectId,
      projectName: '',
      description: newDescription,
      propertyMap: pm,
      extraValues,
    };
    api.saveSettings({ activeTimer: updatedTimer });

    // Refresh to ensure consistency
    get().loadRunningTimers();
    get().loadRecentEntries();
    return true;
  },

  // ---- Deleted page handling ----

  _showDeletedPageDialog: (timer) => {
    set({
      isStopping: false,
      deletedPageInfo: {
        notionPageId: timer.notionPageId,
        title: get().description || '',
        startISO: timer.startISO || timer.roundedStart || null,
      },
    });
  },

  dismissDeletedPage: () => {
    const { deletedPageInfo, timer } = get();
    if (!deletedPageInfo) return;
    // Clear the active timer locally
    if (timer.running) {
      clearInterval(timer.tickInterval);
      api.saveSettings({ activeTimer: null });
      api.setTimerRunning(false);
      set({ timer: { ...idleTimer }, deletedPageInfo: null });
    } else {
      set({ deletedPageInfo: null });
    }
    get().loadRunningTimers();
    get().loadRecentEntries();
  },
}));

// ---- Helpers ----

/** Resolves once the selected workspace's extra-field definitions are loaded. */
async function waitForExtraFields(get) {
  if (get().extrasLoading && extrasLoadPromise) {
    await extrasLoadPromise.catch(() => {});
  }
}

/** Builds the payload written to the sync page's code block. Excludes the
 * Notion API key and device-local toggles (alwaysOnTop, startWithWindows). */
function buildSyncPayload(state, updatedAt) {
  return {
    updatedAt,
    workspaces: state.workspaces,
    theme: state.theme,
    locale: state.locale,
    dateFormat: state.dateFormat,
    timeFormat: state.timeFormat,
    showNotionLink: state.showNotionLink,
    showArchiveAction: state.showArchiveAction,
  };
}

/** Takes the server's settings and remembers that this device is in step with them. */
async function pullSyncPayload(set, get, remote) {
  applySyncPayload(set, get, remote);
  await api.saveSettings({ settingsUpdatedAt: remote.updatedAt, lastSyncedUpdatedAt: remote.updatedAt });
  set({ settingsUpdatedAt: remote.updatedAt, lastSyncedUpdatedAt: remote.updatedAt });
}

/** Applies a fetched sync payload to local state and persists it. */
function applySyncPayload(set, get, payload) {
  const patch = { workspaces: (payload.workspaces || []).map(withPresetTitles) };
  if (payload.theme !== undefined) patch.theme = payload.theme;
  if (payload.locale !== undefined) { setLocale(payload.locale); patch.locale = payload.locale; }
  if (payload.dateFormat !== undefined) patch.dateFormat = payload.dateFormat;
  if (payload.timeFormat !== undefined) patch.timeFormat = payload.timeFormat;
  if (payload.showNotionLink !== undefined) patch.showNotionLink = payload.showNotionLink;
  if (payload.showArchiveAction !== undefined) patch.showArchiveAction = payload.showArchiveAction;
  // A pulled payload can carry different workspaces, presets and language,
  // all three are baked into the tray menu, so it is rebuilt once the main
  // process has the new values on disk.
  Promise.resolve(api.saveSettings(patch)).then(() => api.notifySettingsChanged?.());
  set(patch);
  get().applyTheme();
}

/**
 * Merges `incoming` into `current` keyed by pageId: existing entries keep their
 * position and are updated in place, entries no longer present are dropped, and
 * genuinely new entries are appended. This avoids clearing and fully reloading
 * the list on every refresh.
 */
function mergeById(current, incoming) {
  const incomingById = new Map(incoming.map((x) => [x.pageId, x]));
  const merged = current
    .filter((x) => incomingById.has(x.pageId))
    .map((x) => ({ ...x, ...incomingById.get(x.pageId) }));
  const existingIds = new Set(merged.map((x) => x.pageId));
  for (const item of incoming) {
    if (!existingIds.has(item.pageId)) merged.push(item);
  }
  return merged;
}

function isPageDeletedError(msg) {
  const lower = (msg || '').toLowerCase();
  return (
    lower.includes('404') ||
    lower.includes('not_found') ||
    lower.includes('object_not_found') ||
    lower.includes('could not find page') ||
    lower.includes('page not found')
  );
}

function buildEmptyNotionValue(type) {
  switch (type) {
    case 'rich_text': return { rich_text: [] };
    case 'select': return { select: null };
    case 'multi_select': return { multi_select: [] };
    case 'url': return { url: null };
    case 'email': return { email: null };
    case 'phone_number': return { phone_number: null };
    case 'number': return { number: null };
    case 'date': return { date: null };
    case 'relation': return { relation: [] };
    default: return null;
  }
}
