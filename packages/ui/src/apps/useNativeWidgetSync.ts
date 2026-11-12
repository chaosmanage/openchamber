import React from 'react';
import { registerPlugin } from '@capacitor/core';

import { getGitStatus, getRemotes } from '@/lib/gitApiHttp';
import { useOptionalThemeSystem } from '@/contexts/useThemeSystem';
import type { Theme } from '@/types/theme';
import { getRuntimeKey } from '@/lib/runtime-switch';
import { useGlobalSessionsStore } from '@/stores/useGlobalSessionsStore';
import { useGlobalSessionStatusStore } from '@/sync/global-session-status';
import { useQuotaStore } from '@/stores/useQuotaStore';
import { createWidgetUsageSnapshot } from './widgetUsageSnapshot';
import { WidgetCompletionTracker } from './widgetSessionStatus';
import { useGitStore } from '@/stores/useGitStore';

type WidgetBridge = {
  update(args: { usage: string; sessions: string }): Promise<void>;
};

const WidgetBridge = registerPlugin<WidgetBridge>('WidgetBridge');
const short = (value: string, size: number) => value.length > size ? value.slice(0, size - 1) + '…' : value;
const updatedLabel = () => new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

/** The native RemoteViews widget uses the active OpenChamber theme palette.
 * Only display-safe color hexes are passed; themes never carry credentials. */
const themePalette = (theme?: Theme) => {
  const colors = theme?.colors;
  return {
    variant: theme?.metadata.variant === 'light' ? 'light' : 'dark',
    primary: colors?.primary.base ?? '#da7c47',
    foreground: colors?.surface.foreground ?? '#dbd7cc',
    muted: colors?.surface.mutedForeground ?? '#89867c',
    info: colors?.status.info ?? '#479fe6',
    success: colors?.status.success ?? '#76ad4f',
    warning: colors?.status.warning ?? '#c67f13',
    error: colors?.status.error ?? '#da5b4a',
  };
};
type WidgetPalette = ReturnType<typeof themePalette>;


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

const widgetCompletionTracker = new WidgetCompletionTracker();

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
    .slice(0, 10);
  const directories = [...new Set(sessions.map((session) => session.directory).filter(Boolean))];
  const repoByDirectory = new Map(await Promise.all(directories.map(async (directory) => (
    [directory, await repoForDirectory(directory)] as const
  ))));
  const rows = sessions.map((session) => {
      const active = live.statusById.get(session.id)?.status.type;
      const completion = widgetCompletionTracker.resolve(
        session.id,
        live.activeSessionIds.has(session.id),
        active === 'retry',
        live.observedById.get(session.id),
      );
      const project = session.directory?.split(/[\\/]/).filter(Boolean).slice(-1)[0] ?? '';
      const repo = repoByDirectory.get(session.directory) ?? {};
      return {
        id: session.id,
        directory: session.directory ?? '',
        name: short(session.title || 'Untitled session', 95),
        project: short(project, 35),
        ...completion,
        ...(repo.github ? { github: repo.github } : {}),
        ...(repo.branch ? { branch: repo.branch } : {}),
      };
    });
  widgetCompletionTracker.retain(sessions.map((session) => session.id));
  return { updated: updatedLabel(), rows };
};

const snapshot = async (palette: WidgetPalette): Promise<{ usage: string; sessions: string }> => {
  // Same quota store and provider visibility/model preferences as Usage.
  const usage = createWidgetUsageSnapshot(useQuotaStore.getState());
  return {
    usage: JSON.stringify({ ...usage, palette }),
    sessions: JSON.stringify({ ...(await sessionSnapshot()), palette }),
  };
};

/** Widgets use OpenChamber's built-in quota and session stores.
 * All network reads stay inside the connected Android WebView. Cached widget
 * snapshots contain only displayed quota/session information, never auth tokens.
 */
export const useNativeWidgetSync = ({ enabled }: { enabled: boolean }): void => {
  const activeTheme = useOptionalThemeSystem()?.currentTheme;
  const palette = React.useMemo(() => themePalette(activeTheme), [activeTheme]);
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
      void snapshot(palette).then((data) => {
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
  }, [enabled, palette]);
};
