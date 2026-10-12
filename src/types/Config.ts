import type { Geofence } from './Geofence';

/** Desired accuracy constants (meters hint; platform-mapped). */
export enum DesiredAccuracy {
  NAVIGATION = -2,
  HIGH = -1,
  MEDIUM = 10,
  LOW = 100,
  VERY_LOW = 1000,
  LOWEST = 3000,
}

/** Verbosity of the SDK's internal persistent logger. */
export enum LogLevel {
  OFF = 0,
  ERROR = 1,
  WARNING = 2,
  INFO = 3,
  DEBUG = 4,
  VERBOSE = 5,
}

/** Location authorization status reported by `onProviderChange` / `requestPermission`. */
export enum AuthorizationStatus {
  NOT_DETERMINED = 0,
  RESTRICTED = 1,
  DENIED = 2,
  ALWAYS = 3,
  WHEN_IN_USE = 4,
}

/** Location accuracy authorization (iOS 14+ "Precise Location" toggle). */
export enum AccuracyAuthorization {
  FULL = 0,
  REDUCED = 1,
}

/** iOS `CLLocationManager.activityType` hint. */
export enum ActivityType {
  OTHER = 1,
  AUTOMOTIVE_NAVIGATION = 2,
  FITNESS = 3,
  OTHER_NAVIGATION = 4,
  AIRBORNE = 5,
}

/** Controls which categories of events the SDK persists to its database. */
export enum PersistMode {
  ALL = 2,
  LOCATION = 1,
  GEOFENCE = -1,
  NONE = 0,
}

/** Android notification priority for the tracking foreground-service notification. */
export enum NotificationPriority {
  DEFAULT = 0,
  HIGH = 1,
  LOW = -1,
  MAX = 2,
  MIN = -2,
}

/** Tracking modes reported in {@link State.trackingMode}. */
export enum TrackingMode {
  /** Geofences-only mode, entered via `startGeofences()`. */
  GEOFENCE = 0,
  /** Full location + geofence tracking, entered via `start()`. */
  LOCATION = 1,
}

/**
 * Android foreground-service notification customization.
 */
export interface Notification {
  /** Title line. Default: app name. */
  title?: string;
  /** Body line. Default: "Location service activated". */
  text?: string;
  /** Notification accent color, e.g. `#FF0000`. */
  color?: string;
  /** Drawable resource name for the small icon, e.g. `mipmap/ic_launcher`. */
  smallIcon?: string;
  /** Drawable resource name for the large icon. */
  largeIcon?: string;
  /** Notification channel name shown in system settings. Default: "Location Service". */
  channelName?: string;
  /**
   * Stable channel id. Default `"open_bg_geo_silent"`. The default channel is
   * created silent (no sound, vibration, lights or badge) — note that Android
   * channels are immutable once created, so supplying a custom id that already
   * exists keeps whatever settings it was first created with.
   */
  channelId?: string;
  /**
   * Priority, mapped onto the Android channel importance. Default `LOW`.
   *
   * Use `MIN` for the least intrusive notification Android permits: silent,
   * and the persistent **status-bar icon is hidden** on most devices (it
   * remains visible in the notification shade).
   *
   * The notification can never be removed entirely — Android requires a
   * visible notification for any foreground service, which is how users know
   * background location is active. There is no supported way around this, and
   * attempting one gets the service killed.
   */
  priority?: NotificationPriority;
  /** Keep the notification non-dismissable while tracking. Default `true`. */
  sticky?: boolean;
}

/**
 * Bearer/JWT authorization with automatic refresh. See {@link Config.authorization}.
 */
export interface Authorization {
  /** `JWT` sends `Authorization: Bearer <token>`; any other value is used as the scheme verbatim. */
  strategy?: 'JWT' | string;
  /** Current access token. */
  accessToken: string;
  /** Refresh token exchanged at {@link refreshUrl} when the server returns 401. */
  refreshToken?: string;
  /** Endpoint that issues a new access token. */
  refreshUrl?: string;
  /** Body sent to {@link refreshUrl}; `"{refreshToken}"` is substituted. Default `{ refresh_token }`. */
  refreshPayload?: Record<string, string>;
  /** Extra headers for the refresh request. */
  refreshHeaders?: Record<string, string>;
  /** Expiry as a UNIX timestamp in seconds, if your server returns one. */
  expires?: number;
}

