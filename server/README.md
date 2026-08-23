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

One command publishes the APK to GitHub Releases and rewrites the metadata
and every site download link so the two can never drift:

```bash
# 1. bump the version in app/pubspec.yaml, then build the signed APK
#    (signing env vars required — see .github/workflows/release-apk.yml):
cd app && flutter build apk --release

# 2. publish (run from the repo root):
python3 tools/publish_update.py \
    --version 1.1.0 \
    --notes "What changed in this release." \
    --apk app/build/app/outputs/flutter-apk/app-release.apk
```

That single command:

  - creates the GitHub Release `v1.1.0` and uploads the APK (via `gh`),
  - derives the canonical `downloadUrl` itself,
  - fills `sha256` and the signing-cert digest (via `apksigner`),
  - rewrites `website/update.json` **and** the download button in every
    site page (`index.html`, `desktop.html`, `mobile.html`).

The repo is detected from `git remote` — override with `--repo owner/name`.
`--dry-run` previews without changing anything. `--required` marks the
release required (the app nudges persistently but never blocks). Use
`--no-latest` to avoid marking it the "latest" release, and `--notes-file
NOTES.md` for long notes.

Then commit and push:

```bash
git add website/
git commit -m "release v1.1.0"
git push
```

`--apk` triggers the GitHub Releases flow; no `--url` is needed. Pass
`--url <https>` instead of `--apk` for a metadata-only publish when the
APK is already hosted somewhere else.

## What deliberately is not here

- No account/login system, no telemetry, no download tracking.
- No APK hosting logic in this service (APK hosting is a distribution
  decision, kept separate on purpose).
- No way to run code from the server: the contract is a fixed JSON schema
  that the app validates (HTTPS URL, semver, well-formed checksum) and
  treats anything else as "no update".
