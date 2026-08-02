import { NativeModule } from './NativeModule';

/**
 * The SDK's persistent logger. Log entries are stored in the SDK database
 * (pruned after `Config.logMaxDays`) and can be fetched or emailed for
 * field debugging.
 *
 * ```ts
 * import BackgroundGeolocation, { Logger } from 'react-native-open-background-geolocation';
 *
 * Logger.info('Something interesting happened');
 * const log = await Logger.getLog();
 * ```
 */
export const Logger = {
  error(message: string): Promise<void> {
    return NativeModule.log('error', message);
  },
  warn(message: string): Promise<void> {
    return NativeModule.log('warn', message);
  },
  info(message: string): Promise<void> {
    return NativeModule.log('info', message);
  },
  debug(message: string): Promise<void> {
    return NativeModule.log('debug', message);
  },
  notice(message: string): Promise<void> {
    return NativeModule.log('info', message);
  },

  /** The entire persisted log as a single string, newest entries last. */
  getLog(): Promise<string> {
    return NativeModule.getLog();
  },

  /** Delete all persisted log entries. */
  destroyLog(): Promise<void> {
    return NativeModule.destroyLog();
  },

  /** Open the platform mail composer with the log attached/inlined. */
  emailLog(email: string): Promise<void> {
    return NativeModule.emailLog(email);
  },
};
