import { AppRegistry, Platform } from 'react-native';

import { EventEmitter, NativeEvents, NativeModule } from './NativeModule';
import { Scheduler } from './Scheduler';
import type {
  Config,
  ConnectivityChangeEvent,
  DeviceInfo,
  EnabledChangeEvent,
  HeartbeatEvent,
  HttpEvent,
  MotionActivityEvent,
  MotionChangeEvent,
  PermissionStatus,
  SyncStatus,
  PowerSaveChangeEvent,
  ProviderChangeEvent,
  ScheduleEvent,
  Sensors,
  State,
  Subscription,
} from './types/Config';
import {
  AccuracyAuthorization,
  ActivityType,
  AuthorizationStatus,
  DesiredAccuracy,
  LogLevel,
  NotificationPriority,
  PersistMode,
} from './types/Config';
import type { Geofence, GeofenceEvent, GeofencesChangeEvent } from './types/Geofence';
import type {
  CurrentPositionRequest,
  Location,
  WatchPositionRequest,
} from './types/Location';

type HeadlessEvent = { name: string; params: unknown };
type HeadlessTask = (event: HeadlessEvent) => Promise<void> | void;

const HEADLESS_TASK_KEY = 'OpenBackgroundGeolocationHeadlessTask';

function subscribe(eventName: string, handler: (event: never) => void): Subscription {
  const subscription = EventEmitter.addListener(eventName, handler as (event: unknown) => void);
  return { remove: () => subscription.remove() };
}

/**
 * # BackgroundGeolocation
 *
 * A battery-conscious background location-tracking SDK for React Native and
 * Expo. The SDK uses a motion-detection state machine: while the device is
 * *stationary* location services are off; when movement is detected tracking
 * engages; after {@link Config.stopTimeout} minutes of stillness it powers
 * back down.
 *
 * ```ts
 * import BackgroundGeolocation from '@evitras-technologies/react-native-background-geolocation';
 *
 * const onLocation = BackgroundGeolocation.onLocation((location) => {
 *   console.log('[location]', location);
 * });
 *
 * const state = await BackgroundGeolocation.ready({
 *   desiredAccuracy: BackgroundGeolocation.DESIRED_ACCURACY_HIGH,
 *   distanceFilter: 10,
 *   stopOnTerminate: false,
 *   startOnBoot: true,
 *   url: 'https://your.server.com/locations',
 * });
 *
 * if (!state.enabled) {
 *   await BackgroundGeolocation.start();
 * }
 * ```
 */
class BackgroundGeolocationFacade {
  // ── Constants (mirroring the familiar plugin surface) ─────────────────────
  readonly DESIRED_ACCURACY_NAVIGATION = DesiredAccuracy.NAVIGATION;
  readonly DESIRED_ACCURACY_HIGH = DesiredAccuracy.HIGH;
  readonly DESIRED_ACCURACY_MEDIUM = DesiredAccuracy.MEDIUM;
  readonly DESIRED_ACCURACY_LOW = DesiredAccuracy.LOW;
  readonly DESIRED_ACCURACY_VERY_LOW = DesiredAccuracy.VERY_LOW;
  readonly DESIRED_ACCURACY_LOWEST = DesiredAccuracy.LOWEST;

  readonly AUTHORIZATION_STATUS_NOT_DETERMINED = AuthorizationStatus.NOT_DETERMINED;
  readonly AUTHORIZATION_STATUS_RESTRICTED = AuthorizationStatus.RESTRICTED;
  readonly AUTHORIZATION_STATUS_DENIED = AuthorizationStatus.DENIED;
  readonly AUTHORIZATION_STATUS_ALWAYS = AuthorizationStatus.ALWAYS;
  readonly AUTHORIZATION_STATUS_WHEN_IN_USE = AuthorizationStatus.WHEN_IN_USE;

  readonly ACCURACY_AUTHORIZATION_FULL = AccuracyAuthorization.FULL;
  readonly ACCURACY_AUTHORIZATION_REDUCED = AccuracyAuthorization.REDUCED;

  readonly LOG_LEVEL_OFF = LogLevel.OFF;
  readonly LOG_LEVEL_ERROR = LogLevel.ERROR;
  readonly LOG_LEVEL_WARNING = LogLevel.WARNING;
  readonly LOG_LEVEL_INFO = LogLevel.INFO;
  readonly LOG_LEVEL_DEBUG = LogLevel.DEBUG;
  readonly LOG_LEVEL_VERBOSE = LogLevel.VERBOSE;

