import React from 'react';
import { registerPlugin } from '@capacitor/core';

import { getRuntimeKey } from '@/lib/runtime-switch';
import { useGlobalSessionsStore } from '@/stores/useGlobalSessionsStore';
import { useGlobalSessionStatusStore } from '@/sync/global-session-status';
import { useQuotaStore } from '@/stores/useQuotaStore';

type WidgetBridge = {
  update(args: { usage: string; sessions: string }): Promise<void>;
};

const WidgetBridge = registerPlugin<WidgetBridge>('WidgetBridge');
const short = (value: string, size: number) => value.length > size ? value.slice(0, size - 1) + '…' : value;

const snapshot = () => {
  const quota = useQuotaStore.getState();
  const globals = useGlobalSessionsStore.getState();
  const live = useGlobalSessionStatusStore.getState();
  const displayMode = quota.displayMode;
  const usageRows = quota.dropdownProviderIds.flatMap((id) => {
    const result = quota.results.find((value) => value.providerId === id);
    if (!result || !result.configured) return [];
    const windows = Object.entries(result.usage?.windows ?? {});
    const models = Object.entries(result.usage?.models ?? {});
    // Some providers expose model-specific windows only.
    if (!windows.length) {
      for (const [modelName, model] of models) {
        const selected = quota.selectedModels[id];
        if (selected?.length && !selected.includes(modelName)) continue;
        for (const [label, window] of Object.entries(model.windows ?? {})) {
          windows.push([modelName + ' ' + label, window]);
        }
      }
    }
    const details = windows.slice(0, 4).map(([label, window]) => {
      const fraction = displayMode === 'remaining' ? window.remainingPercent : window.usedPercent;
      const percent = typeof fraction === 'number' && Number.isFinite(fraction)
        ? Math.round(Math.max(0, Math.min(100, fraction))) + '%' : '–';
      const compactLabel = label.replace(/models/gi, 'Models').replace(/5[- ]?hours?/gi, '5h').replace(/weekly/gi, '7d');
      return short(compactLabel, 34) + ' ' + percent;
    });
    const used = windows.map(([, window]) => window.usedPercent).filter((num): num is number => Number.isFinite(num));
    const progress = used.length ? Math.max(...used) : 0;
    return [{
      name: result.providerName,
      detail: details.length ? details.join(' · ') + ' ' + displayMode : (result.error || 'No quota reported'),
      progress: Math.round(progress),
    }];
  });

  const recent = globals.activeSessions
    .filter((session) => !session.parentID)
    .slice()
    .sort((a, b) => {
      const aBusy = live.activeSessionIds.has(a.id) ? 1 : 0;
      const bBusy = live.activeSessionIds.has(b.id) ? 1 : 0;
      if (aBusy !== bBusy) return bBusy - aBusy;
      return (b.time?.updated ?? b.time?.created ?? 0) - (a.time?.updated ?? a.time?.created ?? 0);
    })
    .slice(0, 6)
    .map((session) => {
      const running = live.activeSessionIds.has(session.id);
      const outcome = live.observedById.get(session.id)?.outcome;
      const status = running ? 'Running' : outcome === 'completed' ? 'Done'
        : outcome === 'failed' ? 'Failed' : 'Recent';
      return {
        id: session.id,
        directory: session.directory ?? '',
        name: session.title || 'Untitled session',
        detail: status + (session.directory ? ' · ' + session.directory.split(/[\\/]/).filter(Boolean).slice(-1)[0] : ''),
      };
    });

  const updated = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  return {
    usage: JSON.stringify({ runtime: getRuntimeKey(), updated, rows: usageRows }),
    sessions: JSON.stringify({ runtime: getRuntimeKey(), updated, rows: recent }),
  };
};

/**
 * Android home-screen widgets show cached redacted snapshots.
 * All network reads stay in the connected app (the widget never receives auth).
 * Store events are coalesced, with a periodic refresh while connected.
 */
export const useNativeWidgetSync = ({ enabled }: { enabled: boolean }): void => {
  React.useEffect(() => {
    const platform = typeof window === 'undefined' ? undefined
      : (window as typeof window & { Capacitor?: { getPlatform?: () => string } }).Capacitor?.getPlatform?.();
    if (!enabled || platform !== 'android') return;
    let disposed = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const update = () => {
      timer = null;
      if (disposed) return;
      void WidgetBridge.update(snapshot()).catch(() => undefined);
    };
    const schedule = () => {
      if (disposed || timer !== null) return;
      timer = setTimeout(update, 1200);
    };
    const listeners = [
      useQuotaStore.subscribe(schedule),
      useGlobalSessionsStore.subscribe(schedule),
      useGlobalSessionStatusStore.subscribe(schedule),
    ];
    schedule();
    // The widget itself counts as a quota consumer even when Usage is not open.
    void useQuotaStore.getState().ensureLoadedForRuntime().finally(schedule);
    const interval = setInterval(() => {
      void useQuotaStore.getState().ensureLoadedForRuntime({ refresh: true })
        .finally(schedule);
      schedule();
    }, 3 * 60 * 1000);
    const onVisible = () => { if (document.visibilityState === 'visible') schedule(); };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      disposed = true;
      if (timer !== null) clearTimeout(timer);
      clearInterval(interval);
      document.removeEventListener('visibilitychange', onVisible);
      for (const unsubscribe of listeners) unsubscribe();
    };
  }, [enabled]);
};
