# Expo example

Demo of `react-native-open-background-geolocation` in an Expo project using the
SDK's config plugin.

> ⚠️ The SDK contains native code — it **cannot run in Expo Go**. Use a
> development build.

## Run it

```bash
npm install

# Generate native projects with the config plugin applied
npx expo prebuild

# Build & launch a development build
npx expo run:android   # or: npx expo run:ios
```

The config plugin (declared in [app.json](app.json)) automatically adds:

- **iOS**: `NSLocationAlwaysAndWhenInUseUsageDescription`,
  `NSLocationWhenInUseUsageDescription`, `NSMotionUsageDescription` and the
  `location` background mode.
- **Android**: every location / foreground-service / boot permission the SDK
  needs.

Customize the permission-dialog texts via the plugin options in `app.json`.

## EAS Build

Works out of the box — the plugin runs during `eas build`'s prebuild step:

```bash
eas build --profile development --platform android
```