  readonly ACTIVITY_TYPE_OTHER = ActivityType.OTHER;
  readonly ACTIVITY_TYPE_AUTOMOTIVE_NAVIGATION = ActivityType.AUTOMOTIVE_NAVIGATION;
  readonly ACTIVITY_TYPE_FITNESS = ActivityType.FITNESS;
  readonly ACTIVITY_TYPE_OTHER_NAVIGATION = ActivityType.OTHER_NAVIGATION;

  readonly PERSIST_MODE_ALL = PersistMode.ALL;
  readonly PERSIST_MODE_LOCATION = PersistMode.LOCATION;
  readonly PERSIST_MODE_GEOFENCE = PersistMode.GEOFENCE;
  readonly PERSIST_MODE_NONE = PersistMode.NONE;

  readonly NOTIFICATION_PRIORITY_DEFAULT = NotificationPriority.DEFAULT;
  readonly NOTIFICATION_PRIORITY_HIGH = NotificationPriority.HIGH;
  readonly NOTIFICATION_PRIORITY_LOW = NotificationPriority.LOW;
  readonly NOTIFICATION_PRIORITY_MAX = NotificationPriority.MAX;
  readonly NOTIFICATION_PRIORITY_MIN = NotificationPriority.MIN;

  private scheduler = new Scheduler(
    () => this.start(),
    () => this.stop()
  );

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  /**
   * Signal that your app is ready to receive events and apply configuration.
   * Must be called every launch, as early as possible, **after** registering
   * event listeners. The provided config is applied on first install and
   * remembered thereafter (pass `reset: true` inside `setConfig`-style calls
   * or use {@link reset} to force-apply).
   */
  ready(config: Config = {}): Promise<State> {
    return NativeModule.ready(config as Record<string, unknown>) as Promise<State>;
  }

  /** Re-apply the default configuration merged with `config`, ignoring persisted values. */
  reset(config: Config = {}): Promise<State> {
    return NativeModule.reset(config as Record<string, unknown>) as Promise<State>;
  }

  /** Merge new configuration values at runtime. */
  setConfig(config: Config): Promise<State> {
    return NativeModule.setConfig(config as Record<string, unknown>) as Promise<State>;
  }

  /** Fetch the current SDK {@link State} (persisted config + live flags). */
  getState(): Promise<State> {
    return NativeModule.getState() as Promise<State>;
  }

  /**
   * Engage location + geofence tracking.
   *
   * Resolves with the new {@link State}. **Always check `state.enabled`**: if
   * the user declined location permission the SDK refuses to start and
   * resolves with `enabled: false` rather than pretending to track. It also
   * emits `onEnabledChange(false)` and `onProviderChange`, so a UI toggle can
   * fall back correctly instead of showing "on" while nothing is recorded.
   *
   * Recover by calling {@link requestAllPermissions} and then `start()` again,
   * or set `autoRequestPermissions: true` in {@link ready} to have the SDK
   * prompt at launch.
   */
  start(): Promise<State> {
    return NativeModule.start() as Promise<State>;
  }

  /** Halt tracking and location-services. Resolves with the new {@link State}. */
  stop(): Promise<State> {
    this.scheduler.stop();
    return NativeModule.stop() as Promise<State>;
  }

  /** Engage geofences-only mode: no continuous location, geofences still fire. */
  startGeofences(): Promise<State> {
    return NativeModule.startGeofences() as Promise<State>;
  }

  /**
   * Manually toggle the motion state. `changePace(true)` forces the SDK into
   * the *moving* state (location updates engage immediately);
   * `changePace(false)` forces *stationary*.
   */
  changePace(isMoving: boolean): Promise<void> {
    return NativeModule.changePace(isMoving);
  }

  /** Engage the configured {@link Config.schedule}. */
  async startSchedule(): Promise<State> {
    const state = await this.getState();
    this.scheduler.start(state.schedule ?? [], state.enabled);
    return this.getState();
  }

  /** Disengage the schedule (tracking state is left as-is). */
  async stopSchedule(): Promise<State> {
    this.scheduler.stop();
    return this.getState();
  }

  // ── Geolocation ───────────────────────────────────────────────────────────

