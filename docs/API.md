# API Reference

```ts
import BackgroundGeolocation, {
  Logger,
  DeviceSettings,
} from '@evitras-technologies/react-native-background-geolocation';
```

`BackgroundGeolocation` is a singleton. Every method returns a Promise unless
stated otherwise.

- [Lifecycle](#lifecycle)
- [Geolocation](#geolocation)
- [Database & HTTP](#database--http)
- [Geofencing](#geofencing)
- [Permissions](#permissions)
- [Device state](#device-state)
- [Events](#events)
- [Logger](#logger)
- [DeviceSettings](#devicesettings)
- [Types](#types)

---

## Lifecycle

### `ready(config?): Promise<State>`
Apply and persist configuration. **Call on every app launch, after registering
event listeners.** Config is remembered across restarts, so options only need
to be passed once — though passing them every launch is harmless and clearer.

With `autoRequestPermissions: true` it also runs the full permission
onboarding before resolving.

### `start(): Promise<State>`
Engage location + geofence tracking. Requests location permission and prompts
to enable GPS if needed. Resolves with the new state.

### `stop(): Promise<State>`
Halt all tracking, alarms, sensors and the foreground service.

### `startGeofences(): Promise<State>`
Geofences-only mode: no continuous location tracking, but geofences still fire.
Much cheaper on battery than full tracking.

### `changePace(isMoving): Promise<void>`
Manually force the motion state. `changePace(true)` engages tracking
immediately without waiting for motion detection.

### `setConfig(config): Promise<State>`
Merge new options at runtime. Live-tunable settings (accuracy, distance filter,
intervals, stationary updates) apply immediately.

### `getState(): Promise<State>` · `reset(config?): Promise<State>`
Read current state; or discard persisted config and re-apply defaults merged
with `config`.

### `startSchedule()` / `stopSchedule(): Promise<State>`
Engage or disengage `Config.schedule` windows. Evaluated by a JS timer, so
honored while your app process is alive.

---

## Geolocation

### `getCurrentPosition(options?): Promise<Location>`
One-shot position. Requests permission and prompts for GPS automatically.

```ts
const location = await BackgroundGeolocation.getCurrentPosition({
  timeout: 30000,        // ms to wait for a fix
  maximumAge: 0,         // accept a cached fix younger than this (ms)
  samples: 3,            // collect N fixes, return the most accurate
  desiredAccuracy: 10,   // resolve early once accuracy ≤ this (metres)
  persist: true,         // also store + upload it
  extras: { trigger: 'manual' },
});
```

Rejects with a message such as `"Location permission denied"` or
`"Timed out waiting for a location fix"`.

### `watchPosition(onLocation, onError?, options?): Promise<Subscription>`
Raw position stream, independent of the tracking state machine.

```ts
const sub = await BackgroundGeolocation.watchPosition(
  (location) => console.log(location),
  (errorCode) => console.warn(errorCode),
  { interval: 1000, persist: false }
);
sub.remove();
```

### `stopWatchPosition(): Promise<void>`

### `getOdometer(): Promise<number>` · `setOdometer(v): Promise<Location>` · `resetOdometer(): Promise<Location>`
Trip distance in metres. Only fixes with accuracy better than
`desiredOdometerAccuracy` contribute.

---

## Database & HTTP

### `getLocations(): Promise<Location[]>`
All records currently queued for upload.

### `getCount(): Promise<number>`
Queue size — the quickest health check that recording works.

### `sync(): Promise<Location[]>`
Upload everything pending now, bypassing retry backoff. Resolves with the
records that were synced. Rejects if no `url` is configured.

### `destroyLocations(): Promise<void>` · `destroyLocation(uuid): Promise<void>`
### `insertLocation(location): Promise<string>`
Manually insert a record; resolves with its `uuid`.

---

## Geofencing

On-device geofences fire even when the app is terminated, and need no network.

```ts
await BackgroundGeolocation.addGeofence({
  identifier: 'warehouse',
  latitude: 12.9716,
  longitude: 77.5946,
  radius: 200,              // metres; ≥150 recommended for reliability
  notifyOnEntry: true,
  notifyOnExit: true,
  notifyOnDwell: false,
  loiteringDelay: 30000,    // ms before DWELL fires (Android)
  extras: { siteId: 42 },
});
```

| Method | Description |
|---|---|
| `addGeofence(geofence)` | Add or replace one |
| `addGeofences(geofences[])` | Add or replace many |
| `removeGeofence(identifier)` / `removeGeofences()` | Remove |
| `getGeofences()` / `getGeofence(id)` / `geofenceExists(id)` | Query |

Geofences persist across reboots. You may register more than the OS limit
(Android 100, iOS 20) — the SDK keeps the nearest ones within
`geofenceProximityRadius` active and swaps as the device moves, emitting
`onGeofencesChange`.

---

## Permissions

### `requestAllPermissions(): Promise<PermissionStatus>`
The complete onboarding sequence in the order Android and Play policy require:
foreground location (plus activity recognition and notifications) → prominent
disclosure dialog → background location → "Turn on location?" GPS prompt →
battery-optimization whitelist.

Resolves with the resulting status rather than rejecting — partial grants are
normal and tracking degrades gracefully.

Set `autoRequestPermissions: true` in `ready()` to run this automatically at
launch instead.

### `getPermissionStatus(): Promise<PermissionStatus>`
Read state without prompting:

```ts
{
  status: AuthorizationStatus,
  location: boolean,
  backgroundLocation: boolean,
  activityRecognition: boolean,
  notifications: boolean,
  locationServicesEnabled: boolean,
  batteryOptimizationDisabled: boolean,
  readyForBackgroundTracking: boolean,  // true only when nothing is missing
}
```

### `requestPermission(): Promise<AuthorizationStatus>`
Location permission only. Rejects when denied.

### `requestLocationServices(): Promise<boolean>`
Show the system "Turn on location?" dialog when GPS is off (Android). On iOS,
reports current state — the platform has no such prompt.

### `requestTemporaryFullAccuracy(purpose): Promise<AccuracyAuthorization>`
iOS 14+ only, when the user granted approximate location.

### `getProviderState(): Promise<ProviderChangeEvent>`

---

## Device state

| Method | Returns |
|---|---|
| `getDeviceId()` | Stable per-installation id, attached to every upload |
| `getDeviceInfo()` | model, manufacturer, version, platform, framework, deviceId |
| `getSensors()` | Availability of accelerometer, gyroscope, magnetometer, … |
| `isPowerSaveMode()` | OS battery-saver state |
| `startBackgroundTask()` / `stopBackgroundTask(id)` | Extra background execution time (iOS ~30 s) |
| `playSound(soundId)` | Play a system sound (debug aid) |
| `registerHeadlessTask(fn)` | Android: receive events after app terminate |

---

## Events

Every listener returns a `Subscription` with `.remove()`. **Register before
`ready()`.**

| Event | Payload | Fires when |
|---|---|---|
| `onLocation(success, failure?)` | `Location` | Any location is recorded |
| `onMotionChange(cb)` | `{ isMoving, location }` | Moving ⇄ stationary transition |
| `onActivityChange(cb)` | `{ activity, confidence }` | Detected activity changes |
| `onProviderChange(cb)` | `ProviderChangeEvent` | Permission or GPS state changes |
| `onGeofence(cb)` | `{ identifier, action, location, extras }` | Geofence ENTER/EXIT/DWELL |
| `onGeofencesChange(cb)` | `{ on, off }` | Actively-monitored geofence set changes |
| `onHeartbeat(cb)` | `{ location }` | Every `heartbeatInterval` while stationary |
| `onHttp(cb)` | `{ success, status, responseText }` | Each upload attempt completes |
| `onConnectivityChange(cb)` | `{ connected }` | Network comes or goes |
| `onPowerSaveChange(cb)` | `boolean` | Battery-saver toggles |
| `onEnabledChange(cb)` | `boolean` | Tracking starts or stops |
| `onNotificationAction(cb)` | `string` | Android notification button tapped |
| `onSchedule(cb)` | `{ state }` | A schedule window opens or closes |

`removeAllListeners()` detaches everything.

**Note on `is_moving`:** this field means *"tracking is in high-power mode"*,
not *"the device is physically moving"* — with `disableStopDetection: true` it
stays `true` permanently. For physical motion, use `activity.type` and
`coords.speed`.

---

## Logger

Persistent log stored in the SDK database, pruned after `logMaxDays`.

```ts
import { Logger } from '@evitras-technologies/react-native-background-geolocation';

Logger.info('Trip started');
const text = await Logger.getLog();
await Logger.emailLog('support@example.com');
await Logger.destroyLog();
```

Also `Logger.error/warn/debug/notice`.

---

## DeviceSettings

Android helpers for the vendor settings that decide whether background
tracking survives. No-ops on iOS.

```ts
import { DeviceSettings } from '@evitras-technologies/react-native-background-geolocation';

await DeviceSettings.isIgnoringBatteryOptimizations();  // boolean
await DeviceSettings.showIgnoreBatteryOptimizations();  // system "Allow?" dialog
await DeviceSettings.showPowerManager();                // OEM auto-start screen
await DeviceSettings.show();                            // app settings page
```

`showPowerManager()` knows the vendor screens for Xiaomi/Redmi/Poco, Vivo/iQOO,
Oppo/Realme, Samsung, Transsion (Tecno/Infinix/itel), OnePlus, Huawei/Honor,
Asus and Meizu; it rejects when the device has none.

---

## Types

### `Location`

```ts
{
  timestamp: string;        // ISO-8601 UTC
  age: number;              // ms since the fix was taken
  event?: 'motionchange' | 'geofence' | 'heartbeat' | 'providerchange';
  is_moving: boolean;       // tracking power state (see note above)
  uuid: string;
  coords: {
    latitude, longitude, accuracy,
    speed, speed_accuracy,
    heading, heading_accuracy,
    altitude, altitude_accuracy, ellipsoidal_altitude?
  };
  activity: { type: MotionActivityType; confidence: number };
  battery: { is_charging: boolean; level: number };  // level 0..1
  odometer: number;         // metres
  geofence?: { identifier, action, extras? };
  mock?: boolean;           // produced by a mock-location app
  extras?: Record<string, unknown>;
}
```

`MotionActivityType`: `still | on_foot | walking | running | on_bicycle |
in_vehicle | unknown`.

Unavailable numeric fields are `-1`, never `null`.

### Constants

`DESIRED_ACCURACY_NAVIGATION | HIGH | MEDIUM | LOW | VERY_LOW | LOWEST`,
`AUTHORIZATION_STATUS_*`, `ACCURACY_AUTHORIZATION_*`, `LOG_LEVEL_*`,
`ACTIVITY_TYPE_*`, `PERSIST_MODE_*`, `NOTIFICATION_PRIORITY_*` — all exposed
on the singleton and as TypeScript enums.

### Upload body

Every HTTP upload automatically includes device identity:

```json
{
  "location": { ...record },
  "device_id": "b3f1c9de-…",
  "device_name": "samsung SM-S711B",
  "device": { "id", "name", "model", "manufacturer", "platform", "os_version" }
}
```

`location` is the `httpRootProperty` (configurable; `"."` places the record at
the body root). `Config.params` is merged in and wins on key collisions.
