/**
 * Expo config plugin for @evitras-technologies/react-native-background-geolocation.
 *
 * Adds the native permissions and background capabilities the SDK needs, so
 * Expo users never touch Info.plist or AndroidManifest.xml by hand:
 *
 * ```json
 * {
 *   "expo": {
 *     "plugins": [
 *       [
 *         "@evitras-technologies/react-native-background-geolocation",
 *         {
 *           "locationAlwaysAndWhenInUsePermission": "This app tracks your route in the background.",
 *           "motionPermission": "Motion detection reduces battery usage while stationary."
 *         }
 *       ]
 *     ]
 *   }
 * }
 * ```
 *
 * Requires a development build (`npx expo prebuild` / EAS Build) — the SDK
 * contains native code and cannot run in Expo Go.
 */
let configPlugins;
try {
  // Preferred: the version re-exported by the project's own `expo` package.
  configPlugins = require('expo/config-plugins');
} catch (error) {
  configPlugins = require('@expo/config-plugins');
}

const { AndroidConfig, withInfoPlist, withAndroidManifest, createRunOncePlugin } = configPlugins;

const packageJson = require('../package.json');

const DEFAULT_MESSAGES = {
  locationAlwaysAndWhenInUsePermission:
    'This app uses your location in the background to provide location-aware features.',
  locationWhenInUsePermission: 'This app uses your location to provide location-aware features.',
  motionPermission:
    'This app uses motion detection to conserve battery while you are stationary.',
};

const ANDROID_PERMISSIONS = [
  'android.permission.INTERNET',
  'android.permission.ACCESS_NETWORK_STATE',
  'android.permission.ACCESS_COARSE_LOCATION',
  'android.permission.ACCESS_FINE_LOCATION',
  'android.permission.ACCESS_BACKGROUND_LOCATION',
  'android.permission.ACTIVITY_RECOGNITION',
  'android.permission.FOREGROUND_SERVICE',
  'android.permission.FOREGROUND_SERVICE_LOCATION',
  'android.permission.RECEIVE_BOOT_COMPLETED',
  // Keeps the CPU awake while tracking; without it the processor sleeps when
  // the screen turns off and background uploads stall.
  'android.permission.WAKE_LOCK',
  'android.permission.POST_NOTIFICATIONS',
  // Lets the SDK show the system "allow background activity" dialog, which is
  // required for reliable tracking on Xiaomi / Samsung / Oppo / Vivo devices.
  'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
];

function withIosBackgroundGeolocation(config, messages) {
  return withInfoPlist(config, (pluginConfig) => {
    const infoPlist = pluginConfig.modResults;

    infoPlist.NSLocationAlwaysAndWhenInUseUsageDescription =
      messages.locationAlwaysAndWhenInUsePermission;
    infoPlist.NSLocationWhenInUseUsageDescription = messages.locationWhenInUsePermission;
    infoPlist.NSMotionUsageDescription = messages.motionPermission;

    const backgroundModes = new Set(infoPlist.UIBackgroundModes || []);
    backgroundModes.add('location');
    infoPlist.UIBackgroundModes = Array.from(backgroundModes);

    return pluginConfig;
  });
}

function withAndroidBackgroundGeolocation(config) {
  return withAndroidManifest(config, (pluginConfig) => {
    const manifest = pluginConfig.modResults;
    ANDROID_PERMISSIONS.forEach((permission) => {
      AndroidConfig.Permissions.addPermission(manifest, permission);
    });
    return pluginConfig;
  });
}

function withBackgroundGeolocation(config, options = {}) {
  const messages = { ...DEFAULT_MESSAGES, ...options };
  let modifiedConfig = withIosBackgroundGeolocation(config, messages);
  modifiedConfig = withAndroidBackgroundGeolocation(modifiedConfig);
  return modifiedConfig;
}

module.exports = createRunOncePlugin(
  withBackgroundGeolocation,
  packageJson.name,
  packageJson.version
);
