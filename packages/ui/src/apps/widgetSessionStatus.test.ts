import { describe, expect, test } from 'bun:test';
import { WidgetCompletionTracker, WIDGET_DONE_DURATION_MS } from './widgetSessionStatus';

describe('Android home-widget completion expiry', () => {
  test('Done expires to Idle after 3 minutes even without another status event', () => {
    const tracker = new WidgetCompletionTracker();
    const observation = { outcome: 'completed' as const };
    expect(tracker.resolve('s1', false, false, observation, 1000)).toEqual({
      status: 'Done', doneUntil: 1000 + WIDGET_DONE_DURATION_MS,
    });
    expect(tracker.resolve('s1', false, false, observation, 1000 + WIDGET_DONE_DURATION_MS - 1).status).toBe('Done');
    expect(tracker.resolve('s1', false, false, observation, 1000 + WIDGET_DONE_DURATION_MS)).toEqual({ status: 'Idle' });
    // Repeated snapshots cannot re-arm an already-expired Done state.
    expect(tracker.resolve('s1', false, false, observation, 999_999)).toEqual({ status: 'Idle' });
  });

  test('a genuinely new completed turn gets its own Done window', () => {
    const tracker = new WidgetCompletionTracker();
    const old = { outcome: 'completed' as const };
    const next = { outcome: 'completed' as const };
    tracker.resolve('s1', false, false, old, 0);
    expect(tracker.resolve('s1', true, false, old, 100).status).toBe('Running');
    expect(tracker.resolve('s1', false, false, next, 200)).toEqual({
      status: 'Done', doneUntil: 200 + WIDGET_DONE_DURATION_MS,
    });
  });

  test('Running, Retrying, Failed and unknown outcomes retain their semantics', () => {
    const tracker = new WidgetCompletionTracker();
    const completed = { outcome: 'completed' as const };
    expect(tracker.resolve('s', true, false, completed).status).toBe('Running');
    expect(tracker.resolve('s', true, true, completed).status).toBe('Retrying');
    expect(tracker.resolve('s', false, false, { outcome: 'failed' }).status).toBe('Failed');
    expect(tracker.resolve('s', false, false, undefined).status).toBe('Idle');
    expect(tracker.resolve('s', false, false, { outcome: null }).status).toBe('Idle');
  });

  test('forgets sessions that drop out of the ten-session widget window', () => {
    const tracker = new WidgetCompletionTracker();
    const completed = { outcome: 'completed' as const };
    tracker.resolve('old', false, false, completed, 100);
    tracker.retain(['new']);
    expect(tracker.resolve('old', false, false, completed, 200)).toMatchObject({
      status: 'Done', doneUntil: 200 + WIDGET_DONE_DURATION_MS,
    });
  });
});
