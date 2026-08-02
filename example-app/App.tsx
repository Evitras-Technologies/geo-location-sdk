/**
 * react-native-open-background-geolocation — full-featured demo.
 *
 * Demonstrates:
 *  - ready() configuration with battery-conscious defaults
 *  - start / stop, changePace, getCurrentPosition
 *  - live event feed (location, motionchange, activitychange, geofence, http…)
 *  - geofence add / remove
 *  - odometer, database count, sync & destroy
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  FlatList,
  Pressable,
  SafeAreaView,
  StatusBar,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import BackgroundGeolocation, {
  DeviceSettings,
  Location,
  Subscription,
} from 'react-native-open-background-geolocation';

interface FeedEntry {
  id: string;
  eventName: string;
  summary: string;
  receivedAt: string;
}

const DEMO_GEOFENCE = {
  identifier: 'demo-office',
  radius: 200,
  latitude: 37.33182,
  longitude: -122.03118,
  notifyOnEntry: true,
  notifyOnExit: true,
  extras: { placeName: 'Demo Office' },
};

let feedEntryCounter = 0;

// Device identity is handled entirely by the SDK — it generates a stable id on
// first run and attaches it to every upload automatically. Apps only read it.

export default function App(): React.JSX.Element {
  const [isReady, setIsReady] = useState(false);
  const [isTrackingEnabled, setIsTrackingEnabled] = useState(false);
  const [isMoving, setIsMoving] = useState(false);
  const [odometerKm, setOdometerKm] = useState('0.00');
  const [currentPosition, setCurrentPosition] = useState<Location | null>(null);
  const [eventFeed, setEventFeed] = useState<FeedEntry[]>([]);
  const [serverUrl, setServerUrl] = useState('');
  const [deviceName, setDeviceName] = useState('');
  const [isRealtimeMode, setIsRealtimeMode] = useState(false);
  const subscriptionsRef = useRef<Subscription[]>([]);

  const appendToFeed = useCallback((eventName: string, summary: string) => {
    feedEntryCounter += 1;
    setEventFeed((previousFeed) =>
      [
        {
          id: String(feedEntryCounter),
          eventName,
          summary,
          receivedAt: new Date().toLocaleTimeString(),
        },
        ...previousFeed,
      ].slice(0, 60)
    );
  }, []);

  useEffect(() => {
    // 1. Register listeners BEFORE ready() so no events are missed.
    subscriptionsRef.current = [
      BackgroundGeolocation.onLocation(
        (location) => {
          setCurrentPosition(location);
          setOdometerKm((location.odometer / 1000).toFixed(2));
          appendToFeed(
            'location',
            `${location.coords.latitude.toFixed(5)}, ${location.coords.longitude.toFixed(5)} ±${Math.round(location.coords.accuracy)}m`
          );
        },
        (errorCode) => appendToFeed('location-error', `code ${errorCode}`)
      ),
      BackgroundGeolocation.onMotionChange((event) => {
        setIsMoving(event.isMoving);
        appendToFeed('motionchange', event.isMoving ? '🚶 moving' : '🛑 stationary');
      }),
      BackgroundGeolocation.onActivityChange((event) =>
        appendToFeed('activitychange', `${event.activity} (${event.confidence}%)`)
      ),
      BackgroundGeolocation.onProviderChange((event) =>
        appendToFeed('providerchange', `status ${event.status}, gps: ${event.gps}`)
      ),
      BackgroundGeolocation.onGeofence((event) =>
        appendToFeed('geofence', `${event.action} → ${event.identifier}`)
      ),
      BackgroundGeolocation.onHeartbeat(() => appendToFeed('heartbeat', '💓')),
      BackgroundGeolocation.onHttp((event) =>
        appendToFeed('http', `HTTP ${event.status} success=${event.success}`)
      ),
      BackgroundGeolocation.onConnectivityChange((event) =>
        appendToFeed('connectivity', event.connected ? 'online' : 'offline')
      ),
      BackgroundGeolocation.onPowerSaveChange((isPowerSaveEnabled) =>
        appendToFeed('powersave', isPowerSaveEnabled ? 'enabled' : 'disabled')
      ),
      BackgroundGeolocation.onEnabledChange((enabled) => {
        setIsTrackingEnabled(enabled);
        appendToFeed('enabledchange', enabled ? 'tracking started' : 'tracking stopped');
      }),
    ];

    // 2. Configure the SDK. `ready()` remembers config across restarts.
    BackgroundGeolocation.ready({
      // Let the SDK request every permission it needs at launch: location,
      // background location (with the required disclosure), GPS enable and the
      // battery-optimization whitelist.
      autoRequestPermissions: true,
      desiredAccuracy: BackgroundGeolocation.DESIRED_ACCURACY_HIGH,
      distanceFilter: 10,
      stopTimeout: 5,
      // Indoors or with a weak sky view, Android falls back to network
      // positioning: ±100–200 m fixes that jump hundreds of metres between
      // samples. Those read as high-speed travel and inflate the odometer, so
      // discard them rather than record fiction.
      minimumAccuracy: 50,
      // Keep reporting a position every 12s even while parked/stationary,
      // instead of going silent.
      stationaryUpdateInterval: 12,
      notification: {
        title: 'Tracking active',
        text: 'Recording your route',
        // MIN is the least intrusive Android allows for a foreground service:
        // silent, and the status-bar icon is hidden on most devices. Android
        // requires the notification itself to exist while tracking.
        priority: BackgroundGeolocation.NOTIFICATION_PRIORITY_MIN,
      },
      stopOnTerminate: false,
      startOnBoot: true,
      enableHeadless: true,
      debug: false, // set true for audible life-cycle tones while developing
      logLevel: BackgroundGeolocation.LOG_LEVEL_VERBOSE,
      // Point at your own server to see automatic uploads:
      // url: 'https://your.server.com/locations',
      // headers: { Authorization: 'Bearer …' },
      backgroundPermissionRationale: {
        title: 'Allow background location?',
        message: 'This demo tracks your route even while the app is closed.',
        positiveAction: 'Change to Allow all the time',
        negativeAction: 'Cancel',
      },
    }).then(async (state) => {
      setIsReady(true);
      setIsTrackingEnabled(state.enabled);
      setServerUrl(state.url ?? '');
      appendToFeed('ready', `enabled=${state.enabled}, mode=${state.trackingMode}`);

      // The SDK already tags uploads with this device's identity; just show it.
      const deviceInfo = await BackgroundGeolocation.getDeviceInfo();
      setDeviceName(`${deviceInfo.manufacturer} ${deviceInfo.model}`);
      appendToFeed('device', `id: ${deviceInfo.deviceId}`);

      // Permissions are handled entirely by the SDK via
      // `autoRequestPermissions` below — just report the outcome.
      const permissions = await BackgroundGeolocation.getPermissionStatus();
      appendToFeed(
        'permissions',
        permissions.readyForBackgroundTracking
          ? 'all granted ✅'
          : `missing: ${[
              !permissions.location && 'location',
              !permissions.backgroundLocation && 'background',
              !permissions.locationServicesEnabled && 'GPS off',
              !permissions.batteryOptimizationDisabled && 'battery-opt',
            ]
              .filter(Boolean)
              .join(', ')} ⚠️`
      );

      // Without this permission the SDK has no activity signal, so motion
      // detection falls back to GPS speed alone — which is unreliable, since
      // a stationary phone reports phantom speed.
      if (!permissions.activityRecognition) {
        appendToFeed('activity', 'Physical activity DENIED — motion detection degraded ⚠️');
      }
    });

    return () => {
      subscriptionsRef.current.forEach((subscription) => subscription.remove());
    };
  }, [appendToFeed]);

  const handleToggleTracking = async (shouldEnable: boolean) => {
    if (shouldEnable) {
      await BackgroundGeolocation.start();
    } else {
      await BackgroundGeolocation.stop();
    }
  };

  const handleGetCurrentPosition = async () => {
    try {
      const location = await BackgroundGeolocation.getCurrentPosition({
        timeout: 30000,
        persist: true,
        extras: { requestedManually: true },
      });
      Alert.alert(
        'Current position',
        `${location.coords.latitude.toFixed(6)}, ${location.coords.longitude.toFixed(6)}\naccuracy ±${Math.round(location.coords.accuracy)}m`
      );
    } catch (error) {
      Alert.alert(
        'Location error',
        error instanceof Error ? error.message : String(error)
      );
    }
  };

  /** Point the SDK at your test server (see test-server/) — persists across restarts. */
  const handleSaveServerUrl = async () => {
    const url = serverUrl.trim();
    if (!url) {
      Alert.alert('Server URL', 'Enter your server URL first, e.g.\nhttp://192.168.1.5:3000/api/locations');
      return;
    }
    await BackgroundGeolocation.setConfig({ url, autoSync: true });
    appendToFeed('config', `upload url set: ${url}`);
    Alert.alert('Server configured', `Locations will now upload to:\n${url}`);
  };

  const handleChangePace = () => BackgroundGeolocation.changePace(!isMoving);

  /**
   * Realtime (ride-hailing) mode: stream a fix every second regardless of
   * distance and never power down on stillness — what Uber/Swiggy do during an
   * active trip. Costs far more battery, so it is a deliberate toggle rather
   * than the default.
   */
  const handleToggleRealtime = async (enableRealtime: boolean) => {
    setIsRealtimeMode(enableRealtime);
    if (enableRealtime) {
      await BackgroundGeolocation.setConfig({
        distanceFilter: 0, // every fix, not every N metres
        locationUpdateInterval: 1000,
        fastestLocationUpdateInterval: 1000,
        disableStopDetection: true, // never drop back to stationary
        disableElasticity: true, // no speed-based thinning
        allowIdenticalLocations: true,
        // Sample at 1 Hz but upload in small batches. One request per second
        // keeps the cellular radio permanently awake, which is the main cause
        // of heat and drain in continuous tracking — the radio also stays in a
        // high-power state for seconds after each transmission. Batching every
        // ~4 fixes cuts radio wake-ups ~4x while keeping the map within a few
        // seconds of live. This is what ride-hailing apps do during a trip.
        autoSync: true,
        autoSyncThreshold: 4,
        batchSync: true,
        maxBatchSize: 50,
        desiredAccuracy: BackgroundGeolocation.DESIRED_ACCURACY_HIGH,
      });
      // Engage immediately — no waiting for motion detection.
      await BackgroundGeolocation.changePace(true);
      appendToFeed('mode', '⚡ realtime streaming (1s, high battery)');
    } else {
      await BackgroundGeolocation.setConfig({
        distanceFilter: 10,
        locationUpdateInterval: 1000,
        fastestLocationUpdateInterval: 10000,
        disableStopDetection: false,
        disableElasticity: false,
        allowIdenticalLocations: false,
        autoSyncThreshold: 0,
        batchSync: false,
        stationaryUpdateInterval: 12,
      });
      appendToFeed('mode', '🔋 battery-saving · 12s updates while stationary');
    }
  };

  const handleAddGeofence = async () => {
    await BackgroundGeolocation.addGeofence(DEMO_GEOFENCE);
    appendToFeed('geofence-add', `added "${DEMO_GEOFENCE.identifier}" r=${DEMO_GEOFENCE.radius}m`);
  };

  const handleShowDatabase = async () => {
    const count = await BackgroundGeolocation.getCount();
    Alert.alert('SDK database', `${count} location(s) pending upload`, [
      { text: 'OK' },
      {
        text: 'Destroy all',
        style: 'destructive',
        onPress: async () => {
          await BackgroundGeolocation.destroyLocations();
          appendToFeed('database', 'destroyed all locations');
        },
      },
    ]);
  };

  const handleResetOdometer = async () => {
    await BackgroundGeolocation.resetOdometer();
    setOdometerKm('0.00');
  };

  /**
   * Battery-optimization whitelist: essential on Xiaomi / Vivo / Oppo /
   * Realme / Samsung — their battery managers kill background apps that are
   * not whitelisted.
   */
  const handleBatteryWhitelist = async () => {
    const alreadyWhitelisted = await DeviceSettings.isIgnoringBatteryOptimizations();
    if (alreadyWhitelisted) {
      Alert.alert('Battery optimization', 'Already whitelisted — tracking is protected. ✅');
      return;
    }
    await DeviceSettings.showIgnoreBatteryOptimizations();
  };

  /** OEM auto-start manager (MIUI Security app, ColorOS startup manager, …). */
  const handleAutoStartSettings = async () => {
    try {
      await DeviceSettings.showPowerManager();
    } catch {
      Alert.alert(
        'Auto-start manager',
        'This device has no vendor auto-start screen — no action needed.'
      );
    }
  };

  return (
    <SafeAreaView style={styles.screen}>
      <StatusBar barStyle="light-content" />
      <View style={styles.header}>
        <View>
          <Text style={styles.headerTitle}>Background Geolocation</Text>
          {deviceName !== '' && <Text style={styles.headerSubtitle}>{deviceName}</Text>}
        </View>
        <Switch
          value={isTrackingEnabled}
          disabled={!isReady}
          onValueChange={handleToggleTracking}
        />
      </View>

      <View style={styles.realtimeRow}>
        <View>
          <Text style={styles.realtimeLabel}>⚡ Realtime mode</Text>
          <Text style={styles.realtimeHint}>
            {isRealtimeMode ? 'Streaming every 1s · high battery' : 'Battery-saving motion detection'}
          </Text>
        </View>
        <Switch value={isRealtimeMode} disabled={!isReady} onValueChange={handleToggleRealtime} />
      </View>

      <View style={styles.statusCard}>
        <StatusItem label="State" value={isTrackingEnabled ? (isMoving ? 'Moving' : 'Stationary') : 'Off'} />
        <StatusItem label="Odometer" value={`${odometerKm} km`} />
        <StatusItem
          label="Position"
          value={
            currentPosition
              ? `${currentPosition.coords.latitude.toFixed(4)}, ${currentPosition.coords.longitude.toFixed(4)}`
              : '—'
          }
        />
      </View>

      <View style={styles.actionsRow}>
        <ActionButton label="📍 Locate" onPress={handleGetCurrentPosition} />
        <ActionButton
          label={isMoving ? '🛑 Stationary' : '🚶 Moving'}
          onPress={handleChangePace}
          disabled={!isTrackingEnabled}
        />
        <ActionButton label="⭕ Geofence" onPress={handleAddGeofence} />
      </View>
      <View style={styles.actionsRow}>
        <ActionButton label="🗄 Database" onPress={handleShowDatabase} />
        <ActionButton label="🔄 Reset odo" onPress={handleResetOdometer} />
      </View>
      <View style={styles.actionsRow}>
        <ActionButton label="🔋 Battery whitelist" onPress={handleBatteryWhitelist} />
        <ActionButton label="🚀 Auto-start" onPress={handleAutoStartSettings} />
      </View>

      <View style={styles.serverRow}>
        <TextInput
          style={styles.serverInput}
          value={serverUrl}
          onChangeText={setServerUrl}
          placeholder="http://<your-pc-ip>:3000/api/locations"
          placeholderTextColor="#475569"
          autoCapitalize="none"
          autoCorrect={false}
          keyboardType="url"
        />
        <ActionButton label="💾 Save" onPress={handleSaveServerUrl} />
      </View>

      <Text style={styles.feedTitle}>Event feed</Text>
      <FlatList
        style={styles.feed}
        data={eventFeed}
        keyExtractor={(entry) => entry.id}
        renderItem={({ item }) => (
          <View style={styles.feedRow}>
            <Text style={styles.feedTime}>{item.receivedAt}</Text>
            <Text style={styles.feedEvent}>{item.eventName}</Text>
            <Text style={styles.feedSummary} numberOfLines={1}>
              {item.summary}
            </Text>
          </View>
        )}
        ListEmptyComponent={
          <Text style={styles.feedEmpty}>
            Toggle the switch to start tracking. Events will appear here.
          </Text>
        }
      />
    </SafeAreaView>
  );
}

