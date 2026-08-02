/**
 * GPS coordinate data attached to every recorded {@link Location}.
 */
export interface Coords {
  /** Latitude in decimal degrees. */
  latitude: number;
  /** Longitude in decimal degrees. */
  longitude: number;
  /** Estimated horizontal accuracy radius in meters. */
  accuracy: number;
  /** Speed in meters / second. `-1` when unavailable. */
  speed: number;
  /** Estimated accuracy of {@link speed} in meters / second. `-1` when unavailable. */
  speed_accuracy: number;
  /** Compass heading in degrees (0…360). `-1` when unavailable. */
  heading: number;
  /** Estimated accuracy of {@link heading} in degrees. `-1` when unavailable. */
  heading_accuracy: number;
  /** Altitude above sea level in meters. `-1` when unavailable. */
  altitude: number;
  /** Estimated vertical accuracy in meters. `-1` when unavailable. */
  altitude_accuracy: number;
  /** Raw altitude above the WGS84 reference ellipsoid, when the platform reports it. */
  ellipsoidal_altitude?: number;
}

/**
 * Device motion-activity estimate attached to every recorded {@link Location}.
 */
export interface MotionActivity {
  /** One of `still | on_foot | walking | running | on_bicycle | in_vehicle | unknown`. */
  type: MotionActivityType;
  /** Confidence of the activity estimate, 0…100. */
  confidence: number;
}

export type MotionActivityType =
  | 'still'
  | 'on_foot'
  | 'walking'
  | 'running'
  | 'on_bicycle'
  | 'in_vehicle'
  | 'unknown';

/**
 * Battery snapshot attached to every recorded {@link Location}.
 */
export interface Battery {
  /** `true` while the device is plugged in and charging. */
  is_charging: boolean;
  /** Battery charge 0.0…1.0. `-1` when unavailable. */
  level: number;
}

/**
 * Describes which geofence transition produced a {@link Location} whose
 * `event` is `"geofence"`.
 */
export interface GeofenceEventDetail {
  /** The `identifier` of the geofence which fired. */
  identifier: string;
  /** The transition which occurred: `ENTER | EXIT | DWELL`. */
  action: 'ENTER' | 'EXIT' | 'DWELL';
  /** Arbitrary `extras` that were attached to the geofence when it was added. */
  extras?: Record<string, unknown>;
}

/**
 * A recorded location. This is the payload delivered to `onLocation`,
 * persisted to the SDK's SQLite database and uploaded to your {@link Config.url}.
 */
export interface Location {
  /** ISO-8601 UTC timestamp of when the fix was recorded, e.g. `2026-07-31T12:00:00.000Z`. */
  timestamp: string;
  /** Age of the fix, in milliseconds, at the moment it was delivered to the SDK. */
  age: number;
  /** What produced this location: `motionchange | geofence | heartbeat | providerchange` or `undefined` for ordinary tracking samples. */
  event?: 'motionchange' | 'geofence' | 'heartbeat' | 'providerchange';
  /** `true` when the SDK's motion state-machine considers the device to be moving. */
  is_moving: boolean;
  /** Universally unique identifier of this record in the SDK database. */
  uuid: string;
  /** The GPS coordinate data. */
  coords: Coords;
  /** Motion-activity estimate at the time of recording. */
  activity: MotionActivity;
  /** Battery snapshot at the time of recording. */
  battery: Battery;
  /** Distance traveled, in meters, since {@link BackgroundGeolocation.resetOdometer} was last called. */
  odometer: number;
  /** Present only when `event` is `"geofence"`. */
  geofence?: GeofenceEventDetail;
  /** `true` when the location was produced by a mock-location app (Android). */
  mock?: boolean;
  /** `true` for one-shot samples requested internally by the SDK (never persisted). */
  sample?: boolean;
  /** Arbitrary key/values merged from {@link Config.extras}. */
  extras?: Record<string, unknown>;
}

/**
 * Options accepted by {@link BackgroundGeolocation.getCurrentPosition}.
 */
export interface CurrentPositionRequest {
  /** Maximum time in milliseconds to wait for a fix. Default `30000`. */
  timeout?: number;
  /** Accept the last-known location if it is no older than this, in milliseconds. Default `0` (always fetch fresh). */
  maximumAge?: number;
  /** Keep sampling until a fix with accuracy <= this many meters arrives (or `timeout`). Default `0` (first fix wins). */
  desiredAccuracy?: number;
  /** Number of samples to collect before resolving with the most accurate. Default `3`. */
  samples?: number;
  /** When `true`, the resolved location is also persisted to the database and posted to {@link Config.url}. Default `true`. */
  persist?: boolean;
  /** Arbitrary key/values merged onto the resolved location's `extras`. */
  extras?: Record<string, unknown>;
}

/**
 * Options accepted by {@link BackgroundGeolocation.watchPosition}.
 */
export interface WatchPositionRequest {
  /** Interval between samples in milliseconds. Default `1000`. */
  interval?: number;
  /** Desired accuracy in meters. Default: highest available. */
  desiredAccuracy?: number;
  /** When `true`, each watched location is also persisted. Default `false`. */
  persist?: boolean;
  /** Arbitrary key/values merged onto each location's `extras`. */
  extras?: Record<string, unknown>;
}

/**
 * Error codes rejected by location-fetching methods, mirroring the familiar
 * web Geolocation error contract.
 */
export enum LocationError {
  /** Location permission denied by the user. */
  PERMISSION_DENIED = 1,
  /** Location services unavailable (e.g. airplane-mode, GPS disabled). */
  POSITION_UNAVAILABLE = 2,
  /** No fix arrived within the requested `timeout`. */
  TIMEOUT = 3,
}
