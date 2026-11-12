/**
 * A completed turn is worth highlighting briefly, not indefinitely.
 * We retain the completion's observation identity so ordinary quota/session
 * resyncs cannot restart the "Done" countdown.
 */
export const WIDGET_DONE_DURATION_MS = 3 * 60_000;

export type WidgetSessionStatus = 'Running' | 'Retrying' | 'Done' | 'Failed' | 'Idle';
export type WidgetObservedOutcome = { outcome: 'completed' | 'failed' | null };

export type WidgetSessionState = {
  status: WidgetSessionStatus;
  /** Wall-clock expiry; the native widget can expire Done while the app is closed. */
  doneUntil?: number;
};

export class WidgetCompletionTracker {
  private readonly completions = new Map<string, { observation: WidgetObservedOutcome; until: number }>();

  resolve(
    sessionId: string,
    isRunning: boolean,
    isRetrying: boolean,
    observed: WidgetObservedOutcome | undefined,
    now: number = Date.now(),
  ): WidgetSessionState {
    if (isRetrying || isRunning) {
      this.completions.delete(sessionId);
      return { status: isRetrying ? 'Retrying' : 'Running' };
    }
    if (observed?.outcome === 'failed') {
      this.completions.delete(sessionId);
      return { status: 'Failed' };
    }
    if (observed?.outcome !== 'completed') {
      this.completions.delete(sessionId);
      return { status: 'Idle' };
    }

    let completion = this.completions.get(sessionId);
    if (!completion || completion.observation !== observed) {
      completion = { observation: observed, until: now + WIDGET_DONE_DURATION_MS };
      this.completions.set(sessionId, completion);
    }
    // Once expired, preserve the entry while the observation is the same.
    // Otherwise every subsequent snapshot would restart the Done countdown.
    return completion.until > now
      ? { status: 'Done', doneUntil: completion.until }
      : { status: 'Idle' };
  }

  retain(sessionIds: Iterable<string>): void {
    const active = new Set(sessionIds);
    for (const id of this.completions.keys()) {
      if (!active.has(id)) this.completions.delete(id);
    }
  }
}
