/**
 * Minimal stand-in for `react-native` so the SDK's TypeScript layer can be
 * unit tested on plain Node. Records every native call so tests can assert
 * what the JS facade actually sends across the bridge.
 */

export interface NativeCall {
  method: string;
  args: unknown[];
}

/** Every native method invoked during a test, in order. */
export const nativeCalls: NativeCall[] = [];

/** Values the mocked native module should resolve with, keyed by method. */
export const nativeResults: Record<string, unknown> = {};

export const listeners: Array<{ eventName: string; handler: (event: unknown) => void }> = [];

export function resetMocks(): void {
  nativeCalls.length = 0;
  listeners.length = 0;
  Object.keys(nativeResults).forEach((key) => delete nativeResults[key]);
  Platform.OS = 'android';
}

/** Deliver an event to every listener registered for it, as the bridge would. */
export function emitNativeEvent(eventName: string, payload: unknown): void {
  listeners
    .filter((entry) => entry.eventName === eventName)
    .forEach((entry) => entry.handler(payload));
}

const nativeModule = new Proxy(
  {},
  {
    get(_target, property: string) {
      if (property === 'addListener' || property === 'removeListeners') {
        return () => undefined;
      }
      return (...args: unknown[]) => {
        nativeCalls.push({ method: property, args });
        return Promise.resolve(nativeResults[property] ?? null);
      };
    },
  }
);

export const NativeModules: Record<string, unknown> = {
  OpenBackgroundGeolocation: nativeModule,
};

export class NativeEventEmitter {
  addListener(eventName: string, handler: (event: unknown) => void) {
    const entry = { eventName, handler };
    listeners.push(entry);
    return {
      remove: () => {
        const index = listeners.indexOf(entry);
        if (index >= 0) listeners.splice(index, 1);
      },
    };
  }

  removeAllListeners(eventName: string) {
    for (let index = listeners.length - 1; index >= 0; index--) {
      if (listeners[index].eventName === eventName) listeners.splice(index, 1);
    }
  }
}

export const Platform: { OS: string; select: (spec: Record<string, unknown>) => unknown } = {
  OS: 'android',
  select: (spec) => spec[Platform.OS] ?? spec.default,
};

export const AppRegistry = {
  registeredTasks: [] as string[],
  registerHeadlessTask(taskKey: string) {
    AppRegistry.registeredTasks.push(taskKey);
  },
};