  /** Fetch a fresh, one-shot position. */
  getCurrentPosition(options: CurrentPositionRequest = {}): Promise<Location> {
    return NativeModule.getCurrentPosition(
      options as Record<string, unknown>
    ) as Promise<Location>;
  }

  /** Begin a stream of raw position samples. Stop with {@link stopWatchPosition}. */
  async watchPosition(
    onLocation: (location: Location) => void,
    onError?: (errorCode: number) => void,
    options: WatchPositionRequest = {}
  ): Promise<Subscription> {
    const subscription = EventEmitter.addListener(
      NativeEvents.WATCHPOSITION,
      (location: Location & { error?: number }) => {
        if (location.error != null) {
          onError?.(location.error);
        } else {
          onLocation(location);
        }
      }
    );
    await NativeModule.watchPosition(options as Record<string, unknown>);
    return {
      remove: () => {
        subscription.remove();
        NativeModule.stopWatchPosition();
      },
    };
  }

  /** Stop the {@link watchPosition} stream. */
  stopWatchPosition(): Promise<void> {
    return NativeModule.stopWatchPosition();
  }

  /** Current odometer reading in meters. */
  getOdometer(): Promise<number> {
    return NativeModule.getOdometer();
  }

  /** Set the odometer to an arbitrary value. Resolves with the location recorded at reset. */
  setOdometer(value: number): Promise<Location> {
    return NativeModule.setOdometer(value) as Promise<Location>;
  }

  /** Reset the odometer to `0`. */
  resetOdometer(): Promise<Location> {
    return this.setOdometer(0);
  }

  // ── Database & HTTP ───────────────────────────────────────────────────────

  /** All locations currently persisted in the SDK's SQLite database. */
  getLocations(): Promise<Location[]> {
    return NativeModule.getLocations() as Promise<Location[]>;
  }

  /** Count of persisted locations. */
  getCount(): Promise<number> {
    return NativeModule.getCount();
  }

  /**
   * Connectivity and sync health.
   *
   * Locations are always written to SQLite before upload, so an outage never
   * loses data — but without this the app has no way to show it. Use it to
   * display "offline · 42 queued" rather than appearing frozen, and to detect
   * a degraded link (`networkSlow`) as distinct from no link at all.
   *
   * The same document is attached to every upload as `network`, so a server
   * can record how long each device was offline.
   */
  getSyncStatus(): Promise<SyncStatus> {
    return NativeModule.getSyncStatus();
  }

  /** Manually insert a location into the database. Resolves with its `uuid`. */
  insertLocation(location: Partial<Location>): Promise<string> {
    return NativeModule.insertLocation(location as Record<string, unknown>);
  }

  /** Delete all persisted locations. */
  destroyLocations(): Promise<void> {
    return NativeModule.destroyLocations();
  }

  /** Delete a single persisted location by `uuid`. */
  destroyLocation(uuid: string): Promise<void> {
    return NativeModule.destroyLocation(uuid);
  }

  /**
   * Immediately upload all persisted locations to {@link Config.url}.
   * Resolves with the records that were synced (records are deleted from the
   * database once the server responds 2xx).
   */
  sync(): Promise<Location[]> {
    return NativeModule.sync() as Promise<Location[]>;
  }

  // ── Geofencing ────────────────────────────────────────────────────────────

  /** Add (or replace) a single geofence. */
  addGeofence(geofence: Geofence): Promise<void> {
    return NativeModule.addGeofence(geofence as unknown as Record<string, unknown>);
  }

  /** Add (or replace) many geofences at once. */
  addGeofences(geofences: Geofence[]): Promise<void> {
    return NativeModule.addGeofences(geofences as unknown as Array<Record<string, unknown>>);
  }

  /** Remove a geofence by `identifier`. */
  removeGeofence(identifier: string): Promise<void> {
    return NativeModule.removeGeofence(identifier);
  }

  /** Remove all geofences. */
  removeGeofences(): Promise<void> {
    return NativeModule.removeGeofences();
  }

  /** All registered geofences. */
  getGeofences(): Promise<Geofence[]> {
    return NativeModule.getGeofences() as Promise<Geofence[]>;
  }

  /** A single geofence by `identifier` (rejects when not found). */
  getGeofence(identifier: string): Promise<Geofence> {
    return NativeModule.getGeofence(identifier) as unknown as Promise<Geofence>;
  }

