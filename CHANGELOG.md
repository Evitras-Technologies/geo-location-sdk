# Changelog

All notable changes to this project are documented here.

This project follows [Semantic Versioning](https://semver.org/). Documentation
under [docs/](docs/) is updated alongside every change listed below.

## [Unreleased]

### Added
- **`locationTemplate`: upload a record in the server's own shape.** A JSON
  object written as text, with `<%= latitude %>`-style tags. With one set, the
  request body is that object and `params` only: no device identity and no
  network report, which a server that validates its input refuses. Until now
  such a server could only be reached by posting from JavaScript, which is
  the one part of tracking that does not survive the screen being off on
  phones that hold background apps back.
- **`autoSyncInterval`: at most one automatic upload per interval.** Fixes can
  be taken often (for a fresh position and steady accuracy) while the server
  is sent one no more often than it wants; the upload carries the newest fix.
  A change of motion is uploaded regardless.

### Fixed
- **A 401 no longer empties the queue.** Any 4xx other than 408 and 429
  dropped the records it was answered for, 401 among them. With no
  `authorization.refreshUrl` configured (a host that renews its own token and
  hands it over with `setConfig`), the first upload after the token ran out
  therefore threw away everything queued, one batch per request. The records
  now stay, the upload is retried with backoff, and `sync()` sends them as
  soon as the host has set the new token.
- **`minimumAccuracy` holds for every stored location.** The one-off fix of
  a motion change or a stationary check bypassed it and could be uploaded at
  any accuracy; it is still handed to listeners, but no longer stored.
- **`maxRecordsToPersist` is kept to on every write.** It was enforced once
  every few minutes, so a queue meant to hold only the newest position could
  upload older ones first.
- **GPS no longer stays on when activity recognition is silent.** The `still`
  activity was the only trigger for the stop timeout, so with the
  physical-activity permission denied, or the signal throttled by the OEM, the
  SDK never left the moving state — full-power GPS through every halt. A
  second timer, re-armed only by real displacement, now returns to stationary
  `stopTimeout` minutes after the device last actually moved.
- **A stop is no longer undone by the previous stop's position.** On going
  stationary, the passive drift watch started before the new parked position
  was recorded, so it measured the device against wherever it had last been
  parked — typically kilometres back — and switched straight to moving again.
  From the second stop of any journey, the GPS therefore never turned off. The
  parked position is now cleared on every state change and set by the
  transition fix.
- **No GPS fix on every service restart while stationary.** The watchdog tick
  (every 15 min) and each `ready()` re-ran the stationary transition — a GPS
  fix and a `motionchange` event each time — because the check for "already
  engaged" looked at the location callback, which is off while stationary.
- **`location.age` is immune to a wrong device clock.** It was the device's
  wall clock minus the fix's (satellite) time, so a mis-set clock made every
  fix look minutes old. It is now measured on the monotonic clock.
- **Builds on React Native 0.80+.** RN 0.80 moved several Android classes to
  Kotlin, which broke compilation: the inherited `currentActivity` property is
  gone (now read from the React context), `PermissionListener` takes
  `Array<String>`, and `ReadableArray.getMap/getArray` return nullable types.
  All three changes also compile against older React Native.
- **New-Architecture apps build.** `package.json` declared a `codegenConfig`,
  but the module is a bridge module with no TurboModule spec and its Gradle
  file never runs codegen. Autolinking still pointed the app's CMake at the
  codegen output, which never existed, failing every New-Architecture build.
  The declaration is removed; the module keeps working through the interop
  layer.
- **Events reach JS on the New Architecture.** Android looked up the React
  context through `reactNativeHost.reactInstanceManager`, which in a bridgeless
  app is a second, never-started instance — so `onLocation` and every other
  listener stayed silent with the app open. The context now comes from
  `ReactHost` first, and a legacy instance is only read when one exists.
- **The background-location disclosure is shown only when it is needed.**
  `requestAllPermissions()` (and `autoRequestPermissions`) showed the prominent
  disclosure and sent the user to Settings on every call, even when "Allow all
  the time" was already granted.
- **Uploads from JS no longer stall in the background.** React Native stops
  JS timers once the app leaves the foreground unless a headless task is
  running, and `fetch` resolves through `setTimeout(0)` — so a listener's
  request completed on the wire but its promise never settled, and anything
  waiting on it stalled for good. With `enableHeadless: true`, events are now
  delivered to the headless task whenever the app is not in the foreground,
  even while its JS is still alive, which keeps timers running for as long as
  the task's work takes. **Behaviour change:** in that state listeners such as
  `onLocation` no longer fire; register the same handling in the headless task.
- **No replay of stale events after the process is killed.** The headless
  service inherited React Native's `START_REDELIVER_INTENT`, so a restarted
  process received every earlier event again in one burst — locations minutes
  old, delivered as if new. It now returns `START_NOT_STICKY`.
- **A missed departure is caught within one stationary interval.** While
  parked, the fix taken every `stationaryUpdateInterval` seconds was reported
  but never compared with the parked position, so when activity recognition,
  the significant-motion sensor and the stationary geofence all missed a
  departure (throttled on some OEMs, absent on emulators) tracking stayed in
  the stationary state for good. That fix now runs the same drift check as the
  passive watch and resumes moving tracking.
- **No more all-day wake lock.** The tracking service held a partial wake lock
  for its whole life (up to 24 h), so a parked device never let its CPU sleep.
  The lock is now held only in bounded spans: renewed by each fix while moving
  (lapsing 10 min after the last one), for 15 s around each event delivered to
  JS, and released when the device goes stationary.

### Added
- **SDK-managed permission onboarding** — `autoRequestPermissions: true` makes
  `ready()` run the whole sequence (foreground location → prominent disclosure
  → background location → GPS enable prompt → battery-optimization exemption),
  so host apps implement none of it.
- `requestAllPermissions()` and `getPermissionStatus()`, including a single
  `readyForBackgroundTracking` flag that is true only when every prerequisite
  for reliable background tracking is satisfied.
- `requestLocationServices()` — shows the system "Turn on location?" dialog
  when device GPS is off (Android).
- **Automatic device identity.** The SDK generates a stable per-installation id
  and attaches `device_id`, `device_name` and a `device` object to every HTTP
  upload. Exposed via `getDeviceId()`; also returned by `getDeviceInfo()`.
- **`stationaryUpdateInterval`** — record, persist and upload a fresh fix every
  N seconds while parked instead of going silent. Runs on AlarmManager so it
  survives Doze.
- **WakeLock** held by the tracking service, keeping the CPU alive when the
  screen is off.
- **OEM-survival hardening**: 15-minute watchdog that restarts a killed
  service, service resurrection via geofence/activity broadcasts, direct
  battery-optimization dialog, `isIgnoringBatteryOptimizations()`, and a
  15-entry OEM auto-start table (Xiaomi, Vivo, Oppo/Realme, Samsung,
  Transsion, OnePlus, Huawei, Asus, Meizu) with the Android 11+ `<queries>`
  declarations required to resolve them.
- **Four independent movement signals** so tracking cannot get stranded in the
  stationary state: hardware significant-motion sensor, Activity Transition
  API, passive location watch, stationary geofence.
- Multi-sample `getCurrentPosition` — collects up to `samples` fixes, resolves
  early on `desiredAccuracy`, returns the most accurate; honours `maximumAge`.
- Prominent-disclosure dialog before requesting background location, as
  required by Google Play policy; customizable via
  `backgroundPermissionRationale`.
- HTTP retry with exponential backoff (5 s → 5 min) and non-retryable 4xx
  handling so one bad record cannot block the queue.
- Config validation: all numeric options clamped to safe ranges.
- Complete documentation set under [docs/](docs/).

### Fixed
- **Tracking notification played a sound on MIUI/Redmi.** `IMPORTANCE_LOW` is
  silent on stock Android but some skins still play the default tone unless
  sound, vibration and lights are disabled explicitly on the channel. The
  channel is now created fully silent, the builder sets `setSilent(true)` for
  older devices, and the default channel id moved to `open_bg_geo_silent` with
  the legacy channel deleted on upgrade — Android channels are immutable once
  created, so a new id is the only way to apply the change to existing
  installs.
- **Odometer inflated while stationary.** Only fix *accuracy* was checked, so
  every periodic stationary fix added its GPS drift to the total — roughly
  50 m accumulated with the device sitting on a table. Distance now requires
  the SDK to be in the moving state, and any step shorter than the position
  uncertainty is discarded as noise (a 5 m step on a ±15 m fix is
  indistinguishable from jitter). Covered by regression tests.
- **`notification.priority` was ignored** — the channel was hardcoded to
  `IMPORTANCE_LOW`. It now maps to the channel importance, so
  `NOTIFICATION_PRIORITY_MIN` gives `IMPORTANCE_MIN`: the least intrusive
  notification Android permits for a foreground service, hiding the persistent
  status-bar icon on most devices. The default channel id now encodes its
  importance so changing `priority` recreates the channel rather than silently
  keeping the old behaviour (channels are immutable once created).
- **Crash on Android 6** — `registerDefaultNetworkCallback` (API 24) was
  reachable at `minSdk 23`; now version-guarded with a fallback.
- **Play policy violation** — stationary alarms used
  `setExactAndAllowWhileIdle`, which requires `SCHEDULE_EXACT_ALARM`, a
  permission Play restricts to clock/calendar apps. Switched to the inexact
  `setAndAllowWhileIdle`.
- **Crash on Android 14** when starting the location foreground service before
  location permission was granted.
- **Fabricated activity data** — the SDK reported `still` at 100 % confidence
  before activity recognition had produced any reading, masking devices where
  it never fires. Now `unknown`/0 until a real sample arrives.
- **Infinite upload loop** when a record uploaded successfully but failed to
  delete from the queue.
- Permission requests missing from `getCurrentPosition()`, `setOdometer()` and
  the `ready()` auto-resume path.
- Two activity-recognition subscriptions shared one `PendingIntent`, so the
  second silently superseded the first.
- Continuous mode (`disableStopDetection: true`) did not engage tracking
  without a manual `changePace(true)`.
- `setConfig()` changes were ignored while stationary until the next motion
  transition.
- Identical-coordinate filtering discarded updates in realtime mode
  (`distanceFilter: 0`).
- Unguarded exceptions in location callbacks, persistence and event dispatch
  could crash the host app.
- `types` field in `package.json` pointed at a non-existent path, breaking
  TypeScript consumers.

### Added (feature parity)
- **Polygon geofencing — free.** Define geofences as arbitrary shapes
  (`vertices: [[lat, lng], …]`) for parks, building footprints or delivery
  zones. Neither Android nor iOS can monitor polygons natively, so the SDK
  registers the polygon's bounding circle with the OS — preserving native
  wake-ups while the app is terminated — then decides exact containment with a
  ray-casting point-in-polygon test, re-evaluated on every recorded location.
  Handles concave shapes and any vertex count. Covered by 11 unit tests.
- **`minimumAccuracy`** location filter: discard fixes worse than N metres
  before they reach the database, odometer or your server.
- **JWT / bearer authorization with automatic refresh.** Sends
  `Authorization: Bearer <token>` on every upload; on a `401` it posts to
  `refreshUrl`, persists the new token and replays the request, so an expired
  token never stalls the queue.

### Added (testing)
- **59 unit tests, all passing** (`gradlew test`), across four suites:
  - `PolygonGeometryTest` (11) — containment including concave shapes and
    near-edge cases, vertex parsing, centroid, area.
  - `ConfigValidatorTest` (20) — every clamping rule, disable-vs-floor
    semantics for `heartbeatInterval` / `stationaryUpdateInterval`, HTTP
    method fallback, and non-numeric input.
  - `LocationFiltersTest` (18) — accuracy gate, duplicate suppression
    (including the realtime-mode exemption), GPS-teleport rejection,
    divide-by-zero safety, elasticity bands, odometer eligibility.
  - `RequestBodyBuilderTest` (10) — upload body shape, `"."` root flattening,
    batch arrays, device identity, `params` precedence, token-refresh
    placeholder substitution.

### Changed (architecture)
- Extracted the SDK's decision logic into pure, dependency-free objects —
  `ConfigValidator`, `LocationFilters` and `RequestBodyBuilder` — and wired the
  existing classes to call them. Previously this logic was private inside
  classes requiring a live Android `Context`, so none of it was testable.

### Added (test server / dashboard)
- **Map matching — "Snap to roads".** Raw GPS polylines cut across buildings
  and drift off the carriageway; the dashboard can now snap a device's track to
  the real road network via OSRM. Measured on a 2.3 km route with ~20 m of
  injected noise: mean distance from the true road fell from **8.1 m to 0.3 m**.
  Low-accuracy fixes (>50 m) are filtered before matching, results are cached
  for 15 s, and the raw trace is dimmed rather than hidden so corrections stay
  visible. Falls back to the raw line (drawn dashed) when matching fails.
  Configure via `OSRM_URL`, `OSRM_PROFILE`, `OSRM_MAX_COORDS`,
  `OSRM_MAX_CHUNKS`. **Note:** the public OSRM demo server rejects traces above
  ~10 coordinates and is rate limited — self-host OSRM for production use.

### Performance
- **SQLite indexes** on `locations.recorded_at` and `logs.logged_at` (schema
  migration included). Every upload query orders by `recorded_at`; without an
  index SQLite full-scanned and sorted the whole table per recorded location.
- **Shared, pooled `OkHttpClient`.** A new client was built per request,
  discarding OkHttp's connection pool so every upload paid a fresh TCP + TLS
  handshake — once per second in realtime mode. Also reduces radio energy.
- **Battery state cached for 30 s** instead of a synchronous system binder
  call on every recorded location.
- **Retention pruning every 5 minutes** rather than a `DELETE`-with-subquery
  on every insert.
- **Removed `COUNT(*)` from the upload hot path** — it is O(n) in SQLite and
  served only as a runaway-loop guard, now a constant.
- **Test server**: `/api/devices` uses a MongoDB aggregation instead of
  loading up to 5000 documents into Node, plus indexes on `createdAt` and
  `{ deviceId, createdAt }`.

### Changed
- Uploads now always carry device identity (see Added).
- `getCurrentPosition()` and permission methods reject with readable messages
  ("Location permission denied") instead of bare numeric codes.

## [1.0.0]

Initial implementation: motion-detection state machine, background tracking on
Android (foreground service) and iOS (background modes), geofencing with
proximity swapping beyond OS limits, SQLite persistence with retention rules,
HTTP sync with at-least-once delivery, headless Android tasks, boot resume,
odometer, persistent logging, Expo config plugin, TypeScript API, and CLI +
Expo example apps.

### Fixed (offline sync)
- **Queue did not flush when the network returned.** Locations were always
  persisted to SQLite, so nothing was lost — but each failed upload during an
  outage escalated the retry backoff up to 5 minutes, and the reconnect handler
  called lush() *without* bypassing it. The queue therefore sat idle long
  after connectivity was restored. Reconnect now forces a flush.
- **Outage duration was mis-measured** when it began at epoch 0, because a
  `offlineSinceMillis > 0` sentinel treated a legitimate timestamp as "unset";
  and a backwards wall-clock jump (NTP sync) could yield a negative duration.
  Both found by unit tests and fixed.

### Added (network awareness)
- **`getSyncStatus()`** — connected state, current and last outage duration,
  cumulative offline time, outage count, queued-record count, last and average
  upload latency, and a `networkSlow` flag distinguishing a degraded link from
  no link.
- `onConnectivityChange` now carries `offlineDurationMs` and `queuedRecords`.
- Every upload includes the same telemetry under a `network` key, and the
  server records a per-record `deliveryLagMs`.
- Uploads are skipped entirely while known-offline, avoiding pointless radio
  wake-ups during an outage.
- Admin panel shows per-device offline state, queue depth, latency, outage
  totals, and tags late-delivered records.
- 14 unit tests for the connectivity accounting.
