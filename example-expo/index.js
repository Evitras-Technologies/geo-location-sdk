import { registerRootComponent } from 'expo';
import BackgroundGeolocation from 'react-native-open-background-geolocation';

import App from './App';

/**
 * Android headless task: receives SDK events fired after the app has been
 * terminated (requires `stopOnTerminate: false, enableHeadless: true`).
 * Must be registered here in the entry file, outside any component.
 */
BackgroundGeolocation.registerHeadlessTask(async (event) => {
  console.log('[BackgroundGeolocation HeadlessTask]', event.name, event.params);
});

registerRootComponent(App);
