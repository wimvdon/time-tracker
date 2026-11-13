import { useEffect, useRef, useState } from 'react';
import { useStore, fetchExtraFieldDefs } from '../store/useStore.js';
import { formatHHMM, formatElapsed, formatDateStr, formatTimeStr, parseTimeStr } from '../lib/format.js';
import { t } from '../lib/i18n.js';
import { buildPropertyValue, emptyValueFor } from '../lib/notionValues.js';
import { getWorkspaceAccentColor, getWorkspaceSolidFillColor } from '../lib/color.js';
import { useIsDarkTheme } from '../hooks/useIsDarkTheme.js';
import ExtraFieldInput from '../components/ExtraFieldInput.jsx';
import SearchableSelect from '../components/SearchableSelect.jsx';
import { DESCRIPTION_AUTOSAVE_DELAY_MS, shouldAutoSaveDescription } from '../lib/descriptionAutoSave.js';
import { setupStep } from '../lib/setupStep.js';
import {
  ArrowClockwise20Regular,
  Add20Regular,
  Settings20Regular,
  Play20Regular,
  Edit20Regular,
  ArrowUp20Regular,
  ArrowDown20Regular,
  Delete20Regular,
  Stop20Regular,
  Open20Regular,
  Info20Regular,
  ChevronDown20Regular,
  Dismiss16Regular,
  Play20Filled,
  Stop20Filled,
  Add20Regular as AddPreset20Regular,
  Key20Regular,
  Briefcase20Regular
} from '@fluentui/react-icons';

/** Combines the date of `base` with an "HH:MM" string from a time input. */
function timeOnDate(base, hhmm) {
  const { hours, minutes } = parseTimeStr(hhmm);
  const result = new Date(base);
  result.setHours(hours, minutes, 0, 0);
  return result;
}

