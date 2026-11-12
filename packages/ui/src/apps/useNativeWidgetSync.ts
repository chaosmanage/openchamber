import React from 'react';
import { registerPlugin } from '@capacitor/core';

import { runtimeFetch } from '@/lib/runtime-fetch';
import { getGitStatus, getRemotes } from '@/lib/gitApiHttp';
import { getRuntimeKey } from '@/lib/runtime-switch';
import { useGlobalSessionsStore } from '@/stores/useGlobalSessionsStore';
import { useGlobalSessionStatusStore } from '@/sync/global-session-status';
import { useQuotaStore } from '@/stores/useQuotaStore';
import { useGitStore } from '@/stores/useGitStore';
import type { UsageWindow } from '@/types';

type WidgetBridge = {
  update(args: { usage: string; sessions: string }): Promise<void>;
};

type QuotaWidgetRow = {
  label: string;
  percent: number | null;
  value: string;
  detail?: string;
  resetAt?: number | null;
  used?: number | null;
};

type QuotaWidgetGroup = {
  id: string;
  name: string;
  rows: QuotaWidgetRow[];
};

type UsageWidgetData = {
  mode: 'used' | 'remaining';
  updated: string;
  rows: QuotaWidgetGroup[];
};

const WidgetBridge = registerPlugin<WidgetBridge>('WidgetBridge');
const short = (value: string, size: number) => value.length > size ? value.slice(0, size - 1) + '…' : value;
const clamp = (value: number) => Math.max(0, Math.min(100, value));
const updatedLabel = () => new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

/**
 * Reuse the installed 'usage-bars' OpenChamber SDK extension service.
 * That service applies the provider/model choices, merges Antigravity's four
 * quota windows, and returns the same names, percentages, raw credit totals
 * and reset timestamps used by the desktop Usage panel.
 *
 * If the guest is not installed, use the application's normal quota store.
 */
let cachedSdkUsage: { key: string; at: number; value: UsageWidgetData } | null = null;
let inFlightSdkUsage: Promise<UsageWidgetData | null> | null = null;

const readSdkUsage = async (): Promise<UsageWidgetData | null> => {
  const key = getRuntimeKey();
  if (cachedSdkUsage?.key === key && Date.now() - cachedSdkUsage.at < 45_000) {
    return cachedSdkUsage.value;
  }
  if (inFlightSdkUsage) return inFlightSdkUsage;
  const job = (async (): Promise<UsageWidgetData | null> => {
    try {
      const response = await runtimeFetch('/api/guests/usage-bars/service/request', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ method: 'GET', path: '/data' }),
      });
      if (!response.ok) return null;
      const envelope: unknown = await response.json();
      if (!envelope || typeof envelope !== 'object') return null;
      const service = envelope as { status?: number; body?: unknown };
      if (service.status !== 200) return null;
      const data: unknown = typeof service.body === 'string' ? JSON.parse(service.body) : service.body;
      if (!data || typeof data !== 'object') return null;
      const result = data as { mode?: string; groups?: unknown[]; error?: string };
      if (result.error || !Array.isArray(result.groups)) return null;
      const groups: QuotaWidgetGroup[] = result.groups.flatMap((raw): QuotaWidgetGroup[] => {
        if (!raw || typeof raw !== 'object') return [];
        const group = raw as { id?: string; name?: string; rows?: unknown[] };
        if (!group.id || !group.name || !Array.isArray(group.rows)) return [];
        const rows: QuotaWidgetRow[] = group.rows.flatMap((item): QuotaWidgetRow[] => {
          if (!item || typeof item !== 'object') return [];
          const value = item as {
            label?: string; percent?: number | null; value?: string;
            detail?: string; resetAt?: number | null; used?: number | null;
          };
          if (!value.label || typeof value.value !== 'string') return [];
          return [{
            label: value.label,
            percent: typeof value.percent === 'number' && Number.isFinite(value.percent) ? clamp(value.percent) : null,
            value: value.value,
            detail: value.detail,
            resetAt: typeof value.resetAt === 'number' && Number.isFinite(value.resetAt) ? value.resetAt : null,
            used: typeof value.used === 'number' && Number.isFinite(value.used) ? clamp(value.used) : null,
          }];
        });
        return [{ id: group.id, name: group.name, rows }];
      }).filter((group) => group.rows.length > 0);
      // A successful empty response is valid (no providers configured).
      const value: UsageWidgetData = {
        mode: result.mode === 'used' ? 'used' : 'remaining',
        updated: updatedLabel(),
        rows: groups,
      };
      if (getRuntimeKey() === key) cachedSdkUsage = { key, at: Date.now(), value };
      return getRuntimeKey() === key ? value : null;
    } catch {
      return null;
    }
  })();
  inFlightSdkUsage = job;
  try {
    return await job;
  } finally {
    if (inFlightSdkUsage === job) inFlightSdkUsage = null;
  }
};