/**
 * Android Settings-redirect dialog text shown before requesting
 * "Allow all the time" background permission (Android 11+).
 */
export interface PermissionRationale {
  title?: string;
  message?: string;
  positiveAction?: string;
  negativeAction?: string;
}

/**
 * The full SDK configuration. Every field is optional; the SDK applies
 * sensible defaults and persists the merged result across app restarts —
 * configure once in `ready()` and the SDK remembers.
 */
export interface Config {
  // ── Geolocation ────────────────────────────────────────────────────────────
  /** Desired accuracy. Use the {@link DesiredAccuracy} constants. Default `HIGH`. */
  desiredAccuracy?: DesiredAccuracy | number;
  /** Minimum distance in meters a device must move before a new location is recorded. Default `10`. */
  distanceFilter?: number;
  /** Radius in meters of the stationary zone; exiting it triggers `motionchange → moving`. Minimum/default `25`. */
  stationaryRadius?: number;
  /** Dynamically scale `distanceFilter` with speed to save battery at highway speeds. Default `false` (elasticity ON when `true`). */
  disableElasticity?: boolean;
  /** Multiplier applied by the elasticity algorithm. Default `1`. */
  elasticityMultiplier?: number;
  /** Stop tracking automatically after this many minutes from `start()`. Default `0` (disabled). */
  stopAfterElapsedMinutes?: number;
  /** Only accumulate odometer when accuracy <= this many meters. Default `100`. */
  desiredOdometerAccuracy?: number;
  /** iOS: use significant-location-changes only (no continuous GPS). Default `false`. */
  useSignificantChangesOnly?: boolean;
  /** Which permission to request: `Always`, `WhenInUse` or `Any`. Default `Always`. */
  locationAuthorizationRequest?: 'Always' | 'WhenInUse' | 'Any';
  /**
   * When `true`, `ready()` runs the SDK's complete permission onboarding —
   * foreground location, prominent disclosure + background location, the
   * system "Turn on location?" prompt, and the battery-optimization whitelist
   * — so your app implements none of it. Default `false`.
   */
  autoRequestPermissions?: boolean;
  /** iOS: show the blue background-location status-bar indicator. Default `false`. */
  showsBackgroundLocationIndicator?: boolean;
  /** iOS: `CLLocationManager.activityType` hint. Default `OTHER`. */
  activityType?: ActivityType;
  /** iOS: let iOS auto-pause location updates when stationary. Default `false`. */
  pausesLocationUpdatesAutomatically?: boolean;
  /** Android: minimum interval in ms between location updates. Default `1000`. */
  locationUpdateInterval?: number;
  /** Android: fastest interval in ms your app can handle updates. Default `10000`. */
  fastestLocationUpdateInterval?: number;
  /** Android: discard locations with identical coordinates. Default `false` (i.e. identical allowed when `true`). */
  allowIdenticalLocations?: boolean;
  /** Android: discard fixes implying an impossible speed jump above this m/s. Default `300`. */
  speedJumpFilter?: number;
  /**
   * Discard fixes whose reported accuracy is worse than this many meters, so
   * cold-start and indoor noise never reaches your database or server.
   * Default `0` (keep everything). Typical production value: `25`–`50`.
   */
  minimumAccuracy?: number;

  // ── Motion / activity recognition ─────────────────────────────────────────
  /** `true` to begin tracking immediately in the *moving* state. Default `false`. */
  isMoving?: boolean;
  /** Minutes of detected stillness before switching to the *stationary* state. Default `5`. */
  stopTimeout?: number;
  /** Sampling interval in ms for activity-recognition updates (Android). Default `10000`. */
  activityRecognitionInterval?: number;
  /** Minimum activity confidence 0…100 to honor a motion-state transition. Default `70`. */
  minimumActivityRecognitionConfidence?: number;
  /** Disable the automatic stop-detection state machine; tracking runs continuously. Default `false`. */
  disableStopDetection?: boolean;
  /** iOS: disable `CMMotionActivityManager` updates (skips the Motion & Fitness permission). Default `false`. */
  disableMotionActivityUpdates?: boolean;
  /** Delay in minutes after motion is detected before high-power tracking engages. Default `0`. */
  motionTriggerDelay?: number;

