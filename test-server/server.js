/**
 * Local test server for react-native-open-background-geolocation.
 *
 * Receives the SDK's HTTP uploads (single records or batches), stores them in
 * MongoDB, and serves a live dashboard at http://localhost:3000.
 *
 * Falls back to in-memory storage when MongoDB is not reachable, so the
 * dashboard works even before Mongo is installed.
 */
require('dotenv').config(); // loads MONGODB_URI from test-server/.env
const express = require('express');
const cors = require('cors');
const mongoose = require('mongoose');
const os = require('os');
const path = require('path');

const PORT = process.env.PORT || 3000;
const MONGODB_URI =
  process.env.MONGODB_URI || 'mongodb://127.0.0.1:27017/geolocation_test';

// Schema-less on purpose: store whatever the SDK sends, verbatim.
const locationSchema = new mongoose.Schema({}, { strict: false, timestamps: true });
// Indexes matching the dashboard's access patterns: newest-first listing,
// and per-device filtering/grouping. Without these every query is a
// collection scan once the data grows.
locationSchema.index({ createdAt: -1 });
locationSchema.index({ deviceId: 1, createdAt: -1 });

const LocationRecord = mongoose.model('Location', locationSchema);

/** Circular zones drawn on the dashboard map; evaluated server-side. */
const ZoneRecord = mongoose.model(
  'Zone',
  new mongoose.Schema(
    {
      zoneId: String,
      name: String,
      latitude: Number,
      longitude: Number,
      radius: Number,
    },
    { timestamps: true }
  )
);

let mongoConnected = false;
const memoryStore = []; // fallback when MongoDB is unavailable
let zoneCache = []; // mirrors the zones collection for fast per-upload checks

/** Metres between two coordinates (haversine). */
function distanceMeters(fromLat, fromLon, toLat, toLon) {
  const earthRadius = 6371000;
  const toRadians = (degrees) => (degrees * Math.PI) / 180;
  const deltaLat = toRadians(toLat - fromLat);
  const deltaLon = toRadians(toLon - fromLon);
  const a =
    Math.sin(deltaLat / 2) ** 2 +
    Math.cos(toRadians(fromLat)) * Math.cos(toRadians(toLat)) * Math.sin(deltaLon / 2) ** 2;
  return 2 * earthRadius * Math.asin(Math.sqrt(a));
}

/** Names of the drawn zones containing this coordinate. */
function zonesContaining(latitude, longitude) {
  return zoneCache
    .filter(
      (zone) =>
        distanceMeters(latitude, longitude, zone.latitude, zone.longitude) <= zone.radius
    )
    .map((zone) => zone.name);
}

async function reloadZoneCache() {
  if (!mongoConnected) return;
  try {
    zoneCache = await ZoneRecord.find().lean();
  } catch (error) {
    console.warn(`[zones] reload failed: ${error.message}`);
  }
}

mongoose
  .connect(MONGODB_URI, { serverSelectionTimeoutMS: 10000 })
  .then(() => {
    mongoConnected = true;
    console.log(`[mongo] connected to database "${mongoose.connection.name}"`);
  })
  .catch((error) => {
    console.warn(`[mongo] not reachable (${error.message})`);
    console.warn('[mongo] falling back to in-memory storage — data is lost on restart');
  });

// Track live connection state so the dashboard can show it truthfully, and
// flush anything buffered in memory once the connection returns.
mongoose.connection.on('connected', async () => {
  mongoConnected = true;
  await reloadZoneCache();
  if (memoryStore.length > 0) {
    const buffered = memoryStore.splice(0);
    try {
      await LocationRecord.insertMany(buffered);
      console.log(`[mongo] reconnected — flushed ${buffered.length} buffered record(s)`);
    } catch {
      memoryStore.unshift(...buffered);
    }
  }
});
mongoose.connection.on('disconnected', () => {
  mongoConnected = false;
  console.warn('[mongo] connection lost — buffering to memory until it returns');
});
mongoose.connection.on('error', (error) => {
  console.warn(`[mongo] error: ${error.message}`);
});

// Keep retrying the initial connection — once the Atlas IP allowlist is
// fixed, the server picks the database back up without a restart and flushes
// everything buffered in memory.
setInterval(() => {
  if (!mongoConnected && mongoose.connection.readyState === 0) {
    mongoose.connect(MONGODB_URI, { serverSelectionTimeoutMS: 10000 }).catch(() => {});
  }
}, 30_000);

