/**
 * react-native-open-background-geolocation — Expo demo.
 *
 * Requires a development build (`npx expo run:android` / `run:ios`) — the SDK
 * contains native code and cannot run inside Expo Go.
 */
import { StatusBar } from 'expo-status-bar';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  FlatList,
  Pressable,
  SafeAreaView,
  StyleSheet,
  Switch,
  Text,
  View,
} from 'react-native';
import BackgroundGeolocation, {
  Location,
  Subscription,
} from 'react-native-open-background-geolocation';

interface FeedEntry {
  id: string;
  eventName: string;
  summary: string;
  receivedAt: string;
}

let feedEntryCounter = 0;

export default function App(): React.JSX.Element {
  const [isReady, setIsReady] = useState(false);
  const [isTrackingEnabled, setIsTrackingEnabled] = useState(false);
  const [isMoving, setIsMoving] = useState(false);
  const [lastLocation, setLastLocation] = useState<Location | null>(null);
  const [eventFeed, setEventFeed] = useState<FeedEntry[]>([]);
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
    // Register listeners BEFORE ready() so no events are missed.
    subscriptionsRef.current = [
      BackgroundGeolocation.onLocation((location) => {
        setLastLocation(location);
        appendToFeed(
          'location',
          `${location.coords.latitude.toFixed(5)}, ${location.coords.longitude.toFixed(5)} ±${Math.round(location.coords.accuracy)}m`
        );
      }),
      BackgroundGeolocation.onMotionChange((event) => {
        setIsMoving(event.isMoving);
        appendToFeed('motionchange', event.isMoving ? '🚶 moving' : '🛑 stationary');
      }),
      BackgroundGeolocation.onActivityChange((event) =>
        appendToFeed('activitychange', `${event.activity} (${event.confidence}%)`)
      ),
      BackgroundGeolocation.onGeofence((event) =>
        appendToFeed('geofence', `${event.action} → ${event.identifier}`)
      ),
      BackgroundGeolocation.onHttp((event) =>
        appendToFeed('http', `HTTP ${event.status} success=${event.success}`)
      ),
      BackgroundGeolocation.onEnabledChange((enabled) => {
        setIsTrackingEnabled(enabled);
        appendToFeed('enabledchange', enabled ? 'tracking started' : 'tracking stopped');
      }),
    ];

    BackgroundGeolocation.ready({
      desiredAccuracy: BackgroundGeolocation.DESIRED_ACCURACY_HIGH,
      distanceFilter: 10,
      stopTimeout: 5,
      stopOnTerminate: false,
      startOnBoot: true,
      enableHeadless: true,
      debug: false, // set true for audible life-cycle tones while developing
    }).then((state) => {
      setIsReady(true);
      setIsTrackingEnabled(state.enabled);
      appendToFeed('ready', `enabled=${state.enabled}`);
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

  const handleLocate = async () => {
    try {
      const location = await BackgroundGeolocation.getCurrentPosition({ timeout: 30000 });
      Alert.alert(
        'Current position',
        `${location.coords.latitude.toFixed(6)}, ${location.coords.longitude.toFixed(6)}`
      );
    } catch (errorCode) {
      Alert.alert('Location error', `Error code: ${String(errorCode)}`);
    }
  };

  return (
    <SafeAreaView style={styles.screen}>
      <StatusBar style="light" />
      <View style={styles.header}>
        <Text style={styles.headerTitle}>BG Geolocation (Expo)</Text>
        <Switch
          value={isTrackingEnabled}
          disabled={!isReady}
          onValueChange={handleToggleTracking}
        />
      </View>

      <View style={styles.statusCard}>
        <Text style={styles.statusText}>
          {isTrackingEnabled ? (isMoving ? '🚶 Moving' : '🛑 Stationary') : '⏸ Tracking off'}
        </Text>
        <Text style={styles.statusSubtext}>
          {lastLocation
            ? `${lastLocation.coords.latitude.toFixed(4)}, ${lastLocation.coords.longitude.toFixed(4)} · odo ${(lastLocation.odometer / 1000).toFixed(2)} km`
            : 'No location yet'}
        </Text>
      </View>

      <View style={styles.actionsRow}>
        <Pressable style={styles.actionButton} onPress={handleLocate}>
          <Text style={styles.actionButtonLabel}>📍 Where am I?</Text>
        </Pressable>
        <Pressable
          style={[styles.actionButton, !isTrackingEnabled && styles.actionButtonDisabled]}
          disabled={!isTrackingEnabled}
          onPress={() => BackgroundGeolocation.changePace(!isMoving)}
        >
          <Text style={styles.actionButtonLabel}>
            {isMoving ? '🛑 Go stationary' : '🚶 Go moving'}
          </Text>
        </Pressable>
      </View>

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

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: '#0f172a' },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 20,
    paddingVertical: 16,
    marginTop: 24,
  },
  headerTitle: { color: '#f8fafc', fontSize: 20, fontWeight: '700' },
  statusCard: {
    marginHorizontal: 16,
    borderRadius: 12,
    backgroundColor: '#1e293b',
    padding: 16,
    alignItems: 'center',
  },
  statusText: { color: '#f8fafc', fontSize: 18, fontWeight: '700' },
  statusSubtext: { color: '#94a3b8', fontSize: 13, marginTop: 6 },
  actionsRow: { flexDirection: 'row', marginHorizontal: 12, marginTop: 12 },
  actionButton: {
    flex: 1,
    marginHorizontal: 4,
    borderRadius: 10,
    backgroundColor: '#2563eb',
    paddingVertical: 12,
    alignItems: 'center',
  },
  actionButtonDisabled: { backgroundColor: '#334155' },
  actionButtonLabel: { color: '#f8fafc', fontSize: 14, fontWeight: '600' },
  feed: { flex: 1, marginHorizontal: 16, marginTop: 16 },
  feedRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 7,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#1e293b',
  },
  feedTime: { color: '#64748b', fontSize: 11, width: 78 },
  feedEvent: { color: '#38bdf8', fontSize: 12, fontWeight: '600', width: 105 },
  feedSummary: { color: '#e2e8f0', fontSize: 12, flex: 1 },
  feedEmpty: { color: '#64748b', fontSize: 13, textAlign: 'center', marginTop: 32 },
});
