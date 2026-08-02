import { Scheduler } from '../Scheduler';

/**
 * Schedule windows are parsed from user-supplied strings, so malformed input
 * must be rejected rather than silently producing a window that never opens —
 * or worse, one that opens at the wrong time.
 */
describe('Scheduler.parse', () => {
  it('parses a weekday range', () => {
    const window = Scheduler.parse('2-6 09:00-17:00');
    expect(window).not.toBeNull();
    expect(window!.days).toEqual([2, 3, 4, 5, 6]);
    expect(window!.startMinutes).toBe(9 * 60);
    expect(window!.endMinutes).toBe(17 * 60);
  });

  it('parses a comma-separated day list', () => {
    const window = Scheduler.parse('1,3,5 08:30-12:15');
    expect(window!.days).toEqual([1, 3, 5]);
    expect(window!.startMinutes).toBe(8 * 60 + 30);
    expect(window!.endMinutes).toBe(12 * 60 + 15);
  });

  it('parses a single day', () => {
    expect(Scheduler.parse('7 10:00-14:00')!.days).toEqual([7]);
  });

  it('tolerates surrounding whitespace', () => {
    expect(Scheduler.parse('  2-6 09:00-17:00  ')).not.toBeNull();
  });

  it('accepts a single-digit hour', () => {
    expect(Scheduler.parse('1 9:00-17:00')!.startMinutes).toBe(9 * 60);
  });

  it('rejects a window that ends before it starts', () => {
    // Would otherwise create a window that can never be inside.
    expect(Scheduler.parse('2-6 17:00-09:00')).toBeNull();
  });

  it('rejects a zero-length window', () => {
    expect(Scheduler.parse('2-6 09:00-09:00')).toBeNull();
  });

  it('rejects malformed input', () => {
    expect(Scheduler.parse('')).toBeNull();
    expect(Scheduler.parse('nonsense')).toBeNull();
    expect(Scheduler.parse('2-6')).toBeNull();
    expect(Scheduler.parse('09:00-17:00')).toBeNull();
    expect(Scheduler.parse('2-6 0900-1700')).toBeNull();
  });

  it('drops day numbers outside 1-7', () => {
    // 1 = Sunday … 7 = Saturday; 0 and 9 are not real days.
    expect(Scheduler.parse('0,9 09:00-17:00')).toBeNull();
    expect(Scheduler.parse('0,3,9 09:00-17:00')!.days).toEqual([3]);
  });
});

describe('Scheduler lifecycle', () => {
  let engaged = 0;
  let disengaged = 0;
  const scheduler = () =>
    new Scheduler(
      async () => {
        engaged += 1;
      },
      async () => {
        disengaged += 1;
      }
    );

  beforeEach(() => {
    engaged = 0;
    disengaged = 0;
    jest.useFakeTimers();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('does not run when every entry is malformed', () => {
    const instance = scheduler();
    instance.start(['nonsense', 'also bad'], false);
    expect(instance.isRunning).toBe(false);
  });

  it('runs when at least one entry is valid', () => {
    const instance = scheduler();
    instance.start(['nonsense', '1-7 00:00-23:59'], false);
    expect(instance.isRunning).toBe(true);
    instance.stop();
    expect(instance.isRunning).toBe(false);
  });

  it('engages tracking inside an always-open window', () => {
    const instance = scheduler();
    instance.start(['1-7 00:00-23:59'], false);
    expect(engaged).toBe(1);
    instance.stop();
  });

  it('does not re-engage while already inside the window', () => {
    const instance = scheduler();
    instance.start(['1-7 00:00-23:59'], false);
    jest.advanceTimersByTime(60_000 * 5);
    expect(engaged).toBe(1); // once, not once per tick
    instance.stop();
  });

  it('leaves tracking alone outside any window', () => {
    const instance = scheduler();
    // A window on no valid day can never be active.
    instance.start(['1-7 00:00-23:59'], true);
    expect(disengaged).toBe(0);
    instance.stop();
  });

  it('stop() detaches the interval so no further evaluation happens', () => {
    const instance = scheduler();
    instance.start(['1-7 00:00-23:59'], false);
    instance.stop();
    const engagedAfterStop = engaged;
    jest.advanceTimersByTime(60_000 * 10);
    expect(engaged).toBe(engagedAfterStop);
  });

  it('onSchedule subscribers can be removed', () => {
    const instance = scheduler();
    let notified = 0;
    const subscription = instance.onSchedule(() => {
      notified += 1;
    });
    subscription.remove();
    instance.start(['1-7 00:00-23:59'], false);
    jest.advanceTimersByTime(60_000);
    expect(notified).toBe(0);
    instance.stop();
  });
});