// A transient DB/network failure must never kill the server.
process.on('unhandledRejection', (reason) => {
  console.warn(`[server] unhandled rejection: ${reason instanceof Error ? reason.message : reason}`);
});
process.on('uncaughtException', (error) => {
  console.warn(`[server] uncaught exception: ${error.message}`);
});

const app = express();
app.use(cors());
app.use(express.json({ limit: '5mb' }));
app.use(express.static(path.join(__dirname, 'public')));

/**
 * Optional shared-secret auth for the /api routes. Set API_KEY when the server
 * is reachable from the public internet — location history is sensitive and an
 * open endpoint lets anyone read your movements or inject fake points.
 * Unset (local LAN testing) means no auth, preserving the simple setup.
 *
 * The key may be sent as an `x-api-key` header or a `?key=` query parameter —
 * the query form lets the mobile app authenticate using only its URL field.
 */
const API_KEY = process.env.API_KEY || '';
app.use('/api', (req, res, next) => {
  if (!API_KEY) return next();
  const provided = req.get('x-api-key') || req.query.key;
  if (provided === API_KEY) return next();
  res.status(401).json({ success: false, error: 'Invalid or missing API key' });
});

/** Normalize the SDK's request body into an array of location records. */
function extractRecords(body) {
  if (!body) return [];
  const payload = body.location ?? body.locations ?? body;
  const records = Array.isArray(payload) ? payload : [payload];
  return records.filter((record) => record && record.coords);
}

/**
 * Multi-device support: the app tags each record via `Config.extras`
 * ({ device_id, device_name }). Promote those to top-level fields so the
 * dashboard can group and filter by device. Records without an identity
 * (older app builds) collect under "unknown".
 */
function withDeviceIdentity(record, body) {
  const extras = record.extras || {};
  const device = body.device || {};
  return {
    ...record,
    // The SDK sends device_id/device_name/device at the body root
    // automatically; extras is honoured as a manual override.
    deviceId: extras.device_id || body.device_id || device.id || 'unknown',
    deviceName:
      extras.device_name || body.device_name || device.name || 'Unknown device',
    devicePlatform: device.platform || null,
    deviceOsVersion: device.os_version || null,
    zones: zonesContaining(record.coords.latitude, record.coords.longitude),
    receivedAt: new Date(),
    // Delivery lag: how long the record sat in the device's SQLite queue
    // before reaching us. Large values mean the device was offline and has
    // just caught up — the clearest signal of a connectivity gap.
    deliveryLagMs: Math.max(0, Date.now() - new Date(record.timestamp).getTime()),
    // Connectivity telemetry the SDK attaches to each upload.
    network: body.network || null,
  };
}

/**
 * Liveness inferred from the last upload.
 *
 * An app cannot report its own uninstall — the OS deletes it before any code
 * could run, and it is excluded from its own package-removed broadcast. What a
 * server *can* do is notice that a device has gone quiet. Combined with the
 * SDK's periodic stationary updates (which report even while parked), silence
 * is a strong signal: the app was uninstalled, force-stopped, denied
 * permission, or the device is off.
 *
 * For a definitive answer, pair this with a push token: send a silent
 * FCM/APNs message and treat `NotRegistered` / HTTP 410 as proof of uninstall.
 * That is the only method Google and Apple sanction.
 */
const LIVENESS_THRESHOLDS_MS = {
  active: 5 * 60_000, // uploading normally
  idle: 60 * 60_000, // quiet, but plausibly stationary or briefly offline
  offline: 7 * 24 * 60 * 60_000, // gone long enough to be a real problem
};

function withLivenessStatus(device) {
  const silentForMs = Date.now() - new Date(device.lastSeen).getTime();
  let status;
  if (silentForMs <= LIVENESS_THRESHOLDS_MS.active) status = 'active';
  else if (silentForMs <= LIVENESS_THRESHOLDS_MS.idle) status = 'idle';
  else if (silentForMs <= LIVENESS_THRESHOLDS_MS.offline) status = 'offline';
  else status = 'likely-removed';

  // Sync health as last reported by the device itself.
  const network = (device.latest && device.latest.network) || null;
  const sync = network
    ? {
        deviceOnline: network.connected,
        queuedRecords: network.queuedRecords || 0,
        lastOfflineDurationMs: network.lastOfflineDurationMs || 0,
        totalOfflineMs: network.totalOfflineMs || 0,
        outageCount: network.outageCount || 0,
        latencyMs: network.lastLatencyMs || 0,
        networkSlow: !!network.networkSlow,
      }
    : null;

  return { ...device, status, silentForMs, sync };
}

/** Track which zones each device was last inside, to emit ENTER/EXIT. */
const lastZonesByDevice = new Map();

