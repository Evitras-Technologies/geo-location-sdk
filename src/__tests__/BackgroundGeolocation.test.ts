import BackgroundGeolocation from '../index';
import { DeviceSettings } from '../DeviceSettings';
import { Logger } from '../Logger';
import {
  emitNativeEvent,
  nativeCalls,
  nativeResults,
  listeners,
  Platform,
  resetMocks,
} from './mocks/reactNative';

/**
 * The JS facade is the surface every consumer touches. These tests assert it
 * forwards to the right native method with the right arguments, routes events
 * to the right callbacks, and honours platform guards — the failures that
 * would break an app without any native-side bug.
 */
beforeEach(() => resetMocks());

const lastCall = () => nativeCalls[nativeCalls.length - 1];

describe('lifecycle delegation', () => {
  it('ready() forwards config to the native module', async () => {
    await BackgroundGeolocation.ready({ distanceFilter: 25, url: 'https://example.com' });
    expect(lastCall().method).toBe('ready');
    expect(lastCall().args[0]).toEqual({ distanceFilter: 25, url: 'https://example.com' });
  });

  it('ready() defaults to an empty config', async () => {
    await BackgroundGeolocation.ready();
    expect(lastCall().args[0]).toEqual({});
  });

  it('start, stop and startGeofences map to their native methods', async () => {
    await BackgroundGeolocation.start();
    expect(lastCall().method).toBe('start');
    await BackgroundGeolocation.stop();
    expect(lastCall().method).toBe('stop');
    await BackgroundGeolocation.startGeofences();
    expect(lastCall().method).toBe('startGeofences');
  });

  it('changePace passes the boolean through', async () => {
    await BackgroundGeolocation.changePace(true);
    expect(lastCall()).toEqual({ method: 'changePace', args: [true] });
  });
});

describe('geolocation', () => {
  it('getCurrentPosition forwards its options', async () => {
    await BackgroundGeolocation.getCurrentPosition({ timeout: 5000, samples: 5 });
    expect(lastCall().method).toBe('getCurrentPosition');
    expect(lastCall().args[0]).toEqual({ timeout: 5000, samples: 5 });
  });

  it('resetOdometer sets the odometer to zero', async () => {
    await BackgroundGeolocation.resetOdometer();
    expect(lastCall()).toEqual({ method: 'setOdometer', args: [0] });
  });

  it('watchPosition registers a listener and starts the native watch', async () => {
    const received: unknown[] = [];
    const subscription = await BackgroundGeolocation.watchPosition((location) =>
      received.push(location)
    );
    expect(nativeCalls.some((call) => call.method === 'watchPosition')).toBe(true);

    emitNativeEvent('watchposition', { coords: { latitude: 1, longitude: 2 } });
    expect(received).toHaveLength(1);

    subscription.remove();
    emitNativeEvent('watchposition', { coords: { latitude: 3, longitude: 4 } });
    expect(received).toHaveLength(1); // detached
  });

  it('watchPosition routes error payloads to the error callback', async () => {
    const locations: unknown[] = [];
    const errors: number[] = [];
    await BackgroundGeolocation.watchPosition(
      (location) => locations.push(location),
      (code) => errors.push(code)
    );
    emitNativeEvent('watchposition', { error: 1 });
    expect(errors).toEqual([1]);
    expect(locations).toHaveLength(0);
  });
});

describe('geofencing', () => {
  it('addGeofence forwards a circular geofence', async () => {
    const geofence = { identifier: 'depot', latitude: 12.9, longitude: 77.5, radius: 200 };
    await BackgroundGeolocation.addGeofence(geofence);
    expect(lastCall()).toEqual({ method: 'addGeofence', args: [geofence] });
  });

  it('addGeofence forwards a polygon geofence unchanged', async () => {
    const polygon = {
      identifier: 'zone',
      vertices: [
        [12.97, 77.59],
        [12.98, 77.6],
        [12.97, 77.61],
      ] as Array<[number, number]>,
    };
    await BackgroundGeolocation.addGeofence(polygon);
    expect(lastCall().args[0]).toEqual(polygon);
  });

  it('removeGeofences takes no arguments', async () => {
    await BackgroundGeolocation.removeGeofences();
    expect(lastCall()).toEqual({ method: 'removeGeofences', args: [] });
  });
});

