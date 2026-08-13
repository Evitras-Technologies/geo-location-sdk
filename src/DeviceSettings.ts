import { Platform } from 'react-native';

import { NativeModule } from './NativeModule';

/**
 * Helpers for redirecting the user to vendor-specific device settings which
 * affect background tracking reliability (battery optimization whitelisting,
 * OEM "auto-start" managers, …). Android only; no-ops on iOS.
 *
 * ```ts
 * import { DeviceSettings } from '@evitras-technologies/react-native-background-geolocation';
 *
 * await DeviceSettings.showIgnoreBatteryOptimizations();
 * ```
 */
export const DeviceSettings = {
  /**
   * Show the direct system "Allow app to run in background?" dialog which
   * whitelists your app from battery optimization — the single most important
   * step for reliable tracking on Xiaomi / Vivo / Oppo / Realme / Samsung
   * devices. Resolves immediately when already whitelisted.
   */
  showIgnoreBatteryOptimizations(): Promise<void> {
    if (Platform.OS !== 'android') {
      return Promise.resolve();
    }
    return NativeModule.showSettings('IGNORE_BATTERY_OPTIMIZATIONS');
  },

  /** `true` when the app is already whitelisted from battery optimization (always `true` on iOS). */
  isIgnoringBatteryOptimizations(): Promise<boolean> {
    if (Platform.OS !== 'android') {
      return Promise.resolve(true);
    }
    return NativeModule.isIgnoringBatteryOptimizations();
  },

  /** Open the OEM power-manager / auto-start screen when the vendor exposes one (Huawei, Xiaomi, Oppo, …). */
  showPowerManager(): Promise<void> {
    if (Platform.OS !== 'android') {
      return Promise.resolve();
    }
    return NativeModule.showSettings('POWER_MANAGER');
  },

  /** Open your app's system settings page. */
  show(): Promise<void> {
    return NativeModule.showSettings('APP_SETTINGS');
  },
};