function detectZoneTransitions(stamped) {
  const events = [];
  for (const record of stamped) {
    const previous = lastZonesByDevice.get(record.deviceId) || [];
    const current = record.zones || [];
    for (const zoneName of current) {
      if (!previous.includes(zoneName)) {
        events.push({ deviceId: record.deviceId, deviceName: record.deviceName, zone: zoneName, action: 'ENTER' });
      }
    }
    for (const zoneName of previous) {
      if (!current.includes(zoneName)) {
        events.push({ deviceId: record.deviceId, deviceName: record.deviceName, zone: zoneName, action: 'EXIT' });
      }
    }
    lastZonesByDevice.set(record.deviceId, current);
  }
  return events;
}

// ── SDK upload endpoint (point Config.url here) ─────────────────────────────
app.post('/api/locations', async (req, res) => {
  const records = extractRecords(req.body);
  if (records.length === 0) {
    return res.status(422).json({ success: false, error: 'No location records in body' });
  }
  const stamped = records.map((record) => withDeviceIdentity(record, req.body));

  try {
    if (mongoConnected) {
      await LocationRecord.insertMany(stamped);
    } else {
      memoryStore.push(...stamped);
    }
  } catch (error) {
    console.warn(`[mongo] insert failed (${error.message}) — buffering in memory`);
    memoryStore.push(...stamped);
  }
  console.log(
    `[upload] ${records.length} record(s) from "${stamped[0].deviceName}" — ` +
      stamped
        .map((r) => `${r.coords.latitude.toFixed(5)},${r.coords.longitude.toFixed(5)}`)
        .join(' | ')
  );
  broadcast('locations', { locations: stamped }); // push to live dashboards

  const zoneEvents = detectZoneTransitions(stamped);
  for (const event of zoneEvents) {
    console.log(`[zone] ${event.deviceName} ${event.action} "${event.zone}"`);
    broadcast('zone', event);
  }
  res.json({ success: true, received: records.length });
});

// ── Realtime push (Server-Sent Events) ──────────────────────────────────────
// Dashboards subscribe here and receive each location the instant it arrives,
// instead of polling — the same "live pin" behaviour ride-hailing apps show.
const sseClients = new Set();

app.get('/api/stream', (req, res) => {
  res.set({
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
    'X-Accel-Buffering': 'no', // disable proxy buffering (nginx, Render)
  });
  res.flushHeaders();
  res.write('retry: 3000\n\n'); // client auto-reconnects after 3s if dropped

  sseClients.add(res);
  req.on('close', () => sseClients.delete(res));
});

function broadcast(eventName, payload) {
  const message = `event: ${eventName}\ndata: ${JSON.stringify(payload)}\n\n`;
  for (const client of sseClients) {
    try {
      client.write(message);
    } catch {
      sseClients.delete(client);
    }
  }
}

// Keep connections alive through proxies that close idle sockets.
setInterval(() => broadcast('ping', { at: Date.now() }), 25_000);

// ── Dashboard API ───────────────────────────────────────────────────────────
app.get('/api/status', (req, res) => {
  res.json({
    mongoConnected,
    storage: mongoConnected ? 'mongodb' : 'memory',
    database: mongoConnected ? mongoose.connection.name : null,
    host: mongoConnected ? mongoose.connection.host : null,
  });
});

app.get('/api/locations', async (req, res) => {
  const limit = Math.min(parseInt(req.query.limit, 10) || 500, 5000);
  const deviceFilter = req.query.device ? { deviceId: req.query.device } : {};
  try {
    if (mongoConnected) {
      const records = await LocationRecord.find(deviceFilter)
        .sort({ createdAt: -1 })
        .limit(limit)
        .lean();
      return res.json({ storage: 'mongodb', count: records.length, locations: records });
    }
  } catch (error) {
    console.warn(`[mongo] query failed (${error.message}) — serving memory buffer`);
  }
  const records = memoryStore
    .filter((record) => !req.query.device || record.deviceId === req.query.device)
    .slice(-limit)
    .reverse();
  res.json({ storage: 'memory', count: records.length, locations: records });
});

// ── Map matching (snap the track to roads) ──────────────────────────────────
//
// Raw GPS points carry 5–30 m of error, so a polyline joining them cuts across
// buildings and wanders off the carriageway. Map matching runs the trace
// through a routing engine that finds the most probable path along real roads.
//
// Uses the public OSRM demo server: free, open-source, no API key. It is rate
// limited and offers no SLA — for production, self-host OSRM (or use Mapbox /
// Google Roads / GraphHopper) and point OSRM_URL at it.
const OSRM_URL = process.env.OSRM_URL || 'https://router.project-osrm.org';
const OSRM_PROFILE = process.env.OSRM_PROFILE || 'driving';

