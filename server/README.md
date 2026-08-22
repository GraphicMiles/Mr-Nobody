# Update server

The service behind Mr Nobody's update notifications. It has one job:
serve the release metadata the app checks when it opens.

```
Open Mr Nobody
      ↓
GET https://<service>.onrender.com/update.json
      ↓
{ latestVersion, releaseNotes, downloadUrl, required, sha256, signature, publishedAt }
```

The app compares `latestVersion` with the installed version and, when a
newer release is published, surfaces it in **Settings → App → Updates**.
The response is metadata only — this service never serves executable code,
and the app never auto-downloads or injects anything. Installing an update
goes through Android's normal signed package installer.

## What runs here

- `GET /` — the landing page (`website/index.html`)
- `GET /update.json` — the update endpoint (served from `website/update.json`,
  `Cache-Control: no-cache` so a new release is visible on the next launch)
- `GET /health` — health check
- `GET /<file>` — other static files under `website/` (fonts, images)

Python 3 standard library only — no dependencies to install.

## Local run

```bash
python3 server/app.py            # http://localhost:8080
PORT=9000 python3 server/app.py  # custom port
```

Then:

```bash
curl -s localhost:8080/health
curl -s localhost:8080/update.json
```

## Deploying to Render

1. Create the service from the blueprint:
   ```bash
   render deploy            # or: open render.com → New → Blueprint → this repo
   ```
   The blueprint (`render.yaml` at the repo root) starts a Python 3.12 web
   service running `python3 server/app.py` with a `/health` health check.
   No terminal needed: the same works from the Render dashboard
   (New → Blueprint → pick the repo), since the blueprint is at the root.
2. Note the public URL, e.g. `https://mrnobody-updates.onrender.com`.
3. Point the app at it — there is exactly one place to change:
   `UpdateChecker.UPDATE_URL` in
   `app/android/app/src/main/java/com/mrnobody/browser/update/UpdateChecker.java`.
4. Commit `website/update.json` (see below) — it is the single source of
   truth for both the landing page's "Release metadata" card and the app.

Free-tier services sleep after inactivity; the app's check tolerates a slow
first response (6 s read timeout) and falls back to the last cached check,
so a cold start is not a failure case.

## Cutting a release

1. Bump the version in `app/pubspec.yaml` and build the signed release APK
   (`.github/workflows/release-apk.yml` shows the signing setup).
2. Publish the metadata:
   ```bash
   python3 tools/publish_update.py \
       --version 1.1.0 \
       --notes "What changed in this release." \
       --apk path/to/app-release.apk \
       --url https://<where-you-host-the-apk>/mr-nobody-1.1.0.apk
   ```
   The script validates the version, computes the APK's `sha256`, and
   rewrites `website/update.json`. `--required` marks the release required
   (the app nudges persistently but never blocks — see the brief).
   Until real signing metadata is settled, `signature` stays a
   placeholder; fill it with the apksigner cert digest:
   ```bash
   apksigner verify --print-certs app-release.apk
   # → "Signer #1 certificate SHA-256 digest: <hex>"
   ```
3. Host the APK wherever distribution is decided (see the README's
   "Update distribution" note) and make sure `downloadUrl` points there.
4. Commit `website/update.json` and push. Both the landing page and every
   app that opens next will pick the release up on their next check.

## What deliberately is not here

- No account/login system, no telemetry, no download tracking.
- No APK hosting logic in this service (APK hosting is a distribution
  decision, kept separate on purpose).
- No way to run code from the server: the contract is a fixed JSON schema
  that the app validates (HTTPS URL, semver, well-formed checksum) and
  treats anything else as "no update".
