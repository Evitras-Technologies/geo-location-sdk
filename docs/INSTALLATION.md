# Installation & Setup

- [React Native CLI](#react-native-cli)
- [Expo](#expo)
- [Android details](#android-details)
- [iOS details](#ios-details)
- [Verifying the install](#verifying-the-install)

---

## React Native CLI

```bash
npm install @evitras-technologies/react-native-background-geolocation
```

Autolinking picks up the native modules. Then:

```bash
cd ios && pod install && cd ..
```

### iOS — Info.plist

Add these to `ios/<YourApp>/Info.plist`. **The app will crash on launch without
the usage descriptions** — iOS requires them for any location access.

```xml
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>This app tracks your route in the background to …</string>

<key>NSLocationWhenInUseUsageDescription</key>
<string>This app shows your position while you use it.</string>

<key>NSMotionUsageDescription</key>
<string>Motion detection lets the app save battery while you are stationary.</string>

<key>UIBackgroundModes</key>
<array>
  <string>location</string>
</array>
```

Write real sentences explaining *why* — Apple rejects vague strings such as
"needs location".

### Android — nothing to do

The SDK's own manifest declares every permission, service and receiver it
needs, and the manifest merger applies them automatically. No manual edits.

### Headless task (optional, Android)

To receive events after the app is terminated, register a task in your entry
file **outside** any component:

```js
// index.js
import BackgroundGeolocation from '@evitras-technologies/react-native-background-geolocation';

BackgroundGeolocation.registerHeadlessTask(async (event) => {
  console.log('[headless]', event.name, event.params);
});
```

Requires `stopOnTerminate: false` and `enableHeadless: true` in your config.

---

## Expo

> ⚠️ This SDK contains native code and **cannot run in Expo Go**. You need a
> development build.

```bash
npx expo install @evitras-technologies/react-native-background-geolocation
```

Add the config plugin to `app.json`:

```json
{
  "expo": {
    "plugins": [
      [
        "@evitras-technologies/react-native-background-geolocation",
        {
          "locationAlwaysAndWhenInUsePermission": "This app tracks your route in the background to …",
          "locationWhenInUsePermission": "This app shows your position while you use it.",
          "motionPermission": "Motion detection lets the app save battery while you are stationary."
        }
      ]
    ]
  }
}
```

All three options are optional; sensible defaults are used if omitted. Then:

```bash
npx expo prebuild        # generates native projects with the plugin applied
npx expo run:android     # or: npx expo run:ios
```

### What the plugin does for you

**iOS** — adds `NSLocationAlwaysAndWhenInUseUsageDescription`,
`NSLocationWhenInUseUsageDescription`, `NSMotionUsageDescription` and the
`location` background mode to `Info.plist`.

**Android** — adds every permission the SDK needs: `ACCESS_FINE_LOCATION`,
`ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `ACTIVITY_RECOGNITION`,
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `RECEIVE_BOOT_COMPLETED`,
`WAKE_LOCK`, `POST_NOTIFICATIONS`, `INTERNET`, `ACCESS_NETWORK_STATE`.

### EAS Build

Works unchanged — the plugin runs during the prebuild step:

```bash
eas build --profile development --platform android
```

---

## Android details

### Versions

| Setting | Value |
|---|---|
| `minSdkVersion` | 23 (Android 6.0) |
| `compileSdkVersion` | 34 |
| `targetSdkVersion` | 34 (raise to 35 before a Play Store submission) |

Override these from your app's `android/build.gradle` `ext` block if needed —
the SDK reads the host app's values.

### Dependencies pulled in

- `com.google.android.gms:play-services-location` — FusedLocationProvider,
  geofencing, activity recognition
- `com.squareup.okhttp3:okhttp` — HTTP uploads
- `androidx.core:core-ktx`

Google Play Services is **required**. Devices without it (some Chinese ROMs,
Huawei post-2019) will not get location updates.

### Cleartext HTTP for local testing

Production servers should use HTTPS. If you must upload to `http://` on a LAN
during development, add to your app's `AndroidManifest.xml`:

```xml
<application android:usesCleartextTraffic="true" ...>
```

Remove it before release.

---

## iOS details

### Versions

Minimum deployment target **iOS 13.0**. Set in your Podfile:

```ruby
platform :ios, '13.0'
```

### Background capability

`UIBackgroundModes` with `location` must be present (the Expo plugin adds it;
CLI users add it manually as shown above). Without it, the SDK cannot request
background location updates and iOS suspends tracking when the app leaves the
foreground.

### Known limitation

The iOS native code is complete and mirrors Android feature-for-feature, but
has not yet been compiled on a Mac in this project's CI. Expect to fix minor
build issues on first `pod install` + Xcode build.

---

## Verifying the install

```ts
const info = await BackgroundGeolocation.getDeviceInfo();
console.log(info);
// { model, manufacturer, version, platform, framework, deviceId }
```

If this resolves, the native module is linked. If it throws
"doesn't seem to be linked", you either skipped `pod install`, are running in
Expo Go, or need to rebuild the app (a Metro reload is not enough — native
changes require a full rebuild).

Then check that everything needed for background tracking is granted:

```ts
const status = await BackgroundGeolocation.getPermissionStatus();
console.log(status.readyForBackgroundTracking); // true when nothing is missing
```

See **[Background Tracking](BACKGROUND-TRACKING.md)** for what to do when it
returns `false`.