// The public demo server rejects traces above ~10 coordinates with
// code=TooBig (measured, despite OSRM's documented default of 100). A
// self-hosted instance can go much higher — raise OSRM_MAX_COORDS to 100 then.
const OSRM_MAX_COORDS = parseInt(process.env.OSRM_MAX_COORDS, 10) || 10;
// Cap total requests per match so one long track cannot hammer a shared
// server. 12 chunks ≈ the most recent ~110 points.
const OSRM_MAX_CHUNKS = parseInt(process.env.OSRM_MAX_CHUNKS, 10) || 12;
const OSRM_CHUNK_DELAY_MS = parseInt(process.env.OSRM_CHUNK_DELAY_MS, 10) || 250;

const matchCache = new Map(); // deviceId → { key, geometry, at }
const MATCH_CACHE_MILLIS = 15_000;

/** Split into chunks that overlap by one point so the segments join up. */
function chunkCoordinates(points, size) {
  if (points.length <= size) return [points];
  const chunks = [];
  for (let start = 0; start < points.length - 1; start += size - 1) {
    chunks.push(points.slice(start, start + size));
  }
  return chunks;
}

async function matchChunk(points) {
  const coordinates = points
    .map((p) => `${p.coords.longitude},${p.coords.latitude}`)
    .join(';');
  // Per-point search radius from the reported GPS accuracy: tells OSRM how far
  // it may legitimately move each point to reach a road.
  const radiuses = points
    .map((p) => Math.min(Math.max(Math.round(p.coords.accuracy || 10), 5), 50))
    .join(';');
  const timestamps = points
    .map((p) => Math.floor(new Date(p.timestamp).getTime() / 1000))
    .join(';');

  const url =
    `${OSRM_URL}/match/v1/${OSRM_PROFILE}/${coordinates}` +
    `?geometries=geojson&overview=full&radiuses=${radiuses}&timestamps=${timestamps}`;

  const response = await fetch(url, { signal: AbortSignal.timeout(15000) });
  if (!response.ok) throw new Error(`OSRM responded ${response.status}`);
  const data = await response.json();
  if (data.code !== 'Ok') throw new Error(`OSRM: ${data.code}`);

  // Matchings are [lon, lat]; Leaflet wants [lat, lon].
  return (data.matchings || []).flatMap((matching) =>
    matching.geometry.coordinates.map(([lon, lat]) => [lat, lon])
  );
}

app.get('/api/track/:deviceId/matched', async (req, res) => {
  const { deviceId } = req.params;
  const limit = Math.min(parseInt(req.query.limit, 10) || 300, 1000);

  let records;
  try {
    records = mongoConnected
      ? await LocationRecord.find({ deviceId }).sort({ createdAt: -1 }).limit(limit).lean()
      : memoryStore.filter((r) => r.deviceId === deviceId).slice(-limit).reverse();
  } catch (error) {
    return res.status(500).json({ error: error.message });
  }

  // Oldest-first, and drop low-quality fixes — feeding OSRM 100 m-error points
  // makes the match worse, not better.
  const points = records
    .reverse()
    .filter((r) => r.coords && (r.coords.accuracy ?? 999) <= 50);

  if (points.length < 2) {
    return res.json({ matched: false, reason: 'need at least 2 accurate points', geometry: [] });
  }

  const cacheKey = `${points.length}:${points[points.length - 1].uuid}`;
  const cached = matchCache.get(deviceId);
  if (cached && cached.key === cacheKey && Date.now() - cached.at < MATCH_CACHE_MILLIS) {
    return res.json({ matched: true, cached: true, geometry: cached.geometry });
  }

  try {
    let chunks = chunkCoordinates(points, OSRM_MAX_COORDS);
    // Keep the most recent chunks when a track is long — the live end of the
    // trace is what a dashboard cares about.
    const truncated = chunks.length > OSRM_MAX_CHUNKS;
    if (truncated) chunks = chunks.slice(-OSRM_MAX_CHUNKS);

    const geometry = [];
    for (const [index, chunk] of chunks.entries()) {
      if (index > 0) await new Promise((r) => setTimeout(r, OSRM_CHUNK_DELAY_MS));
      geometry.push(...(await matchChunk(chunk)));
    }
    matchCache.set(deviceId, { key: cacheKey, geometry, at: Date.now() });
    res.json({ matched: true, points: points.length, truncated, geometry });
  } catch (error) {
    console.warn(`[match] ${deviceId}: ${error.message}`);
    // Fall back to the raw trace so the dashboard always has something to draw.
    res.json({
      matched: false,
      reason: error.message,
      geometry: points.map((p) => [p.coords.latitude, p.coords.longitude]),
    });
  }
});

