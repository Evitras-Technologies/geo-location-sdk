# React Native CLI example

A full-featured demo of `react-native-open-background-geolocation`: live event
feed, start/stop tracking, `changePace`, `getCurrentPosition`, geofences,
odometer and the SQLite database.

Because native `android/` and `ios/` folders are machine-generated, create them
once with the React Native CLI and drop this example's JS files in.

## 1. Generate the native shell

```bash
npx @react-native-community/cli init BackgroundGeolocationExample --version 0.74.0
cd BackgroundGeolocationExample
```

Copy `App.tsx`, `index.js` and `app.json` from this folder over the generated
ones, then install the SDK:

```bash
npm install ../..   # or: npm install react-native-open-background-geolocation
```

## 2. Android setup

The SDK's manifest already declares every permission and service it needs —
the manifest merger picks them up automatically. Nothing else is required.

Optional (recommended for reliable vendor devices): ask the user to whitelist
the app from battery optimization via
`DeviceSettings.showIgnoreBatteryOptimizations()`.

## 3. iOS setup

```bash
cd ios && pod install
```

Add to `ios/BackgroundGeolocationExample/Info.plist`:

```xml
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>This demo tracks your route in the background.</string>
<key>NSLocationWhenInUseUsageDescription</key>
<string>This demo shows your position on screen.</string>
<key>NSMotionUsageDescription</key>
<string>Motion detection reduces battery usage while stationary.</string>
<key>UIBackgroundModes</key>
<array>
  <string>location</string>
</array>
```

## 4. Run it

```bash
npm run android   # or: npm run ios
```

Toggle the switch to start tracking, then walk around (or use the emulator's
route simulation). Watch the event feed fill with `location`, `motionchange`,
`activitychange` and `geofence` events.

### Testing background behaviour

- **Terminated tracking**: with `stopOnTerminate: false`, swipe the app away —
  the Android foreground-service notification stays and the headless task in
  `index.js` keeps logging events.
- **Boot**: with `startOnBoot: true`, reboot the device; tracking resumes.
- **Geofence**: tap *⭕ Geofence* (registers a 200 m fence at Apple Park), then
  simulate a route through `37.33182, -122.03118`.
