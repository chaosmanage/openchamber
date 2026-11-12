import type { ProviderResult, QuotaProviderId, UsageWindow } from '@/types';
import { formatWindowLabel } from '@/lib/quota/utils';

export type WidgetQuota = {
  label: string;
  percent: number | null;
  used: number | null;
  value: string;
  detail?: string;
  resetAt: number | null;
};

export type WidgetProvider = {
  id: string;
  name: string;
  rows: WidgetQuota[];
};

export type WidgetUsageSnapshot = {
  mode: 'used' | 'remaining';
  updated: string;
  rows: WidgetProvider[];
};

export type WidgetQuotaInput = {
  displayMode: 'usage' | 'remaining';
  dropdownProviderIds: readonly QuotaProviderId[];
  selectedModels: Record<string, string[]>;
  results: readonly ProviderResult[];
};

const fraction = (n: number | null | undefined): number | null => (
  typeof n === 'number' && Number.isFinite(n)
    ? Math.min(100, Math.max(0, n)) : null
);

const quotaLabel = (key: string, providerId: string): string => {
  if (key === 'premium_interactions' && providerId.startsWith('github-copilot')) return 'AI Credits';
  if (key === 'credits_balance') return 'Credits balance';
  // Preserve names supplied by providers, including 5-hour / weekly and
  // the four Google/Non-Google Antigravity windows.
  const label = key.includes(' · ') ? key : formatWindowLabel(key);
  return label.replace(/models\b/gi, 'Models');
};

const quota = (key: string, window: UsageWindow, providerId: string, mode: 'used' | 'remaining'): WidgetQuota => {
  const used = fraction(window.usedPercent) ?? (
    fraction(window.remainingPercent) === null ? null : 100 - fraction(window.remainingPercent)!
  );
  const remaining = fraction(window.remainingPercent) ?? (used === null ? null : 100 - used);
  const percentage = mode === 'remaining' ? remaining : used;
  const raw = window.valueLabel ?? (
    typeof window.creditsUsed === 'number' ? `${window.creditsUsed} used` : undefined
  );
  const label = quotaLabel(key, providerId);
  const balance = key.toLowerCase().includes('balance') && percentage === null;
  return {
    label,
    percent: percentage,
    used,
    value: balance ? (raw ?? '–') : percentage === null ? '–' : `${Number(percentage.toFixed(1))}%`,
    ...(raw !== undefined ? { detail: raw } : {}),
    resetAt: window.resetAt !== null && Number.isFinite(window.resetAt) ? window.resetAt : null,
  };
};

/**
 * Share the actual Usage configuration, including the selected provider order,
 * model visibility, exact percentage precision, credits and reset timestamps.
 * No custom status-section SDK endpoint or provider-specific network call.
 */
export const createWidgetUsageSnapshot = (
  state: WidgetQuotaInput,
  now: Date = new Date(),
): WidgetUsageSnapshot => {
  const mode = state.displayMode === 'usage' ? 'used' : 'remaining';
  const rows = state.dropdownProviderIds.flatMap((id): WidgetProvider[] => {
    const result = state.results.find((entry) => entry.providerId === id);
    if (!result?.configured || !result.usage) return [];
    const windows: WidgetQuota[] = Object.entries(result.usage.windows ?? {})
      .map(([key, value]) => quota(key, value, id, mode));
    const selected = state.selectedModels[id];
    for (const [modelName, model] of Object.entries(result.usage.models ?? {})) {
      if (selected?.length && !selected.includes(modelName)) continue;
      for (const [key, window] of Object.entries(model.windows ?? {})) {
        windows.push(quota(`${modelName} · ${key}`, window, id, mode));
      }
    }
    return windows.length > 0 ? [{ id, name: result.providerName, rows: windows }] : [];
  });
  return {
    mode,
    updated: now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
    rows,
  };
};