  /** `true` when a geofence with `identifier` is registered. */
  geofenceExists(identifier: string): Promise<boolean> {
    return NativeModule.geofenceExists(identifier);
  }

  // ── Permissions & device state ────────────────────────────────────────────

  /**
   * Explicitly request location permission (normally requested automatically
   * on `start()`). Resolves with the resulting {@link AuthorizationStatus};
   * rejects with the status when denied.
   */
  requestPermission(): Promise<AuthorizationStatus> {
    return NativeModule.requestPermission() as Promise<AuthorizationStatus>;
  }

  /**
   * Run the SDK's complete permission onboarding in the order Android and
   * Play policy require, so your app orchestrates nothing:
   *
   * 1. Foreground location (plus activity recognition and notifications)
   * 2. Prominent-disclosure dialog, then background location
   * 3. The system "Turn on location?" prompt when GPS is off
   * 4. The battery-optimization whitelist dialog
   *
   * Resolves with the resulting {@link PermissionStatus} rather than
   * rejecting — partial grants are normal and tracking degrades gracefully.
   * Set `autoRequestPermissions: true` in `ready()` to run this automatically
   * at launch instead of calling it yourself.
   */
  requestAllPermissions(): Promise<PermissionStatus> {
    if (Platform.OS !== 'android') {
      return this.requestPermission().then(() => this.getPermissionStatus());
    }
    return NativeModule.requestAllPermissions();
  }

  /**
   * Current state of every permission the SDK depends on, without prompting.
   * Check `readyForBackgroundTracking` to know whether anything is missing.
   */
  getPermissionStatus(): Promise<PermissionStatus> {
    return NativeModule.getPermissionStatus();
  }

  /**
   * Ask the user to switch on device location services (GPS) via the system's
   * in-app "Turn on location?" dialog — the same prompt ride-hailing and
   * delivery apps show, which avoids sending users into Settings.
   *
   * Called automatically by `start()`; use it directly to prompt earlier.
   * Resolves `true` when location services end up enabled. Android only;
   * resolves the current state on iOS, where no such prompt exists.
   */
  requestLocationServices(): Promise<boolean> {
    if (Platform.OS !== 'android') {
      return this.getProviderState().then((state) => state.enabled);
    }
    return NativeModule.requestLocationServices();
  }

  /** iOS 14+: request temporary full-accuracy when the user granted only approximate location. */
  requestTemporaryFullAccuracy(purpose: string): Promise<AccuracyAuthorization> {
    return NativeModule.requestTemporaryFullAccuracy(purpose) as Promise<AccuracyAuthorization>;
  }

  /** Current provider/permission state without prompting the user. */
  getProviderState(): Promise<ProviderChangeEvent> {
    return NativeModule.getProviderState() as unknown as Promise<ProviderChangeEvent>;
  }

  /** `true` while OS battery-saver mode is engaged. */
  isPowerSaveMode(): Promise<boolean> {
    return NativeModule.isPowerSaveMode();
  }

  /** Availability of motion sensors on this device. */
  getSensors(): Promise<Sensors> {
    return NativeModule.getSensors() as unknown as Promise<Sensors>;
  }

  /** Basic device metadata, including this installation's {@link getDeviceId}. */
  getDeviceInfo(): Promise<DeviceInfo> {
    return NativeModule.getDeviceInfo() as unknown as Promise<DeviceInfo>;
  }

  /**
   * A stable identifier for this app installation, generated by the SDK on
   * first run and persisted thereafter.
   *
   * Every HTTP upload automatically carries it (as `device_id`, `device_name`
   * and a `device` object at the body root), so a shared server can attribute
   * records to the device that produced them without your app doing anything.
   *
   * It identifies an *installation*, not a person: it is not derived from
   * hardware identifiers and resets if the app is reinstalled.
   */
  getDeviceId(): Promise<string> {
    return NativeModule.getDeviceId();
  }

  // ── Background tasks ──────────────────────────────────────────────────────

  /**
   * Ask the OS for extra background execution time (≈30s on iOS). Always pair
   * with {@link stopBackgroundTask}.
   */
  startBackgroundTask(): Promise<number> {
    return NativeModule.startBackgroundTask();
  }

  /** Signal completion of a task started with {@link startBackgroundTask}. */
  stopBackgroundTask(taskId: number): Promise<void> {
    return NativeModule.stopBackgroundTask(taskId);
  }

