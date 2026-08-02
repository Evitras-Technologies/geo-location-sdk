import BackgroundGeolocationFacade from './BackgroundGeolocation';

/**
 * The singleton SDK instance — import as default:
 *
 * ```ts
 * import BackgroundGeolocation from 'react-native-open-background-geolocation';
 * ```
 */
const BackgroundGeolocation = new BackgroundGeolocationFacade();

export default BackgroundGeolocation;

export { Logger } from './Logger';
export { DeviceSettings } from './DeviceSettings';

export {
  DesiredAccuracy,
  LogLevel,
  AuthorizationStatus,
  AccuracyAuthorization,
  ActivityType,
  PersistMode,
  NotificationPriority,
  TrackingMode,
} from './types/Config';

export type {
  Config,
  State,
  Authorization,
  Notification,
  PermissionRationale,
  ProviderChangeEvent,
  HttpEvent,
  MotionChangeEvent,
  MotionActivityEvent,
  HeartbeatEvent,
  ConnectivityChangeEvent,
  PowerSaveChangeEvent,
  EnabledChangeEvent,
  ScheduleEvent,
  DeviceInfo,
  Sensors,
  PermissionStatus,
  SyncStatus,
  Subscription,
} from './types/Config';

export type {
  Location,
  Coords,
  MotionActivity,
  MotionActivityType,
  Battery,
  CurrentPositionRequest,
  WatchPositionRequest,
} from './types/Location';

export { LocationError } from './types/Location';

export type {
  Geofence,
  GeofenceVertex,
  GeofenceEvent,
  GeofencesChangeEvent,
} from './types/Geofence';
