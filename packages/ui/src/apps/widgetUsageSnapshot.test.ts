import { describe, expect, test } from 'bun:test';
import type { ProviderResult, UsageWindow } from '@/types';
import { createWidgetUsageSnapshot, type WidgetQuotaInput } from './widgetUsageSnapshot';

const quotaWindow = (
  used: number | null,
  remaining: number | null,
  valueLabel?: string,
  resetAt?: number,
): UsageWindow => ({
  usedPercent: used,
  remainingPercent: remaining,
  windowSeconds: null,
  resetAfterSeconds: null,
  resetAt: resetAt ?? null,
  resetAtFormatted: null,
  resetAfterFormatted: null,
  ...(valueLabel === undefined ? {} : { valueLabel }),
});

const provider = (
  id: ProviderResult['providerId'],
  name: string,
  windows: Record<string, UsageWindow>,
): ProviderResult => ({
  providerId: id,
  providerName: name,
  ok: true,
  configured: true,
  fetchedAt: 1,
  usage: { windows },
});

const data: WidgetQuotaInput = {
  displayMode: 'remaining',
  dropdownProviderIds: ['cursor', 'github-copilot', 'codex', 'ext-antigravity-usage'],
  selectedModels: {},
  results: [
    provider('cursor', 'Cursor Pro', {
      'Cursor Models': quotaWindow(0, 100, undefined, 1_800_000_000_000),
      'Other Models': quotaWindow(0, 100),
    }),
    provider('github-copilot', 'GitHub Copilot', {
      premium_interactions: quotaWindow(1.1, 98.9, '2.2 / 200 used', 1_800_000_000_000),
    }),
    provider('codex', 'Codex', {
      '5h': quotaWindow(32, 68),
      weekly: quotaWindow(21, 79),
      credits_balance: quotaWindow(null, null, '0'),
    }),
    provider('ext-antigravity-usage', 'Antigravity', {
      'Google models · 5-Hour': quotaWindow(0, 100),
      'Google models · Weekly': quotaWindow(1, 99),
      'Non-Google models · 5-Hour': quotaWindow(0, 100),
      'Non-Google models · Weekly': quotaWindow(0, 100),
    }),
  ],
};

describe('Android Usage widgets from built-in quota state', () => {
  test('keeps selected provider order and provider-defined windows', () => {
    const snapshot = createWidgetUsageSnapshot(data, new Date('2026-11-12T00:00:00Z'));
    expect(snapshot.rows.map((group) => group.name)).toEqual([
      'Cursor Pro', 'GitHub Copilot', 'Codex', 'Antigravity',
    ]);
    expect(snapshot.rows[0]?.rows.map((row) => row.label)).toEqual([
      'Cursor Models', 'Other Models',
    ]);
    expect(snapshot.rows[3]?.rows.map((row) => row.label)).toEqual([
      'Google Models · 5-Hour', 'Google Models · Weekly',
      'Non-Google Models · 5-Hour', 'Non-Google Models · Weekly',
    ]);
  });

  test('preserves decimal Copilot percentage, raw credits and reset timestamp', () => {
    expect(createWidgetUsageSnapshot(data).rows[1]?.rows[0]).toMatchObject({
      label: 'AI Credits', percent: 98.9, used: 1.1,
      value: '98.9%', detail: '2.2 / 200 used', resetAt: 1_800_000_000_000,
    });
  });

  test('renders credit balance as text without a fabricated percentage ring', () => {
    expect(createWidgetUsageSnapshot(data).rows[2]?.rows[2]).toMatchObject({
      label: 'Credits balance', percent: null, value: '0',
    });
  });

  test('honors used mode and hidden providers', () => {
    const snapshot = createWidgetUsageSnapshot({
      ...data,
      displayMode: 'usage',
      dropdownProviderIds: ['github-copilot'],
    });
    expect(snapshot.mode).toBe('used');
    expect(snapshot.rows).toHaveLength(1);
    expect(snapshot.rows[0]?.rows[0]?.value).toBe('1.1%');
  });

  test('honors selected models for model-scoped windows', () => {
    const google = provider('google', 'Google', {});
    google.usage = {
      windows: {},
      models: {
        'gemini/flash': { windows: { '5h': quotaWindow(4, 96) } },
        'gemini/pro': { windows: { weekly: quotaWindow(5, 95) } },
      },
    };
    const snapshot = createWidgetUsageSnapshot({
      ...data,
      dropdownProviderIds: ['google'],
      selectedModels: { google: ['gemini/pro'] },
      results: [google],
    });
    expect(snapshot.rows[0]?.rows).toHaveLength(1);
    expect(snapshot.rows[0]?.rows[0]?.label).toContain('gemini/pro');
  });
});
