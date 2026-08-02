/** A polygon vertex, as `[latitude, longitude]` or an object. */
export type GeofenceVertex =
  | [number, number]
  | { latitude: number; longitude: number };

/**
 * A geofence, monitored natively by the OS even while your app is terminated
 * (subject to platform limits — see the Geofencing guide).
 *
 * Supply **either** a circle (`latitude` + `longitude` + `radius`) **or** a
 * polygon (`vertices`), never both.
 *
 * ```ts
 * // Circular
 * { identifier: 'depot', latitude: 12.97, longitude: 77.59, radius: 200 }
 *
 * // Polygon — an arbitrary shape such as a park or delivery zone
 * { identifier: 'park', vertices: [[12.97, 77.59], [12.98, 77.60], [12.97, 77.61]] }
 * ```
 */
export interface Geofence {
  /** Unique identifier. Adding a geofence with an existing identifier replaces it. */
  identifier: string;
  /** Circle radius in meters. Platforms are most reliable at >= 150m; minimum enforced is 100m. */
  radius?: number;
  /** Latitude of the circle center in decimal degrees. */
  latitude?: number;
  /** Longitude of the circle center in decimal degrees. */
  longitude?: number;
  /**
   * Three or more vertices defining an arbitrary polygon — parks, building
   * footprints, delivery zones.
   *
   * Neither Android nor iOS can monitor polygons natively, so the SDK
   * registers the polygon's bounding circle with the OS (preserving native
   * wake-ups even when your app is terminated) and then decides exact
   * containment itself with a point-in-polygon test. Transitions therefore
   * follow the true shape, not the circle.
   */
  vertices?: GeofenceVertex[];
  /** Fire the geofence when the device enters the circle. Default `true`. */
  notifyOnEntry?: boolean;
  /** Fire the geofence when the device exits the circle. Default `false`. */
  notifyOnExit?: boolean;
  /** Fire after the device dwells inside the circle for {@link loiteringDelay} ms (Android only). Default `false`. */
  notifyOnDwell?: boolean;
  /** Milliseconds the device must remain inside before a DWELL fires (Android only). Default `0`. */
  loiteringDelay?: number;
  /** Arbitrary key/values echoed back on every geofence event and attached to the recorded location. */
  extras?: Record<string, unknown>;
}

/**
 * Payload delivered to `onGeofence` when a monitored geofence transition fires.
 */
export interface GeofenceEvent {
  /** The `identifier` of the geofence which fired. */
  identifier: string;
  /** The transition which occurred. */
  action: 'ENTER' | 'EXIT' | 'DWELL';
  /** The location recorded at the moment of the transition. */
  location: import('./Location').Location;
  /** The `extras` attached to the geofence when it was added. */
  extras?: Record<string, unknown>;
}

/**
 * Payload delivered to `onGeofencesChange` whenever the SDK starts / stops
 * actively monitoring geofences (relevant when you register more geofences
 * than the platform natively supports and the SDK swaps the nearest ones in).
 */
export interface GeofencesChangeEvent {
  /** Identifiers of geofences which began active monitoring. */
  on: Geofence[];
  /** Identifiers of geofences whose active monitoring stopped. */
  off: string[];
}
