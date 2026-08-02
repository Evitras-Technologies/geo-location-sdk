import type { Subscription } from './types/Config';

// Provided globally by the React Native runtime; declared here so the library
// compiles standalone without the DOM lib.
declare function setInterval(handler: () => void, timeoutMs: number): unknown;
declare function clearInterval(handle: unknown): void;

interface ScheduleWindow {
  /** Days of week the window applies to; 1 = Sunday … 7 = Saturday. */
  days: number[];
  /** Window start, minutes after midnight. */
  startMinutes: number;
  /** Window end, minutes after midnight. */
  endMinutes: number;
}

/**
 * Evaluates {@link Config.schedule} entries of the form `"{DAYS} {START}-{END}"`
 * (e.g. `"2-6 09:00-17:00"`, `"1,7 10:00-14:00"`) once per minute while the
 * JS runtime is alive, starting/stopping tracking as windows open and close.
 *
 * Note: unlike native alarm scheduling, this runs in the JS thread — windows
 * are honored while your app is running (foreground or background-headless).
 */
export class Scheduler {
  private windows: ScheduleWindow[] = [];
  private timer: ReturnType<typeof setInterval> | null = null;
  private trackingEngaged = false;
  private listeners = new Set<() => void>();

  constructor(
    private readonly engageTracking: () => Promise<unknown>,
    private readonly disengageTracking: () => Promise<unknown>
  ) {}

  start(schedule: string[], currentlyEnabled: boolean): void {
    this.stop();
    this.windows = schedule
      .map((entry) => Scheduler.parse(entry))
      .filter((window): window is ScheduleWindow => window !== null);
    if (this.windows.length === 0) {
      return;
    }
    this.trackingEngaged = currentlyEnabled;
    this.evaluate();
    this.timer = setInterval(() => this.evaluate(), 60_000);
  }

  stop(): void {
    if (this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
    this.windows = [];
  }

  get isRunning(): boolean {
    return this.timer !== null;
  }

  onSchedule(listener: () => void): Subscription {
    this.listeners.add(listener);
    return { remove: () => this.listeners.delete(listener) };
  }

  private evaluate(): void {
    const now = new Date();
    const day = now.getDay() + 1; // JS: 0=Sunday → schedule format: 1=Sunday
    const minutes = now.getHours() * 60 + now.getMinutes();

    const insideWindow = this.windows.some(
      (window) =>
        window.days.includes(day) &&
        minutes >= window.startMinutes &&
        minutes < window.endMinutes
    );

    if (insideWindow && !this.trackingEngaged) {
      this.trackingEngaged = true;
      this.engageTracking().then(() => this.notify());
    } else if (!insideWindow && this.trackingEngaged) {
      this.trackingEngaged = false;
      this.disengageTracking().then(() => this.notify());
    }
  }

  private notify(): void {
    this.listeners.forEach((listener) => listener());
  }

  /** Parse `"2-6 09:00-17:00"` / `"1,3,5 08:30-12:00"`; returns `null` on malformed input. */
  static parse(entry: string): ScheduleWindow | null {
    const match = entry.trim().match(/^([\d,\-]+)\s+(\d{1,2}):(\d{2})-(\d{1,2}):(\d{2})$/);
    if (!match) {
      return null;
    }
    const [, dayExpression, startHour, startMinute, endHour, endMinute] = match;

    const days = new Set<number>();
    for (const part of dayExpression.split(',')) {
      const range = part.split('-').map(Number);
      if (range.some(Number.isNaN)) {
        return null;
      }
      if (range.length === 2) {
        for (let d = range[0]; d <= range[1]; d++) days.add(d);
      } else {
        days.add(range[0]);
      }
    }

    const startMinutes = Number(startHour) * 60 + Number(startMinute);
    const endMinutes = Number(endHour) * 60 + Number(endMinute);
    if (startMinutes >= endMinutes) {
      return null;
    }

    const validDays = [...days].filter((d) => d >= 1 && d <= 7);
    return validDays.length > 0 ? { days: validDays, startMinutes, endMinutes } : null;
  }
}
