# Troubleshooting

Symptom-first. Each entry gives the usual cause and the check that confirms it.

---

## "doesn't seem to be linked" on import

The native module isn't in the build.

- Running in **Expo Go**? Not supported — build a development build
  (`npx expo run:android`).
- iOS: did you run `pod install`?
- Did you **rebuild** the app? A Metro reload does not pick up native changes.
- Expo: re-run `npx expo prebuild` after adding the plugin.

---

## No permission dialog appears

Two distinct causes:

**Android permanently silenced it.** After two denials Android stops showing
the system dialog entirely — `requestPermissions` returns "denied" instantly
with no UI. The SDK detects this and shows its own dialog with an
**Open Settings** button. To reset for testing: uninstall the app, or Settings
→ Apps → your app → Permissions → Location → Allow.

**You never asked.** Set `autoRequestPermissions: true` in `ready()`, or call
`requestAllPermissions()` yourself.

---

## "Enable location" / no fix arrives

Device **GPS is switched off** — different from app permission, which is why
granting permission doesn't help. `start()` and `getCurrentPosition()` now show
the system "Turn on location?" prompt automatically. Check:

```ts
(await BackgroundGeolocation.getProviderState()).enabled
```

---

## Works in foreground, silent in background

The most common problem, and almost always environmental rather than code.

1. **Check readiness first:**
   ```ts
   const status = await BackgroundGeolocation.getPermissionStatus();
   console.log(status);  // readyForBackgroundTracking must be true
   ```
2. **`batteryOptimizationDisabled: false`?** That's your answer — call
   `DeviceSettings.showIgnoreBatteryOptimizations()`.
3. **`backgroundLocation: false`?** The user granted "While using the app"
   only. They must choose **Allow all the time**.
4. **Is the notification visible?** If it disappeared, the OEM killed the
   service. Follow the vendor steps in
   [Background Tracking](BACKGROUND-TRACKING.md#device-specific-setup).
5. **Samsung specifically:** Settings → Battery → Background usage limits →
   remove the app from "Sleeping apps".

---

## Stuck in "stationary" — never switches to moving

- Give it **20–30 seconds of real walking**. Shaking the phone at a desk isn't
  enough; the SDK requires evidence of sustained movement.
- Check the activity data in your records. If `activity.type` is permanently
  `unknown` with confidence `0`, activity recognition isn't firing — usually a
  denied physical-activity permission or missing Play Services.
- Need instant engagement? Either `changePace(true)`, or
  `disableStopDetection: true` for continuous tracking.

---

## Dashboard shows "moving" while the device is still

`is_moving` means *"tracking is in high-power mode"*, **not** "physically
moving". With `disableStopDetection: true` it stays `true` permanently by
design.

For physical motion use `activity.type` (preferred — purpose-built) and
`coords.speed`. Don't threshold speed alone: a stationary phone routinely
reports 1–2 m/s of GPS jitter.

---

## No updates while parked

`stationaryUpdateInterval` defaults to `0` — completely silent while
stationary, which is battery-optimal. Set it:

```ts
await BackgroundGeolocation.setConfig({ stationaryUpdateInterval: 12 });
```

Minimum 5 seconds. On Android this runs on AlarmManager so it survives Doze —
but Doze rate-limits alarms to roughly once per 9 minutes **unless the app is
whitelisted from battery optimization**.

---

## Uploads never reach the server

1. **Is `url` set?** `(await BackgroundGeolocation.getState()).url`
2. **Are records being recorded at all?** `getCount()` — if it grows, recording
   works and only upload is failing.
3. **Watch the HTTP events:**
   ```ts
   BackgroundGeolocation.onHttp((e) => console.log(e.status, e.responseText));
   ```
   Status `0` means the request never left the device (DNS, TLS, no route);
   any other status came from your server.
4. **Testing on a LAN?** The phone must reach your PC: same Wi-Fi, firewall
   allowing the port, and `usesCleartextTraffic` for `http://`. A public tunnel
   (`npx cloudflared tunnel --url http://localhost:3000`) avoids all three.
5. **Backoff:** after failures the SDK backs off up to 5 minutes. Force a retry
   with `sync()`, which bypasses it.

Nothing is lost while uploads fail — records queue in SQLite and drain in order
when the server returns.

---

## Duplicate records on the server

Expected. Delivery is **at-least-once**: if your server commits but the
response is lost, the SDK retries. **Deduplicate on `uuid`**, which is stable
per record.

---

## Crash on Android 6 / older devices

Fixed in current versions (an API-24 call was reachable at minSdk 23). If you
see `NoSuchMethodError`, update the package.

---

## Build fails after upgrading

```bash
cd android && ./gradlew clean && cd ..
npx react-native start --reset-cache
```

For a stale local install, reinstall from a fresh pack rather than a symlink —
Metro cannot resolve symlinks outside the project root:

```bash
npm pack        # in the SDK folder
npm install ../path/to/react-native-open-background-geolocation-1.0.0.tgz
```

---

## Checking for policy violations before release

```bash
cd android && ./gradlew lint
```

Then upload to a Play Console internal-testing track and read the
**Pre-Launch Report** — Google runs the app on real devices and reports policy,
crash and performance issues. Finally complete the manual declarations:
background-location justification (with demo video), foreground-service type,
Data Safety form, and privacy policy URL.

---

## Getting diagnostic data

```ts
import { Logger } from 'react-native-open-background-geolocation';

await BackgroundGeolocation.setConfig({
  logLevel: BackgroundGeolocation.LOG_LEVEL_VERBOSE,
  debug: true,   // audible beeps on SDK life-cycle events
});

const log = await Logger.getLog();
await Logger.emailLog('you@example.com');
```

Include the log, the output of `getPermissionStatus()`, and the phone's brand
and Android version when reporting an issue — those three answer most
questions immediately.