function StatusItem({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.statusItem}>
      <Text style={styles.statusLabel}>{label}</Text>
      <Text style={styles.statusValue}>{value}</Text>
    </View>
  );
}

function ActionButton({
  label,
  onPress,
  disabled,
}: {
  label: string;
  onPress: () => void;
  disabled?: boolean;
}) {
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      style={({ pressed }) => [
        styles.actionButton,
        disabled && styles.actionButtonDisabled,
        pressed && styles.actionButtonPressed,
      ]}
    >
      <Text style={styles.actionButtonLabel}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: '#0f172a' },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 20,
    paddingVertical: 16,
  },
  headerTitle: { color: '#f8fafc', fontSize: 20, fontWeight: '700' },
  headerSubtitle: { color: '#64748b', fontSize: 11, marginTop: 2 },
  realtimeRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginHorizontal: 16,
    marginBottom: 10,
    padding: 12,
    borderRadius: 12,
    backgroundColor: '#1e293b',
  },
  realtimeLabel: { color: '#f8fafc', fontSize: 14, fontWeight: '600' },
  realtimeHint: { color: '#94a3b8', fontSize: 11, marginTop: 2 },
  statusCard: {
    flexDirection: 'row',
    marginHorizontal: 16,
    borderRadius: 12,
    backgroundColor: '#1e293b',
    paddingVertical: 14,
  },
  statusItem: { flex: 1, alignItems: 'center' },
  statusLabel: { color: '#94a3b8', fontSize: 12, marginBottom: 4 },
  statusValue: { color: '#f8fafc', fontSize: 14, fontWeight: '600' },
  actionsRow: {
    flexDirection: 'row',
    marginHorizontal: 12,
    marginTop: 10,
  },
  serverRow: {
    flexDirection: 'row',
    marginHorizontal: 12,
    marginTop: 10,
    alignItems: 'center',
  },
  serverInput: {
    flex: 2.5,
    marginHorizontal: 4,
    borderRadius: 10,
    backgroundColor: '#1e293b',
    color: '#f8fafc',
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 12,
  },
  actionButton: {
    flex: 1,
    marginHorizontal: 4,
    borderRadius: 10,
    backgroundColor: '#2563eb',
    paddingVertical: 12,
    alignItems: 'center',
  },
  actionButtonDisabled: { backgroundColor: '#334155' },
  actionButtonPressed: { opacity: 0.7 },
  actionButtonLabel: { color: '#f8fafc', fontSize: 13, fontWeight: '600' },
  feedTitle: {
    color: '#94a3b8',
    fontSize: 12,
    fontWeight: '700',
    textTransform: 'uppercase',
    marginTop: 18,
    marginBottom: 6,
    marginHorizontal: 20,
  },
  feed: { flex: 1, marginHorizontal: 16 },
  feedRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 7,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#1e293b',
  },
  feedTime: { color: '#64748b', fontSize: 11, width: 78 },
  feedEvent: { color: '#38bdf8', fontSize: 12, fontWeight: '600', width: 110 },
  feedSummary: { color: '#e2e8f0', fontSize: 12, flex: 1 },
  feedEmpty: { color: '#64748b', fontSize: 13, textAlign: 'center', marginTop: 32 },
});