export default function Timer() {
  const settingsLoaded = useStore((s) => s.settingsLoaded);
  const signedIn = useStore((s) => !!s.notionAccount);
  const workspaces = useStore((s) => s.workspaces);
  const step = setupStep(settingsLoaded, signedIn, workspaces.length);
  const selectedWorkspaceId = useStore((s) => s.selectedWorkspaceId);
  const selectWorkspace = useStore((s) => s.selectWorkspace);
  const description = useStore((s) => s.description);
  const setDescription = useStore((s) => s.setDescription);
  const extraFieldDefs = useStore((s) => s.extraFieldDefs);
  const extrasLoading = useStore((s) => s.extrasLoading);
  const extrasMissing = useStore((s) => s.extrasMissing);
  const extraValues = useStore((s) => s.extraValues);
  const setExtraValue = useStore((s) => s.setExtraValue);
  const timer = useStore((s) => s.timer);
  const startTimer = useStore((s) => s.startTimer);
  const isStarting = useStore((s) => s.isStarting);
  const setView = useStore((s) => s.setView);
  const runningTimers = useStore((s) => s.runningTimers);
  const runningTimersLoading = useStore((s) => s.runningTimersLoading);
  const loadRunningTimers = useStore((s) => s.loadRunningTimers);
  const loadRunningTimer = useStore((s) => s.loadRunningTimer);
  const stopOtherRunningTimer = useStore((s) => s.stopOtherRunningTimer);
  const recentEntries = useStore((s) => s.recentEntries);
  const recentEntriesLoading = useStore((s) => s.recentEntriesLoading);
  const loadRecentEntries = useStore((s) => s.loadRecentEntries);
  const startFromRecentEntry = useStore((s) => s.startFromRecentEntry);
  const deleteConfirm = useStore((s) => s.deleteConfirm);
  const confirmDelete = useStore((s) => s.confirmDelete);
  const cancelDelete = useStore((s) => s.cancelDelete);
  const deleteEntry = useStore((s) => s.deleteEntry);
  const openNotionPage = useStore((s) => s.openNotionPage);
  const refreshAll = useStore((s) => s.refreshAll);

  const applyPreset = useStore((s) => s.applyPreset);
  const deletedPageInfo = useStore((s) => s.deletedPageInfo);
  const dismissDeletedPage = useStore((s) => s.dismissDeletedPage);

  const showAlreadyRunningWarning = useStore((s) => s.showAlreadyRunningWarning);
  const onConfirmAlreadyRunning = useStore((s) => s.onConfirmAlreadyRunning);
  const onCancelAlreadyRunning = useStore((s) => s.onCancelAlreadyRunning);
  const onPlusButtonClicked = useStore((s) => s.onPlusButtonClicked);
  const savePreset = useStore((s) => s.savePreset);
  const deletePreset = useStore((s) => s.deletePreset);
  const movePresetUp = useStore((s) => s.movePresetUp);
  const movePresetDown = useStore((s) => s.movePresetDown);

  const [showManagePresetsModal, setShowManagePresetsModal] = useState(false);
  // The preset being created or edited: { workspaceId, presetId (none for a new one), name,
  // pageTitle, extraValues }, or null when the editor is closed.
  const [presetDraft, setPresetDraft] = useState(null);
  // A new preset starts from the timer form (its workspace, description and fields), so
  // "save what I'm doing as a preset" stays one step.
  const newPresetDraft = () => ({
    workspaceId: selectedWorkspaceId || workspaces[0]?.id || '',
    presetId: null,
    name: '',
    pageTitle: description,
    extraValues: { ...extraValues },
  });
  // A start time picked for a timer the user forgot to start ("HH:MM"); empty means now.
  // Only the Start button uses it: presets, shortcuts and recent entries always start now.
  const [pickedStart, setPickedStart] = useState('');

  const selectedWorkspace = workspaces.find((w) => w.id === selectedWorkspaceId);
  const isDarkTheme = useIsDarkTheme();

  // Reload running timers and recent entries on every state transition
  const timerRunning = timer.running;
  useEffect(() => {
    loadRunningTimers();
    loadRecentEntries();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [timerRunning]);

  const settingsButton = (
    <div className="flex justify-end gap-2 px-4 pt-3 pb-3 border-t border-stroke">
      <button
        onClick={refreshAll}
        title={t('btnRefreshAll')}
        aria-label={t('btnRefreshAll')}
        className="text-fg3 hover:text-fg1 transition-colors flex items-center justify-center"
      >
        <ArrowClockwise20Regular className={`w-5 h-5 ${runningTimersLoading || recentEntriesLoading ? 'animate-spin' : ''}`} />
      </button>
      {timer.running && (
        <button
          onClick={onPlusButtonClicked}
          title={t('titleStartNewTimer')}
          aria-label={t('titleStartNewTimer')}
          className="text-fg3 hover:text-fg1 transition-colors flex items-center justify-center"
        >
          <Add20Regular className="w-5 h-5" />
        </button>
      )}
      <button
        onClick={() => setView('settings')}
        title={t('titleSettings')}
        className="text-fg3 hover:text-fg1 transition-colors flex items-center justify-center"
      >
        <Settings20Regular className="w-5 h-5" />
      </button>
    </div>
  );

  // ---- Running state ----
  let content;
  if (timer.running) {
    content = (
      <RunningView
        workspaceName={selectedWorkspace?.name || '–'}
        workspaceColor={selectedWorkspace?.color?.trim() || null}
        settingsButton={settingsButton}
        runningTimers={runningTimers}
        runningTimersLoading={runningTimersLoading}
        activePageId={timer.notionPageId}
        recentEntries={recentEntries}
        recentEntriesLoading={recentEntriesLoading}
        deleteConfirm={deleteConfirm}
        onLoadRunningTimer={loadRunningTimer}
        onStopOtherRunningTimer={stopOtherRunningTimer}
        onConfirmDelete={confirmDelete}
        onCancelDelete={cancelDelete}
        onDeleteEntry={deleteEntry}
        onOpenUrl={openNotionPage}
        onApplyPreset={applyPreset}
        onStartFromRecentEntry={startFromRecentEntry}
        onManagePresetsClick={() => setShowManagePresetsModal(true)}
        onNewPresetClick={() => setPresetDraft(newPresetDraft())}
      />
    );
  } else if (!settingsLoaded) {
    content = <div className="h-full" />;
  } else if (step) {
    // Until the API key and a workspace are set, the timer can't do anything: the whole
    // view says what to set up instead (DESIGN.md, "Empty and setup states").
    content = (
      <div className="flex flex-col h-full">
        <SetupNeeded step={step} onOpenSettings={() => setView('settings')} />
        {settingsButton}
      </div>
    );
  } else {
    // ---- Idle state ----
    const canStart = signedIn && !!selectedWorkspaceId && !extrasLoading && !isStarting;
    // Like required extra fields, a required description is enforced when the
    // timer is stopped; starting stays a single click.
    const descriptionRequired = !!selectedWorkspace?.descriptionRequired;
    const descriptionMissing = descriptionRequired && !description.trim();

    content = (
      <div className="flex flex-col h-full">
        <div className="flex-1 flex flex-col px-5 pt-5 pb-4 gap-4 overflow-y-auto">
          <PresetChips
            workspaces={workspaces}
            signedIn={signedIn}
            onApplyPreset={applyPreset}
            onManageClick={() => setShowManagePresetsModal(true)}
            onNewClick={() => setPresetDraft(newPresetDraft())}
            className="mb-2"
          />

          <WorkspaceTitle
            workspaces={workspaces}
            selectedId={selectedWorkspaceId}
            onSelect={(v) => selectWorkspace(v || null)}
          />

          <TimerHeroCard elapsedText={formatElapsed(0)} running={false}>
            <button
              onClick={() => startTimer(pickedStart ? timeOnDate(new Date(), pickedStart) : undefined)}
              disabled={!canStart || isStarting}
              className={`w-full h-10 rounded-md font-semibold text-base transition-colors flex items-center justify-center ${
                selectedWorkspace?.color
                  ? 'text-white hover:brightness-110 active:brightness-90 disabled:bg-control-disabled disabled:text-fg-disabled'
                  : 'bg-emerald-600 hover:bg-emerald-500 active:bg-emerald-700 text-white disabled:bg-control-disabled disabled:text-fg-disabled'
              }`}
              style={
                selectedWorkspace?.color && canStart && !isStarting
                  ? { backgroundColor: getWorkspaceSolidFillColor(selectedWorkspace.color) }
                  : undefined
              }
            >
              {isStarting ? (
                <span className="animate-spin inline-block w-5 h-5 border-2 border-current border-t-transparent rounded-full" />
              ) : (
                <>
                  <Play20Filled className="w-5 h-5 mr-2" />
                  {t('btnStart')}
                </>
              )}
            </button>
          </TimerHeroCard>

          <TimerDetailsCard>
            <div>
              <label htmlFor="idle-description" className={`block text-xs mb-1 ${descriptionMissing ? 'text-danger' : 'text-fg2'}`}>
                {t('labelDescription')}
                {descriptionRequired && ' *'}
              </label>
              <input
                id="idle-description"
                type="text"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder={t('placeholderDescription')}
                className={`w-full bg-control border rounded-md px-3 py-2 text-sm placeholder-fg3 ${
                  descriptionMissing ? 'border-danger' : 'border-stroke'
                }`}
              />
            </div>

            <div>
              <label htmlFor="idle-start" className="block text-xs text-fg2 mb-1">{t('labelStartTime')}</label>
              <TimeInput id="idle-start" value={pickedStart} onChange={setPickedStart} emptyLabel={t('timeNow')} />
            </div>

            {selectedWorkspaceId && extrasLoading && (
              <div className="text-xs text-fg3">{t('placeholderLoading')}</div>
            )}
            {selectedWorkspaceId && !extrasLoading && extrasMissing > 0 && (
              <div className="text-xs text-danger">{t('errorExtrasMissing')}</div>
            )}
            {selectedWorkspaceId &&
              extraFieldDefs.map((def) => (
                <ExtraFieldInput
                  key={def.name}
                  def={def}
                  value={extraValues[def.name]}
                  error={def.required && buildPropertyValue(def.type, extraValues[def.name]) === null}
                  onChange={(value) => setExtraValue(def.name, value)}
                />
              ))}
          </TimerDetailsCard>

          <OtherRunningTimers
            timers={runningTimers}
            loading={runningTimersLoading}
            activePageId={null}
            onLoad={loadRunningTimer}
            onStop={stopOtherRunningTimer}
            onDelete={(rt) => confirmDelete(rt, 'running')}
            onOpenUrl={openNotionPage}
          />
          <RecentEntries
            entries={recentEntries}
            loading={recentEntriesLoading}
            workspaces={workspaces}
            onDelete={(entry) => confirmDelete(entry, 'recent')}
            onOpenUrl={openNotionPage}
            onPlay={startFromRecentEntry}
          />
        </div>
        {settingsButton}
        {deleteConfirm && (
          <DeleteConfirmDialog
            title={deleteConfirm.title}
            onConfirm={deleteEntry}
            onCancel={cancelDelete}
          />
        )}
        {deletedPageInfo && (
          <DeletedPageDialog info={deletedPageInfo} onDismiss={dismissDeletedPage} />
        )}
      </div>
    );
  }

  return (
    <>
      {content}

      {showAlreadyRunningWarning && (
        <div className="fixed inset-0 bg-smoke flex items-center justify-center z-50 p-4">
          <div className="bg-dialog border border-surface-stroke rounded-lg shadow-dialog p-4 w-full flex flex-col gap-3">
            <h2 className="text-sm font-semibold text-fg1">{t('warnAlreadyRunningTitle')}</h2>
            <p className="text-xs text-fg2">{t('warnAlreadyRunningBody')}</p>
            {timer.running && (
              <p className="text-xs text-fg2">
                {t('warnAlreadyRunningIn')}{' '}
                <span className="font-semibold text-fg1">{workspaces.find(w => w.id === timer.workspaceId)?.name || 'Notion'}</span>
              </p>
            )}
            <div className="flex flex-col gap-2 mt-1">
              <button
                type="button"
                onClick={onConfirmAlreadyRunning}
                className="w-full py-2 rounded-md bg-accent hover:bg-accent-hover active:bg-accent-pressed text-on-accent text-sm font-semibold"
              >
                {t('btnStopAndStartNew')}
              </button>
              <button
                type="button"
                onClick={onCancelAlreadyRunning}
                className="w-full py-2 rounded-md bg-control hover:bg-control-hover active:bg-control-pressed border border-stroke text-sm text-fg2"
              >
                {t('btnCancel')}
              </button>
            </div>
          </div>
        </div>
      )}

      {showManagePresetsModal && (
        <div className="fixed inset-0 bg-smoke flex items-center justify-center z-50 p-4">
          <div className="bg-dialog border border-surface-stroke rounded-lg shadow-dialog p-4 w-full flex flex-col gap-3 max-h-[90vh]">
            <h2 className="text-sm font-semibold">{t('presetsManageTitle')}</h2>
            
            <div className="bg-layer border border-stroke rounded-lg p-3 flex gap-2.5 items-start text-xs text-fg1">
              <Info20Regular className="w-4 h-4 text-fg2 mt-0.5" />
              <p>{t('tooltipPresetsShortcuts')}</p>
            </div>

            <div className="flex-1 overflow-y-auto pr-1 flex flex-col gap-2">
              {(() => {
                const allPresets = workspaces.flatMap((ws) =>
                  (ws.presets || []).map((p, index, arr) => ({ ws, p, index, arr }))
                );
                if (allPresets.length === 0) {
                  return <div className="text-xs text-fg3 py-4 text-center">{t('presetsNoneConfigured')}</div>;
                }
                return allPresets.map(({ ws, p, index, arr }) => (
                  <div key={p.id} className="flex items-center justify-between bg-control border border-stroke rounded px-2.5 py-1.5 text-xs">
                    <div className="flex flex-col min-w-0 flex-1 pr-2">
                      <span className="font-semibold text-fg1 truncate">{p.name}</span>
                      <div className="flex items-center gap-1.5 mt-0.5">
                        {ws.color?.trim() && (
                          <span className="w-1.5 h-1.5 rounded-full shrink-0" style={{ backgroundColor: getWorkspaceAccentColor(ws.color.trim(), isDarkTheme) }} />
                        )}
                        <span className="text-[10px] text-fg2 truncate">{ws.name}</span>
                      </div>
                    </div>
                    <div className="flex items-center gap-1">
                      <button
                        type="button"
                        onClick={() => movePresetUp(ws.id, p.id)}
                        disabled={index === 0}
                        title={t('titleMoveUp')}
                        className="p-1 hover:bg-subtle-hover rounded disabled:opacity-30 disabled:hover:bg-transparent flex items-center justify-center text-fg2 hover:text-fg1"
                      >
                        <ArrowUp20Regular className="w-4 h-4" />
                      </button>
                      <button
                        type="button"
                        onClick={() => movePresetDown(ws.id, p.id)}
                        disabled={index === arr.length - 1}
                        title={t('titleMoveDown')}
                        className="p-1 hover:bg-subtle-hover rounded disabled:opacity-30 disabled:hover:bg-transparent flex items-center justify-center text-fg2 hover:text-fg1"
                      >
                        <ArrowDown20Regular className="w-4 h-4" />
                      </button>
                      <button
                        type="button"
                        onClick={() => setPresetDraft({
                          workspaceId: ws.id,
                          presetId: p.id,
                          name: p.name,
                          pageTitle: p.pageTitle || '',
                          extraValues: { ...(p.extraValues || {}) },
                        })}
                        title={t('presetEdit')}
                        aria-label={t('presetEdit')}
                        className="p-1 hover:bg-subtle-hover rounded flex items-center justify-center text-fg2 hover:text-fg1"
                      >
                        <Edit20Regular className="w-4 h-4" />
                      </button>
                    </div>
                  </div>
                ));
              })()}
            </div>

            <button
              type="button"
              onClick={() => setPresetDraft(newPresetDraft())}
              disabled={workspaces.length === 0}
              className="self-start flex items-center gap-1.5 px-2 py-1.5 rounded-md text-sm text-fg1 hover:bg-subtle-hover active:bg-control-pressed transition-colors disabled:opacity-40 disabled:pointer-events-none"
            >
              <AddPreset20Regular className="w-4 h-4" />
              {t('presetNew')}
            </button>

            <button
              type="button"
              onClick={() => setShowManagePresetsModal(false)}
              className="w-full py-2 rounded-md bg-control hover:bg-control-hover active:bg-control-pressed border border-stroke text-sm mt-1"
            >
              {t('titleDismiss')}
            </button>
          </div>
        </div>
      )}

      {presetDraft && (
        <PresetEditorDialog
          draft={presetDraft}
          workspaces={workspaces}
          onCancel={() => setPresetDraft(null)}
          onSave={async (preset) => {
            await savePreset(preset);
            setPresetDraft(null);
          }}
          onDelete={presetDraft.presetId ? async () => {
            await deletePreset(presetDraft.workspaceId, presetDraft.presetId);
            setPresetDraft(null);
          } : undefined}
        />
      )}
    </>
  );
}

function RunningView({
  workspaceName,
  workspaceColor,
  settingsButton,
  runningTimers,
  runningTimersLoading,
  activePageId,
  recentEntries,
  recentEntriesLoading,
  deleteConfirm,
  onLoadRunningTimer,
  onStopOtherRunningTimer,
  onConfirmDelete,
  onCancelDelete,
  onDeleteEntry,
  onOpenUrl,
  onApplyPreset,
  onStartFromRecentEntry,
  onManagePresetsClick,
  onNewPresetClick,
}) {
  const timer = useStore((s) => s.timer);
  const isStopping = useStore((s) => s.isStopping);
  const description = useStore((s) => s.description);
  const extraFieldDefs = useStore((s) => s.extraFieldDefs);
  const extraValues = useStore((s) => s.extraValues);
  const selectedProjectId = useStore((s) => s.selectedProjectId);
  const updateTimerDetails = useStore((s) => s.updateTimerDetails);
  const addToast = useStore((s) => s.addToast);
  const stopTimer = useStore((s) => s.stopTimer);
  const updateStartTime = useStore((s) => s.updateStartTime);
  const workspaces = useStore((s) => s.workspaces);
  const signedIn = useStore((s) => !!s.notionAccount);
  const isDarkTheme = useIsDarkTheme();
  const accentColor = workspaceColor ? getWorkspaceAccentColor(workspaceColor, isDarkTheme) : null;

  // <input type="time"> only accepts a 24-hour "HH:MM" value; it renders blank
  // for anything else, so the value stays 24-hour whatever the display format
  // is. The browser presents it in the OS's own 12/24-hour convention.
  const [startInput, setStartInput] = useState(() => formatHHMM(new Date(timer.roundedStart)));
  const [endInput, setEndInput] = useState('');
  const [localDesc, setLocalDesc] = useState(description);
  const [localExtras, setLocalExtras] = useState({ ...extraValues });

  const selectedWorkspaceId = useStore((s) => s.selectedWorkspaceId);

  const [showTimeErrorModal, setShowTimeErrorModal] = useState(false);
  const [pendingEndTime, setPendingEndTime] = useState(null);

  const activeWorkspace = workspaces.find((w) => w.id === selectedWorkspaceId);
  const descriptionMissing = !!activeWorkspace?.descriptionRequired && !localDesc.trim();

  useEffect(() => {
    setStartInput(formatHHMM(new Date(timer.roundedStart)));
  }, [timer.startISO]); // eslint-disable-line react-hooks/exhaustive-deps

  // Extra-field edits are saved after a short pause instead of on every
  // keystroke (same 600 ms debounce as the Android app). While one is pending,
  // a refresh from Notion must not overwrite the draft.
  const pendingExtrasRef = useRef(null);
  const extrasTimeoutRef = useRef(null);
  const descSaveRef = useRef(null);
  // True while unsaved edits are written just before stopping, so the Stop
  // button shows its spinner right away and can't be clicked twice.
  const [flushingStop, setFlushingStop] = useState(false);
  const localDescRef = useRef(localDesc);
  localDescRef.current = localDesc;
  const localExtrasRef = useRef(localExtras);
  localExtrasRef.current = localExtras;
  const descTimeoutRef = useRef(null);

  const cancelPendingDescription = () => {
    clearTimeout(descTimeoutRef.current);
    descTimeoutRef.current = null;
  };

  const saveDescription = (desc) => {
    cancelPendingDescription();
    const pending = cancelPendingExtras();
    descSaveRef.current = updateTimerDetails(selectedProjectId, desc, pending || localExtrasRef.current);
  };

  // The description is saved by itself a moment after the user stops typing (same rule and
  // delay as the Android app). Stopping writes the draft itself, so no save runs alongside it.
  const scheduleDescriptionSave = (desc) => {
    cancelPendingDescription();
    if (!shouldAutoSaveDescription(desc, description)) return;
    descTimeoutRef.current = setTimeout(() => {
      descTimeoutRef.current = null;
      const state = useStore.getState();
      if (state.isStopping || !shouldAutoSaveDescription(localDescRef.current, state.description)) return;
      saveDescription(localDescRef.current);
    }, DESCRIPTION_AUTOSAVE_DELAY_MS);
  };

  const cancelPendingExtras = () => {
    clearTimeout(extrasTimeoutRef.current);
    extrasTimeoutRef.current = null;
    const pending = pendingExtrasRef.current;
    pendingExtrasRef.current = null;
    return pending;
  };

  const scheduleExtrasSave = (newExtras) => {
    clearTimeout(extrasTimeoutRef.current);
    pendingExtrasRef.current = newExtras;
    extrasTimeoutRef.current = setTimeout(() => {
      const pending = cancelPendingExtras();
      if (pending) updateTimerDetails(selectedProjectId, localDescRef.current, pending);
    }, 600);
  };

  useEffect(() => {
    // Switching to another running timer: drop the old timer's pending edits.
    cancelPendingExtras();
    cancelPendingDescription();
    setLocalDesc(description);
    setLocalExtras({ ...extraValues });
  }, [timer.notionPageId]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => () => {
    clearTimeout(extrasTimeoutRef.current);
    clearTimeout(descTimeoutRef.current);
  }, []);

  // Extra field values (e.g. connected project) can arrive asynchronously
  // shortly after a running timer is loaded; re-sync the draft once they do.
  useEffect(() => {
    if (pendingExtrasRef.current) return;
    setLocalExtras({ ...extraValues });
  }, [extraValues]);

  const commitStart = () => {
    if (!startInput) {
      setStartInput(formatHHMM(new Date(timer.roundedStart)));
      return;
    }
    updateStartTime(timeOnDate(timer.rawStart, startInput));
  };

  const handleStop = async (manualEnd = null) => {
    if (descriptionMissing) {
      addToast({ type: 'error', message: t('toastDescriptionBeforeStopping') });
      return;
    }
    const missingRequired = extraFieldDefs.some(
      (def) => def.required && buildPropertyValue(def.type, localExtras[def.name]) === null
    );
    if (missingRequired) {
      addToast({ type: 'error', message: t('toastRequiredBeforeStopping') });
      return;
    }
    // Write unsaved edits (a pending extra field, or a description that is
    // still being typed) before the entry is closed, so they aren't lost.
    // Clicking Stop blurs the description first; let that save land.
    if (flushingStop) return;
    cancelPendingDescription();
    setFlushingStop(true);
    try {
      await descSaveRef.current;
      const pendingExtras = cancelPendingExtras();
      const savedDesc = useStore.getState().description;
      if (pendingExtras || localDesc.trim() !== savedDesc.trim()) {
        // If the edits couldn't be written (a toast says why), the entry stays
        // running: stopping anyway closed it with the old values and the
        // edits were gone. Stop again once they can be saved.
        const saved = await updateTimerDetails(selectedProjectId, localDesc, pendingExtras || localExtras);
        if (!saved) {
          // Keep them pending, so the next Stop tries to write them again.
          if (pendingExtras) pendingExtrasRef.current = pendingExtras;
          return;
        }
      }
    } finally {
      setFlushingStop(false);
    }
    stopTimer(manualEnd);
  };

  const commitEnd = () => {
    if (!endInput) return;
    const endDate = timeOnDate(timer.rawStart, endInput);
    if (endDate < timer.rawStart) {
      setPendingEndTime(endDate);
      setShowTimeErrorModal(true);
    } else {
      handleStop(endDate);
    }
  };

  const blurOnEnter = (e) => {
    if (e.key === 'Enter') e.currentTarget.blur();
  };

  const timeInputClass =
    'w-full bg-control border border-stroke rounded-md px-3 py-2 text-sm tabular-nums';

  return (
    <div
      className="flex flex-col h-full"
      style={accentColor ? { backgroundColor: accentColor + '33' } : undefined}
    >
      <div className="flex-1 flex flex-col px-5 pt-5 pb-4 gap-4 overflow-y-auto">
        <PresetChips
          workspaces={workspaces}
          signedIn={signedIn}
          onApplyPreset={onApplyPreset}
          onManageClick={onManagePresetsClick}
          onNewClick={onNewPresetClick}
          className="mb-2"
        />

        {/* A running timer's workspace can't change, so here it is a plain title. */}
        <WorkspaceTitle workspaces={workspaces} selectedId={selectedWorkspaceId} fallbackName={workspaceName} />

        <TimerHeroCard elapsedText={formatElapsed(timer.elapsedSeconds)} running tint={accentColor}>
          <button
            onClick={() => handleStop()}
            disabled={isStopping || flushingStop}
            className="w-full h-10 rounded-md bg-red-600 hover:bg-red-500 active:bg-red-700 text-white font-semibold text-base transition-colors flex items-center justify-center disabled:opacity-50"
          >
            {isStopping || flushingStop ? (
              <span className="animate-spin inline-block w-5 h-5 border-2 border-current border-t-transparent rounded-full" />
            ) : (
              <>
                <Stop20Filled className="w-5 h-5 mr-2" />
                {t('btnStop')}
              </>
            )}
          </button>
        </TimerHeroCard>

        <TimerDetailsCard>
          <div>
            <label htmlFor="running-description" className={`block text-xs mb-1 ${descriptionMissing ? 'text-danger' : 'text-fg2'}`}>
              {t('labelDescription')}
              {activeWorkspace?.descriptionRequired && ' *'}
            </label>
            <input
              id="running-description"
              type="text"
              value={localDesc}
              onChange={(e) => {
                setLocalDesc(e.target.value);
                scheduleDescriptionSave(e.target.value);
              }}
              onBlur={() => {
                cancelPendingDescription();
                // Leaving the field saves right away, an emptied description too.
                if (localDesc.trim() === useStore.getState().description.trim()) return;
                saveDescription(localDesc);
              }}
              onKeyDown={blurOnEnter}
              placeholder={t('placeholderDescription')}
              className={`w-full bg-control border rounded-md px-3 py-2 text-sm placeholder-fg3 ${
                descriptionMissing ? 'border-danger' : 'border-stroke'
              }`}
            />
          </div>

          <div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label htmlFor="running-start" className="block text-xs text-fg2 mb-1">{t('labelStartTime')}</label>
                <input
                  id="running-start"
                  type="time"
                  value={startInput}
                  onChange={(e) => setStartInput(e.target.value)}
                  onBlur={commitStart}
                  onKeyDown={blurOnEnter}
                  className={timeInputClass}
                />
              </div>
              <div>
                <label htmlFor="running-end" className="block text-xs text-fg2 mb-1">{t('labelEndTime')}</label>
                <input
                  id="running-end"
                  type="time"
                  value={endInput}
                  onChange={(e) => setEndInput(e.target.value)}
                  onBlur={commitEnd}
                  onKeyDown={blurOnEnter}
                  className={timeInputClass}
                />
              </div>
            </div>
            <p className="mt-1 text-xs text-fg3">{t('hintManualEnd')}</p>
          </div>

          {extraFieldDefs.map((def) => (
            <ExtraFieldInput
              key={def.name}
              def={def}
              value={localExtras[def.name]}
              error={def.required && buildPropertyValue(def.type, localExtras[def.name]) === null}
              onChange={(value) => {
                const newExtras = { ...localExtras, [def.name]: value };
                setLocalExtras(newExtras);
                scheduleExtrasSave(newExtras);
              }}
            />
          ))}
        </TimerDetailsCard>

        <OtherRunningTimers
          timers={runningTimers}
          loading={runningTimersLoading}
          activePageId={activePageId}
          onLoad={onLoadRunningTimer}
          onStop={onStopOtherRunningTimer}
          onDelete={(rt) => onConfirmDelete(rt, rt.pageId === activePageId ? 'active' : 'running')}
          onOpenUrl={onOpenUrl}
        />
        <RecentEntries
          entries={recentEntries}
          loading={recentEntriesLoading}
          workspaces={workspaces}
          onDelete={(entry) => onConfirmDelete(entry, 'recent')}
          onOpenUrl={onOpenUrl}
          onPlay={onStartFromRecentEntry}
        />
      </div>
      {settingsButton}
      {deleteConfirm && (
        <DeleteConfirmDialog
          title={deleteConfirm.title}
          onConfirm={onDeleteEntry}
          onCancel={onCancelDelete}
        />
      )}
      {showTimeErrorModal && (
        <div className="fixed inset-0 bg-smoke flex items-center justify-center z-50 p-4">
          <div className="bg-dialog border border-surface-stroke rounded-lg shadow-dialog p-4 w-full max-w-xs flex flex-col gap-3">
            <h2 className="text-sm font-semibold text-fg1">{t('dialogTimeErrorTitle')}</h2>
            <p className="text-xs text-fg2">{t('dialogTimeErrorBody')}</p>
            <div className="flex gap-2 mt-1">
              <button
                type="button"
                onClick={() => {
                  setShowTimeErrorModal(false);
                  const nextDayDate = new Date(pendingEndTime.getTime() + 24 * 3600 * 1000);
                  handleStop(nextDayDate);
                  setEndInput('');
                  setPendingEndTime(null);
                }}
                className="flex-1 py-2 rounded-md bg-accent hover:bg-accent-hover active:bg-accent-pressed text-on-accent text-xs font-semibold"
              >
                {t('btnNextDay')}
              </button>
              <button
                type="button"
                onClick={() => {
                  setShowTimeErrorModal(false);
                  setEndInput('');
                  setPendingEndTime(null);
                }}
                className="flex-1 py-2 rounded-md bg-control hover:bg-control-hover active:bg-control-pressed border border-stroke text-xs font-semibold text-fg1"
              >
                {t('btnEdit')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * The workspace as the screen's title, in Fluent's Subtitle style with its colour dot. With
 * `onSelect` (idle) it is a subtle drop-down button opening the workspace list, each with its
 * dot; while a timer runs it is plain text, since a running timer's workspace can't change.
 */
function WorkspaceTitle({ workspaces, selectedId, onSelect, fallbackName }) {
  const isDarkTheme = useIsDarkTheme();
  const dotFor = (ws) => {
    const hex = ws?.color?.trim();
    return hex ? getWorkspaceAccentColor(hex, isDarkTheme) : null;
  };
  const anyColour = workspaces.some((w) => dotFor(w));
  const selected = workspaces.find((w) => w.id === selectedId);
  const dot = (color) => (
    <span className="w-2.5 h-2.5 rounded-full shrink-0" style={{ backgroundColor: color || 'transparent' }} />
  );
  const title = (ws) => (
    <span className={`min-w-0 truncate text-xl leading-7 font-semibold ${ws ? 'text-fg1' : 'text-fg3'}`}>
      {ws?.name || fallbackName || t('placeholderSelectWorkspace')}
    </span>
  );

  if (!onSelect) {
    return (
      <div className="w-full flex justify-center items-center gap-2 px-2 py-1">
        {dotFor(selected) && dot(dotFor(selected))}
        {title(selected)}
      </div>
    );
  }
  return (
    <div className="w-full flex justify-center">
      <SearchableSelect
        value={selectedId || ''}
        onChange={onSelect}
        options={workspaces.map((w) => ({ value: w.id, label: w.name }))}
        allowClear={false}
        disabled={workspaces.length === 0}
        className="max-w-full min-w-0"
        panelMinWidth={240}
        buttonClass="max-w-full flex items-center gap-2 px-2 py-1 rounded-md hover:bg-subtle-hover active:bg-control-pressed transition-colors disabled:opacity-50"
        renderValue={() => (
          <>
            {dotFor(selected) && dot(dotFor(selected))}
            {title(selected)}
            <ChevronDown20Regular className="w-4 h-4 shrink-0 text-fg2" />
          </>
        )}
        renderPrefix={anyColour ? (o) => dot(dotFor(workspaces.find((w) => w.id === o.value))) : undefined}
      />
    </div>
  );
}

/**
 * The timer itself: the elapsed time large (Fluent Title Large) on a card, with Start or Stop
 * as the card's own action. Idle it shows 00:00:00 in a quiet colour; running, the card takes a
 * tint of the workspace colour.
 */
function TimerHeroCard({ elapsedText, running, tint, children }) {
  return (
    <div
      className="w-full rounded-lg border border-stroke bg-layer px-4 pt-5 pb-4 flex flex-col items-center gap-4"
      style={tint ? { backgroundColor: tint + '26' } : undefined}
    >
      <div
        className={`text-[40px] leading-[52px] font-semibold tabular-nums ${running ? 'text-fg1' : 'text-fg3'}`}
        style={{ fontFamily: "'Segoe UI Variable Display', 'Segoe UI', system-ui, sans-serif" }}
      >
        {elapsedText}
      </div>
      {children}
    </div>
  );
}

/** Every field of the timer in one card, set apart from the timer above and the lists below. */
function TimerDetailsCard({ children }) {
  return (
    <section className="w-full rounded-lg border border-stroke bg-layer p-3 flex flex-col gap-3">
      {children}
    </section>
  );
}

/**
 * A time ("HH:MM") that may be left empty. Empty, it reads `emptyLabel` ("Now") until it gets
 * focus, since a native time input can only show "--:--"; set, a button clears it again.
 */
function TimeInput({ id, value, onChange, emptyLabel }) {
  const [focused, setFocused] = useState(false);
  return (
    <div className="relative">
      <input
        id={id}
        type={focused || value ? 'time' : 'text'}
        value={value}
        placeholder={emptyLabel}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter') e.currentTarget.blur(); }}
        className="w-full bg-control border border-stroke rounded-md pl-3 pr-9 py-2 text-sm tabular-nums placeholder-fg3"
      />
      {value && (
        <button
          type="button"
          onClick={() => onChange('')}
          title={t('btnClear')}
          aria-label={t('btnClear')}
          className="absolute right-1 top-1/2 -translate-y-1/2 p-1.5 rounded text-fg2 hover:text-fg1 hover:bg-subtle-hover flex items-center justify-center"
        >
          <Dismiss16Regular className="w-4 h-4" />
        </button>
      )}
    </div>
  );
}

/**
 * Creates or edits a preset: its workspace (new presets only), name, the description it starts
 * with and its extra field values. The workspace's fields are loaded from Notion when it opens
 * and whenever another workspace is picked.
 */
function PresetEditorDialog({ draft, workspaces, onCancel, onSave, onDelete }) {
  const isDarkTheme = useIsDarkTheme();
  const [workspaceId, setWorkspaceId] = useState(draft.workspaceId);
  const [name, setName] = useState(draft.name);
  const [pageTitle, setPageTitle] = useState(draft.pageTitle);
  const [values, setValues] = useState(draft.extraValues);
  const [defs, setDefs] = useState([]);
  const [loading, setLoading] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  const [saving, setSaving] = useState(false);
  const [confirmRemove, setConfirmRemove] = useState(false);
  const workspace = workspaces.find((w) => w.id === workspaceId);

  useEffect(() => {
    let cancelled = false;
    setDefs([]);
    setLoadFailed(false);
    setLoading(!!workspace?.extraProps?.length);
    fetchExtraFieldDefs(workspace)
      .then(({ defs: loaded }) => {
        if (cancelled) return;
        setDefs(loaded);
        setValues((current) => {
          const next = { ...current };
          for (const def of loaded) if (next[def.name] === undefined) next[def.name] = emptyValueFor(def.type);
          return next;
        });
      })
      .catch(() => { if (!cancelled) setLoadFailed(true); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [workspaceId]); // eslint-disable-line react-hooks/exhaustive-deps

  const dotFor = (ws) => {
    const hex = ws?.color?.trim();
    return hex ? getWorkspaceAccentColor(hex, isDarkTheme) : null;
  };
  const anyColour = workspaces.some((w) => dotFor(w));

  return (
    <div className="fixed inset-0 bg-smoke flex items-center justify-center z-50 p-4">
      <div className="bg-dialog border border-surface-stroke rounded-lg shadow-dialog p-4 w-full flex flex-col gap-3 max-h-[90vh]">
        <h2 className="text-sm font-semibold">{t(draft.presetId ? 'presetEdit' : 'presetNew')}</h2>
        <div className="flex-1 overflow-y-auto pr-1 flex flex-col gap-3 min-h-0">
          {/* The name stands on its own: it is the preset (what its chip shows) and always
              needed, so it carries no "required" mark. */}
          <div>
            <label htmlFor="preset-name" className="block text-xs text-fg2 mb-1">{t('labelPresetName')}</label>
            <input
              id="preset-name"
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={t('presetNamePlaceholder')}
              autoFocus
              className="w-full bg-control border border-stroke rounded-md px-3 py-2 text-sm placeholder-fg3"
            />
          </div>

          {/* What the preset fills in when it starts a timer, in a card like the timer's own
              details: the workspace, the description and the extra fields. */}
          <section className="rounded-lg border border-stroke bg-layer p-3 flex flex-col gap-3">
            <div>
              <label className="block text-xs text-fg2 mb-1">{t('labelWorkspace')}</label>
              {draft.presetId ? (
                <div className="flex items-center gap-2 text-sm text-fg1 py-1">
                  {dotFor(workspace) && (
                    <span className="w-2 h-2 rounded-full shrink-0" style={{ backgroundColor: dotFor(workspace) }} />
                  )}
                  <span className="truncate">{workspace?.name}</span>
                </div>
              ) : (
                <SearchableSelect
                  value={workspaceId}
                  onChange={(v) => { if (v) { setWorkspaceId(v); setValues({}); } }}
                  options={workspaces.map((w) => ({ value: w.id, label: w.name }))}
                  allowClear={false}
                  className="w-full"
                  renderPrefix={anyColour ? (o) => (
                    <span
                      className="w-2 h-2 rounded-full shrink-0"
                      style={{ backgroundColor: dotFor(workspaces.find((w) => w.id === o.value)) || 'transparent' }}
                    />
                  ) : undefined}
                />
              )}
            </div>
            <div>
              <label htmlFor="preset-title" className="block text-xs text-fg2 mb-1">{t('labelDescription')}</label>
              <input
                id="preset-title"
                type="text"
                value={pageTitle}
                onChange={(e) => setPageTitle(e.target.value)}
                placeholder={t('placeholderDescription')}
                className="w-full bg-control border border-stroke rounded-md px-3 py-2 text-sm placeholder-fg3"
              />
            </div>
            {loading && <div className="text-xs text-fg3">{t('placeholderLoading')}</div>}
            {loadFailed && <div className="text-xs text-danger">{t('errorLoadingExtras')}</div>}
            {defs.map((def) => (
              <ExtraFieldInput
                key={def.name}
                def={def}
                value={values[def.name] ?? emptyValueFor(def.type)}
                onChange={(value) => setValues((current) => ({ ...current, [def.name]: value }))}
              />
            ))}
            {/* Required fields are marked but not enforced here: they only have to be filled in
                before a timer stops. */}
            {defs.some((def) => def.required) && (
              <p className="text-xs text-fg3">{t('hintPresetRequired')}</p>
            )}
          </section>

          {/* Removing lives here, with the preset it removes, rather than in the list. */}
          {onDelete && (
            <button
              type="button"
              onClick={() => setConfirmRemove(true)}
              className="self-start flex items-center gap-1.5 px-2 py-1.5 rounded-md text-sm text-danger hover:bg-subtle-hover active:bg-control-pressed transition-colors"
            >
              <Delete20Regular className="w-4 h-4" />
              {t('titleRemovePreset')}
            </button>
          )}
        </div>
        <div className="flex gap-2 mt-1">
          <button
            type="button"
            onClick={async () => {
              setSaving(true);
              await onSave({ workspaceId, presetId: draft.presetId, name, pageTitle, extraValues: values });
            }}
            disabled={!name.trim() || !workspaceId || saving}
            className="flex-1 py-2 rounded-md bg-accent hover:bg-accent-hover active:bg-accent-pressed text-on-accent text-sm font-semibold disabled:opacity-40 disabled:pointer-events-none"
          >
            {t('btnSave')}
          </button>
          <button
            type="button"
            onClick={onCancel}
            className="flex-1 py-2 rounded-md bg-control hover:bg-control-hover active:bg-control-pressed border border-stroke text-sm"
          >
            {t('btnCancel')}
          </button>
        </div>
      </div>

      {confirmRemove && (
        <div className="fixed inset-0 bg-smoke flex items-center justify-center z-[60] px-4">
          <div className="bg-dialog border border-surface-stroke rounded-lg shadow-dialog p-4 max-w-xs w-full">
            <p className="text-sm text-fg1 mb-1">{t('confirmRemovePresetTitle')}</p>
            <p className="text-xs text-fg3 mb-4 truncate">&ldquo;{draft.name}&rdquo;</p>
            <div className="flex gap-2 justify-end">
              <button
                type="button"
                onClick={() => setConfirmRemove(false)}
                className="px-3 py-1.5 text-sm text-fg2 hover:text-fg1 transition-colors"
              >
                {t('btnCancel')}
              </button>
              <button
                type="button"
                onClick={onDelete}
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

function DeletedPageDialog({ info, onDismiss }) {
  return (
    <div className="fixed inset-0 bg-smoke flex items-center justify-center z-50 px-4">
      <div className="bg-dialog border border-surface-stroke rounded-lg shadow-dialog p-4 max-w-xs w-full">
        <p className="text-sm font-semibold text-fg1 mb-2">{t('deletedPageTitle')}</p>
        <p className="text-xs text-fg2 mb-2">{t('deletedPageBody')}</p>
        {info.title && (
          <p className="text-xs text-fg2 bg-control rounded px-2 py-1 mb-2 truncate">
            &ldquo;{info.title}&rdquo;
          </p>
        )}
        <p className="text-xs text-fg3 mb-4">{t('deletedPageHint')}</p>
        <div className="flex justify-end">
          <button
            onClick={onDismiss}
            className="px-3 py-1.5 text-sm bg-control hover:bg-control-hover active:bg-control-pressed border border-stroke rounded-md transition-colors"
          >
            {t('deletedPageConfirm')}
          </button>
        </div>
      </div>
    </div>
  );
}

function RunningTimerItem({ rt, isActive, onLoad, onStop, onDelete, onOpenUrl }) {
  const showNotionLink = useStore((s) => s.showNotionLink);
  const showArchiveAction = useStore((s) => s.showArchiveAction);
  const [elapsed, setElapsed] = useState(0);

  useEffect(() => {
    const startMs = new Date(rt.startISO).getTime();
    const updateElapsed = () => {
      setElapsed(Math.max(0, Math.floor((Date.now() - startMs) / 1000)));
    };
    updateElapsed();
    const interval = setInterval(updateElapsed, 1000);
    return () => clearInterval(interval);
  }, [rt.startISO]);

  return (
    <div
      onClick={isActive ? undefined : () => onLoad(rt)}
      className={`flex items-center gap-2 px-3 py-2 bg-control rounded-md mb-1 transition-colors ${isActive ? 'opacity-60' : 'hover:bg-control-hover cursor-pointer'}`}
    >
      <div className="flex-1 min-w-0">
        <div className="text-sm truncate text-fg1">{rt.title}</div>
        <div className="flex flex-col gap-0.5 text-xs text-fg3 mt-0.5">
          <span className="truncate">{rt.workspaceName}</span>
          <span className="font-mono text-success font-semibold">{formatElapsed(elapsed)}</span>
        </div>
      </div>
      <div className="flex items-center gap-1 shrink-0">
        {showNotionLink && rt.url && (
          <button
            onClick={(e) => { e.stopPropagation(); onOpenUrl(rt.url); }}
            title={t('titleOpenInNotion')}
            className="text-fg3 hover:text-fg1 transition-colors flex items-center justify-center p-1"
          >
            <Open20Regular className="w-4 h-4" />
          </button>
        )}
        {showArchiveAction && (
        <button
          onClick={(e) => { e.stopPropagation(); onDelete(rt); }}
          title={t('titleDeleteEntry')}
          className="text-fg3 hover:text-danger transition-colors flex items-center justify-center p-1"
        >
          <Delete20Regular className="w-4 h-4" />
        </button>
        )}
        {!isActive && (
          <button
            onClick={(e) => { e.stopPropagation(); onStop(rt); }}
            title={t('titleStopRunningTimer')}
            className="text-fg3 hover:text-danger transition-colors flex items-center justify-center p-1"
          >
            <Stop20Regular className="w-4 h-4" />
          </button>
        )}
      </div>
    </div>
  );
}

/** Quick-start preset chips, shown when an API key is set and any workspace
 *  has presets, in both the idle form and the running view. Clicking one while a timer is already
 *  running goes through `applyPreset`'s "already running" confirmation. */
function PresetChips({ workspaces, signedIn, onApplyPreset, onManageClick, onNewClick, className = '' }) {
  const isDarkTheme = useIsDarkTheme();
  // Hidden (not just disabled) without an API key, same as on Android.
  if (!signedIn || workspaces.length === 0) return null;
  // Before the first preset, one quiet chip creates it.
  if (!workspaces.some((w) => (w.presets?.length ?? 0) > 0)) {
    return (
      <div className={`w-full flex ${className}`}>
        <button
          onClick={onNewClick}
          className="flex items-center gap-1 px-2.5 py-1 border border-dashed border-stroke-strong rounded-full text-xs text-fg2 hover:text-fg1 hover:bg-subtle-hover transition-colors"
        >
          <AddPreset20Regular className="w-3 h-3 shrink-0" />
          {t('presetNew')}
        </button>
      </div>
    );
  }
  return (
    <div className={`w-full flex flex-wrap items-center gap-1.5 ${className}`}>
      {workspaces.flatMap((w) =>
        (w.presets || []).map((preset) => {
          const hex = w.color?.trim();
          const accent = hex ? getWorkspaceAccentColor(hex, isDarkTheme) : null;
          const chipStyle = accent
            ? { borderColor: accent + '66', color: accent, backgroundColor: accent + '22' }
            : {};
          const label = preset.name;
          // The label only truncates once the chip would overflow the row,
          // instead of being cut off at a fixed width.
          return (
            <button
              key={`${w.id}:${preset.id}`}
              onClick={() => onApplyPreset(w.id, preset)}
              title={label}
              style={chipStyle}
              className="flex items-center gap-1 min-w-0 max-w-full px-2.5 py-1 bg-control hover:bg-control-hover border border-stroke hover:border-stroke-strong rounded-full text-xs text-fg2 hover:text-fg1 transition-colors"
            >
              <Play20Regular className="w-3 h-3 shrink-0" />
              <span className="min-w-0 truncate">{label}</span>
            </button>
          );
        })
      )}
      <button
        onClick={onManageClick}
        title={t('presetsManageTitle')}
        aria-label={t('presetsManageTitle')}
        className="p-1 text-fg3 hover:text-fg1 hover:bg-subtle-hover rounded-full transition-colors flex items-center justify-center text-sm"
      >
        <Edit20Regular className="w-4 h-4" />
      </button>
    </div>
  );
}

function OtherRunningTimers({ timers, activePageId, onLoad, onStop, onDelete, onOpenUrl }) {
  if (!timers.length) return null;
  return (
    <div className="w-full mt-6 pt-3 border-t border-stroke">
      <div className="mb-2">
        <span className="text-xs text-fg3">{t('runningTimersHeading')}</span>
      </div>
      {timers.map((rt) => (
        <RunningTimerItem
          key={rt.pageId}
          rt={rt}
          isActive={rt.pageId === activePageId}
          onLoad={onLoad}
          onStop={onStop}
          onDelete={onDelete}
          onOpenUrl={onOpenUrl}
        />
      ))}
    </div>
  );
}

function formatRecentRange(startISO, endISO) {
  if (!startISO) return '';
  const { dateFormat, timeFormat } = useStore.getState();
  const dateStr = formatDateStr(startISO, dateFormat);
  const startStr = formatTimeStr(startISO, timeFormat);
  if (endISO) {
    const start = new Date(startISO);
    const end = new Date(endISO);
    const isSameDay = start.getFullYear() === end.getFullYear() &&
                      start.getMonth() === end.getMonth() &&
                      start.getDate() === end.getDate();
    const endStr = formatTimeStr(endISO, timeFormat);
    if (isSameDay) {
      return `${dateStr} ${startStr} → ${endStr}`;
    } else {
      const endDateStr = formatDateStr(endISO, dateFormat);
      return `${dateStr} ${startStr} → ${endDateStr} ${endStr}`;
    }
  }
  return `${dateStr} ${startStr} → …`;
}

function RecentEntries({ entries, workspaces, onDelete, onOpenUrl, onPlay }) {
  const showNotionLink = useStore((s) => s.showNotionLink);
  const showArchiveAction = useStore((s) => s.showArchiveAction);
  const isDarkTheme = useIsDarkTheme();
  if (!entries.length) return null;
  return (
    <div className="w-full mt-6 pt-3 border-t border-stroke">
      <div className="flex items-center justify-between mb-2">
        <span className="text-xs text-fg3">{t('recentEntriesHeading')}</span>
      </div>
      {entries.map((entry) => {
        const wsColor = workspaces?.find((w) => w.id === entry.workspaceId)?.color?.trim();
        const accent = wsColor ? getWorkspaceAccentColor(wsColor, isDarkTheme) : null;
        return (
          <div
            key={entry.pageId}
            className="flex items-center gap-2 px-3 py-2 rounded-md mb-1 transition-colors border-l-2"
            style={
              accent
                ? { backgroundColor: accent + '1a', borderColor: accent }
                : { backgroundColor: 'var(--color-control)', borderColor: 'transparent' }
            }
          >
            <div className="flex-1 min-w-0">
              <div className="text-sm truncate text-fg2">{entry.title}</div>
              <div className="flex flex-col gap-0.5 text-xs text-fg3 mt-0.5">
                <span className="truncate">{entry.workspaceName}</span>
                <span className="font-mono text-fg3 whitespace-nowrap">{formatRecentRange(entry.startISO, entry.endISO)}</span>
              </div>
            </div>
            <div className="flex items-center gap-1 shrink-0">
              {onPlay && (
                <button
                  onClick={() => onPlay(entry)}
                  title={t('titleStartFromEntry')}
                  className="text-fg3 hover:text-success transition-colors flex items-center justify-center p-1"
                >
                  <Play20Regular className="w-4 h-4" />
                </button>
              )}
              {showNotionLink && entry.url && (
                <button
                  onClick={() => onOpenUrl(entry.url)}
                  title={t('titleOpenInNotion')}
                  className="text-fg3 hover:text-fg1 transition-colors flex items-center justify-center p-1"
                >
                  <Open20Regular className="w-4 h-4" />
                </button>
              )}
              {showArchiveAction && (
                <button
                  onClick={() => onDelete(entry)}
                  title={t('titleDeleteEntry')}
                  className="text-fg3 hover:text-danger transition-colors flex items-center justify-center p-1"
                >
                  <Delete20Regular className="w-4 h-4" />
                </button>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}


function DeleteConfirmDialog({ title, onConfirm, onCancel }) {
  return (
    <div className="fixed inset-0 bg-smoke flex items-center justify-center z-50 px-4">
      <div className="bg-dialog border border-surface-stroke rounded-lg shadow-dialog p-4 max-w-xs w-full">
        <p className="text-sm text-fg1 mb-1">{t('confirmDeleteTitle')}</p>
        <p className="text-xs text-fg3 mb-4 truncate">"{title}"</p>
        <div className="flex gap-2 justify-end">
          <button
            onClick={onCancel}
            className="px-3 py-1.5 text-sm text-fg2 hover:text-fg1 transition-colors"
          >
            {t('btnCancel')}
          </button>
          <button
            onClick={onConfirm}
            className="px-3 py-1.5 text-sm bg-red-700 hover:bg-red-600 text-white rounded-md font-medium transition-colors"
          >
            {t('confirmDeleteConfirm')}
          </button>
        </div>
      </div>
    </div>
  );
}

/** The view shown instead of the timer while `step` ('signIn' or 'workspace') is not set up. */
function SetupNeeded({ step, onOpenSettings }) {
  const Icon = step === 'signIn' ? Key20Regular : Briefcase20Regular;
  const title = step === 'signIn' ? t('setupConnectTitle') : t('setupWorkspaceTitle');
  const body = step === 'signIn' ? t('setupConnectBody') : t('setupWorkspaceBody');
  return (
    <div className="flex-1 flex flex-col items-center justify-center gap-2 px-8 py-6 overflow-y-auto text-center">
      <div className="w-16 h-16 mb-2 rounded-full bg-control flex items-center justify-center text-accent">
        <Icon className="w-8 h-8" />
      </div>
      <h2 className="text-base font-semibold text-fg1">{title}</h2>
      <p className="text-sm text-fg2 max-w-xs">{body}</p>
      <button
        onClick={onOpenSettings}
        className="mt-2 px-4 py-2 rounded-md bg-accent hover:bg-accent-hover active:bg-accent-pressed text-on-accent text-sm font-semibold"
      >
        {t('setupOpenSettings')}
      </button>
    </div>
  );
}
