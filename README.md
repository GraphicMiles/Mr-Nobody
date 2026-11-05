# Mr Nobody

An Android privacy browser with a task agent built in. The browser and the agent
share the same privacy controls, the same download engine and the same network
routing, so a job the agent runs is blocked, filtered and cleaned exactly like a
page you opened yourself.

MIT licensed.

## Why it exists

Privacy browsers and AI agents are usually two apps with two threat models. The
browser strips trackers and the agent quietly fetches the page anyway. Mr Nobody
puts them on one path: if the browser would block it, the agent cannot fetch it,
and if the agent wants to do something consequential, it asks first.

## What you can do

**Browse privately**

- Use the WebView already on the device. Nothing extra to ship or trust.
- Block known advertising and tracking requests locally, with filter rules in
  `filters/`.
- Block cross-site redirects into known gambling, pop-up and ad-heavy
  link-shortener destinations.
- Strip common tracking parameters from links.
- Keep no history by default, with third-party cookies off.

**Give the agent a job**

- Search the web and return parsed results.
- Read source pages and produce a cited answer, ranked by what the question wants
  (a figure, a person, a definition) rather than dumped as text.
- Fall back to rendered WebView extraction when a plain read is not enough.
- Download files through the app's own download engine.
- Resolve a landing page like `film.mkv.html` and fetch the real file, so it never
  saves an HTML page where a video was asked for.
- Run recurring checks through Android WorkManager.
- Pause for approval before a consequential action, and resume after process death
  without repeating an effect it already committed.

## What is not here

- The agent is deterministic code. It is not an on-device language model, and it
  should not be described as one: it searches, extracts, ranks and verifies.
- No account, no sync, no telemetry.
- Agent tools and model providers require HTTPS. Cleartext is only ever used for
  browsing or a download the user explicitly approved, after a warning.
- Android only.

## How it is built

```
app/            the Flutter app shell and platform integration
filters/        blocklists and redirect rules
server/         the backend the app talks to
tools/          build and maintenance scripts
website/        the project site
CODE-AUDIT.md   audit notes
ROADMAP.md      what is done and what is next
```

### Build

The app is a Flutter project in `app/`:

```bash
cd app
flutter build apk --release
```

Three workflows run in `.github/`: `flutter.yml` for the build checks,
`android-emulator.yml` for device-level runs and `release-apk.yml` which produces
the installable APK.

## Licence

MIT. See `LICENSE`.