  // ── HTTP & persistence ────────────────────────────────────────────────────
  /** Your server endpoint. When set, recorded locations are uploaded automatically. */
  url?: string;
  /** HTTP method used for uploads. Default `POST`. */
  method?: 'POST' | 'PUT' | 'OPTIONS';
  /** Root property of the JSON request body in which location(s) are placed. Default `"location"`. Use `"."` to place at root. */
  httpRootProperty?: string;
  /** Extra key/values merged into the request body root. */
  params?: Record<string, unknown>;
  /** HTTP headers sent with each upload. */
  headers?: Record<string, string>;
  /**
   * The shape of one uploaded record: a JSON object written as text, with
   * `<%= name %>` tags replaced by that value of the record.
   *
   * ```ts
   * httpRootProperty: '.',
   * locationTemplate: '{"lat":<%= latitude %>,"lng":<%= longitude %>,"at":"<%= timestamp %>"}',
   * ```
   *
   * Tags: `latitude`, `longitude`, `accuracy`, `speed`, `heading`,
   * `altitude`, `timestamp`, `uuid`, `event`, `is_moving`, `odometer`, or a
   * dotted path such as `battery.level`. Text is not quoted for you. With a
   * template the request body is that object and `params`: no device identity
   * or network report is added, so it suits a server that refuses fields it
   * does not know.
   */
  locationTemplate?: string;
  /**
   * Bearer/JWT authorization with automatic token refresh.
   *
   * The SDK sends `Authorization: Bearer <accessToken>` on every upload. When
   * the server replies `401`, it POSTs to `refreshUrl`, persists the new token
   * and replays the request — so a queue never stalls on an expired token.
   *
   * ```ts
   * authorization: {
   *   strategy: 'JWT',
   *   accessToken: '…',
   *   refreshToken: '…',
   *   refreshUrl: 'https://api.example.com/auth/refresh',
   *   refreshPayload: { refresh_token: '{refreshToken}' },
   * }
   * ```
   *
   * Any `refreshPayload` value equal to `"{refreshToken}"` is replaced with
   * the current refresh token. The response may name the new token
   * `access_token`, `accessToken` or `token`.
   */
  authorization?: Authorization;
  /** Arbitrary key/values merged into every recorded location's `extras`. */
  extras?: Record<string, unknown>;
  /** Upload each location as it is recorded. Default `true`. */
  autoSync?: boolean;
  /** Wait until this many records accumulate before auto-syncing. Default `0`. */
  autoSyncThreshold?: number;
  /**
   * Least time between two automatic uploads, in ms. Default `0` (every
   * record is uploaded as it is written). With fixes taken more often than
   * this, the upload that goes carries the newest one. A change of motion is
   * uploaded regardless.
   */
  autoSyncInterval?: number;
  /** Upload all pending records in a single request (JSON array). Default `false`. */
  batchSync?: boolean;
  /** Cap the number of records per batch request. Default `-1` (unlimited). */
  maxBatchSize?: number;
  /** Days to retain unsynced records in the database. Default `14`. */
  maxDaysToPersist?: number;
  /** Max records retained in the database; oldest are pruned. Default `-1` (unlimited). */
  maxRecordsToPersist?: number;
  /** Which events are persisted; see {@link PersistMode}. Default `ALL`. */
  persistMode?: PersistMode;
  /** HTTP request timeout in ms. Default `60000`. */
  httpTimeout?: number;
  /** Order in which records are uploaded: `ASC` (oldest first) or `DESC`. Default `ASC`. */
  locationsOrderDirection?: 'ASC' | 'DESC';

  // ── Application behaviour ─────────────────────────────────────────────────
  /** Stop tracking when the user terminates the app. Default `true`. Set `false` for always-on tracking. */
  stopOnTerminate?: boolean;
  /** Resume tracking after device reboot (requires `stopOnTerminate: false`). Default `false`. */
  startOnBoot?: boolean;
  /** Fire an `onHeartbeat` event every N seconds while stationary. Default `0` (disabled); minimum `60`. */
  heartbeatInterval?: number;
  /**
   * Seconds between fresh location fixes recorded while the device is
   * *stationary*. Default `0` — no updates at all while still, which is the
   * battery-optimal behaviour.
   *
   * Set this (e.g. `12`) when your server needs a periodic "still here"
   * position rather than silence. Unlike {@link heartbeatInterval}, which only
   * emits an event carrying the last known location, these are real fixes:
   * recorded, persisted, uploaded and delivered to `onLocation` with
   * `event: "heartbeat"`. Minimum `5` seconds; costs a GPS wake-up per update.
   */
  stationaryUpdateInterval?: number;
  /** iOS: keep the app alive while stationary to allow `heartbeat` events. Battery-hungry. Default `false`. */
  preventSuspend?: boolean;
  /** Android: run your registered headless-task for events fired after app terminate. Default `false`. */
  enableHeadless?: boolean;
  /** Android foreground-service notification customization. */
  notification?: Notification;
  /** Android 11+ rationale dialog shown before redirecting to Settings for "Allow all the time". */
  backgroundPermissionRationale?: PermissionRationale;
  /** Automatically `stop()` tracking when location permission is revoked. Default `false`. */
  stopOnStationary?: boolean;

