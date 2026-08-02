import { NativeEventEmitter, NativeModules, Platform } from 'react-native';

const LINKING_ERROR =
  `The package 'react-native-open-background-geolocation' doesn't seem to be linked. Make sure: \n\n` +
  Platform.select({ ios: "- You have run 'pod install'\n", default: '' }) +
  '- You rebuilt the app after installing the package\n' +
  '- You are not using Expo Go (use a development build)\n';

/**
 * Raw native module. Every method returns a Promise resolved/rejected by the
 * platform implementation. Return payloads are untyped maps at this boundary;
 * the JS facade in `BackgroundGeolocation.ts` narrows them to the public
 * domain types — do not call this module directly from application code.
 */
export interface NativeBackgroundGeolocation {
  ready(config: Record<string, unknown>): Promise<any>;
  setConfig(config: Record<string, unknown>): Promise<any>;
  reset(config: Record<string, unknown>): Promise<any>;
  getState(): Promise<any>;
  start(): Promise<any>;
  stop(): Promise<any>;
  startGeofences(): Promise<any>;
  changePace(isMoving: boolean): Promise<void>;

  getCurrentPosition(options: Record<string, unknown>): Promise<any>;
  watchPosition(options: Record<string, unknown>): Promise<void>;
  stopWatchPosition(): Promise<void>;

  getOdometer(): Promise<number>;
  setOdometer(value: number): Promise<any>;

  getLocations(): Promise<any[]>;
  getCount(): Promise<number>;
  getSyncStatus(): Promise<any>;
  insertLocation(location: Record<string, unknown>): Promise<string>;
  destroyLocations(): Promise<void>;
  destroyLocation(uuid: string): Promise<void>;
  sync(): Promise<any[]>;

  addGeofence(geofence: Record<string, unknown>): Promise<void>;
  addGeofences(geofences: Array<Record<string, unknown>>): Promise<void>;
  removeGeofence(identifier: string): Promise<void>;
  removeGeofences(): Promise<void>;
  getGeofences(): Promise<any[]>;
  getGeofence(identifier: string): Promise<any>;
  geofenceExists(identifier: string): Promise<boolean>;

  requestPermission(): Promise<number>;
  requestLocationServices(): Promise<boolean>;
  requestAllPermissions(): Promise<any>;
  getPermissionStatus(): Promise<any>;
  requestTemporaryFullAccuracy(purpose: string): Promise<number>;
  getProviderState(): Promise<any>;

  isPowerSaveMode(): Promise<boolean>;
  getSensors(): Promise<any>;
  getDeviceInfo(): Promise<any>;
  getDeviceId(): Promise<string>;

  startBackgroundTask(): Promise<number>;
  stopBackgroundTask(taskId: number): Promise<void>;

  playSound(soundId: number): Promise<void>;

  getLog(): Promise<string>;
  destroyLog(): Promise<void>;
  emailLog(email: string): Promise<void>;
  log(level: string, message: string): Promise<void>;

  showSettings(setting: string): Promise<void>;
  isIgnoringBatteryOptimizations(): Promise<boolean>;

  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

const nativeModule: NativeBackgroundGeolocation = NativeModules.OpenBackgroundGeolocation
  ? NativeModules.OpenBackgroundGeolocation
  : new Proxy({} as NativeBackgroundGeolocation, {
      get() {
        throw new Error(LINKING_ERROR);
      },
    });

export const NativeModule = nativeModule;

export const EventEmitter = new NativeEventEmitter(
  NativeModules.OpenBackgroundGeolocation
);

/** Native event names emitted over the RCTEventEmitter / DeviceEventEmitter bridge. */
export const NativeEvents = {
  LOCATION: 'location',
  MOTIONCHANGE: 'motionchange',
  ACTIVITYCHANGE: 'activitychange',
  PROVIDERCHANGE: 'providerchange',
  GEOFENCE: 'geofence',
  GEOFENCESCHANGE: 'geofenceschange',
  HEARTBEAT: 'heartbeat',
  HTTP: 'http',
  CONNECTIVITYCHANGE: 'connectivitychange',
  POWERSAVECHANGE: 'powersavechange',
  ENABLEDCHANGE: 'enabledchange',
  NOTIFICATIONACTION: 'notificationaction',
  WATCHPOSITION: 'watchposition',
} as const;
