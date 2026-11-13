import { useEffect, useState } from 'react';
import { useStore } from '../store/useStore.js';
import { propertiesByType, statusOptions } from '../lib/schema.js';
import { WRITABLE_EXTRA_TYPES } from '../lib/notionValues.js';
import { t } from '../lib/i18n.js';
import { errorMessage } from '../lib/errors.js';
import { getWorkspaceColorVariants } from '../lib/color.js';
import SearchableSelect from '../components/SearchableSelect.jsx';
import {
  Delete20Regular,
  Edit20Regular,
  ArrowUp20Regular,
  ArrowDown20Regular,
  ArrowClockwise20Regular,
  CheckmarkCircle20Regular,
  Dismiss20Regular,
} from '@fluentui/react-icons';

export default function Settings() {
  const notionAccount = useStore((s) => s.notionAccount);
  const signedIn = !!notionAccount;
  const signingIn = useStore((s) => s.signingIn);
  const workspaces = useStore((s) => s.workspaces);
  const alwaysOnTop = useStore((s) => s.alwaysOnTop);
  const startWithWindows = useStore((s) => s.startWithWindows);
  const theme = useStore((s) => s.theme);
  const signIn = useStore((s) => s.signIn);
  const cancelSignIn = useStore((s) => s.cancelSignIn);
  const signOut = useStore((s) => s.signOut);
  const deleteWorkspace = useStore((s) => s.deleteWorkspace);
  const [confirmingRemove, setConfirmingRemove] = useState(null);
  const moveWorkspace = useStore((s) => s.moveWorkspace);
  const toggleAlwaysOnTop = useStore((s) => s.toggleAlwaysOnTop);
  const toggleStartWithWindows = useStore((s) => s.toggleStartWithWindows);
  const showNotionLink = useStore((s) => s.showNotionLink);
  const showArchiveAction = useStore((s) => s.showArchiveAction);
  const setShowNotionLink = useStore((s) => s.setShowNotionLink);
  const setShowArchiveAction = useStore((s) => s.setShowArchiveAction);
  const setTheme = useStore((s) => s.setTheme);
  const setView = useStore((s) => s.setView);
  const timerRunning = useStore((s) => s.timer.running);
  const locale = useStore((s) => s.locale);
  const dateFormat = useStore((s) => s.dateFormat);
  const timeFormat = useStore((s) => s.timeFormat);
  const setLocale = useStore((s) => s.setLocale);
  const setDateFormat = useStore((s) => s.setDateFormat);
  const setTimeFormat = useStore((s) => s.setTimeFormat);
  const syncEnabled = useStore((s) => s.syncEnabled);
  const syncing = useStore((s) => s.syncing);
  const syncError = useStore((s) => s.syncError);
  const setSyncEnabled = useStore((s) => s.setSyncEnabled);
  const deleteSyncedSettings = useStore((s) => s.deleteSyncedSettings);
  const [confirmingDeleteSync, setConfirmingDeleteSync] = useState(false);

  const [showAddWorkspace, setShowAddWorkspace] = useState(false);
  const [editingWorkspace, setEditingWorkspace] = useState(null);


  return (
    <div className="flex flex-col h-full overflow-y-auto px-5 py-4 gap-5">
      <div className="flex items-center gap-2">
        <button
          onClick={() => setView('timer')}
          className="text-fg2 hover:text-fg1 text-lg"
          title={t('titleBack')}
        >
          {t('btnBack')}
        </button>
        <h1 className="text-base font-semibold">{t('settingsTitle')}</h1>
      </div>

      <div>
        <label className="block text-xs text-fg2 mb-1">{t('labelNotionAccount')}</label>
        {signedIn ? (
          <>
            <div className="flex items-center justify-between bg-control border border-stroke rounded-md px-3 py-2 gap-2">
              <span className="text-sm truncate flex items-center gap-1.5">
                <CheckmarkCircle20Regular className="w-4 h-4 shrink-0 text-success" />
                {t('signedInTo').replace('{workspace}', notionAccount.workspaceName || 'Notion')}
              </span>
              <button
                onClick={signOut}
                disabled={signingIn}
                className="text-xs text-fg3 hover:text-danger shrink-0 disabled:opacity-50"
              >
                {t('btnSignOut')}
              </button>
            </div>
            <div className="mt-2 flex items-center gap-2">
              <button
                onClick={signIn}
                disabled={signingIn}
                title={t('titleChoosePages')}
                className="px-3 py-1.5 rounded-md bg-control hover:bg-control-hover active:bg-control-pressed border border-stroke disabled:opacity-50 text-sm"
              >
                {signingIn ? t('signInWaiting') : t('btnChoosePages')}
              </button>
              {signingIn && (
                <button onClick={cancelSignIn} className="px-3 py-1.5 rounded-md text-sm text-fg2 hover:bg-subtle-hover">
                  {t('btnCancel')}
                </button>
              )}
            </div>
          </>
        ) : (
          <>
            <p className="text-xs text-fg3 mb-2">{t('signInHint')}</p>
            <div className="flex items-center gap-2">
              <button
                onClick={signIn}
                disabled={signingIn}
                className="px-4 py-2 rounded-md bg-accent hover:bg-accent-hover active:bg-accent-pressed text-on-accent text-sm font-semibold disabled:opacity-60"
              >
                {signingIn ? t('signInWaiting') : t('btnSignIn')}
              </button>
              {signingIn && (
                <button onClick={cancelSignIn} className="px-3 py-1.5 rounded-md text-sm text-fg2 hover:bg-subtle-hover">
                  {t('btnCancel')}
                </button>
              )}
            </div>
          </>
        )}
      </div>

      {/* Settings sync through the sign-in server */}
      <div>
        <label className="block text-xs text-fg2 mb-1">{t('labelSync')}</label>
        <label className="flex items-center gap-2 text-sm cursor-pointer">
          <input
            type="checkbox"
            checked={syncEnabled}
            disabled={!signedIn}
            onChange={(e) => setSyncEnabled(e.target.checked)}
            className="accent-accent"
          />
          <span className="flex items-center gap-1.5">
            {t('labelSyncEnabled')}
            {syncEnabled && signedIn && syncing && <ArrowClockwise20Regular className="w-4 h-4 text-fg3" />}
          </span>
        </label>
        <p className="text-xs text-fg3 mt-1">{t('syncHint')}</p>
        {syncEnabled && signedIn && syncError && <p className="text-xs text-danger mt-1">{syncError}</p>}
        {signedIn && (
          confirmingDeleteSync ? (
            <div className="mt-2 flex flex-wrap items-center gap-2">
              <span className="text-xs text-fg2">{t('confirmDeleteSync')}</span>
              <button
                onClick={() => {
                  setConfirmingDeleteSync(false);
                  deleteSyncedSettings();
                }}
                className="px-3 py-1.5 rounded-md text-sm text-danger hover:bg-subtle-hover"
              >
                {t('btnDeleteSync')}
              </button>
              <button
                onClick={() => setConfirmingDeleteSync(false)}
                className="px-3 py-1.5 rounded-md text-sm text-fg2 hover:bg-subtle-hover"
              >
                {t('btnCancel')}
              </button>
            </div>
          ) : (
            <button
              onClick={() => setConfirmingDeleteSync(true)}
              className="mt-1 text-xs text-fg3 hover:text-danger"
            >
              {t('btnDeleteSync')}
            </button>
          )
        )}
      </div>

      <div>
        <label className="block text-xs text-fg2 mb-1">{t('labelWorkspaces')}</label>
        <div className="flex flex-col gap-1">
          {workspaces.length === 0 && (
            <div className="text-xs text-fg3 italic py-1">{t('noWorkspacesYet')}</div>
          )}
          {workspaces.map((w, i) => (
            <div key={w.id}>
              <div className="flex items-center justify-between bg-control border border-stroke rounded-md px-3 py-2">
                <span className="text-sm truncate">{w.name}</span>
                <span className="flex items-center gap-1.5 ml-2 shrink-0">
                  <button
                    onClick={() => moveWorkspace(w.id, 'up')}
                    disabled={i === 0}
                    title={t('titleMoveUp')}
                    className="text-fg3 hover:text-fg1 disabled:opacity-30 p-0.5 flex items-center justify-center"
                  >
                    <ArrowUp20Regular className="w-4 h-4" />
                  </button>
                  <button
                    onClick={() => moveWorkspace(w.id, 'down')}
                    disabled={i === workspaces.length - 1}
                    title={t('titleMoveDown')}
                    className="text-fg3 hover:text-fg1 disabled:opacity-30 p-0.5 flex items-center justify-center"
                  >
                    <ArrowDown20Regular className="w-4 h-4" />
                  </button>

                  <button
                    onClick={() => setEditingWorkspace(w)}
                    title={t('modalTitleEditWorkspace')}
                    className="text-fg3 hover:text-fg1 p-0.5 flex items-center justify-center"
                  >
                    <Edit20Regular className="w-4 h-4" />
                  </button>
                  <button
                    onClick={() => setConfirmingRemove(w)}
                    disabled={timerRunning}
                    title={timerRunning ? t('titleDeleteWorkspaceDisabled') : t('titleDeleteWorkspace')}
                    className="text-fg3 hover:text-danger disabled:opacity-40 p-0.5 flex items-center justify-center"
                  >
                    <Delete20Regular className="w-4 h-4" />
                  </button>
                </span>
              </div>

            </div>
          ))}
        </div>
        <div className="flex flex-wrap gap-2 mt-2">
          <button
            onClick={() => setShowAddWorkspace(true)}
            disabled={!signedIn}
            title={!signedIn ? t('warnNotSignedIn') : undefined}
            className="px-3 py-1.5 rounded-md bg-control hover:bg-control-hover active:bg-control-pressed border border-stroke disabled:opacity-50 text-sm"
          >
            {t('btnAddWorkspace')}
          </button>
        </div>
      </div>

      <label className="flex items-center gap-2 text-sm cursor-pointer">
        <input
          type="checkbox"
          checked={alwaysOnTop}
          onChange={(e) => toggleAlwaysOnTop(e.target.checked)}
          className="accent-accent"
        />
        {t('labelAlwaysOnTop')}
      </label>

      <label className="flex items-center gap-2 text-sm cursor-pointer">
        <input
          type="checkbox"
          checked={startWithWindows}
          onChange={(e) => toggleStartWithWindows(e.target.checked)}
          className="accent-accent"
        />
        {t('labelStartWithWindows')}
      </label>

      {/* Notion entry actions */}
      <div>
        <label className="block text-xs text-fg2 mb-1">{t('labelNotionActions')}</label>
        <div className="flex flex-col gap-2">
          <label className="flex items-center gap-2 text-sm cursor-pointer">
            <input
              type="checkbox"
              checked={showNotionLink}
              onChange={(e) => setShowNotionLink(e.target.checked)}
              className="accent-accent"
            />
            {t('labelShowNotionLink')}
          </label>
          <label className="flex items-center gap-2 text-sm cursor-pointer">
            <input
              type="checkbox"
              checked={showArchiveAction}
              onChange={(e) => setShowArchiveAction(e.target.checked)}
              className="accent-accent"
            />
            {t('labelShowArchiveAction')}
          </label>
        </div>
      </div>

      {/* Theme */}
      <div>
        <label className="block text-xs text-fg2 mb-1">{t('labelTheme')}</label>
        <div className="flex gap-3">
          {['auto', 'light', 'dark'].map((opt) => (
            <label key={opt} className="flex items-center gap-1.5 text-sm cursor-pointer">
              <input
                type="radio"
                name="theme"
                value={opt}
                checked={theme === opt}
                onChange={() => setTheme(opt)}
                className="accent-accent"
              />
              {t(`theme_${opt}`)}
            </label>
          ))}
        </div>
      </div>

      {/* Language */}
      <div>
        <label className="block text-xs text-fg2 mb-1">{t('labelLanguage')}</label>
        <select
          value={locale}
          onChange={(e) => setLocale(e.target.value)}
          className="w-full bg-control border border-stroke rounded-md px-2.5 py-1.5 text-xs text-fg1 focus:outline-none focus:border-accent"
        >
          <option value="system">{t('optSystem')}</option>
          <option value="en-GB">English</option>
          <option value="nl-BE">Nederlands</option>
          <option value="fr">Français</option>
          <option value="de">Deutsch</option>
          <option value="es">Español</option>
          <option value="it">Italiano</option>
        </select>
      </div>

      {/* Date Format */}
      <div>
        <label className="block text-xs text-fg2 mb-1">{t('labelDateFormat')}</label>
        <select
          value={dateFormat}
          onChange={(e) => setDateFormat(e.target.value)}
          className="w-full bg-control border border-stroke rounded-md px-2.5 py-1.5 text-xs text-fg1 focus:outline-none focus:border-accent"
        >
          <option value="DD/MM/YYYY">DD/MM/YYYY</option>
          <option value="MM/DD/YYYY">MM/DD/YYYY</option>
          <option value="YYYY-MM-DD">YYYY-MM-DD</option>
        </select>
      </div>

      {/* Time Format */}
      <div>
        <label className="block text-xs text-fg2 mb-1">{t('labelTimeFormat')}</label>
        <select
          value={timeFormat}
          onChange={(e) => setTimeFormat(e.target.value)}
          className="w-full bg-control border border-stroke rounded-md px-2.5 py-1.5 text-xs text-fg1 focus:outline-none focus:border-accent"
        >
          <option value="12h">{t('opt12h')}</option>
          <option value="24h">{t('opt24h')}</option>
        </select>
      </div>

      {showAddWorkspace && <WorkspaceModal onClose={() => setShowAddWorkspace(false)} />}
      {editingWorkspace && (
        <WorkspaceModal
          workspace={editingWorkspace}
          onClose={() => setEditingWorkspace(null)}
        />
      )}
      {confirmingRemove && (
        <div className="fixed inset-0 bg-smoke flex items-center justify-center z-[60] px-4">
          <div className="bg-dialog border border-surface-stroke rounded-lg shadow-dialog p-4 max-w-xs w-full">
            <p className="text-sm text-fg1 mb-1">{t('confirmRemoveWorkspaceTitle')}</p>
            <p className="text-xs text-fg3 mb-1 truncate">&ldquo;{confirmingRemove.name}&rdquo;</p>
            <p className="text-xs text-fg3 mb-4">{t('confirmRemoveWorkspaceBody')}</p>
            <div className="flex gap-2 justify-end">
              <button
                type="button"
                onClick={() => setConfirmingRemove(null)}
                className="px-3 py-1.5 text-sm text-fg2 hover:text-fg1 transition-colors"
              >
                {t('btnCancel')}
              </button>
              <button
                type="button"
                onClick={() => {
                  deleteWorkspace(confirmingRemove.id);
                  setConfirmingRemove(null);
                }}
                className="px-3 py-1.5 text-sm bg-red-700 hover:bg-red-600 text-white rounded-md font-medium transition-colors"
              >
                {t('btnRemove')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

const EMPTY_SELECTION = {
  timeEntryDateProp: '',
  timeEntryStatusProp: '',
  timeEntryStatusRunning: '',
  timeEntryStatusDone: '',
};

const MAX_EXTRA_PROPS = 5;

/** Add (no `workspace` prop) or edit (`workspace` provided) a workspace. */
function WorkspaceModal({ workspace, onClose }) {
  const addWorkspace = useStore((s) => s.addWorkspace);
  const updateWorkspace = useStore((s) => s.updateWorkspace);
  const addToast = useStore((s) => s.addToast);

  const [name, setName] = useState(workspace?.name || '');
  const [timeEntriesDbId, setTimeEntriesDbId] = useState(workspace?.timeEntriesDbId || '');
  const [roundingMinutes, setRoundingMinutes] = useState(
    workspace?.roundingMinutes ?? (workspace?.roundingEnabled === false ? 0 : 15)
  );
  const [sel, setSel] = useState({ ...EMPTY_SELECTION, ...(workspace?.propertyMap || {}) });
  const [extraProps, setExtraProps] = useState(
    (workspace?.extraProps || []).map((ep) =>
      typeof ep === 'string' ? { propName: ep, required: false } : ep
    )
  );
  const [color, setColor] = useState(workspace?.color || '');
  const [descriptionRequired, setDescriptionRequired] = useState(!!workspace?.descriptionRequired);

  // Database list, fetched once per modal lifetime (Retry/Refresh re-fetches)
  const [dbs, setDbs] = useState({ loading: true, error: null, items: [] });
  // Schema of the selected Time Entries database
  const [teSchema, setTeSchema] = useState({ loading: false, error: null, schema: null });

  const setProp = (key, value) => setSel((s) => ({ ...s, [key]: value }));

  const loadDatabases = async () => {
    setDbs({ loading: true, error: null, items: [] });
    try {
      const items = await window.notionAPI.searchDatabases();
      setDbs({ loading: false, error: null, items });
    } catch (err) {
      setDbs({ loading: false, error: errorMessage(err), items: [] });
    }
  };

  useEffect(() => {
    loadDatabases();
  }, []);

  // Load the Time Entries schema whenever that database changes. Selections
  // that don't exist in the freshly loaded schema are cleared so a stale
  // property name can never be saved.
  const loadTeSchema = async (dbId) => {
    setTeSchema({ loading: true, error: null, schema: null });
    try {
      const schema = await window.notionAPI.getDatabaseSchema(dbId);
      setTeSchema({ loading: false, error: null, schema });
      setSel((s) => validateTeSelection(s, schema));
      // Drop extra fields that don't exist (or aren't writable) in this schema
      setExtraProps((prev) =>
        prev.filter(
          (ep) =>
            !ep.propName ||
            (schema[ep.propName] && WRITABLE_EXTRA_TYPES.includes(schema[ep.propName].type))
        )
      );
    } catch (err) {
      setTeSchema({ loading: false, error: errorMessage(err), schema: null });
    }
  };

  useEffect(() => {
    if (timeEntriesDbId) loadTeSchema(timeEntriesDbId);
    else setTeSchema({ loading: false, error: null, schema: null });
  }, [timeEntriesDbId]);



  const teDateOpts = teSchema.schema ? propertiesByType(teSchema.schema, 'date') : [];
  const teStatusOpts = teSchema.schema ? propertiesByType(teSchema.schema, 'status') : [];
  const teStatusOptions = teSchema.schema
    ? statusOptions(teSchema.schema, sel.timeEntryStatusProp)
    : [];
  // The running and ended statuses must differ, so each dropdown hides the
  // option already chosen in the other.
  const teRunningOptions = teStatusOptions.filter((o) => o.name !== sel.timeEntryStatusDone);
  const teDoneOptions = teStatusOptions.filter((o) => o.name !== sel.timeEntryStatusRunning);

  // Candidates for an extra-field row: writable types, minus the managed
  // properties and the names already chosen in other rows
  const extraCandidates = (rowIndex) => {
    if (!teSchema.schema) return [];
    const taken = new Set([
      sel.timeEntryDateProp,
      sel.timeEntryStatusProp,
      ...extraProps
        .filter((_, i) => i !== rowIndex)
        .map((ep) => ep.propName)
        .filter(Boolean),
    ]);
    return Object.entries(teSchema.schema)
      .filter(([propName, prop]) => WRITABLE_EXTRA_TYPES.includes(prop.type) && !taken.has(propName))
      .map(([propName, prop]) => ({ name: propName, type: prop.type }));
  };

  const complete =
    !!name.trim() &&
    !!timeEntriesDbId &&
    !!teSchema.schema &&
    !!sel.timeEntryDateProp &&
    !!sel.timeEntryStatusProp &&
    !!sel.timeEntryStatusRunning &&
    !!sel.timeEntryStatusDone &&
    sel.timeEntryStatusRunning !== sel.timeEntryStatusDone;

  const handleSave = async () => {
    if (!complete) return;
    const relPropName = propertiesByType(teSchema.schema, 'relation')[0]?.name ?? '';
    const titlePropName = propertiesByType(teSchema.schema, 'title')[0]?.name ?? '';
    const projectsDbId = teSchema.schema[relPropName]?.relation?.database_id ?? '';
    // Fields this form doesn't edit (and anything the Android app stores that
    // this app doesn't know about) are carried over from the saved workspace,
    // so editing here never strips them from the synced configuration.
    const saved = {
      ...(workspace || {}),
      id: workspace?.id || crypto.randomUUID(),
      name: name.trim(),
      color: color.trim(),
      timeEntriesDbId,
      projectsDbId,
      roundingMinutes,
      descriptionRequired,
      propertyMap: {
        timeEntryTitleProp: titlePropName,
        timeEntryDateProp: sel.timeEntryDateProp,
        timeEntryStatusProp: sel.timeEntryStatusProp,
        timeEntryStatusRunning: sel.timeEntryStatusRunning,
        timeEntryStatusDone: sel.timeEntryStatusDone,
        timeEntryRelationProp: relPropName,
      },
      // Keeps each row's other fields, notably the default value set in the
      // Android app, which this form has no input for. A row whose property
      // was changed here starts fresh (see the field picker below), so a
      // default never carries over to a different property.
      extraProps: extraProps.filter((ep) => !!ep.propName).map((ep) => ({
        ...ep,
        propName: ep.propName,
        required: !!ep.required,
      })),
      presets: workspace?.presets || [],
    };
    if (workspace) {
      await updateWorkspace(saved);
    } else {
      await addWorkspace(saved);
      addToast({ type: 'success', message: t('toastProjectAdded').replace('{name}', saved.name) });
    }
    onClose();
  };

  const dbOptions = dbs.items.map((db) => ({ value: db.id, label: db.name }));

  const roundingOptions = [
    { value: '0', label: t('roundingOff') },
    { value: '5', label: t('rounding5') },
    { value: '10', label: t('rounding10') },
    { value: '15', label: t('rounding15') },
  ];

  return (
    <div className="fixed inset-0 bg-smoke flex items-center justify-center z-40 p-4">
      <div className="bg-dialog border border-surface-stroke rounded-lg shadow-dialog p-4 w-full max-h-full overflow-y-auto flex flex-col gap-4">
        <h2 className="text-sm font-semibold">
          {workspace ? t('modalTitleEditWorkspace') : t('modalTitleAddWorkspace')}
        </h2>

        {/* Name */}
        <Field label={t('labelWorkspaceName')}>
          <input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="w-full bg-control border border-stroke rounded-md px-3 py-2 text-sm"
          />
        </Field>

        {/* Time Entries database */}
        {dbs.error && (
          <div className="text-xs bg-danger-bg border border-danger-stroke text-fg1 rounded-md p-2 flex items-start gap-2">
            <span className="flex-1">{`${t('errorLoadingDatabases')}: ${dbs.error}`}</span>
            <button onClick={loadDatabases} className="font-semibold underline shrink-0">
              {t('btnRetry')}
            </button>
          </div>
        )}
        {!dbs.loading && !dbs.error && dbs.items.length === 0 && (
          <div className="text-xs bg-warning-bg border border-warning-stroke text-fg1 rounded-md p-2">
            {t('noDatabasesFound')}
          </div>
        )}
        <div>
          <div className="flex items-center justify-between mb-1">
            <label className="text-xs text-fg2">{t('labelTimeEntriesDb')}</label>
            <button
              onClick={loadDatabases}
              disabled={dbs.loading}
              className="text-xs text-fg3 hover:text-fg1 disabled:opacity-50 flex items-center gap-1"
              title={t('btnRefreshDatabases')}
            >
              <ArrowClockwise20Regular className="w-3.5 h-3.5 inline mr-1" /> {t('btnRefreshDatabases')}
            </button>
          </div>
          <SearchableSelect
            value={timeEntriesDbId}
            onChange={setTimeEntriesDbId}
            options={dbOptions}
            placeholder={dbs.loading ? t('loadingDatabases') : t('placeholderSelectDatabase')}
            disabled={dbs.loading || !!dbs.error}
            loading={dbs.loading}
            className="w-full"
          />
          <p className="text-xs text-fg3 mt-1">{t('hintTimeEntriesDb')}</p>
        </div>

        {/* Time entry properties */}
        {timeEntriesDbId && (
          <div className="flex flex-col gap-3 border-t border-stroke pt-3">
            {teSchema.error && (
              <div className="text-xs bg-danger-bg border border-danger-stroke text-fg1 rounded-md p-2 flex items-start gap-2">
                <span className="flex-1">{`${t('errorLoadingSchema')}: ${teSchema.error}`}</span>
                <button
                  onClick={() => loadTeSchema(timeEntriesDbId)}
                  className="font-semibold underline shrink-0"
                >
                  {t('btnRetry')}
                </button>
              </div>
            )}
            <PropSelect
              label={t('labelTimeEntryDateProp')}
              hint={t('hintTimeEntryDateProp')}
              loading={teSchema.loading}
              options={teDateOpts}
              value={sel.timeEntryDateProp}
              onChange={(v) => setProp('timeEntryDateProp', v)}
            />
            <PropSelect
              label={t('labelTimeEntryStatusProp')}
              hint={t('hintTimeEntryStatusProp')}
              loading={teSchema.loading}
              options={teStatusOpts}
              value={sel.timeEntryStatusProp}
              onChange={(v) =>
                // Changing the status field invalidates the chosen options
                setSel((s) => ({
                  ...s,
                  timeEntryStatusProp: v,
                  timeEntryStatusRunning: '',
                  timeEntryStatusDone: '',
                }))
              }
            />
            {sel.timeEntryStatusProp && (
              <div className="ml-4 flex flex-col gap-3">
                <PropSelect
                  label={t('labelTimeEntryStatusRunning')}
                  hint={t('hintTimeEntryStatusRunning')}
                  loading={teSchema.loading}
                  options={teRunningOptions}
                  value={sel.timeEntryStatusRunning}
                  onChange={(v) => setProp('timeEntryStatusRunning', v)}
                />
                <PropSelect
                  label={t('labelTimeEntryStatusDone')}
                  hint={t('hintTimeEntryStatusDone')}
                  loading={teSchema.loading}
                  options={teDoneOptions}
                  value={sel.timeEntryStatusDone}
                  onChange={(v) => setProp('timeEntryStatusDone', v)}
                />
              </div>
            )}
          </div>
        )}

        {/* Extra fields (optional, max 5) */}
        {timeEntriesDbId && teSchema.schema && (
          <div className="flex flex-col gap-2 border-t border-stroke pt-3">
            <p className="text-xs font-semibold text-fg2">{t('sectionExtraFields')}</p>
            <p className="text-xs text-fg3">{t('hintExtraFields')}</p>
            {extraProps.map((ep, i) => {
              const candidateOptions = extraCandidates(i).map((c) => ({
                value: c.name,
                label: `${c.name} (${c.type})`,
              }));
              return (
                <div key={i} className="flex items-center gap-2">
                  <SearchableSelect
                    value={ep.propName}
                    onChange={(v) =>
                      setExtraProps((prev) =>
                        prev.map((p, j) =>
                          j === i ? { propName: v, required: p.required } : p
                        )
                      )
                    }
                    options={candidateOptions}
                    placeholder={t('placeholderSelectField')}
                    className="flex-1"
                  />
                  <label className="flex items-center gap-1 text-xs text-fg2 shrink-0 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={!!ep.required}
                      onChange={(e) =>
                        setExtraProps((prev) =>
                          prev.map((p, j) => (j === i ? { ...p, required: e.target.checked } : p))
                        )
                      }
                      className="accent-accent"
                    />
                    {t('labelRequiredField')}
                  </label>
                  <button
                    onClick={() => setExtraProps((prev) => prev.filter((_, j) => j !== i))}
                    title={t('titleRemoveExtraField')}
                    className="text-fg3 hover:text-danger shrink-0 flex items-center justify-center p-0.5"
                  >
                    <Dismiss20Regular className="w-4 h-4" />
                  </button>
                </div>
              );
            })}
            {extraProps.length < MAX_EXTRA_PROPS && (
              <button
                onClick={() => setExtraProps((prev) => [...prev, { propName: '', defaultValue: null, required: false }])}
                className="self-start px-3 py-1.5 rounded-md bg-control hover:bg-control-hover active:bg-control-pressed border border-stroke text-xs"
              >
                {t('btnAddExtraField')}
              </button>
            )}
          </div>
        )}



        {/* Description */}
        <div className="border-t border-stroke pt-3">
          <label className="flex items-center gap-2 text-xs text-fg2 cursor-pointer">
            <input
              type="checkbox"
              checked={descriptionRequired}
              onChange={(e) => setDescriptionRequired(e.target.checked)}
              className="accent-accent"
            />
            {t('labelDescriptionRequired')}
          </label>
          <p className="text-xs text-fg3 mt-1">
            {descriptionRequired ? t('hintDescriptionRequired') : t('hintDescriptionOptional')}
          </p>
        </div>

        {/* Rounding */}
        <div className="border-t border-stroke pt-3">
          <label className="block text-xs text-fg2 mb-1">{t('labelWorkspaceRounding')}</label>
          <SearchableSelect
            value={String(roundingMinutes)}
            onChange={(v) => setRoundingMinutes(Number(v))}
            options={roundingOptions}
            allowClear={false}
            className="w-full"
          />
          <p className="text-xs text-fg3 mt-1">{t('workspaceRoundingHint')}</p>
        </div>

        {/* Colour */}
        <div className="border-t border-stroke pt-3">
          <label className="block text-xs text-fg2 mb-2">{t('labelWorkspaceColor')}</label>
          <WorkspaceColorPicker value={color} onChange={setColor} />
        </div>



        {/* Buttons */}
        {!complete && (
          <p className="text-xs text-fg3">{t('saveIncompleteHint')}</p>
        )}
        <div className="flex justify-end gap-2">
          <button
            onClick={onClose}
            className="px-3 py-1.5 rounded-md bg-control hover:bg-control-hover active:bg-control-pressed border border-stroke text-sm"
          >
            {t('btnCancel')}
          </button>
          <button
            onClick={handleSave}
            disabled={!complete}
            className="px-3 py-1.5 rounded-md bg-accent hover:bg-accent-hover active:bg-accent-pressed text-on-accent disabled:opacity-50 text-sm font-semibold"
          >
            {t('btnSave')}
          </button>
        </div>
      </div>
    </div>
  );
}

function Field({ label, hint, children }) {
  return (
    <div>
      <label className="block text-xs text-fg2 mb-1">{label}</label>
      {children}
      {hint && <p className="text-xs text-fg3 mt-1">{hint}</p>}
    </div>
  );
}

/** Searchable dropdown over a list of { name } options, with loading and empty states. */
function PropSelect({ label, hint, loading, options, value, onChange }) {
  const empty = !loading && options.length === 0;
  const selectOptions = options.map((o) => ({ value: o.name, label: o.name }));
  return (
    <Field label={label} hint={hint}>
      <SearchableSelect
        value={value}
        onChange={onChange}
        options={selectOptions}
        placeholder={loading ? t('loadingProperties') : t('placeholderSelect')}
        disabled={loading || empty}
        loading={loading}
        className="w-full"
      />
      {empty && <p className="text-xs text-danger mt-1">{t('noPropertiesOfType')}</p>}
    </Field>
  );
}



const PRESET_PALETTE = [
  '#ef4444', '#f97316', '#eab308', '#22c55e',
  '#06b6d4', '#3b82f6', '#8b5cf6', '#ec4899',
];

function WorkspaceColorPicker({ value, onChange }) {
  const [customHex, setCustomHex] = useState(
    value && !PRESET_PALETTE.includes(value) ? value : ''
  );

  const applyCustom = () => {
    const hex = customHex.trim().startsWith('#') ? customHex.trim() : `#${customHex.trim()}`;
    if (/^#[0-9a-fA-F]{6}$/.test(hex)) onChange(hex);
  };

  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap gap-2 items-center">
        {/* None swatch */}
        <button
          type="button"
          onClick={() => onChange('')}
          title={t('colorNone')}
          className={`w-7 h-7 rounded-full border-2 bg-control flex items-center justify-center text-xs transition-colors ${
            !value ? 'border-accent' : 'border-stroke hover:border-stroke-strong'
          }`}
        >
          {!value && <span className="text-fg2">✓</span>}
        </button>
        {PRESET_PALETTE.map((hex) => {
          const { light, dark } = getWorkspaceColorVariants(hex);
          return (
            <button
              key={hex}
              type="button"
              onClick={() => { onChange(hex); setCustomHex(''); }}
              title={hex}
              style={{ background: `linear-gradient(to right, ${light} 50%, ${dark} 50%)` }}
              className={`w-7 h-7 rounded-full border-2 flex items-center justify-center transition-colors ${
                value === hex ? 'border-white' : 'border-transparent hover:border-white/50'
              }`}
            >
              {value === hex && <span className="text-white text-xs">✓</span>}
            </button>
          );
        })}
      </div>
      <div className="flex items-center gap-2">
        <input
          type="text"
          value={customHex}
          onChange={(e) => setCustomHex(e.target.value)}
          placeholder="#RRGGBB"
          className="flex-1 bg-control border border-stroke rounded px-2 py-1 text-xs font-mono"
          onKeyDown={(e) => e.key === 'Enter' && applyCustom()}
        />
        {customHex && (() => {
          const trimmed = customHex.trim();
          const valid = /^#?[0-9a-fA-F]{6}$/.test(trimmed);
          const hex = valid ? (trimmed.startsWith('#') ? trimmed : `#${trimmed}`) : null;
          const variants = hex ? getWorkspaceColorVariants(hex) : null;
          return (
            <div
              className="w-6 h-6 rounded-full border border-stroke shrink-0"
              style={
                variants
                  ? { background: `linear-gradient(to right, ${variants.light} 50%, ${variants.dark} 50%)` }
                  : { backgroundColor: 'transparent' }
              }
            />
          );
        })()}
        <button
          type="button"
          onClick={applyCustom}
          disabled={!customHex.trim()}
          className="px-2 py-1 text-xs rounded bg-control hover:bg-control-hover active:bg-control-pressed border border-stroke disabled:opacity-40"
        >
          {t('btnApplyColor')}
        </button>
      </div>
    </div>
  );
}

// Clears selections that don't exist in the freshly loaded schema, and
// auto-selects when a property type has exactly one candidate.
function validateTeSelection(sel, schema) {
  const next = { ...sel };
  const pick = (key, type) => {
    const candidates = propertiesByType(schema, type);
    if (next[key] && !candidates.some((c) => c.name === next[key])) next[key] = '';
    if (!next[key] && candidates.length === 1) next[key] = candidates[0].name;
  };
  pick('timeEntryDateProp', 'date');
  pick('timeEntryStatusProp', 'status');
  const opts = statusOptions(schema, next.timeEntryStatusProp);
  if (next.timeEntryStatusRunning && !opts.some((o) => o.name === next.timeEntryStatusRunning)) {
    next.timeEntryStatusRunning = '';
  }
  if (next.timeEntryStatusDone && !opts.some((o) => o.name === next.timeEntryStatusDone)) {
    next.timeEntryStatusDone = '';
  }
  return next;
}