  // ── Geofencing ────────────────────────────────────────────────────────────
  /** Radius in meters around the device within which geofences are actively monitored. Default `1000`. */
  geofenceProximityRadius?: number;
  /** Fire ENTER immediately when a geofence is added while the device is already inside it. Default `true`. */
  geofenceInitialTriggerEntry?: boolean;
  /** Android: use high-accuracy continuous location while in geofences-only mode. Default `false`. */
  geofenceModeHighAccuracy?: boolean;

  // ── Logging & debug ───────────────────────────────────────────────────────
  /**
   * Play a short tone on SDK **life-cycle** events — start, stop, motion
   * changes and geofence transitions. Individual locations are deliberately
   * silent: at realtime rates that would be a tone every second.
   *
   * A development aid — leave `false` (the default) in anything a user runs.
   */
  debug?: boolean;
  /** Verbosity of the persistent log; see {@link LogLevel}. Default `OFF`. */
  logLevel?: LogLevel;
  /** Days of log retention. Default `3`. */
  logMaxDays?: number;

  /**
   * Schedule tracking windows, evaluated while the app runs.
   * Format: `"{DAYS} {START}-{END}"` where DAYS is `1-7` (1 = Sunday) e.g.
   * `["2-6 09:00-17:00", "7 10:00-14:00"]`.
   */
  schedule?: string[];
}

/**
 * The SDK's runtime state: the persisted {@link Config} plus live status flags.
 * Returned by `ready()`, `getState()`, `start()`, `stop()` and `setConfig()`.
 */
export interface State extends Config {
  /** `true` when the tracking service is started (via `start()` or `startGeofences()`). */
  enabled: boolean;
  /** Which tracking mode is active; see {@link TrackingMode}. */
  trackingMode: TrackingMode;
  /** `true` when a `schedule` has been engaged via `startSchedule()`. */
  schedulerEnabled: boolean;
  /** Current odometer reading in meters. */
  odometer: number;
  /** `true` if the SDK believes the device is currently moving. */
  isMoving?: boolean;
  /** `true` when the app was launched in the background by the OS (e.g. geofence wake). */
  didLaunchInBackground?: boolean;
  /** `true` when the device rebooted and tracking was resumed by `startOnBoot`. */
  didDeviceReboot?: boolean;
  /** Geofences currently registered with the SDK. */
  geofences?: Geofence[];
}

/**
 * Payload delivered to `onProviderChange` whenever device location-services
 * state or app permission changes.
 */
export interface ProviderChangeEvent {
  /** `true` when device location-services are enabled and permission allows tracking. */
  enabled: boolean;
  /** Permission status; see {@link AuthorizationStatus}. */
  status: AuthorizationStatus;
  /** `true` when network-based location is available. */
  network: boolean;
  /** `true` when GPS-based location is available. */
  gps: boolean;
  /** iOS 14+ precise-location toggle; see {@link AccuracyAuthorization}. */
  accuracyAuthorization: AccuracyAuthorization;
}

/** Payload delivered to `onHttp` after each upload attempt. */
export interface HttpEvent {
  /** `true` when the server responded 2xx. */
  success: boolean;
  /** HTTP status code (`0` when the request failed to send). */
  status: number;
  /** Raw response body text. */
  responseText: string;
}

/** Payload delivered to `onMotionChange`. */
export interface MotionChangeEvent {
  /** The new motion state. */
  isMoving: boolean;
  /** The location recorded at the moment of the transition. */
  location: import('./Location').Location;
}