// ── Zones drawn on the dashboard map ────────────────────────────────────────
app.get('/api/zones', async (req, res) => {
  await reloadZoneCache();
  res.json({ zones: zoneCache });
});

app.post('/api/zones', async (req, res) => {
  const { name, latitude, longitude, radius } = req.body || {};
  if (
    typeof latitude !== 'number' ||
    typeof longitude !== 'number' ||
    typeof radius !== 'number' ||
    radius <= 0
  ) {
    return res.status(422).json({ success: false, error: 'latitude, longitude and radius required' });
  }
  const zone = {
    zoneId: `zone-${Date.now()}`,
    name: name || `Zone ${zoneCache.length + 1}`,
    latitude,
    longitude,
    radius,
  };
  if (mongoConnected) {
    await ZoneRecord.create(zone);
    await reloadZoneCache();
  } else {
    zoneCache.push(zone);
  }
  broadcast('zones-changed', { zones: zoneCache });
  res.json({ success: true, zone });
});

app.delete('/api/zones/:zoneId', async (req, res) => {
  if (mongoConnected) {
    await ZoneRecord.deleteOne({ zoneId: req.params.zoneId });
    await reloadZoneCache();
  } else {
    zoneCache = zoneCache.filter((zone) => zone.zoneId !== req.params.zoneId);
  }
  broadcast('zones-changed', { zones: zoneCache });
  res.json({ success: true });
});

/**
 * One row per tracked device: point count, last-seen time, latest position,
 * battery and motion state. Drives the dashboard's device panel.
 */
app.get('/api/devices', async (req, res) => {
  const summarize = (records) => {
    const byDevice = new Map();
    for (const record of records) {
      const key = record.deviceId || 'unknown';
      const existing = byDevice.get(key);
      const receivedAt = new Date(record.receivedAt);
      if (!existing) {
        byDevice.set(key, {
          deviceId: key,
          deviceName: record.deviceName || 'Unknown device',
          count: 1,
          lastSeen: receivedAt,
          latest: record,
        });
      } else {
        existing.count += 1;
        if (receivedAt > existing.lastSeen) {
          existing.lastSeen = receivedAt;
          existing.latest = record;
        }
      }
    }
    return [...byDevice.values()].sort((a, b) => b.lastSeen - a.lastSeen);
  };

  try {
    if (mongoConnected) {
      // Group in the database rather than pulling thousands of documents into
      // Node just to reduce them — constant memory regardless of collection size.
      const devices = await LocationRecord.aggregate([
        { $sort: { createdAt: -1 } },
        {
          $group: {
            _id: '$deviceId',
            deviceName: { $first: '$deviceName' },
            count: { $sum: 1 },
            lastSeen: { $first: '$receivedAt' },
            latest: { $first: '$$ROOT' },
          },
        },
        { $project: { _id: 0, deviceId: '$_id', deviceName: 1, count: 1, lastSeen: 1, latest: 1 } },
        { $sort: { lastSeen: -1 } },
      ]);
      return res.json({ devices: devices.map(withLivenessStatus) });
    }
  } catch (error) {
    console.warn(`[mongo] device query failed (${error.message})`);
  }
  res.json({ devices: summarize(memoryStore).map(withLivenessStatus) });
});

app.delete('/api/locations', async (req, res) => {
  try {
    if (mongoConnected) {
      await LocationRecord.deleteMany({});
    }
  } catch (error) {
    console.warn(`[mongo] delete failed (${error.message})`);
  }
  memoryStore.length = 0;
  console.log('[clear] all records deleted');
  res.json({ success: true });
});

// ── Start ───────────────────────────────────────────────────────────────────
app.listen(PORT, '0.0.0.0', () => {
  console.log('');
  console.log('Background Geolocation test server running.');
  console.log(`  Dashboard:  http://localhost:${PORT}`);
  console.log('  Phone must use your PC\'s LAN address — set the app\'s server URL to one of:');
  for (const addresses of Object.values(os.networkInterfaces())) {
    for (const address of addresses ?? []) {
      if (address.family === 'IPv4' && !address.internal) {
        console.log(`    http://${address.address}:${PORT}/api/locations`);
      }
    }
  }
  console.log('');
});
