# Making Background Tracking Actually Work

Getting a location while your app is open is easy. Keeping it running for hours
with the screen off, the app swiped away and an aggressive OEM battery manager
trying to kill you is the hard part. This guide explains what fights you and
what the SDK does about it.

- [What the SDK does for you](#what-the-sdk-does-for-you)
- [What you must do](#what-you-must-do)
- [Device-specific setup (India especially)](#device-specific-setup)
- [Battery expectations](#battery-expectations)
- [iOS differences](#ios-differences)

---

## What the SDK does for you

**Foreground service (Android).** A `location`-type foreground service with a
persistent notification. Android will not let an app track in the background
without one.

**WakeLock.** A partial WakeLock is held while tracking. A foreground service
keeps your *process* alive but does **not** keep the *CPU* awake — without a
WakeLock the processor sleeps when the screen turns off, timers stop firing and
uploads stall mid-flight. This is the single most common cause of "works in
foreground, silent in background".

**Doze-proof timers.** Stationary updates run on `AlarmManager` with
`setAndAllowWhileIdle`, not `Handler.postDelayed` — handler timers are
suspended entirely once the device enters Doze, which is precisely when you
need them. (Deliberately *inexact* alarms: exact alarms require
`SCHEDULE_EXACT_ALARM`, which Play restricts to clock and calendar apps.)

**Four independent movement signals**, so no single failure strands tracking in
the stationary state:

1. Hardware significant-motion sensor — needs no permission, cannot be
   throttled by the OS
2. Activity Transition API — fires the instant you start walking or driving
3. Passive location watch — piggybacks on fixes other apps request
4. Stationary geofence exit

**OEM-kill watchdog.** An alarm every 15 minutes restarts the tracking service
if a vendor battery manager killed it.

**Service resurrection.** Geofence and activity-recognition broadcasts are
among the few events Android permits to start a foreground service from the
background, so your own movement can revive tracking after a kill.

**Boot and update resume.** With `startOnBoot: true`, tracking restarts after a
reboot without the user opening the app.

**Crash isolation.** Location callbacks, persistence and event dispatch are
individually guarded — an exception is logged to the SDK log rather than
crashing your app.

---

## What you must do

### 1. Let the SDK request everything

```ts
await BackgroundGeolocation.ready({ autoRequestPermissions: true, /* … */ });
```

This runs the sequence Android and Play policy require, in order: foreground
location → prominent disclosure → background location → GPS enable prompt →
battery-optimization exemption.

### 2. Check the readiness flag

```ts
const status = await BackgroundGeolocation.getPermissionStatus();
if (!status.readyForBackgroundTracking) {
  // Something is missing — inspect the individual fields
}
```

`readyForBackgroundTracking` is `true` only when location, background location,
GPS and the battery exemption are all in place. **If it is `false`, background
tracking will be unreliable no matter how correct your code is.**

### 3. Get the battery exemption

```ts
if (!(await DeviceSettings.isIgnoringBatteryOptimizations())) {
  await DeviceSettings.showIgnoreBatteryOptimizations();
}
```

This is not optional on most Android phones sold today. Without it, Doze
throttles your alarms and blocks network access outside maintenance windows.

### 4. Configure for terminated tracking

```ts
{ stopOnTerminate: false, startOnBoot: true, enableHeadless: true }
```

---

## Device-specific setup

Stock Android (Pixel, Motorola, Nokia) generally behaves once the battery
exemption is granted. These vendors need more:

| Vendor | Extra step |
|---|---|
| **Xiaomi / Redmi / Poco** (MIUI, HyperOS) | Security app → Autostart → enable. Settings → Apps → your app → Battery saver → **No restrictions**. Lock the app in Recents (pull down on the card) |
| **Samsung** (One UI) | Settings → Battery → Background usage limits → ensure the app is **not** in "Sleeping apps" or "Deep sleeping apps". Turn off "Put unused apps to sleep" |
| **Oppo / Realme** (ColorOS) | Settings → Battery → App battery management → allow background running + auto-launch |
| **Vivo / iQOO** (Funtouch/OriginOS) | Settings → Battery → High background power consumption → allow. Also enable Autostart |
| **Tecno / Infinix / itel** (HiOS/XOS) | Phone Manager → Autostart manager → enable. Also disable "Power Marathon" for the app |
| **OnePlus** (OxygenOS) | Settings → Battery → Battery optimization → Don't optimize. Disable "Advanced optimization" |
| **Huawei / Honor** (EMUI) | Phone Manager → App launch → Manage manually → enable all three toggles |

The SDK can open most of these screens directly:

```ts
await DeviceSettings.showPowerManager();
```

It rejects when the device has no vendor screen, which is the expected result
on stock Android.

> **Why can't the SDK just fix this?** These settings are protected system UI.
> No app can toggle them programmatically — that is the point. The best any SDK
> can do is detect the state and take the user to the right screen.

---

## Battery expectations

Honest numbers with `desiredAccuracy: HIGH`:

| Mode | While moving | While parked |
|---|---|---|
| Battery-first (`stationaryUpdateInterval: 0`) | 2–4 %/h | ~0 |
| Balanced (`stationaryUpdateInterval: 12`) | 2–4 %/h | 4–6 %/h |
| Realtime (`disableStopDetection: true`) | 10–15 %/h | 10–15 %/h |

The dominant cost is GPS radio time, not the SDK. Cheapest wins available:

- Raise `distanceFilter` — fewer fixes for the same route
- Raise `stationaryUpdateInterval` from 12 s to 30–60 s — large saving, map
  still alive
- Use `batchSync: true` with `autoSyncThreshold` — one radio wake-up per batch
  instead of per point
- Drop to `DESIRED_ACCURACY_MEDIUM` when precision doesn't matter — skips GPS
  entirely and uses Wi-Fi/cell
- Switch to Realtime mode only during an active trip, then back

**On "100 % battery optimized and always reporting":** these pull in opposite
directions. Android's entire power model exists to stop apps doing exactly what
continuous tracking requires. What is achievable is the best available balance,
not zero cost — treat any SDK claiming otherwise with suspicion.

---

## iOS differences

iOS is stricter but more predictable — there are no vendor battery managers.

- **Termination**: iOS never keeps arbitrary apps running. With
  `stopOnTerminate: false` the SDK registers for significant-location-change
  updates, so iOS relaunches the app in the background when the device moves
  ~500 m; full tracking then resumes. This is how every iOS tracking app works.
- **No battery-exemption concept** — nothing to request.
- **No in-app GPS prompt** — if Location Services is off, the user must enable
  it in Settings. `requestLocationServices()` reports state only.
- **"Allow all the time"** is often granted by iOS as a delayed prompt days
  after install; handle `onProviderChange` rather than assuming.
- **`preventSuspend: true`** keeps the app awake while stationary for reliable
  heartbeats, at a real battery cost.
