# Update & Metrics Server

The service behind Mr Nobody's update notifications and anonymous active device counting.

```
Open Mr Nobody
      ↓
GET https://<service>.onrender.com/update.json?id=<anon_uuid>&v=1.0.7
      ↓
Records anonymous device ping in local SQLite metrics.db
      ↓
Returns { latestVersion, releaseNotes, downloadUrl, required, sha256, signature, publishedAt }
```

The app compares `latestVersion` with the installed version and, when a
newer release is published, surfaces it in **Settings → App → Updates**.
The response is metadata only — this service never serves executable code,
and the app never auto-downloads or injects anything. Installing an update
goes through Android's normal signed package installer.

## What runs here

- `GET /` — the landing page (`website/index.html`)
- `GET /update.json` — the update endpoint (served from `website/update.json`,
  `Cache-Control: no-cache` so a new release is visible on the next launch;
  also records anonymous device ping in `metrics.db`)
- `GET /stats` — live HTML dashboard of active devices (DAU, WAU, MAU, version breakdown)
- `GET /stats.json` — machine-readable JSON metrics of active device counts
- `GET /health` — health check
- `GET /<file>` — other static files under `website/` (fonts, images)

Python 3 standard library only — zero third-party dependencies to install.

## Device counting & Privacy

- When the app checks for updates, it passes an anonymous, locally generated UUID (`install_id`)
  and installed app version.
- The server computes a SHA-256 hash of the ID and records it in an embedded SQLite database (`metrics.db`).
- **Zero personal data**, hardware identifiers (no IMEI, no MAC address, no Android ID), or GPS/location data are ever collected or stored.
- Legacy clients (prior versions without an install ID) are anonymized and counted via hashed connection attributes so past installs are still represented in total counts.

## Local run

```bash
python3 server/app.py            # http://localhost:8080
PORT=9000 python3 server/app.py  # custom port
```

Then:

```bash
curl -s localhost:8080/health
curl -s "localhost:8080/update.json?id=test-device-1&v=1.0.7"
curl -s localhost:8080/stats.json
# Or open http://localhost:8080/stats in a browser
```

## Deploying to Render

1. Create the service from the blueprint:
   ```bash
   render deploy            # or: open render.com → New → Blueprint → this repo
   ```
   The blueprint (`render.yaml` at the repo root) starts a Python 3.12 web
   service running `python3 server/app.py` with a `/health` health check.
2. Note the public URL, e.g. `https://mrnobody-updates.onrender.com`.
3. Point the app at it — there is exactly one place to change:
   `UpdateChecker.UPDATE_URL` in
   `app/android/app/src/main/java/com/mrnobody/browser/update/UpdateChecker.java`.
4. Check your live user counts anytime at `https://<service>.onrender.com/stats`.

## Cutting a release

One command publishes the APK to GitHub Releases and rewrites the metadata
and every site download link so the two can never drift:

```bash
# 1. bump the version in app/pubspec.yaml, then build the signed APK:
cd app && flutter build apk --release

# 2. publish (run from the repo root):
python3 tools/publish_update.py \
    --version 1.1.0 \
    --notes "What changed in this release." \
    --apk app/build/app/outputs/flutter-apk/app-release.apk
```