const fromWindow = (label: string, window: UsageWindow, mode: 'used' | 'remaining'): QuotaWidgetRow => {
  const remaining = typeof window.remainingPercent === 'number' ? clamp(window.remainingPercent)
    : typeof window.usedPercent === 'number' ? clamp(100 - window.usedPercent) : null;
  const used = typeof window.usedPercent === 'number' ? clamp(window.usedPercent)
    : remaining === null ? null : 100 - remaining;
  const percent = mode === 'used' ? used : remaining;
  const balance = /credits.balance|credits_balance/i.test(label);
  return {
    label: label.replace(/models\b/gi, 'Models').replace(/\b7d\b/gi, 'Weekly')
      .replace(/\b5h\b/gi, '5-Hour').replace(/premium_interactions/g, 'AI Credits'),
    percent: balance ? null : percent,
    value: balance ? (window.valueLabel ?? String(window.creditsUsed ?? '–'))
      : percent === null ? '–' : `${Math.round(percent * 10) / 10}%`,
    detail: window.valueLabel ?? undefined,
    resetAt: window.resetAt,
    used,
  };
};

const fallbackUsage = (): UsageWidgetData => {
  const quota = useQuotaStore.getState();
  const mode = quota.displayMode === 'usage' ? 'used' : 'remaining';
  const rows = quota.dropdownProviderIds.flatMap((id): QuotaWidgetGroup[] => {
    const result = quota.results.find((value) => value.providerId === id);
    if (!result || !result.configured) return [];
    const windows = Object.entries(result.usage?.windows ?? {}).map(([label, window]) => fromWindow(label, window, mode));
    for (const [modelName, model] of Object.entries(result.usage?.models ?? {})) {
      const selected = quota.selectedModels[id];
      if (selected?.length && !selected.includes(modelName)) continue;
      for (const [label, window] of Object.entries(model.windows ?? {})) {
        windows.push(fromWindow(modelName + ' · ' + label, window, mode));
      }
    }
    if (!windows.length) return [];
    return [{ id, name: result.providerName, rows: windows }];
  });
  return { mode, updated: updatedLabel(), rows };
};

// Reuse OpenChamber's Git API. Never store remote URLs, auth data, or tokens in
// launcher snapshots; only short, human-readable GitHub and branch labels.
type WidgetRepo = { github?: string; branch?: string };
const gitWidgetCache = new Map<string, { at: number; value: WidgetRepo }>();
const GIT_WIDGET_TTL = 5 * 60_000;

