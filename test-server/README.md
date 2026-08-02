# Test server + live dashboard

A localhost test rig for the SDK (not part of the SDK itself): an Express
server that receives the app's location uploads, stores them in **MongoDB**,
and shows them live on a map dashboard.

## 1. Run it

```bash
cd test-server
npm install
npm start
```

- Dashboard: **http://localhost:3000**
- MongoDB: connects to `mongodb://127.0.0.1:27017/geolocation_test` by default
  (override with the `MONGODB_URI` env var). If Mongo isn't running, the server
  falls back to in-memory storage so you can still test immediately.

## 2. Point the app at it

Your phone must be on the **same Wi-Fi network** as this PC.

1. The server prints your LAN URL(s) on startup, e.g.
   `http://192.168.1.5:3000/api/locations` (or find your IP with `ipconfig`).
2. In the demo app, paste that URL into the server field and tap **💾 Save**.
   No rebuild needed — the SDK persists it.
3. Start tracking and move around. Every recorded point uploads automatically
   (`autoSync`) and appears on the dashboard within seconds. The `http` events
   in the app's feed show each upload's status code.

If uploads fail with connection errors: check both devices are on the same
network, and allow Node.js through the Windows firewall when prompted (or run
`netsh advfirewall firewall add rule name="BG Geo Test" dir=in action=allow protocol=TCP localport=3000`).

## What you can verify with it

- **At-least-once delivery**: turn on airplane mode while walking — points
  queue in the SDK's SQLite; when connectivity returns they upload in order.
- **Background uploads**: swipe the app away (with `stopOnTerminate: false`)
  and watch points keep arriving.
- **Motion state machine**: the table's state column shows 🛑/🚶 transitions
  and the `motionchange` events.
- **Odometer & battery**: reported with every record.
