# @evitras-technologies/react-native-background-geolocation

**Free, open-source (MIT) background geolocation for React Native CLI and Expo.**

Battery-conscious background location tracking with a motion-detection state
machine, geofencing, SQLite persistence, automatic HTTP sync, headless Android
tasks and OEM-survival hardening. No license keys, no premium tier — every
feature works in debug *and* release builds on both platforms.

## Project status — read before adopting

This is an early release (`0.1.0`). Being straight about what is proven and
what is not:

| | Status |
|---|---|
| **Android** | Compiles, runs, and has been exercised on real hardware — background tracking, offline queue and sync, geofencing, HTTP upload |
| **iOS** | **Written but never compiled.** Feature-complete in source and mirrors Android, but it has not been built in Xcode. Treat as unverified |
| **Tests** | 117 unit tests (79 Kotlin, 38 TypeScript) covering config validation, location filtering, polygon geometry, network accounting and the JS API |
| **Untested areas** | Motion state machine, SQLite layer and foreground-service lifecycle have no automated coverage |
| **Field testing** | Limited — a small number of Android devices |

Android use is reasonable if you can test on your target devices. **Do not ship
to iOS users without compiling and testing it first.** Bug reports and device
results are the most useful contribution right now.

---

## Documentation

| Guide | What's in it |
|---|---|
| **[Installation](docs/INSTALLATION.md)** | React Native CLI, Expo, Android and iOS setup |
| **[API Reference](docs/API.md)** | Every method, event and type |
| **[Configuration](docs/CONFIGURATION.md)** | Every option, with defaults and tradeoffs |
| **[Background Tracking](docs/BACKGROUND-TRACKING.md)** | Making tracking survive Doze, OEM battery managers and app termination |
| **[Troubleshooting](docs/TROUBLESHOOTING.md)** | Symptom-first fixes for the common failures |
| **[Changelog](CHANGELOG.md)** | What changed, when |

## How it works

The SDK runs a **motion-detection state machine** designed for multi-hour
tracking without draining the battery:

```
 ┌────────────┐   movement detected (4 independent signals)   ┌──────────┐
 │ STATIONARY │ ────────────────────────────────────────────▶ │  MOVING  │
 │  (GPS off) │ ◀──────────────────────────────────────────── │ (GPS on) │
 └────────────┘        still for `stopTimeout` minutes        └──────────┘
```

- While **stationary**, continuous GPS is off. Movement is detected by four
  independent signals so a single failure can't strand tracking: the hardware
  significant-motion sensor, activity recognition, a passive location watch and
  a stationary geofence.
- While **moving**, points are recorded every `distanceFilter` metres (scaled
  up with speed to avoid flooding at highway pace).
- Optionally, `stationaryUpdateInterval` keeps reporting a position on a timer
  even while parked, so a server-side map never goes silent.
- Every point is written to **SQLite**, then uploaded to your `url` with
  **at-least-once delivery** — records are deleted only after your server
  responds 2xx. Offline points queue and sync when connectivity returns.

## Feature matrix

| Feature | Android | iOS |
|---|---|---|
| Background tracking | ✅ foreground service | ✅ background mode |
| Motion state machine | ✅ | ✅ |
| Activity recognition | ✅ Play Services + transitions | ✅ CoreMotion |
| Significant-motion hardware sensor | ✅ | — (CoreMotion covers it) |
| Geofencing (beyond OS limits via proximity swapping) | ✅ 100 native | ✅ 20 native |
| SQLite persistence + retention rules | ✅ | ✅ |
| HTTP sync (single/batch, retry backoff) | ✅ | ✅ |
| Automatic device identity on uploads | ✅ | ✅ |
| Tracking after app terminate | ✅ | ✅ significant-change relaunch |
| Resume after reboot | ✅ | ✅ |
| Headless JS events after terminate | ✅ | — |
| OEM-kill watchdog + service resurrection | ✅ | n/a |
| WakeLock while moving (released when parked) | ✅ | n/a |
| Doze-proof stationary updates | ✅ AlarmManager | ✅ |
| SDK-managed permission onboarding | ✅ | ✅ partial¹ |
| Expo config plugin | ✅ | ✅ |

¹ iOS has no in-app prompt for enabling Location Services or battery
exemption; those concepts don't exist on the platform.

## Quick start

```bash
npm install @evitras-technologies/react-native-background-geolocation
cd ios && pod install   # React Native CLI only
```

```ts
import BackgroundGeolocation from '@evitras-technologies/react-native-background-geolocation';

// 1. Register listeners BEFORE ready() so nothing is missed.
BackgroundGeolocation.onLocation((location) => {
  console.log('[location]', location.coords.latitude, location.coords.longitude);
});

BackgroundGeolocation.onMotionChange((event) => {
  console.log('[motionchange] moving?', event.isMoving);
});

// 2. Configure. ready() persists config — call it on every app launch.
const state = await BackgroundGeolocation.ready({
  autoRequestPermissions: true,   // SDK handles every permission prompt
  desiredAccuracy: BackgroundGeolocation.DESIRED_ACCURACY_HIGH,
  distanceFilter: 10,             // metres between points while moving
  stationaryUpdateInterval: 12,   // seconds between points while parked
  stopOnTerminate: false,         // keep tracking after the app is closed
  startOnBoot: true,              // resume after reboot
  url: 'https://your.server.com/locations',
  headers: { Authorization: 'Bearer your-token' },
});

// 3. Start tracking.
if (!state.enabled) {
  await BackgroundGeolocation.start();
}
```

That's the whole integration. The SDK requests permissions, shows the
background-location disclosure required by Play policy, prompts to enable GPS,
asks for the battery-optimization exemption, tracks, persists, uploads and
tags each record with a stable device identity — with no further code.

## Examples

- **[example/](example/)** — React Native CLI demo: live event feed, realtime
  mode toggle, geofences, odometer, database inspector, OEM settings buttons.
- **[example-expo/](example-expo/)** — the same demo as an Expo development
  build using the config plugin.
- **[test-server/](test-server/)** — a local Express + MongoDB server with a
  live map dashboard for verifying uploads end to end.

## Requirements

- React Native 0.71+ (new architecture works via the interop layer)
- **Android**: minSdk 23, compileSdk 34, Google Play Services
- **iOS**: 13.0+
- **Expo**: SDK 50+, development build required (**not** Expo Go)

## License

[MIT](LICENSE) — free for personal and commercial use, forever, on every
platform, in debug and release builds.