const githubRepoName = (raw: string): string | undefined => {
  const remote = raw.trim();
  const match = /^(?:https:\/\/|ssh:\/\/git@|git@)github\.com(?::|\/)([A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+?)(?:\.git)?\/?$/.exec(remote);
  return match?.[1];
};

const repoForDirectory = async (directory: string): Promise<WidgetRepo> => {
  if (!directory) return {};
  const key = JSON.stringify([getRuntimeKey(), directory]);
  const state = useGitStore.getState().getDirectoryState(directory);
  const cached = gitWidgetCache.get(key);
  const branch = state?.status?.current?.trim() || cached?.value.branch;
  if (cached && Date.now() - cached.at < GIT_WIDGET_TTL) {
    return { ...cached.value, ...(branch ? { branch } : {}) };
  }
  let github: string | undefined;
  try {
    const remotes = await getRemotes(directory);
    const preferred = [...remotes].sort((a, b) => Number(b.name === 'origin') - Number(a.name === 'origin'));
    for (const remote of preferred) {
      github = githubRepoName(remote.fetchUrl) ?? githubRepoName(remote.pushUrl);
      if (github) break;
    }
  } catch {
    // Not every project is a Git repo or has a remote.
  }
  let resolvedBranch = branch;
  if (!resolvedBranch) {
    try {
      const gitStatus = await getGitStatus(directory, { mode: 'light' });
      resolvedBranch = gitStatus.current?.trim() || undefined;
    } catch {
      // Unknown branches are omitted, not guessed.
    }
  }
  const value = {
    ...(github ? { github: short(github, 46) } : {}),
    ...(resolvedBranch && resolvedBranch !== 'HEAD' ? { branch: short(resolvedBranch, 46) } : {}),
  };
  if (gitWidgetCache.size > 128) gitWidgetCache.clear();
  gitWidgetCache.set(key, { at: Date.now(), value });
  return value;
};

const sessionSnapshot = async () => {
  const globals = useGlobalSessionsStore.getState();
  const live = useGlobalSessionStatusStore.getState();
  const sessions = globals.activeSessions
    .filter((session) => !session.parentID)
    .slice()
    .sort((a, b) => {
      const aBusy = live.activeSessionIds.has(a.id) ? 1 : 0;
      const bBusy = live.activeSessionIds.has(b.id) ? 1 : 0;
      if (aBusy !== bBusy) return bBusy - aBusy;
      return (b.time?.updated ?? b.time?.created ?? 0) - (a.time?.updated ?? a.time?.created ?? 0);
    })
    .slice(0, 6);
  const directories = [...new Set(sessions.map((session) => session.directory).filter(Boolean))];
  const repoByDirectory = new Map(await Promise.all(directories.map(async (directory) => (
    [directory, await repoForDirectory(directory)] as const
  ))));
  const rows = sessions.map((session) => {
      const active = live.statusById.get(session.id)?.status.type;
      const outcome = live.observedById.get(session.id)?.outcome;
      const status = active === 'retry' ? 'Retrying'
        : live.activeSessionIds.has(session.id) ? 'Running'
        : outcome === 'completed' ? 'Done'
        : outcome === 'failed' ? 'Failed' : 'Recent';
      const project = session.directory?.split(/[\\/]/).filter(Boolean).slice(-1)[0] ?? '';
      const repo = repoByDirectory.get(session.directory) ?? {};
      return {
        id: session.id,
        directory: session.directory ?? '',
        name: short(session.title || 'Untitled session', 95),
        project: short(project, 35),
        agent: short(session.agent || '', 20),
        status,
        ...(repo.github ? { github: repo.github } : {}),
        ...(repo.branch ? { branch: repo.branch } : {}),
      };
    });
  return { updated: updatedLabel(), rows };
};

const snapshot = async (): Promise<{ usage: string; sessions: string }> => {
  const usage = await readSdkUsage() ?? fallbackUsage();
  return {
    usage: JSON.stringify(usage),
    sessions: JSON.stringify(await sessionSnapshot()),
  };
};

/** Widgets use the desktop SDK Usage guest's data, with a native-store fallback.
 * All network reads stay inside the connected Android WebView. Cached widget
 * snapshots contain only displayed quota/session information, never auth tokens.
 */
export const useNativeWidgetSync = ({ enabled }: { enabled: boolean }): void => {
  React.useEffect(() => {
    const platform = typeof window === 'undefined' ? undefined
      : (window as typeof window & { Capacitor?: { getPlatform?: () => string } }).Capacitor?.getPlatform?.();
    if (!enabled || platform !== 'android') return;
    let disposed = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let writing = false;
    const update = () => {
      timer = null;
      if (disposed || writing) return;
      writing = true;
      void snapshot().then((data) => {
        if (!disposed) return WidgetBridge.update(data);
      }).catch(() => undefined).finally(() => { writing = false; });
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
    void useQuotaStore.getState().ensureLoadedForRuntime().finally(schedule);
    const interval = setInterval(() => {
      void useQuotaStore.getState().ensureLoadedForRuntime({ refresh: true }).finally(schedule);
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
