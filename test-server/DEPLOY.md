# Testing while travelling — make the server reachable from anywhere

On your LAN the phone can only upload while it is on the same Wi-Fi. To test on
the road (mobile data, any city), the server needs a public URL. Two ways —
pick one.

Neither needs a new APK: paste the new URL into the app's server field and tap
💾 Save.

---

## Option A — Deploy to the cloud (recommended, permanent, free tier)

Best for real travel testing: an always-on HTTPS URL that works even when your
PC is off.

1. Push this project to GitHub.
2. Go to [render.com](https://render.com) → **New → Web Service** → connect the
   repo. It reads [render.yaml](render.yaml) automatically (or set manually:
   root directory `test-server`, build `npm install`, start `node server.js`).
3. In **Environment**, add:
   - `MONGODB_URI` — your Atlas connection string (same one as `.env`)
   - `API_KEY` — any long random string, e.g. `k7Fp2xQ9mZ4vR8nT`
4. Deploy. You get a URL like `https://bg-geo.onrender.com`.
5. In MongoDB Atlas → **Network Access**, allow `0.0.0.0/0` (cloud hosts do not
   have fixed IPs).

**Use it:**

- App server field → `https://bg-geo.onrender.com/api/locations?key=YOUR_API_KEY`
- Dashboard → `https://bg-geo.onrender.com/?key=YOUR_API_KEY`

Both work from anywhere in the world. Note: Render's free tier sleeps after
~15 minutes idle and takes ~30 s to wake — the SDK simply retries, and queued
points upload once it is awake.

---

## Option B — Tunnel your local server (instant, temporary)

Best for a quick test right now, while your PC stays on.

```bash
# in a second terminal, with the server already running on :3000
npx ngrok http 3000
```

ngrok prints a public HTTPS URL like `https://a1b2-49-37-x-x.ngrok-free.app`.

- App server field → `https://a1b2-49-37-x-x.ngrok-free.app/api/locations`
- Dashboard → the same URL in your browser

No firewall rule needed — the tunnel dials out from your PC. The URL changes
each time you restart ngrok (free tier), and it dies when your PC sleeps.

---

## Security note

Once the server is public, **always set `API_KEY`**. Without it, anyone who
guesses the URL can read your location history or inject fake points. With it
set, every `/api/*` request must carry the key (`?key=…` or an `x-api-key`
header) — the dashboard remembers it in localStorage after the first visit.

For anything beyond testing, add real user authentication and per-device
identity instead of a single shared key.