  /**
   * Android only: register a task to receive SDK events fired after the app
   * has been terminated (requires `stopOnTerminate: false, enableHeadless: true`).
   * Must be called in your app's entry file (`index.js`), outside any component.
   */
  registerHeadlessTask(task: HeadlessTask): void {
    if (Platform.OS !== 'android') {
      return;
    }
    AppRegistry.registerHeadlessTask(HEADLESS_TASK_KEY, () => async (event: HeadlessEvent) => {
      await task(event);
    });
  }

  // ── Debug utilities ───────────────────────────────────────────────────────

  /** Play an OS system sound by id (used by `debug: true` mode). */
  playSound(soundId: number): Promise<void> {
    return NativeModule.playSound(soundId);
  }

  // ── Event listeners ───────────────────────────────────────────────────────

  /** Every recorded location. */
  onLocation(
    success: (location: Location) => void,
    failure?: (errorCode: number) => void
  ): Subscription {
    const sub = EventEmitter.addListener(
      NativeEvents.LOCATION,
      (location: Location & { error?: number }) => {
        if (location.error != null) {
          failure?.(location.error);
        } else {
          success(location);
        }
      }
    );
    return { remove: () => sub.remove() };
  }

  /** Motion state transitions (moving ⇄ stationary). */
  onMotionChange(callback: (event: MotionChangeEvent) => void): Subscription {
    return subscribe(NativeEvents.MOTIONCHANGE, callback);
  }

  /** Device activity classification changes (still, walking, in_vehicle, …). */
  onActivityChange(callback: (event: MotionActivityEvent) => void): Subscription {
    return subscribe(NativeEvents.ACTIVITYCHANGE, callback);
  }

  /** Location-services / permission changes. */
  onProviderChange(callback: (event: ProviderChangeEvent) => void): Subscription {
    return subscribe(NativeEvents.PROVIDERCHANGE, callback);
  }

  /** Geofence transitions. */
  onGeofence(callback: (event: GeofenceEvent) => void): Subscription {
    return subscribe(NativeEvents.GEOFENCE, callback);
  }

  /** Changes to the actively-monitored geofence set. */
  onGeofencesChange(callback: (event: GeofencesChangeEvent) => void): Subscription {
    return subscribe(NativeEvents.GEOFENCESCHANGE, callback);
  }

  /** Periodic heartbeat while stationary (see {@link Config.heartbeatInterval}). */
  onHeartbeat(callback: (event: HeartbeatEvent) => void): Subscription {
    return subscribe(NativeEvents.HEARTBEAT, callback);
  }

  /** Result of each HTTP upload attempt. */
  onHttp(callback: (event: HttpEvent) => void): Subscription {
    return subscribe(NativeEvents.HTTP, callback);
  }

  /** Network connectivity changes. */
  onConnectivityChange(callback: (event: ConnectivityChangeEvent) => void): Subscription {
    return subscribe(NativeEvents.CONNECTIVITYCHANGE, callback);
  }

  /** OS power-save mode toggles. */
  onPowerSaveChange(callback: (event: PowerSaveChangeEvent) => void): Subscription {
    return subscribe(NativeEvents.POWERSAVECHANGE, callback);
  }

  /** SDK enabled/disabled transitions (fires on `start()` / `stop()`). */
  onEnabledChange(callback: (event: EnabledChangeEvent) => void): Subscription {
    return subscribe(NativeEvents.ENABLEDCHANGE, callback);
  }

  /** Taps on custom Android foreground-notification action buttons. */
  onNotificationAction(callback: (buttonId: string) => void): Subscription {
    return subscribe(NativeEvents.NOTIFICATIONACTION, callback);
  }

  /** Schedule window transitions (see {@link Config.schedule}). */
  onSchedule(callback: (event: ScheduleEvent) => void): Subscription {
    return this.scheduler.onSchedule(async () => callback({ state: await this.getState() }));
  }

  /** Remove every registered SDK event listener. */
  removeAllListeners(): void {
    Object.values(NativeEvents).forEach((eventName) => {
      EventEmitter.removeAllListeners(eventName);
    });
  }

  /** Alias kept for API familiarity. */
  removeListeners(): void {
    this.removeAllListeners();
  }
}

export default BackgroundGeolocationFacade;