describe('events', () => {
  it('onLocation delivers successful fixes', () => {
    const received: unknown[] = [];
    const subscription = BackgroundGeolocation.onLocation((location) => received.push(location));
    emitNativeEvent('location', { uuid: 'a', coords: {} });
    expect(received).toHaveLength(1);
    subscription.remove();
  });

  it('onLocation routes error payloads to the failure callback', () => {
    const received: unknown[] = [];
    const errors: number[] = [];
    const subscription = BackgroundGeolocation.onLocation(
      (location) => received.push(location),
      (code) => errors.push(code)
    );
    emitNativeEvent('location', { error: 2 });
    expect(errors).toEqual([2]);
    expect(received).toHaveLength(0);
    subscription.remove();
  });

  it('subscriptions detach on remove', () => {
    let count = 0;
    const subscription = BackgroundGeolocation.onMotionChange(() => {
      count += 1;
    });
    emitNativeEvent('motionchange', { isMoving: true });
    subscription.remove();
    emitNativeEvent('motionchange', { isMoving: false });
    expect(count).toBe(1);
  });

  it('removeAllListeners detaches every registered handler', () => {
    BackgroundGeolocation.onLocation(() => undefined);
    BackgroundGeolocation.onGeofence(() => undefined);
    BackgroundGeolocation.onHttp(() => undefined);
    expect(listeners.length).toBeGreaterThan(0);
    BackgroundGeolocation.removeAllListeners();
    expect(listeners).toHaveLength(0);
  });
});

describe('platform guards', () => {
  it('registerHeadlessTask is a no-op on iOS', () => {
    Platform.OS = 'ios';
    expect(() => BackgroundGeolocation.registerHeadlessTask(async () => undefined)).not.toThrow();
  });

  it('DeviceSettings reports battery optimization as disabled on iOS', async () => {
    Platform.OS = 'ios';
    // iOS has no battery-optimization concept; reporting `true` keeps callers
    // from prompting for something that cannot exist.
    await expect(DeviceSettings.isIgnoringBatteryOptimizations()).resolves.toBe(true);
  });

  it('DeviceSettings battery dialog is a no-op on iOS', async () => {
    Platform.OS = 'ios';
    await DeviceSettings.showIgnoreBatteryOptimizations();
    expect(nativeCalls).toHaveLength(0);
  });

  it('DeviceSettings requests the right Android settings screen', async () => {
    Platform.OS = 'android';
    await DeviceSettings.showIgnoreBatteryOptimizations();
    expect(lastCall()).toEqual({
      method: 'showSettings',
      args: ['IGNORE_BATTERY_OPTIMIZATIONS'],
    });
  });

  it('requestLocationServices falls back to provider state on iOS', async () => {
    Platform.OS = 'ios';
    nativeResults.getProviderState = { enabled: true };
    await expect(BackgroundGeolocation.requestLocationServices()).resolves.toBe(true);
    expect(lastCall().method).toBe('getProviderState');
  });
});

describe('Logger', () => {
  it('maps each level onto the native log method', async () => {
    await Logger.error('boom');
    expect(lastCall()).toEqual({ method: 'log', args: ['error', 'boom'] });
    await Logger.warn('careful');
    expect(lastCall()).toEqual({ method: 'log', args: ['warn', 'careful'] });
    await Logger.info('fyi');
    expect(lastCall()).toEqual({ method: 'log', args: ['info', 'fyi'] });
    await Logger.debug('detail');
    expect(lastCall()).toEqual({ method: 'log', args: ['debug', 'detail'] });
  });
});

describe('constants', () => {
  it('exposes the documented accuracy and priority constants', () => {
    expect(BackgroundGeolocation.DESIRED_ACCURACY_HIGH).toBe(-1);
    expect(BackgroundGeolocation.DESIRED_ACCURACY_NAVIGATION).toBe(-2);
    expect(BackgroundGeolocation.NOTIFICATION_PRIORITY_MIN).toBe(-2);
    expect(BackgroundGeolocation.LOG_LEVEL_VERBOSE).toBe(5);
  });
});
