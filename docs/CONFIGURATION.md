# Configuration

Every option is optional. The merged config is **persisted natively** and
survives app restarts and reboots, so the SDK can resume tracking headlessly
before your JavaScript runs.

All numeric values are **clamped to safe ranges** — an SDK cannot trust its
callers, and a negative `distanceFilter` or zero update interval would spin
location services and destroy the battery.

- [Geolocation](#geolocation)
- [Motion detection](#motion-detection)
- [Stationary reporting](#stationary-reporting)
- [HTTP & persistence](#http--persistence)
- [Application behaviour](#application-behaviour)
- [Geofencing](#geofencing)
- [Debugging](#debugging)
- [Recipes](#recipes)

---

## Geolocation

| Option | Default | Description |
|---|---|---|
| `desiredAccuracy` | `HIGH` | `NAVIGATION`, `HIGH`, `MEDIUM` (10m), `LOW` (100m), `VERY_LOW` (1km), `LOWEST` (3km). `MEDIUM` and below skip GPS entirely — far cheaper |
| `distanceFilter` | `10` | Metres of movement before a new point. `0` records every fix |
| `stationaryRadius` | `25` | Exit radius that triggers the moving state (min 25) |
| `disableElasticity` | `false` | When `false`, the distance filter widens with speed to thin highway points |
| `elasticityMultiplier` | `1` | How aggressively elasticity scales |
| `desiredOdometerAccuracy` | `100` | Fixes worse than this don't count toward the odometer |
| `stopAfterElapsedMinutes` | `0` | Auto-stop tracking after N minutes (`0` = never) |
| `locationUpdateInterval` | `1000` | Android: ms between updates (min 1000) |
| `fastestLocationUpdateInterval` | `10000` | Android: fastest rate your app can handle |
| `allowIdenticalLocations` | `false` | Keep fixes with unchanged coordinates |
| `speedJumpFilter` | `55` | Discard fixes implying speeds above this (m/s ≈ 200 km/h) — rejects GPS teleports. Raise for aviation |
| `minimumAccuracy` | `0` | Discard fixes worse than N metres. **Set this** (25–50) for vehicle tracking: indoors Android falls back to network positioning with ±100–200 m fixes that jump hundreds of metres and read as high-speed travel |
| `useSignificantChangesOnly` | `false` | iOS: ~500m granularity, minimal battery |
| `showsBackgroundLocationIndicator` | `false` | iOS: blue status-bar pill |
| `pausesLocationUpdatesAutomatically` | `false` | iOS: let the OS pause updates |
| `activityType` | `OTHER` | iOS `CLLocationManager.activityType` hint |

## Motion detection

| Option | Default | Description |
|---|---|---|
| `stopTimeout` | `5` | Minutes of stillness before returning to stationary |
| `isMoving` | `false` | Start directly in the moving state |
| `disableStopDetection` | `false` | **Never** power down — continuous tracking (ride-hailing style) |
| `motionTriggerDelay` | `0` | Minutes to wait after motion before engaging |
| `minimumActivityRecognitionConfidence` | `70` | Ignore activity samples below this confidence |
| `activityRecognitionInterval` | `10000` | Android: ms between activity samples |
| `disableMotionActivityUpdates` | `false` | iOS: skip CoreMotion (avoids the Motion & Fitness permission) |

## Stationary reporting

| Option | Default | Description |
|---|---|---|
| `stationaryUpdateInterval` | `0` | **Seconds between fresh fixes while parked.** `0` = silent (battery-optimal). Set e.g. `12` to keep a server map alive. Min 5 |
| `heartbeatInterval` | `0` | Seconds between `onHeartbeat` events while stationary. Min 60. Emits the *last known* location only — it does not record or upload |
| `preventSuspend` | `false` | iOS: keep the app awake while stationary. Battery-hungry |

`stationaryUpdateInterval` produces **real** fixes: recorded, persisted,
uploaded and delivered to `onLocation` with `event: "heartbeat"`. On Android it
runs on AlarmManager so it keeps firing during Doze.

## HTTP & persistence

| Option | Default | Description |
|---|---|---|
| `url` | `''` | Your endpoint. Uploads are disabled when empty |
| `method` | `POST` | `POST`, `PUT` or `OPTIONS` |
| `httpRootProperty` | `location` | Body key holding the record(s). `"."` places them at the root |
| `params` | `{}` | Merged into the body root (wins over SDK keys) |
| `headers` | `{}` | Sent with every request |
| `extras` | `{}` | Merged into every record's `extras` |
| `autoSync` | `true` | Upload as records are recorded |
| `autoSyncThreshold` | `0` | Wait for N queued records before syncing |
| `batchSync` | `false` | Send many records per request as a JSON array |
| `maxBatchSize` | `-1` | Cap records per batch (`-1` = unlimited) |
| `maxDaysToPersist` | `14` | Retention for unsynced records |
| `maxRecordsToPersist` | `-1` | Cap queue size; oldest pruned first |
| `persistMode` | `ALL` | `ALL`, `LOCATION`, `GEOFENCE` or `NONE` |
| `httpTimeout` | `60000` | Request timeout in ms |
| `locationsOrderDirection` | `ASC` | Upload oldest-first or newest-first |

**Delivery guarantees.** Records are deleted only after a 2xx response
(at-least-once delivery — your server should deduplicate on `uuid`). Failures
back off exponentially from 5 s to 5 min. Non-retryable 4xx responses discard
the offending record so one bad payload can't block the queue forever.

## Application behaviour

| Option | Default | Description |
|---|---|---|
| `autoRequestPermissions` | `false` | **Let the SDK run the whole permission onboarding at `ready()`** |
| `stopOnTerminate` | `true` | Set `false` to keep tracking after the app is closed |
| `startOnBoot` | `false` | Resume after reboot (needs `stopOnTerminate: false`) |
| `enableHeadless` | `false` | Android: deliver events to your headless task after terminate |
| `locationAuthorizationRequest` | `Always` | `Always`, `WhenInUse` or `Any` |
| `notification` | see below | Android foreground-service notification |
| `backgroundPermissionRationale` | see below | Text for the Play-required disclosure dialog |
| `schedule` | `[]` | Tracking windows, e.g. `["2-6 09:00-17:00"]` (1 = Sunday) |

```ts
notification: {
  title: 'My App',
  text: 'Tracking your route',
  color: '#2563eb',
  smallIcon: 'mipmap/ic_launcher',
  channelName: 'Location Service',
  priority: BackgroundGeolocation.NOTIFICATION_PRIORITY_LOW,
  sticky: true,
},
backgroundPermissionRationale: {
  title: 'Allow background location?',
  message: 'We collect location data to track your route even when the app is closed.',
  positiveAction: 'Change to "Allow all the time"',
  negativeAction: 'Cancel',
},
```

## Geofencing

| Option | Default | Description |
|---|---|---|
| `geofenceProximityRadius` | `1000` | Radius within which geofences are actively monitored |
| `geofenceInitialTriggerEntry` | `true` | Fire ENTER if already inside when added |
| `geofenceModeHighAccuracy` | `false` | Android: high-accuracy location in geofences-only mode |

## Debugging

| Option | Default | Description |
|---|---|---|
| `debug` | `false` | Audible beeps on SDK life-cycle events |
| `logLevel` | `OFF` | `OFF`…`VERBOSE` |
| `logMaxDays` | `3` | Log retention |

---

## Recipes

### Battery-first (all-day tracking)

~2–4 %/hour moving, near zero while parked.

```ts
{
  desiredAccuracy: BackgroundGeolocation.DESIRED_ACCURACY_HIGH,
  distanceFilter: 25,
  stopTimeout: 5,
  stationaryUpdateInterval: 0,   // silent while parked
  batchSync: true,               // one radio wake-up per batch
  autoSyncThreshold: 10,
}
```

### Balanced (recommended default)

~2–4 %/hour moving, ~4–6 %/hour parked.

```ts
{
  desiredAccuracy: BackgroundGeolocation.DESIRED_ACCURACY_HIGH,
  distanceFilter: 10,
  stopTimeout: 5,
  stationaryUpdateInterval: 12,  // keeps the server map alive
  autoSync: true,
}
```

### Realtime (ride-hailing / delivery, active trip only)

~10–15 %/hour. A point every second, never idles, no manual trigger.

```ts
{
  desiredAccuracy: BackgroundGeolocation.DESIRED_ACCURACY_HIGH,
  distanceFilter: 0,
  locationUpdateInterval: 1000,
  fastestLocationUpdateInterval: 1000,
  disableStopDetection: true,
  disableElasticity: true,
  allowIdenticalLocations: true,
  autoSync: true,
  autoSyncThreshold: 0,
  batchSync: false,
}
```

Switch into it with `setConfig()` when a trip starts and back out when it ends
— that is exactly what commercial ride-hailing apps do.

### Geofences only (lowest power)

```ts
await BackgroundGeolocation.ready({ url: '…' });
await BackgroundGeolocation.addGeofences([...]);
await BackgroundGeolocation.startGeofences();
```
