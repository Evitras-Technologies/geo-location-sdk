# Expo example

The same full-featured demo as [../example](../example) — realtime mode,
geofences, odometer, database inspector, OEM battery settings, live event feed
— running as an Expo development build via the SDK's config plugin.

`App.tsx` is **identical** to the React Native CLI example. That is deliberate:
the SDK's JavaScript API is the same on both, so nothing in the demo needs to
change between workflows.

> ⚠️ This SDK contains native code and **cannot run in Expo Go**. You need a
> development build.

## Run it

```bash
npm install

# Generate native projects with the config plugin applied
npx expo prebuild

# Build and launch a development build
npx expo run:android     # or: npx expo run:ios
```

## What the config plugin does

Declared in [app.json](app.json); no manual native edits required.

**iOS** — adds `NSLocationAlwaysAndWhenInUseUsageDescription`,
`NSLocationWhenInUseUsageDescription`, `NSMotionUsageDescription` and the
`location` background mode to `Info.plist`.

**Android** — adds every permission the SDK needs: fine/coarse/background
location, activity recognition, foreground service (+ location type), boot
completed, wake lock, notifications, network state, and the
battery-optimization exemption request.

Customize the permission dialog text through the plugin options in `app.json`.

## Testing against the local server

The demo has a server-URL field, so you can point it at
[../test-server](../test-server) without rebuilding.

Use an **HTTPS** URL (for example a `cloudflared` tunnel). Plain `http://` on a
LAN is blocked by Android's default cleartext policy — if you need it during
development, add to `app.json`:

```json
"android": { "usesCleartextTraffic": true }
```

Remove it before release.

## EAS Build

Works unchanged — the plugin runs during the prebuild step:

```bash
eas build --profile development --platform android
```