/** Payload delivered to `onActivityChange`. */
export interface MotionActivityEvent {
  /** The detected activity type. */
  activity: import('./Location').MotionActivityType;
  /** Confidence 0…100. */
  confidence: number;
}

/** Payload delivered to `onHeartbeat`. */
export interface HeartbeatEvent {
  /** The last known location (not a fresh fix). */
  location: import('./Location').Location;
}

/** Payload delivered to `onConnectivityChange`. */
export interface ConnectivityChangeEvent {
  /** `true` when the device has a network connection. */
  connected: boolean;
  /**
   * Length of the outage that just ended, in milliseconds. `0` when the event
   * reports going *offline*, or when connectivity was never lost.
   */
  offlineDurationMs: number;
  /** Locations waiting in the SQLite queue at the moment of the transition. */
  queuedRecords: number;
}

/**
 * Connectivity and upload health, from `getSyncStatus()`. The same document
 * is attached to every HTTP upload under the `network` key.
 */
export interface SyncStatus {
  /** Whether the device currently has a network connection. */
  connected: boolean;
  /** Duration of the outage in progress, in ms. `0` while connected. */
  offlineDurationMs: number;
  /** Duration of the most recently completed outage, in ms. */
  lastOfflineDurationMs: number;
  /** Cumulative offline time since the app process started, in ms. */
  totalOfflineMs: number;
  /** Number of outages since the process started. */
  outageCount: number;
  /** Locations persisted in SQLite awaiting upload. */
  queuedRecords: number;
  /** Round-trip time of the last successful upload, in ms. */
  lastLatencyMs: number;
  /** Rolling mean upload latency, in ms. */
  averageLatencyMs: number;
  /** `true` when uploads succeed but are slow — a degraded link, not a dead one. */
  networkSlow: boolean;
  /** Epoch ms of the last successful upload; `0` if none yet. */
  lastSuccessfulSyncAt: number;
  /** Consecutive upload failures; resets on success. */
  consecutiveFailures: number;
  /** Reason for the most recent failure, when there is one. */
  lastFailureReason?: string;
}

/** Payload delivered to `onPowerSaveChange`: the new power-save state. */
export type PowerSaveChangeEvent = boolean;

/** Payload delivered to `onEnabledChange`: the new `enabled` state. */
export type EnabledChangeEvent = boolean;

/** Payload delivered to `onSchedule` when a schedule window starts/stops tracking. */
export interface ScheduleEvent {
  /** The SDK state at the moment the schedule fired. */
  state: State;
}

/** Basic device metadata returned by `getDeviceInfo()`. */
export interface DeviceInfo {
  model: string;
  manufacturer: string;
  version: string;
  platform: 'ios' | 'android';
  framework: 'react-native';
  /** Stable per-installation identifier; see `BackgroundGeolocation.getDeviceId()`. */
  deviceId: string;
}

/** Device sensor availability returned by `getSensors()`. */
export interface Sensors {
  platform: 'ios' | 'android';
  accelerometer: boolean;
  gyroscope: boolean;
  magnetometer: boolean;
  /** Android: significant-motion sensor availability. */
  significant_motion?: boolean;
  /** iOS: whether CMMotionActivityManager is available. */
  motion_hardware?: boolean;
}

/**
 * Everything the SDK needs in order to track reliably in the background,
 * returned by `requestAllPermissions()` and `getPermissionStatus()`.
 */
export interface PermissionStatus {
  /** Overall authorization; see {@link AuthorizationStatus}. */
  status: AuthorizationStatus;
  /** Foreground location permission granted. */
  location: boolean;
  /** "Allow all the time" granted (always `true` below Android 10). */
  backgroundLocation: boolean;
  /** Physical-activity permission granted; powers motion detection. */
  activityRecognition: boolean;
  /** Notification permission granted (required for the Android tracking notification). */
  notifications: boolean;
  /** Device location services (GPS) are switched on. */
  locationServicesEnabled: boolean;
  /** App is exempt from battery optimization — essential on Samsung/Xiaomi/Oppo. */
  batteryOptimizationDisabled: boolean;
  /** `true` only when every prerequisite above is satisfied. */
  readyForBackgroundTracking: boolean;
}

/** A subscription returned by every `onXXX` event-listener method. */
export interface Subscription {
  /** Detach this listener. */
  remove: () => void;
}
