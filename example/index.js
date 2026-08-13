import { AppRegistry } from 'react-native';
import BackgroundGeolocation from '@evitras-technologies/react-native-background-geolocation';

import App from './App';
import { name as appName } from './app.json';

/**
 * Android headless task: receives SDK events fired after the app has been
 * terminated (requires `stopOnTerminate: false, enableHeadless: true`).
 * Must be registered here in the entry file, outside any component.
 */
BackgroundGeolocation.registerHeadlessTask(async (event) => {
  console.log('[BackgroundGeolocation HeadlessTask]', event.name, event.params);

  switch (event.name) {
    case 'location':
      // Persisted + uploaded natively already; do any custom work here.
      break;
    case 'geofence':
      break;
    case 'heartbeat':
      break;
    default:
      break;
  }
});

AppRegistry.registerComponent(appName, () => App);
